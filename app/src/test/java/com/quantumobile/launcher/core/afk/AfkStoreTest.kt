package com.quantumobile.launcher.core.afk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AfkStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, "afk.json")

    @Test
    fun serversAndSwitchesSurviveARestart() {
        val a = AfkServer(name = "Lobby", host = "play.example.com", accountId = "acc1")
        val b = AfkServer(name = "Farm", host = "10.0.0.5", port = 25566, accountId = "acc2")
        AfkStore(file()).apply {
            upsert(a)
            upsert(b)
            setEnabled(b.id, true)
        }

        val reloaded = AfkStore(file()).servers.value
        assertEquals(listOf(a, b.copy(enabled = true)), reloaded)
    }

    @Test
    fun editRemoveAndStopAll() {
        val store = AfkStore(file())
        val a = AfkServer(name = "A", host = "a.example", accountId = "x", enabled = true)
        val b = AfkServer(name = "B", host = "b.example", accountId = "x", enabled = true)
        store.upsert(a); store.upsert(b)

        store.upsert(a.copy(name = "A2", port = 1234))
        assertEquals("A2", store.get(a.id)!!.name)
        assertEquals(2, store.servers.value.size) // edited in place, not duplicated

        store.disableAll()
        assertTrue(AfkStore(file()).servers.value.none { it.enabled })

        store.remove(a.id)
        assertNull(AfkStore(file()).get(a.id))
        assertEquals(listOf("B"), AfkStore(file()).servers.value.map { it.name })
    }

    @Test
    fun brokenFileStartsEmpty() {
        file().writeText("{ not json")
        assertTrue(AfkStore(file()).servers.value.isEmpty())
    }

    @Test
    fun unknownFieldsFromNewerVersionsAreIgnored() {
        file().writeText("""{"servers":[{"id":"1","name":"N","host":"h","accountId":"a","enabled":true,"future":5}]}""")
        val s = AfkStore(file()).servers.value.single()
        assertEquals(25565, s.port)
        assertTrue(s.enabled)
    }

    @Test
    fun parsesAddresses() {
        assertEquals("play.example.com" to 25565, AfkServer.parseAddress(" play.example.com "))
        assertEquals("1.2.3.4" to 25566, AfkServer.parseAddress("1.2.3.4:25566"))
        assertEquals("host" to 30000, AfkServer.parseAddress("host", defaultPort = 30000))
        assertNull(AfkServer.parseAddress(""))
        assertNull(AfkServer.parseAddress("host:99999"))
        assertNull(AfkServer.parseAddress(":25565"))
        assertNull(AfkServer.parseAddress("a b"))
        assertFalse(AfkServer(name = "n", host = "h", port = 25570, accountId = "a").address == "h")
    }
}
