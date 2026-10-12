package com.quantumobile.launcher.core.mc

import com.quantumobile.launcher.core.LauncherPaths
import com.quantumobile.launcher.core.net.DownloadTask
import com.quantumobile.launcher.core.net.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun interface ProgressListener {
    fun onProgress(stage: String, done: Int, total: Int)
}

/** Downloads everything a resolved version needs: client jar, libraries, assets and logging config. */
class GameInstaller(
    private val paths: LauncherPaths,
    private val downloader: Downloader = Downloader(),
    private val env: RuleEnv = RuleEnv(),
) {
    suspend fun install(version: VersionInfo, gameDir: File, listener: ProgressListener) = withContext(Dispatchers.IO) {
        paths.ensureDirs()

        listener.onProgress("Client", 0, 1)
        val client = version.clientDownload
        val jar = paths.versionJar(version.jarId)
        if (client != null) {
            downloader.download(DownloadTask(client.url, jar, client.sha1, client.size))
        } else check(jar.isFile) { "No client jar for ${version.jarId}" }

        val libs = libraryTasks(version)
        downloader.downloadAll(libs) { d, t -> listener.onProgress("Libraries", d, t) }

        installAssets(version, gameDir, listener)

        version.logging?.let { log ->
            downloader.download(DownloadTask(log.url, File(paths.assets, "log_configs/${log.id}"), log.sha1, log.size))
        }
        listener.onProgress("Done", 1, 1)
    }

    fun libraryTasks(version: VersionInfo): List<DownloadTask> =
        classpathLibraries(version, env, hasLwjglOverride(paths)).map { lib ->
            DownloadTask(lib.downloadUrl, File(paths.libraries, lib.path), lib.artifact?.sha1, lib.artifact?.size ?: -1)
        }

    private suspend fun installAssets(version: VersionInfo, gameDir: File, listener: ProgressListener) {
        val ref = version.assetIndex ?: return
        val indexFile = File(paths.assets, "indexes/${ref.id}.json")
        downloader.download(DownloadTask(ref.url, indexFile, ref.sha1, ref.size))
        val index = AssetIndex.parse(indexFile.readText())

        val tasks = index.objects.map { obj ->
            DownloadTask(
                url = AssetIndex.RESOURCES_URL + obj.objectPath,
                dest = File(paths.assets, "objects/${obj.objectPath}"),
                sha1 = obj.hash,
                size = obj.size,
            )
        }
        downloader.downloadAll(tasks, parallelism = 16) { d, t -> listener.onProgress("Assets", d, t) }

        // Pre-1.7 versions read assets from a flat directory instead of the hashed object store.
        if (index.isVirtual || index.mapToResources) {
            val target = if (index.mapToResources) File(gameDir, "resources") else File(paths.assets, "virtual/${ref.id}")
            index.objects.forEach { obj ->
                val dest = File(target, obj.path)
                if (!dest.isFile || dest.length() != obj.size) {
                    dest.parentFile?.mkdirs()
                    File(paths.assets, "objects/${obj.objectPath}").copyTo(dest, overwrite = true)
                }
            }
        }
    }

    companion object {
        fun hasLwjglOverride(paths: LauncherPaths) =
            paths.lwjgl.listFiles { f -> f.name.endsWith(".jar") }?.isNotEmpty() == true

        /**
         * Libraries that go on the classpath, in order, de-duplicated by maven key (first wins, so mod
         * loader overrides beat vanilla). Desktop natives are dropped; LWJGL is dropped when an
         * Android-compatible build has been provided.
         */
        fun classpathLibraries(version: VersionInfo, env: RuleEnv, lwjglOverride: Boolean): List<Library> {
            val seen = HashSet<String>()
            return version.libraries.filter { lib ->
                Rules.isAllowed(lib.rules, env) &&
                    !lib.isDesktopNative &&
                    !(lwjglOverride && lib.coords.group == "org.lwjgl") &&
                    seen.add(lib.coords.key)
            }
        }
    }
}
