package com.quantumobile.launcher.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.quantumobile.launcher.QuantumApp
import com.quantumobile.launcher.R
import com.quantumobile.launcher.core.instance.Instance
import com.quantumobile.launcher.core.mods.InstallPlan
import com.quantumobile.launcher.core.mods.InstalledMod
import com.quantumobile.launcher.core.mods.ModHit
import com.quantumobile.launcher.core.mods.ModInstaller
import com.quantumobile.launcher.core.mods.ModTarget
import com.quantumobile.launcher.core.mods.ModrinthApi
import com.quantumobile.launcher.core.mods.ProjectType
import com.quantumobile.launcher.core.parseJsonObject
import com.quantumobile.launcher.core.str
import com.quantumobile.launcher.databinding.FragmentModsBinding
import com.quantumobile.launcher.service.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Browse Modrinth for mods, resource packs and shaders matching the selected instance, and manage what is installed. */
class ModsFragment : Fragment() {
    private var _binding: FragmentModsBinding? = null
    private val binding get() = _binding!!
    private val app get() = requireActivity().application as QuantumApp
    private val api = ModrinthApi()

    private lateinit var adapter: SimpleListAdapter
    private var instance: Instance? = null
    private var target: ModTarget? = null
    private var installer: ModInstaller? = null

    private var type = ProjectType.MOD
    private var installedMode = false
    private var query = ""
    private val hits = LinkedHashMap<String, ModHit>()
    private var total = 0
    private var loading = false
    private var searchJob: Job? = null
    private val debounce = Runnable { reload() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentModsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = SimpleListAdapter(
            viewLifecycleOwner.lifecycleScope,
            showMore = false,
            onClick = { row -> if (!installedMode) hits[row.id]?.let(::startInstall) },
            onMore = ::showInstalledMenu,
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (dy > 0 && !installedMode && !loading && hits.size < total && lm.findLastVisibleItemPosition() >= hits.size - 5) {
                    loadPage()
                }
            }
        })

        binding.mode.addOnButtonCheckedListener { _, id, checked ->
            if (checked) { installedMode = id == R.id.btn_installed; reload() }
        }
        binding.types.setOnCheckedStateChangeListener { _, ids ->
            type = when (ids.firstOrNull()) {
                R.id.chip_packs -> ProjectType.RESOURCEPACK
                R.id.chip_shaders -> ProjectType.SHADER
                else -> ProjectType.MOD
            }
            reload()
        }
        binding.search.doAfterTextChanged {
            query = it?.toString()?.trim().orEmpty()
            binding.root.removeCallbacks(debounce)
            binding.root.postDelayed(debounce, 400)
        }
        binding.search.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { binding.root.removeCallbacks(debounce); reload(); true } else false
        }
        binding.checkUpdates.setOnClickListener { checkUpdates() }
    }

    override fun onResume() {
        super.onResume()
        val current = app.instances.selected
        if (current?.id != instance?.id || current?.versionId != instance?.versionId) {
            instance = current
            target = current?.let(::targetFor)
            installer = current?.let { ModInstaller(api, it.gameDir(app.paths)) }
            reload()
        } else if (installedMode) {
            reload()
        }
    }

    private fun targetFor(instance: Instance): ModTarget {
        val parent = runCatching { parseJsonObject(app.paths.versionJson(instance.versionId).readText()).str("inheritsFrom") }.getOrNull()
        return ModTarget.detect(instance.versionId, parent)
    }

    /** Re-runs the current view (search results or installed list) from the start. */
    private fun reload() {
        searchJob?.cancel()
        hits.clear()
        total = 0
        loading = false
        adapter.submitList(emptyList())
        adapter.showMore = installedMode

        val inst = instance
        val tgt = target
        binding.browseGroup.isVisible = !installedMode
        binding.checkUpdates.isVisible = installedMode
        binding.targetInfo.text = if (inst == null || tgt == null) getString(R.string.no_instance)
        else getString(R.string.mods_target_format, inst.name, loaderLabel(tgt), tgt.gameVersion)

        when {
            inst == null -> showEmpty(R.string.mods_no_instance)
            installedMode -> showInstalled()
            type.usesLoader && tgt!!.isVanilla -> showEmpty(R.string.mods_vanilla)
            else -> loadPage()
        }
    }

    private fun loaderLabel(t: ModTarget) =
        if (t.isVanilla) getString(R.string.loader_vanilla) else t.loaders.first().replaceFirstChar { it.uppercase() }

    private fun showEmpty(text: Int) {
        binding.empty.setText(text)
        binding.empty.isVisible = true
        binding.loading.isVisible = false
    }

    private fun loadPage() {
        val tgt = target ?: return
        loading = true
        binding.empty.isVisible = false
        binding.loading.isVisible = hits.isEmpty()
        val requestType = type
        val requestQuery = query
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val page = api.search(requestQuery, requestType, tgt, offset = hits.size)
                page.hits.forEach { hits.putIfAbsent(it.projectId, it) }
                total = page.total
                adapter.submitList(hits.values.map { it.toRow() })
                binding.empty.isVisible = hits.isEmpty()
                if (hits.isEmpty()) binding.empty.setText(R.string.mods_none)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (hits.isEmpty()) { binding.empty.text = getString(R.string.modrinth_error, e.message); binding.empty.isVisible = true }
                else Snackbar.make(binding.root, getString(R.string.modrinth_error, e.message), Snackbar.LENGTH_SHORT).show()
            } finally {
                loading = false
                binding.loading.isVisible = false
            }
        }
    }

    private fun ModHit.toRow() = Row(
        id = projectId, title = title, subtitle = description,
        badge = "⬇ ${compact(downloads)}", iconUrl = iconUrl,
    )

    private fun showInstalled() {
        val mods = installer?.store?.list().orEmpty()
        adapter.submitList(mods.map { m ->
            Row(m.projectId, m.title, "${m.versionNumber} · ${m.fileName}",
                badge = if (m.dependency) getString(R.string.dependency_badge) else null,
                icon = when (m.projectType) {
                    ProjectType.MOD -> R.drawable.ic_extension
                    ProjectType.RESOURCEPACK -> R.drawable.ic_folder
                    ProjectType.SHADER -> R.drawable.ic_layers
                })
        })
        binding.empty.setText(R.string.mods_installed_empty)
        binding.empty.isVisible = mods.isEmpty()
        binding.loading.isVisible = false
        binding.checkUpdates.isEnabled = mods.isNotEmpty()
    }

    private fun showInstalledMenu(row: Row, anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 1, 0, R.string.remove)
            setOnMenuItemClickListener {
                val mod = installer?.store?.list()?.firstOrNull { m -> m.projectId == row.id } ?: return@setOnMenuItemClickListener true
                confirmRemove(mod)
                true
            }
        }.show()
    }

    private fun confirmRemove(mod: InstalledMod) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.confirm_remove, mod.title))
            .setPositiveButton(R.string.remove) { _, _ ->
                installer?.remove(mod)
                showInstalled()
                Snackbar.make(binding.root, getString(R.string.removed, mod.title), Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- install ----

    private fun startInstall(hit: ModHit) {
        val inst = installer ?: return
        val tgt = target ?: return
        val progress = MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.resolving).setCancelable(false).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val plan = try {
                inst.resolve(hit.projectId, hit.title, hit.type, tgt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress.dismiss()
                showError(e)
                return@launch
            }
            progress.dismiss()
            confirmPlan(getString(R.string.install_title, hit.title), plan)
        }
    }

    private fun confirmPlan(title: String, plan: InstallPlan) {
        val lines = plan.items.joinToString("\n") { item ->
            "• ${item.title} ${item.version.versionNumber}" + if (item.dependency) "  (${getString(R.string.install_required)})" else ""
        }
        val warning = if (plan.missing.isEmpty()) "" else "\n\n" + getString(R.string.install_missing, plan.missing.joinToString())
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(lines + warning)
            .setPositiveButton(R.string.install) { _, _ -> apply(plan) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun apply(plan: InstallPlan) {
        val inst = installer ?: return
        val app = app
        val ctx = requireContext().applicationContext
        val title = getString(R.string.installing_mods, plan.items.first().title)
        val progress = MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.preparing)
            .setCancelable(false)
            .setNegativeButton(R.string.run_in_background, null) // just closes the dialog; the install carries on
            .show()
        // The work belongs to the app, not this screen: it keeps running if the user leaves or backgrounds the app.
        app.tasks.launch(
            title,
            block = { reporter ->
                inst.apply(plan) { i, n, name ->
                    val message = ctx.getString(R.string.installing_item, name, minOf(i + 1, n), n)
                    reporter.report(message, i * 100 / n)
                    if (progress.isShowing) progress.setMessage(message)
                }
            },
            onResult = { result ->
                if (progress.isShowing) progress.dismiss()
                result.onSuccess { done ->
                    val text = ctx.getString(R.string.installed_count, done.size)
                    if (_binding != null) {
                        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()
                        if (installedMode) showInstalled()
                    }
                    if (!app.isForeground) Notifications.done(ctx, title, text)
                }.onFailure { e ->
                    if (_binding != null && app.isForeground) showError(e as? Exception ?: Exception(e))
                    else Notifications.done(ctx, title, e.message)
                }
            },
        )
    }

    private fun checkUpdates() {
        val inst = installer ?: return
        val tgt = target ?: return
        binding.checkUpdates.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val updates = inst.findUpdates(tgt)
                if (updates.isEmpty()) {
                    Snackbar.make(binding.root, R.string.up_to_date, Snackbar.LENGTH_SHORT).show()
                } else {
                    confirmPlan(getString(R.string.updates_available), InstallPlan(updates, emptyList()))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e)
            } finally {
                binding.checkUpdates.isEnabled = true
            }
        }
    }

    private fun showError(e: Exception) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(e.message ?: e.javaClass.simpleName)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroyView() {
        binding.root.removeCallbacks(debounce)
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** 1234 → "1.2K", 242793522 → "242.8M". */
        fun compact(n: Long): String = when {
            n >= 1_000_000_000 -> "%.1fB".format(n / 1e9)
            n >= 1_000_000 -> "%.1fM".format(n / 1e6)
            n >= 1_000 -> "%.1fK".format(n / 1e3)
            else -> n.toString()
        }
    }
}
