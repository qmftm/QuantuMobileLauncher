package com.quantumobile.launcher.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
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
import com.quantumobile.launcher.core.mods.AndroidCompat
import com.quantumobile.launcher.core.mods.InstallPlan
import com.quantumobile.launcher.core.mods.InstalledMod
import com.quantumobile.launcher.core.mods.ModHit
import com.quantumobile.launcher.core.mods.ModInstaller
import com.quantumobile.launcher.core.mods.ModTarget
import com.quantumobile.launcher.core.mods.ModrinthApi
import com.quantumobile.launcher.core.mods.PackManager
import com.quantumobile.launcher.core.mods.ProjectType
import com.quantumobile.launcher.core.parseJsonObject
import com.quantumobile.launcher.core.str
import com.quantumobile.launcher.databinding.FragmentDownloadBinding
import com.quantumobile.launcher.service.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Download" tab: one screen for everything that is added to an instance. Pick what (mods, resource packs or shaders),
 * then browse Modrinth or manage what is installed. Mods list the installed mods (on/off, remove); packs and shaders also
 * get the pack tools: add a file from the device, enable and order resource packs, choose the active shader.
 */
class DownloadFragment : Fragment() {
    private var _binding: FragmentDownloadBinding? = null
    private val binding get() = _binding!!
    private val app get() = requireActivity().application as QuantumApp
    private val api = ModrinthApi()

    private lateinit var adapter: SimpleListAdapter
    private var instance: Instance? = null
    private var target: ModTarget? = null
    private var installer: ModInstaller? = null
    private var packs: PackManager? = null

    private var type = ProjectType.MOD
    private var installedMode = false
    private var query = ""
    private val hits = LinkedHashMap<String, ModHit>()
    private var total = 0
    private var loading = false
    private var searchJob: Job? = null
    private val debounce = Runnable { reload() }

    private val isPack get() = type != ProjectType.MOD
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> importFiles(uris) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        savedInstanceState?.let {
            type = ProjectType.values().getOrElse(it.getInt(KEY_TYPE)) { ProjectType.MOD }
            installedMode = it.getBoolean(KEY_INSTALLED)
        }
        binding.types.check(chipFor(type))
        binding.mode.check(if (installedMode) R.id.btn_installed else R.id.btn_browse)

        adapter = SimpleListAdapter(
            viewLifecycleOwner.lifecycleScope,
            showMore = false,
            onClick = { row -> if (installedMode) { if (isPack) togglePack(row.id) } else hits[row.id]?.let(::startInstall) },
            onMore = ::showPackMenu,
            onAction = ::onRowAction,
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
        binding.add.setOnClickListener { pickFile.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
        // Rows being downloaded show a spinner; refresh when that set changes (and when a download ends).
        viewLifecycleOwner.lifecycleScope.launch { ModActions.active.collect { if (!installedMode) renderHits() } }
    }

    private fun chipFor(t: ProjectType) = when (t) {
        ProjectType.MOD -> R.id.chip_mods
        ProjectType.RESOURCEPACK -> R.id.chip_packs
        ProjectType.SHADER -> R.id.chip_shaders
    }

    override fun onResume() {
        super.onResume()
        val current = app.instances.selected
        if (current?.id != instance?.id || current?.versionId != instance?.versionId) {
            instance = current
            target = current?.let(::targetFor)
            val dir = current?.gameDir(app.paths)
            installer = dir?.let { ModInstaller(api, it) }
            packs = dir?.let(::PackManager)
        }
        reload()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TYPE, type.ordinal)
        outState.putBoolean(KEY_INSTALLED, installedMode)
    }

    private fun targetFor(instance: Instance): ModTarget {
        val parent = runCatching { parseJsonObject(app.paths.versionJson(instance.versionId).readText()).str("inheritsFrom") }.getOrNull()
        return ModTarget.detect(instance.versionId, parent)
    }

    /** Re-runs the current view (search results or installed list) from the start. */
    private fun reload() {
        if (_binding == null) return
        searchJob?.cancel()
        hits.clear()
        total = 0
        loading = false
        adapter.submitList(emptyList())
        adapter.showMore = installedMode && isPack

        val inst = instance
        val tgt = target
        binding.browseGroup.isVisible = !installedMode
        binding.checkUpdates.isVisible = installedMode
        binding.add.isVisible = installedMode && isPack && inst != null
        binding.hint.isVisible = installedMode && isPack && inst != null
        binding.hint.text = getString(if (type == ProjectType.SHADER) R.string.visual_shader_hint else R.string.visual_rp_hint)
        binding.targetInfo.text = if (inst == null || tgt == null) getString(R.string.no_instance)
        else getString(R.string.mods_target_format, inst.name, loaderLabel(tgt), tgt.gameVersion)
        binding.empty.isVisible = false
        binding.loading.isVisible = false

        when {
            inst == null -> showEmpty(getString(R.string.mods_no_instance))
            installedMode -> showInstalled()
            type.usesLoader && tgt!!.isVanilla -> showEmpty(getString(R.string.mods_vanilla))
            else -> loadPage()
        }
    }

