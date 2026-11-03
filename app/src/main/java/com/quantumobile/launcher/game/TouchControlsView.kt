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

    /** A spec plus its live touch state and pixel rectangle. */
    private class Button(val spec: ControlSpec) {
        val rect = RectF()
        var pressed = false
        var pointerId = -1
        var repeater: Runnable? = null
        val kind get() = spec.kind
        val code get() = spec.code
        val toggle get() = spec.toggle
        val label get() = spec.label
    }

    var opacity: Float = 0.6f
        set(value) { field = value; invalidate() }
    var sensitivity: Float = 1f
    /** Maps view pixels to framebuffer pixels (render resolution scale). */
    var framebufferScale: Float = 1f
    var onShowKeyboard: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private var buttons: List<Button> = emptyList()

    /** (Re)reads the user's saved layout. */
    fun reloadLayout() {
        releaseAll()
        buttons = ControlLayoutStore.forContext(context).load().map(::Button)
        layoutButtons()
        invalidate()
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 16 * density
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private var grabbed = false
    private val pollGrab = object : Runnable {
        override fun run() {
            val now = NativeBridge.isCursorGrabbed()
            if (now != grabbed) {
                android.util.Log.w("QM", "grabbed $grabbed -> $now")
                releaseAll()
                grabbed = now
                if (now) {
                    cursorX = NativeBridge.windowWidth() / 2.0
                    cursorY = NativeBridge.windowHeight() / 2.0
                    NativeBridge.sendCursorPos(cursorX, cursorY)
                }
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
        if (buttons.isEmpty()) reloadLayout()
        post(pollGrab)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(pollGrab)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutButtons()

    private fun layoutButtons() {
        if (width == 0) return
        for (b in buttons) b.spec.rect(width, height, density, b.rect)
    }

    /** PojavLauncher's "GUI" button: hides every other button until pressed again. */
    private var controlsHidden = false

    /** The "Mouse" button: menus get a visible cursor moved by dragging, and a tap clicks where the cursor is. */
    private var pointerMode = false
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * density; color = Color.BLACK; strokeJoin = Paint.Join.MITER }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val cursorArrow = android.graphics.Path()

    private fun visible(b: Button) =
        if (controlsHidden) b.kind == ControlKind.HIDE else true

    override fun onDraw(canvas: Canvas) {
        val alpha = (opacity * 255).toInt()
        if (alpha == 0) return
        for (b in buttons) {
            if (!visible(b)) continue
            fill.color = if (b.pressed || (b.kind == ControlKind.MOUSE_MODE && pointerMode)) Color.argb(alpha, 46, 230, 214) else Color.argb(alpha / 2 + 20, 40, 40, 40)
            canvas.drawRoundRect(b.rect, b.rect.cornerRadius(), b.rect.cornerRadius(), fill)
            text.alpha = (alpha + 80).coerceAtMost(255)
            if (ControlIcons.isIcon(b.label)) ControlIcons.draw(canvas, if (b.kind == ControlKind.HIDE && controlsHidden) "@eyeoff" else b.label, b.rect, density, iconPaint)
            else {
                // Shrink the text a little when the label is wider than the button.
                val fit = (b.rect.width() * 0.84f / text.measureText(b.label)).coerceAtMost(1f)
                val size = text.textSize
                text.textSize = size * fit
                canvas.drawText(b.label, b.rect.centerX(), b.rect.centerY() - (text.descent() + text.ascent()) / 2, text)
                text.textSize = size
            }
        }
        if (pointerMode && !grabbed && !controlsHidden) {
            val cx = (cursorX / framebufferScale).toFloat()
            val cy = (cursorY / framebufferScale).toFloat()
            // A PC-style arrow pointer: the tip is the click position.
            val u = density * 0.63f
            val arrow = cursorArrow
            arrow.reset()
            arrow.moveTo(cx, cy)
            arrow.lineTo(cx, cy + 16 * u); arrow.lineTo(cx + 4 * u, cy + 12.5f * u); arrow.lineTo(cx + 7 * u, cy + 19 * u)
            arrow.lineTo(cx + 9.8f * u, cy + 17.8f * u); arrow.lineTo(cx + 6.8f * u, cy + 11.4f * u); arrow.lineTo(cx + 11.5f * u, cy + 11.4f * u)
            arrow.close()
            canvas.drawPath(arrow, cursorFill)
            canvas.drawPath(arrow, cursorPaint)
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
            ControlKind.KEYBOARD -> onShowKeyboard()
            ControlKind.HIDE -> { releaseAll(); controlsHidden = !controlsHidden }
            ControlKind.MOUSE_MODE -> {
                pointerMode = !pointerMode
                if (pointerMode && !grabbed) {
                    cursorX = NativeBridge.windowWidth() / 2.0
                    cursorY = NativeBridge.windowHeight() / 2.0
                    NativeBridge.sendCursorPos(cursorX, cursorY)
                }
            }
            ControlKind.SCROLL_UP, ControlKind.SCROLL_DOWN -> {
                b.pointerId = pointer
                fireScroll(b)
                startRepeat(b, pointer)
            }
            ControlKind.KEY, ControlKind.MOUSE -> {
                if (b.toggle) {
                    b.pressed = !b.pressed
                    send(b, if (b.pressed) Glfw.PRESS else Glfw.RELEASE)
                    return
                }
                b.pressed = true
                b.pointerId = pointer
                send(b, Glfw.PRESS)
                startRepeat(b, pointer)
            }
        }
    }

    private fun fireScroll(b: Button) {
        NativeBridge.sendScroll(0.0, if (b.kind == ControlKind.SCROLL_UP) 1.0 else -1.0)
    }

    /** A held "repeat" button fires again after a short delay and then at a steady rate, like a keyboard's auto-repeat. */
    private fun startRepeat(b: Button, pointer: Int) {
        if (!b.spec.repeat) return
        val tick = object : Runnable {
            override fun run() {
                if (b.pointerId != pointer) return
                if (b.kind == ControlKind.SCROLL_UP || b.kind == ControlKind.SCROLL_DOWN) fireScroll(b) else send(b, Glfw.PRESS)
                postDelayed(this, REPEAT_MS)
            }
        }
        b.repeater = tick
        postDelayed(tick, REPEAT_DELAY_MS)
    }

    private fun stopRepeat(b: Button) {
        b.repeater?.let { removeCallbacks(it) }
        b.repeater = null
    }

    private fun releaseButton(b: Button) {
        stopRepeat(b)
        b.pointerId = -1
        if (b.toggle || !b.pressed) return
        b.pressed = false
        send(b, Glfw.RELEASE)
    }

    private fun send(b: Button, action: Int) {
        val codes = b.spec.allCodes
        // Several keys press in order and release in reverse (so Ctrl+Shift+X style combos behave).
        for (c in if (action == Glfw.RELEASE) codes.asReversed() else codes) {
            if (b.kind == ControlKind.MOUSE) NativeBridge.sendMouseButton(c, action, 0)
            else NativeBridge.sendKey(c, 0, action, 0)
        }
    }

    /** Releases every pressed on-screen button and the camera finger (used when the screen goes to the background). */
    fun releaseAll() {
        buttons.forEach { b ->
            stopRepeat(b)
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
        } else if (!pointerMode) {
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
        } else if (pointerMode) {
            cursorX = (cursorX + (x - lastX) * sensitivity * framebufferScale).coerceIn(0.0, NativeBridge.windowWidth().toDouble())
            cursorY = (cursorY + (y - lastY) * sensitivity * framebufferScale).coerceIn(0.0, NativeBridge.windowHeight().toDouble())
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
        } else if (pointerMode) {
            // A tap clicks at the cursor; a drag only moved it.
            if (System.currentTimeMillis() - downTime < HOLD_MS && abs(lastX - downX) < 12 * density && abs(lastY - downY) < 12 * density) {
                NativeBridge.sendMouseButton(Glfw.MOUSE_LEFT, Glfw.PRESS, 0)
                NativeBridge.sendMouseButton(Glfw.MOUSE_LEFT, Glfw.RELEASE, 0)
            }
        } else {
            NativeBridge.sendMouseButton(Glfw.MOUSE_LEFT, Glfw.RELEASE, 0)
        }
        holdingAttack = false
        aimPointer = -1
    }

    companion object {
        private const val HOLD_MS = 300L
        private const val REPEAT_DELAY_MS = 450L
        private const val REPEAT_MS = 70L
    }
}

/** Corners scale with the button so every button has the look of the 50dp click buttons. */
internal fun android.graphics.RectF.cornerRadius() = 0.1f * minOf(width(), height())
