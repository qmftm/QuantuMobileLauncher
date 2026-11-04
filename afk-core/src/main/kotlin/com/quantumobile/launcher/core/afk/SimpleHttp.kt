package com.quantumobile.launcher.core.afk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTP for the AFK code (works on Android and desktop JVMs alike). Never logs request bodies. */
object SimpleHttp {
    data class Response(val code: Int, val body: String) {
        val isSuccess get() = code in 200..299
    }

    suspend fun post(
        url: String, body: String, contentType: String = "application/json", headers: Map<String, String> = emptyMap(),
    ): Response = request("POST", url, body, contentType, headers)

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): Response = request("GET", url, null, null, headers)

    private suspend fun request(
        method: String, url: String, body: String?, contentType: String?, headers: Map<String, String> = emptyMap(),
    ): Response = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "QuantuMobileLauncher-AFK")
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            Response(code, stream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }
}
