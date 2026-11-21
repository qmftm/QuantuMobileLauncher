package com.quantumobile.afkhost.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JPanel

fun Graphics2D.smooth() {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
}

/** A panel with rounded corners, a fill and an optional outline. Children sit on top of it. */
open class RoundedPanel(
    private val fill: Color = Theme.card,
    private val radius: Int = 22,
    private val outline: Color? = Theme.outline,
) : JPanel() {
    init { isOpaque = false }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.smooth()
        val shape = RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, radius.toFloat(), radius.toFloat())
        g2.color = fill
        g2.fill(shape)
        if (outline != null) { g2.color = outline; g2.stroke = BasicStroke(1f); g2.draw(shape) }
        g2.dispose()
        super.paintComponent(g)
    }
}

/** A flat rounded button that lights up under the mouse. [primary] buttons use the teal-to-purple gradient. */
class FlatButton(private var label: String, private val primary: Boolean = false, private val onClick: () -> Unit) : JComponent() {
    private var hover = false
    private var pressed = false

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = Theme.font(13f, bold = true)
        isFocusable = true
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; pressed = false; repaint() }
            override fun mousePressed(e: MouseEvent) { pressed = true; repaint() }
            override fun mouseReleased(e: MouseEvent) {
                val inside = contains(e.point)
                pressed = false; repaint()
                if (inside) onClick()
            }
        })
    }

    fun setLabel(text: String) { label = text; revalidate(); repaint() }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        return Dimension(fm.stringWidth(label) + 36, 38)
    }
    override fun getMinimumSize() = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.smooth()
        val shape = RoundRectangle2D.Float(0f, 0f, width - 1f, height - 1f, height.toFloat(), height.toFloat())
        if (primary) {
            g2.paint = GradientPaint(0f, 0f, Theme.teal, width.toFloat(), 0f, Theme.purple)
            g2.fill(shape)
            if (hover) { g2.color = Color(255, 255, 255, 40); g2.fill(shape) }
            if (pressed) { g2.color = Color(0, 0, 0, 50); g2.fill(shape) }
        } else {
            g2.color = if (pressed) Theme.cardHigh else if (hover) Color(0x2C2757) else Theme.cardHigh
            g2.fill(shape)
            g2.color = if (hover) Theme.purple else Theme.outline
            g2.draw(shape)
        }
        g2.font = font
        g2.color = if (primary) Color(0x0B1B1F) else Theme.text
        val fm = g2.fontMetrics
        g2.drawString(label, (width - fm.stringWidth(label)) / 2, (height - fm.height) / 2 + fm.ascent)
        g2.dispose()
    }
}

/** An on/off switch like the one in the phone app. */
class Switch(var on: Boolean, private val onChange: (Boolean) -> Unit) : JComponent() {
    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = Dimension(46, 26)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (isEnabled) { on = !on; repaint(); onChange(on) } }
        })
    }

    override fun getMinimumSize() = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.smooth()
        val track = RoundRectangle2D.Float(0f, 1f, width - 1f, height - 2f, height - 2f, height - 2f)
        if (on) g2.paint = GradientPaint(0f, 0f, Theme.teal, width.toFloat(), 0f, Theme.purple) else g2.color = Theme.cardHigh
        g2.fill(track)
        if (!on) { g2.color = Theme.outline; g2.draw(track) }
        val d = height - 8f
        val x = if (on) width - d - 4f else 4f
        g2.color = if (isEnabled) Color.WHITE else Theme.gray
        g2.fill(Ellipse2D.Float(x, 4f, d, d))
        g2.dispose()
    }
}

/** A small rounded tag with a coloured dot: "접속됨", "꺼짐" … */
class Pill(text: String = "", color: Color = Theme.gray, private val showDot: Boolean = true) : JComponent() {
    var text: String = text
        set(v) { field = v; revalidate(); repaint() }
    var color: Color = color
        set(v) { field = v; repaint() }

    init { font = Theme.font(12f, bold = true) }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        return Dimension(fm.stringWidth(text) + (if (showDot) 38 else 24), 26)
    }
    override fun getMinimumSize() = preferredSize
    override fun getMaximumSize() = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.smooth()
        val shape = RoundRectangle2D.Float(0f, 0f, width - 1f, height - 1f, height.toFloat(), height.toFloat())
        g2.color = Theme.alpha(color, 38)
        g2.fill(shape)
        g2.color = Theme.alpha(color, 110)
        g2.draw(shape)
        var x = 12
        if (showDot) {
            g2.color = color
            g2.fill(Ellipse2D.Float(12f, height / 2f - 4f, 8f, 8f))
            x = 26
        }
        g2.font = font
        g2.color = Theme.alpha(Color.WHITE, 235)
        val fm = g2.fontMetrics
        g2.drawString(text, x, (height - fm.height) / 2 + fm.ascent)
        g2.dispose()
    }
}

/** The app's cube with its orbit ring, drawn with shapes so it stays sharp at any size. */
object Logo {
    fun paint(g: Graphics2D, x: Float, y: Float, size: Float) {
        g.smooth()
        val s = size
        fun px(u: Float) = x + u * s
        fun py(v: Float) = y + v * s
        val top = Path2D.Float().apply { moveTo(px(0.50f), py(0.14f)); lineTo(px(0.80f), py(0.30f)); lineTo(px(0.50f), py(0.46f)); lineTo(px(0.20f), py(0.30f)); closePath() }
        val left = Path2D.Float().apply { moveTo(px(0.20f), py(0.30f)); lineTo(px(0.50f), py(0.46f)); lineTo(px(0.50f), py(0.82f)); lineTo(px(0.20f), py(0.66f)); closePath() }
        val right = Path2D.Float().apply { moveTo(px(0.50f), py(0.46f)); lineTo(px(0.80f), py(0.30f)); lineTo(px(0.80f), py(0.66f)); lineTo(px(0.50f), py(0.82f)); closePath() }
        g.paint = GradientPaint(px(0.2f), py(0.1f), Color(0xA6FFF4), px(0.8f), py(0.46f), Theme.teal); g.fill(top)
        g.paint = GradientPaint(px(0.2f), py(0.3f), Theme.purple, px(0.5f), py(0.82f), Color(0x5B3CD0)); g.fill(left)
        g.paint = GradientPaint(px(0.5f), py(0.46f), Color(0x5B3CD0), px(0.8f), py(0.8f), Theme.purpleDeep); g.fill(right)
        // the orbit ring and its dot
        g.stroke = BasicStroke(s * 0.045f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.paint = GradientPaint(px(0.05f), py(0.6f), Theme.teal, px(0.95f), py(0.4f), Color(0xCFF8FF))
        val ring = java.awt.geom.Arc2D.Float(px(0.04f), py(0.30f), s * 0.92f, s * 0.36f, 200f, 270f, java.awt.geom.Arc2D.OPEN)
        val rot = java.awt.geom.AffineTransform.getRotateInstance(Math.toRadians(-18.0), px(0.5f).toDouble(), py(0.48f).toDouble())
        g.draw(rot.createTransformedShape(ring))
        g.color = Color.WHITE
        val dot = rot.transform(java.awt.geom.Point2D.Float(px(0.93f), py(0.50f)), null)
        g.fill(Ellipse2D.Double(dot.x - s * 0.04, dot.y - s * 0.04, s * 0.08, s * 0.08))
    }

    /** The logo on a transparent picture, for the window and tray icon. */
    fun image(size: Int): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        paint(g, 0f, 0f, size.toFloat())
        g.dispose()
        return img
    }
}
