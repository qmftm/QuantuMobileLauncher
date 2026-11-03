package com.quantumobile.afkhost

import com.sun.net.httpserver.HttpServer
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

