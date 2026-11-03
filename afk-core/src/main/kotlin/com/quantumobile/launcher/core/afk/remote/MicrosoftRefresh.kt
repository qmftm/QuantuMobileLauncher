package com.quantumobile.launcher.core.afk.remote

import com.quantumobile.launcher.core.afk.AfkFatalException
import com.quantumobile.launcher.core.afk.SimpleHttp
import com.quantumobile.launcher.core.auth.Account
import com.quantumobile.launcher.core.auth.AccountType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Refreshes a Microsoft account's Minecraft token from its refresh token (Microsoft → Xbox Live → XSTS →
 * Minecraft services), for the PC host. Same chain and public client as the app's MicrosoftAuth.
 * Tokens never appear in exceptions or logs.
 */
object MicrosoftRefresh {
    private const val CLIENT_ID = "00000000402b5328"
    private const val REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf"
    private const val SCOPE = "service::user.auth.xboxlive.com::MBI_SSL"

    suspend fun refresh(account: Account): Account {
        val refreshToken = account.refreshToken
            ?: throw AfkFatalException("마이크로소프트 로그인 정보가 없어요. 앱에서 서버를 다시 저장해 주세요")
        val form = mapOf(
            "client_id" to CLIENT_ID, "redirect_uri" to REDIRECT_URI, "scope" to SCOPE,
            "grant_type" to "refresh_token", "refresh_token" to refreshToken,
        ).entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val ms = SimpleHttp.post("https://login.live.com/oauth20_token.srf", form, "application/x-www-form-urlencoded")
        if (ms.code in 400..499) throw AfkFatalException("마이크로소프트 로그인이 만료됐어요. 앱에서 다시 로그인한 뒤 서버를 저장해 주세요")
        val msJson = ok(ms, "Microsoft")
        val msAccess = msJson.str("access_token") ?: throw IOException("Microsoft login: no token")
        val newRefresh = msJson.str("refresh_token") ?: refreshToken

        val xbl = ok(SimpleHttp.post("https://user.auth.xboxlive.com/user/authenticate", buildJsonObject {
            putJsonObject("Properties") {
                put("AuthMethod", "RPS"); put("SiteName", "user.auth.xboxlive.com"); put("RpsTicket", msAccess)
            }
            put("RelyingParty", "http://auth.xboxlive.com"); put("TokenType", "JWT")
        }.toString()), "Xbox Live")
        val xsts = ok(SimpleHttp.post("https://xsts.auth.xboxlive.com/xsts/authorize", buildJsonObject {
            putJsonObject("Properties") {
                put("SandboxId", "RETAIL")
                putJsonArray("UserTokens") { add(JsonPrimitive(xbl.str("Token"))) }
            }
            put("RelyingParty", "rp://api.minecraftservices.com/"); put("TokenType", "JWT")
        }.toString()), "XSTS")
        val uhs = ((xsts["DisplayClaims"] as? JsonObject)?.get("xui") as? JsonArray)?.firstOrNull()
            ?.let { (it as? JsonObject)?.str("uhs") } ?: throw IOException("XSTS: no user hash")
        val mc = ok(SimpleHttp.post("https://api.minecraftservices.com/authentication/login_with_xbox", buildJsonObject {
            put("identityToken", "XBL3.0 x=$uhs;${xsts.str("Token")}")
        }.toString()), "Minecraft services")
        val expiresIn = (mc["expires_in"] as? JsonPrimitive)?.longOrNull ?: 86_400
        return account.copy(
            type = AccountType.MICROSOFT,
            accessToken = mc.str("access_token") ?: throw IOException("Minecraft login: no token"),
            refreshToken = newRefresh,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000,
        )
    }

    private fun ok(res: SimpleHttp.Response, step: String): JsonObject {
        if (!res.isSuccess) throw IOException("$step 인증 실패 (HTTP ${res.code})")
        return Json.parseToJsonElement(res.body.ifEmpty { "{}" }) as JsonObject
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
