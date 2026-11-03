package com.quantumobile.launcher.core.afk

import kotlinx.serialization.Serializable

enum class AfkLogKind { INFO, CHAT, WARN, ERROR }

/** One line of a server's AFK log: connection events and the chat the bot sees. */
@Serializable
data class AfkLogLine(val time: Long, val kind: AfkLogKind, val text: String)

/** The last [max] lines per server, kept in memory. Thread-safe. */
class AfkLogBook(private val max: Int = 500) {
    private val lines = HashMap<String, ArrayDeque<AfkLogLine>>()

    @Synchronized
    fun add(id: String, line: AfkLogLine) {
        val q = lines.getOrPut(id) { ArrayDeque() }
        q.addLast(line)
        while (q.size > max) q.removeFirst()
    }

    /** Lines of server [id] newer than [since] (epoch ms), oldest first. */
    @Synchronized
    fun get(id: String, since: Long = 0): List<AfkLogLine> = lines[id]?.filter { it.time > since }.orEmpty()

    @Synchronized
    fun remove(id: String) { lines.remove(id) }
}
