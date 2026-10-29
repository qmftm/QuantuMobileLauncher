package com.quantumobile.launcher.game

import android.graphics.RectF
import com.quantumobile.launcher.core.LauncherJson
import java.io.File
import kotlinx.serialization.Serializable

@Serializable
enum class ControlKind {
    KEY, MOUSE, SCROLL_UP, SCROLL_DOWN, KEYBOARD,

    /** Shows/hides all other on-screen buttons (PojavLauncher's "GUI" button). */
    HIDE,

    /** Mouse on/off: in menus, drags move a visible cursor and a tap clicks it (instead of jumping to the touch point). */
    MOUSE_MODE,
}

/**
 * One on-screen button. [x]/[y] are dp from the screen edge the button is anchored to ([fromRight]/[fromBottom]),
 * so a layout keeps its shape on different screen sizes. As in PojavLauncher's control maps, one button can press several keys
 * together ([code] plus [extraKeys], e.g. Ctrl+Shift) and some buttons are wider than tall ([aspect] = width / height).
 */
@Serializable
data class ControlSpec(
    val id: String,
    val label: String,
    val kind: ControlKind,
    val code: Int = 0,
    val toggle: Boolean = false,
    val showInMenus: Boolean = false,
    val fromRight: Boolean = false,
    val fromBottom: Boolean = false,
    val x: Float,
    val y: Float,
    val size: Float = 56f,
    val extraKeys: List<Int> = emptyList(),
    val aspect: Float = 1f,
    /** Anchored to the horizontal centre of the screen: [x] is then an offset from the centre (dp), not from an edge. */
    val centerX: Boolean = false,
    /** While held, the button keeps firing (after the first press) like a held key on a keyboard: Q drops item after item. */
    val repeat: Boolean = false,
) {
    /** Every key (or mouse button) this button presses. */
    val allCodes: List<Int> get() = listOf(code) + extraKeys

    /** The button's rectangle in pixels for a [w]×[h] screen, as left, top, right, bottom. */
    fun bounds(w: Int, h: Int, density: Float): FloatArray {
        val sh = size * density
        val sw = sh * aspect
        val left = if (centerX) (w - sw) / 2 + x * density else if (fromRight) w - x * density - sw else x * density
        val top = if (fromBottom) h - y * density - sh else y * density
        val inset = 3 * density
        return floatArrayOf(left + inset, top + inset, left + sw - inset, top + sh - inset)
    }

    /** Fills [out] with [bounds]. */
    fun rect(w: Int, h: Int, density: Float, out: RectF): RectF =
        bounds(w, h, density).let { out.apply { set(it[0], it[1], it[2], it[3]) } }

    /** Moves the button so its top-left corner is at ([left], [top]) pixels, keeping it fully on screen. */
    fun movedTo(left: Float, top: Float, w: Int, h: Int, density: Float): ControlSpec {
        val sh = size * density
        val sw = sh * aspect
        val l = left.coerceIn(0f, (w - sw).coerceAtLeast(0f))
        val t = top.coerceIn(0f, (h - sh).coerceAtLeast(0f))
        return copy(
            x = (if (centerX) l - (w - sw) / 2 else if (fromRight) w - l - sw else l) / density,
            y = (if (fromBottom) h - t - sh else t) / density,
        )
    }
}

@Serializable
private data class LayoutFile(val buttons: List<ControlSpec> = emptyList(), val version: Int = 1)

/** The user's button layout, saved as `controls.json`; falls back to [defaults] when missing or unreadable. */
class ControlLayoutStore(private val file: File) {
    fun load(): List<ControlSpec> {
        val file = runCatching { LauncherJson.decodeFromString<LayoutFile>(file.readText()) }.getOrNull()
            ?.takeIf { it.buttons.isNotEmpty() } ?: return defaults()
        return if (file.version >= VERSION) file.buttons else migrate(file.buttons, file.version)
    }

