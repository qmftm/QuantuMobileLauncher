package com.quantumobile.afkhost

import com.quantumobile.launcher.core.afk.AfkJson
import com.quantumobile.launcher.core.afk.AfkPhase
import com.quantumobile.launcher.core.afk.proto.AfkClient
import com.quantumobile.launcher.core.afk.remote.BadCodeException
import com.quantumobile.launcher.core.afk.remote.HostAddresses
import com.quantumobile.launcher.core.afk.remote.Rendezvous
import kotlinx.coroutines.delay
import com.quantumobile.launcher.core.afk.remote.RemoteProtocol
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

@Serializable
data class HostConfig(val name: String, val code: String, val port: Int = RemoteProtocol.DEFAULT_PORT)

object HostFiles {
    /** Config folder: QML_AFK_HOME, or ~/.quantumobile-afk. */
    fun dir(): File = File(System.getenv("QML_AFK_HOME") ?: File(System.getProperty("user.home"), ".quantumobile-afk").path)
        .apply { mkdirs() }

    /** Keeps files with tokens readable only by this user (best effort; no-op where unsupported). */
    fun restrictToOwner(f: File) {
        runCatching {
            f.setReadable(false, false); f.setReadable(true, true)
            f.setWritable(false, false); f.setWritable(true, true)
        }
    }
}

/** Some consoles report an ASCII charset (e.g. a bare POSIX locale); print UTF-8 there instead of "?". */
private fun useUtf8IfConsoleCannotShowKorean() {
    val name = System.getProperty("stdout.encoding") ?: System.getProperty("sun.stdout.encoding") ?: java.nio.charset.Charset.defaultCharset().name()
    val ok = runCatching { java.nio.charset.Charset.forName(name).newEncoder().canEncode('가') }.getOrDefault(false)
    if (!ok) System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"))
}

/** Everything the host prints also goes to host.log in the config folder (the only output in background mode). */
object HostLog {
    private var file: java.io.PrintStream? = null
    var console = true

    fun open(dir: File): File {
        val f = File(dir, "host.log")
        if (f.length() > 1_000_000) { val old = File(dir, "host.log.1"); old.delete(); f.renameTo(old) }
        val out = java.io.PrintStream(java.io.FileOutputStream(f, true), true, "UTF-8")
        file = out
        if (!console) System.setErr(out) // crashes in windowless mode end up in the log
        return f
    }

    @Synchronized
    fun line(msg: String) {
        if (console) println(msg)
        file?.println(msg)
    }
}

private fun log(msg: String) = HostLog.line("[${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))}] $msg")

