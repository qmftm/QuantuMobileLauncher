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

/** Reconnect delay: 10 s, then doubling (20, 40) up to 60 s. [reset] after a successful join. */
class Backoff(private val firstMs: Long = 10_000, private val maxMs: Long = 60_000) {
    private var attempt = 0

    fun nextDelayMs(): Long {
        val delay = (firstMs shl attempt.coerceAtMost(16)).coerceAtMost(maxMs)
        if (delay < maxMs) attempt++
        return delay
    }

    fun reset() { attempt = 0 }
}
