package com.quantumobile.afkhost.ui

import com.quantumobile.afkhost.HostSnapshot
import com.quantumobile.afkhost.HostText
import com.quantumobile.afkhost.ServerRowData
import com.quantumobile.launcher.core.afk.AfkPhase
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GradientPaint
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.border.EmptyBorder

/** What the buttons in the window do. */
interface HostUiActions {
    fun copyCode()
    fun setAutostart(on: Boolean)
    fun hideWindow()
    fun quit()
    fun openLog()
    fun openFolder()
}

private fun label(text: String, size: Float, color: Color = Theme.text, bold: Boolean = false) =
    JLabel(text).apply { font = Theme.font(size, bold); foreground = color }

/** The host window's content: header, pairing code, servers, log and footer. Fed with [update]. */
class HostPanel(private val actions: HostUiActions) : JPanel(BorderLayout(0, 14)) {
    private val header = Header()
    private val codeCard = CodeCard(actions)
    private val serversCard = ServersCard()
    private val logCard = LogCard(actions)
    private val footer = Footer(actions)

    init {
        background = Theme.bg
        isOpaque = true
        border = EmptyBorder(18, 20, 16, 20)
        add(header, BorderLayout.NORTH)

        val middle = JPanel(GridBagLayout()).apply { isOpaque = false }
        fun c(x: Int, y: Int, w: Int = 1, wx: Double = 0.0, wy: Double = 0.0, fill: Int = GridBagConstraints.BOTH) =
            GridBagConstraints().apply {
                gridx = x; gridy = y; gridwidth = w; weightx = wx; weighty = wy; this.fill = fill
                insets = Insets(0, if (x == 0) 0 else 7, if (y == 0) 14 else 0, if (x == 0 && w == 1) 7 else 0)
            }
        middle.add(codeCard, c(0, 0, wx = 0.0, wy = 1.0))
        middle.add(serversCard, c(1, 0, wx = 1.0, wy = 1.0))
        middle.add(logCard, c(0, 1, w = 2, wx = 1.0, wy = 0.0))
        add(middle, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
        preferredSize = Dimension(940, 740)
    }

    fun update(s: HostSnapshot) {
        header.update(s)
        codeCard.update(s)
        serversCard.update(s)
        logCard.update(s)
        footer.update(s)
    }

    /** Draws the window's content into a picture (for screenshots and tests); works without a screen. */
    fun render(width: Int, height: Int): BufferedImage {
        setSize(width, height)
        layoutRecursively(this)
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        paint(g)
        g.dispose()
        return img
    }

    private fun layoutRecursively(c: java.awt.Container) {
        c.doLayout()
        c.components.forEach { if (it is java.awt.Container) layoutRecursively(it) }
    }
}

// ---------------------------------------------------------------- header

private class Header : JPanel(BorderLayout()) {
    private val title = label("QuantuMobile AFK 호스트", 21f, Color.WHITE, bold = true)
    private val subtitle = label("", 12.5f, Color(255, 255, 255, 200))
    private val pill = Pill("", Theme.gray)
    private val phone = label("", 12f, Color(255, 255, 255, 215))

