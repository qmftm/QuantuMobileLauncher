package com.quantumobile.launcher.core.skin

import com.quantumobile.launcher.core.auth.Account
import com.quantumobile.launcher.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64

/** A failure with a message that is fine to show the user (never contains tokens). */
class SkinApiException(message: String) : IOException(message)

/** Where a player's skin and cape images are. */
data class PlayerTextures(val uuid: String, val name: String?, val skinUrl: String?, val slim: Boolean, val capeUrl: String?)

/**
 * Mojang's public skin APIs: look up a player's textures (no sign-in needed) and set the signed-in account's skin.
 * Capes cannot be uploaded: the account owns capes Mojang gave it, and can only pick among those.
 */
class SkinApi(
    private val client: OkHttpClient = Http.client,
    private val profileBase: String = "https://api.mojang.com",
    private val sessionBase: String = "https://sessionserver.mojang.com",
    private val servicesBase: String = "https://api.minecraftservices.com",
    /** Texture images may only come from here (the skin URLs in a profile are untrusted data). */
    private val textureHostOk: (String) -> Boolean = { it == "textures.minecraft.net" },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The undashed UUID of the player called [name], or null if there is none. */
    suspend fun uuidOf(name: String): String? = withContext(Dispatchers.IO) {
        val clean = name.trim()
        if (!NAME.matches(clean)) return@withContext null
        val res = get("$profileBase/users/profiles/minecraft/$clean")
        if (res.code == 404 || res.code == 204) return@withContext null
        if (res.code == 429) throw SkinApiException("요청이 너무 많아요. 잠시 뒤 다시 해 주세요")
        if (res.code !in 200..299) throw SkinApiException("플레이어를 찾지 못했어요 (HTTP ${res.code})")
        json.parseToJsonElement(res.body).jsonObject["id"]?.jsonPrimitive?.contentOrNull
    }

    /** The skin and cape of a player (a name or a UUID with or without dashes); null if it has no profile. */
    suspend fun textures(nameOrUuid: String): PlayerTextures? = withContext(Dispatchers.IO) {
        val input = nameOrUuid.trim()
        val uuid = if (UUID_LIKE.matches(input)) input.replace("-", "") else uuidOf(input) ?: return@withContext null
        val res = get("$sessionBase/session/minecraft/profile/$uuid")
        if (res.code == 404 || res.code == 204) return@withContext null
        if (res.code == 429) throw SkinApiException("요청이 너무 많아요. 잠시 뒤 다시 해 주세요")
        if (res.code !in 200..299) throw SkinApiException("프로필을 불러오지 못했어요 (HTTP ${res.code})")
        parseTextures(res.body)
    }

    /** Parses a session-server profile answer. */
    internal fun parseTextures(body: String): PlayerTextures? {
        val profile = json.parseToJsonElement(body).jsonObject
        val id = profile["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val value = profile["properties"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == "textures" }
            ?.get("value")?.jsonPrimitive?.contentOrNull
            ?: return PlayerTextures(id, profile["name"]?.jsonPrimitive?.contentOrNull, null, false, null)
        val textures = json.parseToJsonElement(String(Base64.getDecoder().decode(value), Charsets.UTF_8))
            .jsonObject["textures"]?.jsonObject ?: JsonObject(emptyMap())
        val skin = textures["SKIN"]?.jsonObject
        return PlayerTextures(
            uuid = id,
            name = profile["name"]?.jsonPrimitive?.contentOrNull,
            skinUrl = skin?.get("url")?.jsonPrimitive?.contentOrNull,
            slim = skin?.get("metadata")?.jsonObject?.get("model")?.jsonPrimitive?.contentOrNull == "slim",
            capeUrl = textures["CAPE"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull,
        )
    }

    /** Mojang's profile data names its texture server with plain http; it answers on https too, so use that. */
    internal fun secure(url: okhttp3.HttpUrl) =
        if (url.scheme == "http" && url.host == "textures.minecraft.net") url.newBuilder().scheme("https").build() else url

    /** Downloads a texture image from Mojang's texture server. */
    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        val parsed = url.toHttpUrlOrNull()?.let(::secure)
        if (parsed == null || !textureHostOk(parsed.host)) throw SkinApiException("믿을 수 없는 이미지 주소예요")
        client.newCall(Request.Builder().url(parsed).build()).execute().use { res ->
            if (!res.isSuccessful) throw SkinApiException("이미지를 받지 못했어요 (HTTP ${res.code})")
            val body = res.body ?: throw SkinApiException("이미지가 비어 있어요")
            val bytes = body.byteStream().use { readUpTo(it, MAX_IMAGE + 1) }
            if (bytes.size > MAX_IMAGE) throw SkinApiException("이미지가 너무 커요")
            bytes
        }
    }

    /**
     * Sets [account]'s skin to [png]. This changes the real skin everyone sees on servers. The access token goes only
     * into the request's Authorization header; it is never logged or put in a message.
     */
    suspend fun uploadSkin(account: Account, png: ByteArray, model: SkinModel) = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("variant", model.apiName)
            .addFormDataPart("file", "skin.png", png.toRequestBody("image/png".toMediaType()))
            .build()
        val request = Request.Builder().url("$servicesBase/minecraft/profile/skins")
            .header("Authorization", "Bearer ${account.accessToken}")
            .post(body).build()
        client.newCall(request).execute().use { res ->
            when {
                res.isSuccessful -> Unit
                res.code == 401 -> throw SkinApiException("로그인이 만료됐어요. 계정을 다시 로그인해 주세요")
                res.code == 400 -> throw SkinApiException("마인크래프트가 이 이미지를 받아들이지 않았어요")
                res.code == 403 -> throw SkinApiException("이 계정은 스킨을 바꿀 수 없어요")
                res.code == 429 -> throw SkinApiException("요청이 너무 많아요. 잠시 뒤 다시 해 주세요")
                else -> throw SkinApiException("스킨을 올리지 못했어요 (HTTP ${res.code})")
            }
        }
    }

    private fun get(url: String): com.quantumobile.launcher.core.net.HttpResponse {
        client.newCall(Request.Builder().url(url).build()).execute().use { res ->
            return com.quantumobile.launcher.core.net.HttpResponse(res.code, res.body?.string().orEmpty())
        }
    }

    private companion object {
        /** Reads at most [limit] bytes (InputStream.readNBytes needs Android 13). */
        fun readUpTo(input: java.io.InputStream, limit: Int): ByteArray = readLimited(input, limit)

        val NAME = Regex("^[A-Za-z0-9_]{1,16}$")
        val UUID_LIKE = Regex("^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$")
        const val MAX_IMAGE = 1024 * 1024
    }
}

/** Reads up to [limit] bytes from [input]; a result of exactly [limit] bytes means "at least that much". */
fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    var left = limit
    while (left > 0) {
        val n = input.read(buf, 0, minOf(buf.size, left))
        if (n < 0) break
        out.write(buf, 0, n)
        left -= n
    }
    return out.toByteArray()
}
