package com.quantumobile.afkhost

import com.quantumobile.launcher.core.afk.AfkPhase
import com.quantumobile.launcher.core.afk.AfkServer
import com.quantumobile.launcher.core.afk.remote.AfkHost
import com.quantumobile.launcher.core.afk.remote.BadCodeException
import com.quantumobile.launcher.core.afk.remote.Ops
import com.quantumobile.launcher.core.afk.remote.RemoteClient
import com.quantumobile.launcher.core.afk.remote.RemoteProtocol
import com.quantumobile.launcher.core.afk.remote.RemoteRequest
import com.quantumobile.launcher.core.auth.Account
import com.quantumobile.launcher.core.auth.AccountType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** The real host HTTP server and the app's client, end to end, with a fake Minecraft connection. */
class HostServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val joined = CopyOnWriteArrayList<String>()
    private val code = RemoteProtocol.newCode()

    private fun service(dir: File = tmp.root, refresh: suspend (Account) -> Account = { it }) = HostService(
        dir, "Test PC", "9.9", scope,
        connector = { host, _, account, events ->
            joined += "$host as ${account.username} (${account.accessToken})"
            events.serverIcon(byteArrayOf(1, 2, 3))
            events.connected("26.3")
            awaitCancellation()
        },
        refresh = refresh,
    ).also { it.start() }

    private fun withHost(svc: HostService, block: suspend (RemoteClient) -> Unit) {
        val http = startHttp(0, code, svc, InetAddress.getLoopbackAddress())
        try {
            val client = RemoteClient(AfkHost(name = "pc", address = "127.0.0.1:${http.address.port}", code = code.lowercase().replace("-", " ")))
            runBlocking { withTimeout(15_000) { block(client) } }
        } finally {
            http.stop(0)
        }
    }

    @After fun tearDown() = scope.cancel()

    private val ms = Account(type = AccountType.MICROSOFT, username = "Alex", uuid = "0123456789abcdef0123456789abcdef",
        accessToken = "tok-1", refreshToken = "refresh-1", expiresAt = System.currentTimeMillis() + 3_600_000)

    @Test
    fun pairAddToggleAndRemove() = withHost(service()) { client ->
        assertEquals("Test PC", client.hello().name) // the code works typed loosely

        val server = AfkServer(name = "Farm", host = "play.example.com", accountId = ms.id)
        var list = client.call(RemoteRequest(Ops.UPSERT, 0, server = server, account = ms)).servers
        assertEquals(listOf("Farm"), list.map { it.server.name })
        assertEquals("Alex", list.single().accountName)
        assertEquals(AfkPhase.OFF, list.single().phase)

        client.call(RemoteRequest(Ops.SET_ENABLED, 0, id = server.id, enabled = true))
        repeat(50) { if (client.list().single().phase == AfkPhase.CONNECTED) return@repeat; delay(100) }
        list = client.list()
        assertEquals(AfkPhase.CONNECTED, list.single().phase)
        assertEquals("26.3", list.single().version)
        assertEquals(listOf("play.example.com as Alex (tok-1)"), joined) // the PC joined with the phone's account
        // The server's own icon, saved when the bot pinged it, is available to the phone.
        assertNotNull(list.single().iconHash)
        val icon = client.call(RemoteRequest(Ops.ICON, 0, id = server.id)).iconPng
        assertEquals(listOf<Byte>(1, 2, 3), Base64.getDecoder().decode(icon).toList())

        client.call(RemoteRequest(Ops.SET_ENABLED, 0, id = server.id, enabled = false))
        assertEquals(AfkPhase.OFF, client.list().single().phase)

        client.call(RemoteRequest(Ops.REMOVE, 0, id = server.id))
        assertTrue(client.list().isEmpty())
        assertFalse(File(tmp.root, "accounts.json").readText().contains("refresh-1")) // tokens forgotten with the server
    }

    @Test
    fun serversAndAccountsSurviveAHostRestart() {
        val server = AfkServer(name = "Farm", host = "h", accountId = ms.id)
        withHost(service()) { it.call(RemoteRequest(Ops.UPSERT, 0, server = server.copy(enabled = true), account = ms)) }
        scope.coroutineContext[kotlinx.coroutines.Job]!!.children.forEach { it.cancel() }
        joined.clear()
        withHost(service()) { client ->
            repeat(50) { if (joined.isNotEmpty()) return@repeat; delay(100) }
            assertEquals(listOf("h as Alex (tok-1)"), joined)
            assertEquals(AfkPhase.CONNECTED, client.list().single().phase)
        }
    }

    @Test
    fun expiredMicrosoftTokensAreRefreshedOnThePc() {
        val expired = ms.copy(expiresAt = 0)
        val svc = service(refresh = { it.copy(accessToken = "tok-2", expiresAt = System.currentTimeMillis() + 3_600_000) })
        withHost(svc) { client ->
            val server = AfkServer(name = "Farm", host = "h", accountId = ms.id, enabled = true)
            client.call(RemoteRequest(Ops.UPSERT, 0, server = server, account = expired))
            repeat(50) { if (joined.isNotEmpty()) return@repeat; delay(100) }
            assertEquals(listOf("h as Alex (tok-2)"), joined)
        }
        assertTrue(File(tmp.root, "accounts.json").readText().contains("tok-2")) // kept for next time
    }

    @Test
    fun wrongCodeIsRefused() {
        val http = startHttp(0, code, service(), InetAddress.getLoopbackAddress())
        try {
            val wrong = RemoteClient(AfkHost(name = "pc", address = "127.0.0.1:${http.address.port}", code = RemoteProtocol.newCode()))
            try { runBlocking { wrong.hello() }; fail("accepted a wrong code") } catch (e: BadCodeException) { }
        } finally { http.stop(0) }
    }

    @Test
    fun staleRequestsAreRefused() = withHost(service()) { _ ->
        val http = startHttp(0, code, service(tmp.newFolder()), InetAddress.getLoopbackAddress())
        try {
            val old = RemoteClient(AfkHost(name = "pc", address = "127.0.0.1:${http.address.port}", code = code)) {
                System.currentTimeMillis() - 10 * 60_000
            }
            try { old.hello(); fail("accepted a 10-minute-old request") } catch (e: IOException) {
                assertTrue(e.message!!.contains("시계"))
            }
        } finally { http.stop(0) }
    }

    @Test
    fun addressesGetTheDefaultPort() {
        assertEquals("http://192.168.0.5:25580/afk", AfkHost(name = "", address = "192.168.0.5", code = "").url())
        assertEquals("http://pc.local:9000/afk", AfkHost(name = "", address = "pc.local:9000", code = "").url())
        assertNull(null)
    }
}
