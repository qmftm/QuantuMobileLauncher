package com.quantumobile.launcher.ui.skin

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.quantumobile.launcher.QuantumApp
import com.quantumobile.launcher.R
import com.quantumobile.launcher.core.auth.Account
import com.quantumobile.launcher.core.auth.AccountType
import com.quantumobile.launcher.core.auth.MicrosoftAuth
import com.quantumobile.launcher.core.skin.SkinApi
import com.quantumobile.launcher.core.skin.SkinEntry
import com.quantumobile.launcher.core.skin.SkinKind
import com.quantumobile.launcher.core.skin.SkinModel
import com.quantumobile.launcher.core.skin.SkinRender
import com.quantumobile.launcher.core.skin.SkinStore
import com.quantumobile.launcher.core.skin.Texture
import com.quantumobile.launcher.databinding.ActivitySkinPreviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One saved skin or cape, drawn the way the game shows it. A skin is seen from the front and from the back (with a
 * chosen cape on); a cape is seen on a figure from behind and flat. A skin can be put on a Microsoft account.
 */
class SkinPreviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySkinPreviewBinding
    private lateinit var store: SkinStore
    private lateinit var entryId: String
    private val api = SkinApi()

    private var texture: Texture? = null
    /** The other half of the picture: a cape on a skin, or the skin a cape is worn with (null = none / plain figure). */
    private var companionId: String? = null

    private val exportTo = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri -> uri?.let(::export) }

    private val entry: SkinEntry? get() = store.get(entryId)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = (application as QuantumApp).skins
        val first = intent.getStringExtra(EXTRA_ID)?.let(store::get)
        if (first == null) { finish(); return }
        entryId = first.id
        texture = load(first.id)
        if (texture == null) { finish(); return }

        binding = ActivitySkinPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = first.name

        val isSkin = first.kind == SkinKind.SKIN
        binding.model.visibility = if (isSkin) android.view.View.VISIBLE else android.view.View.GONE
        binding.apply.visibility = if (isSkin) android.view.View.VISIBLE else android.view.View.GONE
        binding.note.text = getString(R.string.skin_drag_hint) + "\n" + getString(if (isSkin) R.string.skin_note_skin else R.string.skin_note_cape)
        if (isSkin) {
            binding.model.check(if (first.slim) R.id.model_slim else R.id.model_classic)
            binding.model.addOnButtonCheckedListener { _, id, checked ->
                if (checked) { store.setSlim(entryId, id == R.id.model_slim); render() }
            }
            binding.apply.setOnClickListener { entry?.let(::apply) }
        }
        binding.companion.setOnClickListener { chooseCompanion() }
        binding.walk.isChecked = true
        binding.spin.isChecked = true
        binding.motion.addOnButtonCheckedListener { _, id, checked ->
            if (id == R.id.walk) binding.preview.walking = checked else if (id == R.id.spin) binding.preview.spinning = checked
        }
        // Skins start at a three-quarter view from the front; capes are seen from behind first.
        render(startYaw = if (isSkin) 0.35f else 2.75f)
    }

    private fun load(id: String): Texture? =
        runCatching { SkinBitmaps.decode(store.textureFile(id).readBytes()) }.getOrNull()

    private fun companionTexture(): Texture? = companionId?.let(::load)

    private fun render(startYaw: Float? = null) {
        val e = entry ?: return
        val t = texture ?: return
        val other = companionTexture()
        if (e.kind == SkinKind.SKIN) {
            binding.preview.show(t, e.model, other, startYaw)
            val capeName = companionId?.let { store.get(it)?.name }
            binding.companion.text = getString(R.string.skin_with_cape, capeName ?: getString(R.string.skin_none))
        } else {
            // A cape is shown on a figure, from behind first: a chosen skin, or a plain grey figure.
            val body = other ?: SkinRender.mannequin()
            val model = companionId?.let { store.get(it)?.model } ?: SkinModel.CLASSIC
            binding.preview.show(body, model, t, startYaw)
            val skinName = companionId?.let { store.get(it)?.name }
            binding.companion.text = getString(R.string.skin_with_skin, skinName ?: getString(R.string.skin_plain_figure))
        }
        supportActionBar?.title = e.name
    }

    /** Picks the saved cape (for a skin) or skin (for a cape) to show it with. */
    private fun chooseCompanion() {
        val e = entry ?: return
        val wanted = if (e.kind == SkinKind.SKIN) SkinKind.CAPE else SkinKind.SKIN
        val options = store.entries.value.filter { it.kind == wanted }
        val none = getString(if (wanted == SkinKind.CAPE) R.string.skin_none else R.string.skin_plain_figure)
        val names = (listOf(none) + options.map { it.name }).toTypedArray()
        val current = options.indexOfFirst { it.id == companionId } + 1
        MaterialAlertDialogBuilder(this)
            .setTitle(if (wanted == SkinKind.CAPE) R.string.skin_pick_cape else R.string.skin_pick_skin)
            .setSingleChoiceItems(names, current) { d, which ->
                companionId = options.getOrNull(which - 1)?.id
                render(); d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.skin_preview, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val e = entry ?: return super.onOptionsItemSelected(item)
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.export -> exportTo.launch("${e.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.png")
            R.id.rename -> rename(e)
            R.id.delete -> MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.skin_delete_confirm, e.name))
                .setPositiveButton(R.string.skin_delete) { _, _ -> store.delete(e.id); finish() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun snack(res: Int) = Snackbar.make(binding.root, res, Snackbar.LENGTH_LONG).show()
    private fun snack(text: String) = Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()

    private fun rename(e: SkinEntry) {
        val input = EditText(this).apply { setText(e.name); setSingleLine(); selectAll() }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.skin_rename)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> store.rename(e.id, input.text.toString()); render() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun export(uri: Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { it.write(store.textureFile(entryId).readBytes()) } != null
                }.getOrDefault(false)
            }
            snack(if (ok) R.string.skin_exported else R.string.skin_export_failed)
        }
    }

    // ---- putting the skin on a Microsoft account ----

    private fun apply(e: SkinEntry) {
        val accounts = (application as QuantumApp).accounts.accounts.value.filter { it.type == AccountType.MICROSOFT }
        if (accounts.isEmpty()) return snack(R.string.skin_apply_no_account)
        if (accounts.size == 1) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.skin_apply_title)
                .setMessage(getString(R.string.skin_apply_confirm_one, e.name, accounts[0].username))
                .setPositiveButton(R.string.skin_apply_go) { _, _ -> upload(e, accounts[0]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.skin_apply_confirm, e.name))
                .setItems(accounts.map { it.username }.toTypedArray()) { _, i -> upload(e, accounts[i]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun upload(e: SkinEntry, account: Account) {
        snack(R.string.skin_loading)
        val app = application as QuantumApp
        lifecycleScope.launch {
            try {
                // Sign in again quietly if the saved token ran out, without switching the selected account.
                val fresh = if (account.needsRefresh) MicrosoftAuth.refresh(account).also { app.accounts.upsert(it, select = false) } else account
                val png = withContext(Dispatchers.IO) { store.textureFile(e.id).readBytes() }
                api.uploadSkin(fresh, png, e.model)
                snack(R.string.skin_apply_done)
            } catch (ex: Exception) {
                snack(getString(R.string.skin_apply_failed, ex.message ?: ex.javaClass.simpleName))
            }
        }
    }

    companion object {
        const val EXTRA_ID = "skin_id"
    }
}
