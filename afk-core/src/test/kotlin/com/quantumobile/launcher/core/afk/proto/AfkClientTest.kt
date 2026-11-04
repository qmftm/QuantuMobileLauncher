package com.quantumobile.launcher.core.afk.proto

import com.quantumobile.launcher.core.afk.AfkDisconnect
import com.quantumobile.launcher.core.afk.AfkEvents
import com.quantumobile.launcher.core.afk.AfkTiming
import com.quantumobile.launcher.core.auth.Account
import com.quantumobile.launcher.core.auth.AccountType
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Runs the real client against a small fake server on localhost that plays the server side of the protocol:
 * status ping, login (optionally with encryption and compression), configuration, then play.
 */
class AfkClientTest {

    private val offline = Account.offline("Steve")
    private val microsoft = Account(type = AccountType.MICROSOFT, username = "Alex", uuid = "0123456789abcdef0123456789abcdef", accessToken = "secret-token")

    private class Recorder : AfkEvents {
        val connected = CopyOnWriteArrayList<String>()
        val notes = CopyOnWriteArrayList<String>()
        val icons = CopyOnWriteArrayList<ByteArray>()
        val logs = CopyOnWriteArrayList<String>()
        @Volatile var world: com.quantumobile.launcher.core.afk.AfkWorldInfo? = null
        @Volatile var sender: ((String) -> Boolean)? = null
        override fun chatSender(send: ((String) -> Boolean)?) { if (send != null) sender = send }
        override fun world(info: com.quantumobile.launcher.core.afk.AfkWorldInfo) { world = info }
        override fun log(kind: com.quantumobile.launcher.core.afk.AfkLogKind, text: String) { logs += "$kind $text" }
        override fun connected(version: String) { connected += version }
        override fun note(message: String) { notes += message }
        override fun serverIcon(png: ByteArray) { icons += png }
    }

    /** Fast sneaking so the test sees a full press/release cycle. */
    private val fast = AfkTiming(Random(1), sneakMinMs = 30, sneakMaxMs = 60, sneakHoldMs = 20)

    private class FakeServer(
        val protocol: Int,
        val versionName: String,
        val online: Boolean,
        val compression: Int,
        /** Set when the bot should have a chat key: the server then checks the session update and every signature. */
        val signingKey: java.security.PublicKey? = null,
        val signingExpiresAt: Long = 0,
        val signingKeySignature: ByteArray = ByteArray(0),
    ) {
        val socket = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val port get() = socket.localPort
        val ids = PacketIds.exact(protocol)
        val failure = AtomicReference<Throwable?>(null)
        val loginName = AtomicReference<String?>(null)
        val sneaks: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
        /** The bot's chat-key announcement, if it arrived while the server was waiting for something else. */
        private val sessionPacket = AtomicReference<PacketReader?>(null)
        var respawned = false
        var serverHash: String? = null
        /** Called once the bot is in the world, to make it send chat (from the test). */
        @Volatile var sendFromBot: (() -> Unit)? = null

        fun start(statusOnly: Boolean = false) = thread(isDaemon = true) {
            try {
                socket.accept().use { s -> status(McConnection.wrap(s)) }
                if (!statusOnly) socket.accept().use { s -> play(McConnection.wrap(s)) }
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                socket.close()
            }
        }

        private fun status(c: McConnection) {
            c.receive() // handshake
            c.receive() // status request
            c.send(PacketWriter(0).string("""{"version":{"name":"$versionName","protocol":$protocol},"players":{"max":20,"online":0},"favicon":"data:image/png;base64,iVBORw0KGgo="}"""))
        }

        /** Reads packets until [id], recording sneaks on the way (they can arrive at any time). */
        private fun McConnection.expect(id: Int): PacketReader {
            while (true) {
                val p = receive()
                if (ids != null && protocol >= 768 && p.id == ids.playInputOut) { sneaks += p.body.byte() == 0x20; continue }
                if (ids != null && protocol < 768 && p.id == ids.playEntityActionOut) {
                    p.body.varInt(); sneaks += p.body.varInt() == 0; continue
                }
                if (ids != null && p.id == ids.playChatSessionOut && id != p.id) { sessionPacket.set(p.body); continue }
                if (p.id == id) return p.body
                // Anything else (e.g. player_loaded) is fine to skip.
            }
        }

        private fun play(c: McConnection) {
            val hs = c.receive().body
            assertEquals(protocol, hs.varInt()); hs.string(); hs.short(); assertEquals(2, hs.varInt())
            val start = c.receive().body
            loginName.set(start.string())
            val uuid = start.uuid()

            if (online) {
                val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.genKeyPair()
                val token = byteArrayOf(1, 2, 3, 4)
                val req = PacketWriter(0x01).string("").bytes(keys.public.encoded).bytes(token)
                if (protocol >= 766) req.bool(true)
                c.send(req)
                val resp = c.expect(0x01)
                val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, keys.private) }
                val secret = rsa.doFinal(resp.byteArray())
                assertTrue(rsa.doFinal(resp.byteArray()).contentEquals(token))
                serverHash = MojangSession.serverHash("", secret, keys.public)
                c.enableEncryption(secret)
            }
            if (compression >= 0) {
                c.send(PacketWriter(0x03).varInt(compression))
                c.enableCompression(compression)
            }
            c.send(PacketWriter(0x02).uuid(uuid).string(loginName.get()!!).varInt(0).also { if (protocol in 766..767) it.bool(true) })
            c.expect(0x03) // login acknowledged