    init {
        isOpaque = false
        preferredSize = Dimension(10, 104)
        border = EmptyBorder(0, 104, 0, 24)
        val text = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(Box.createVerticalGlue()); add(title); add(Box.createVerticalStrut(4)); add(subtitle); add(Box.createVerticalGlue())
        }
        val right = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(Box.createVerticalGlue())
            add(pill.also { it.alignmentX = 1f })
            add(Box.createVerticalStrut(6))
            add(phone.also { it.alignmentX = 1f })
            add(Box.createVerticalGlue())
        }
        add(text, BorderLayout.CENTER)
        add(right, BorderLayout.EAST)
    }

    fun update(s: HostSnapshot) {
        subtitle.text = "v${s.version}  ·  이 PC의 이름: ${s.name}"
        val connected = HostText.phoneConnected(s.lastContactAt, s.now)
        val running = s.servers.count { it.enabled && it.phase == AfkPhase.CONNECTED }
        val enabled = s.servers.count { it.enabled }
        when {
            enabled > 0 -> { pill.text = "서버 $running / $enabled 접속 중"; pill.color = if (running == enabled) Theme.green else Theme.amber }
            else -> { pill.text = "대기 중"; pill.color = Theme.gray }
        }
        phone.text = when {
            connected -> "폰 연결됨 · ${HostText.ago(s.lastContactAt!!, s.now)}"
            s.lastContactAt != null -> "폰 마지막 연결 ${HostText.ago(s.lastContactAt, s.now)}"
            else -> "아직 폰이 연결되지 않았어요"
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.smooth()
        val shape = RoundRectangle2D.Float(0f, 0f, width - 1f, height - 1f, 30f, 30f)
        g2.paint = GradientPaint(0f, 0f, Color(0x3B1FA3), width.toFloat(), height.toFloat(), Color(0x1FB8C9))
        g2.fill(shape)
        // a soft highlight in the middle, like the app's banner
        g2.clip = shape
        g2.paint = GradientPaint(width * 0.35f, 0f, Color(0x8F6BFF, false).let { Color(143, 107, 255, 150) }, width * 0.75f, height.toFloat(), Color(143, 107, 255, 0))
        g2.fillRect(0, 0, width, height)
        g2.clip = null
        Logo.paint(g2, 22f, 12f, 80f)
        g2.dispose()
        super.paintComponent(g)
    }
}

// ---------------------------------------------------------------- pairing code card

private class CodeText(private val onCopy: () -> Unit) : JComponent() {
    var code = ""
    private var hover = false
    private var copiedAt = 0L

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = Dimension(280, 70)
        toolTipText = "누르면 복사돼요"
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            override fun mouseClicked(e: MouseEvent) { copiedAt = System.currentTimeMillis(); onCopy(); repaint(); javax.swing.Timer(1600) { repaint() }.apply { isRepeats = false; start() } }
        })
    }

    override fun getMinimumSize() = Dimension(260, 70)
    override fun getMaximumSize() = Dimension(Int.MAX_VALUE, 70)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.smooth()
        val shape = RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 18f, 18f)
        g2.color = Color(0x120F29); g2.fill(shape)
        g2.color = if (hover) Theme.teal else Theme.outline; g2.stroke = BasicStroke(if (hover) 1.6f else 1f); g2.draw(shape)
        val copied = System.currentTimeMillis() - copiedAt < 1500
        g2.font = Theme.monoFont(25f, bold = true)
        val fm = g2.fontMetrics
        g2.paint = GradientPaint(0f, 0f, Theme.teal, width.toFloat(), 0f, Color(0xB79CFF))
        g2.drawString(code, (width - fm.stringWidth(code)) / 2, height / 2 + fm.ascent / 2 - 9)
        g2.font = Theme.font(11.5f)
        g2.color = if (copied) Theme.green else Theme.textDim
        val hint = if (copied) "✓ 복사했어요" else "누르면 복사돼요"
        g2.drawString(hint, (width - g2.fontMetrics.stringWidth(hint)) / 2, height - 13)
        g2.dispose()
    }
}

private class AddressRow(private val name: String) : JComponent() {
    var value = ""
    var dot: Color = Theme.gray
    init { preferredSize = Dimension(280, 40) }
    override fun getMinimumSize() = Dimension(200, 40)
    override fun getMaximumSize() = Dimension(Int.MAX_VALUE, 40)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.smooth()
        g2.color = dot
        g2.fill(Ellipse2D.Float(2f, 8f, 8f, 8f))
        g2.font = Theme.font(11.5f)
        g2.color = Theme.textDim
        g2.drawString(name, 18, 16)
        // Addresses are ASCII (monospace); the status texts have Korean letters, which monospace fonts cannot draw.
        val ascii = value.all { it.code < 128 }
        g2.font = if (ascii) Theme.monoFont(12f) else Theme.font(12f)
        g2.color = if (ascii) Theme.text else Theme.textDim
        val fm = g2.fontMetrics
        var text = value
        val max = width - 18
        if (fm.stringWidth(text) > max) {
            while (text.length > 4 && fm.stringWidth("…$text") > max) text = text.drop(1)
            text = "…$text"
        }
        g2.drawString(text, 18, 33)
        g2.dispose()
    }
}

