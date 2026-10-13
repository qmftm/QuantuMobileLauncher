package com.quantumobile.launcher.core.lwjgl

import com.quantumobile.launcher.core.LauncherPaths
import com.quantumobile.launcher.core.net.DownloadTask
import com.quantumobile.launcher.core.net.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs [LwjglPackage] into app-private storage: the jar into `lwjgl3/` (put on the game's classpath in place of
 * Mojang's desktop LWJGL) and the natives into `lwjgl-natives/<abi>/` (loaded with dlopen, which Android allows from
 * app data).
 */
class LwjglInstaller(private val paths: LauncherPaths, private val downloader: Downloader = Downloader()) {

    fun nativesDir(abi: String) = File(paths.lwjglNatives, abi)

    /** Cheap check (existence and size, no hashing) used by the UI and before every launch. */
    fun isInstalled(pkg: LwjglPackage, abi: String): Boolean {
        val natives = pkg.natives[abi] ?: return false
        return sizeMatches(File(paths.lwjgl, pkg.jar.name), pkg.jar) &&
            natives.all { sizeMatches(File(nativesDir(abi), it.name), it) }
    }

    private fun sizeMatches(file: File, expected: LwjglFile) = file.isFile && (expected.size < 0 || file.length() == expected.size)

    suspend fun install(pkg: LwjglPackage, abi: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Unit =
        withContext(Dispatchers.IO) {
            val natives = pkg.natives[abi] ?: error("No LWJGL natives for $abi")
            paths.lwjgl.mkdirs()
            nativesDir(abi).mkdirs()
            val tasks = buildList {
                add(DownloadTask(pkg.jar.url, File(paths.lwjgl, pkg.jar.name), size = pkg.jar.size, sha256 = pkg.jar.sha256))
                natives.forEach { add(DownloadTask(it.url, File(nativesDir(abi), it.name), size = it.size, sha256 = it.sha256)) }
            }
            downloader.downloadAll(tasks, parallelism = 3, onProgress = onProgress)

            // The package owns these folders: other LWJGL jars (e.g. a manual import) would clash with the merged jar.
            paths.lwjgl.listFiles { f -> f.name.endsWith(".jar") && f.name != pkg.jar.name }?.forEach { it.delete() }
            nativesDir(abi).listFiles { f -> natives.none { n -> n.name == f.name } }?.forEach { it.delete() }
            Unit
        }
}
