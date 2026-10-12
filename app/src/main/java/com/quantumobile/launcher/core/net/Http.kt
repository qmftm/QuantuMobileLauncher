package com.quantumobile.launcher.core.net

import com.quantumobile.launcher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, val body: String, url: String) : IOException("HTTP $code for $url")

data class HttpResponse(val code: Int, val body: String) {
    val isSuccess get() = code in 200..299
}

object Http {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "QuantuMobileLauncher/${BuildConfig.VERSION_NAME}")
                    .build()
            )
        }
        .build()

    suspend fun getString(url: String, headers: Map<String, String> = emptyMap()): String {
        val res = execute(Request.Builder().url(url).headers(headers).get().build())
        if (!res.isSuccess) throw HttpException(res.code, res.body, url)
        return res.body
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        execute(Request.Builder().url(url).headers(headers).get().build())

    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        execute(
            Request.Builder().url(url).headers(headers)
                .header("Accept", "application/json")
                .post(json.toRequestBody(JSON)).build()
        )

    suspend fun postForm(url: String, form: Map<String, String>): HttpResponse {
        val body: RequestBody = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return execute(Request.Builder().url(url).post(body).build())
    }

    private fun Request.Builder.headers(map: Map<String, String>) = apply {
        map.forEach { (k, v) -> header(k, v) }
    }

    private suspend fun execute(request: Request): HttpResponse = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { HttpResponse(it.code, it.body?.string().orEmpty()) }
    }
}
