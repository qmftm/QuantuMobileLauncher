package com.quantumobile.launcher.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import com.quantumobile.launcher.QuantumApp
import com.quantumobile.launcher.R
import com.quantumobile.launcher.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsFragment : PreferenceFragmentCompat() {
    private val app get() = requireActivity().application as QuantumApp

    private val pickRuntime = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importRuntime)
    }
    private val pickLwjgl = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importLwjgl(uris)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<SeekBarPreference>(Settings.KEY_MEMORY)?.apply {
            if (!preferenceManager.sharedPreferences!!.contains(Settings.KEY_MEMORY)) value = Settings.defaultMemoryMb(requireContext())
        }
        findPreference<Preference>("import_runtime")?.setOnPreferenceClickListener {
            pickRuntime.launch(arrayOf("application/x-xz", "application/x-tar", "application/gzip", "application/octet-stream", "*/*"))
            true
        }
        findPreference<Preference>("import_lwjgl")?.setOnPreferenceClickListener {
            pickLwjgl.launch(arrayOf("application/java-archive", "application/octet-stream", "*/*"))
            true
        }
        findPreference<Preference>("manage_runtimes")?.setOnPreferenceClickListener { manageRuntimes(); true }
        findPreference<Preference>("game_dir")?.summary = app.paths.gameRoot.absolutePath
        updateSummaries()
    }

    private fun updateSummaries() {
        findPreference<Preference>("manage_runtimes")?.summary = app.runtimes.list()
            .joinToString("\n") { "${it.name} (Java ${it.fullVersion})" }
            .ifEmpty { getString(R.string.no_runtime) }
        findPreference<Preference>("import_lwjgl")?.summary = app.paths.lwjgl.listFiles()?.size?.takeIf { it > 0 }
            ?.let { getString(R.string.lwjgl_count, it) } ?: getString(R.string.lwjgl_summary)
    }

    private fun displayName(uri: Uri): String =
        requireContext().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()

    private fun importRuntime(uri: Uri) {
        val name = displayName(uri)
        val compression = when {
            name.endsWith(".xz") -> "xz"
            name.endsWith(".gz") || name.endsWith(".tgz") -> "gz"
            else -> "tar"
        }
        val progress = AlertDialog.Builder(requireContext()).setMessage(getString(R.string.importing, name)).setCancelable(false).show()
        lifecycleScope.launch {
            try {
                val runtime = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)!!.use { app.runtimes.install(name, it, compression) }
                }
                Toast.makeText(requireContext(), getString(R.string.runtime_installed, runtime.fullVersion), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                AlertDialog.Builder(requireContext()).setTitle(R.string.import_failed).setMessage(e.message)
                    .setPositiveButton(android.R.string.ok, null).show()
            } finally {
                progress.dismiss()
                updateSummaries()
            }
        }
    }

    private fun importLwjgl(uris: List<Uri>) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    val name = displayName(uri).takeIf { it.endsWith(".jar") } ?: return@forEach
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        File(app.paths.lwjgl, File(name).name).outputStream().use { input.copyTo(it) }
                    }
                }
            }
            updateSummaries()
        }
    }

    private fun manageRuntimes() {
        val runtimes = app.runtimes.list()
        if (runtimes.isEmpty()) return
        val lwjglCount = app.paths.lwjgl.listFiles()?.size ?: 0
        val items = runtimes.map { "${it.name} — Java ${it.fullVersion}" } +
            if (lwjglCount > 0) listOf(getString(R.string.clear_lwjgl)) else emptyList()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.tap_to_delete)
            .setItems(items.toTypedArray()) { _, which ->
                if (which < runtimes.size) app.runtimes.delete(runtimes[which].name)
                else app.paths.lwjgl.listFiles()?.forEach { it.delete() }
                updateSummaries()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