/** Holds host.lock while running, so a second copy (e.g. the autostarted one plus a double-click) does not start. */
private fun lockInstance(dir: File): Any? = runCatching {
    val ch = java.nio.channels.FileChannel.open(
        File(dir, "host.lock").toPath(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
    )
    ch.tryLock() ?: run { ch.close(); null }
}.getOrNull()

/** Kept referenced so the lock lives as long as the process. */
@Volatile private var instanceLock: Any? = null

private const val ALREADY_RUNNING =
    "QuantuMobile AFK 호스트가 이미 실행 중이에요.\n작업 표시줄 오른쪽 아래(숨겨진 아이콘 ^)의 AFK 아이콘을 확인하세요."

fun main(args: Array<String>) {
    val background = Autostart.BACKGROUND_FLAG in args
    HostLog.console = !background
    if (!background) useUtf8IfConsoleCannotShowKorean()
    val dir = HostFiles.dir()
    val configFile = File(dir, "host.json")
    val firstRun = !configFile.isFile
    var config = runCatching { AfkJson.decodeFromString(HostConfig.serializer(), configFile.readText()) }.getOrNull()
        ?: HostConfig(
            name = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "PC",
            code = RemoteProtocol.newCode(),
        )
    // Options: --port N, --name NAME, --new-code (forget the old pairing code; paired phones must re-enter it),
    // --lan-only (no Cloudflare tunnel and no address posting: same Wi-Fi only),
    // --background (no console window: log to host.log, control from the tray icon), --autostart on|off.
    var lanOnly = false
    var autostartWanted: Boolean? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> config = config.copy(port = args.getOrNull(++i)?.toIntOrNull() ?: config.port)
            "--name" -> config = config.copy(name = args.getOrNull(++i) ?: config.name)
            "--new-code" -> config = config.copy(code = RemoteProtocol.newCode())
            "--lan-only" -> lanOnly = true
            "--autostart" -> autostartWanted = when (args.getOrNull(++i)?.lowercase()) { "on", "true", "1" -> true; "off", "false", "0" -> false; else -> null }
            "--help", "-h" -> {
                println("사용법: java -jar afk-host.jar [--port 25580] [--name 이름] [--new-code] [--lan-only] [--background] [--autostart on|off]")
                return
            }
        }
        i++
    }
    val autostart = Autostart(Autostart.currentCommand(if (lanOnly) listOf("--lan-only") else emptyList()))
    if (autostartWanted != null) {
        val result = runCatching { autostart.setEnabled(autostartWanted) }
        println(result.exceptionOrNull()?.message ?: if (autostartWanted) "자동 실행을 켰어요. 다음 로그인부터 창 없이 실행돼요." else "자동 실행을 껐어요.")
        return
    }

    val lock = lockInstance(dir).also { instanceLock = it }
    if (lock == null) {
        if (background) HostTray.message("QuantuMobile AFK", ALREADY_RUNNING)
        else { println(ALREADY_RUNNING.replace("\n", " ")); Thread.sleep(10_000) }
        return
    }
    configFile.writeText(AfkJson.encodeToString(HostConfig.serializer(), config))
    HostFiles.restrictToOwner(configFile)
    val logFile = HostLog.open(dir)
    HostLog.line("===== ${java.time.LocalDateTime.now().withNano(0)} 시작 =====")

    val version = HostService::class.java.`package`?.implementationVersion ?: "dev"
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val service = HostService(dir, config.name, version, scope, AfkClient(dnsServers = ::systemDnsServers))
    service.start()

    val server = try {
        startHttp(config.port, config.code, service)
    } catch (e: java.net.BindException) {
        val msg = "포트 ${config.port}을(를) 다른 프로그램이 쓰고 있어요. --port 로 다른 포트를 지정해 주세요."
        log(msg)
        if (background) HostTray.message("QuantuMobile AFK", msg) else Thread.sleep(10_000)
        kotlin.system.exitProcess(1)
    }

    autostart.refresh()
    val title = "QuantuMobile AFK 호스트 $version"
    val tray = HostTray(title, config.code, logFile, dir, autostart)
    val trayShown = tray.show()

    HostLog.line("========================================================")
    HostLog.line(" $title  (이름: ${config.name})")
    HostLog.line(" 앱 → AFK 탭 → PC 연결 → PC 추가 에 연결 코드만 넣으면 돼요")
    HostLog.line("   연결 코드: ${config.code}")
    lanAddresses().ifEmpty { listOf("127.0.0.1") }.forEach { HostLog.line("   (같은 와이파이 주소: $it:${config.port})") }
    if (!lanOnly) HostLog.line(" 다른 와이파이·LTE에서도 연결되도록 외부 주소를 여는 중…")
    HostLog.line(" 설정 폴더: ${dir.absolutePath}")
    if (trayShown) {
        val auto = runCatching { autostart.isEnabled() }.getOrDefault(false)
        HostLog.line(" 컴퓨터 켤 때 자동 실행: ${if (auto) "켜짐" else "꺼짐"} (작업 표시줄 오른쪽 아래 AFK 아이콘 → 메뉴)")
    }
    if (!background) HostLog.line(" 끄려면 이 창을 닫거나 Ctrl+C")
    HostLog.line("========================================================")
    // Windowless and never run before: nobody has seen the code yet.
    if (background && firstRun && trayShown) tray.showCode()

    if (!lanOnly) startRemoteAccess(scope, config, dir)

    // Log state changes (never tokens) and keep the tray tooltip current.
    scope.launch {
        var last = emptyMap<String, String>()
        combine(service.manager.servers, service.manager.statuses) { s, st -> s to st }.collect { (servers, statuses) ->
            val now = servers.associate { s ->
                val st = statuses[s.id]
                s.id to if (!s.enabled) "꺼짐" else when (st?.phase) {
                    AfkPhase.CONNECTED -> "접속됨${st.version?.let { " ($it)" } ?: ""}"
                    AfkPhase.CONNECTING -> "접속 중"
                    AfkPhase.WAITING -> "재접속 대기${st.message?.let { " · $it" } ?: ""}"
                    AfkPhase.ERROR -> "오류 · ${st.message}"
                    else -> "꺼짐"
                }
            }
            for (s in servers) if (now[s.id] != last[s.id]) log("${s.name} (${s.address}): ${now[s.id]}")
            last = now
            tray.update(
                connected = servers.count { it.enabled && statuses[it.id]?.phase == AfkPhase.CONNECTED },
                enabled = servers.count { it.enabled },
            )
        }
    }
    Runtime.getRuntime().addShutdownHook(Thread { server.stop(0) })
    Thread.currentThread().join()
}

