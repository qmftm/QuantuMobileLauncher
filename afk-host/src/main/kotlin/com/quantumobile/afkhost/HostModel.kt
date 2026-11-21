package com.quantumobile.afkhost

import com.quantumobile.launcher.core.afk.AfkPhase

/** What the window shows about one server. */
data class ServerRowData(
    val name: String,
    val address: String,
    val account: String,
    val phase: AfkPhase,
    val enabled: Boolean,
    /** "접속됨 (1.21.4)", "재접속 대기 · 서버가 꺼졌어요" … ready to show. */
    val statusText: String,
    val detail: String?,
    /** The server's icon (PNG bytes), if it has one. */
    val icon: ByteArray?,
)

/** A picture of the host at one moment: everything the window needs, so the window never reaches into the bots. */
data class HostSnapshot(
    val name: String,
    val version: String,
    val code: String,
    val port: Int,
    val lanAddresses: List<String>,
    /** The Cloudflare tunnel address once it is open; null while opening. */
    val tunnelUrl: String?,
    val tunnelEnabled: Boolean,
    /** When the app last talked to this PC (epoch millis), or null if it has not yet. */
    val lastContactAt: Long?,
    val servers: List<ServerRowData>,
    val autostart: Boolean?,
    val autostartAvailable: Boolean,
    val logLines: List<String>,
    val now: Long,
)

/** Live facts the HTTP side and the tunnel report, for the window to read. */
object HostModel {
    @Volatile var tunnelUrl: String? = null
    @Volatile var tunnelEnabled: Boolean = true
    @Volatile var lastContactAt: Long? = null

    fun contacted() { lastContactAt = System.currentTimeMillis() }
}

/** Texts shared by the window and the tray. */
object HostText {
    fun status(phase: AfkPhase, enabled: Boolean, version: String?, message: String?, retryInMs: Long): Pair<String, String?> = when {
        !enabled -> "꺼짐" to null
        phase == AfkPhase.CONNECTED -> "접속됨" to version
        phase == AfkPhase.CONNECTING -> "접속 중" to message
        phase == AfkPhase.WAITING -> "재접속 대기" to listOfNotNull(message, retryInMs.takeIf { it > 0 }?.let { "${waitText(it)} 뒤" }).joinToString(" · ").ifBlank { null }
        phase == AfkPhase.ERROR -> "오류" to message
        else -> "꺼짐" to null
    }

    fun waitText(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s < 60) "${s}초" else if (s % 60 == 0L) "${s / 60}분" else "${s / 60}분 ${s % 60}초"
    }

    /** "방금 전", "12초 전", "3분 전" … */
    fun ago(thenMs: Long, nowMs: Long): String {
        val s = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
        return when {
            s < 5 -> "방금 전"
            s < 60 -> "${s}초 전"
            s < 3600 -> "${s / 60}분 전"
            else -> "${s / 3600}시간 전"
        }
    }

    /** The phone counts as connected while it asked within the last minute (the app asks every few seconds). */
    fun phoneConnected(lastContactAt: Long?, now: Long) = lastContactAt != null && now - lastContactAt < 60_000
}
