package com.quantumobile.launcher.core.afk.proto

import com.quantumobile.launcher.core.afk.AfkJson
import com.quantumobile.launcher.core.afk.SimpleHttp
import com.quantumobile.launcher.core.auth.Account
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * The key pair Mojang issues to a Microsoft account for signing chat ("player certificate").
 * The private key never leaves memory: it is not logged, stored or sent anywhere.
 */
class PlayerCertificate(
    /** When the key stops being accepted (epoch millis). */
    val expiresAt: Long,
    /** When a new one should be fetched instead of reusing this (epoch millis). */
    val refreshAfter: Long,
    /** X.509 encoded public key, as sent to the server. */
    val publicKey: ByteArray,
    /** Mojang's signature over the public key (the "V2" one, used since 1.19.3). */
    val keySignature: ByteArray,
    internal val privateKey: PrivateKey,
) {
    fun usable(now: Long = System.currentTimeMillis()) = now < minOf(expiresAt - 60_000, refreshAfter)

    override fun toString() = "PlayerCertificate(expiresAt=$expiresAt)" // never prints key material
}

/** Gets the chat-signing key for [account]. */
fun interface CertificateFetcher {
    suspend fun fetch(account: Account): PlayerCertificate
}

/** `POST https://api.minecraftservices.com/player/certificates` with the account's access token. */
object MojangCertificates : CertificateFetcher {
    private const val URL = "https://api.minecraftservices.com/player/certificates"

    @Serializable
    private data class KeyPairDto(val privateKey: String, val publicKey: String)

    @Serializable
    private data class CertificateDto(
        val keyPair: KeyPairDto,
        val publicKeySignatureV2: String,
        val expiresAt: String,
        val refreshedAfter: String,
    )

    override suspend fun fetch(account: Account): PlayerCertificate {
        // The token goes only into this header; it is never logged or put in an error message.
        val res = SimpleHttp.post(URL, "", headers = mapOf("Authorization" to "Bearer ${account.accessToken}"))
        if (!res.isSuccess) {
            throw IOException(
                when (res.code) {
                    401 -> "로그인이 만료됐어요"
                    403 -> "이 계정은 채팅 서명 키를 받을 수 없어요"
                    429 -> "잠시 뒤 다시 시도해 주세요 (요청이 너무 많아요)"
                    else -> "HTTP ${res.code}"
                },
            )
        }
        return parse(res.body)
    }

    /** Parses Mojang's JSON answer. */
    internal fun parse(json: String): PlayerCertificate {
        val dto = AfkJson.decodeFromString(CertificateDto.serializer(), json)
        val private = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(pemBody(dto.keyPair.privateKey)))
        val public = pemBody(dto.keyPair.publicKey)
        return PlayerCertificate(
            expiresAt = Instant.parse(dto.expiresAt).toEpochMilli(),
            refreshAfter = Instant.parse(dto.refreshedAfter).toEpochMilli(),
            publicKey = public,
            keySignature = Base64.getDecoder().decode(dto.publicKeySignatureV2),
            privateKey = private,
        )
    }

    /** The Base64 between the "-----BEGIN …-----" and "-----END …-----" lines. */
    private fun pemBody(pem: String): ByteArray =
        Base64.getMimeDecoder().decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString(""))
}

/**
 * Signs chat the way the game does since 1.19.3: every signed message (and every signed command argument) is one
 * link in a chain that starts at index 0 for each connection, so the server can tell none was dropped or reordered.
 * Not thread-safe: call [sign] under the caller's lock, in the order the packets are sent.
 */
class ChatSigner(private val sender: UUID, val sessionId: UUID, private val certificate: PlayerCertificate) {
    private var index = 0

    /**
     * The signature (256 bytes) for [content], sent with [timestampMs] and [salt]. We mark no earlier message as
     * "seen", so the list of seen signatures in the signed data is empty.
     */
    fun sign(content: String, timestampMs: Long, salt: Long): ByteArray {
        val text = content.toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(certificate.privateKey)
        signature.update(payload(sender, sessionId, index, salt, Math.floorDiv(timestampMs, 1000L), text))
        index++
        return signature.sign()
    }

    companion object {
        /**
         * The exact bytes vanilla signs (PlayerChatMessage.updateSignature): version 1, the link (sender, session,
         * index), the body (salt, timestamp in seconds, length-prefixed text) and the seen-messages list (empty).
         */
        fun payload(sender: UUID, sessionId: UUID, index: Int, salt: Long, epochSecond: Long, text: ByteArray): ByteArray =
            ByteBuffer.allocate(4 + 16 + 16 + 4 + 8 + 8 + 4 + text.size + 4)
                .putInt(1)
                .putLong(sender.mostSignificantBits).putLong(sender.leastSignificantBits)
                .putLong(sessionId.mostSignificantBits).putLong(sessionId.leastSignificantBits)
                .putInt(index)
                .putLong(salt)
                .putLong(epochSecond)
                .putInt(text.size).put(text)
                .putInt(0)
                .array()
    }
}

/** The part of a signable command that is signed, e.g. `msg Alex hi there` → ("message", "hi there"). */
object SignableCommands {
    private val MESSAGE_AFTER_TARGET = setOf("msg", "tell", "w")
    private val MESSAGE_ONLY = mapOf("say" to "message", "teammsg" to "message", "tm" to "message", "me" to "action")

    val roots: Set<String> = MESSAGE_AFTER_TARGET + MESSAGE_ONLY.keys

    /** The signed arguments (name to value) of [command] (without the slash), in order; empty if it has none. */
    fun arguments(command: String): List<Pair<String, String>> {
        val root = command.substringBefore(' ').lowercase()
        val rest = if (' ' in command) command.substringAfter(' ') else return emptyList()
        return when (root) {
            in MESSAGE_AFTER_TARGET -> {
                if (' ' !in rest) emptyList() else listOf("message" to rest.substringAfter(' '))
            }
            in MESSAGE_ONLY -> listOf(MESSAGE_ONLY.getValue(root) to rest)
            else -> emptyList()
        }
    }
}
