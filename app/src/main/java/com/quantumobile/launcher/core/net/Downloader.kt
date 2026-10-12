package com.quantumobile.launcher.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

data class DownloadTask(
    val url: String,
    val dest: File,
    val sha1: String? = null,
    val size: Long = -1,
    val sha256: String? = null,
)

class Downloader(private val client: OkHttpClient = Http.client) {

    /** Downloads [tasks] concurrently, skipping files that are already present and valid. */
    suspend fun downloadAll(
        tasks: List<DownloadTask>,
        parallelism: Int = 8,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) = coroutineScope {
        val unique = tasks.distinctBy { it.dest.absolutePath }
        val done = AtomicInteger()
        val semaphore = Semaphore(parallelism)
        onProgress(0, unique.size)
        unique.map { task ->
            async(Dispatchers.IO) {
                semaphore.withPermit { download(task) }
                onProgress(done.incrementAndGet(), unique.size)
            }
        }.awaitAll()
    }

    suspend fun download(task: DownloadTask) = withContext(Dispatchers.IO) {
        if (isValid(task.dest, task.sha1, task.size, task.sha256)) return@withContext
        var lastError: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            coroutineContext.ensureActive()
            try {
                fetch(task)
                return@withContext
            } catch (e: IOException) {
                lastError = e
                delay(500L * (attempt + 1))
            }
        }
        throw IOException("Failed to download ${task.url}", lastError)
    }

    private fun fetch(task: DownloadTask) {
        task.dest.parentFile?.mkdirs()
        val tmp = File(task.dest.path + ".part")
        client.newCall(Request.Builder().url(task.url).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code} for ${task.url}")
            val body = res.body ?: throw IOException("Empty body for ${task.url}")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        if (task.sha1 != null) {
            val actual = sha1(tmp)
            if (!actual.equals(task.sha1, ignoreCase = true)) {
                tmp.delete()
                throw IOException("SHA-1 mismatch for ${task.url}: expected ${task.sha1}, got $actual")
            }
        }
        if (task.sha256 != null) {
            val actual = digest(tmp, "SHA-256")
            if (!actual.equals(task.sha256, ignoreCase = true)) {
                tmp.delete()
                throw IOException("SHA-256 mismatch for ${task.url}: expected ${task.sha256}, got $actual")
            }
        }
        if (!tmp.renameTo(task.dest)) {
            task.dest.delete()
            if (!tmp.renameTo(task.dest)) throw IOException("Cannot move ${tmp.path} to ${task.dest.path}")
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3

        fun isValid(file: File, sha1: String?, size: Long, sha256: String? = null): Boolean {
            if (!file.isFile) return false
            if (size >= 0 && file.length() != size) return false
            if (sha256 != null && !digest(file, "SHA-256").equals(sha256, ignoreCase = true)) return false
            if (sha1 != null) return sha1(file).equals(sha1, ignoreCase = true)
            return file.length() > 0
        }

        fun sha1(file: File): String = digest(file, "SHA-1")

        fun digest(file: File, algorithm: String): String {
            val digest = MessageDigest.getInstance(algorithm)
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