            val ids = ids!!
            // configuration
            if (ids.configKnownPacks >= 0) {
                c.send(PacketWriter(ids.configKnownPacks).varInt(1).string("minecraft").string("core").string(versionName))
                val echo = c.expect(ids.configKnownPacksOut)
                assertEquals(1, echo.varInt()); assertEquals("minecraft", echo.string())
            }
            val pack = UUID.randomUUID()
            // 1.20.3 added the pack's UUID to the request and the answers.
            c.send(PacketWriter(ids.configResourcePack).also { if (protocol >= 765) it.uuid(pack) }.string("https://example.invalid/p.zip").string("").bool(true).bool(false))
            for (expected in listOf(3, 0)) {
                val r = c.expect(ids.configResourcePackOut)
                if (protocol >= 765) assertEquals(pack, r.uuid())
                assertEquals(expected, r.varInt())
            }
            c.send(PacketWriter(ids.configKeepAlive).long(42))
            assertEquals(42L, c.expect(ids.configKeepAliveOut).long())
            c.send(PacketWriter(ids.configPing).int(9))
            assertEquals(9, c.expect(ids.configPongOut).int())
            // A big packet, so compression is exercised in this direction too.
            c.send(PacketWriter(0x7F).raw(ByteArray(5000) { (it % 7).toByte() }))
            c.send(PacketWriter(ids.configFinish))
            c.expect(ids.configFinishOut)

            // play
            // Login: entity id, hardcore, world names, limits, flags, then spawn info (dimension "the_nether", creative).
            val login = PacketWriter(ids.playLogin).int(77).bool(false).varInt(1).string("minecraft:overworld")
                .varInt(20).varInt(10).varInt(10).bool(false).bool(true).bool(false)
            if (protocol <= 765) login.string("minecraft:the_nether") else login.varInt(1)
            login.string("minecraft:the_nether").long(0).byte(1).raw(ByteArray(30))
            c.send(login)
            val pos = PacketWriter(ids.playPosition)
            if (protocol >= 768) {
                pos.varInt(5).double(1.5).double(64.0).double(-2.5).raw(ByteArray(8 * 3)).float(90f).float(0f).int(0)
            } else {
                pos.raw(ByteArray(33)).varInt(5)
            }
            c.send(pos)
            val confirm = c.expect(ids.playTeleportConfirmOut)
            assertEquals(5, confirm.varInt())
            if (protocol >= 777) { // 26.3 echoes the position back
                assertEquals(1.5, confirm.double(), 0.0); assertEquals(64.0, confirm.double(), 0.0); assertEquals(-2.5, confirm.double(), 0.0)
                assertEquals(90f, confirm.float(), 0f); assertEquals(0f, confirm.float(), 0f)
            }
            assertEquals(0, confirm.remaining)
            c.send(PacketWriter(ids.playHealth).raw(floatBytes(0f)).varInt(20).raw(floatBytes(5f)))
            assertEquals(0, c.expect(ids.playClientCommandOut).varInt())
            respawned = true
            c.send(PacketWriter(ids.playKeepAlive).long(7))
            assertEquals(7L, c.expect(ids.playKeepAliveOut).long())

