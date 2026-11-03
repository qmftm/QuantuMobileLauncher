package com.quantumobile.launcher.core.afk.proto

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One received packet: its ID and a reader positioned after the ID. */
class Packet(val id: Int, val body: PacketReader)

/**
 * A TCP connection speaking Minecraft's framing: length-prefixed packets, optional zlib compression
 * (after Set Compression) and optional AES/CFB8 encryption (after the encryption handshake).
 *
 * Reads happen on one thread; [send] is safe to call from any thread.
 */
class McConnection private constructor(private val socket: Socket) : Closeable {
    private var input: InputStream = BufferedInputStream(socket.getInputStream(), 16 * 1024)
    private var output: OutputStream = BufferedOutputStream(socket.getOutputStream(), 4 * 1024)
    private val writeLock = Any()
    @Volatile private var threshold = -1
    private val inflater = Inflater()
    private val deflater = Deflater()

    val isClosed get() = socket.isClosed

    fun enableCompression(threshold: Int) { this.threshold = threshold }

    fun enableEncryption(secret: ByteArray) {
        fun cipher(mode: Int) = Cipher.getInstance("AES/CFB8/NoPadding").apply {
            init(mode, SecretKeySpec(secret, "AES"), IvParameterSpec(secret))
        }
        synchronized(writeLock) {
            output.flush()
            output = BufferedOutputStream(CipherOut(socket.getOutputStream(), cipher(Cipher.ENCRYPT_MODE)), 4 * 1024)
        }
        // The server sends nothing between Encryption Request and our response, so the read buffer is empty here
        // and every byte from now on goes through the decrypting stream.
        input = BufferedInputStream(CipherIn(input, cipher(Cipher.DECRYPT_MODE)), 16 * 1024)
    }

    fun send(packet: PacketWriter) {
        val body = packet.toByteArray()
        synchronized(writeLock) {
            val t = threshold
            if (t < 0) {
                writeVarInt(output, body.size)
                output.write(body)
            } else if (body.size < t) {
                writeVarInt(output, body.size + 1)
                output.write(0)
                output.write(body)
            } else {
                val compressed = deflate(body)
                writeVarInt(output, compressed.size + varIntSize(body.size))
                writeVarInt(output, body.size)
                output.write(compressed)
            }
            output.flush()
        }
    }

    /** Blocks until the next packet arrives. Throws [java.io.EOFException] when the server closes the connection. */
    fun receive(): Packet {
        val length = input.readVarIntOrEnd()
        if (length < 0) throw java.io.EOFException("Connection closed by server")
        if (length > MAX_PACKET) throw ProtocolException("Packet too large ($length bytes)")
        val frame = input.readFully(length)
        val data = if (threshold < 0) frame else {
            val r = PacketReader(frame)
            val size = r.varInt()
            if (size == 0) r.rest() else inflate(r.rest(), size)
        }
        val reader = PacketReader(data)
        return Packet(reader.varInt(), reader)
    }

    private fun inflate(data: ByteArray, size: Int): ByteArray {
        if (size > MAX_UNCOMPRESSED) throw ProtocolException("Packet too large ($size bytes)")
        inflater.reset()
        inflater.setInput(data)
        val out = ByteArray(size)
        var off = 0
        while (off < size) {
            val n = inflater.inflate(out, off, size - off)
            if (n == 0 && (inflater.finished() || inflater.needsInput())) break
            off += n
        }
        if (off != size) throw ProtocolException("Bad compressed packet")
        return out
    }

    private fun deflate(data: ByteArray): ByteArray {
        deflater.reset()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 16)
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        return out.toByteArray()
    }

    override fun close() {
        runCatching { socket.close() }
        inflater.end()
        deflater.end()
    }

    private class CipherOut(out: OutputStream, private val cipher: Cipher) : FilterOutputStream(out) {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(cipher.update(b, off, len) ?: return) }
    }

    private class CipherIn(input: InputStream, private val cipher: Cipher) : FilterInputStream(input) {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = `in`.read(b, off, len)
            if (n <= 0) return n
            // CFB8 is a stream mode: the output has exactly as many bytes as the input, so decrypt in place.
            cipher.update(b, off, n, b, off)
            return n
        }

        override fun available() = `in`.available()
        override fun markSupported() = false
    }

    companion object {
        private const val MAX_PACKET = 1 shl 21 // 2 MiB, the vanilla limit
        private const val MAX_UNCOMPRESSED = 1 shl 23

        /** Wraps an already connected socket (used by tests to play the server side). */
        internal fun wrap(socket: Socket) = McConnection(socket)

        fun open(host: String, port: Int, connectTimeoutMs: Int, readTimeoutMs: Int): McConnection {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = readTimeoutMs
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
            return McConnection(socket)
        }

        private fun writeVarInt(out: OutputStream, value: Int) {
            var v = value
            while (true) {
                if (v and 0x7F.inv() == 0) { out.write(v); return }
                out.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
        }
    }
}
