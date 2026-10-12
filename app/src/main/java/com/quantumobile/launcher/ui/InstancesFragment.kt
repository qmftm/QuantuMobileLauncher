package com.quantumobile.launcher.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
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

    private val adapter = SimpleListAdapter(
        onClick = { row -> app.instances.select(row.id); refresh() },
        onLongClick = { row -> confirmDelete(row) },
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.empty.setText(R.string.instances_empty)
        binding.primaryAction.setText(R.string.new_instance)
        binding.secondaryAction.isVisible = false
        binding.primaryAction.setOnClickListener { newInstance() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.instances.instances.collect { refresh() }
            }
        }
    }

    private fun refresh() {
        val list = app.instances.instances.value
        val selected = app.instances.selected?.id
        binding.empty.isVisible = list.isEmpty()
        adapter.submit(list.map { Row(it.id, it.name, it.versionId, it.id == selected) })
    }

    private fun newInstance() {
        val dialogBinding = DialogNewInstanceBinding.inflate(layoutInflater)
        val loaders = listOf(getString(R.string.loader_vanilla), getString(R.string.loader_fabric))
        dialogBinding.loader.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, loaders)
        dialogBinding.versionLoading.isVisible = true

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.new_instance)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.create, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        val create = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        create.isEnabled = false

        var versionIds: List<String> = emptyList()
        fun bindVersions(snapshots: Boolean) = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val manifest = app.versions.manifest()
                val remote = manifest.versions.filter { it.type == "release" || (snapshots && it.type == "snapshot") }.map { it.id }
                // Locally installed profiles that are not in the manifest (e.g. manually added mod loaders).
                val local = app.versions.installedIds().filter { manifest.find(it) == null }
                versionIds = local + remote
                dialogBinding.version.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, versionIds)
                create.isEnabled = versionIds.isNotEmpty()
            } catch (e: Exception) {
                dialogBinding.error.text = getString(R.string.manifest_failed, e.message)
                dialogBinding.error.isVisible = true
            } finally {
                dialogBinding.versionLoading.isVisible = false
            }
        }
        bindVersions(app.settings.showSnapshots)
        dialogBinding.snapshots.isChecked = app.settings.showSnapshots
        dialogBinding.snapshots.setOnCheckedChangeListener { _, checked -> bindVersions(checked) }

        create.setOnClickListener {
            val gameVersion = versionIds.getOrNull(dialogBinding.version.selectedItemPosition) ?: return@setOnClickListener
            val fabric = dialogBinding.loader.selectedItemPosition == 1
            val name = dialogBinding.name.text.toString().trim().ifEmpty { if (fabric) "Fabric $gameVersion" else gameVersion }
            create.isEnabled = false
            dialogBinding.versionLoading.isVisible = true
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val versionId = if (fabric) FabricInstaller(app.versions).install(gameVersion) else gameVersion
                    app.instances.add(Instance(name = name, versionId = versionId, isolated = dialogBinding.isolated.isChecked))
                    dialog.dismiss()
                } catch (e: Exception) {
                    dialogBinding.error.text = e.message
                    dialogBinding.error.isVisible = true
                    create.isEnabled = true
                } finally {
                    dialogBinding.versionLoading.isVisible = false
                }
            }
        }
    }

    private fun confirmDelete(row: Row) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.confirm_remove_instance, row.title))
            .setPositiveButton(R.string.remove) { _, _ -> app.instances.remove(row.id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
