package com.quantumobile.launcher.game

import android.graphics.RectF
import com.quantumobile.launcher.core.LauncherJson
import java.io.File
import kotlinx.serialization.Serializable

@Serializable
enum class ControlKind { KEY, MOUSE, SCROLL_UP, SCROLL_DOWN, KEYBOARD }

/**
 * One on-screen button. [x]/[y] are dp from the screen edge the button is anchored to ([fromRight]/[fromBottom]),
 * so a layout keeps its shape on different screen sizes.
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
) {
    /** Fills [out] with the button's rectangle in pixels for a [w]×[h] screen. */
    fun rect(w: Int, h: Int, density: Float, out: RectF): RectF {
        val s = size * density
        val left = if (fromRight) w - x * density - s else x * density
        val top = if (fromBottom) h - y * density - s else y * density
        val inset = 3 * density
        return out.apply { set(left + inset, top + inset, left + s - inset, top + s - inset) }
    }

    /** Moves the button so its top-left corner is at ([left], [top]) pixels, keeping it fully on screen. */
    fun movedTo(left: Float, top: Float, w: Int, h: Int, density: Float): ControlSpec {
        val s = size * density
        val l = left.coerceIn(0f, (w - s).coerceAtLeast(0f))
        val t = top.coerceIn(0f, (h - s).coerceAtLeast(0f))
        return copy(
            x = (if (fromRight) w - l - s else l) / density,
            y = (if (fromBottom) h - t - s else t) / density,
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

        private const val M = 16f // margin
        private const val U = 56f // grid unit

        fun defaults(): List<ControlSpec> = listOf(
            // Movement pad (bottom-left)
            key("w", "W", Glfw.KEY_W, M + U, M + 2 * U, bottom = true),
            key("a", "A", Glfw.KEY_A, M, M + U, bottom = true),
            key("s", "S", Glfw.KEY_S, M + U, M, bottom = true),
            key("d", "D", Glfw.KEY_D, M + 2 * U, M + U, bottom = true),
            key("sneak", "⇩", Glfw.KEY_LEFT_SHIFT, M + U, M + U, bottom = true, toggle = true),
            // Actions (bottom-right)
            key("jump", "⤒", Glfw.KEY_SPACE, M, M, right = true, bottom = true, size = U * 1.3f),
            ControlSpec("attack", "⚔", ControlKind.MOUSE, Glfw.MOUSE_LEFT, fromRight = true, fromBottom = true, x = M, y = M + 1.4f * U),
            ControlSpec("use", "✋", ControlKind.MOUSE, Glfw.MOUSE_RIGHT, fromRight = true, fromBottom = true, x = M + 1.2f * U, y = M),
            key("sprint", "Run", Glfw.KEY_LEFT_CONTROL, M + 1.2f * U, M + 1.4f * U, right = true, bottom = true, toggle = true),
            // Top row
            key("esc", "Esc", Glfw.KEY_ESCAPE, M, M, menus = true),
            ControlSpec("kbd", "⌨", ControlKind.KEYBOARD, showInMenus = true, x = M + U, y = M),
            key("chat", "T", Glfw.KEY_T, M + 2 * U, M),
            key("inv", "E", Glfw.KEY_E, M + 3 * U, M, menus = true),
            key("drop", "Q", Glfw.KEY_Q, M + 4 * U, M),
            key("view", "F5", Glfw.KEY_F5, M, M, right = true),
            key("debug", "F3", Glfw.KEY_F3, M + U, M, right = true),
            ControlSpec("hot_prev", "◀", ControlKind.SCROLL_UP, fromRight = true, x = M + 3 * U, y = M),
            ControlSpec("hot_next", "▶", ControlKind.SCROLL_DOWN, fromRight = true, x = M + 2 * U, y = M),
        )

        private fun key(
            id: String, label: String, code: Int, x: Float, y: Float,
            right: Boolean = false, bottom: Boolean = false, toggle: Boolean = false, menus: Boolean = false, size: Float = U,
        ) = ControlSpec(id, label, ControlKind.KEY, code, toggle, menus, right, bottom, x, y, size)
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