            // Chat the bot should log: a system message, an action bar (skipped), a player message, an anonymous one.
            fun text(s: String) = if (protocol >= 765) {
                val b = s.toByteArray(); PacketWriter(0).byte(8).short(b.size).raw(b).toByteArray().drop(1).toByteArray()
            } else {
                PacketWriter(0).string("\"$s\"").toByteArray().drop(1).toByteArray()
            }
            c.send(PacketWriter(ids.playSystemChat).raw(text("Server restarting soon")).bool(false))
            c.send(PacketWriter(ids.playSystemChat).raw(text("action bar spam")).bool(true))
            val pc = PacketWriter(ids.playPlayerChat)
            if (protocol >= 770) pc.varInt(3)
            pc.uuid(UUID.randomUUID()).varInt(0).bool(false).string("hello there").long(0).long(0).varInt(0).bool(false).varInt(0)
            pc.varInt(1).raw(text("Alex")).bool(false)
            c.send(pc)
            c.send(PacketWriter(ids.playDisguisedChat).raw(text("hi from console")).varInt(1).raw(text("Server")).bool(false))
            // World state: night time, two players join (bits: add_player, listed, latency), one leaves.
            val time = PacketWriter(ids.playTime).long(1000)
            when {
                protocol >= 775 -> time.varInt(1).varInt(0).varLong(24_000L * 3 + 18_000).raw(ByteArray(8))
                protocol >= 768 -> time.long(18_000).bool(true)
                else -> time.long(18_000)
            }
            c.send(time)
            val alex = UUID.randomUUID(); val steve = UUID.randomUUID()
            val info = PacketWriter(ids.playInfoUpdate).byte(0b11001).varInt(2)
            for ((id, name) in listOf(alex to "Alex", steve to "Steve")) info.uuid(id).string(name).varInt(0).bool(true).varInt(50)
            c.send(info)
            c.send(PacketWriter(ids.playInfoRemove).varInt(1).uuid(steve))
            c.send(PacketWriter(ids.playHealth).raw(floatBytes(15.5f)).varInt(17).raw(floatBytes(2f)))
            c.send(PacketWriter(ids.playKeepAlive).long(9))
            assertEquals(9L, c.expect(ids.playKeepAliveOut).long()) // all of the above was read without breaking the stream

            // 65 signed chat messages: the bot must acknowledge them (servers kick past 4096 unacknowledged).
            repeat(65) {
                val signed = PacketWriter(ids.playPlayerChat)
                if (protocol >= 770) signed.varInt(it)
                signed.uuid(UUID.randomUUID()).varInt(it).bool(true).raw(ByteArray(256)).string("spam $it").long(0).long(0).varInt(0).bool(false).varInt(0)
                signed.varInt(1).raw(text("Alex")).bool(false)
                c.send(signed)
            }
            assertEquals(65, c.expect(ids.playChatAckOut).varInt())

