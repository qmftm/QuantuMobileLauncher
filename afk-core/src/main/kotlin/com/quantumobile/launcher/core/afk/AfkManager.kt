package com.quantumobile.launcher.core.afk

import com.quantumobile.launcher.core.auth.Account
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs one [AfkBot] per enabled server in [store] and exposes their states. Turning a server's switch on or off in
 * the store is all it takes; [start] makes the manager follow the store.
 */
class AfkManager(
    private val store: AfkStore,
    private val scope: CoroutineScope,
    private val connector: AfkConnector,
    private val account: suspend (String) -> Account,
    val network: StateFlow<Boolean>,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Called with a server's list icon each time its bot pings it. */
    private val onServerIcon: (String, ByteArray) -> Unit = { _, _ -> },
) {
    private class Running(val server: AfkServer, val job: Job, val token: Any)

    private val running = mutableMapOf<String, Running>()
    private var started = false

    /** Per-server logs (kept while the app or host runs). [logVersion] changes whenever a line is added. */
    val logBook = AfkLogBook()
    private val _logVersion = MutableStateFlow(0L)
    val logVersion: StateFlow<Long> get() = _logVersion

    private fun addLog(id: String, line: AfkLogLine) {
        logBook.add(id, line)
        _logVersion.update { it + 1 }
    }

    fun logs(id: String, since: Long = 0) = logBook.get(id, since)

    private val _statuses = MutableStateFlow<Map<String, AfkStatus>>(emptyMap())
    /** Status per server ID; servers that are off are missing (treat as [AfkStatus.OFF]). */
    val statuses: StateFlow<Map<String, AfkStatus>> get() = _statuses

    val servers get() = store.servers

    /** Starts following the store (idempotent). Enabled servers connect right away. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch { store.servers.collect { sync(it) } }
    }

    fun setEnabled(id: String, enabled: Boolean) = store.setEnabled(id, enabled)

    /** Adds or edits a server; a running bot reconnects if its address or account changed. */
    fun upsert(server: AfkServer) = store.upsert(server)

    fun remove(id: String) {
        store.remove(id)
        logBook.remove(id)
    }

    fun stopAll() = store.disableAll()

    fun status(id: String) = _statuses.value[id] ?: AfkStatus.OFF

    @Synchronized
    private fun sync(servers: List<AfkServer>) {
        val wanted = servers.filter { it.enabled }.associateBy { it.id }
        // Stop bots that were switched off, removed, or whose address/account changed.
        for ((id, run) in running.toMap()) {
            val now = wanted[id]
            if (now == null || now.host != run.server.host || now.port != run.server.port || now.accountId != run.server.accountId) {
                run.job.cancel()
                running.remove(id)
                _statuses.update { it - id }
                if (servers.any { it.id == id }) addLog(id, AfkLogLine(clock(), AfkLogKind.INFO, if (now == null) "꺼짐" else "설정이 바뀌어 다시 접속해요"))
            }
        }
        for ((id, server) in wanted) {
            if (id in running) continue
            val token = Any()
            val bot = AfkBot(
                server, connector, account, network,
                onStatus = { s -> publish(id, token, s) }, onIcon = { png -> onServerIcon(id, png) },
                onLog = { line -> synchronized(this) { if (running[id]?.token === token) addLog(id, line) } }, clock = clock,
            )
            // Registered before it starts, so its first status update is not taken for a stale one.
            val job = scope.launch(start = CoroutineStart.LAZY) { bot.run() }
            running[id] = Running(server, job, token)
            job.start()
        }
    }

    private fun publish(id: String, token: Any, status: AfkStatus) {
        synchronized(this) {
            if (running[id]?.token !== token) return // a late update from a bot that was stopped
            _statuses.update { it + (id to status) }
        }
    }
}
