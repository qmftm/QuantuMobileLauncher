package com.quantumobile.afkhost

import com.quantumobile.launcher.core.afk.AfkPhase
import com.quantumobile.launcher.core.afk.AfkServer
import com.quantumobile.launcher.core.afk.remote.AfkHost
import com.quantumobile.launcher.core.afk.remote.Ops
import com.quantumobile.launcher.core.afk.remote.RemoteClient
import com.quantumobile.launcher.core.afk.remote.RemoteRequest
import com.quantumobile.launcher.core.auth.Account
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Plays the phone against a running AFK host jar. Opt-in:
 * QML_AFK_HOST=ip:port QML_AFK_HOST_CODE=XXXX-XXXX-XXXX QML_AFK_SERVER=host:port ./gradlew :afk-host:test --tests '*LiveHostTest*'
 */
class LiveHostTest {
    @Test
    fun hostJoinsARealServer() = runBlocking {
        val addr = System.getenv("QML_AFK_HOST"); val code = System.getenv("QML_AFK_HOST_CODE"); val target = System.getenv("QML_AFK_SERVER")
        assumeTrue(!addr.isNullOrBlank() && !code.isNullOrBlank() && !target.isNullOrBlank())
        val client = RemoteClient(AfkHost(name = "pc", address = addr!!, code = code!!))
        println("hello: ${client.hello()}")
        val account = Account.offline("PcAfkBot")
        val server = AfkServer(name = "Live", host = target!!.substringBefore(':'), port = target.substringAfter(':').toInt(), accountId = account.id, enabled = true, icon = "preset:diamond")
        client.call(RemoteRequest(Ops.UPSERT, 0, server = server, account = account))
        var phase = AfkPhase.OFF
        repeat(60) {
            val s = client.list().single { it.server.id == server.id }
            if (s.phase != phase) println("state: ${s.phase} ${s.version ?: ""} ${s.message ?: ""}")
            phase = s.phase
            if (phase == AfkPhase.CONNECTED) return@repeat
            delay(500)
        }
        assertEquals(AfkPhase.CONNECTED, phase)
        delay(70_000) // past a 1-minute idle timeout
        val after = client.list().single { it.server.id == server.id }
        println("after 70s: ${after.phase} ${after.message ?: ""}")
        assertEquals(AfkPhase.CONNECTED, after.phase)
        client.call(RemoteRequest(Ops.REMOVE, 0, id = server.id))
        assertEquals(0, client.list().count { it.server.id == server.id })
    }
}
