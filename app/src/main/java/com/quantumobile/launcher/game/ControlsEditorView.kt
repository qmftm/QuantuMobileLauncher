package com.quantumobile.launcher.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** Shows every on-screen button on an empty canvas; drag to move, tap to edit. */
class ControlsEditorView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var buttons: List<ControlSpec> = emptyList()
        set(value) { field = value; invalidate() }
    var selectedId: String? = null
        set(value) { field = value; invalidate() }
    /** Called after a drag finishes with the full updated list. */
    var onChanged: (List<ControlSpec>) -> Unit = {}
    /** Called when a button is tapped without dragging. */
    var onTap: (ControlSpec) -> Unit = {}

    private val density = resources.displayMetrics.density
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * density; color = Color.rgb(46, 230, 214) }
    private val grid = Paint().apply { color = Color.argb(30, 255, 255, 255); strokeWidth = 1f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 16 * density }

    private var dragId: String? = null
    private var downX = 0f
    private var downY = 0f
    private var startLeft = 0f
    private var startTop = 0f
    private var moved = false

    override fun onDraw(canvas: Canvas) {
        val step = 28 * density
        var x = step
        while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), grid); x += step }
        var y = step
        while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, grid); y += step }
        val radius = 10 * density
        for (b in buttons) {
            b.rect(width, height, density, rect)
            fill.color = Color.argb(150, 40, 40, 40)
            canvas.drawRoundRect(rect, radius, radius, fill)
            if (b.id == selectedId) canvas.drawRoundRect(rect, radius, radius, stroke)
            canvas.drawText(b.label, rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = buttons.lastOrNull { it.rect(width, height, density, rect).contains(event.x, event.y) } ?: return false
                dragId = hit.id
                selectedId = hit.id
                downX = event.x; downY = event.y
                hit.rect(width, height, density, rect)
                val inset = 3 * density
                startLeft = rect.left - inset; startTop = rect.top - inset
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val id = dragId ?: return true
                if (!moved && hypot(event.x - downX, event.y - downY) < 8 * density) return true
                moved = true
                buttons = buttons.map {
                    if (it.id == id) it.movedTo(startLeft + event.x - downX, startTop + event.y - downY, width, height, density) else it
                }
            }
            MotionEvent.ACTION_UP -> {
                val id = dragId
                dragId = null
                if (id != null) {
                    if (moved) onChanged(buttons) else buttons.firstOrNull { it.id == id }?.let(onTap)
                }
            }
            MotionEvent.ACTION_CANCEL -> dragId = null
        }
        return true
    }
}
