package com.quantumobile.afkhost

import java.awt.BasicStroke
import java.awt.CheckboxMenuItem
import java.awt.Color
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.JOptionPane
import javax.swing.JTextField
import javax.swing.UIManager

/**
 * The icon in the taskbar's notification area (menu bar on macOS): shows the pairing code, opens the log,
 * toggles "start with the computer" and quits. It is how the windowless background mode is controlled.
 * Does nothing where there is no desktop (servers, SSH).
 */
class HostTray(
    private val title: String,
    private val code: String,
    private val logFile: File,
    private val dir: File,
    private val autostart: Autostart,
) {
    private var icon: TrayIcon? = null
    private var active = false

    /** Shows the icon; false when this machine has no tray. */
    fun show(): Boolean {
        if (!available()) return false
        return runCatching {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            var ok = false
            EventQueue.invokeAndWait { ok = install() }
            ok
        }.getOrDefault(false)
    }

    private fun install(): Boolean {
        val menu = PopupMenu()
        menu.add(MenuItem(title).apply { isEnabled = false })
        menu.addSeparator()
        menu.add(MenuItem("연결 코드 보기 (복사)").apply { addActionListener { showCode() } })
        menu.add(MenuItem("로그 보기").apply { addActionListener { open(logFile) } })
        menu.add(MenuItem("설정 폴더 열기").apply { addActionListener { open(dir) } })
        menu.addSeparator()
        val auto = CheckboxMenuItem("컴퓨터 켤 때 자동 실행")
        auto.isEnabled = autostart.command != null
        // Reading the state may run a command (Windows: reg query); do it off the UI thread.
        Thread { val on = runCatching { autostart.isEnabled() }.getOrDefault(false); EventQueue.invokeLater { auto.state = on } }.start()
        auto.addItemListener { toggleAutostart(auto) }
        menu.add(auto)
        menu.addSeparator()
        menu.add(MenuItem("종료").apply {
            addActionListener {
                val r = JOptionPane.showConfirmDialog(null, "AFK 호스트를 끌까요?\nPC에서 돌던 AFK 봇이 모두 서버에서 나가요.", title, JOptionPane.OK_CANCEL_OPTION)
                if (r == JOptionPane.OK_OPTION) Thread { System.exit(0) }.start()
            }
        })
        val t = TrayIcon(image(false), title, menu).apply {
            isImageAutoSize = true
            addActionListener { showCode() } // double click (Windows) / click
        }
        SystemTray.getSystemTray().add(t)
        icon = t
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { SystemTray.getSystemTray().remove(t) } })
        return true
    }

    /** Updates the tooltip and the dot (green while any bot is connected). */
    fun update(connected: Int, enabled: Int) {
        val t = icon ?: return
        EventQueue.invokeLater {
            t.toolTip = "$title\n${if (enabled == 0) "켜진 서버 없음" else "접속 $connected / $enabled"}"
            val now = connected > 0
            if (now != active) { active = now; t.image = image(now) }
        }
    }

    fun showCode() = EventQueue.invokeLater {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(code), null) }
        val field = JTextField(code).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.BOLD, 22)
            horizontalAlignment = JTextField.CENTER
        }
        JOptionPane.showMessageDialog(
            null,
            arrayOf<Any>("앱 → AFK 탭 → PC 연결 → PC 추가 에 이 연결 코드를 넣으세요.", field, "(클립보드에 복사했어요)"),
            title, JOptionPane.INFORMATION_MESSAGE,
        )
    }

    private fun toggleAutostart(item: CheckboxMenuItem) {
        val want = item.state
        Thread {
            val error = runCatching { autostart.setEnabled(want) }.exceptionOrNull()
            EventQueue.invokeLater {
                if (error != null) {
                    item.state = !want
                    JOptionPane.showMessageDialog(null, error.message ?: error.toString(), title, JOptionPane.ERROR_MESSAGE)
                } else if (want) {
                    JOptionPane.showMessageDialog(
                        null,
                        "다음에 컴퓨터를 켜면 창 없이 백그라운드에서 실행돼요.\n작업 표시줄 오른쪽 아래(숨겨진 아이콘 ^)의 AFK 아이콘으로 코드 확인·종료를 할 수 있어요.",
                        title, JOptionPane.INFORMATION_MESSAGE,
                    )
                }
            }
        }.start()
    }

    private fun open(f: File) {
        runCatching { Desktop.getDesktop().open(f) }.onFailure {
            JOptionPane.showMessageDialog(null, f.absolutePath, title, JOptionPane.INFORMATION_MESSAGE)
        }
    }

    companion object {
        fun available(): Boolean = !GraphicsEnvironment.isHeadless() && runCatching { SystemTray.isSupported() }.getOrDefault(false)

        /** A message box when there is a desktop (used by the windowless mode, which has no console to print to). */
        fun message(title: String, text: String) {
            if (GraphicsEnvironment.isHeadless()) return
            runCatching {
                runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
                JOptionPane.showMessageDialog(null, text, title, JOptionPane.INFORMATION_MESSAGE)
            }
        }

        /** Drawn icon: a dark rounded square with "AFK" and a status dot (green = a bot is connected). */
        fun image(active: Boolean, size: Int = 64): BufferedImage {
            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val s = size.toFloat()
            g.color = Color(0x26, 0x32, 0x38)
            g.fillRoundRect(0, 0, size, size, size / 4, size / 4)
            g.color = Color.WHITE
            g.font = Font(Font.SANS_SERIF, Font.BOLD, (s * 0.36f).toInt())
            val fm = g.fontMetrics
            val text = "AFK"
            g.drawString(text, (size - fm.stringWidth(text)) / 2, (size * 0.52f + fm.ascent * 0.35f).toInt())
            val d = (s * 0.34f).toInt()
            g.color = if (active) Color(0x43, 0xA0, 0x47) else Color(0x9E, 0x9E, 0x9E)
            g.fillOval(size - d - 1, size - d - 1, d, d)
            g.stroke = BasicStroke(s * 0.05f)
            g.color = Color(0x26, 0x32, 0x38)
            g.drawOval(size - d - 1, size - d - 1, d, d)
            g.dispose()
            return img
        }
    }
}
