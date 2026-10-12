package com.quantumobile.launcher.ui

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.quantumobile.launcher.QuantumApp
import com.quantumobile.launcher.R
import com.quantumobile.launcher.core.auth.Account
import com.quantumobile.launcher.core.auth.AccountType
import com.quantumobile.launcher.core.auth.MicrosoftAuth
import com.quantumobile.launcher.databinding.FragmentListBinding
import kotlinx.coroutines.launch

class AccountsFragment : Fragment() {
    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private val store get() = (requireActivity().application as QuantumApp).accounts

    private val msLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val code = result.data?.getStringExtra(MicrosoftLoginActivity.EXTRA_CODE)
        if (result.resultCode == Activity.RESULT_OK && code != null) finishMicrosoftLogin(code)
    }

    private val adapter = SimpleListAdapter(
        onClick = { row -> store.select(row.id); refresh() },
        onLongClick = { row -> confirmDelete(row) },
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.empty.setText(R.string.accounts_empty)
        binding.primaryAction.setText(R.string.add_microsoft)
        binding.secondaryAction.setText(R.string.add_offline)
        binding.primaryAction.setOnClickListener { msLogin.launch(android.content.Intent(requireContext(), MicrosoftLoginActivity::class.java)) }
        binding.secondaryAction.setOnClickListener { addOffline() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                store.accounts.collect { refresh() }
            }
        }
    }

    private fun refresh() {
        val accounts = store.accounts.value
        val selected = store.selected?.id
        binding.empty.isVisible = accounts.isEmpty()
        adapter.submit(accounts.map {
            Row(
                it.id, it.username,
                getString(if (it.type == AccountType.MICROSOFT) R.string.account_microsoft else R.string.account_offline),
                it.id == selected,
            )
        })
    }

    private fun addOffline() {
        val input = EditText(requireContext()).apply { hint = getString(R.string.player_name) ; isSingleLine = true }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.add_offline)
            .setView(input.withPadding())
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (Account.isValidOfflineName(name)) store.upsert(Account.offline(name))
                else Toast.makeText(requireContext(), R.string.invalid_name, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun finishMicrosoftLogin(code: String) {
        val dialog = AlertDialog.Builder(requireContext())
            .setMessage(R.string.signing_in)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            try {
                store.upsert(MicrosoftAuth.loginWithCode(code))
            } catch (e: Exception) {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.login_failed)
                    .setMessage(e.message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } finally {
                dialog.dismiss()
            }
        }
    }

    private fun confirmDelete(row: Row) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.confirm_remove, row.title))
            .setPositiveButton(R.string.remove) { _, _ -> store.remove(row.id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

internal fun View.withPadding(): View {
    val pad = (20 * resources.displayMetrics.density).toInt()
    return android.widget.FrameLayout(context).also { frame ->
        frame.setPadding(pad, pad / 2, pad, 0)
        frame.addView(this)
    }
}
