package com.quantumobile.launcher.ui

import android.content.Context
import com.quantumobile.launcher.R
import com.quantumobile.launcher.core.afk.AfkPhase
import com.quantumobile.launcher.core.afk.AfkStatus

/** Short state name ("접속됨"). */
fun AfkStatus.label(context: Context): String = context.getString(
    when (phase) {
        AfkPhase.OFF -> R.string.afk_off
        AfkPhase.CONNECTING -> R.string.afk_connecting
        AfkPhase.CONNECTED -> R.string.afk_connected
        AfkPhase.WAITING -> R.string.afk_waiting
        AfkPhase.ERROR -> R.string.afk_error
    },
)

/** State plus detail: "재접속 대기 (35초) · 서버가 종료됐어요". */
fun AfkStatus.describe(context: Context, now: Long = System.currentTimeMillis()): String {
    val head = when {
        phase == AfkPhase.WAITING && retryAt > now -> waitingText(context, ((retryAt - now + 999) / 1000).toInt())
        phase == AfkPhase.CONNECTED && version != null -> "${label(context)} ($version)"
        else -> label(context)
    }
    return if (message.isNullOrBlank()) head else "$head · $message"
}

/** "재접속 대기 (35초)", or with minutes for the long waits: "재접속 대기 (4분 30초)". */
private fun waitingText(context: Context, seconds: Int): String =
    if (seconds < 60) context.getString(R.string.afk_waiting_seconds, seconds)
    else context.getString(R.string.afk_waiting_minutes, seconds / 60, seconds % 60)
