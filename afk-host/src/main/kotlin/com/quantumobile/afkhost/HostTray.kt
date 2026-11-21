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
    /** Opens the host window; when null the tray has no window to open. */
    private val onOpenWindow: (() -> Unit)? = null,
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
        if (onOpenWindow != null) {
            menu.add(MenuItem("창 열기").apply { font = font.deriveFont(java.awt.Font.BOLD); addActionListener { onOpenWindow.invoke() } })
        } else {
            menu.add(MenuItem(title).apply { isEnabled = false })
        }
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
            addActionListener { if (onOpenWindow != null) onOpenWindow.invoke() else showCode() } // double click (Windows) / click
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

        /** The app's cube with a status dot (green = a bot is connected). */
        fun image(active: Boolean, size: Int = 64): BufferedImage {
            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            com.quantumobile.afkhost.ui.Logo.paint(g, 0f, 0f, size * 0.92f)
            val d = (size * 0.34f).toInt()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(0x0E0B1F)
            g.fillOval(size - d - 1, size - d - 1, d, d)
            g.color = if (active) Color(0x43D17A) else Color(0x7C77A3)
            val inset = (size * 0.05f).toInt().coerceAtLeast(1)
            g.fillOval(size - d - 1 + inset, size - d - 1 + inset, d - 2 * inset, d - 2 * inset)
            g.dispose()
            return img
        }
    }
}
