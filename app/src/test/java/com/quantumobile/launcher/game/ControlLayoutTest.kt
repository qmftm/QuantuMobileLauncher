package com.quantumobile.launcher.game

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ControlLayoutTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun defaultsHaveUniqueIdsAndKnownKeys() {
        val d = ControlLayoutStore.defaults()
        assertEquals(d.size, d.map { it.id }.toSet().size)
        d.filter { it.kind == ControlKind.KEY || it.kind == ControlKind.MOUSE }.forEach {
            assertNotNull("${it.id} not in catalog", KeyCatalog.find(it.kind, it.code))
        }
    }

    @Test fun saveLoadResetRoundTrip() {
        val store = ControlLayoutStore(File(tmp.root, "controls.json"))
        assertEquals(ControlLayoutStore.defaults(), store.load())
        val edited = ControlLayoutStore.defaults().map { if (it.id == "w") it.copy(label = "↑", size = 80f) else it }
        store.save(edited)
        assertEquals(edited, store.load())
        store.reset()
        assertEquals(ControlLayoutStore.defaults(), store.load())
    }

    @Test fun corruptFileFallsBackToDefaults() {
        val f = File(tmp.root, "controls.json").apply { writeText("{ nope") }
        assertEquals(ControlLayoutStore.defaults(), ControlLayoutStore(f).load())
    }

    @Test fun movedToKeepsAnchorAndStaysOnScreen() {
        val b = ControlSpec("x", "X", ControlKind.KEY, 1, fromRight = true, fromBottom = true, x = 16f, y = 16f, size = 50f)
        val moved = b.movedTo(left = 10_000f, top = -50f, w = 1000, h = 500, density = 2f)
        assertTrue(moved.fromRight && moved.fromBottom)
        assertEquals(0f, moved.x, 0.001f)            // clamped to the right edge
        assertEquals((500f - 100f) / 2f, moved.y, 0.001f) // clamped to the top edge
    }
}
