package com.quantumobile.launcher.ui

import android.os.Build
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.quantumobile.launcher.QuantumApp
import com.quantumobile.launcher.R
import com.quantumobile.launcher.core.runtime.RuntimeCatalog
import com.quantumobile.launcher.core.runtime.RuntimeDownloader
import com.quantumobile.launcher.core.runtime.RuntimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** "Download Java runtime" flow shared by Settings and the missing-runtime error on Home. */
object RuntimeInstallUi {
    private const val LINK_ID = "__link__"

    /** [required] puts matching Java versions first; [onDone] runs after a successful install. */
    fun show(fragment: Fragment, required: Int? = null, onDone: () -> Unit = {}) {
        val ctx = fragment.requireContext()
        val app = ctx.applicationContext as QuantumApp
        val loading = MaterialAlertDialogBuilder(ctx).setMessage(R.string.loading_runtimes).setCancelable(false).show()
        fragment.lifecycleScope.launch {
            val catalog = try {
                RuntimeCatalog.forDevice(RuntimeCatalog.fetch(app.settings.runtimeCatalogUrl), Build.SUPPORTED_ABIS.toList())
            } catch (e: Exception) {
                emptyList()
            } finally {
                loading.dismiss()
            }
            val sources = catalog.associateBy { it.url }
            val rows = catalog.sortedByDescending { it.java == required }.map { s ->
                Row(
                    s.url, "${s.name} · Java ${s.java}",
                    listOfNotNull(s.abi, s.sizeMb?.let { "$it MB" }).joinToString(" · "),
                    badge = if (s.java == required) ctx.getString(R.string.recommended) else null,
                )
            } + Row(LINK_ID, ctx.getString(R.string.enter_link), ctx.getString(R.string.enter_link_hint),
                icon = R.drawable.ic_add, iconTint = ctx.getColor(R.color.brand))

            PickerSheet.show(fragment, ctx.getString(R.string.download_runtime), rows, searchable = false) { row ->
                if (row.id == LINK_ID) askLink(fragment, app, onDone) else sources[row.id]?.let { download(fragment, app, it, onDone) }
            }
        }
    }

    private fun askLink(fragment: Fragment, app: QuantumApp, onDone: () -> Unit) {
        val ctx = fragment.requireContext()
        val layout = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = ctx.getString(R.string.runtime_link)
            helperText = ctx.getString(R.string.runtime_link_warning)
        }
        val input = TextInputEditText(layout.context).apply { isSingleLine = true }
        layout.addView(input)
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.enter_link)
            .setView(layout.withPadding())
            .setPositiveButton(R.string.download, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val url = input.text.toString().trim()
            if (url.startsWith("https://") && url.length > 12) {
                dialog.dismiss()
                download(fragment, app, RuntimeSource.fromUrl(url), onDone)
            } else {
                layout.error = ctx.getString(R.string.link_must_be_https)
            }
        }
        input.requestFocus()
    }

    private fun download(fragment: Fragment, app: QuantumApp, source: RuntimeSource, onDone: () -> Unit) {
        val ctx = fragment.requireContext()
        val label = TextView(ctx).apply { text = ctx.getString(R.string.downloading_runtime, source.fileName) }
        val bar = LinearProgressIndicator(ctx).apply { isIndeterminate = true; max = 100 }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * resources.displayMetrics.density).toInt() })
        }
        var job: Job? = null
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.download_runtime)
            .setView(content.withPadding())
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
            .show()

        job = fragment.lifecycleScope.launch {
            try {
                val runtime = RuntimeDownloader(app.runtimes, app.paths.cacheDir).install(source) { done, total ->
                    bar.post {
                        if (total > 0) {
                            bar.isIndeterminate = false
                            bar.setProgressCompat((done * 100 / total).toInt(), true)
                            label.text = ctx.getString(R.string.download_progress, source.fileName, done / 1_048_576, total / 1_048_576)
                        } else {
                            label.text = ctx.getString(R.string.download_progress_unknown, source.fileName, done / 1_048_576)
                        }
                    }
                }
                dialog.dismiss()
                fragment.view?.let {
                    Snackbar.make(it, ctx.getString(R.string.runtime_installed, runtime.fullVersion), Snackbar.LENGTH_LONG).show()
                }
                onDone()
            } catch (e: CancellationException) {
                dialog.dismiss()
                throw e
            } catch (e: Exception) {
                dialog.dismiss()
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.import_failed)
                    .setMessage(e.message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

}
