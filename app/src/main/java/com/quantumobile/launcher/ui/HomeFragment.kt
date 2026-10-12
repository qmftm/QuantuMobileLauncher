package com.quantumobile.launcher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.quantumobile.launcher.BuildConfig
import com.quantumobile.launcher.QuantumApp
import com.quantumobile.launcher.R
import com.quantumobile.launcher.core.LaunchStages
import com.quantumobile.launcher.core.LaunchState
import com.quantumobile.launcher.core.auth.AccountType
import com.quantumobile.launcher.core.instance.Instance
import com.quantumobile.launcher.databinding.FragmentHomeBinding
import com.quantumobile.launcher.databinding.ItemSetupStepBinding
import com.quantumobile.launcher.game.GameActivity
import kotlinx.coroutines.launch
import java.util.Calendar

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
        binding.gameFolder.setOnClickListener { showGameFolder() }
        binding.accountCard.setOnClickListener { pickAccount() }
        binding.instanceCard.setOnClickListener { pickInstance() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val account = app.accounts.selected
        binding.greeting.text = getString(greetingRes(), account?.username ?: getString(R.string.player))
        binding.heroSubtitle.text = getString(R.string.hero_subtitle, BuildConfig.VERSION_NAME)

        binding.accountName.text = account?.username ?: getString(R.string.no_account)
        binding.accountType.text = when (account?.type) {
            AccountType.MICROSOFT -> getString(R.string.account_microsoft)
            AccountType.OFFLINE -> getString(R.string.account_offline)
            null -> getString(R.string.tap_to_add)
        }
        AvatarLoader.load(binding.accountAvatar, account, viewLifecycleOwner.lifecycleScope)

        val instance = app.instances.selected
        binding.instanceName.text = instance?.name ?: getString(R.string.no_instance)
        binding.instanceVersion.text = instance?.let(::describe) ?: getString(R.string.tap_to_add)

        val runtimes = app.runtimes.list()
        binding.runtimeInfo.text = if (runtimes.isEmpty()) getString(R.string.no_runtime)
        else getString(R.string.runtimes_installed, runtimes.joinToString { "Java ${it.majorVersion}" })

        renderSetup()
    }

    private fun describe(instance: Instance): String {
        val played = if (instance.lastPlayed > 0)
            DateUtils.getRelativeTimeSpanString(instance.lastPlayed, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        else getString(R.string.never_played)
        return "${instance.versionId} · $played"
    }

    private fun greetingRes(): Int = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> R.string.greeting_morning
        in 12..17 -> R.string.greeting_afternoon
        else -> R.string.greeting_evening
    }

    /** Checklist of what is still missing before the game can start, each with a shortcut. */
    private fun renderSetup() {
        data class Step(val done: Boolean, val label: Int, val action: Int, val run: () -> Unit)
        val steps = listOf(
            Step(app.accounts.selected != null, R.string.setup_account, R.string.add) { pickAccount() },
            Step(app.instances.selected != null, R.string.setup_instance, R.string.create) { navigate(R.id.nav_instances) },
            Step(app.runtimes.list().isNotEmpty(), R.string.setup_runtime, R.string.download) { RuntimeInstallUi.show(this) { refresh() } },
            Step(app.isLwjglInstalled() || app.paths.lwjgl.listFiles()?.isNotEmpty() == true, R.string.setup_lwjgl, R.string.install) { installLwjgl() },
        )
        binding.setupCard.isVisible = steps.any { !it.done }
        binding.setupSteps.removeAllViews()
        steps.forEach { step ->
            val row = ItemSetupStepBinding.inflate(layoutInflater, binding.setupSteps, true)
            row.label.setText(step.label)
            setIcon(row, if (step.done) R.drawable.ic_check_circle else R.drawable.ic_warning, if (step.done) R.color.accent else R.color.warning)
            row.label.alpha = if (step.done) 0.6f else 1f
            row.action.isVisible = !step.done
            row.action.setText(step.action)
            row.action.setOnClickListener { step.run() }
        }
    }

    /** Starts the download in the background; the checklist refreshes when it finishes. */
    private fun installLwjgl() {
        snack(R.string.installing_lwjgl)
        app.installLwjgl { result ->
            if (_binding == null) return@installLwjgl
            result.onSuccess { snack(R.string.lwjgl_done); refresh() }
                .onFailure { e ->
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.lwjgl_failed)
                        .setMessage(e.message).setPositiveButton(android.R.string.ok, null).show()
                }
        }
    }

    private fun setIcon(row: ItemSetupStepBinding, @DrawableRes icon: Int, color: Int) {
        row.icon.setImageResource(icon)
        row.icon.setColorFilter(requireContext().getColor(color))
    }

    private fun navigate(itemId: Int) = (activity as? MainActivity)?.navigateTo(itemId)

    private fun pickAccount() {
        val accounts = app.accounts.accounts.value
        if (accounts.isEmpty()) return navigate(R.id.nav_accounts).let { }
        val selected = app.accounts.selected?.id
        val rows = accounts.map {
            Row(it.id, it.username, getString(if (it.type == AccountType.MICROSOFT) R.string.account_microsoft else R.string.account_offline),
                selected = it.id == selected, avatar = it)
        } + Row(ADD_ID, getString(R.string.manage_accounts), "", icon = R.drawable.ic_add, iconTint = requireContext().getColor(R.color.brand))
        PickerSheet.show(this, getString(R.string.switch_account), rows, searchable = false) { row ->
            if (row.id == ADD_ID) navigate(R.id.nav_accounts) else { app.accounts.select(row.id); refresh() }
        }
    }

    private fun pickInstance() {
        val instances = app.instances.sorted
        if (instances.isEmpty()) return navigate(R.id.nav_instances).let { }
        val selected = app.instances.selected?.id
        val rows = instances.map {
            Row(it.id, it.name, describe(it), selected = it.id == selected, badge = loaderBadge(it.versionId))
        } + Row(ADD_ID, getString(R.string.new_instance), "", icon = R.drawable.ic_add, iconTint = requireContext().getColor(R.color.brand))
        PickerSheet.show(this, getString(R.string.switch_instance), rows) { row ->
            if (row.id == ADD_ID) navigate(R.id.nav_instances) else { app.instances.select(row.id); refresh() }
        }
    }

    private fun showGameFolder() {
        val instance = app.instances.selected
        val path = (instance?.gameDir(app.paths) ?: app.paths.gameRoot).absolutePath
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.game_folder)
            .setMessage(getString(R.string.game_folder_hint, path))
            .setPositiveButton(R.string.copy_action) { _, _ ->
                requireContext().getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("path", path))
                Snackbar.make(binding.root, R.string.path_copied, Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun play() {
        val instance = app.instances.selected
        when {
            app.accounts.selected == null -> { snack(R.string.add_account_hint); pickAccount() }
            instance == null -> { snack(R.string.create_instance_hint); navigate(R.id.nav_instances) }
            else -> {
                val metrics = resources.displayMetrics
                vm.start(instance, maxOf(metrics.widthPixels, metrics.heightPixels), minOf(metrics.widthPixels, metrics.heightPixels))
            }
        }
    }

    private fun render(state: LaunchState) {
        val working = state is LaunchState.Working
        binding.play.isEnabled = !working
        binding.play.setText(if (working) R.string.preparing else R.string.play)
        binding.progressGroup.isVisible = working
        when (state) {
            is LaunchState.Working -> {
                binding.status.text = stageLabel(state.stage)
                val determinate = state.total > 1
                binding.progress.isIndeterminate = !determinate
                binding.percent.isVisible = determinate
                if (determinate) {
                    val pct = state.done * 100 / state.total
                    binding.progress.setProgressCompat(pct, true)
                    binding.percent.text = getString(R.string.percent_format, pct, state.done, state.total)
                }
            }
            is LaunchState.Ready -> {
                vm.consumed()
                app.instances.selected?.let { app.instances.markPlayed(it.id) }
                startActivity(GameActivity.intent(requireContext(), state.launch))
            }
            is LaunchState.Failed -> {
                vm.consumed()
                val dialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.launch_failed)
                    .setMessage(state.message)
                if (state.missingJava != null) {
                    dialog.setPositiveButton(getString(R.string.get_java, state.missingJava)) { _, _ ->
                        RuntimeInstallUi.show(this, state.missingJava) { refresh() }
                    }.setNegativeButton(android.R.string.cancel, null)
                } else {
                    dialog.setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.game_log) { _, _ -> startActivity(Intent(requireContext(), LogActivity::class.java)) }
                }
                dialog.show()
            }
            LaunchState.Idle -> Unit
        }
    }

    private fun stageLabel(stage: String): String = getString(LaunchStages.label(stage))

    private fun snack(res: Int) = Snackbar.make(binding.root, res, Snackbar.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ADD_ID = "__add__"

        fun loaderBadge(versionId: String): String? = when {
            versionId.startsWith("fabric-loader") -> "Fabric"
            versionId.contains("forge", ignoreCase = true) -> "Forge"
            versionId.contains("quilt", ignoreCase = true) -> "Quilt"
            else -> null
        }
    }
}
