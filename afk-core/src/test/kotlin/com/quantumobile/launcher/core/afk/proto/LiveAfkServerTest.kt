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
            var lastWorld = ""
            override fun chatSender(send: ((String) -> Boolean)?) {
                // QML_AFK_SAY="hello;/me waves" sends those lines a few seconds after joining.
                val lines = System.getenv("QML_AFK_SAY")?.split(';')?.filter { it.isNotBlank() } ?: return
                if (send == null) return
                Thread {
                    Thread.sleep(4_000)
                    lines.forEach { log("send '$it' -> ${send(it)}"); Thread.sleep(500) }
                }.start()
            }
            override fun world(info: com.quantumobile.launcher.core.afk.AfkWorldInfo) {
                val w = "pos=${info.x?.toInt()},${info.y?.toInt()},${info.z?.toInt()} dim=${info.dimension} mode=${info.gameMode} hp=${info.health} food=${info.food} time=${info.dayTime} players=${info.players}"
                if (w != lastWorld) { lastWorld = w; log("world $w") }
            }
        }
        // QML_AFK_SIGNED=1: act as a Microsoft account with a self-made chat key (the key's Mojang signature is
        // fake), to see whether a real server accepts our chat session update and message signatures.
        val signed = System.getenv("QML_AFK_SIGNED") == "1"
        val name = System.getenv("QML_AFK_NAME") ?: "AfkBot"
        val account = if (signed) {
            Account(type = com.quantumobile.launcher.core.auth.AccountType.MICROSOFT, username = name, uuid = Account.offlineUuid(name))
        } else Account.offline(name)
        val client = if (signed) {
            val pair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
            val certificate = PlayerCertificate(
                System.currentTimeMillis() + 48 * 3_600_000L, System.currentTimeMillis() + 24 * 3_600_000L,
                pair.public.encoded, ByteArray(512), pair.private,
            )
            AfkClient(certificates = { certificate })
        } else AfkClient()
        val ended: AfkDisconnect? = withTimeoutOrNull(seconds * 1000) {
            client.run(host, port, account, events)
        }
        log("ended: ${ended ?: "still connected after ${seconds}s"}")
        assertEquals(1, connected.size)
        assertNull("disconnected early: $ended", ended)
    }
}
