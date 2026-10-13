package com.quantumobile.launcher.core.instance

import com.quantumobile.launcher.core.LauncherJson
import com.quantumobile.launcher.core.LauncherPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable
data class Instance(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val versionId: String,
    /** When true the instance gets its own game directory (mods, saves, options) under `instances/`. */
    val isolated: Boolean = true,
    /** Overrides the global memory setting when > 0. */
    val memoryMb: Int = 0,
    val extraJvmArgs: String = "",
    /** Epoch millis of the last launch, 0 if never played. */
    val lastPlayed: Long = 0,
) {
    fun gameDir(paths: LauncherPaths): File =
        if (isolated) File(paths.instances, id.take(8) + "-" + name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        else paths.gameRoot
}

@Serializable
private data class InstanceFile(val selected: String? = null, val instances: List<Instance> = emptyList())

class InstanceStore(private val file: File) {
    private var data = runCatching { LauncherJson.decodeFromString<InstanceFile>(file.readText()) }.getOrDefault(InstanceFile())
    private val _instances = MutableStateFlow(data.instances)
    val instances: StateFlow<List<Instance>> get() = _instances

    val selected: Instance? get() = data.instances.firstOrNull { it.id == data.selected } ?: data.instances.firstOrNull()

    @Synchronized
    fun add(instance: Instance) = save(InstanceFile(instance.id, data.instances + instance))

    @Synchronized
    fun select(id: String) = save(data.copy(selected = id))

    @Synchronized
    fun update(instance: Instance) = save(data.copy(instances = data.instances.map { if (it.id == instance.id) instance else it }))

    fun markPlayed(id: String) {
        data.instances.firstOrNull { it.id == id }?.let { update(it.copy(lastPlayed = System.currentTimeMillis())) }
    }

    /** Most recently played first, never-played instances after in creation order. */
    val sorted: List<Instance> get() = data.instances.sortedByDescending { it.lastPlayed }

    @Synchronized
    fun remove(id: String) = save(InstanceFile(data.selected.takeUnless { it == id }, data.instances.filterNot { it.id == id }))

    private fun save(newData: InstanceFile) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(LauncherJson.encodeToString(InstanceFile.serializer(), newData))
        tmp.renameTo(file)
        data = newData
        _instances.value = newData.instances
    }
}
