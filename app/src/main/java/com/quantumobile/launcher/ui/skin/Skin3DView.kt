package com.quantumobile.launcher.ui.skin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.quantumobile.launcher.core.skin.Skin3DRenderer
import com.quantumobile.launcher.core.skin.SkinModel
import com.quantumobile.launcher.core.skin.Texture

/**
 * A skin as a 3D figure that walks and turns. Drag to turn it. It draws itself only while it is on screen, so it
 * costs nothing in the background.
 */
class Skin3DView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var skin: Texture? = null
    private var cape: Texture? = null
    private var model = SkinModel.CLASSIC

    var walking = true
        set(value) { field = value; kick() }
    var spinning = true
        set(value) { field = value; kick() }

    private var yaw = 0.5f
    private var pitch = 0.08f
    private var seconds = 0f
    /** How far the limbs swing: eases in and out when walking starts and stops. */
    private var swing = 0f
    private var lastNanos = 0L
    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f

    private var renderer: Skin3DRenderer? = null
    private var bitmap: Bitmap? = null
    private var shownAt = 0L
    private val paint = Paint().apply { isFilterBitmap = false }
    private val backPaint = Paint().apply { color = 0xFF2A2A2A.toInt() }
    private val src = Rect()
    private val dst = RectF()
    private var active = false

    /** Shows [skin] (a 64×64 texture), optionally wearing [cape]; [startYaw] is the first viewing angle (0 = from the front). */
    fun show(skin: Texture, model: SkinModel, cape: Texture?, startYaw: Float? = null) {
        this.skin = skin; this.model = model; this.cape = cape
        if (startYaw != null) yaw = startYaw
        shownAt = 0 // force a redraw of the picture
        kick()
    }

    // ---- touch: drag to turn ----

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragging = true; lastX = e.x; lastY = e.y; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                yaw += (e.x - lastX) * 0.012f
                pitch = (pitch + (e.y - lastY) * 0.006f).coerceIn(-0.3f, 0.9f)
                lastX = e.x; lastY = e.y
                shownAt = 0; invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; parent?.requestDisallowInterceptTouchEvent(false); kick() }
        }
        return true
    }

    // ---- running only while visible ----

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateActive() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); updateActive() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateActive() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); updateActive() }

    private fun updateActive() {
        active = isAttachedToWindow && windowVisibility == VISIBLE && visibility == VISIBLE
        lastNanos = 0
        if (active) invalidate()
    }

    /** Draws another frame if something is moving. */
    private fun kick() { if (active) invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val rw = (w / RENDER_SCALE).coerceAtLeast(1); val rh = (h / RENDER_SCALE).coerceAtLeast(1)
        renderer = Skin3DRenderer(rw, rh)
        bitmap = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
        shownAt = 0
    }

    override fun onDraw(canvas: Canvas) {
        val pad = 12f * resources.displayMetrics.density
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), pad, pad, backPaint)
        val s = skin ?: return
        val r = renderer ?: return
        val bmp = bitmap ?: return

        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceAtMost(0.05f)
        val moving = walking || swing > 0.01f || (spinning && !dragging)
        // At most about 60 pictures a second, whatever the screen's refresh rate.
        if (dt >= MIN_FRAME || shownAt == 0L) {
            lastNanos = now
            if (walking) { seconds += dt; swing = minOf(1f, swing + dt * 4f) } else swing = maxOf(0f, swing - dt * 4f)
            if (spinning && !dragging) yaw += dt * 0.7f
            r.render(s, model, cape, yaw, pitch, Skin3DRenderer.walkPhase(seconds), swing)
            bmp.setPixels(r.pixels, 0, r.width, 0, 0, r.width, r.height)
            shownAt = now
        }
        src.set(0, 0, bmp.width, bmp.height)
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(bmp, src, dst, paint)
        if (active && (moving || dragging)) postInvalidateOnAnimation()
    }

    private companion object {
        /** The picture is drawn at half the screen resolution: crisp blocky pixels, and a quarter of the work. */
        const val RENDER_SCALE = 2
        const val MIN_FRAME = 1f / 62f
    }
}
