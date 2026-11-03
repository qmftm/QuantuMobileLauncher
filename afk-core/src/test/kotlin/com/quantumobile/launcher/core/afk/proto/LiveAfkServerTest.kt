package com.quantumobile.launcher.core.afk.proto

import com.quantumobile.launcher.core.afk.AfkDisconnect
import com.quantumobile.launcher.core.afk.AfkEvents
import com.quantumobile.launcher.core.auth.Account
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Joins a real (offline-mode) server and idles. Opt-in:
 * `QML_AFK_SERVER=host:port [QML_AFK_SECONDS=150] [QML_AFK_NAME=AfkBot] ./gradlew testDebugUnitTest --tests '*LiveAfkServerTest*'`
 * Passes when the bot joins and is still connected at the end (set the server's player-idle-timeout below the
 * test length to check that sneaking keeps it from being kicked).
 */
class LiveAfkServerTest {
    @Test
    fun staysOnARealServer() = runBlocking {
        val target = System.getenv("QML_AFK_SERVER")
        assumeTrue(!target.isNullOrBlank())
        val (host, port) = target!!.substringBefore(':') to (target.substringAfter(':', "25565").toInt())
        val seconds = System.getenv("QML_AFK_SECONDS")?.toLong() ?: 150
        val start = System.currentTimeMillis()
        fun log(s: String) = println("[%6.1fs] %s".format((System.currentTimeMillis() - start) / 1000.0, s))

        val connected = mutableListOf<String>()
        val events = object : AfkEvents {
            override fun connected(version: String) { connected += version; log("connected: $version") }
            override fun note(message: String) = log("note: $message")
            override fun log(kind: com.quantumobile.launcher.core.afk.AfkLogKind, text: String) = log("log $kind: $text")
        }
        val account = Account.offline(System.getenv("QML_AFK_NAME") ?: "AfkBot")
        val ended: AfkDisconnect? = withTimeoutOrNull(seconds * 1000) {
            AfkClient().run(host, port, account, events)
        }
        log("ended: ${ended ?: "still connected after ${seconds}s"}")
        assertEquals(1, connected.size)
        assertNull("disconnected early: $ended", ended)
    }
}
