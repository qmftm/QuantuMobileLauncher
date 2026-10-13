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
            val old = File(File(gameDir, it.projectType.folder), it.fileName)
            old.delete()
            File(old.path + DISABLED_SUFFIX).delete()
        }
        val mod = InstalledMod(item.projectId, item.version.id, item.version.versionNumber, item.title, item.type.api, name, item.dependency)
        store.put(mod)
        mod
    }

    /**
     * Everything in the instance's `mods` folder: what the launcher installed plus jars the user dropped in by hand
     * (listed so they can be switched off or removed too). Disabled jars (`*.jar.disabled`) are included.
     */
    fun listAllMods(): List<InstalledMod> {
        val tracked = store.list()
        val known = tracked.filter { it.projectType == ProjectType.MOD }.map { it.fileName }.toSet()
        val untracked = File(gameDir, ProjectType.MOD.folder).listFiles().orEmpty()
            .filter { it.isFile && (it.name.endsWith(".jar") || it.name.endsWith(".jar$DISABLED_SUFFIX")) }
            .map { it.name.removeSuffix(DISABLED_SUFFIX) }
            .filter { it !in known }
            .distinct()
            .sorted()
            .map { InstalledMod("file:$it", "", "", it.removeSuffix(".jar"), ProjectType.MOD.api, it) }
        return tracked + untracked
    }

    private fun fileOf(mod: InstalledMod) = File(File(gameDir, mod.projectType.folder), mod.fileName)
    private val packs by lazy { PackManager(gameDir) }

    /**
     * Whether the game will use [mod]: mods are on unless renamed to `*.disabled`, resource packs are on when listed
     * in options.txt, and a shader is on when it is the selected shader pack.
     */
    fun isEnabled(mod: InstalledMod): Boolean = when (mod.projectType) {
        ProjectType.MOD -> fileOf(mod).isFile
        ProjectType.RESOURCEPACK -> mod.fileName in packs.enabledResourcePacks()
        ProjectType.SHADER -> packs.activeShader() == mod.fileName
    }

    fun setEnabled(mod: InstalledMod, enabled: Boolean) {
        when (mod.projectType) {
            ProjectType.MOD -> {
                val on = fileOf(mod)
                val off = File(on.path + DISABLED_SUFFIX)
                if (enabled && off.isFile) { on.delete(); off.renameTo(on) }
                else if (!enabled && on.isFile) { off.delete(); on.renameTo(off) }
            }
            ProjectType.RESOURCEPACK -> packs.setResourcePackEnabled(mod.fileName, enabled)
            ProjectType.SHADER -> when {
                enabled -> packs.setActiveShader(mod.fileName)
                packs.activeShader() == mod.fileName -> packs.setActiveShader(null)
            }
        }
    }

    fun remove(mod: InstalledMod) {
        // Turn it off in the game's settings first so the game is not left pointing at a missing file.
        when (mod.projectType) {
            ProjectType.RESOURCEPACK -> packs.setResourcePackEnabled(mod.fileName, false)
            ProjectType.SHADER -> if (packs.activeShader() == mod.fileName) packs.setActiveShader(null)
            ProjectType.MOD -> Unit
        }
        fileOf(mod).delete()
        File(fileOf(mod).path + DISABLED_SUFFIX).delete()
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
        const val DISABLED_SUFFIX = ".disabled"
    }
}
