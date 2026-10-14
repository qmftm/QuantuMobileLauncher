package com.quantumobile.launcher.game

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Tiny drawn icons for buttons whose label starts with `@` (so they never depend on a font having the glyph):
 * `@shift` is an up arrow, `@mouse` is a pointer arrow, `@space` (jump) is two stacked chevrons.
 */
internal object ControlIcons {
    fun isIcon(label: String) = label.startsWith("@")

    fun draw(canvas: Canvas, label: String, r: RectF, density: Float, paint: Paint) {
        val cx = r.centerX(); val cy = r.centerY()
        val s = minOf(r.width(), r.height()) * 0.2f // leaves a generous margin inside the button
        val old = paint.style; val oldW = paint.strokeWidth; val oldJoin = paint.strokeJoin; val oldCap = paint.strokeCap
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * density
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        val path = Path()
        when (label) {
            "@shift" -> { // plain up arrow
                path.moveTo(cx, cy + s); path.lineTo(cx, cy - s)
                path.moveTo(cx - s * 0.8f, cy - s * 0.2f); path.lineTo(cx, cy - s); path.lineTo(cx + s * 0.8f, cy - s * 0.2f)
            }
            "@mouse" -> { // pointer arrow
                path.moveTo(cx - s * 0.7f, cy - s)
                path.lineTo(cx - s * 0.7f, cy + s * 0.8f)
                path.lineTo(cx - s * 0.25f, cy + s * 0.4f)
                path.lineTo(cx + s * 0.1f, cy + s * 1.1f)
                path.lineTo(cx + s * 0.45f, cy + s * 0.95f)
                path.lineTo(cx + s * 0.1f, cy + s * 0.25f)
                path.lineTo(cx + s * 0.75f, cy + s * 0.2f)
                path.close()
            }
            "@space" -> { // two stacked chevrons: jump
                for (dy in floatArrayOf(-0.45f, 0.55f)) {
                    path.moveTo(cx - s, cy + s * (dy + 0.45f)); path.lineTo(cx, cy + s * (dy - 0.25f)); path.lineTo(cx + s, cy + s * (dy + 0.45f))
                }
            }
        }
        canvas.drawPath(path, paint)
        paint.style = old; paint.strokeWidth = oldW; paint.strokeJoin = oldJoin; paint.strokeCap = oldCap
    }
}
