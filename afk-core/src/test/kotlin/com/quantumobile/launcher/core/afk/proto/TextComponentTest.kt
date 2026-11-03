package com.quantumobile.launcher.core.afk.proto

import org.junit.Assert.assertEquals
import org.junit.Test

class TextComponentTest {

    @Test
    fun jsonForms() {
        assertEquals("You are banned", TextComponent.fromJson("\"You are banned\""))
        assertEquals("Server restarting in 5", TextComponent.fromJson("""{"text":"Server ","extra":[{"text":"restarting"}," in 5"]}"""))
        assertEquals("너무 오래 가만히 있어서 추방됐어요", TextComponent.fromJson("""{"translate":"multiplayer.disconnect.idling"}"""))
        assertEquals("some.unknown.key", TextComponent.fromJson("""{"translate":"some.unknown.key"}"""))
        assertEquals("plain text, not JSON", TextComponent.fromJson("plain text, not JSON"))
    }

    @Test
    fun nbtStringTag() {
        val bytes = byteArrayOf(8, 0, 3) + "bye".toByteArray()
        assertEquals("bye", TextComponent.fromNbt(PacketReader(bytes)))
    }

    @Test
    fun nbtCompoundWithExtraList() {
        // {text:"Kicked: ", extra:[{text:"spam", bold:1b}]}
        val out = java.io.ByteArrayOutputStream()
        fun name(s: String) { out.write(0); out.write(s.length); out.write(s.toByteArray()) }
        fun str(s: String) { val b = s.toByteArray(); out.write(b.size ushr 8); out.write(b.size and 0xFF); out.write(b) }
        out.write(10)
        out.write(8); name("text"); str("Kicked: ")
        out.write(9); name("extra"); out.write(10); out.write(byteArrayOf(0, 0, 0, 1))
        out.write(8); name("text"); str("spam")
        out.write(1); name("bold"); out.write(1)
        out.write(0) // end of list item compound
        out.write(0) // end of root compound
        assertEquals("Kicked: spam", TextComponent.fromNbt(PacketReader(out.toByteArray())))
    }
}

class ServerPingTest {
    @Test
    fun readsVersionAndFavicon() {
        val v = ServerPing.parse("""{"version":{"name":"Paper 26.3","protocol":777},"favicon":"data:image/png;base64,iVBORw0K\nGgo="}""")
        assertEquals(777, v.protocol)
        assertEquals("Paper 26.3", v.name)
        assertEquals(8, v.favicon!!.size)
        org.junit.Assert.assertNull(ServerPing.parse("""{"version":{"name":"x","protocol":1}}""").favicon)
        org.junit.Assert.assertNull(ServerPing.parse("""{"version":{"name":"x","protocol":1},"favicon":"garbage"}""").favicon)
    }
}