    /** Layouts saved by older versions: keep the user's edits, apply what the newer defaults changed. */
    private fun migrate(old: List<ControlSpec>, from: Int): List<ControlSpec> {
        val defaults = defaults()
        var list = old
        if (from < 2) {
            // 2: the movement/click pad shows outside the game too, and the Mouse button appears.
            val alwaysShown = setOf("w", "a", "s", "d", "attack", "use")
            list = list.map { if (it.id in alwaysShown) it.copy(showInMenus = true) else it }
            list = list + defaults.filter { d -> d.id == "mouse" && list.none { it.id == d.id } }
        }
        if (from < 3) {
            // 3: left click / Space / right click in one row, Shift, Ctrl and Q above them: take the new positions.
            val moved = setOf("attack", "use", "jump", "sneak", "sprint", "drop")
            list = list.map { b -> if (b.id in moved) defaults.firstOrNull { it.id == b.id } ?: b else b }
        }
        if (from < 4) {
            // 4: F and Q flank S on the bottom row, GUI and Inventory flank W.
            val moved = setOf("hide", "inv", "drop")
            list = list.map { b -> if (b.id in moved) defaults.firstOrNull { it.id == b.id } ?: b else b }
            list = list + defaults.filter { d -> d.id == "swap" && list.none { it.id == d.id } }
        }
        if (from < 5) {
            // 5: top row rearranged (Esc/Keyboard left, GUI gear in the centre, Mouse at the right end), Ctrl takes GUI's old place.
            val moved = setOf("esc", "kbd", "debug", "chat", "tab", "view", "hide", "mouse", "hot_next", "hot_prev", "sprint")
            list = list.map { b -> if (b.id in moved) defaults.firstOrNull { it.id == b.id } ?: b else b }
        }
        if (from < 6) {
            // 6: Shift in the middle of WASD, Space and the click row one cell up, Shift/Space drawn as simple icons.
            val moved = setOf("sneak", "jump", "attack", "use")
            list = list.map { b -> if (b.id in moved) defaults.firstOrNull { it.id == b.id } ?: b else b }
        }
        if (from < 7) {
            // 7: tighter gaps between buttons: every default button takes its new position (labels and keys are kept).
            list = list.map { b ->
                val d = defaults.firstOrNull { it.id == b.id } ?: return@map b
                b.copy(fromRight = d.fromRight, fromBottom = d.fromBottom, centerX = d.centerX, x = d.x, y = d.y)
            }
        }
        if (from < 8) {
            // 8: Mouse is a small pointer icon; the hotbar arrows next to it shift over.
            list = list.map { b -> if (b.id in setOf("mouse", "hot_next", "hot_prev")) defaults.firstOrNull { it.id == b.id } ?: b else b }
        }
        if (from < 9) {
            // 9: the GUI button is an eye (closed while the buttons are hidden) and sits higher.
            list = list.map { b -> if (b.id == "hide") defaults.firstOrNull { it.id == b.id } ?: b else b }
        }
        if (from < 10) {
            // 10: gaps tightened again: every default button takes its new position (labels and keys are kept).
            list = list.map { b ->
                val d = defaults.firstOrNull { it.id == b.id } ?: return@map b
                b.copy(fromRight = d.fromRight, fromBottom = d.fromBottom, centerX = d.centerX, x = d.x, y = d.y)
            }
        }
        if (from < 11) {
            // 11: Mouse is a small labelled button again (the on-screen cursor became a PC arrow); hotbar arrows follow it.
            list = list.map { b -> if (b.id in setOf("mouse", "hot_next", "hot_prev")) defaults.firstOrNull { it.id == b.id } ?: b else b }
        }
        if (from < 12) {
            // 12: wider top-row buttons and smaller gaps: default buttons take the new positions and widths.
            list = list.map { b ->
                val d = defaults.firstOrNull { it.id == b.id } ?: return@map b
                b.copy(fromRight = d.fromRight, fromBottom = d.fromBottom, centerX = d.centerX, x = d.x, y = d.y, aspect = d.aspect)
            }
        }
        if (from < 13) {
            // 13: the Mouse button is half its former size; the hotbar arrows next to it follow.
            list = list.map { b ->
                val d = defaults.firstOrNull { it.id == b.id }?.takeIf { it.id in setOf("mouse", "hot_next", "hot_prev") } ?: return@map b
                b.copy(x = d.x, y = d.y, size = d.size, aspect = d.aspect)
            }
        }
        if (from < 14) {
            // 14: Mouse button back to its earlier size (it was the on-screen pointer that should shrink); gaps halved.
            list = list.map { b ->
                val d = defaults.firstOrNull { it.id == b.id } ?: return@map b
                val resized = b.id in setOf("mouse", "hot_next", "hot_prev")
                b.copy(
                    fromRight = d.fromRight, fromBottom = d.fromBottom, centerX = d.centerX, x = d.x, y = d.y,
                    size = if (resized) d.size else b.size, aspect = if (resized) d.aspect else b.aspect,
                )
            }
        }
        if (from < 15) {
            // 15: every top-row button is the same height (Mouse, gear, hotbar arrows included) and the click / jump buttons are one size.
            val resized = setOf("hide", "mouse", "hot_next", "hot_prev", "attack", "use", "jump")
            list = list.map { b ->
                val d = defaults.firstOrNull { it.id == b.id } ?: return@map b
                b.copy(
                    fromRight = d.fromRight, fromBottom = d.fromBottom, centerX = d.centerX, x = d.x, y = d.y,
                    size = if (b.id in resized) d.size else b.size, aspect = if (b.id in resized) d.aspect else b.aspect,
                )
            }
        }
        if (from < 16) {
            // 16: Q, F and the hotbar arrows keep firing while held.
            val repeating = setOf("swap", "drop", "hot_next", "hot_prev")
            list = list.map { b -> if (b.id in repeating) b.copy(repeat = true) else b }
        }
        return list
    }

