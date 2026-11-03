package com.quantumobile.launcher.core.afk.remote

import com.quantumobile.launcher.core.afk.SimpleHttp
import java.io.IOException

/** Talks to one PC host. Every call is encrypted with the host's pairing code. */
class RemoteClient(private val host: AfkHost, private val clock: () -> Long = System::currentTimeMillis) {
    private val key = RemoteProtocol.key(host.code)

    /** Sends [req] and returns the host's answer; throws on network errors, a wrong code or a refused request. */
    suspend fun call(req: RemoteRequest): RemoteResponse {
        val res = SimpleHttp.post(host.url(), RemoteProtocol.encodeRequest(key, req.copy(time = clock())), "text/plain")
        if (res.code == 403) throw BadCodeException()
        if (!res.isSuccess) throw IOException("PC 응답 오류 (HTTP ${res.code})")
        val answer = RemoteProtocol.decodeResponse(key, res.body)
        if (!answer.ok) throw IOException(answer.error ?: "PC가 요청을 처리하지 못했어요")
        return answer
    }

    suspend fun hello() = call(RemoteRequest(Ops.HELLO, 0)).host!!
    suspend fun list() = call(RemoteRequest(Ops.LIST, 0)).servers
}
