package com.quantumobile.launcher.core.afk.remote

import com.quantumobile.launcher.core.afk.SimpleHttp
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** Talks to one PC host at [url]. Every call is encrypted with the host's pairing [code]. */
class RemoteClient(val url: String, code: String, private val clock: () -> Long = System::currentTimeMillis) {
    constructor(host: AfkHost, clock: () -> Long = System::currentTimeMillis) :
        this(host.url() ?: throw IOException("PC 주소가 없어요"), host.code, clock)

    private val key = RemoteProtocol.key(code)

    /** Sends [req] and returns the host's answer; throws on network errors, a wrong code or a refused request. */
    suspend fun call(req: RemoteRequest): RemoteResponse {
        val res = SimpleHttp.post(url, RemoteProtocol.encodeRequest(key, req.copy(time = clock())), "text/plain")
        if (res.code == 403) throw BadCodeException()
        if (!res.isSuccess) throw IOException("PC 응답 오류 (HTTP ${res.code})")
        val answer = try { RemoteProtocol.decodeResponse(key, res.body) } catch (e: BadCodeException) {
            throw IOException("PC가 아닌 곳이 응답했어요 (주소 확인)")
        }
        if (!answer.ok) throw IOException(answer.error ?: "PC가 요청을 처리하지 못했어요")
        return answer
    }

    suspend fun hello() = call(RemoteRequest(Ops.HELLO, 0)).host!!
    suspend fun list() = call(RemoteRequest(Ops.LIST, 0)).servers
}

/**
 * Finds a URL where a PC answers: its fixed address if set, the last one that worked, then the addresses the PC
 * posted ([Rendezvous]) — its LAN addresses first, its Cloudflare tunnel last.
 */
class HostLocator(private val rendezvous: Rendezvous = Rendezvous(), private val tryTimeoutMs: Long = 4_000) {

    class Found(val url: String, val info: HostInfo)

    suspend fun locate(host: AfkHost, lastWorking: String? = null): Found {
        val tried = mutableSetOf<String>()
        var lastError: Exception? = null
        suspend fun attempt(url: String?): Found? {
            if (url == null || !tried.add(url)) return null
            return try {
                Found(url, withTimeout(tryTimeoutMs) { RemoteClient(url, host.code).hello() })
            } catch (e: BadCodeException) {
                throw e // the PC is there but the code is wrong: no point trying elsewhere
            } catch (e: Exception) {
                lastError = e
                null
            }
        }
        attempt(host.url())?.let { return it }
        attempt(lastWorking)?.let { return it }
        val posted = try { rendezvous.fetch(host.code) } catch (e: Exception) { lastError = e; null }
        posted?.candidates?.forEach { url -> attempt(AfkHost.endpoint(url))?.let { return it } }
        throw when {
            posted == null && lastError == null -> IOException("PC가 꺼져 있거나 아직 주소를 알리지 않았어요")
            posted == null -> IOException("PC 주소를 찾지 못했어요 (${lastError?.message ?: "인터넷 확인"})")
            else -> IOException("PC가 응답하지 않아요 (프로그램이 켜져 있는지 확인)")
        }
    }
}
