package com.quantumobile.launcher.game

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Tiny drawn icons for buttons whose label starts with `@` (so they never depend on a font having the glyph):
 * `@shift` is a hollow up triangle, `@space` (jump) a filled one, `@eye`/`@eyeoff` the open/closed GUI eye, `@mouse` the ring-and-crosshair cursor.
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
            "@shift", "@space" -> { // sneak: hollow up triangle, jump: filled up triangle
                path.moveTo(cx, cy - s); path.lineTo(cx + s * 1.15f, cy + s * 0.85f); path.lineTo(cx - s * 1.15f, cy + s * 0.85f); path.close()
                if (label == "@space") paint.style = Paint.Style.FILL_AND_STROKE
            }
            "@eye", "@eyeoff" -> {
                val w = s * 1.5f; val h = s * 0.9f
                path.moveTo(cx - w, cy)
                path.quadTo(cx, cy - h * 2f, cx + w, cy)
                path.quadTo(cx, cy + h * 2f, cx - w, cy)
                if (label == "@eye") path.addCircle(cx, cy, s * 0.4f, Path.Direction.CW)
                else { // closed: lashes-free lid line plus a slash
                    path.moveTo(cx - w, cy + s * 0.9f); path.lineTo(cx + w, cy - s * 0.9f)
                }
            }
            "@mouse" -> { // the same ring-and-crosshair cursor that is drawn in the game while Mouse mode is on
                val r = s * 1.0f
                path.addCircle(cx, cy, r, Path.Direction.CW)
                path.moveTo(cx - r * 1.6f, cy); path.lineTo(cx + r * 1.6f, cy)
                path.moveTo(cx, cy - r * 1.6f); path.lineTo(cx, cy + r * 1.6f)
            }
        }
        canvas.drawPath(path, paint)
        paint.style = old; paint.strokeWidth = oldW; paint.strokeJoin = oldJoin; paint.strokeCap = oldCap
    }
}
