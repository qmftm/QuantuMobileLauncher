package com.quantumobile.launcher.game

import org.lwjgl.glfw.CallbackBridge

/** Input/log facade over the Pojav native layer (see org.lwjgl.glfw.CallbackBridge) and libqmlnative. Used in `:game`. */
object NativeBridge {
    init {
        System.loadLibrary("qmlnative")
        CallbackBridge.ensureLoaded()
    }

    @JvmStatic external fun redirectOutput(path: String): Boolean

    fun sendKey(key: Int, scancode: Int, action: Int, mods: Int) = CallbackBridge.sendKey(key, scancode, action, mods)
    fun sendChar(codepoint: Int, mods: Int) = CallbackBridge.sendChar(codepoint, mods)
    fun sendCursorPos(x: Double, y: Double) = CallbackBridge.sendCursorPos(x.toFloat(), y.toFloat())
    fun sendMouseButton(button: Int, action: Int, mods: Int) = CallbackBridge.sendMouseButton(button, action, mods)
    fun sendScroll(dx: Double, dy: Double) = CallbackBridge.sendScroll(dx, dy)
    fun isCursorGrabbed(): Boolean = CallbackBridge.isGrabbing

    fun tapKey(key: Int) {
        sendKey(key, 0, Glfw.PRESS, 0)
        sendKey(key, 0, Glfw.RELEASE, 0)
    }
}
