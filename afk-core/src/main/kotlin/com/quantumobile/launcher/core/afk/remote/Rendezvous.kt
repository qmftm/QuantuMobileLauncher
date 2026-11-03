package com.quantumobile.launcher.core.afk.remote

import com.quantumobile.launcher.core.afk.AfkJson
import com.quantumobile.launcher.core.afk.SimpleHttp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.security.MessageDigest

/** Where a PC host can be reached right now: its LAN URLs and, if running, its Cloudflare quick-tunnel URL. */
@Serializable
data class HostAddresses(
    val name: String,
    val lan: List<String> = emptyList(),
    val tunnel: String? = null,
    val time: Long = 0,
) {
    /** LAN first (fast, works without internet), then the tunnel (works from anywhere). */
    val candidates get() = lan + listOfNotNull(tunnel)
}

/**
 * Lets the phone find its PC with nothing but the pairing code: the host posts its current addresses, encrypted with
 * the code, to a topic on a public ntfy server whose name is also derived from the code; the app reads the latest one.
 * The relay only ever sees ciphertext under a random-looking topic. Nothing to sign up for or keep running.
 */
class Rendezvous(private val base: String = DEFAULT_BASE) {

    fun topic(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("QuantuMobile-AFK-topic:${RemoteProtocol.normalize(code)}".toByteArray(Charsets.UTF_8))
        return "qmafk" + digest.take(16).joinToString("") { "%02x".format(it) }
    }

    suspend fun publish(code: String, addresses: HostAddresses) {
        val body = RemoteProtocol.seal(RemoteProtocol.key(code), AfkJson.encodeToString(HostAddresses.serializer(), addresses))
        val res = SimpleHttp.post("$base/${topic(code)}", body, "text/plain")
        if (!res.isSuccess) throw IOException("주소 공유 실패 (HTTP ${res.code})")
    }

    /** The newest addresses the host posted (within the relay's retention, ~12 h), or null if there are none. */
    suspend fun fetch(code: String): HostAddresses? {
        val res = SimpleHttp.get("$base/${topic(code)}/json?poll=1&since=all")
        if (!res.isSuccess) throw IOException("주소 확인 실패 (HTTP ${res.code})")
        val key = RemoteProtocol.key(code)
        return res.body.lineSequence()
            .mapNotNull { line -> runCatching { Json.parseToJsonElement(line) as JsonObject }.getOrNull() }
            .filter { (it["event"] as? JsonPrimitive)?.contentOrNull == "message" }
            .mapNotNull { (it["message"] as? JsonPrimitive)?.contentOrNull }
            .mapNotNull { sealed ->
                runCatching { AfkJson.decodeFromString(HostAddresses.serializer(), RemoteProtocol.open(key, sealed)) }.getOrNull()
            }
            .maxByOrNull { it.time }
    }

    companion object {
        const val DEFAULT_BASE = "https://ntfy.sh"
    }
}
