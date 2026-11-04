package com.quantumobile.launcher.core.skin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** One saved skin or cape in the library. */
@Serializable
data class SkinEntry(
    val id: String,
    val name: String,
    val kind: SkinKind,
    /** Skins only: slim (Alex) arms. */
    val slim: Boolean = false,
    val modified: Long = 0,
) {
    val model get() = SkinModel.of(slim)
}

/**
 * The saved skins and capes, in app-private storage: `index.json` plus `<id>.png` (the texture) and
 * `<id>-thumb.png` (a small preview for lists). Writes go through a temporary file, so a crash never leaves a
 * half-written texture.
 */
class SkinStore(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val indexFile = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<SkinEntry>> get() = _entries

    fun get(id: String) = _entries.value.firstOrNull { it.id == id }
    fun textureFile(id: String) = File(dir, "$id.png")
    fun thumbFile(id: String) = File(dir, "$id-thumb.png")

    @Synchronized
    fun add(name: String, kind: SkinKind, slim: Boolean, png: ByteArray, thumb: ByteArray): SkinEntry {
        val entry = SkinEntry(UUID.randomUUID().toString(), name.trim().ifEmpty { defaultName(kind) }, kind, slim, clock())
        writeFiles(entry.id, png, thumb)
        save(listOf(entry) + _entries.value)
        return entry
    }

    /** Switches a skin between classic (4 px) and slim (3 px) arms. */
    @Synchronized
    fun setSlim(id: String, slim: Boolean) {
        save(_entries.value.map { if (it.id == id) it.copy(slim = slim, modified = clock()) else it })
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val clean = name.trim().ifEmpty { return }
        save(_entries.value.map { if (it.id == id) it.copy(name = clean) else it })
    }

    @Synchronized
    fun delete(id: String) {
        textureFile(id).delete(); thumbFile(id).delete()
        save(_entries.value.filterNot { it.id == id })
    }

    private fun writeFiles(id: String, png: ByteArray, thumb: ByteArray) {
        dir.mkdirs()
        atomicWrite(textureFile(id), png)
        if (thumb.isNotEmpty()) atomicWrite(thumbFile(id), thumb)
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    private fun save(list: List<SkinEntry>) {
        dir.mkdirs()
        atomicWrite(indexFile, json.encodeToString(ListSerializer(SkinEntry.serializer()), list).toByteArray())
        _entries.value = list
    }

    private fun load(): List<SkinEntry> =
        runCatching { json.decodeFromString(ListSerializer(SkinEntry.serializer()), indexFile.readText()) }
            .getOrDefault(emptyList())
            .filter { textureFile(it.id).isFile } // an entry whose texture is gone is not usable

    private fun defaultName(kind: SkinKind) = if (kind == SkinKind.CAPE) "망토" else "스킨"
}
