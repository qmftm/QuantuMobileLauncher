package com.quantumobile.afkhost

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Runs a Cloudflare quick tunnel (`cloudflared tunnel --url …`) so the phone can reach this PC from any network,
 * without an account, port forwarding or a fixed IP. The public URL changes each time the tunnel starts; [onUrl]
 * is called with every new one (the host then re-posts its addresses, so the app keeps finding it).
 */
class CloudflareTunnel(
    private val localPort: Int,
    private val binDir: File,
    private val log: (String) -> Unit,
    private val onUrl: (String) -> Unit,
    /** Override for tests or a custom install; otherwise PATH, then a copy downloaded into [binDir]. */
    private val binary: File? = null,
) {
    @Volatile private var process: Process? = null
    @Volatile private var stopped = false

    fun start() {
        Thread({ runLoop() }, "cloudflared").apply { isDaemon = true }.start()
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
    }

    fun stop() {
        stopped = true
        process?.destroy()
    }

    private fun runLoop() {
        val exe = try {
            binary ?: findOrDownload()
        } catch (e: Exception) {
            log("외부 접속용 터널을 준비하지 못했어요 (${e.message}). 같은 와이파이에서만 연결돼요.")
            return
        }
        var delayMs = 5_000L
        while (!stopped) {
            val started = System.currentTimeMillis()
            try {
                val p = ProcessBuilder(exe.path, "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:$localPort")
                    .redirectErrorStream(true).start()
                process = p
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> parseUrl(line)?.let(onUrl) }
                }
                p.waitFor()
            } catch (e: Exception) {
                log("터널 실행 오류: ${e.message}")
            }
            if (stopped) break
            // Restart after a pause (longer if it keeps dying right away, e.g. no internet).
            delayMs = if (System.currentTimeMillis() - started > 60_000) 5_000 else (delayMs * 2).coerceAtMost(300_000)
            log("터널이 끊겼어요. ${delayMs / 1000}초 뒤 다시 열어요.")
            Thread.sleep(delayMs)
        }
    }

    private fun findOrDownload(): File {
        val name = if (isWindows) "cloudflared.exe" else "cloudflared"
        System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }?.let { return it }
        val local = File(binDir, name)
        if (local.isFile && local.length() > 1_000_000) return local
        binDir.mkdirs()
        val asset = assetName() ?: throw IllegalStateException("이 운영체제/CPU용 cloudflared가 없어요")
        log("외부 접속용 cloudflared를 처음 한 번 받는 중… ($asset)")
        val tmp = File(binDir, "$asset.part")
        download("https://github.com/cloudflare/cloudflared/releases/latest/download/$asset", tmp)
        if (asset.endsWith(".tgz")) {
            val rc = ProcessBuilder("tar", "-xzf", tmp.path, "-C", binDir.path).inheritIO().start().waitFor()
            tmp.delete()
            if (rc != 0 || !local.isFile) throw IllegalStateException("압축 풀기 실패")
        } else {
            local.delete()
            if (!tmp.renameTo(local)) throw IllegalStateException("저장 실패")
        }
        local.setExecutable(true)
        return local
    }

    private fun download(url: String, out: File) {
        var u = URL(url)
        repeat(5) { // follow redirects (GitHub → its asset CDN)
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            when (c.responseCode) {
                in 300..399 -> { u = URL(u, c.getHeaderField("Location")); c.disconnect() }
                200 -> { c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }; return }
                else -> throw IllegalStateException("다운로드 실패 (HTTP ${c.responseCode})")
            }
        }
        throw IllegalStateException("다운로드 실패 (리다이렉트가 너무 많음)")
    }

    companion object {
        private val os = System.getProperty("os.name").lowercase()
        private val arch = System.getProperty("os.arch").lowercase()
        private val isWindows = os.contains("win")
        private val URL_PATTERN = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com")

        /** The quick-tunnel URL if [line] (cloudflared's log output) announces one. */
        fun parseUrl(line: String): String? =
            URL_PATTERN.find(line)?.value?.takeUnless { it.startsWith("https://api.") }

        /** cloudflared release asset for this machine. */
        fun assetName(): String? {
            val a = when {
                arch == "amd64" || arch == "x86_64" -> "amd64"
                arch == "aarch64" || arch == "arm64" -> "arm64"
                arch.startsWith("arm") -> "arm"
                arch == "x86" || arch == "i386" || arch == "i686" -> "386"
                else -> return null
            }
            return when {
                isWindows -> if (a == "386") "cloudflared-windows-386.exe" else "cloudflared-windows-amd64.exe"
                os.contains("mac") || os.contains("darwin") -> "cloudflared-darwin-${if (a == "arm64") "arm64" else "amd64"}.tgz"
                else -> "cloudflared-linux-$a"
            }
        }
    }
}
