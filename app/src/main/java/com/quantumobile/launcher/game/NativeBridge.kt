package com.quantumobile.launcher.game

import android.view.MotionEvent
import org.libsdl.app.QuantuSdlInput
import org.libsdl.app.SDLActivity
import org.lwjgl.glfw.CallbackBridge

/**
 * Input/log facade. For 1.21.x and older it feeds the Pojav native layer (org.lwjgl.glfw.CallbackBridge); for 26.x
 * ([sdl] = true) the same calls are translated into SDL's Android events. Used in `:game`.
 */
object NativeBridge {
    init {
        System.loadLibrary("qmlnative")
        CallbackBridge.ensureLoaded()
    }

    /** Set by [Sdl3Activity]: route input to SDL instead of the GLFW bridge. */
    @Volatile var sdl = false

    /** Size of the game window in pixels (SDL path: the surface; GLFW path: what the bridge was told). */
    @Volatile var sdlWidth = 0
    @Volatile var sdlHeight = 0
    fun windowWidth(): Int = if (sdl) sdlWidth else CallbackBridge.windowWidth
    fun windowHeight(): Int = if (sdl) sdlHeight else CallbackBridge.windowHeight

    @JvmStatic external fun redirectOutput(path: String): Boolean

    private var mouseState = 0 // Android button mask: 1 primary, 2 secondary, 4 tertiary
    private var lastX = 0f
    private var lastY = 0f
    private var wasRelative = false

    /** Drops held mouse buttons / relative-mode tracking (after the app returns from the background). */
    fun resetInputState() { mouseState = 0; wasRelative = false }

    fun sendKey(key: Int, scancode: Int, action: Int, mods: Int) {
        if (!sdl) return CallbackBridge.sendKey(key, scancode, action, mods)
        val code = Glfw.toAndroid(key) ?: return
        if (action == Glfw.RELEASE) SDLActivity.onNativeKeyUp(code) else SDLActivity.onNativeKeyDown(code)
    }

    fun sendChar(codepoint: Int, mods: Int) {
        if (!sdl) return CallbackBridge.sendChar(codepoint, mods)
        QuantuSdlInput.commitText(String(Character.toChars(codepoint)))
    }

    fun sendCursorPos(x: Double, y: Double) {
        if (!sdl) return CallbackBridge.sendCursorPos(x.toFloat(), y.toFloat())
        val fx = x.toFloat(); val fy = y.toFloat()
        val relative = QuantuSdlInput.isRelativeMouse()
        // Entering mouse-look: restart the delta baseline instead of jumping by the distance from the last menu position.
        if (relative && !wasRelative) { lastX = fx; lastY = fy }
        wasRelative = relative
        // Mouse-look wants deltas; menus want the absolute position.
        if (relative) SDLActivity.onNativeMouse(mouseState, MotionEvent.ACTION_MOVE, fx - lastX, fy - lastY, true)
        else SDLActivity.onNativeMouse(mouseState, MotionEvent.ACTION_MOVE, fx, fy, false)
        lastX = fx; lastY = fy
    }

    fun sendMouseButton(button: Int, action: Int, mods: Int) {
        if (!sdl) return CallbackBridge.sendMouseButton(button, action, mods)
        val bit = when (button) {
            Glfw.MOUSE_LEFT -> 1
            Glfw.MOUSE_RIGHT -> 2
            else -> 4
        }
        mouseState = if (action == Glfw.RELEASE) mouseState and bit.inv() else mouseState or bit
        val relative = QuantuSdlInput.isRelativeMouse()
        SDLActivity.onNativeMouse(
            mouseState,
            if (action == Glfw.RELEASE) MotionEvent.ACTION_UP else MotionEvent.ACTION_DOWN,
            if (relative) 0f else lastX, if (relative) 0f else lastY, relative,
        )
    }

    fun sendScroll(dx: Double, dy: Double) {
        if (!sdl) return CallbackBridge.sendScroll(dx, dy)
        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_SCROLL, dx.toFloat(), dy.toFloat(), false)
    }

    fun isCursorGrabbed(): Boolean = if (sdl) QuantuSdlInput.isRelativeMouse() else CallbackBridge.isGrabbing

    fun tapKey(key: Int) {
        sendKey(key, 0, Glfw.PRESS, 0)
        sendKey(key, 0, Glfw.RELEASE, 0)
    }
}
