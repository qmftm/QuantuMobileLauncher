package com.quantumobile.launcher.game

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Tiny drawn icons for buttons whose label starts with `@` (so they never depend on a font having the glyph):
 * `@shift` is an outlined up arrow, `@space` is the open-box "space" bracket.
 */
internal object ControlIcons {
    fun isIcon(label: String) = label.startsWith("@")

    fun draw(canvas: Canvas, label: String, r: RectF, density: Float, paint: Paint) {
        val cx = r.centerX(); val cy = r.centerY()
        val s = minOf(r.width(), r.height()) * 0.28f
        val old = paint.style; val oldW = paint.strokeWidth; val oldJoin = paint.strokeJoin; val oldCap = paint.strokeCap
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * density
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        val path = Path()
        when (label) {
            "@shift" -> {
                path.moveTo(cx, cy - s)
                path.lineTo(cx + s * 0.95f, cy + 0.05f * s)
                path.lineTo(cx + s * 0.4f, cy + 0.05f * s)
                path.lineTo(cx + s * 0.4f, cy + s)
                path.lineTo(cx - s * 0.4f, cy + s)
                path.lineTo(cx - s * 0.4f, cy + 0.05f * s)
                path.lineTo(cx - s * 0.95f, cy + 0.05f * s)
                path.close()
            }
            "@space" -> {
                path.moveTo(cx - s * 1.3f, cy - s * 0.35f)
                path.lineTo(cx - s * 1.3f, cy + s * 0.35f)
                path.lineTo(cx + s * 1.3f, cy + s * 0.35f)
                path.lineTo(cx + s * 1.3f, cy - s * 0.35f)
            }
        }
        canvas.drawPath(path, paint)
        paint.style = old; paint.strokeWidth = oldW; paint.strokeJoin = oldJoin; paint.strokeCap = oldCap
    }
}
