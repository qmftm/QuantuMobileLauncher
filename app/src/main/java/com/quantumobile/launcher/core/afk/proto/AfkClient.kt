package com.quantumobile.launcher.core.afk.proto

import com.quantumobile.launcher.core.afk.AfkConnector
import com.quantumobile.launcher.core.afk.AfkDisconnect
import com.quantumobile.launcher.core.afk.AfkEvents
import com.quantumobile.launcher.core.afk.AfkTiming
import com.quantumobile.launcher.core.auth.Account
import com.quantumobile.launcher.core.auth.AccountType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher

/**
 * A minimal Minecraft: Java Edition client that joins a server and idles: it answers keep-alives, pings,
 * resource pack and cookie requests, respawns after death, and taps sneak now and then. Nothing else.
 *
 * Supports 1.20.2 (protocol 764) and newer; see [PacketIds].
 */
class AfkClient(
    private val session: SessionJoiner = MojangSession,
    private val dnsServers: () -> List<InetAddress> = { emptyList() },
    private val timing: AfkTiming = AfkTiming(),
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 35_000,
    private val joinTimeoutMs: Long = 60_000,
) : AfkConnector {

    override suspend fun run(host: String, port: Int, account: Account, events: AfkEvents): AfkDisconnect =
        withContext(Dispatchers.IO) {
            val target = if (port == DEFAULT_PORT) SrvLookup.resolve(host, dnsServers()) else null
            val realHost = target?.host ?: host
            val realPort = target?.port ?: port

            events.note("서버 버전 확인 중")
            val version = ServerPing.query(realHost, realPort, connectTimeoutMs)
            val ids = PacketIds.forProtocol(version.protocol) ?: return@withContext AfkDisconnect(
                "지원하지 않는 서버 버전이에요: ${version.name.ifEmpty { "프로토콜 ${version.protocol}" }} " +
                    "(1.20.2 이상 정식 버전만 지원)",
                retry = false,
            )

            McConnection.open(realHost, realPort, connectTimeoutMs, readTimeoutMs).use { conn ->
                coroutineScope {
                    // Blocking socket reads do not react to cancellation; closing the socket ends them.
                    val closer = launch { try { awaitCancellation() } finally { conn.close() } }
                    try {
                        Session(conn, ids, version, account, events, realHost, realPort).run()
                    } finally {
                        closer.cancel()
                    }
                }
            }
        }

    private enum class State { LOGIN, CONFIG, PLAY }

    private inner class Session(
        private val conn: McConnection,
        private val ids: PacketIds,
        private val version: ServerVersion,
        private val account: Account,
        private val events: AfkEvents,
        private val host: String,
        private val port: Int,
    ) {
        private val protocol = version.protocol
        @Volatile private var state = State.LOGIN
        @Volatile private var entityId = 0
        private var dead = false
        private var loadedSent = false
        private var sneakJob: Job? = null

        // Last position the server put us at (only needed to echo back in teleport confirmations).
        private var x = 0.0
        private var y = 0.0
        private var z = 0.0
        private var yaw = 0f
        private var pitch = 0f

        /** 1.21.2+ Player Position: id, position, velocity, rotation, then flags saying which values are relative. */
        private fun readTeleport(r: PacketReader): Int {
            val id = r.varInt()
            val nx = r.double(); val ny = r.double(); val nz = r.double()
            r.double(); r.double(); r.double() // velocity
            val nYaw = r.float(); val nPitch = r.float()
            val relative = r.int()
            x = if (relative and 0x01 != 0) x + nx else nx
            y = if (relative and 0x02 != 0) y + ny else ny
            z = if (relative and 0x04 != 0) z + nz else nz
            yaw = if (relative and 0x08 != 0) yaw + nYaw else nYaw
            pitch = if (relative and 0x10 != 0) pitch + nPitch else nPitch
            return id
        }

        @Volatile private var joined = false
        @Volatile private var stalled = false

        suspend fun run(): AfkDisconnect = coroutineScope {
            // If the server never lets us into the world (e.g. a newer version whose packets we misread and it waits
            // for an answer we never send), give up with a clear reason instead of sitting there forever.
            val watchdog = launch {
                delay(joinTimeoutMs)
                if (!joined) { stalled = true; conn.close() }
            }
            try {
                conn.send(ServerPing.handshake(protocol, host, port, nextState = 2))
                conn.send(PacketWriter(0x00).string(account.username).uuid(uuidOf(account.uuid)))
                while (isActive) {
                    val packet = try { conn.receive() } catch (e: java.io.IOException) {
                        if (stalled) return@coroutineScope stalledReason()
                        ensureActive() // closed because we were cancelled: report the cancellation, not a socket error
                        throw e
                    }
                    val ended = when (state) {
                        State.LOGIN -> login(packet)
                        State.CONFIG -> config(packet)
                        State.PLAY -> play(packet) { joined = true; startSneaking() }
                    }
                    if (ended != null) return@coroutineScope ended
                }
                AfkDisconnect("중지됨")
            } finally {
                watchdog.cancel()
                sneakJob?.cancel()
            }
        }

        private fun stalledReason(): AfkDisconnect {
            val name = version.name.ifEmpty { "프로토콜 $protocol" }
            return if (PacketIds.exact(protocol) == null) {
                AfkDisconnect("이 서버 버전($name)은 아직 지원 목록에 없어요. 앱 업데이트가 필요해요", retry = false)
            } else {
                AfkDisconnect("서버 입장이 끝나지 않았어요 (시간 초과)")
            }
        }

        /** Taps sneak at random intervals for as long as the session lives. */
        private fun CoroutineScope.startSneaking() {
            if (sneakJob?.isActive == true) return
            sneakJob = launch {
                while (isActive) {
                    delay(timing.nextSneakDelayMs())
                    if (state != State.PLAY) continue
                    sneak(true)
                    delay(timing.sneakHoldMs)
                    sneak(false)
                }
            }
        }

        private fun sneak(down: Boolean) {
            runCatching {
                if (protocol >= PLAYER_INPUT_SINCE) {
                    conn.send(PacketWriter(ids.playInputOut).byte(if (down) INPUT_SHIFT else 0))
                } else {
                    conn.send(PacketWriter(ids.playEntityActionOut).varInt(entityId).varInt(if (down) 0 else 1).varInt(0))
                }
            }
        }

        // ---- login ----

        private suspend fun login(p: Packet): AfkDisconnect? {
            val r = p.body
            when (p.id) {
                0x00 -> return AfkDisconnect(TextComponent.fromJson(r.string()))
                0x01 -> encryption(r)?.let { return it }
                0x02 -> {
                    conn.send(PacketWriter(0x03)) // login acknowledged
                    state = State.CONFIG
                }
                0x03 -> conn.enableCompression(r.varInt())
                0x04 -> conn.send(PacketWriter(0x02).varInt(r.varInt()).bool(false)) // plugin request: not understood
                0x05 -> conn.send(PacketWriter(0x04).string(r.string()).bool(false)) // cookie request: none
            }
            return null
        }

        private suspend fun encryption(r: PacketReader): AfkDisconnect? {
            val serverId = r.string()
            val keyBytes = r.byteArray()
            val token = r.byteArray()
            val authenticate = if (protocol >= 766) r.bool() else true
            if (authenticate && account.type != AccountType.MICROSOFT) {
                return AfkDisconnect("정품 인증 서버예요. 마이크로소프트 계정으로 바꿔 주세요", retry = false)
            }
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
            val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }
            if (authenticate) session.join(account, MojangSession.serverHash(serverId, secret, publicKey))
            val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, publicKey) }
            val encSecret = rsa.doFinal(secret)
            val encToken = rsa.doFinal(token)
            conn.send(PacketWriter(0x01).bytes(encSecret).bytes(encToken))
            conn.enableEncryption(secret)
            return null
        }

        // ---- configuration ----

        private fun config(p: Packet): AfkDisconnect? {
            val r = p.body
            when (p.id) {
                ids.configKeepAlive -> conn.send(PacketWriter(ids.configKeepAliveOut).long(r.long()))
                ids.configPing -> conn.send(PacketWriter(ids.configPongOut).int(r.int()))
                ids.configDisconnect -> return AfkDisconnect(readReason(r))
                ids.configFinish -> {
                    conn.send(PacketWriter(ids.configFinishOut))
                    state = State.PLAY
                }
                ids.configResourcePack -> answerResourcePack(r, ids.configResourcePackOut)
                ids.configKnownPacks -> {
                    // Claim the same data packs the server offers, so it skips sending registry contents we would ignore.
                    val n = r.varInt()
                    val out = PacketWriter(ids.configKnownPacksOut).varInt(n)
                    repeat(n) { out.string(r.string()).string(r.string()).string(r.string()) }
                    conn.send(out)
                }
                ids.configCookieRequest -> conn.send(PacketWriter(ids.configCookieResponseOut).string(r.string()).bool(false))
                ids.configCodeOfConduct -> {
                    conn.send(PacketWriter(ids.configAcceptCodeOfConductOut))
                    events.note("서버 행동 강령에 자동으로 동의했어요")
                }
            }
            return null
        }

        // ---- play ----

        private fun play(p: Packet, onJoined: () -> Unit): AfkDisconnect? {
            val r = p.body
            when (p.id) {
                ids.playKeepAlive -> conn.send(PacketWriter(ids.playKeepAliveOut).long(r.long()))
                ids.playPing -> conn.send(PacketWriter(ids.playPongOut).int(r.int()))
                ids.playDisconnect -> return AfkDisconnect(readReason(r))
                ids.playLogin -> {
                    entityId = r.int()
                    dead = false
                    events.connected(version.name)
                    onJoined()
                }
                ids.playPosition -> {
                    val teleportId = if (protocol >= 768) readTeleport(r) else { r.bytes(33); r.varInt() }
                    val confirm = PacketWriter(ids.playTeleportConfirmOut).varInt(teleportId)
                    // 26.3 added the position to the confirmation (the server only checks the numbers are valid).
                    if (protocol >= TELEPORT_CONFIRM_HAS_POSITION) {
                        confirm.double(x).double(y).double(z).float(yaw).float(pitch)
                    }
                    conn.send(confirm)
                    if (!loadedSent && ids.playLoadedOut >= 0) {
                        conn.send(PacketWriter(ids.playLoadedOut))
                        loadedSent = true
                    }
                }
                ids.playHealth -> {
                    val health = r.float()
                    if (health <= 0f && !dead) {
                        dead = true
                        conn.send(PacketWriter(ids.playClientCommandOut).varInt(0)) // perform respawn
                        events.note("사망해서 자동으로 리스폰했어요")
                    } else if (health > 0f) {
                        dead = false
                    }
                }
                ids.playResourcePack -> answerResourcePack(r, ids.playResourcePackOut)
                ids.playStartConfiguration -> {
                    conn.send(PacketWriter(ids.playConfigurationAckOut))
                    state = State.CONFIG
                }
                ids.playCookieRequest -> if (ids.playCookieResponseOut >= 0) {
                    conn.send(PacketWriter(ids.playCookieResponseOut).string(r.string()).bool(false))
                }
            }
            return null
        }

        /** Says "accepted" then "loaded" without downloading anything, so servers that require a pack let us stay. */
        private fun answerResourcePack(r: PacketReader, outId: Int) {
            if (protocol >= 765) {
                val id = r.uuid()
                conn.send(PacketWriter(outId).uuid(id).varInt(PACK_ACCEPTED))
                conn.send(PacketWriter(outId).uuid(id).varInt(PACK_LOADED))
            } else {
                conn.send(PacketWriter(outId).varInt(PACK_ACCEPTED))
                conn.send(PacketWriter(outId).varInt(PACK_LOADED))
            }
        }

        private fun readReason(r: PacketReader): String =
            runCatching { if (protocol >= 765) TextComponent.fromNbt(r) else TextComponent.fromJson(r.string()) }
                .getOrNull()?.ifBlank { null } ?: "서버가 연결을 끊었어요"
    }

    companion object {
        const val DEFAULT_PORT = 25565
        private const val PLAYER_INPUT_SINCE = 768 // 1.21.2: sneak moved from entity_action to player_input
        private const val INPUT_SHIFT = 0x20
        private const val TELEPORT_CONFIRM_HAS_POSITION = 777 // 26.3
        private const val PACK_ACCEPTED = 3
        private const val PACK_LOADED = 0

        fun uuidOf(undashed: String): UUID {
            val s = undashed.replace("-", "")
            if (s.length != 32) return UUID.nameUUIDFromBytes(undashed.toByteArray())
            return UUID(java.lang.Long.parseUnsignedLong(s.substring(0, 16), 16), java.lang.Long.parseUnsignedLong(s.substring(16), 16))
        }
    }
}
