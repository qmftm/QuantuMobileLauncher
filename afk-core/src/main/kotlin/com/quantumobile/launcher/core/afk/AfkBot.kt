package com.quantumobile.launcher.core.afk

import com.quantumobile.launcher.core.auth.Account
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Keeps one server joined: connect, idle until the connection ends, wait (10 s → 60 s), repeat.
 * Runs until cancelled (switch turned off) or until an error that retrying cannot fix.
 */
class AfkBot(
    private val server: AfkServer,
    private val connector: AfkConnector,
    /** Returns a ready-to-use account (refreshed if needed); throws [AfkFatalException] when it is gone. */
    private val account: suspend (String) -> Account,
    private val network: StateFlow<Boolean>,
    private val onStatus: (AfkStatus) -> Unit,
    private val onIcon: (ByteArray) -> Unit = {},
    private val onLog: (AfkLogLine) -> Unit = {},
    private val backoff: Backoff = Backoff(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var status = AfkStatus.OFF

    private fun set(status: AfkStatus) {
        this.status = status
        onStatus(status)
    }

    private fun log(kind: AfkLogKind, text: String) = onLog(AfkLogLine(clock(), kind, text))

    private fun set(phase: AfkPhase, message: String? = null, retryAt: Long = 0) =
        set(AfkStatus(phase, message, status.version, retryAt, since = clock()))

    private val events = object : AfkEvents {
        override fun connected(version: String) {
            backoff.reset()
            set(AfkStatus(AfkPhase.CONNECTED, null, version.ifEmpty { null }, since = clock()))
            log(AfkLogKind.INFO, "접속됨${if (version.isNotEmpty()) " ($version)" else ""} — 월드에 들어갔어요")
        }

        override fun note(message: String) {
            set(status.copy(message = message))
            log(AfkLogKind.INFO, message)
        }

        override fun serverIcon(png: ByteArray) = onIcon(png)

        override fun log(kind: AfkLogKind, text: String) = this@AfkBot.log(kind, text)
    }

    suspend fun run() {
        while (true) {
            if (!network.value) {
                set(AfkPhase.WAITING, MSG_NO_NETWORK)
                log(AfkLogKind.WARN, MSG_NO_NETWORK)
                network.first { it }
            }
            set(AfkPhase.CONNECTING)
            log(AfkLogKind.INFO, "${server.address} 에 접속하는 중")
            val result = connectOnce()
            if (!result.retry) {
                set(AfkPhase.ERROR, result.reason)
                log(AfkLogKind.ERROR, "${result.reason} — 재접속하지 않아요")
                return
            }
            val wait = backoff.nextDelayMs()
            set(AfkPhase.WAITING, result.reason, retryAt = clock() + wait)
            log(AfkLogKind.WARN, "연결 끊김: ${result.reason} — ${wait / 1000}초 뒤 다시 접속")
            waitBeforeRetry(wait)
        }
    }

    /** One connection; also ends it if the phone loses its network, instead of waiting for a socket timeout. */
    private suspend fun connectOnce(): AfkDisconnect = coroutineScope {
        var result: AfkDisconnect? = null
        val connection = launch {
            result = try {
                connector.run(server.host, server.port, account(server.accountId), events)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AfkFatalException) {
                AfkDisconnect(e.message ?: "오류", retry = false)
            } catch (e: Exception) {
                AfkDisconnect(describe(e))
            }
        }
        val watcher = launch {
            network.first { !it }
            result = AfkDisconnect(MSG_NETWORK_LOST)
            connection.cancel()
        }
        connection.join()
        watcher.cancel()
        result ?: AfkDisconnect(MSG_NETWORK_LOST)
    }

    /** Waits [ms], but reconnects at once when the network drops and comes back meanwhile. */
    private suspend fun waitBeforeRetry(ms: Long) {
        withTimeoutOrNull(ms) {
            network.first { !it }
            network.first { it }
            backoff.reset()
        } ?: return
        delay(NETWORK_SETTLE_MS) // let DNS and routes settle after the network returns
    }

    companion object {
        const val MSG_NO_NETWORK = "네트워크 연결을 기다리는 중"
        const val MSG_NETWORK_LOST = "네트워크 연결이 끊어졌어요"
        private const val NETWORK_SETTLE_MS = 2_000L

        /** A short Korean explanation for a failed connection. Never includes tokens (exceptions here carry none). */
        fun describe(e: Throwable): String = when (e) {
            is UnknownHostException -> "서버 주소를 찾을 수 없어요"
            is SocketTimeoutException -> "서버가 응답하지 않아요 (시간 초과)"
            is ConnectException, is NoRouteToHostException -> "서버에 연결할 수 없어요 (꺼져 있거나 주소·포트가 틀렸어요)"
            is EOFException -> "서버가 연결을 끊었어요"
            is SocketException -> "연결이 끊어졌어요"
            else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        }
    }
}
