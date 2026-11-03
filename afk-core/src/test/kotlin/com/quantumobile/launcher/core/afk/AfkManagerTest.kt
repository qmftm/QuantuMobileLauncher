package com.quantumobile.launcher.core.afk

import com.quantumobile.launcher.core.auth.Account
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AfkManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val connected = mutableListOf<String>()
    private val cancelled = mutableListOf<String>()

    private fun TestScope.manager(store: AfkStore) = AfkManager(
        store, backgroundScope,
        connector = { host, _, _, events ->
            connected += host
            events.connected("1.21.4")
            try { awaitCancellation() } finally { cancelled += host }
        },
        account = { Account.offline("Steve") },
        network = MutableStateFlow(true),
    )

    @Test
    fun eachSwitchControlsItsOwnServer() = runTest {
        val store = AfkStore(File(tmp.root, "afk.json"))
        val a = AfkServer(name = "A", host = "a.example", accountId = "x")
        val b = AfkServer(name = "B", host = "b.example", accountId = "x")
        store.upsert(a); store.upsert(b)
        val afk = manager(store)
        afk.start()
        runCurrent()
        assertEquals(emptyList<String>(), connected)

        afk.setEnabled(a.id, true)
        afk.setEnabled(b.id, true)
        runCurrent()
        assertEquals(listOf("a.example", "b.example"), connected)
        assertEquals(AfkPhase.CONNECTED, afk.status(a.id).phase)
        assertEquals(AfkPhase.CONNECTED, afk.status(b.id).phase)

        afk.setEnabled(a.id, false)
        runCurrent()
        assertEquals(listOf("a.example"), cancelled)
        assertNull(afk.statuses.value[a.id])
        assertEquals(AfkPhase.OFF, afk.status(a.id).phase)
        assertEquals(AfkPhase.CONNECTED, afk.status(b.id).phase)

        // Changing the address of a running server reconnects it.
        afk.upsert(store.get(b.id)!!.copy(host = "b2.example"))
        runCurrent()
        assertEquals(listOf("a.example", "b.example"), cancelled)
        assertEquals("b2.example", connected.last())

        afk.stopAll()
        runCurrent()
        assertEquals(emptyMap<String, AfkStatus>(), afk.statuses.value)
    }

    @Test
    fun serversLeftOnStartAgainAfterARestart() = runTest {
        val file = File(tmp.root, "afk.json")
        AfkStore(file).upsert(AfkServer(name = "A", host = "a.example", accountId = "x", enabled = true))
        manager(AfkStore(file)).start() // as after an app or phone restart
        runCurrent()
        assertEquals(listOf("a.example"), connected)
    }
}
