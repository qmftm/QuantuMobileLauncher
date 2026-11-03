package com.quantumobile.launcher.core.afk.proto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What the server list ping says about a server. */
data class ServerVersion(val protocol: Int, val name: String)

/** Server list ping (status state), used to learn which protocol version the server speaks. */
object ServerPing {
    /** Protocol number sent in the status handshake; servers answer with their own version regardless. */
    private const val STATUS_PROTOCOL = PacketIds.NEWEST_KNOWN

    fun query(host: String, port: Int, timeoutMs: Int): ServerVersion =
        McConnection.open(host, port, timeoutMs, timeoutMs).use { conn ->
            conn.send(handshake(STATUS_PROTOCOL, host, port, nextState = 1))
            conn.send(PacketWriter(0x00)) // status request
            val packet = conn.receive()
            if (packet.id != 0x00) throw ProtocolException("Unexpected status reply")
            parse(packet.body.string())
        }

    fun parse(json: String): ServerVersion {
        val root = runCatching { Json.parseToJsonElement(json) as JsonObject }.getOrNull()
            ?: throw ProtocolException("Server sent an unreadable status")
        val version = root["version"] as? JsonObject ?: throw ProtocolException("Server did not report its version")
        val protocol = runCatching { version["protocol"]!!.jsonPrimitive.int }.getOrNull()
            ?: throw ProtocolException("Server did not report its protocol version")
        return ServerVersion(protocol, version["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
    }

    fun handshake(protocol: Int, host: String, port: Int, nextState: Int) =
        PacketWriter(0x00).varInt(protocol).string(host).short(port).varInt(nextState)
}
