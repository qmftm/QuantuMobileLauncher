package com.quantumobile.launcher.core.afk

/** Where a server's bot is. */
enum class AfkPhase { OFF, CONNECTING, CONNECTED, WAITING, ERROR }

/**
 * A bot's state for the screen and the notification.
 * [message] is the latest detail (kick reason, error, "waiting for network"); [retryAt] is set while [AfkPhase.WAITING].
 */
data class AfkStatus(
    val phase: AfkPhase = AfkPhase.OFF,
    val message: String? = null,
    val version: String? = null,
    val retryAt: Long = 0,
    val since: Long = 0,
) {
    val isActive get() = phase != AfkPhase.OFF && phase != AfkPhase.ERROR

    companion object {
        val OFF = AfkStatus()
    }
}