private class CodeCard(private val actions: HostUiActions) : RoundedPanel() {
    private val code = CodeText { actions.copyCode() }
    private val lan = AddressRow("같은 와이파이")
    private val tunnel = AddressRow("어디서나 (외부 주소)")

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = EmptyBorder(18, 18, 18, 18)
        preferredSize = Dimension(330, 400)
        add(label("연결 코드", 13f, Theme.textDim, bold = true).also { it.alignmentX = 0f })
        add(Box.createVerticalStrut(10))
        add(code.also { it.alignmentX = 0f })
        add(Box.createVerticalStrut(12))
        add(FlatButton("코드 복사", primary = true) { actions.copyCode() }.also { it.alignmentX = 0f })
        add(Box.createVerticalStrut(20))
        add(label("접속 주소", 13f, Theme.textDim, bold = true).also { it.alignmentX = 0f })
        add(Box.createVerticalStrut(8))
        add(lan.also { it.alignmentX = 0f })
        add(tunnel.also { it.alignmentX = 0f })
        add(Box.createVerticalGlue())
        add(JLabel("<html><body style='width:250px'>앱 → <b>AFK 탭</b> → <b>PC 연결</b> → <b>PC 추가</b> 에<br>위 연결 코드만 넣으면 돼요.</body></html>").apply {
            font = Theme.font(12f); foreground = Theme.textDim; alignmentX = 0f
        })
    }

    fun update(s: HostSnapshot) {
        code.code = s.code
        lan.value = s.lanAddresses.firstOrNull()?.let { "$it:${s.port}" } ?: "연결된 네트워크가 없어요"
        lan.dot = if (s.lanAddresses.isEmpty()) Theme.red else Theme.green
        when {
            !s.tunnelEnabled -> { tunnel.value = "사용 안 함 (같은 와이파이 전용)"; tunnel.dot = Theme.gray }
            s.tunnelUrl == null -> { tunnel.value = "여는 중… (처음엔 조금 걸려요)"; tunnel.dot = Theme.amber }
            else -> { tunnel.value = s.tunnelUrl.removePrefix("https://"); tunnel.dot = Theme.green }
        }
        code.repaint(); lan.repaint(); tunnel.repaint()
    }
}

// ---------------------------------------------------------------- servers

private class ServerRow(private val data: ServerRowData, private val image: BufferedImage?) : JComponent() {
    init {
        preferredSize = Dimension(300, 66)
        alignmentX = 0f
    }
    override fun getMinimumSize() = Dimension(260, 66)
    override fun getMaximumSize() = Dimension(Int.MAX_VALUE, 66)

    private fun color() = when {
        !data.enabled -> Theme.gray
        data.phase == AfkPhase.CONNECTED -> Theme.green
        data.phase == AfkPhase.ERROR -> Theme.red
        data.phase == AfkPhase.OFF -> Theme.gray
        else -> Theme.amber
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.smooth()
        val card = RoundRectangle2D.Float(0.5f, 3f, width - 1f, height - 7f, 18f, 18f)
        g2.color = Theme.cardHigh; g2.fill(card)
        // icon
        val ix = 14; val iy = (height - 42) / 2
        val iconShape = RoundRectangle2D.Float(ix.toFloat(), iy.toFloat(), 42f, 42f, 12f, 12f)
        if (image != null) {
            g2.clip = iconShape
            g2.drawImage(image, ix, iy, 42, 42, null)
            g2.clip = null
        } else {
            g2.paint = GradientPaint(ix.toFloat(), iy.toFloat(), Theme.purple, ix + 42f, iy + 42f, Theme.purpleDeep)
            g2.fill(iconShape)
            g2.font = Theme.font(19f, bold = true); g2.color = Color.WHITE
            val letter = data.name.firstOrNull()?.uppercase() ?: "?"
            val fm = g2.fontMetrics
            g2.drawString(letter, ix + (42 - fm.stringWidth(letter)) / 2, iy + (42 - fm.height) / 2 + fm.ascent)
        }
        // status pill on the right
        val pillText = data.statusText
        g2.font = Theme.font(12f, bold = true)
        val pfm = g2.fontMetrics
        val pw = pfm.stringWidth(pillText) + 38
        val px = width - pw - 14
        val py = (height - 26) / 2
        val c = color()
        val pillShape = RoundRectangle2D.Float(px.toFloat(), py.toFloat(), pw - 1f, 25f, 26f, 26f)
        g2.color = Theme.alpha(c, 38); g2.fill(pillShape)
        g2.color = Theme.alpha(c, 110); g2.draw(pillShape)
        g2.color = c; g2.fill(Ellipse2D.Float(px + 12f, py + 8.5f, 8f, 8f))
        g2.color = Theme.alpha(Color.WHITE, 235)
        g2.drawString(pillText, px + 26, py + (26 - pfm.height) / 2 + pfm.ascent)
        // name and details
        val textX = ix + 42 + 14
        val maxText = px - textX - 10
        g2.font = Theme.font(14.5f, bold = true); g2.color = Theme.text
        g2.drawString(fit(data.name, g2, maxText), textX, 28)
        g2.font = Theme.font(11.5f); g2.color = Theme.textDim
        val sub = listOfNotNull(data.address, data.account.takeIf { it.isNotBlank() && it != "?" }, data.detail).joinToString("  ·  ")
        g2.drawString(fit(sub, g2, maxText), textX, 47)
        g2.dispose()
    }

