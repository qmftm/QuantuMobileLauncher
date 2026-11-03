package com.quantumobile.afkhost

import com.quantumobile.launcher.core.afk.AfkFatalException
import com.quantumobile.launcher.core.afk.AfkJson
import com.quantumobile.launcher.core.afk.AfkManager
import com.quantumobile.launcher.core.afk.AfkServer
import com.quantumobile.launcher.core.afk.AfkStatus
import com.quantumobile.launcher.core.afk.AfkStore
import com.quantumobile.launcher.core.afk.AfkConnector
import com.quantumobile.launcher.core.afk.remote.HostInfo
import com.quantumobile.launcher.core.afk.remote.MicrosoftRefresh
import com.quantumobile.launcher.core.afk.remote.Ops
import com.quantumobile.launcher.core.afk.remote.RemoteProtocol
import com.quantumobile.launcher.core.afk.remote.RemoteRequest
import com.quantumobile.launcher.core.afk.remote.RemoteResponse
import com.quantumobile.launcher.core.afk.remote.RemoteServer
import com.quantumobile.launcher.core.auth.Account
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.abs

/**
 * The PC side: keeps the server list, the accounts sent by the phone and the server icons in [dir], runs the bots,
 * and answers the app's requests ([handle]).
 */
class HostService(
    private val dir: File,
    val name: String,
    val version: String,
    scope: CoroutineScope,
    connector: AfkConnector,
    private val refresh: suspend (Account) -> Account = MicrosoftRefresh::refresh,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val store = AfkStore(File(dir, "afk.json"))
    private val accountsFile = File(dir, "accounts.json")
    private val iconDir = File(dir, "icons").apply { mkdirs() }
    private val accounts = MutableStateFlow(loadAccounts())
    private val refreshLock = Mutex()

    val manager = AfkManager(
        store, scope, connector,
        account = ::account,
        network = MutableStateFlow(true),
        clock = clock,
        onServerIcon = { id, png -> saveIcon(serverIconFile(id), png) },
    )

    fun start() = manager.start()

    private suspend fun account(id: String): Account = refreshLock.withLock {
        val a = accounts.value[id] ?: throw AfkFatalException("이 서버의 계정 정보가 PC에 없어요. 앱에서 서버를 다시 저장해 주세요")
        if (!a.needsRefresh) return@withLock a
        refresh(a).also { putAccount(it) }
    }

    /** Answers one decrypted request. */
    suspend fun handle(req: RemoteRequest): RemoteResponse {
        if (abs(clock() - req.time) > RemoteProtocol.MAX_SKEW_MS) {
            return RemoteResponse(ok = false, error = "폰과 PC의 시계가 5분 넘게 달라요. 시간을 맞춰 주세요")
        }
        return when (req.op) {
            Ops.HELLO -> RemoteResponse(host = HostInfo(name, version))
            Ops.LIST -> RemoteResponse(servers = list())
            Ops.UPSERT -> {
                val server = req.server ?: return RemoteResponse(ok = false, error = "서버 정보가 없어요")
                val account = req.account ?: return RemoteResponse(ok = false, error = "계정 정보가 없어요")
                putAccount(account.copy(id = server.accountId))
                req.iconPng?.let { b64 -> runCatching { saveIcon(customIconFile(server.id), Base64.getDecoder().decode(b64)) } }
                if (server.icon != AfkServer.ICON_CUSTOM) customIconFile(server.id).delete()
                manager.upsert(server)
                RemoteResponse(servers = list())
            }
            Ops.SET_ENABLED -> {
                val id = req.id ?: return RemoteResponse(ok = false, error = "서버 ID가 없어요")
                manager.setEnabled(id, req.enabled == true)
                RemoteResponse(servers = list())
            }
            Ops.REMOVE -> {
                val id = req.id ?: return RemoteResponse(ok = false, error = "서버 ID가 없어요")
                val accountId = manager.servers.value.firstOrNull { it.id == id }?.accountId
                manager.remove(id)
                serverIconFile(id).delete(); customIconFile(id).delete()
                // Forget the account once no server uses it.
                if (accountId != null && manager.servers.value.none { it.accountId == accountId }) {
                    accounts.value = accounts.value - accountId
                    saveAccounts()
                }
                RemoteResponse(servers = list())
            }
            Ops.ICON -> {
                val server = manager.servers.value.firstOrNull { it.id == req.id }
                val file = server?.let { iconFile(it) }
                RemoteResponse(iconPng = file?.let { Base64.getEncoder().encodeToString(it.readBytes()) })
            }
            Ops.CHAT -> {
                val id = req.id ?: return RemoteResponse(ok = false, error = "서버 ID가 없어요")
                if (manager.sendChat(id, req.text.orEmpty())) RemoteResponse()
                else RemoteResponse(ok = false, error = "봇이 서버에 접속해 있지 않아요")
            }
            Ops.LOGS -> RemoteResponse(
                logs = req.id?.let { manager.logs(it, req.since ?: 0) }.orEmpty(),
                world = req.id?.let { manager.world(it) },
            )
            else -> RemoteResponse(ok = false, error = "알 수 없는 요청 (${req.op}). 앱이나 PC 프로그램을 업데이트해 주세요")
        }
    }

    fun list(): List<RemoteServer> {
        val now = clock()
        return manager.servers.value.map { s ->
            val st = if (s.enabled) manager.status(s.id) else AfkStatus.OFF
            RemoteServer(
                server = s,
                accountName = accounts.value[s.accountId]?.username ?: "?",
                phase = st.phase, message = st.message, version = st.version,
                retryInMs = (st.retryAt - now).coerceAtLeast(0),
                iconHash = iconFile(s)?.let { sha1(it.readBytes()) },
            )
        }
    }

    private fun iconFile(s: AfkServer): File? = when (s.icon) {
        null -> serverIconFile(s.id)
        AfkServer.ICON_CUSTOM -> customIconFile(s.id)
        else -> null
    }?.takeIf { it.isFile }

    private fun serverIconFile(id: String) = File(iconDir, "$id-server.png")
    private fun customIconFile(id: String) = File(iconDir, "$id.png")

    private fun saveIcon(file: File, png: ByteArray) {
        if (png.size > 512 * 1024) return
        if (file.isFile && file.readBytes().contentEquals(png)) return
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(png)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun loadAccounts(): Map<String, Account> =
        runCatching { AfkJson.decodeFromString(accountsSerializer, accountsFile.readText()) }.getOrDefault(emptyMap())

    @Synchronized
    private fun putAccount(account: Account) {
        accounts.value = accounts.value + (account.id to account)
        saveAccounts()
    }

    @Synchronized
    private fun saveAccounts() {
        val tmp = File(accountsFile.path + ".tmp")
        tmp.writeText(AfkJson.encodeToString(accountsSerializer, accounts.value))
        if (!tmp.renameTo(accountsFile)) { accountsFile.delete(); tmp.renameTo(accountsFile) }
        HostFiles.restrictToOwner(accountsFile)
    }

    private companion object {
        val accountsSerializer = MapSerializer(String.serializer(), Account.serializer())
    }

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }
}
