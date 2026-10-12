package com.quantumobile.launcher.core.runtime

import com.quantumobile.launcher.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Downloads a JRE archive, verifies its checksum when known, and installs it through [RuntimeManager]. */
class RuntimeDownloader(
    private val manager: RuntimeManager,
    private val tmpDir: File,
    private val client: OkHttpClient = Http.client,
    private val allowInsecure: Boolean = false,
) {
    suspend fun install(source: RuntimeSource, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): JavaRuntime =
        withContext(Dispatchers.IO) {
            if (!allowInsecure && !source.url.startsWith("https://")) throw IOException("Only https:// links are allowed")
            tmpDir.mkdirs()
            val tmp = File(tmpDir, "runtime-download.part")
            try {
                client.newCall(Request.Builder().url(source.url).build()).execute().use { res ->
                    if (!res.isSuccessful) throw IOException("HTTP ${res.code} for ${source.url}")
                    val body = res.body ?: throw IOException("Empty response")
                    val total = body.contentLength()
                    var done = 0L
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                onProgress(done, total)
                            }
                        }
                    }
                }
                source.sha256?.let { expected ->
                    val actual = sha256(tmp)
                    if (!actual.equals(expected, ignoreCase = true)) {
                        throw IOException("Checksum mismatch: expected $expected but got $actual")
                    }
                }
                tmp.inputStream().buffered().use { manager.install(source.fileName, it, source.compression) }
            } finally {
                tmp.delete()
            }
        }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
