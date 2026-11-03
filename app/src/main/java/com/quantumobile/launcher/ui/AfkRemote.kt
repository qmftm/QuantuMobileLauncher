package com.quantumobile.launcher.ui

import android.content.Context
import com.quantumobile.launcher.core.afk.AfkJson
import com.quantumobile.launcher.core.afk.AfkStatus
import com.quantumobile.launcher.core.afk.remote.AfkHost
import com.quantumobile.launcher.core.afk.remote.BadCodeException
import com.quantumobile.launcher.core.afk.remote.HostLocator
import com.quantumobile.launcher.core.afk.remote.Ops
import com.quantumobile.launcher.core.afk.remote.RemoteClient
import com.quantumobile.launcher.core.afk.remote.RemoteRequest
import com.quantumobile.launcher.core.afk.remote.RemoteResponse
import com.quantumobile.launcher.core.afk.remote.RemoteServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.Base64

/**
 * The PCs ("AFK hosts") this phone controls, and what they last reported. The list (with each PC's pairing code)
 * is stored in app-private storage; [poll] asks every PC for its servers.
 */
class AfkRemote(private val context: Context) {
    /** What one PC last said: its servers, or why it could not be reached. */
    data class HostState(val servers: List<RemoteServer>? = null, val error: String? = null, val name: String? = null)

    private val file = File(context.filesDir, "afk-hosts.json")
    private val _hosts = MutableStateFlow(load())
    val hosts: StateFlow<List<AfkHost>> get() = _hosts

    private val _states = MutableStateFlow<Map<String, HostState>>(emptyMap())
    val states: StateFlow<Map<String, HostState>> get() = _states

    fun host(id: String) = _hosts.value.firstOrNull { it.id == id }

    private val locator = HostLocator()
    /** The URL each PC last answered on (LAN or tunnel); found again through [HostLocator] when it stops working. */
    private val prefs = context.getSharedPreferences("afk_hosts", Context.MODE_PRIVATE)

    private fun workingUrl(hostId: String): String? = prefs.getString("url_$hostId", null)
    private fun rememberUrl(hostId: String, url: String) = prefs.edit().putString("url_$hostId", url).apply()

    /** Runs [block] against PC [host], re-locating it once if its last known address no longer answers. */
    private suspend fun <T> withHost(host: AfkHost, block: suspend (RemoteClient) -> T): T {
        val known = workingUrl(host.id) ?: host.url()
        if (known != null) {
            try {
                return withTimeout(8_000) { block(RemoteClient(known, host.code)) }
            } catch (e: BadCodeException) {
                throw e
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            }
        }
        val found = locator.locate(host, known)
        rememberUrl(host.id, found.url)
        return block(RemoteClient(found.url, host.code))
    }

    /** Checks a new PC (code, optional address) and returns its name and the URL it answered on. */
    suspend fun check(host: AfkHost): HostLocator.Found = locator.locate(host)

    fun saveHost(host: AfkHost, url: String) {
        rememberUrl(host.id, url)
        saveHost(host)
    }

    @Synchronized
    fun saveHost(host: AfkHost) {
        val list = _hosts.value
        save(if (list.any { it.id == host.id }) list.map { if (it.id == host.id) host else it } else list + host)
    }

    @Synchronized
    fun removeHost(id: String) {
        save(_hosts.value.filterNot { it.id == id })
        prefs.edit().remove("url_$id").apply()
        _states.update { it - id }
        iconDir().listFiles()?.filter { it.name.startsWith("$id-") }?.forEach { it.delete() }
    }

    /** Sends [req] to PC [hostId]; the answer's server list (if any) updates [states] at once. */
    suspend fun call(hostId: String, req: RemoteRequest): RemoteResponse {
        val host = host(hostId) ?: throw IllegalStateException("PC가 목록에 없어요")
        val res = withTimeout(30_000) { withHost(host) { it.call(req) } }
        if (req.op != Ops.ICON && req.op != Ops.HELLO) setState(hostId, HostState(servers = res.servers))
        return res
    }

    /** Asks every PC for its servers (in parallel) and fetches icons that changed. */
    suspend fun poll() = coroutineScope {
        _hosts.value.map { host ->
            async {
                val state = try {
                    val servers = withTimeout(20_000) { withHost(host) { it.list() } }
                    servers.forEach { syncIcon(host, it) }
                    HostState(servers = servers)
                } catch (e: BadCodeException) {
                    HostState(error = e.message)
                } catch (e: Exception) {
                    HostState(error = describe(e))
                }
                setState(host.id, state)
            }
        }.awaitAll()
    }

    private fun setState(hostId: String, state: HostState) = _states.update { it + (hostId to state) }

    // ---- icons: cached per server as "<host>-<server>-<hash>.png" ----

    private fun iconDir() = File(context.filesDir, "afk-icons").apply { mkdirs() }

    fun iconFile(hostId: String, s: RemoteServer): File? =
        s.iconHash?.let { File(iconDir(), "$hostId-${s.server.id}-${it.take(12)}.png") }?.takeIf { it.isFile }

    private suspend fun syncIcon(host: AfkHost, s: RemoteServer) {
        val hash = s.iconHash ?: return
        val target = File(iconDir(), "${host.id}-${s.server.id}-${hash.take(12)}.png")
        if (target.isFile) return
        val png = withHost(host) { it.call(RemoteRequest(Ops.ICON, 0, id = s.server.id)) }.iconPng ?: return
        iconDir().listFiles()?.filter { it.name.startsWith("${host.id}-${s.server.id}-") }?.forEach { it.delete() }
        target.writeBytes(Base64.getDecoder().decode(png))
    }

    private fun load(): List<AfkHost> =
        runCatching { AfkJson.decodeFromString(ListSerializer(AfkHost.serializer()), file.readText()) }.getOrDefault(emptyList())

    private fun save(list: List<AfkHost>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(AfkJson.encodeToString(ListSerializer(AfkHost.serializer()), list))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        _hosts.value = list
    }

    companion object {
        fun describe(e: Throwable): String = when (e) {
            is kotlinx.coroutines.TimeoutCancellationException -> "PC가 응답하지 않아요"
            is java.net.ConnectException, is java.net.NoRouteToHostException ->
                "PC에 연결할 수 없어요 (프로그램이 켜져 있는지, 같은 와이파이인지 확인)"
            is java.net.UnknownHostException -> "PC 주소를 찾을 수 없어요"
            is java.net.SocketTimeoutException -> "PC가 응답하지 않아요"
            else -> e.message ?: e.javaClass.simpleName
        }

        /** A PC server's state as the phone's own status type (retry time made absolute on this clock). */
        fun status(s: RemoteServer, now: Long = System.currentTimeMillis()) =
            if (!s.server.enabled) AfkStatus.OFF
            else AfkStatus(s.phase, s.message, s.version, retryAt = if (s.retryInMs > 0) now + s.retryInMs else 0)
    }
}
