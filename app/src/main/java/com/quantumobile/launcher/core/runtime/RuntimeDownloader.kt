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
            if (!allowInsecure && source.parts.any { !it.url.startsWith("https://") }) throw IOException("Only https:// links are allowed")
            tmpDir.mkdirs()
            val files = source.parts.indices.map { File(tmpDir, "runtime-download-$it.part") }
            try {
                // With known part sizes progress is smooth across the whole runtime; otherwise it is per part.
                val knownTotal = if (source.parts.all { it.size > 0 }) source.parts.sumOf { it.size } else -1L
                var finished = 0L
                source.parts.forEachIndexed { i, part ->
                    download(part, files[i]) { done, total ->
                        if (knownTotal > 0) onProgress(finished + done, knownTotal) else onProgress(done, total)
                    }
                    finished += files[i].length()
                }
                val streams = files.map { it.inputStream().buffered() }
                try {
                    manager.install(source.installName, streams.zip(source.parts.map { it.compression }))
                } finally {
                    streams.forEach { runCatching { it.close() } }
                }
            } finally {
                files.forEach { it.delete() }
            }
        }

    private suspend fun download(part: RuntimePart, dest: File, onProgress: (done: Long, total: Long) -> Unit) {
        client.newCall(Request.Builder().url(part.url).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code} for ${part.url}")
            val body = res.body ?: throw IOException("Empty response")
            val total = body.contentLength()
            var done = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
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
        part.sha256?.let { expected ->
            val actual = sha256(dest)
            if (!actual.equals(expected, ignoreCase = true)) {
                throw IOException("Checksum mismatch for ${part.fileName}: expected $expected but got $actual")
            }
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