            // Chat and commands typed in the app.
            sendFromBot?.invoke()
            var index = 0
            val sender = uuid
            val chat = c.expect(ids.playChatOut)
            // The first message made the bot announce its chat key (it arrives just before that message).
            var sessionId: UUID? = null
            if (signingKey != null) {
                val session = sessionPacket.getAndSet(null) ?: error("no chat session update before the first chat")
                sessionId = session.uuid()
                assertEquals(signingExpiresAt, session.long())
                assertTrue(session.byteArray().contentEquals(signingKey.encoded))
                assertTrue(session.byteArray().contentEquals(signingKeySignature))
                assertEquals(0, session.remaining)
            }
            assertEquals("안녕하세요 server", chat.string()); val chatTime = chat.long(); val chatSalt = chat.long()
            if (signingKey == null) {
                assertFalse(chat.bool()) // no signature
            } else {
                assertTrue(chat.bool())
                assertTrue("chat signature", verifies(signingKey, chat.bytes(256), sender, sessionId!!, index++, chatSalt, chatTime, "안녕하세요 server"))
            }
            assertEquals(0, chat.varInt()) // nothing left to acknowledge
            chat.bytes(3); if (protocol >= 770) chat.byte()
            assertEquals(0, chat.remaining)
            val plugin = c.expect(ids.playCommandOut)
            assertEquals("spawn", plugin.string())
            if (protocol < 766) { plugin.long(); plugin.long(); assertEquals(0, plugin.varInt()); plugin.varInt(); plugin.bytes(3) }
            assertEquals(0, plugin.remaining)
            val msg = c.expect(if (protocol >= 766) ids.playSignedCommandOut else ids.playCommandOut)
            assertEquals("msg Alex hi", msg.string()); val msgTime = msg.long(); val msgSalt = msg.long()
            if (signingKey == null) {
                assertEquals(0, msg.varInt()) // no argument signatures
            } else {
                assertEquals(1, msg.varInt())
                assertEquals("message", msg.string())
                // Only the message ("hi") is signed, as the next link of the chain.
                assertTrue("command signature", verifies(signingKey, msg.bytes(256), sender, sessionId!!, index++, msgSalt, msgTime, "hi"))
            }
            msg.varInt(); msg.bytes(3); if (protocol >= 770) msg.byte()
            assertEquals(0, msg.remaining)

