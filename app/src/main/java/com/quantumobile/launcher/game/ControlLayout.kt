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
) {
    /** Every key (or mouse button) this button presses. */
    val allCodes: List<Int> get() = listOf(code) + extraKeys

    /** The button's rectangle in pixels for a [w]×[h] screen, as left, top, right, bottom. */
    fun bounds(w: Int, h: Int, density: Float): FloatArray {
        val sh = size * density
        val sw = sh * aspect
        val left = if (fromRight) w - x * density - sw else x * density
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
            x = (if (fromRight) w - l - sw else l) / density,
            y = (if (fromBottom) h - t - sh else t) / density,
        )
    }
}

@Serializable
private data class LayoutFile(val buttons: List<ControlSpec> = emptyList())

/** The user's button layout, saved as `controls.json`; falls back to [defaults] when missing or unreadable. */
class ControlLayoutStore(private val file: File) {
    fun load(): List<ControlSpec> =
        runCatching { LauncherJson.decodeFromString<LayoutFile>(file.readText()).buttons }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: defaults()

    fun save(buttons: List<ControlSpec>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(LauncherJson.encodeToString(LayoutFile.serializer(), LayoutFile(buttons)))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun reset() { file.delete() }

    companion object {
        fun forContext(context: android.content.Context) = ControlLayoutStore(File(context.filesDir, "controls.json"))

        private const val M = 10f // margin, as in PojavLauncher's default map
        private const val U = 50f // square button
        private const val TH = 36f // height of the wide top-row buttons
        private const val TA = 2.2f // their width / height

        /**
         * Arranged like PojavLauncher's default control map: Debug / Chat / Keyboard / Tab on the top row with the
         * third-person key under Debug, a 3×3 pad bottom-left (left click, W, right click / A, Shift, D / GUI, S, Inventory)
         * and Space bottom-right. Extras kept from this launcher: Esc, hotbar scroll, sprint, drop.
         */
        fun defaults(): List<ControlSpec> = listOf(
            // Top row (left)
            key("debug", "Debug", Glfw.KEY_F3, M, M, size = TH, aspect = TA),
            key("chat", "Chat", Glfw.KEY_T, 2 * M + TH * TA, M, size = TH, aspect = TA),
            ControlSpec("kbd", "Keyboard", ControlKind.KEYBOARD, showInMenus = true, x = 3 * M + 2 * TH * TA, y = M, size = TH, aspect = TA),
            key("tab", "Tab", Glfw.KEY_TAB, 4 * M + 3 * TH * TA, M, size = TH, aspect = TA),
            key("view", "3rd", Glfw.KEY_F5, M, 2 * M + TH, size = TH, aspect = TA),
            // Top right
            key("esc", "Esc", Glfw.KEY_ESCAPE, M, M, right = true, menus = true, size = TH, aspect = TA),
            ControlSpec("hot_prev", "◀", ControlKind.SCROLL_UP, fromRight = true, x = M + TH * TA + M + U, y = M, size = TH),
            ControlSpec("hot_next", "▶", ControlKind.SCROLL_DOWN, fromRight = true, x = M + TH * TA + M, y = M, size = TH),
            // 3×3 pad (bottom-left): rows from the bottom
            ControlSpec("hide", "GUI", ControlKind.HIDE, showInMenus = true, fromBottom = true, x = M, y = M, size = U),
            key("s", "▼", Glfw.KEY_S, 2 * M + U, M, bottom = true),
            key("inv", "Inv", Glfw.KEY_E, 3 * M + 2 * U, M, bottom = true, menus = true),
            key("a", "◀", Glfw.KEY_A, M, 2 * M + U, bottom = true),
            key("sneak", "◇", Glfw.KEY_LEFT_SHIFT, 2 * M + U, 2 * M + U, bottom = true, toggle = true),
            key("d", "▶", Glfw.KEY_D, 3 * M + 2 * U, 2 * M + U, bottom = true),
            ControlSpec("attack", "PRI", ControlKind.MOUSE, Glfw.MOUSE_LEFT, fromBottom = true, x = M, y = 3 * M + 2 * U),
            key("w", "▲", Glfw.KEY_W, 2 * M + U, 3 * M + 2 * U, bottom = true),
            ControlSpec("use", "SEC", ControlKind.MOUSE, Glfw.MOUSE_RIGHT, fromBottom = true, x = 3 * M + 2 * U, y = 3 * M + 2 * U),
            // Bottom right
            key("jump", "⬛", Glfw.KEY_SPACE, 2 * M + U, 2 * M, right = true, bottom = true),
            key("sprint", "Run", Glfw.KEY_LEFT_CONTROL, M, 2 * M + U, right = true, bottom = true, toggle = true),
            key("drop", "Q", Glfw.KEY_Q, M, M, right = true, bottom = true),
        )

        private fun key(
            id: String, label: String, code: Int, x: Float, y: Float,
            right: Boolean = false, bottom: Boolean = false, toggle: Boolean = false, menus: Boolean = false, size: Float = U,
            aspect: Float = 1f,
        ) = ControlSpec(id, label, ControlKind.KEY, code, toggle, menus, right, bottom, x, y, size, aspect = aspect)
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
