package com.quantumobile.launcher.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * On-screen controls drawn over the game surface.
 *
 * Buttons send GLFW key/mouse events. Touches outside buttons control the camera while the game has
 * grabbed the cursor (tap = use, hold = attack/break), and act as a mouse pointer in menus.
 */
class TouchControlsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private enum class Kind { KEY, MOUSE, SCROLL_UP, SCROLL_DOWN, KEYBOARD }

    private class Button(
        val label: String,
        val kind: Kind,
        val code: Int = 0,
        val toggle: Boolean = false,
        val showInMenus: Boolean = false,
        /** Position in units of the button size, measured from the given corner. */
        val col: Float,
        val row: Float,
        val fromRight: Boolean = false,
        val fromBottom: Boolean = false,
        val scale: Float = 1f,
    ) {
        val rect = RectF()
        var pressed = false
        var pointerId = -1
    }

    var opacity: Float = 0.6f
        set(value) { field = value; invalidate() }
    var sensitivity: Float = 1f
    /** Maps view pixels to framebuffer pixels (render resolution scale). */
    var framebufferScale: Float = 1f
    var onShowKeyboard: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private val buttons = listOf(
        // Movement pad (bottom-left)
        Button("W", Kind.KEY, Glfw.KEY_W, col = 1f, row = 2f, fromBottom = true),
        Button("A", Kind.KEY, Glfw.KEY_A, col = 0f, row = 1f, fromBottom = true),
        Button("S", Kind.KEY, Glfw.KEY_S, col = 1f, row = 0f, fromBottom = true),
        Button("D", Kind.KEY, Glfw.KEY_D, col = 2f, row = 1f, fromBottom = true),
        Button("⇩", Kind.KEY, Glfw.KEY_LEFT_SHIFT, toggle = true, col = 1f, row = 1f, fromBottom = true),
        // Actions (bottom-right)
        Button("⤒", Kind.KEY, Glfw.KEY_SPACE, col = 0f, row = 0f, fromRight = true, fromBottom = true, scale = 1.3f),
        Button("⚔", Kind.MOUSE, Glfw.MOUSE_LEFT, col = 0f, row = 1.4f, fromRight = true, fromBottom = true),
        Button("✋", Kind.MOUSE, Glfw.MOUSE_RIGHT, col = 1.2f, row = 0f, fromRight = true, fromBottom = true),
        Button("Run", Kind.KEY, Glfw.KEY_LEFT_CONTROL, toggle = true, col = 1.2f, row = 1.4f, fromRight = true, fromBottom = true),
        // Top row
        Button("Esc", Kind.KEY, Glfw.KEY_ESCAPE, showInMenus = true, col = 0f, row = 0f),
        Button("⌨", Kind.KEYBOARD, showInMenus = true, col = 1f, row = 0f),
        Button("T", Kind.KEY, Glfw.KEY_T, col = 2f, row = 0f),
        Button("E", Kind.KEY, Glfw.KEY_E, showInMenus = true, col = 3f, row = 0f),
        Button("Q", Kind.KEY, Glfw.KEY_Q, col = 4f, row = 0f),
        Button("F5", Kind.KEY, Glfw.KEY_F5, col = 0f, row = 0f, fromRight = true),
        Button("F3", Kind.KEY, Glfw.KEY_F3, col = 1f, row = 0f, fromRight = true),
        Button("◀", Kind.SCROLL_UP, col = 3f, row = 0f, fromRight = true),
        Button("▶", Kind.SCROLL_DOWN, col = 2f, row = 0f, fromRight = true),
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 16 * density
    }

    private var grabbed = false
    private val pollGrab = object : Runnable {
        override fun run() {
            val now = NativeBridge.isCursorGrabbed()
            if (now != grabbed) {
                releaseAll()
                grabbed = now
                invalidate()
            }
            postDelayed(this, 100)
        }
    }

    // Camera / pointer finger state
    private var aimPointer = -1
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var holdingAttack = false
    private var cursorX = 0.0
    private var cursorY = 0.0
    private val holdRunnable = Runnable {
        if (aimPointer != -1 && grabbed && hypot(lastX - downX, lastY - downY) < 12 * density) {
            holdingAttack = true
            NativeBridge.sendMouseButton(Glfw.MOUSE_LEFT, Glfw.PRESS, 0)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(pollGrab)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(pollGrab)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val size = 56 * density
        val margin = 16 * density
        for (b in buttons) {
            val s = size * b.scale
            val x = if (b.fromRight) w - margin - b.col * size - s else margin + b.col * size
            val y = if (b.fromBottom) h - margin - b.row * size - s else margin + b.row * size
            b.rect.set(x + 3 * density, y + 3 * density, x + s - 3 * density, y + s - 3 * density)
        }
    }

    private fun visible(b: Button) = grabbed || b.showInMenus

    override fun onDraw(canvas: Canvas) {
        val alpha = (opacity * 255).toInt()
        if (alpha == 0) return
        val radius = 10 * density
        for (b in buttons) {
            if (!visible(b)) continue
            fill.color = if (b.pressed) Color.argb(alpha, 120, 200, 255) else Color.argb(alpha / 2 + 20, 40, 40, 40)
            canvas.drawRoundRect(b.rect, radius, radius, fill)
            text.alpha = (alpha + 80).coerceAtMost(255)
            canvas.drawText(b.label, b.rect.centerX(), b.rect.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val id = event.getPointerId(index)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(index)
                val y = event.getY(index)
                val button = buttons.firstOrNull { visible(it) && it.rect.contains(x, y) }
                if (button != null) pressButton(button, id)
                else if (aimPointer == -1) startAim(id, x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (aimPointer != -1) {
                    val i = event.findPointerIndex(aimPointer)
                    if (i >= 0) moveAim(event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                buttons.firstOrNull { it.pointerId == id }?.let { releaseButton(it) }
                if (id == aimPointer) endAim()
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        invalidate()
        return true
    }

    private fun pressButton(b: Button, pointer: Int) {
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        when (b.kind) {
            Kind.KEYBOARD -> onShowKeyboard()
            Kind.SCROLL_UP -> NativeBridge.sendScroll(0.0, 1.0)
            Kind.SCROLL_DOWN -> NativeBridge.sendScroll(0.0, -1.0)
            Kind.KEY, Kind.MOUSE -> {
                if (b.toggle) {
                    b.pressed = !b.pressed
                    send(b, if (b.pressed) Glfw.PRESS else Glfw.RELEASE)
                    return
                }
                b.pressed = true
                b.pointerId = pointer
                send(b, Glfw.PRESS)
            }
        }
    }

    private fun releaseButton(b: Button) {
        b.pointerId = -1
        if (b.toggle || !b.pressed) return
        b.pressed = false
        send(b, Glfw.RELEASE)
    }

    private fun send(b: Button, action: Int) {
        if (b.kind == Kind.MOUSE) NativeBridge.sendMouseButton(b.code, action, 0)
        else NativeBridge.sendKey(b.code, 0, action, 0)
    }

    private fun releaseAll() {
        buttons.forEach { b ->
            if (b.pressed) send(b, Glfw.RELEASE)
            b.pressed = false
            b.pointerId = -1
        }
        if (aimPointer != -1) endAim()
    }

    private fun startAim(pointer: Int, x: Float, y: Float) {
        aimPointer = pointer
        lastX = x; lastY = y; downX = x; downY = y
        downTime = System.currentTimeMillis()
        if (grabbed) {
            postDelayed(holdRunnable, HOLD_MS)
        } else {
            cursorX = (x * framebufferScale).toDouble()
            cursorY = (y * framebufferScale).toDouble()
            NativeBridge.sendCursorPos(cursorX, cursorY)
            NativeBridge.sendMouseButton(Glfw.MOUSE_LEFT, Glfw.PRESS, 0)
        }
    }

    private fun moveAim(x: Float, y: Float) {
        if (grabbed) {
            cursorX += (x - lastX) * sensitivity * framebufferScale
            cursorY += (y - lastY) * sensitivity * framebufferScale
        } else {
            cursorX = (x * framebufferScale).toDouble()
            cursorY = (y * framebufferScale).toDouble()
        }
        lastX = x; lastY = y
        NativeBridge.sendCursorPos(cursorX, cursorY)
    }

    private fun endAim() {
        removeCallbacks(holdRunnable)
        if (grabbed) {
            if (holdingAttack) {
                NativeBridge.sendMouseButton(Glfw.MOUSE_LEFT, Glfw.RELEASE, 0)
            } else if (System.currentTimeMillis() - downTime < HOLD_MS &&
                abs(lastX - downX) < 12 * density && abs(lastY - downY) < 12 * density
            ) {
                NativeBridge.sendMouseButton(Glfw.MOUSE_RIGHT, Glfw.PRESS, 0)
                NativeBridge.sendMouseButton(Glfw.MOUSE_RIGHT, Glfw.RELEASE, 0)
            }
        } else {
            NativeBridge.sendMouseButton(Glfw.MOUSE_LEFT, Glfw.RELEASE, 0)
        }
        holdingAttack = false
        aimPointer = -1
    }

    companion object {
        private const val HOLD_MS = 300L
    }
}
