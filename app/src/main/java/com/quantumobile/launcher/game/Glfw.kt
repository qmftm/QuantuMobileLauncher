package com.quantumobile.launcher.game

import android.view.KeyEvent

/** GLFW constants and Android → GLFW key translation. */
object Glfw {
    const val RELEASE = 0
    const val PRESS = 1
    const val REPEAT = 2

    const val MOD_SHIFT = 0x1
    const val MOD_CONTROL = 0x2
    const val MOD_ALT = 0x4

    const val MOUSE_LEFT = 0
    const val MOUSE_RIGHT = 1
    const val MOUSE_MIDDLE = 2

    const val KEY_SPACE = 32
    const val KEY_E = 69
    const val KEY_F = 70
    const val KEY_Q = 81
    const val KEY_T = 84
    const val KEY_W = 87
    const val KEY_A = 65
    const val KEY_S = 83
    const val KEY_D = 68
    const val KEY_ESCAPE = 256
    const val KEY_ENTER = 257
    const val KEY_TAB = 258
    const val KEY_BACKSPACE = 259
    const val KEY_F2 = 291
    const val KEY_F3 = 292
    const val KEY_F5 = 294
    const val KEY_LEFT_SHIFT = 340
    const val KEY_LEFT_CONTROL = 341

    private val map: Map<Int, Int> = buildMap {
        for (i in 0 until 26) put(KeyEvent.KEYCODE_A + i, 65 + i)
        for (i in 0 until 10) put(KeyEvent.KEYCODE_0 + i, 48 + i)
        for (i in 0 until 12) put(KeyEvent.KEYCODE_F1 + i, 290 + i)
        for (i in 0 until 10) put(KeyEvent.KEYCODE_NUMPAD_0 + i, 320 + i)
        put(KeyEvent.KEYCODE_SPACE, KEY_SPACE)
        put(KeyEvent.KEYCODE_APOSTROPHE, 39)
        put(KeyEvent.KEYCODE_COMMA, 44)
        put(KeyEvent.KEYCODE_MINUS, 45)
        put(KeyEvent.KEYCODE_PERIOD, 46)
        put(KeyEvent.KEYCODE_SLASH, 47)
        put(KeyEvent.KEYCODE_SEMICOLON, 59)
        put(KeyEvent.KEYCODE_EQUALS, 61)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 91)
        put(KeyEvent.KEYCODE_BACKSLASH, 92)
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, 93)
        put(KeyEvent.KEYCODE_GRAVE, 96)
        put(KeyEvent.KEYCODE_ESCAPE, KEY_ESCAPE)
        put(KeyEvent.KEYCODE_ENTER, KEY_ENTER)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, 335)
        put(KeyEvent.KEYCODE_TAB, KEY_TAB)
        put(KeyEvent.KEYCODE_DEL, KEY_BACKSPACE)
        put(KeyEvent.KEYCODE_INSERT, 260)
        put(KeyEvent.KEYCODE_FORWARD_DEL, 261)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, 262)
        put(KeyEvent.KEYCODE_DPAD_LEFT, 263)
        put(KeyEvent.KEYCODE_DPAD_DOWN, 264)
        put(KeyEvent.KEYCODE_DPAD_UP, 265)
        put(KeyEvent.KEYCODE_PAGE_UP, 266)
        put(KeyEvent.KEYCODE_PAGE_DOWN, 267)
        put(KeyEvent.KEYCODE_MOVE_HOME, 268)
        put(KeyEvent.KEYCODE_MOVE_END, 269)
        put(KeyEvent.KEYCODE_CAPS_LOCK, 280)
        put(KeyEvent.KEYCODE_SCROLL_LOCK, 281)
        put(KeyEvent.KEYCODE_NUM_LOCK, 282)
        put(KeyEvent.KEYCODE_SYSRQ, 283)
        put(KeyEvent.KEYCODE_BREAK, 284)
        put(KeyEvent.KEYCODE_SHIFT_LEFT, KEY_LEFT_SHIFT)
        put(KeyEvent.KEYCODE_CTRL_LEFT, KEY_LEFT_CONTROL)
        put(KeyEvent.KEYCODE_ALT_LEFT, 342)
        put(KeyEvent.KEYCODE_META_LEFT, 343)
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, 344)
        put(KeyEvent.KEYCODE_CTRL_RIGHT, 345)
        put(KeyEvent.KEYCODE_ALT_RIGHT, 346)
        put(KeyEvent.KEYCODE_META_RIGHT, 347)
        put(KeyEvent.KEYCODE_MENU, 348)
    }

    fun fromAndroid(keyCode: Int): Int? = map[keyCode]

    private val toAndroid: Map<Int, Int> by lazy { map.entries.associate { (android, glfw) -> glfw to android } }

    /** GLFW key code → Android key code (the SDL backend takes Android key events). */
    fun toAndroid(glfwKey: Int): Int? = toAndroid[glfwKey]

    fun mods(event: KeyEvent): Int {
        var m = 0
        if (event.isShiftPressed) m = m or MOD_SHIFT
        if (event.isCtrlPressed) m = m or MOD_CONTROL
        if (event.isAltPressed) m = m or MOD_ALT
        return m
    }
}
