package com.quantumobile.launcher.core.afk.remote

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A tiny stand-in for an ntfy server: POST /<topic> stores, GET /<topic>/json?poll=1 lists as JSON lines. */
class FakeNtfy : AutoCloseable {
    val topics = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path.trim('/')
            val (code, body) = when {
                ex.requestMethod == "POST" -> {
                    topics.getOrPut(path) { CopyOnWriteArrayList() } += ex.requestBody.readBytes().toString(Charsets.UTF_8)
                    200 to "{}"
                }
                path.endsWith("/json") -> 200 to topics[path.removeSuffix("/json")].orEmpty().mapIndexed { i, m ->
                    """{"id":"$i","time":$i,"event":"message","topic":"t","message":"$m"}"""
                }.joinToString("\n", postfix = "\n")
                else -> 404 to ""
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    val base = "http://127.0.0.1:${server.address.port}"
    override fun close() = server.stop(0)
}

class RendezvousTest {
    private val ntfy = FakeNtfy()
    @After fun stop() = ntfy.close()

    @Test
    fun theAppFindsWhatThePcPostedWithTheCodeAlone() = runBlocking {
        val code = RemoteProtocol.newCode()
        val r = Rendezvous(ntfy.base)
        r.publish(code, HostAddresses("PC", listOf("http://192.168.0.5:25580"), null, time = 1))
        r.publish(code, HostAddresses("PC", listOf("http://192.168.0.5:25580"), "https://abc.trycloudflare.com", time = 2))

        val found = Rendezvous(ntfy.base).fetch(code.lowercase().replace("-", ""))!!
        assertEquals("https://abc.trycloudflare.com", found.tunnel) // newest wins
        assertEquals(listOf("http://192.168.0.5:25580", "https://abc.trycloudflare.com"), found.candidates) // LAN first

        // The relay only sees ciphertext under a code-derived topic.
        val stored = ntfy.topics.getValue(r.topic(code)).joinToString()
        assertFalse(stored.contains("192.168") || stored.contains("trycloudflare"))
        assertTrue(r.topic(code).startsWith("qmafk"))
        assertNotEquals(r.topic(code), r.topic(RemoteProtocol.newCode()))
    }

    @Test
    fun nothingPostedOrAnotherCodeMeansNoAddresses() = runBlocking {
        val r = Rendezvous(ntfy.base)
        assertNull(r.fetch(RemoteProtocol.newCode()))
    }

    @Test
    fun endpointsFromAnyAddressForm() {
        assertEquals("http://192.168.0.5:25580/afk", AfkHost.endpoint("192.168.0.5"))
        assertEquals("http://pc.local:9000/afk", AfkHost.endpoint("pc.local:9000"))
        assertEquals("https://abc.trycloudflare.com/afk", AfkHost.endpoint("https://abc.trycloudflare.com/"))
        assertEquals("http://192.168.0.5:25580/afk", AfkHost.endpoint("http://192.168.0.5:25580"))
        assertNull(AfkHost.endpoint("  "))
    }
}
