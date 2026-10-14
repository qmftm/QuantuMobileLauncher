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

    @Test fun oldLayoutKeepsEditsButGainsMouseAndAlwaysVisiblePad() {
        val f = File(tmp.root, "controls.json")
        val old = ControlLayoutStore.defaults().filter { it.id != "mouse" }
            .map { if (it.id == "w") it.copy(label = "↑", showInMenus = false) else it.copy(showInMenus = false) }
        // A file written before layouts were versioned.
        f.writeText(com.quantumobile.launcher.core.LauncherJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ControlSpec.serializer()), old).let { """{"buttons":$it}""" })
        val loaded = ControlLayoutStore(f).load()
        assertEquals("↑", loaded.first { it.id == "w" }.label)
        for (id in listOf("w", "a", "s", "d", "attack", "use")) assertTrue(id, loaded.first { it.id == id }.showInMenus)
        assertTrue(loaded.any { it.id == "mouse" && it.kind == ControlKind.MOUSE_MODE })
        assertTrue(!loaded.first { it.id == "chat" }.showInMenus)
    }

    @Test fun clicksSitBesideSpaceAndShiftExists() {
        val d = ControlLayoutStore.defaults().associateBy { it.id }
        val attack = d.getValue("attack"); val jump = d.getValue("jump"); val use = d.getValue("use")
        // Same row, bottom-right anchored: left click, then Space, then right click, left to right (larger x = further left).
        assertTrue(attack.fromRight && jump.fromRight && use.fromRight)
        assertEquals(attack.y, jump.y, 0f); assertEquals(use.y, jump.y, 0f)
        assertTrue(attack.x > jump.x && jump.x > use.x)
        assertEquals(Glfw.KEY_SPACE, jump.code)
        assertEquals(Glfw.KEY_LEFT_SHIFT, d.getValue("sneak").code)
    }

    @Test fun layoutsBeforeVersion3TakeTheNewClickSpaceShiftPositions() {
        val f = File(tmp.root, "controls.json")
        val d = ControlLayoutStore.defaults()
        val old = d.map { if (it.id == "attack") it.copy(x = 999f, y = 999f, fromRight = false) else it }
        f.writeText("""{"buttons":${com.quantumobile.launcher.core.LauncherJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(ControlSpec.serializer()), old)},"version":2}""")
        val loaded = ControlLayoutStore(f).load().associateBy { it.id }
        assertEquals(d.first { it.id == "attack" }, loaded.getValue("attack"))
        assertEquals(d.first { it.id == "chat" }, loaded.getValue("chat"))
    }

    @Test fun fAndQSitBelowWasdAndOldLayoutsGainF() {
        val d = ControlLayoutStore.defaults().associateBy { it.id }
        val s = d.getValue("s"); val f = d.getValue("swap"); val q = d.getValue("drop"); val a = d.getValue("a")
        assertEquals(Glfw.KEY_F, f.code); assertEquals(Glfw.KEY_Q, q.code)
        // Bottom row: F left of S, Q right of S, all below the A/D row.
        assertEquals(s.y, f.y, 0f); assertEquals(s.y, q.y, 0f)
        assertTrue(f.x < s.x && s.x < q.x)
        assertTrue(a.y > s.y)
        val file = File(tmp.root, "v3.json")
        val v3 = ControlLayoutStore.defaults().filter { it.id != "swap" }
        file.writeText("""{"buttons":${com.quantumobile.launcher.core.LauncherJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(ControlSpec.serializer()), v3)},"version":3}""")
        val loaded = ControlLayoutStore(file).load()
        assertTrue(loaded.any { it.id == "swap" })
    }

    @Test fun topRowAndGearLayout() {
        val d = ControlLayoutStore.defaults().associateBy { it.id }
        val gear = d.getValue("hide")
        assertTrue(gear.centerX)
        // Gear is centred on the screen whatever its width.
        val r = gear.bounds(1000, 500, 1f)
        assertEquals(500f, (r[0] + r[2]) / 2, 0.01f)
        // movedTo keeps the centre anchor.
        assertTrue(gear.movedTo(100f, 20f, 1000, 500, 1f).centerX)
        // Ctrl sits where GUI used to (left of W); Mouse is the right-most top button; Esc and Keyboard are on the left.
        val w = d.getValue("w"); val ctrl = d.getValue("sprint")
        assertEquals(w.y, ctrl.y, 0f); assertTrue(ctrl.x < w.x && !ctrl.fromRight)
        val mouse = d.getValue("mouse")
        assertTrue(mouse.fromRight && d.values.filter { it.fromRight && it.y == mouse.y && !it.fromBottom }.all { it.x >= mouse.x })
        assertTrue(!d.getValue("esc").fromRight && !d.getValue("kbd").fromRight)
    }

    @Test fun shiftIsAtTheCentreOfWasdAndSpaceRowIsOneCellUp() {
        val d = ControlLayoutStore.defaults().associateBy { it.id }
        val shift = d.getValue("sneak"); val s = d.getValue("s"); val w = d.getValue("w"); val a = d.getValue("a"); val dd = d.getValue("d")
        // Shift: same column as W/S, same row as A/D.
        assertEquals(w.x, shift.x, 0f); assertEquals(s.x, shift.x, 0f)
        assertEquals(a.y, shift.y, 0f); assertEquals(dd.y, shift.y, 0f)
        assertTrue(shift.toggle)
        // Space and the two click buttons are one cell (button + margin) above the bottom row.
        val jump = d.getValue("jump")
        assertEquals(5f + 50f + 5f, jump.y, 0f)
        assertEquals(jump.y, d.getValue("attack").y, 0f); assertEquals(jump.y, d.getValue("use").y, 0f)
        // Simple drawn icons instead of text.
        assertEquals("@shift", shift.label); assertEquals("@space", jump.label)
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

    @Test fun defaultButtonsDoNotOverlapOnAPhoneScreen() {
        // 2640×1080 px at density 3 = 880×360 dp (a Flip-class phone in landscape); also a small 640×320 dp screen.
        for ((w, h) in listOf(880 to 360, 640 to 320)) {
            val rects = ControlLayoutStore.defaults().map { it.id to it.bounds(w, h, 1f) } // l, t, r, b
            for (i in rects.indices) for (j in i + 1 until rects.size) {
                val a = rects[i].second; val b = rects[j].second
                val overlap = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]
                assertTrue("${rects[i].first} overlaps ${rects[j].first} on ${w}x$h", !overlap)
            }
        }
    }

    @Test fun oneButtonCanPressSeveralKeys() {
        val b = ControlSpec("combo", "C", ControlKind.KEY, Glfw.KEY_LEFT_CONTROL, extraKeys = listOf(Glfw.KEY_LEFT_SHIFT, 88), x = 0f, y = 0f)
        assertEquals(listOf(341, 340, 88), b.allCodes)
        assertEquals(listOf(Glfw.KEY_W), ControlLayoutStore.defaults().first { it.id == "w" }.allCodes)
    }

    @Test fun wideButtonsKeepTheirAspectAndOldLayoutsStillLoad() {
        val wide = ControlSpec("t", "T", ControlKind.KEY, 1, x = 0f, y = 0f, size = 36f, aspect = 2.2f)
        val r = wide.bounds(1000, 500, 1f)
        assertEquals(36f * 2.2f - 6f, r[2] - r[0], 0.01f)
        assertEquals(36f - 6f, r[3] - r[1], 0.01f)
        val f = File(tmp.root, "old.json").apply {
            writeText("""{"buttons":[{"id":"w","label":"W","kind":"KEY","code":87,"x":10.0,"y":20.0}]}""")
        }
        val loaded = ControlLayoutStore(f).load().first { it.id == "w" }
        assertEquals(1f, loaded.aspect, 0f)
        assertEquals(emptyList<Int>(), loaded.extraKeys)
    }
}
