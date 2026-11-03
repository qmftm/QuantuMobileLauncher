package com.quantumobile.launcher.core.afk.remote

import com.quantumobile.launcher.core.afk.AfkJson
import com.quantumobile.launcher.core.afk.AfkLogLine
import com.quantumobile.launcher.core.afk.AfkWorldInfo
import com.quantumobile.launcher.core.afk.AfkPhase
import com.quantumobile.launcher.core.afk.AfkServer
import com.quantumobile.launcher.core.auth.Account
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phone ↔ PC protocol for running AFK bots on a PC ("host") and controlling them from the app.
 *
 * One HTTP endpoint (`POST /afk`). Request and response bodies are JSON encrypted with AES-GCM under a key
 * derived from the host's pairing code, so the code is never sent and Microsoft tokens (which a PC needs to
 * join online-mode servers) are not readable on the network. Requests carry a timestamp; old ones are refused.
 */
object RemoteProtocol {
    const val DEFAULT_PORT = 25580
    const val PATH = "/afk"
    /** Requests older or newer than this (clock difference) are refused, so a captured one cannot be replayed later. */
    const val MAX_SKEW_MS = 5 * 60_000L

    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O, 1/I

    /** A new pairing code: 12 characters shown as XXXX-XXXX-XXXX (60 bits). */
    fun newCode(random: SecureRandom = SecureRandom()): String =
        (0 until 12).map { ALPHABET[random.nextInt(ALPHABET.length)] }.chunked(4).joinToString("-") { it.joinToString("") }

    /** Accepts the code typed loosely (lower case, without dashes or with spaces). */
    fun normalize(code: String) = code.uppercase().filter { it.isLetterOrDigit() }

    fun key(code: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("QuantuMobile-AFK:${normalize(code)}".toByteArray(Charsets.UTF_8))

    fun seal(key: ByteArray, plain: String): String {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    /** Decrypts [sealed]; throws [BadCodeException] if it was not made with the same code (or was tampered with). */
    fun open(key: ByteArray, sealed: String): String = try {
        val data = Base64.getDecoder().decode(sealed.trim())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
        String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    } catch (e: Exception) {
        throw BadCodeException()
    }

    fun encodeRequest(key: ByteArray, req: RemoteRequest) = seal(key, AfkJson.encodeToString(RemoteRequest.serializer(), req))
    fun decodeRequest(key: ByteArray, body: String): RemoteRequest = AfkJson.decodeFromString(RemoteRequest.serializer(), open(key, body))
    fun encodeResponse(key: ByteArray, res: RemoteResponse) = seal(key, AfkJson.encodeToString(RemoteResponse.serializer(), res))
    fun decodeResponse(key: ByteArray, body: String): RemoteResponse = AfkJson.decodeFromString(RemoteResponse.serializer(), open(key, body))
}

class BadCodeException : Exception("연결 코드가 맞지 않아요")

object Ops {
    const val HELLO = "hello"
    const val LIST = "list"
    const val UPSERT = "upsert"
    const val SET_ENABLED = "setEnabled"
    const val REMOVE = "remove"
    const val ICON = "icon"
    const val LOGS = "logs"
    const val CHAT = "chat"
}

@Serializable
data class RemoteRequest(
    val op: String,
    val time: Long,
    val id: String? = null,
    val server: AfkServer? = null,
    /** The account the server joins with (Microsoft tokens included, so the PC can join and refresh them). */
    val account: Account? = null,
    val enabled: Boolean? = null,
    /** Base64 PNG for a picked custom icon. */
    val iconPng: String? = null,
    /** [Ops.CHAT]: the chat line or "/command" to send. */
    val text: String? = null,
    /** [Ops.LOGS]: only lines newer than this (epoch ms on the PC). */
    val since: Long? = null,
)

@Serializable
data class RemoteResponse(
    val ok: Boolean = true,
    val error: String? = null,
    val host: HostInfo? = null,
    val servers: List<RemoteServer> = emptyList(),
    val iconPng: String? = null,
    val logs: List<AfkLogLine> = emptyList(),
    /** [Ops.LOGS]: the bot's current surroundings, if connected. */
    val world: AfkWorldInfo? = null,
)

@Serializable
data class HostInfo(val name: String, val version: String)

/** A server as the PC runs it: its settings, account name and live state. */
@Serializable
data class RemoteServer(
    val server: AfkServer,
    val accountName: String,
    val phase: AfkPhase,
    val message: String? = null,
    val version: String? = null,
    /** Time left before the next reconnect (relative, so the two clocks need not agree). */
    val retryInMs: Long = 0,
    /** Changes when the server's stored icon image changes; null when it has none. */
    val iconHash: String? = null,
)

/** A PC the app controls. Stored on the phone (app-private), [code] included. */
@Serializable
data class AfkHost(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    /** Optional fixed address: "ip", "ip:port" or a full http(s) URL. Blank = find it with [Rendezvous]. */
    val address: String = "",
    val code: String,
) {
    /** The endpoint URL for [address], or null when there is none. */
    fun url(): String? = endpoint(address)

    companion object {
        /** "ip" / "ip:port" / "http(s)://host[:port][/afk]" → the full endpoint URL. */
        fun endpoint(address: String): String? {
            val a = address.trim().removeSuffix("/")
            if (a.isEmpty()) return null
            if (a.startsWith("http://") || a.startsWith("https://")) {
                return if (a.endsWith(RemoteProtocol.PATH)) a else a + RemoteProtocol.PATH
            }
            val withPort = if (a.substringAfterLast(']').contains(':')) a else "$a:${RemoteProtocol.DEFAULT_PORT}"
            return "http://$withPort${RemoteProtocol.PATH}"
        }
    }
}
