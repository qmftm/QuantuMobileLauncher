package com.quantumobile.launcher.core.afk.proto

import com.quantumobile.launcher.core.afk.AfkFatalException
import com.quantumobile.launcher.core.auth.Account
import com.quantumobile.launcher.core.net.Http
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PublicKey

/** Raised when the server wants Mojang authentication and it cannot be done; retrying will not help. */
class AuthRequiredException(message: String) : AfkFatalException(message)

/** Tells Mojang's session server that [account] is joining the server identified by [serverHash]. */
fun interface SessionJoiner {
    suspend fun join(account: Account, serverHash: String)
}

object MojangSession : SessionJoiner {
    private const val JOIN_URL = "https://sessionserver.mojang.com/session/minecraft/join"

    override suspend fun join(account: Account, serverHash: String) {
        // The access token goes only into this request body; it is never logged or put in an error message.
        val body = buildJsonObject {
            put("accessToken", account.accessToken)
            put("selectedProfile", account.uuid)
            put("serverId", serverHash)
        }.toString()
        val res = Http.postJson(JOIN_URL, body)
        when {
            res.isSuccess -> Unit
            res.code == 401 || res.code == 403 ->
                throw AuthRequiredException("마이크로소프트 로그인이 만료됐어요. 계정 탭에서 다시 로그인해 주세요")
            res.code == 429 -> throw IOException("인증 서버 요청이 너무 많아요 (잠시 후 다시 시도)")
            else -> throw IOException("인증 서버 오류 (HTTP ${res.code})")
        }
    }

    /** Minecraft's "server hash": SHA-1 over server ID, shared secret and public key, as a signed hex number. */
    fun serverHash(serverId: String, secret: ByteArray, publicKey: PublicKey): String {
        val sha = MessageDigest.getInstance("SHA-1")
        sha.update(serverId.toByteArray(Charsets.ISO_8859_1))
        sha.update(secret)
        sha.update(publicKey.encoded)
        return BigInteger(sha.digest()).toString(16)
    }
}
