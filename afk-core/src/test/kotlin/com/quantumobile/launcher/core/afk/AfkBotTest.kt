package com.quantumobile.launcher.core.afk

import com.quantumobile.launcher.core.auth.Account
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException

@OptIn(ExperimentalCoroutinesApi::class)
class AfkBotTest {
    private val server = AfkServer(name = "S", host = "h", accountId = "acc", enabled = true)
    private val account = Account.offline("Steve")
    private val online = MutableStateFlow(true)
    private val statuses = mutableListOf<AfkStatus>()

    private fun TestScope.bot(connector: AfkConnector) =
        AfkBot(server, connector, { account }, online, { statuses += it }, clock = { currentTime })

    private val phases get() = statuses.map { it.phase }

    @Test
    fun connectsThenWaitsAndRetriesWithGrowingDelays() = runTest {
        var calls = 0
        val bot = bot { _, _, _, events ->
            calls++
            if (calls == 1) {
                events.connected("1.21.4")
                AfkDisconnect("Kicked for idling")
            } else {
                throw ConnectException("refused")
            }
        }
        val job = launch { bot.run() }
        runCurrent()
        assertEquals(listOf(AfkPhase.CONNECTING, AfkPhase.CONNECTED, AfkPhase.WAITING), phases)
        assertEquals("1.21.4", statuses[1].version)
        assertEquals("Kicked for idling", statuses.last().message) // last reason is kept for the screen
        assertEquals(currentTime + 10_000, statuses.last().retryAt)

        advanceTimeBy(10_001) // 1st retry fails → next wait 20 s
        assertEquals(2, calls)
        assertEquals(currentTime - 1 + 20_000, statuses.last().retryAt)
        advanceTimeBy(20_000) // 2nd retry fails → 40 s
        assertEquals(3, calls)
        advanceTimeBy(40_000) // → 60 s cap
        assertEquals(4, calls)
        advanceTimeBy(60_000)
        assertEquals(5, calls)
        assertTrue(statuses.last().message!!.contains("연결할 수 없어요"))
        job.cancel()
    }

    @Test
    fun successfulJoinResetsTheBackoff() = runTest {
        var calls = 0
        val bot = bot { _, _, _, events ->
            calls++
            if (calls == 3) events.connected("26.1")
            AfkDisconnect("bye")
        }
        val job = launch { bot.run() }
        runCurrent()                 // call 1 → wait 10 s
        advanceTimeBy(10_001)        // call 2 → wait 20 s
        advanceTimeBy(20_001)        // call 3 joins → reset → wait 10 s
        assertEquals(3, calls)
        assertEquals(10_000, statuses.last().retryAt - statuses.last().since)
        job.cancel()
    }

    @Test
    fun eventsAndChatGoToTheLog() = runTest {
        val logs = mutableListOf<AfkLogLine>()
        val bot = AfkBot(server, { _, _, _, events ->
            events.connected("26.3")
            events.log(AfkLogKind.CHAT, "<Alex> hi")
            AfkDisconnect("Kicked by an operator")
        }, { account }, online, { statuses += it }, onLog = { logs += it }, clock = { currentTime })
        val job = launch { bot.run() }
        runCurrent()
        job.cancel()
        assertEquals(
            listOf(AfkLogKind.INFO, AfkLogKind.INFO, AfkLogKind.CHAT, AfkLogKind.WARN),
            logs.map { it.kind },
        )
        assertTrue(logs[1].text.contains("26.3"))
        assertEquals("<Alex> hi", logs[2].text)
        assertTrue(logs[3].text.contains("Kicked by an operator") && logs[3].text.contains("10초"))
    }

    @Test
    fun errorsThatRetryingCannotFixStopTheBot() = runTest {
        val bot = bot { _, _, _, _ -> AfkDisconnect("unsupported", retry = false) }
        bot.run() // returns by itself
        assertEquals(listOf(AfkPhase.CONNECTING, AfkPhase.ERROR), phases)
        assertEquals("unsupported", statuses.last().message)
    }

    @Test
    fun missingAccountIsAnError() = runTest {
        val bot = AfkBot(server, { _, _, _, _ -> error("not reached") },
            { throw AfkFatalException("no account") }, online, { statuses += it }, clock = { currentTime })
        bot.run()
        assertEquals(AfkPhase.ERROR, statuses.last().phase)
        assertEquals("no account", statuses.last().message)
    }

    @Test
    fun waitsForTheNetworkInsteadOfLooping() = runTest {
        online.value = false
        var calls = 0
        val bot = bot { _, _, _, _ -> calls++; awaitCancellation() }
        val job = launch { bot.run() }
        advanceTimeBy(600_000)
        assertEquals(0, calls)
        assertEquals(listOf(AfkPhase.WAITING), phases)
        assertEquals(AfkBot.MSG_NO_NETWORK, statuses.last().message)

        online.value = true
        runCurrent()
        assertEquals(1, calls)
        assertEquals(AfkPhase.CONNECTING, statuses.last().phase)
        job.cancel()
    }

    @Test
    fun losingTheNetworkEndsTheConnectionAndReconnectsWhenItReturns() = runTest {
        var calls = 0
        val cancelled = CompletableDeferred<Unit>()
        val bot = bot { _, _, _, events ->
            calls++
            events.connected("1.21.4")
            try { awaitCancellation() } finally { if (calls == 1) cancelled.complete(Unit) }
        }
        val job = launch { bot.run() }
        runCurrent()
        assertEquals(AfkPhase.CONNECTED, statuses.last().phase)

        online.value = false
        runCurrent()
        assertTrue(cancelled.isCompleted)
        assertEquals(AfkPhase.WAITING, statuses.last().phase)
        assertEquals(AfkBot.MSG_NETWORK_LOST, statuses.last().message)

        online.value = true
        runCurrent()
        advanceTimeBy(2_001) // short settle delay, not the 10 s backoff
        assertEquals(2, calls)
        job.cancel()
    }
}
