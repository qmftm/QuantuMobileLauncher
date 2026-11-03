package com.quantumobile.launcher.core.afk.proto

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random

/**
 * Looks up `_minecraft._tcp.<host>` SRV records, as the game does when no port is given.
 * Android has no SRV API before API 29, so this sends one plain DNS query over UDP to the network's DNS server.
 */
object SrvLookup {

    data class Target(val host: String, val port: Int)

    /** Returns the SRV target, or null when there is none (or the lookup failed: the caller then uses host:25565). */
    fun resolve(host: String, dnsServers: List<InetAddress>, timeoutMs: Int = 3000): Target? {
        if (host.isEmpty() || host.all { it.isDigit() || it == '.' } || ':' in host) return null // IP literal
        val name = "_minecraft._tcp.$host"
        for (server in dnsServers.take(2)) {
            val target = runCatching { query(name, server, timeoutMs) }.getOrNull()
            if (target != null) return target
        }
        return null
    }

    private fun query(name: String, server: InetAddress, timeoutMs: Int): Target? {
        val id = Random.nextInt(0, 0xFFFF)
        val request = buildQuery(id, name)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.send(DatagramPacket(request, request.size, InetSocketAddress(server, 53)))
            val buf = ByteArray(1500)
            val reply = DatagramPacket(buf, buf.size)
            socket.receive(reply)
            return parse(buf.copyOf(reply.length), id)
        }
    }

    internal fun buildQuery(id: Int, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v ushr 8 and 0xFF); out.write(v and 0xFF) }
        u16(id); u16(0x0100) // recursion desired
        u16(1); u16(0); u16(0); u16(0)
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size); out.write(bytes)
        }
        out.write(0)
        u16(33); u16(1) // SRV, IN
        return out.toByteArray()
    }

    /** Picks the record with the lowest priority (highest weight among equals). */
    internal fun parse(msg: ByteArray, id: Int): Target? {
        fun u16(at: Int) = ((msg[at].toInt() and 0xFF) shl 8) or (msg[at + 1].toInt() and 0xFF)
        if (msg.size < 12 || u16(0) != id) return null
        if (u16(2) and 0x000F != 0) return null // rcode != NOERROR
        val qd = u16(4)
        val an = u16(6)
        var pos = 12
        repeat(qd) { pos = skipName(msg, pos) + 4 }
        var best: Triple<Int, Int, Target>? = null
        repeat(an) {
            pos = skipName(msg, pos)
            val type = u16(pos)
            val rdLength = u16(pos + 8)
            val rd = pos + 10
            if (type == 33 && rdLength >= 7) {
                val priority = u16(rd)
                val weight = u16(rd + 2)
                val port = u16(rd + 4)
                val target = readName(msg, rd + 6).trimEnd('.')
                if (target.isNotEmpty() && (best == null || priority < best!!.first ||
                        (priority == best!!.first && weight > best!!.second))) {
                    best = Triple(priority, weight, Target(target, port))
                }
            }
            pos = rd + rdLength
        }
        return best?.third
    }

    private fun skipName(msg: ByteArray, start: Int): Int {
        var pos = start
        while (true) {
            val len = msg[pos].toInt() and 0xFF
            if (len == 0) return pos + 1
            if (len and 0xC0 == 0xC0) return pos + 2
            pos += len + 1
        }
    }

    private fun readName(msg: ByteArray, start: Int): String {
        val sb = StringBuilder()
        var pos = start
        var jumps = 0
        while (jumps < 16) {
            val len = msg[pos].toInt() and 0xFF
            when {
                len == 0 -> return sb.toString()
                len and 0xC0 == 0xC0 -> {
                    pos = ((len and 0x3F) shl 8) or (msg[pos + 1].toInt() and 0xFF)
                    jumps++
                }
                else -> {
                    sb.append(String(msg, pos + 1, len, Charsets.US_ASCII)).append('.')
                    pos += len + 1
                }
            }
        }
        return sb.toString()
    }
}
