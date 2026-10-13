package com.quantumobile.launcher.core.mods

import com.quantumobile.launcher.core.LauncherJson
import com.quantumobile.launcher.core.net.DownloadTask
import com.quantumobile.launcher.core.net.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class InstalledMod(
    val projectId: String,
    val versionId: String,
    val versionNumber: String,
    val title: String,
    /** [ProjectType.api] value. */
    val type: String,
    val fileName: String,
    /** True when installed only because another project requires it. */
    val dependency: Boolean = false,
) {
    val projectType get() = ProjectType.fromApi(type)
}

@Serializable
private data class StoreFile(val mods: List<InstalledMod> = emptyList())

/** Remembers what the launcher installed into one game directory (`qml-mods.json`). */
class InstalledModStore(private val file: File) {
    @Synchronized
    fun list(): List<InstalledMod> =
        runCatching { LauncherJson.decodeFromString<StoreFile>(file.readText()).mods }.getOrDefault(emptyList())

    fun has(projectId: String) = list().any { it.projectId == projectId }

    @Synchronized
    fun put(mod: InstalledMod) = save(list().filterNot { it.projectId == mod.projectId } + mod)

    @Synchronized
    fun remove(projectId: String) = save(list().filterNot { it.projectId == projectId })

    private fun save(mods: List<InstalledMod>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(LauncherJson.encodeToString(StoreFile.serializer(), StoreFile(mods)))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}

data class PlanItem(val projectId: String, val title: String, val type: ProjectType, val version: ModVersion, val dependency: Boolean)

/** [missing] lists required dependencies that have no build for the target (the mod may not work without them). */
data class InstallPlan(val items: List<PlanItem>, val missing: List<String>)

class ModInstaller(
    private val api: ModrinthApi,
    private val gameDir: File,
    private val downloader: Downloader = Downloader(),
    /** Only files from here are downloaded (tests point this at a local server). */
    private val allowedPrefix: String = "https://cdn.modrinth.com/",
) {
    val store = InstalledModStore(File(gameDir, "qml-mods.json"))

    /** Works out which versions to download, including required dependencies not yet installed. */
    suspend fun resolve(projectId: String, title: String, type: ProjectType, target: ModTarget): InstallPlan {
        if (type.usesLoader && target.isVanilla) {
            throw ModException("This instance has no mod loader. Create a Fabric instance to use mods.")
        }
        val items = LinkedHashMap<String, PlanItem>()
        val missing = ArrayList<String>()

        suspend fun visit(pid: String, known: String?, pType: ProjectType, isDep: Boolean, depth: Int) {
            if (pid in items || (isDep && store.has(pid))) return
            val version = ModrinthApi.pick(api.versions(pid, target, pType))
            if (version == null) {
                val name = known ?: api.projectTitle(pid)
                if (isDep) { missing += name; return }
                throw ModException("$name has no version for Minecraft ${target.gameVersion}" +
                    (if (pType.usesLoader) " (${target.loaders.joinToString("/")})" else ""))
            }
            items[pid] = PlanItem(pid, known ?: api.projectTitle(pid), pType, version, isDep)
            if (depth >= MAX_DEPTH) return
            for (dep in version.dependencies) {
                if (dep.type != "required") continue
                val depId = dep.projectId ?: dep.versionId?.let { api.version(it).projectId } ?: continue
                visit(depId, null, ProjectType.MOD, true, depth + 1)
            }
        }
        visit(projectId, title, type, false, 0)
        return InstallPlan(items.values.toList(), missing)
    }

    /**
     * Downloads and records every item. [onProgress] is called on the caller's own thread/dispatcher (the UI thread
     * when called from a screen or the task runner), never from the IO threads doing the work, so it may touch views.
     */
    suspend fun apply(plan: InstallPlan, onProgress: (done: Int, total: Int, title: String) -> Unit = { _, _, _ -> }): List<InstalledMod> =
        plan.items.mapIndexed { i, item ->
            onProgress(i, plan.items.size, item.title)
            install(item).also { onProgress(i + 1, plan.items.size, item.title) }
        }

    private suspend fun install(item: PlanItem): InstalledMod = withContext(Dispatchers.IO) {
        val file = item.version.primaryFile ?: throw ModException("${item.title} has no downloadable file")
        if (!file.url.startsWith(allowedPrefix)) throw ModException("Refusing to download from ${file.url}")
        val name = File(file.filename).name // strips any path components
        if (name.isBlank() || name.startsWith(".")) throw ModException("Invalid file name: ${file.filename}")
        val dest = File(File(gameDir, item.type.folder), name)
        downloader.download(DownloadTask(file.url, dest, file.sha1, file.size))

        // Replace an older build of the same project instead of leaving two jars behind.
        store.list().firstOrNull { it.projectId == item.projectId && it.fileName != name }?.let {
            File(File(gameDir, it.projectType.folder), it.fileName).delete()
        }
        val mod = InstalledMod(item.projectId, item.version.id, item.version.versionNumber, item.title, item.type.api, name, item.dependency)
        store.put(mod)
        mod
    }

    fun remove(mod: InstalledMod) {
        File(File(gameDir, mod.projectType.folder), mod.fileName).delete()
        store.remove(mod.projectId)
    }

    /** Installed projects that have a newer compatible release. */
    suspend fun findUpdates(target: ModTarget): List<PlanItem> {
        val updates = ArrayList<PlanItem>()
        for (mod in store.list()) {
            val latest = runCatching { ModrinthApi.pick(api.versions(mod.projectId, target, mod.projectType)) }.getOrNull() ?: continue
            if (latest.id != mod.versionId) updates += PlanItem(mod.projectId, mod.title, mod.projectType, latest, mod.dependency)
        }
        return updates
    }

    companion object {
        private const val MAX_DEPTH = 5
    }
}
