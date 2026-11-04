package com.quantumobile.launcher.core.afk.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.UUID

class ChatSigningTest {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
    private val sender = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
    private val session = UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210")

    private fun certificate() = PlayerCertificate(0, 0, pair.public.encoded, ByteArray(256), pair.private)

    private fun verifies(sig: ByteArray, payload: ByteArray) =
        Signature.getInstance("SHA256withRSA").run { initVerify(pair.public); update(payload); verify(sig) }

    @Test
    fun payloadHasTheVanillaLayout() {
        val p = ChatSigner.payload(sender, session, 3, salt = 0x1122334455667788, epochSecond = 1_700_000_000L, text = "hi".toByteArray())
        val b = java.nio.ByteBuffer.wrap(p)
        assertEquals(1, b.int)                                   // version
        assertEquals(sender.mostSignificantBits, b.long); assertEquals(sender.leastSignificantBits, b.long)
        assertEquals(session.mostSignificantBits, b.long); assertEquals(session.leastSignificantBits, b.long)
        assertEquals(3, b.int)                                   // chain index
        assertEquals(0x1122334455667788, b.long)                 // salt
        assertEquals(1_700_000_000L, b.long)                     // timestamp in seconds
        assertEquals(2, b.int); assertEquals('h'.code.toByte(), b.get()); assertEquals('i'.code.toByte(), b.get())
        assertEquals(0, b.int)                                   // seen messages
        assertFalse(b.hasRemaining())
    }

    @Test
    fun eachSignedMessageIsTheNextLinkAndVerifies() {
        val signer = ChatSigner(sender, session, certificate())
        val t = 1_700_000_123_456L
        val first = signer.sign("hello", t, 11)
        val second = signer.sign("hello", t, 11)
        assertEquals(256, first.size)
        assertTrue(verifies(first, ChatSigner.payload(sender, session, 0, 11, 1_700_000_123, "hello".toByteArray())))
        assertTrue(verifies(second, ChatSigner.payload(sender, session, 1, 11, 1_700_000_123, "hello".toByteArray())))
        // Same text, different index → different data, so the chain order matters.
        assertFalse(verifies(second, ChatSigner.payload(sender, session, 0, 11, 1_700_000_123, "hello".toByteArray())))
    }

    @Test
    fun utf8TextIsSignedByBytes() {
        val signer = ChatSigner(sender, session, certificate())
        val sig = signer.sign("안녕", 5_000, 1)
        assertTrue(verifies(sig, ChatSigner.payload(sender, session, 0, 1, 5, "안녕".toByteArray(Charsets.UTF_8))))
    }

    @Test
    fun mojangAnswerIsParsed() {
        val priv = Base64.getMimeEncoder(76, "\n".toByteArray()).encodeToString(pair.private.encoded)
        val pub = Base64.getMimeEncoder(76, "\n".toByteArray()).encodeToString(pair.public.encoded)
        val sigV2 = Base64.getEncoder().encodeToString(ByteArray(512) { it.toByte() })
        val json = """{"keyPair":{"privateKey":"-----BEGIN RSA PRIVATE KEY-----\n$priv\n-----END RSA PRIVATE KEY-----\n",
            "publicKey":"-----BEGIN RSA PUBLIC KEY-----\n$pub\n-----END RSA PUBLIC KEY-----\n"},
            "publicKeySignature":"AAAA","publicKeySignatureV2":"$sigV2",
            "expiresAt":"2026-11-06T10:00:00.123456789Z","refreshedAfter":"2026-11-05T10:00:00Z"}""".replace("\\n", "\\n")
        val c = MojangCertificates.parse(json)
        assertArrayEquals(pair.public.encoded, c.publicKey)
        assertEquals(512, c.keySignature.size)
        assertEquals(java.time.Instant.parse("2026-11-06T10:00:00.123456789Z").toEpochMilli(), c.expiresAt)
        assertEquals(java.time.Instant.parse("2026-11-05T10:00:00Z").toEpochMilli(), c.refreshAfter)
        // The parsed private key matches the public key.
        val sig = ChatSigner(sender, session, c).sign("x", 0, 0)
        assertTrue(verifies(sig, ChatSigner.payload(sender, session, 0, 0, 0, "x".toByteArray())))
        assertFalse("key material must not show in toString", c.toString().contains("PRIVATE"))
    }

    @Test
    fun certificateIsReusedOnlyWhileFresh() {
        val now = 1_000_000L
        assertTrue(PlayerCertificate(now + 10 * 60_000, now + 5 * 60_000, ByteArray(0), ByteArray(0), pair.private).usable(now))
        assertFalse(PlayerCertificate(now + 10 * 60_000, now - 1, ByteArray(0), ByteArray(0), pair.private).usable(now)) // refresh due
        assertFalse(PlayerCertificate(now + 30_000, now + 60 * 60_000, ByteArray(0), ByteArray(0), pair.private).usable(now)) // about to expire
    }

    @Test
    fun signedPartOfCommands() {
        assertEquals(listOf("message" to "hi there"), SignableCommands.arguments("msg Alex hi there"))
        assertEquals(listOf("message" to "hi"), SignableCommands.arguments("tell Alex hi"))
        assertEquals(listOf("message" to "hi"), SignableCommands.arguments("W Alex hi"))
        assertEquals(listOf("message" to "hello  world"), SignableCommands.arguments("say hello  world"))
        assertEquals(listOf("action" to "waves"), SignableCommands.arguments("me waves"))
        assertEquals(listOf("message" to "gg"), SignableCommands.arguments("tm gg"))
        assertEquals(emptyList<Pair<String, String>>(), SignableCommands.arguments("msg Alex"))   // no message yet
        assertEquals(emptyList<Pair<String, String>>(), SignableCommands.arguments("say"))
        assertEquals(emptyList<Pair<String, String>>(), SignableCommands.arguments("spawn now"))
    }

    @Test
    fun sessionUpdatePacketFollowsTheChatPacketInEveryVersion() {
        // From the vanilla packet reports (1.21.1 – 26.3) and minecraft-data (1.20.2 – 1.20.6).
        val expected = mapOf(764 to 6, 765 to 6, 766 to 7, 767 to 7, 768 to 8, 769 to 8, 770 to 8, 771 to 9, 772 to 9,
            773 to 9, 774 to 9, 775 to 10, 776 to 10, 777 to 10)
        for ((protocol, id) in expected) assertEquals("protocol $protocol", id, PacketIds.exact(protocol)!!.playChatSessionOut)
    }
}
