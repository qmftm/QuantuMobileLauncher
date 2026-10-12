package com.quantumobile.launcher.game

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * The game's render target. Also acts as a text editor so the soft keyboard can type into chat/signs,
 * and handles physical mice (with pointer capture while the game grabs the cursor).
 */
class GameSurfaceView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : SurfaceView(context, attrs) {

    var framebufferScale = 1f
    var sensitivity = 1f
    private var mouseSeen = false
    private var cursorX = 0.0
    private var cursorY = 0.0

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                text.codePoints().forEach { NativeBridge.sendChar(it, 0) }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtLeast(1)) { NativeBridge.tapKey(Glfw.KEY_BACKSPACE) }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                NativeBridge.tapKey(Glfw.KEY_ENTER)
                return true
            }
        }
    }

    /** Hardware keyboard handling; returns false for keys that should fall through. */
    fun handleKey(event: KeyEvent): Boolean {
        val key = Glfw.fromAndroid(event.keyCode)
        val mods = Glfw.mods(event)
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount > 0) Glfw.REPEAT else Glfw.PRESS
            KeyEvent.ACTION_UP -> Glfw.RELEASE
            else -> return false
        }
        if (key != null) NativeBridge.sendKey(key, event.scanCode, action, mods)
        if (event.action == KeyEvent.ACTION_DOWN) {
            val ch = event.getUnicodeChar(event.metaState)
            if (ch > 0 && ch >= 0x20) NativeBridge.sendChar(ch, mods)
        }
        return key != null || event.unicodeChar > 0
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return super.onGenericMotionEvent(event)
        mouseSeen = true
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                cursorX = (event.x * framebufferScale).toDouble()
                cursorY = (event.y * framebufferScale).toDouble()
                NativeBridge.sendCursorPos(cursorX, cursorY)
            }
            MotionEvent.ACTION_SCROLL -> NativeBridge.sendScroll(
                event.getAxisValue(MotionEvent.AXIS_HSCROLL).toDouble(),
                event.getAxisValue(MotionEvent.AXIS_VSCROLL).toDouble(),
            )
            MotionEvent.ACTION_BUTTON_PRESS -> mouseButton(event.actionButton, Glfw.PRESS)
            MotionEvent.ACTION_BUTTON_RELEASE -> mouseButton(event.actionButton, Glfw.RELEASE)
            else -> return false
        }
        return true
    }

    override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                cursorX += event.x * sensitivity * framebufferScale
                cursorY += event.y * sensitivity * framebufferScale
                NativeBridge.sendCursorPos(cursorX, cursorY)
            }
            MotionEvent.ACTION_SCROLL -> NativeBridge.sendScroll(0.0, event.getAxisValue(MotionEvent.AXIS_VSCROLL).toDouble())
            MotionEvent.ACTION_BUTTON_PRESS -> mouseButton(event.actionButton, Glfw.PRESS)
            MotionEvent.ACTION_BUTTON_RELEASE -> mouseButton(event.actionButton, Glfw.RELEASE)
        }
        return true
    }

    /** Captures a connected mouse while the game wants a hidden cursor. */
    fun updatePointerCapture(grabbed: Boolean) {
        if (grabbed) centerCursor()
        if (!mouseSeen) return
        if (grabbed && !hasPointerCapture()) requestPointerCapture()
        else if (!grabbed && hasPointerCapture()) releasePointerCapture()
    }

    /** The game reads absolute positions, so start a grab from the window centre to avoid a camera jump. */
    private fun centerCursor() {
        cursorX = org.lwjgl.glfw.CallbackBridge.windowWidth / 2.0
        cursorY = org.lwjgl.glfw.CallbackBridge.windowHeight / 2.0
        NativeBridge.sendCursorPos(cursorX, cursorY)
    }

    private fun mouseButton(androidButton: Int, action: Int) {
        val button = when (androidButton) {
            MotionEvent.BUTTON_PRIMARY -> Glfw.MOUSE_LEFT
            MotionEvent.BUTTON_SECONDARY -> Glfw.MOUSE_RIGHT
            MotionEvent.BUTTON_TERTIARY -> Glfw.MOUSE_MIDDLE
            else -> return
        }
        NativeBridge.sendMouseButton(button, action, 0)
    }
}