            // Wait for one sneak press and release, answering keep-alives like a real server would.
            val deadline = System.currentTimeMillis() + 5_000
            while (!(sneaks.contains(true) && sneaks.lastOrNull() == false) && System.currentTimeMillis() < deadline) {
                c.send(PacketWriter(ids.playKeepAlive).long(8))
                c.expect(ids.playKeepAliveOut)
                Thread.sleep(20)
            }
            val bye = PacketWriter(ids.playDisconnect)
            if (protocol >= 765) bye.byte(8).short(3).raw("bye".toByteArray()) else bye.string("\"bye\"")
            c.send(bye)
            Thread.sleep(200)
        }

        /** Re-implements what a vanilla server checks, from the byte layout alone (not using the client's code). */
        private fun verifies(
            key: java.security.PublicKey, signature: ByteArray, sender: UUID, session: UUID, index: Int,
            salt: Long, timestampMs: Long, text: String,
        ): Boolean {
            val bytes = java.io.ByteArrayOutputStream()
            java.io.DataOutputStream(bytes).apply {
                writeInt(1)
                writeLong(sender.mostSignificantBits); writeLong(sender.leastSignificantBits)
                writeLong(session.mostSignificantBits); writeLong(session.leastSignificantBits)
                writeInt(index)
                writeLong(salt)
                writeLong(timestampMs / 1000)
                val t = text.toByteArray(Charsets.UTF_8)
                writeInt(t.size); write(t)
                writeInt(0) // no earlier messages marked as seen
            }
            return java.security.Signature.getInstance("SHA256withRSA").run {
                initVerify(key); update(bytes.toByteArray()); verify(signature)
            }
        }

        private fun floatBytes(f: Float) = java.nio.ByteBuffer.allocate(4).putFloat(f).array()
    }

    private fun run(
        server: FakeServer, account: Account, joined: MutableList<String> = mutableListOf(),
        certificates: CertificateFetcher = CertificateFetcher { throw java.io.IOException("no key in this test") },
    ): Pair<AfkDisconnect, Recorder> {
        val events = Recorder()
        server.sendFromBot = {
            val send = events.sender!!
            assertTrue(send("안녕하세요 server")); assertTrue(send("/spawn")); assertTrue(send("/msg Alex hi"))
            assertFalse(send("   ")) // nothing to send
        }
        val client = AfkClient(session = { acc, hash -> joined += "${acc.username}:$hash" }, certificates = certificates, timing = fast)
        val result = runBlocking { withTimeout(20_000) { client.run("127.0.0.1", server.port, account, events) } }
        return result to events
    }

    @Test
    fun onlineServerWithEncryptionAndCompression_1_21_4() {
        val server = FakeServer(769, "1.21.4", online = true, compression = 64)
        val t = server.start()
        val joined = mutableListOf<String>()
        val (result, events) = run(server, microsoft, joined)
        t.join(5_000)
        server.failure.get()?.let { throw it }

        assertEquals(AfkDisconnect("bye", retry = true), result)
        assertEquals("Alex", server.loginName.get())
        assertEquals(listOf("Alex:${server.serverHash}"), joined) // joined the session with the same hash the server computed
        assertEquals(listOf("1.21.4"), events.connected)
        // The status ping's favicon is passed on, decoded (these bytes are the PNG signature).
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), events.icons.single().take(4).map { it.toInt() and 0xFF })
        assertTrue(server.respawned)
        assertTrue(events.notes.any { "리스폰" in it })
        assertChat(events)
        assertWorld(events)
        // Sneak goes through player_input on 1.21.2+: pressed, then released.
        assertTrue(server.sneaks.contains(true))
        assertEquals(false, server.sneaks.last())
    }

    @Test
    fun offlineServer_1_20_4_sneaksWithEntityAction() {
        val server = FakeServer(765, "1.20.4", online = false, compression = -1)
        val t = server.start()
        val (result, events) = run(server, offline)
        t.join(5_000)
        server.failure.get()?.let { throw it }

        assertEquals("bye", result.reason)
        assertEquals(listOf("1.20.4"), events.connected)
        assertChat(events)
        assertWorld(events, positioned = false)
        assertTrue(server.sneaks.contains(true))
        assertEquals(false, server.sneaks.last())
    }

    @Test
    fun newest_26_1() {
        val server = FakeServer(775, "26.1", online = false, compression = 256)
        val t = server.start()
        val (result, _) = run(server, offline)
        t.join(5_000)
        server.failure.get()?.let { throw it }
        assertEquals("bye", result.reason)
    }

    @Test
    fun newest_26_3_withCompressionAndEncryption() {
        val server = FakeServer(777, "26.3", online = true, compression = 256)
        val t = server.start()
        val (result, events) = run(server, microsoft)
        t.join(5_000)
        server.failure.get()?.let { throw it }
        assertEquals("bye", result.reason)
        assertEquals(listOf("26.3"), events.connected)
        assertChat(events)
        assertWorld(events)
    }

    private fun signedChatOn(protocol: Int, name: String, compression: Int) {
        val pair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        val certificate = PlayerCertificate(
            expiresAt = System.currentTimeMillis() + 48 * 3_600_000L,
            refreshAfter = System.currentTimeMillis() + 24 * 3_600_000L,
            publicKey = pair.public.encoded,
            keySignature = ByteArray(512) { (it * 7).toByte() },
            privateKey = pair.private,
        )
        val server = FakeServer(
            protocol, name, online = true, compression = compression,
            signingKey = pair.public, signingExpiresAt = certificate.expiresAt, signingKeySignature = certificate.keySignature,
        )
        val t = server.start()
        val (result, events) = try {
            run(server, microsoft, certificates = { certificate })
        } catch (e: Throwable) {
            t.join(2_000)
            throw server.failure.get() ?: e // the server's assertion explains a dropped connection
        }
        t.join(5_000)
        server.failure.get()?.let { throw it }
        assertEquals("bye", result.reason)
        // The key material never shows up in the log.
        assertTrue(events.logs.none { "RSA" in it || "PRIVATE" in it })
    }

    @Test fun signedChat_1_20_2() = signedChatOn(764, "1.20.2", -1)
    @Test fun signedChat_1_20_6() = signedChatOn(766, "1.20.6", 64)
    @Test fun signedChat_1_21_4() = signedChatOn(769, "1.21.4", 256)
    @Test fun signedChat_26_3() = signedChatOn(777, "26.3", 256)

    @Test
    fun chatStaysUnsignedWhenTheKeyCannotBeFetched() {
        val server = FakeServer(769, "1.21.4", online = true, compression = -1) // expects "no signature"
        val t = server.start()
        val (result, events) = run(server, microsoft) // the default test fetcher fails
        t.join(5_000)
        server.failure.get()?.let { throw it }
        assertEquals("bye", result.reason)
        assertTrue(events.logs.any { it.startsWith("WARN ") && "서명 없이" in it && "no key in this test" in it })
    }

    private fun assertWorld(events: Recorder, positioned: Boolean = true) {
        val w = events.world!!
        assertEquals("minecraft:the_nether", w.dimension)
        assertEquals(1, w.gameMode)
        assertEquals(18_000L, w.dayTime)
        assertEquals(listOf("Alex"), w.players)
        assertEquals(15.5f, w.health!!, 0f)
        assertEquals(17, w.food)
        if (positioned) assertEquals(listOf(1.5, 64.0, -2.5), listOf(w.x, w.y, w.z))
    }

    private fun assertChat(events: Recorder) {
        val chat = events.logs.filter { it.startsWith("CHAT ") }.map { it.removePrefix("CHAT ") }
        assertEquals(listOf("Server restarting soon", "<Alex> hello there", "<Server> hi from console"), chat.take(3))
        assertEquals("<Alex> spam 64", chat.last())
    }

    @Test
    fun oldServersAreRefusedWithAReason() {
        val server = FakeServer(763, "1.20.1", online = false, compression = -1)
        server.start(statusOnly = true)
        val (result, events) = run(server, offline)
        assertFalse(result.retry)
        assertTrue(result.reason, "1.20.1" in result.reason && "1.20.2" in result.reason)
        assertTrue(events.connected.isEmpty())
    }

    @Test
    fun offlineAccountCannotJoinAnOnlineModeServer() {
        val server = FakeServer(769, "1.21.4", online = true, compression = -1)
        server.start()
        val (result, _) = run(server, offline)
        assertFalse(result.retry)
        assertTrue(result.reason.contains("마이크로소프트"))
        assertNull(server.serverHash)
    }

    @Test
    fun aServerThatNeverLetsUsInGivesAClearReason() {
        // A future version (778) whose server just waits: the client must not hang forever.
        val socket = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            runCatching {
                socket.accept().use { s ->
                    val c = McConnection.wrap(s); c.receive(); c.receive()
                    c.send(PacketWriter(0).string("""{"version":{"name":"27.1","protocol":778}}"""))
                }
                socket.accept().use { s -> val c = McConnection.wrap(s); while (true) c.receive() }
            }
            socket.close()
        }
        val client = AfkClient(timing = fast, joinTimeoutMs = 500)
        val result = runBlocking { withTimeout(10_000) { client.run("127.0.0.1", socket.localPort, offline, Recorder()) } }
        assertFalse(result.retry)
        assertTrue(result.reason, "27.1" in result.reason && "업데이트" in result.reason)
    }

    @Test
    fun cancellingAConnectedClientIsACleanStop() {
        // A server that lets us in and then just keeps us there.
        val server = FakeServer(769, "1.21.4", online = false, compression = -1)
        server.start()
        val joined = kotlinx.coroutines.CompletableDeferred<Unit>()
        val events = object : AfkEvents {
            override fun connected(version: String) { joined.complete(Unit) }
            override fun note(message: String) = Unit
        }
        val outcome = runBlocking {
            val job = async(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { AfkClient(timing = AfkTiming(sneakMinMs = 60_000, sneakMaxMs = 60_000)).run("127.0.0.1", server.port, offline, events) }
            }
            withTimeout(10_000) { joined.await() }
            job.cancel()
            runCatching { job.await() }
        }
        assertTrue("expected cancellation, got $outcome", outcome.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }

    @Test
    fun everyKnownProtocolHasTheIdsTheClientNeeds() {
        for (p in PacketIds.OLDEST..PacketIds.NEWEST_KNOWN) {
            val ids = PacketIds.exact(p) ?: error("missing table for $p")
            listOf(ids.configFinish, ids.configKeepAlive, ids.playKeepAlive, ids.playLogin, ids.playDisconnect,
                ids.playKeepAliveOut, ids.playTeleportConfirmOut, ids.playClientCommandOut).forEach { assertTrue("$p", it >= 0) }
            assertTrue("$p sneak", if (p >= 768) ids.playInputOut >= 0 else ids.playEntityActionOut >= 0)
        }
        assertEquals(PacketIds.exact(PacketIds.NEWEST_KNOWN), PacketIds.forProtocol(PacketIds.NEWEST_KNOWN + 2))
        assertNull(PacketIds.forProtocol(763))
        assertNull(PacketIds.forProtocol(0x40000000 or 200)) // snapshots
    }
}
