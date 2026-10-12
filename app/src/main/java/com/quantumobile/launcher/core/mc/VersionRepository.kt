package com.quantumobile.launcher.core.mc

import com.quantumobile.launcher.core.LauncherPaths
import com.quantumobile.launcher.core.net.DownloadTask
import com.quantumobile.launcher.core.net.Downloader
import com.quantumobile.launcher.core.net.Http
import com.quantumobile.launcher.core.parseJsonObject
import com.quantumobile.launcher.core.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class VersionRepository(
    private val paths: LauncherPaths,
    private val downloader: Downloader = Downloader(),
) {
    private val manifestCache get() = File(paths.versions, "version_manifest_v2.json")
    @Volatile private var manifest: VersionManifest? = null

    /** Fetches the Mojang manifest, falling back to the cached copy when offline. */
    suspend fun manifest(refresh: Boolean = false): VersionManifest = withContext(Dispatchers.IO) {
        manifest?.takeIf { !refresh }?.let { return@withContext it }
        val text = try {
            Http.getString(VersionManifest.URL).also {
                paths.versions.mkdirs()
                manifestCache.writeText(it)
            }
        } catch (e: IOException) {
            if (manifestCache.isFile) manifestCache.readText() else throw e
        }
        VersionManifest.parse(text).also { manifest = it }
    }

    /** IDs of versions that have a JSON on disk (vanilla, Fabric, or manually installed profiles). */
    fun installedIds(): List<String> =
        paths.versions.listFiles().orEmpty()
            .filter { it.isDirectory && File(it, "${it.name}.json").isFile }
            .map { it.name }
            .sorted()

    /** Loads [id] (downloading its JSON if necessary) and resolves the `inheritsFrom` chain. */
    suspend fun resolve(id: String, depth: Int = 0): VersionInfo = withContext(Dispatchers.IO) {
        check(depth < 8) { "inheritsFrom chain too deep at $id" }
        val json = parseJsonObject(ensureJson(id).readText())
        val parentId = json.str("inheritsFrom")
        if (parentId == null) VersionInfo.parse(json) else VersionInfo.merge(json, resolve(parentId, depth + 1))
    }

    private suspend fun ensureJson(id: String): File {
        val file = paths.versionJson(id)
        val entry = runCatching { manifest().find(id) }.getOrNull()
        if (entry != null) {
            downloader.download(DownloadTask(entry.url, file, entry.sha1))
        } else if (!file.isFile) {
            throw IOException("Unknown version '$id' (not installed and not in the Mojang manifest)")
        }
        return file
    }

    fun saveProfile(id: String, json: String) {
        val file = paths.versionJson(id)
        file.parentFile?.mkdirs()
        file.writeText(json)
    }
}
