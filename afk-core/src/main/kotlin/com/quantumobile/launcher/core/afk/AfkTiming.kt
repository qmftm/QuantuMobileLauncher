package com.quantumobile.launcher.core.afk

import kotlin.random.Random

/** The bot's timings: how often it sneaks and how long it waits before reconnecting. */
class AfkTiming(
    private val random: Random = Random.Default,
    val sneakMinMs: Long = 20_000,
    val sneakMaxMs: Long = 45_000,
    val sneakHoldMs: Long = 600,
) {
    /** Random wait before the next sneak, in [sneakMinMs, sneakMaxMs]. */
    fun nextSneakDelayMs(): Long = random.nextLong(sneakMinMs, sneakMaxMs + 1)
}

/**
 * Reconnect delay: 10 s, 20 s, 40 s, 60 s, then 3 min and 5 min (the last step repeats until a join succeeds).
 * Short waits cover a quick restart; the long ones keep a server that stays down from being hammered.
 * [reset] after a successful join.
 */
class Backoff(private val stepsMs: List<Long> = DEFAULT_STEPS_MS) {
    private var attempt = 0

    fun nextDelayMs(): Long {
        val delay = stepsMs[attempt.coerceAtMost(stepsMs.lastIndex)]
        if (attempt < stepsMs.lastIndex) attempt++
        return delay
    }

    fun reset() { attempt = 0 }

    companion object {
        val DEFAULT_STEPS_MS = listOf(10_000L, 20_000L, 40_000L, 60_000L, 180_000L, 300_000L)

        /** "10초", "3분", "4분 30초". */
        fun describe(ms: Long): String {
            val total = (ms / 1000).coerceAtLeast(0)
            val m = total / 60
            val sec = total % 60
            return when {
                m == 0L -> "${sec}초"
                sec == 0L -> "${m}분"
                else -> "${m}분 ${sec}초"
            }
        }
    }
}
