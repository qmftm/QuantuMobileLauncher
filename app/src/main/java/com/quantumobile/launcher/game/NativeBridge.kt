package com.quantumobile.launcher.game

import android.view.Surface

/** JNI surface of libqmlbridge.so (see app/src/main/cpp/qml_bridge.h). Only used in the :game process. */
object NativeBridge {
    init {
        System.loadLibrary("qmlbridge")
    }

    @JvmStatic external fun launchJvm(javaHome: String, args: Array<String>, env: Array<String>, workDir: String, logPath: String): Int
    @JvmStatic external fun setSurface(surface: Surface?, width: Int, height: Int)
    @JvmStatic external fun sendKey(key: Int, scancode: Int, action: Int, mods: Int)
    @JvmStatic external fun sendChar(codepoint: Int, mods: Int)
    @JvmStatic external fun sendCursorPos(x: Double, y: Double)
    @JvmStatic external fun sendMouseButton(button: Int, action: Int, mods: Int)
    @JvmStatic external fun sendScroll(dx: Double, dy: Double)
    @JvmStatic external fun isCursorGrabbed(): Boolean
    @JvmStatic external fun setClipboard(text: String?)

    fun tapKey(key: Int) {
        sendKey(key, 0, Glfw.PRESS, 0)
        sendKey(key, 0, Glfw.RELEASE, 0)
    }
}
