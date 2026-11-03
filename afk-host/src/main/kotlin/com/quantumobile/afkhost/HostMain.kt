package com.quantumobile.afkhost

import com.quantumobile.launcher.core.afk.AfkJson
import com.quantumobile.launcher.core.afk.AfkPhase
import com.quantumobile.launcher.core.afk.proto.AfkClient
import com.quantumobile.launcher.core.afk.remote.BadCodeException
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

private fun log(msg: String) = println("[${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))}] $msg")

fun main(args: Array<String>) {
    useUtf8IfConsoleCannotShowKorean()
    val dir = HostFiles.dir()
    val configFile = File(dir, "host.json")
    var config = runCatching { AfkJson.decodeFromString(HostConfig.serializer(), configFile.readText()) }.getOrNull()
        ?: HostConfig(
            name = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "PC",
            code = RemoteProtocol.newCode(),
        )
    // Options: --port N, --name NAME, --new-code (forget the old pairing code; paired phones must re-enter it).
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> config = config.copy(port = args.getOrNull(++i)?.toIntOrNull() ?: config.port)
            "--name" -> config = config.copy(name = args.getOrNull(++i) ?: config.name)
            "--new-code" -> config = config.copy(code = RemoteProtocol.newCode())
            "--help", "-h" -> { println("사용법: java -jar afk-host.jar [--port 25580] [--name 이름] [--new-code]"); return }
        }
        i++
    }
    configFile.writeText(AfkJson.encodeToString(HostConfig.serializer(), config))
    HostFiles.restrictToOwner(configFile)

    val version = HostService::class.java.`package`?.implementationVersion ?: "dev"
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val service = HostService(dir, config.name, version, scope, AfkClient(dnsServers = ::systemDnsServers))
    service.start()

    val server = startHttp(config.port, config.code, service)

    println("========================================================")
    println(" QuantuMobile AFK 호스트 $version  (이름: ${config.name})")
    println(" 앱 → AFK 탭 → PC 연결 → 추가 에 아래 정보를 넣으세요")
    lanAddresses().ifEmpty { listOf("127.0.0.1") }.forEach { println("   주소:      $it:${config.port}") }
    println("   연결 코드: ${config.code}")
    println(" 설정 폴더: ${dir.absolutePath}")
    println(" 끄려면 이 창을 닫거나 Ctrl+C")
    println("========================================================")

    // Log state changes (never tokens).
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
