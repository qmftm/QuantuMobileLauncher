package com.quantumobile.launcher.core.auth

import android.net.Uri
import com.quantumobile.launcher.core.arr
import com.quantumobile.launcher.core.long
import com.quantumobile.launcher.core.obj
import com.quantumobile.launcher.core.parseJsonObject
import com.quantumobile.launcher.core.str
import com.quantumobile.launcher.core.net.Http
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException

class AuthException(message: String) : IOException(message)

/**
 * Microsoft → Xbox Live → XSTS → Minecraft services login chain.
 *
 * Uses the public Live "desktop" OAuth client that most third-party launchers use; the sign-in page is
 * shown in a WebView and the authorization code is captured from the redirect URL.
 */
object MicrosoftAuth {
    const val CLIENT_ID = "00000000402b5328"
    const val REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf"
    private const val SCOPE = "service::user.auth.xboxlive.com::MBI_SSL"

    val authorizeUrl: String = Uri.parse("https://login.live.com/oauth20_authorize.srf").buildUpon()
        .appendQueryParameter("client_id", CLIENT_ID)
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("scope", SCOPE)
        .appendQueryParameter("redirect_uri", REDIRECT_URI)
        .appendQueryParameter("prompt", "select_account")
        .build().toString()

    suspend fun loginWithCode(code: String): Account =
        completeLogin(msToken(mapOf("code" to code, "grant_type" to "authorization_code")))

    suspend fun refresh(account: Account): Account {
        val refresh = account.refreshToken ?: throw AuthException("No refresh token; please sign in again")
        return completeLogin(msToken(mapOf("refresh_token" to refresh, "grant_type" to "refresh_token")))
            .copy(id = account.id)
    }

    private data class MsToken(val accessToken: String, val refreshToken: String)

    private suspend fun msToken(params: Map<String, String>): MsToken {
        val res = Http.postForm(
            "https://login.live.com/oauth20_token.srf",
            params + mapOf("client_id" to CLIENT_ID, "redirect_uri" to REDIRECT_URI, "scope" to SCOPE),
        )
        val json = parseJsonObject(res.body.ifEmpty { "{}" })
        if (!res.isSuccess) throw AuthException("Microsoft login failed: ${json.str("error_description") ?: res.code}")
        return MsToken(json.str("access_token")!!, json.str("refresh_token")!!)
    }

    private suspend fun completeLogin(ms: MsToken): Account {
        // Xbox Live user token
        val xbl = postOk("https://user.auth.xboxlive.com/user/authenticate", buildJsonObject {
            putJsonObject("Properties") {
                put("AuthMethod", "RPS")
                put("SiteName", "user.auth.xboxlive.com")
                put("RpsTicket", ms.accessToken)
            }
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
        }, "Xbox Live")
        val xblToken = xbl.str("Token")!!

        // XSTS token for Minecraft services
        val xstsRes = Http.postJson("https://xsts.auth.xboxlive.com/xsts/authorize", buildJsonObject {
            putJsonObject("Properties") {
                put("SandboxId", "RETAIL")
                putJsonArray("UserTokens") { add(JsonPrimitive(xblToken)) }
            }
            put("RelyingParty", "rp://api.minecraftservices.com/")
            put("TokenType", "JWT")
        }.toString())
        if (xstsRes.code == 401) throw AuthException(xstsError(parseJsonObject(xstsRes.body).long("XErr")))
        if (!xstsRes.isSuccess) throw AuthException("XSTS authorization failed (HTTP ${xstsRes.code})")
        val xsts = parseJsonObject(xstsRes.body)
        val claims = (xsts.obj("DisplayClaims")?.arr("xui")?.firstOrNull() as? JsonObject)
        val uhs = claims?.str("uhs") ?: throw AuthException("XSTS response missing user hash")

        // Minecraft access token
        val mc = postOk("https://api.minecraftservices.com/authentication/login_with_xbox", buildJsonObject {
            put("identityToken", "XBL3.0 x=$uhs;${xsts.str("Token")}")
        }, "Minecraft services")
        val mcToken = mc.str("access_token")!!
        val expiresIn = mc.long("expires_in") ?: 86_400

        // Profile (also proves game ownership)
        val profileRes = Http.get(
            "https://api.minecraftservices.com/minecraft/profile",
            mapOf("Authorization" to "Bearer $mcToken"),
        )
        if (profileRes.code == 404) throw AuthException("This Microsoft account does not own Minecraft: Java Edition")
        if (!profileRes.isSuccess) throw AuthException("Failed to fetch Minecraft profile (HTTP ${profileRes.code})")
        val profile = parseJsonObject(profileRes.body)

        return Account(
            type = AccountType.MICROSOFT,
            username = profile.str("name")!!,
            uuid = profile.str("id")!!,
            accessToken = mcToken,
            refreshToken = ms.refreshToken,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000,
            xuid = claims.str("xid").orEmpty(),
        )
    }

    private suspend fun postOk(url: String, body: JsonObject, step: String): JsonObject {
        val res = Http.postJson(url, body.toString())
        if (!res.isSuccess) throw AuthException("$step authentication failed (HTTP ${res.code})")
        return parseJsonObject(res.body)
    }

    private fun xstsError(code: Long?): String = when (code) {
        2148916233 -> "This Microsoft account has no Xbox profile. Sign in at minecraft.net once to create one."
        2148916235 -> "Xbox Live is not available in your country."
        2148916236, 2148916237 -> "This account needs adult verification on the Xbox website."
        2148916238 -> "This is a child account; it must be added to a Microsoft family by an adult."
        else -> "Xbox authorization failed (XErr $code)"
    }

}
