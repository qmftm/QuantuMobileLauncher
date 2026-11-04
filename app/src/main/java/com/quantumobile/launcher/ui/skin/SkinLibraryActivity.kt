package com.quantumobile.launcher.ui.skin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.quantumobile.launcher.QuantumApp
import com.quantumobile.launcher.R
import com.quantumobile.launcher.core.auth.AccountType
import com.quantumobile.launcher.core.skin.SkinApi
import com.quantumobile.launcher.core.skin.SkinEntry
import com.quantumobile.launcher.core.skin.SkinKind
import com.quantumobile.launcher.core.skin.SkinLayout
import com.quantumobile.launcher.core.skin.SkinModel
import com.quantumobile.launcher.core.skin.SkinOps
import com.quantumobile.launcher.core.skin.SkinPresets
import com.quantumobile.launcher.core.skin.Texture
import com.quantumobile.launcher.databinding.ActivitySkinLibraryBinding
import com.quantumobile.launcher.ui.Row
import com.quantumobile.launcher.ui.SimpleListAdapter
import com.quantumobile.launcher.ui.shrinkFabsOnScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The saved skins and capes. New ones are picked from presets, loaded from a player or account, or imported from an
 * image file; tapping one shows its preview.
 */
class SkinLibraryActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySkinLibraryBinding
    private val app get() = application as QuantumApp
    private val store get() = app.skins
    private val api = SkinApi()
    private lateinit var adapter: SimpleListAdapter

    private var exportId: String? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importFile) }
    private val exportTo = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val id = exportId ?: return@registerForActivityResult
        if (uri != null) exportFile(id, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySkinLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.skin_title)

        val list = binding.content
        adapter = SimpleListAdapter(lifecycleScope, onClick = { row -> open(row.id) }, onMore = ::showMenu)
        list.list.layoutManager = LinearLayoutManager(this)
        list.list.adapter = adapter
        list.emptyIcon.setImageResource(R.drawable.ic_brush)
        list.emptyText.setText(R.string.skin_empty)
        list.primaryAction.setText(R.string.skin_add)
        list.secondaryAction.setText(R.string.skin_import)
        list.secondaryAction.setIconResource(R.drawable.ic_upload)
        list.primaryAction.setOnClickListener { chooseNew() }
        list.secondaryAction.setOnClickListener { pickImage.launch(arrayOf("image/png", "image/*")) }
        list.list.addOnScrollListener(shrinkFabsOnScroll(list))

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { store.entries.collect { refresh(it) } }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun refresh(entries: List<SkinEntry>) {
        binding.content.empty.isVisible = entries.isEmpty()
        adapter.submitList(entries.map { e ->
            val thumb = store.thumbFile(e.id).takeIf { it.isFile }
            Row(
                id = e.id,
                title = e.name,
                subtitle = if (e.kind == SkinKind.CAPE) getString(R.string.skin_sub_cape)
                else getString(R.string.skin_sub_skin, getString(if (e.slim) R.string.skin_model_slim else R.string.skin_model_classic)),
                icon = R.drawable.ic_brush,
                iconFile = thumb?.path,
                iconStamp = e.modified,
                iconRaw = true,
            )
        })
    }

    private fun open(id: String) =
        startActivity(Intent(this, SkinPreviewActivity::class.java).putExtra(SkinPreviewActivity.EXTRA_ID, id))

    private fun snack(text: String) = Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()
    private fun snack(res: Int) = Snackbar.make(binding.root, res, Snackbar.LENGTH_LONG).show()

    // ---- adding ----

    private fun chooseNew() {
        val items = listOf<Pair<Int, () -> Unit>>(
            R.string.skin_add_preset_skin to { choosePreset(SkinPresets.skins) },
            R.string.skin_add_preset_cape to { choosePreset(SkinPresets.capes) },
            R.string.skin_add_player_skin to { askPlayer(cape = false) },
            R.string.skin_add_player_cape to { askPlayer(cape = true) },
            R.string.skin_add_account_skin to { loadFromAccount(cape = false) },
            R.string.skin_add_account_cape to { loadFromAccount(cape = true) },
            R.string.skin_add_file to { pickImage.launch(arrayOf("image/png", "image/*")) },
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.skin_add)
            .setItems(items.map { getString(it.first) }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    private fun choosePreset(presets: List<com.quantumobile.launcher.core.skin.SkinPreset>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(if (presets.firstOrNull()?.kind == SkinKind.CAPE) R.string.skin_add_preset_cape else R.string.skin_add_preset_skin)
            .setItems(presets.map { it.label }.toTypedArray()) { _, i ->
                load(presets[i].player, presets[i].kind == SkinKind.CAPE, presets[i].label)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askPlayer(cape: Boolean) {
        val input = EditText(this).apply {
            hint = getString(R.string.skin_player_name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (cape) R.string.skin_add_player_cape else R.string.skin_add_player_skin)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> load(input.text.toString().trim(), cape, null) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadFromAccount(cape: Boolean) {
        val account = app.accounts.selected ?: return snack(R.string.skin_apply_no_account)
        load(if (account.type == AccountType.MICROSOFT) account.uuid else account.username, cape, null)
    }

    /** Saves a new entry and shows its preview. */
    private fun create(kind: SkinKind, model: SkinModel, texture: Texture, name: String) {
        val entry = store.add(name, kind, model == SkinModel.SLIM, SkinBitmaps.encodePng(texture), SkinBitmaps.thumbPng(texture, kind, model))
        open(entry.id)
    }

    /** Fetches a player's skin (or cape) from Mojang, saves it and shows it. [label] names it (default: the player). */
    private fun load(nameOrUuid: String, cape: Boolean, label: String?) {
        if (nameOrUuid.isEmpty()) return
        snack(R.string.skin_loading)
        lifecycleScope.launch {
            try {
                val tex = api.textures(nameOrUuid) ?: return@launch snack(R.string.skin_not_found)
                val url = (if (cape) tex.capeUrl else tex.skinUrl) ?: return@launch snack(if (cape) R.string.skin_no_cape else R.string.skin_no_skin)
                val bytes = api.download(url)
                val decoded = withContext(Dispatchers.Default) { SkinBitmaps.decode(bytes) } ?: return@launch snack(R.string.skin_bad_image)
                val name = label ?: (tex.name ?: nameOrUuid).let { if (cape) getString(R.string.skin_cape_of, it) else it }
                if (cape) {
                    if (!SkinLayout.isCapeSize(decoded.width, decoded.height)) return@launch snack(getString(R.string.skin_bad_size, decoded.width, decoded.height))
                    create(SkinKind.CAPE, SkinModel.CLASSIC, decoded, name)
                } else {
                    val skin = asSkin(decoded) ?: return@launch snack(getString(R.string.skin_bad_size, decoded.width, decoded.height))
                    create(SkinKind.SKIN, SkinModel.of(tex.slim), skin, name)
                }
            } catch (e: Exception) {
                snack(getString(R.string.skin_load_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** A 64×64 skin from [t] (an old 64×32 skin is converted); null for any other size. */
    private fun asSkin(t: Texture): Texture? = when {
        SkinLayout.isSkinSize(t.width, t.height) -> t
        SkinLayout.isLegacySkinSize(t.width, t.height) -> SkinOps.upgradeLegacy(t)
        else -> null
    }

    // ---- importing / exporting ----

    private fun importFile(uri: Uri) {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)?.use { com.quantumobile.launcher.core.skin.readLimited(it, MAX_FILE + 1) } }.getOrNull()
            }
            if (bytes == null || bytes.size > MAX_FILE) return@launch snack(R.string.skin_bad_image)
            val t = withContext(Dispatchers.Default) { SkinBitmaps.decode(bytes) } ?: return@launch snack(R.string.skin_bad_image)
            val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.ifBlank { null }
            when {
                SkinLayout.isSkinSize(t.width, t.height) -> create(SkinKind.SKIN, SkinModel.CLASSIC, t, name ?: getString(R.string.skin_default_name))
                SkinLayout.isLegacySkinSize(t.width, t.height) -> MaterialAlertDialogBuilder(this@SkinLibraryActivity)
                    .setTitle(R.string.skin_legacy_title)
                    .setMessage(R.string.skin_legacy_ask)
                    .setPositiveButton(R.string.skin_legacy_as_cape) { _, _ -> create(SkinKind.CAPE, SkinModel.CLASSIC, t, name ?: getString(R.string.skin_default_cape_name)) }
                    .setNegativeButton(R.string.skin_legacy_as_skin) { _, _ -> create(SkinKind.SKIN, SkinModel.CLASSIC, SkinOps.upgradeLegacy(t), name ?: getString(R.string.skin_default_name)) }
                    .setNeutralButton(android.R.string.cancel, null)
                    .show()
                else -> snack(getString(R.string.skin_bad_size, t.width, t.height))
            }
        }
    }

    private fun exportFile(id: String, uri: Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { it.write(store.textureFile(id).readBytes()) } != null
                }.getOrDefault(false)
            }
            snack(if (ok) R.string.skin_exported else R.string.skin_export_failed)
        }
    }

    // ---- one entry's menu ----

    private fun showMenu(row: Row, anchor: View) {
        val entry = store.get(row.id) ?: return
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.skin_preview)
            menu.add(0, 2, 1, R.string.skin_rename)
            menu.add(0, 3, 2, R.string.skin_export)
            menu.add(0, 4, 3, R.string.skin_delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> open(entry.id)
                    2 -> rename(entry)
                    3 -> { exportId = entry.id; exportTo.launch("${entry.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.png") }
                    4 -> MaterialAlertDialogBuilder(this@SkinLibraryActivity)
                        .setMessage(getString(R.string.skin_delete_confirm, entry.name))
                        .setPositiveButton(R.string.skin_delete) { _, _ -> store.delete(entry.id) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                true
            }
        }.show()
    }

    private fun rename(entry: SkinEntry) {
        val input = EditText(this).apply { setText(entry.name); setSingleLine(); selectAll() }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.skin_rename)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> store.rename(entry.id, input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private companion object {
        const val MAX_FILE = 2 * 1024 * 1024
    }
}
