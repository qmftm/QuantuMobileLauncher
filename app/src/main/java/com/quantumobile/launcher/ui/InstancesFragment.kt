package com.quantumobile.launcher.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.quantumobile.launcher.QuantumApp
import com.quantumobile.launcher.R
import com.quantumobile.launcher.core.instance.Instance
import com.quantumobile.launcher.core.mc.FabricInstaller
import com.quantumobile.launcher.databinding.DialogNewInstanceBinding
import com.quantumobile.launcher.databinding.FragmentListBinding
import kotlinx.coroutines.launch

class InstancesFragment : Fragment() {
    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private val app get() = requireActivity().application as QuantumApp
    private val vm: LaunchViewModel by activityViewModels()
    private lateinit var adapter: SimpleListAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = SimpleListAdapter(
            viewLifecycleOwner.lifecycleScope,
            onClick = { row -> app.instances.select(row.id); refresh() },
            onMore = ::showMenu,
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.emptyIcon.setImageResource(R.drawable.ic_layers)
        binding.emptyText.setText(R.string.instances_empty)
        binding.primaryAction.setText(R.string.new_instance)
        binding.secondaryAction.isVisible = false
        binding.primaryAction.setOnClickListener { newInstance() }
        binding.list.addOnScrollListener(shrinkFabsOnScroll(binding))

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.instances.instances.collect { refresh() }
            }
        }
    }

    private fun refresh() {
        val list = app.instances.sorted
        val selected = app.instances.selected?.id
        binding.empty.isVisible = list.isEmpty()
        adapter.submitList(list.map { Row(it.id, it.name, subtitle(it), it.id == selected, HomeFragment.loaderBadge(it.versionId)) })
    }

    private fun subtitle(i: Instance): String {
        val played = if (i.lastPlayed > 0)
            DateUtils.getRelativeTimeSpanString(i.lastPlayed, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        else getString(R.string.never_played)
        val memory = if (i.memoryMb > 0) " · ${i.memoryMb} MB" else ""
        return "${i.versionId} · $played$memory"
    }

    private fun instance(id: String) = app.instances.instances.value.firstOrNull { it.id == id }

    private fun showMenu(row: Row, anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 1, 0, R.string.play_now)
            menu.add(0, 2, 1, R.string.rename)
            menu.add(0, 3, 2, R.string.memory_override)
            menu.add(0, 4, 3, R.string.jvm_args)
            menu.add(0, 5, 4, R.string.remove)
            setOnMenuItemClickListener { item ->
                val inst = instance(row.id) ?: return@setOnMenuItemClickListener true
                when (item.itemId) {
                    1 -> playNow(inst)
                    2 -> editText(R.string.rename, inst.name) { app.instances.update(inst.copy(name = it.ifBlank { inst.name })) }
                    3 -> editMemory(inst)
                    4 -> editText(R.string.jvm_args, inst.extraJvmArgs) { app.instances.update(inst.copy(extraJvmArgs = it)) }
                    5 -> confirmDelete(row)
                }
                true
            }
        }.show()
    }

    private fun playNow(inst: Instance) {
        app.instances.select(inst.id)
        if (app.accounts.selected == null) {
            Snackbar.make(binding.root, R.string.add_account_hint, Snackbar.LENGTH_SHORT).show()
            return
        }
        val m = resources.displayMetrics
        vm.start(inst, maxOf(m.widthPixels, m.heightPixels), minOf(m.widthPixels, m.heightPixels))
        (activity as? MainActivity)?.navigateTo(R.id.nav_home)
    }

    private fun editText(title: Int, initial: String, onSave: (String) -> Unit) {
        val layout = TextInputLayout(requireContext(), null, com.google.android.material.R.attr.textInputOutlinedStyle)
        val input = TextInputEditText(layout.context).apply { setText(initial); isSingleLine = true; selectAll() }
        layout.addView(input)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(layout.withPadding())
            .setPositiveButton(R.string.save) { _, _ -> onSave(input.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private fun editMemory(inst: Instance) {
        val global = app.settings.memoryMb
        val label = TextView(requireContext()).apply { setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium) }
        val slider = Slider(requireContext()).apply {
            valueFrom = 0f; valueTo = 8192f; stepSize = 256f
            value = inst.memoryMb.coerceIn(0, 8192).toFloat()
            setLabelFormatter { if (it == 0f) getString(R.string.use_global) else "${it.toInt()} MB" }
        }
        fun update() {
            label.text = if (slider.value == 0f) getString(R.string.use_global_value, global) else "${slider.value.toInt()} MB"
        }
        slider.addOnChangeListener { _, _, _ -> update() }
        update()
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(slider)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.memory_override)
            .setView(content.withPadding())
            .setPositiveButton(R.string.save) { _, _ -> app.instances.update(inst.copy(memoryMb = slider.value.toInt())) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun newInstance() {
        val d = DialogNewInstanceBinding.inflate(layoutInflater)
        var gameVersion: String? = null

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.new_instance)
            .setView(d.root)
            .setPositiveButton(R.string.create, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        val create = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)

        fun defaultName(): String {
            val v = gameVersion ?: return ""
            return if (d.loader.checkedButtonId == R.id.loader_fabric) "Fabric $v" else v
        }
        fun updateHint() { d.name.hint = defaultName() }
        d.loader.addOnButtonCheckedListener { _, _, _ -> updateHint() }

        val pickVersion = View.OnClickListener {
            d.working.isVisible = true
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val manifest = app.versions.manifest()
                    val local = app.versions.installedIds().filter { manifest.find(it) == null }
                    val rows = local.map { Row(it, it, getString(R.string.installed_profile), icon = R.drawable.ic_folder, badge = TYPE_INSTALLED) } +
                        manifest.versions.map { v ->
                            Row(v.id, v.id, v.releaseTime.take(10), selected = v.id == gameVersion,
                                badge = if (v.type == "release") null else v.type,
                                icon = if (v.type == "release") R.drawable.ic_cube else R.drawable.ic_layers,
                                iconTint = requireContext().getColor(if (v.type == "release") R.color.accent else R.color.warning))
                        }
                    val showSnapshots = app.settings.showSnapshots
                    PickerSheet.show(
                        this@InstancesFragment, getString(R.string.choose_version), rows, searchable = true,
                        filters = listOf(
                            PickerFilter(getString(R.string.filter_release), true) { it.badge == null },
                            PickerFilter(getString(R.string.filter_snapshot), showSnapshots) { it.badge == "snapshot" },
                            PickerFilter(getString(R.string.filter_old), false) { it.badge?.startsWith("old_") == true },
                            PickerFilter(getString(R.string.filter_installed), local.isNotEmpty()) { it.badge == TYPE_INSTALLED },
                        ),
                    ) { row ->
                        gameVersion = row.id
                        d.version.setText(row.id)
                        // Fabric only applies to vanilla versions.
                        d.loaderFabric.isEnabled = row.badge != TYPE_INSTALLED
                        if (row.badge == TYPE_INSTALLED) d.loader.check(R.id.loader_vanilla)
                        create.isEnabled = true
                        updateHint()
                    }
                } catch (e: Exception) {
                    d.error.text = getString(R.string.manifest_failed, e.message)
                    d.error.isVisible = true
                } finally {
                    d.working.isVisible = false
                }
            }
        }
        d.version.setOnClickListener(pickVersion)
        (d.version.parent.parent as? TextInputLayout)?.setEndIconOnClickListener(pickVersion)

        // Preselect the newest release so creating an instance is one tap.
        create.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { app.versions.manifest().latestRelease }.getOrNull()?.let {
                if (gameVersion == null) {
                    gameVersion = it
                    d.version.setText(it)
                    create.isEnabled = true
                    updateHint()
                }
            }
        }

        create.setOnClickListener {
            val version = gameVersion ?: return@setOnClickListener
            val fabric = d.loader.checkedButtonId == R.id.loader_fabric
            val name = d.name.text.toString().trim().ifEmpty { defaultName() }
            create.isEnabled = false
            d.working.isVisible = true
            d.error.isVisible = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val versionId = if (fabric) FabricInstaller(app.versions).install(version) else version
                    app.instances.add(Instance(name = name, versionId = versionId, isolated = d.isolated.isChecked))
                    dialog.dismiss()
                    Snackbar.make(binding.root, getString(R.string.instance_created, name), Snackbar.LENGTH_LONG)
                        .setAction(R.string.play) { instance(app.instances.selected!!.id)?.let(::playNow) }
                        .show()
                } catch (e: Exception) {
                    d.error.text = e.message
                    d.error.isVisible = true
                    create.isEnabled = true
                } finally {
                    d.working.isVisible = false
                }
            }
        }
    }

    private fun confirmDelete(row: Row) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.confirm_remove_instance, row.title))
            .setPositiveButton(R.string.remove) { _, _ -> app.instances.remove(row.id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TYPE_INSTALLED = "installed"
    }
}
