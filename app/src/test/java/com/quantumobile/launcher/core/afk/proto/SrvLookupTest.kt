package com.quantumobile.launcher.core.afk.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class SrvLookupTest {

    /** A DNS reply with the query echoed and two SRV answers (the second uses name compression). */
    private fun reply(id: Int, rcode: Int = 0): ByteArray {
        val query = SrvLookup.buildQuery(id, "_minecraft._tcp.example.com")
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v ushr 8 and 0xFF); out.write(v and 0xFF) }
        fun u32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        u16(id); u16(0x8180 or rcode); u16(1); u16(2); u16(0); u16(0)
        out.write(query, 12, query.size - 12)
        // answer 1: priority 10 → mc2.example.com:25570
        u16(0xC00C); u16(33); u16(1); u32(300)
        val target = byteArrayOf(3) + "mc2".toByteArray() + byteArrayOf(7) + "example".toByteArray() + byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0)
        u16(6 + target.size); u16(10); u16(5); u16(25570); out.write(target)
        // answer 2: priority 5 (preferred) → mc1.<example.com via pointer>:25599
        u16(0xC00C); u16(33); u16(1); u32(300)
        val suffixOffset = 12 + 1 + "_minecraft".length + 1 + "_tcp".length // "example.com" inside the question
        val t2 = byteArrayOf(3) + "mc1".toByteArray() + byteArrayOf((0xC0 or (suffixOffset ushr 8)).toByte(), suffixOffset.toByte())
        u16(6 + t2.size); u16(5); u16(5); u16(25599); out.write(t2)
        return out.toByteArray()
    }

    @Test
    fun picksTheLowestPriorityRecord() {
        assertEquals(SrvLookup.Target("mc1.example.com", 25599), SrvLookup.parse(reply(0x1234), 0x1234))
    }

    @Test
    fun rejectsMismatchedIdAndErrors() {
        assertNull(SrvLookup.parse(reply(0x1234), 0x9999))
        assertNull(SrvLookup.parse(reply(0x1234, rcode = 3), 0x1234)) // NXDOMAIN
    }

    @Test
    fun ipAddressesSkipTheLookup() {
        assertNull(SrvLookup.resolve("192.168.0.10", emptyList()))
    }
}