    private fun fit(text: String, g: Graphics2D, max: Int): String {
        val fm = g.fontMetrics
        if (fm.stringWidth(text) <= max) return text
        var t = text
        while (t.isNotEmpty() && fm.stringWidth("$t…") > max) t = t.dropLast(1)
        return "$t…"
    }
}

private class ServersCard : RoundedPanel() {
    private val title = label("서버", 13f, Theme.textDim, bold = true)
    private val count = Pill("0", Theme.gray, showDot = false)
    private val list = JPanel().apply { isOpaque = false; layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val empty = JLabel(
        "<html><div style='text-align:center'>아직 이 PC에서 도는 서버가 없어요.<br><br>" +
            "폰 앱에서 서버를 추가할 때<br><b>실행 위치</b>를 이 PC로 고르면 여기에 나와요.</div></html>",
    ).apply { font = Theme.font(13f); foreground = Theme.textDim; horizontalAlignment = JLabel.CENTER }
    private val scroll = JScrollPane(list).apply {
        isOpaque = false; viewport.isOpaque = false; border = null
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = 16
        verticalScrollBar.preferredSize = Dimension(10, 0)
        verticalScrollBar.setUI(SlimScrollBarUI())
    }
    private val body = JPanel(BorderLayout()).apply { isOpaque = false }
    private val imageCache = HashMap<Int, BufferedImage?>()
    private var lastKey: List<Any?>? = null

    init {
        layout = BorderLayout(0, 12)
        border = EmptyBorder(18, 18, 14, 14)
        val top = JPanel(BorderLayout()).apply { isOpaque = false }
        top.add(title, BorderLayout.WEST)
        top.add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { isOpaque = false; add(count) }, BorderLayout.EAST)
        add(top, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
    }

    private fun decode(icon: ByteArray?): BufferedImage? {
        if (icon == null) return null
        return imageCache.getOrPut(icon.contentHashCode()) { runCatching { ImageIO.read(ByteArrayInputStream(icon)) }.getOrNull() }
    }

    fun update(s: HostSnapshot) {
        count.text = "${s.servers.size}"
        val key = s.servers.map { listOf(it.name, it.address, it.account, it.statusText, it.detail, it.enabled, it.icon?.contentHashCode()) }
        if (key == lastKey) return
        lastKey = key
        body.removeAll()
        if (s.servers.isEmpty()) {
            body.add(empty, BorderLayout.CENTER)
        } else {
            list.removeAll()
            s.servers.forEach { list.add(ServerRow(it, decode(it.icon))) }
            list.add(Box.createVerticalGlue())
            body.add(scroll, BorderLayout.CENTER)
        }
        body.revalidate(); body.repaint()
    }
}

// ---------------------------------------------------------------- log

private class LogView : JComponent() {
    var lines: List<String> = emptyList()
    init { preferredSize = Dimension(300, 88) }
    override fun getMinimumSize() = Dimension(100, 88)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.smooth()
        g2.font = Theme.font(12f) // the log has Korean text, which the monospace fonts cannot draw
        val fm = g2.fontMetrics
        val lineH = fm.height + 2
        val fit = (height / lineH).coerceAtLeast(1)
        val shown = lines.takeLast(fit)
        var y = height - (shown.size - 1) * lineH - fm.descent - 1
        for (line in shown) {
            val time = line.takeIf { it.length > 10 && it[0] == '[' && it[9] == ']' }?.substring(0, 10)
            var x = 0
            if (time != null) {
                g2.color = Theme.gray; g2.drawString(time, 0, y); x = fm.stringWidth(time) + fm.charWidth(' ')
            }
            val body = if (time != null) line.substring(10).trimStart() else line
            g2.color = when {
                "오류" in body || "실패" in body || "못했" in body || "틀린" in body -> Theme.red
                "접속됨" in body -> Theme.green
                "대기" in body || "끊겼" in body -> Theme.amber
                else -> Theme.text
            }
            var text = body
            val max = width - x
            if (fm.stringWidth(text) > max) { while (text.length > 3 && fm.stringWidth("$text…") > max) text = text.dropLast(1); text += "…" }
            g2.drawString(text, x, y)
            y += lineH
        }
        g2.dispose()
    }
}