    fun save(buttons: List<ControlSpec>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(LauncherJson.encodeToString(LayoutFile.serializer(), LayoutFile(buttons, VERSION)))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun reset() { file.delete() }

    companion object {
        fun forContext(context: android.content.Context) = ControlLayoutStore(File(context.filesDir, "controls.json"))

        private const val VERSION = 16

        private const val M = 1f // margin / gap between buttons
        private const val U = 50f // square button
        private const val TH = 36f // height of the wide top-row buttons
        private const val TA = 2.0f // their width / height

        /**
         * Arranged like PojavLauncher's default control map: Debug / Chat / Keyboard / Tab on the top row with the
         * third-person key under Debug, a 3×3 pad bottom-left (left click, W, right click / A, Shift, D / GUI, S, Inventory)
         * and Space bottom-right. Extras kept from this launcher: Esc, hotbar scroll, sprint, drop.
         */
        fun defaults(): List<ControlSpec> = listOf(
            // Top row (left): Esc, Keyboard, F3, Chat; Tab and 3rd person below them
            key("esc", "Esc", Glfw.KEY_ESCAPE, M, M, menus = true, size = TH, aspect = TA),
            ControlSpec("kbd", "Key", ControlKind.KEYBOARD, showInMenus = true, x = 2 * M + TH * TA, y = M, size = TH, aspect = TA),
            key("debug", "F3", Glfw.KEY_F3, 3 * M + 2 * TH * TA, M, size = TH, aspect = TA),
            key("chat", "Chat", Glfw.KEY_T, 4 * M + 3 * TH * TA, M, size = TH, aspect = TA),
            key("tab", "Tab", Glfw.KEY_TAB, M, 2 * M + TH, size = TH, aspect = TA),
            key("view", "3rd", Glfw.KEY_F5, 2 * M + TH * TA, 2 * M + TH, size = TH, aspect = TA),
            // Top centre: the small GUI (hide/show all buttons) eye
            ControlSpec("hide", "@eye", ControlKind.HIDE, showInMenus = true, centerX = true, x = 0f, y = M, size = TH),
            // Top right: Mouse on/off at the far end, hotbar scroll next to it
            ControlSpec("mouse", "Mouse", ControlKind.MOUSE_MODE, showInMenus = true, fromRight = true, x = M, y = M, size = TH, aspect = TA),
            ControlSpec("hot_next", "▶", ControlKind.SCROLL_DOWN, fromRight = true, x = 2 * M + TH * TA, y = M, size = TH, repeat = true),
            ControlSpec("hot_prev", "◀", ControlKind.SCROLL_UP, fromRight = true, x = 3 * M + TH * TA + TH, y = M, size = TH, repeat = true),
            // WASD cross (bottom-left) with F and Q flanking S on the bottom row; Ctrl and Inventory flank W
            key("sprint", "Ctrl", Glfw.KEY_LEFT_CONTROL, M, 3 * M + 2 * U, bottom = true, toggle = true),
            key("w", "▲", Glfw.KEY_W, 2 * M + U, 3 * M + 2 * U, bottom = true, menus = true),
            key("inv", "Inv", Glfw.KEY_E, 3 * M + 2 * U, 3 * M + 2 * U, bottom = true, menus = true),
            key("a", "◀", Glfw.KEY_A, M, 2 * M + U, bottom = true, menus = true),
            key("d", "▶", Glfw.KEY_D, 3 * M + 2 * U, 2 * M + U, bottom = true, menus = true),
            key("swap", "F", Glfw.KEY_F, M, M, bottom = true, menus = true, repeat = true),
            key("sneak", "@shift", Glfw.KEY_LEFT_SHIFT, 2 * M + U, 2 * M + U, bottom = true, toggle = true, menus = true),
            key("s", "▼", Glfw.KEY_S, 2 * M + U, M, bottom = true, menus = true),
            key("drop", "Q", Glfw.KEY_Q, 3 * M + 2 * U, M, bottom = true, repeat = true),
            // Right side, one cell up from the bottom: left click, Space, right click in one row (Space in the middle)
            ControlSpec("attack", "PRI", ControlKind.MOUSE, Glfw.MOUSE_LEFT, showInMenus = true, fromRight = true, fromBottom = true, x = 3 * M + 2 * U, y = 2 * M + U, size = U),
            key("jump", "@space", Glfw.KEY_SPACE, 2 * M + U, 2 * M + U, right = true, bottom = true, menus = true),
            ControlSpec("use", "SEC", ControlKind.MOUSE, Glfw.MOUSE_RIGHT, showInMenus = true, fromRight = true, fromBottom = true, x = M, y = 2 * M + U, size = U),
        )

        private fun key(
            id: String, label: String, code: Int, x: Float, y: Float,
            right: Boolean = false, bottom: Boolean = false, toggle: Boolean = false, menus: Boolean = false, size: Float = U,
            aspect: Float = 1f, repeat: Boolean = false,
        ) = ControlSpec(id, label, ControlKind.KEY, code, toggle, menus, right, bottom, x, y, size, aspect = aspect, repeat = repeat)
    }
}

/** What a button can be bound to, for the editor's picker. */
data class KeyChoice(val name: String, val kind: ControlKind, val code: Int)

object KeyCatalog {
    val choices: List<KeyChoice> = buildList {
        add(KeyChoice("Left click", ControlKind.MOUSE, Glfw.MOUSE_LEFT))
        add(KeyChoice("Right click", ControlKind.MOUSE, Glfw.MOUSE_RIGHT))
        add(KeyChoice("Middle click", ControlKind.MOUSE, Glfw.MOUSE_MIDDLE))
        add(KeyChoice("Hotbar previous (scroll)", ControlKind.SCROLL_UP, 0))
        add(KeyChoice("Hotbar next (scroll)", ControlKind.SCROLL_DOWN, 0))
        add(KeyChoice("On-screen keyboard", ControlKind.KEYBOARD, 0))
        add(KeyChoice("Hide / show controls (GUI)", ControlKind.HIDE, 0))
        add(KeyChoice("Mouse on/off (menu cursor)", ControlKind.MOUSE_MODE, 0))
        for (c in 'A'..'Z') add(KeyChoice(c.toString(), ControlKind.KEY, c.code))
        for (d in 0..9) add(KeyChoice(d.toString(), ControlKind.KEY, 48 + d))
        for (f in 1..12) add(KeyChoice("F$f", ControlKind.KEY, 290 + f))
        add(KeyChoice("Space", ControlKind.KEY, Glfw.KEY_SPACE))
        add(KeyChoice("Esc", ControlKind.KEY, Glfw.KEY_ESCAPE))
        add(KeyChoice("Enter", ControlKind.KEY, Glfw.KEY_ENTER))
        add(KeyChoice("Tab", ControlKind.KEY, Glfw.KEY_TAB))
        add(KeyChoice("Backspace", ControlKind.KEY, Glfw.KEY_BACKSPACE))
        add(KeyChoice("Shift", ControlKind.KEY, Glfw.KEY_LEFT_SHIFT))
        add(KeyChoice("Ctrl", ControlKind.KEY, Glfw.KEY_LEFT_CONTROL))
        add(KeyChoice("Alt", ControlKind.KEY, 342))
        add(KeyChoice("↑", ControlKind.KEY, 265))
        add(KeyChoice("↓", ControlKind.KEY, 264))
        add(KeyChoice("←", ControlKind.KEY, 263))
        add(KeyChoice("→", ControlKind.KEY, 262))
    }

    fun find(kind: ControlKind, code: Int) =
        choices.firstOrNull { it.kind == kind && (it.code == code || kind != ControlKind.KEY && kind != ControlKind.MOUSE) }
}