    private fun loaderLabel(t: ModTarget) =
        if (t.isVanilla) getString(R.string.loader_vanilla) else t.loaders.first().replaceFirstChar { it.uppercase() }

    private fun showEmpty(text: String) {
        binding.empty.text = text
        binding.empty.isVisible = true
        binding.loading.isVisible = false
    }

    // ---- browse ----

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
                renderHits()
                if (hits.isEmpty()) showEmpty(getString(R.string.mods_none))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (hits.isEmpty()) showEmpty(getString(R.string.modrinth_error, e.message))
                else Snackbar.make(binding.root, getString(R.string.modrinth_error, e.message), Snackbar.LENGTH_SHORT).show()
            } finally {
                loading = false
                binding.loading.isVisible = false
            }
        }
    }

    private fun renderHits() {
        if (_binding == null || installedMode) return
        val inst = installer ?: return
        val installed = inst.store.list().associateBy { it.projectId }
        val busy = ModActions.active.value
        adapter.submitList(hits.values.map { hit ->
            val mod = installed[hit.projectId]
            Row(
                id = hit.projectId, title = hit.title, subtitle = hit.description,
                badge = "⬇ ${compact(hit.downloads)}", iconUrl = hit.iconUrl,
                actions = ModActions.actionsFor(mod, mod?.let(inst::isEnabled) ?: false),
                busy = hit.projectId in busy,
            )
        })
    }

    // ---- installed ----

    private fun showInstalled() {
        if (_binding == null) return
        if (isPack) showInstalledPacks() else showInstalledMods()
        binding.checkUpdates.isEnabled = (installer?.listAllMods()?.isNotEmpty() == true)
    }

    private fun showInstalledMods() {
        val inst = installer ?: return
        val mods = inst.listAllMods().filter { it.projectType == ProjectType.MOD }
        adapter.submitList(mods.map { m ->
            val unsupported = AndroidCompat.likelyUnsupported(m.fileName)
            Row(m.projectId, m.title, listOf(m.versionNumber, m.fileName).filter { it.isNotEmpty() }.joinToString(" · "),
                badge = when {
                    unsupported -> getString(R.string.android_unsupported_badge)
                    m.dependency -> getString(R.string.dependency_badge)
                    else -> null
                },
                icon = R.drawable.ic_extension,
                actions = ModActions.actionsFor(m, inst.isEnabled(m)))
        })
        binding.empty.setText(R.string.mods_installed_empty)
        binding.empty.isVisible = mods.isEmpty()
        binding.loading.isVisible = false
    }

    private fun showInstalledPacks() {
        val pm = packs ?: return
        val entries = pm.list(type)
        val rows = if (type == ProjectType.SHADER) {
            val active = pm.activeShader()
            entries.map { e ->
                Row(e.name, e.name.removeSuffix(".zip"), sizeText(e.sizeBytes, e.isDirectory),
                    selected = e.name == active,
                    badge = if (e.name == active) getString(R.string.visual_active_badge) else null,
                    icon = R.drawable.ic_layers)
            }
        } else {
            val enabled = pm.enabledResourcePacks().filter { it != "vanilla" }.reversed() // top priority first
            val byName = entries.associateBy { it.name }
            val on = enabled.mapNotNull { byName[it] }
            val off = entries.filter { it.name !in enabled }
            (on + off).map { e ->
                val isOn = e.name in enabled
                Row(e.name, e.name.removeSuffix(".zip"), sizeText(e.sizeBytes, e.isDirectory),
                    selected = isOn,
                    badge = if (isOn) getString(R.string.visual_enabled_badge) else null,
                    icon = R.drawable.ic_folder)
            }
        }
        adapter.submitList(rows)
        binding.empty.setText(if (type == ProjectType.SHADER) R.string.visual_empty_shaders else R.string.visual_empty_packs)
        binding.empty.isVisible = rows.isEmpty()
        binding.loading.isVisible = false
    }

    private fun sizeText(bytes: Long, dir: Boolean) =
        if (dir) getString(R.string.visual_size_folder) else "%.1f MB".format(bytes / 1_048_576.0)

    private fun togglePack(name: String) {
        val pm = packs ?: return
        if (type == ProjectType.SHADER) {
            pm.setActiveShader(if (pm.activeShader() == name) null else name)
        } else {
            pm.setResourcePackEnabled(name, name !in pm.enabledResourcePacks())
        }
        showInstalled()
    }

    private fun showPackMenu(row: Row, anchor: View) {
        val pm = packs ?: return
        val name = row.id
        PopupMenu(requireContext(), anchor).apply {
            if (type == ProjectType.SHADER) {
                val active = pm.activeShader() == name
                menu.add(0, 1, 0, if (active) R.string.visual_shaders_off else R.string.visual_use_shader)
            } else {
                val on = name in pm.enabledResourcePacks()
                menu.add(0, 1, 0, if (on) R.string.visual_disable else R.string.visual_enable)
                if (on) {
                    menu.add(0, 2, 1, R.string.visual_move_up)
                    menu.add(0, 3, 2, R.string.visual_move_down)
                }
            }
            menu.add(0, 4, 3, R.string.remove)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> togglePack(name)
                    2 -> { pm.movePack(name, up = true); showInstalled() }
                    3 -> { pm.movePack(name, up = false); showInstalled() }
                    4 -> confirmDeletePack(name)
                }
                true
            }
        }.show()
    }

    private fun confirmDeletePack(name: String) {
        val pm = packs ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.confirm_remove, name))
            .setPositiveButton(R.string.remove) { _, _ ->
                if (type == ProjectType.SHADER && pm.activeShader() == name) pm.setActiveShader(null)
                if (type == ProjectType.RESOURCEPACK) pm.setResourcePackEnabled(name, false)
                pm.delete(type, name)
                // Forget the Modrinth record too, otherwise "check updates" would resurrect it.
                installer?.store?.list()?.firstOrNull { it.fileName == name && it.projectType == type }?.let { installer?.store?.remove(it.projectId) }
                showInstalled()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importFiles(uris: List<Uri>) {
        val pm = packs ?: return
        val resolver = requireContext().contentResolver
        val kind = type
        viewLifecycleOwner.lifecycleScope.launch {
            for (uri in uris) {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment.orEmpty()
                        val input = resolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open file")
                        pm.import(kind, name, input)
                    }
                }
                if (_binding == null) return@launch
                result.onSuccess { Snackbar.make(binding.root, getString(R.string.visual_added, it), Snackbar.LENGTH_SHORT).show() }
                    .onFailure { Snackbar.make(binding.root, getString(R.string.visual_add_failed, it.message), Snackbar.LENGTH_LONG).show() }
            }
            if (_binding != null && installedMode && type == kind) showInstalled()
        }
    }

    // ---- row buttons (download / on-off / remove) ----

    private fun onRowAction(row: Row, action: RowAction) {
        val inst = installer ?: return
        val mod = inst.listAllMods().firstOrNull { it.projectId == row.id }
        when (action) {
            RowAction.DOWNLOAD -> {
                val hit = hits[row.id] ?: return
                val tgt = target ?: return
                Snackbar.make(binding.root, getString(R.string.download_started, hit.title), Snackbar.LENGTH_SHORT).show()
                ModActions.download(app, inst, tgt, hit) { result ->
                    if (_binding == null) return@download
                    result.onSuccess { out ->
                        val text = getString(R.string.installed_count, out.installed.size) +
                            if (out.missing.isEmpty()) "" else "\n" + getString(R.string.install_missing, out.missing.joinToString())
                        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()
                    }.onFailure { e ->
                        Snackbar.make(binding.root, e.message ?: e.javaClass.simpleName, Snackbar.LENGTH_LONG).show()
                    }
                    refreshRows()
                }
            }
            RowAction.ENABLE, RowAction.DISABLE -> {
                mod ?: return
                inst.setEnabled(mod, action == RowAction.ENABLE)
                refreshRows()
            }
            RowAction.REMOVE -> mod?.let(::confirmRemove)
        }
    }

    private fun refreshRows() { if (installedMode) showInstalled() else renderHits() }

    private fun confirmRemove(mod: InstalledMod) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.confirm_remove, mod.title))
            .setPositiveButton(R.string.remove) { _, _ ->
                installer?.remove(mod)
                refreshRows()
                Snackbar.make(binding.root, getString(R.string.removed, mod.title), Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- install (with dependencies) ----

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
                        refreshRows()
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
                _binding?.checkUpdates?.isEnabled = true
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
        private const val KEY_TYPE = "type"
        private const val KEY_INSTALLED = "installed"

        /** 1234 → "1.2K", 242793522 → "242.8M". */
        fun compact(n: Long): String = when {
            n >= 1_000_000_000 -> "%.1fB".format(n / 1e9)
            n >= 1_000_000 -> "%.1fM".format(n / 1e6)
            n >= 1_000 -> "%.1fK".format(n / 1e3)
            else -> n.toString()
        }
    }
}