/** Serves the app's requests for [service] on [port], encrypted with [code]. */
fun startHttp(port: Int, code: String, service: HostService, bind: InetAddress? = null): HttpServer {
    val key = RemoteProtocol.key(code)
    val server = HttpServer.create(InetSocketAddress(bind, port), 0)
    server.executor = Executors.newFixedThreadPool(4)
    server.createContext(RemoteProtocol.PATH) { ex -> handle(ex, key, service) }
    server.start()
    return server
}

private fun handle(ex: HttpExchange, key: ByteArray, service: HostService) {
    try {
        if (ex.requestMethod != "POST") { ex.sendResponseHeaders(405, -1); return }
        val body = ex.requestBody.use { String(it.readNBytes(4 * 1024 * 1024), Charsets.UTF_8) }
        val (code, text) = try {
            val req = RemoteProtocol.decodeRequest(key, body)
            200 to RemoteProtocol.encodeResponse(key, runBlocking { service.handle(req) })
        } catch (e: BadCodeException) {
            log("연결 코드가 틀린 요청을 거절했어요 (${ex.remoteAddress.address.hostAddress})")
            403 to "bad code"
        } catch (e: Exception) {
            400 to "bad request"
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    } finally {
        ex.close()
    }
}

/**
 * Makes the PC reachable from other networks: opens a Cloudflare quick tunnel and posts this PC's current
 * addresses (LAN + tunnel), encrypted with the pairing code, so the app finds it with the code alone.
 * Re-posted when the tunnel URL changes and every 6 hours (the relay keeps messages about 12 hours).
 */
private fun startRemoteAccess(scope: CoroutineScope, config: HostConfig, dir: File) {
    val rendezvous = Rendezvous(System.getenv("QML_AFK_RELAY") ?: Rendezvous.DEFAULT_BASE)
    var tunnelUrl: String? = null
    var lastPostFailed = false
    fun post() = scope.launch {
        val addresses = HostAddresses(
            name = config.name,
            lan = lanAddresses().map { "http://$it:${config.port}" },
            tunnel = tunnelUrl,
            time = System.currentTimeMillis(),
        )
        try {
            rendezvous.publish(config.code, addresses)
            if (lastPostFailed) log("주소 공유가 다시 됐어요")
            lastPostFailed = false
        } catch (e: Exception) {
            if (!lastPostFailed) log("주소를 공유하지 못했어요 (${e.message}). 잠시 뒤 다시 시도해요.")
            lastPostFailed = true
        }
    }
    post()
    CloudflareTunnel(config.port, File(dir, "bin"), ::log, onUrl = { url ->
        tunnelUrl = url
        log("외부 주소 준비됨: $url  (다른 와이파이·LTE에서도 연결돼요)")
        post()
    }).start()
    scope.launch {
        while (true) {
            delay(if (lastPostFailed) 60_000L else 6 * 60 * 60_000L)
            post()
        }
    }
}

/** IPv4 addresses of this PC on its local networks, for the phone to connect to. */
private fun lanAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .map { it.hostAddress }
}.getOrDefault(emptyList())

/** DNS servers for SRV lookups: /etc/resolv.conf where it exists, otherwise public resolvers. */
private fun systemDnsServers(): List<InetAddress> {
    val fromResolv = runCatching {
        File("/etc/resolv.conf").readLines().mapNotNull { line ->
            line.trim().takeIf { it.startsWith("nameserver") }?.split(Regex("\\s+"))?.getOrNull(1)
        }.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
    }.getOrDefault(emptyList())
    return fromResolv.ifEmpty { listOf("1.1.1.1", "8.8.8.8").map { InetAddress.getByName(it) } }
}
