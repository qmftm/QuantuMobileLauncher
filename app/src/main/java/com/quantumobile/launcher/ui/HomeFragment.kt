package com.quantumobile.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.quantumobile.launcher.QuantumApp
import com.quantumobile.launcher.R
import com.quantumobile.launcher.core.auth.AccountType
import com.quantumobile.launcher.databinding.FragmentHomeBinding
import com.quantumobile.launcher.game.GameActivity
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val vm: LaunchViewModel by activityViewModels()
    private val app get() = requireActivity().application as QuantumApp

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.play.setOnClickListener { play() }
        binding.cancel.setOnClickListener { vm.cancel() }
        binding.viewLog.setOnClickListener { startActivity(Intent(requireContext(), LogActivity::class.java)) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val account = app.accounts.selected
        binding.accountName.text = account?.username ?: getString(R.string.no_account)
        binding.accountType.text = when (account?.type) {
            AccountType.MICROSOFT -> getString(R.string.account_microsoft)
            AccountType.OFFLINE -> getString(R.string.account_offline)
            null -> getString(R.string.add_account_hint)
        }
        val instance = app.instances.selected
        binding.instanceName.text = instance?.name ?: getString(R.string.no_instance)
        binding.instanceVersion.text = instance?.versionId ?: getString(R.string.create_instance_hint)
        val runtimes = app.runtimes.list()
        binding.runtimeInfo.text = if (runtimes.isEmpty()) getString(R.string.no_runtime)
        else getString(R.string.runtimes_installed, runtimes.joinToString { "Java ${it.majorVersion}" })
    }

    private fun play() {
        val instance = app.instances.selected
        when {
            app.accounts.selected == null -> toast(R.string.add_account_hint)
            instance == null -> toast(R.string.create_instance_hint)
            else -> {
                val metrics = resources.displayMetrics
                vm.start(instance, maxOf(metrics.widthPixels, metrics.heightPixels), minOf(metrics.widthPixels, metrics.heightPixels))
            }
        }
    }

    private fun render(state: LaunchState) {
        val working = state is LaunchState.Working
        binding.play.isEnabled = !working
        binding.progressGroup.isVisible = working
        when (state) {
            is LaunchState.Working -> {
                binding.status.text = getString(R.string.progress_format, state.stage, state.done, state.total)
                binding.progress.isIndeterminate = state.total <= 1
                if (state.total > 1) binding.progress.setProgressCompat(state.done * 100 / state.total, true)
            }
            is LaunchState.Ready -> {
                vm.consumed()
                startActivity(GameActivity.intent(requireContext(), state.launch))
            }
            is LaunchState.Failed -> {
                vm.consumed()
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.launch_failed)
                    .setMessage(state.message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            LaunchState.Idle -> Unit
        }
    }

    private fun toast(res: Int) = android.widget.Toast.makeText(requireContext(), res, android.widget.Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
