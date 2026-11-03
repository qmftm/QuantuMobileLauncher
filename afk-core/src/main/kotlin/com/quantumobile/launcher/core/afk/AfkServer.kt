package com.quantumobile.launcher.core.afk

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/** One server the AFK bot can join. [enabled] is the on/off switch and survives restarts. */
@Serializable
data class AfkServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = DEFAULT_PORT,
    /** [com.quantumobile.launcher.core.auth.Account.id] of the account to join with. */
    val accountId: String,
    val enabled: Boolean = false,
    /** Row icon: null = the server's own icon (from its status ping), "preset:<name>", or [ICON_CUSTOM] (a picked image). */
    val icon: String? = null,
) {
    val address get() = if (port == DEFAULT_PORT) host else "$host:$port"

    companion object {
        const val DEFAULT_PORT = 25565
        const val ICON_CUSTOM = "custom"
        const val ICON_PRESET = "preset:"

        /** Splits "host" or "host:port" (the format players share). Returns null for an empty or malformed address. */
        fun parseAddress(input: String, defaultPort: Int = DEFAULT_PORT): Pair<String, Int>? {
            val text = input.trim()
            if (text.isEmpty() || text.any { it.isWhitespace() }) return null
            val colon = text.lastIndexOf(':')
            if (colon < 0 || text.count { it == ':' } > 1) return text to defaultPort
            val host = text.substring(0, colon)
            val port = text.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return if (host.isEmpty()) null else host to port
        }
    }
}

@Serializable
private data class AfkFile(val servers: List<AfkServer> = emptyList())

/** Saves the AFK server list (with each server's on/off state) as `afk.json` in app-private storage. */
class AfkStore(private val file: File) {
    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<AfkServer>> get() = _servers

    fun get(id: String) = _servers.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(server: AfkServer) {
        val list = _servers.value
        val i = list.indexOfFirst { it.id == server.id }
        save(if (i < 0) list + server else list.toMutableList().also { it[i] = server })
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        save(_servers.value.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    @Synchronized
    fun disableAll() = save(_servers.value.map { it.copy(enabled = false) })

    @Synchronized
    fun remove(id: String) = save(_servers.value.filterNot { it.id == id })

    private fun load(): List<AfkServer> =
        runCatching { AfkJson.decodeFromString<AfkFile>(file.readText()).servers }.getOrDefault(emptyList())

    private fun save(list: List<AfkServer>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(AfkJson.encodeToString(AfkFile.serializer(), AfkFile(list)))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        _servers.value = list
    }
}
