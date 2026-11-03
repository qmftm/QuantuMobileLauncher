package com.quantumobile.launcher.core.afk.proto

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Thrown when a server sends something this client cannot read. */
class ProtocolException(message: String) : IOException(message)

/** Builds one packet body (ID + fields) in Minecraft's wire format. */
class PacketWriter(id: Int) {
    private val out = ByteArrayOutputStream(64)

    init { varInt(id) }

    fun varInt(value: Int) = apply {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) { out.write(v); return@apply }
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    fun string(value: String) = apply { bytes(value.toByteArray(Charsets.UTF_8)) }

    /** Length-prefixed byte array. */
    fun bytes(value: ByteArray) = apply { varInt(value.size); out.write(value) }

    fun raw(value: ByteArray) = apply { out.write(value) }
    fun bool(value: Boolean) = apply { out.write(if (value) 1 else 0) }
    fun byte(value: Int) = apply { out.write(value) }
    fun short(value: Int) = apply { out.write(value ushr 8 and 0xFF); out.write(value and 0xFF) }
    fun int(value: Int) = apply { for (s in 24 downTo 0 step 8) out.write(value ushr s and 0xFF) }
    fun long(value: Long) = apply { for (s in 56 downTo 0 step 8) out.write((value ushr s).toInt() and 0xFF) }
    fun uuid(value: UUID) = apply { long(value.mostSignificantBits); long(value.leastSignificantBits) }
    fun varLong(value: Long) = apply {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) { out.write(v.toInt()); return@apply }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }
    fun float(value: Float) = int(java.lang.Float.floatToRawIntBits(value))
    fun double(value: Double) = long(java.lang.Double.doubleToRawLongBits(value))

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Reads fields from one received packet body. */
class PacketReader(private val data: ByteArray, private var pos: Int = 0) {
    val remaining get() = data.size - pos

    private fun need(n: Int) { if (n < 0 || pos + n > data.size) throw ProtocolException("Packet ended early") }

    fun byte(): Int { need(1); return data[pos++].toInt() and 0xFF }
    fun bool() = byte() != 0

    fun varInt(): Int {
        var result = 0
        for (i in 0 until 5) {
            val b = byte()
            result = result or ((b and 0x7F) shl (7 * i))
            if (b and 0x80 == 0) return result
        }
        throw ProtocolException("VarInt too long")
    }

    fun int(): Int { var v = 0; repeat(4) { v = (v shl 8) or byte() }; return v }

    fun varLong(): Long {
        var result = 0L
        for (i in 0 until 10) {
            val b = byte()
            result = result or ((b and 0x7F).toLong() shl (7 * i))
            if (b and 0x80 == 0) return result
        }
        throw ProtocolException("VarLong too long")
    }
    fun long(): Long { var v = 0L; repeat(8) { v = (v shl 8) or byte().toLong() }; return v }
    fun short(): Int { val v = (byte() shl 8) or byte(); return v.toShort().toInt() }
    fun float() = java.lang.Float.intBitsToFloat(int())
    fun double() = java.lang.Double.longBitsToDouble(long())
    fun uuid() = UUID(long(), long())

    fun bytes(n: Int): ByteArray { need(n); return data.copyOfRange(pos, pos + n).also { pos += n } }
    fun byteArray(): ByteArray = bytes(varInt())
    fun string(): String = String(byteArray(), Charsets.UTF_8)
    fun rest(): ByteArray = bytes(remaining)
}

/** VarInt reading straight from a stream (used for packet framing). Returns -1 on a clean end of stream. */
internal fun InputStream.readVarIntOrEnd(): Int {
    var result = 0
    for (i in 0 until 5) {
        val b = read()
        if (b < 0) { if (i == 0) return -1 else throw EOFException("Connection closed") }
        result = result or ((b and 0x7F) shl (7 * i))
        if (b and 0x80 == 0) return result
    }
    throw ProtocolException("VarInt too long")
}

internal fun InputStream.readFully(n: Int): ByteArray {
    val buf = ByteArray(n)
    var off = 0
    while (off < n) {
        val r = read(buf, off, n - off)
        if (r < 0) throw EOFException("Connection closed")
        off += r
    }
    return buf
}

internal fun varIntSize(value: Int): Int {
    var v = value
    var n = 1
    while (v and 0x7F.inv() != 0) { n++; v = v ushr 7 }
    return n
}
