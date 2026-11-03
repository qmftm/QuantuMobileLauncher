package com.quantumobile.launcher.core.afk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AfkTimingTest {

    @Test
    fun backoffDoublesFromTenSecondsUpToSixty() {
        val b = Backoff()
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L), List(6) { b.nextDelayMs() })
    }

    @Test
    fun backoffStartsOverAfterReset() {
        val b = Backoff()
        repeat(5) { b.nextDelayMs() }
        b.reset()
        assertEquals(10_000L, b.nextDelayMs())
        assertEquals(20_000L, b.nextDelayMs())
    }

    @Test
    fun backoffNeverOverflowsAfterManyFailures() {
        val b = Backoff()
        repeat(10_000) { assertTrue(b.nextDelayMs() in 10_000L..60_000L) }
    }

    @Test
    fun sneakDelayStaysBetweenTwentyAndFortyFiveSeconds() {
        val timing = AfkTiming(Random(1234))
        val delays = List(5_000) { timing.nextSneakDelayMs() }
        assertTrue(delays.all { it in 20_000L..45_000L })
        // Spread over the whole range, not stuck at one end.
        assertTrue(delays.min() < 21_000 && delays.max() > 44_000)
        assertEquals(600L, timing.sneakHoldMs)
    }
}
