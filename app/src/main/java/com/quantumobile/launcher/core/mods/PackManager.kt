package com.quantumobile.launcher.core.mods

import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A resource pack or shader pack found in the game directory (zip file or unpacked folder). */
data class PackEntry(val name: String, val isDirectory: Boolean, val sizeBytes: Long)

/**
 * Lists, imports and configures resource packs and shader packs of one game directory.
 *
 * Settings are written into the files the game itself reads: `options.txt` (`resourcePacks:`) for resource packs and
 * `config/iris.properties` / `optionsshaders.txt` for Iris and OptiFine shaders.
 */
class PackManager(private val gameDir: File) {

    fun folder(type: ProjectType): File = File(gameDir, type.folder)

    fun list(type: ProjectType): List<PackEntry> =
        folder(type).listFiles()
            .orEmpty()
            .filter { !it.name.startsWith(".") && (it.isDirectory || it.name.endsWith(".zip", ignoreCase = true)) }
            .map { PackEntry(it.name, it.isDirectory, if (it.isDirectory) 0 else it.length()) }
            .sortedBy { it.name.lowercase() }

    /** Copies a pack the user picked into the right folder. Returns the stored file name. */
    fun import(type: ProjectType, displayName: String, input: InputStream): String {
        val name = File(displayName).name
        if (name.isBlank() || name.startsWith(".")) throw IOException("Invalid file name: $displayName")
        if (!name.endsWith(".zip", ignoreCase = true)) throw IOException("Only .zip packs can be added")
        val dest = File(folder(type).apply { mkdirs() }, name)
        val tmp = File(dest.path + ".part")
        input.use { src -> tmp.outputStream().use { src.copyTo(it) } }
        if (!tmp.renameTo(dest)) { dest.delete(); tmp.renameTo(dest) }
        return name
    }

    fun delete(type: ProjectType, name: String) {
        val file = File(folder(type), File(name).name)
        if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    // ---- resource packs (options.txt) ----

    private val options get() = File(gameDir, "options.txt")

    /** Enabled resource packs, lowest priority first (the order the game stores them). "vanilla" is the built-in pack. */
    fun enabledResourcePacks(): List<String> {
        val line = options.takeIf { it.isFile }?.readLines()?.firstOrNull { it.startsWith(RP_KEY) } ?: return emptyList()
        return runCatching { COMPACT.decodeFromString(ListSerializer(String.serializer()), line.removePrefix(RP_KEY)) }
            .getOrDefault(emptyList())
            .map { it.removePrefix(FILE_PREFIX) }
    }

    fun setEnabledResourcePacks(files: List<String>) {
        val ids = listOf("vanilla") + files.filter { it != "vanilla" }.map { FILE_PREFIX + it }
        val value = COMPACT.encodeToString(ListSerializer(String.serializer()), ids)
        writeLine(options, RP_KEY, RP_KEY + value)
    }

    /** Moves an enabled pack up (more priority) or down. Returns the new order. */
    fun movePack(name: String, up: Boolean): List<String> {
        val list = enabledResourcePacks().filter { it != "vanilla" }.toMutableList()
        val i = list.indexOf(name)
        if (i < 0) return list
        val j = if (up) i + 1 else i - 1 // later in the list = applied on top
        if (j in list.indices) { list[i] = list[j].also { list[j] = list[i] } }
        setEnabledResourcePacks(list)
        return list
    }

    fun setResourcePackEnabled(name: String, enabled: Boolean) {
        val list = enabledResourcePacks().filter { it != "vanilla" && it != name }.toMutableList()
        if (enabled) list += name
        setEnabledResourcePacks(list)
    }

    // ---- shaders ----

    private val iris get() = File(gameDir, "config/iris.properties")
    private val optifineShaders get() = File(gameDir, "optionsshaders.txt")

    /** The active shader pack name, or null when shaders are off. */
    fun activeShader(): String? {
        val props = iris.takeIf { it.isFile }?.readLines().orEmpty()
        val pack = props.firstOrNull { it.startsWith("shaderPack=") }?.substringAfter('=')?.trim().orEmpty()
        val enabled = props.firstOrNull { it.startsWith("enableShaders=") }?.substringAfter('=')?.trim()
        if (pack.isNotEmpty() && pack != "(internal)" && enabled != "false") return pack
        val of = optifineShaders.takeIf { it.isFile }?.readLines()?.firstOrNull { it.startsWith("shaderPack=") }
            ?.substringAfter('=')?.trim().orEmpty()
        return of.takeIf { it.isNotEmpty() && it != "OFF" && it != "(internal)" }
    }

    /** Selects [name] (or turns shaders off with null) for both Iris and OptiFine. */
    fun setActiveShader(name: String?) {
        iris.parentFile?.mkdirs()
        writeLine(iris, "shaderPack=", "shaderPack=" + (name ?: ""))
        writeLine(iris, "enableShaders=", "enableShaders=${name != null}")
        writeLine(optifineShaders, "shaderPack=", "shaderPack=" + (name ?: "OFF"))
    }

    /** Replaces the line starting with [prefix] (or appends one), leaving every other line untouched. */
    private fun writeLine(file: File, prefix: String, line: String) {
        val lines = if (file.isFile) file.readLines().toMutableList() else mutableListOf()
        val i = lines.indexOfFirst { it.startsWith(prefix) }
        if (i >= 0) lines[i] = line else lines += line
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(lines.joinToString("\n") + "\n")
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        private val COMPACT = Json
        private const val RP_KEY = "resourcePacks:"
        private const val FILE_PREFIX = "file/"
    }
}