private class LogCard(private val actions: HostUiActions) : RoundedPanel() {
    private val view = LogView()
    init {
        layout = BorderLayout(0, 8)
        border = EmptyBorder(14, 18, 14, 18)
        val top = JPanel(BorderLayout()).apply { isOpaque = false }
        top.add(label("로그", 13f, Theme.textDim, bold = true), BorderLayout.WEST)
        top.add(JLabel("전체 보기").apply {
            font = Theme.font(12f, bold = true); foreground = Theme.teal; cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { actions.openLog() } })
        }, BorderLayout.EAST)
        add(top, BorderLayout.NORTH)
        add(view, BorderLayout.CENTER)
    }
    fun update(s: HostSnapshot) { view.lines = s.logLines; view.repaint() }
}

// ---------------------------------------------------------------- footer

private class Footer(private val actions: HostUiActions) : JPanel(BorderLayout()) {
    private val switch = Switch(false) { actions.setAutostart(it) }
    private val note = label("컴퓨터 켤 때 자동 실행", 13f, Theme.text)
    private var syncing = false

    init {
        isOpaque = false
        border = EmptyBorder(2, 4, 0, 0)
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply { isOpaque = false; add(switch); add(note) }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)).apply {
            isOpaque = false
            add(FlatButton("설정 폴더") { actions.openFolder() })
            add(FlatButton("트레이로 숨기기") { actions.hideWindow() })
            add(FlatButton("종료") { actions.quit() })
        }
        add(left, BorderLayout.WEST)
        add(right, BorderLayout.EAST)
    }

    fun update(s: HostSnapshot) {
        switch.isEnabled = s.autostartAvailable
        note.text = if (s.autostartAvailable) "컴퓨터 켤 때 자동 실행" else "컴퓨터 켤 때 자동 실행 (이 방식에서는 쓸 수 없어요)"
        note.foreground = if (s.autostartAvailable) Theme.text else Theme.textDim
        val want = s.autostart == true
        if (switch.on != want) { switch.on = want; switch.repaint() }
    }
}

/** A thin rounded scroll bar in the window's colours (the default one is a bright grey strip). */
private class SlimScrollBarUI : javax.swing.plaf.basic.BasicScrollBarUI() {
    private fun none() = javax.swing.JButton().apply { preferredSize = Dimension(0, 0); isVisible = false }
    override fun createDecreaseButton(orientation: Int) = none()
    override fun createIncreaseButton(orientation: Int) = none()
    override fun paintTrack(g: Graphics, c: JComponent, r: java.awt.Rectangle) = Unit
    override fun paintThumb(g: Graphics, c: JComponent, r: java.awt.Rectangle) {
        val g2 = g.create() as Graphics2D
        g2.smooth()
        g2.color = if (isThumbRollover) Theme.purple else Theme.outline
        g2.fill(RoundRectangle2D.Float(r.x + 2f, r.y.toFloat(), r.width - 4f, r.height.toFloat(), 8f, 8f))
        g2.dispose()
    }
}
