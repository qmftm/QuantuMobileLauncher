package com.quantumobile.afkhost

import com.quantumobile.afkhost.ui.HostUiActions
import com.quantumobile.afkhost.ui.HostWindow
import com.quantumobile.launcher.core.afk.AfkPhase
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.swing.JOptionPane

/** Connects the window to the running host: builds its snapshots and carries out its buttons. */
class HostUi(
    private val title: String,
    private val version: String,
    private val config: HostConfig,
    private val dir: File,
    private val logFile: File,
    private val service: HostService,
    private val autostart: Autostart,
) : HostUiActions {
    private var window: HostWindow? = null

    /** The autostart state is read in the background: on Windows reading it runs a program, too slow for every refresh. */
    @Volatile private var autostartOn: Boolean? = null

    init { refreshAutostart() }

    private fun refreshAutostart() {
        Thread { autostartOn = runCatching { autostart.isEnabled() }.getOrNull() }.apply { isDaemon = true }.start()
    }

    fun snapshot(): HostSnapshot {
        val now = System.currentTimeMillis()
        val rows = service.list().map { r ->
            val (status, detail) = HostText.status(r.phase, r.server.enabled, r.version, r.message, r.retryInMs)
            ServerRowData(
                name = r.server.name,
                address = r.server.address,
                account = r.accountName,
                phase = if (r.server.enabled) r.phase else AfkPhase.OFF,
                enabled = r.server.enabled,
                statusText = status,
                detail = detail,
                icon = service.iconBytes(r.server.id),
            )
        }
        return HostSnapshot(
            name = config.name,
            version = version,
            code = config.code,
            port = config.port,
            lanAddresses = lanAddresses(),
            tunnelUrl = HostModel.tunnelUrl,
            tunnelEnabled = HostModel.tunnelEnabled,
            lastContactAt = HostModel.lastContactAt,
            servers = rows,
            autostart = autostartOn,
            autostartAvailable = autostart.command != null,
            logLines = HostLog.recent().filter { it.startsWith("[") }, // the start-up banner is for the console
            now = now,
        )
    }

    /** Shows the window (creating it the first time). */
    fun openWindow() {
        EventQueue.invokeLater {
            val w = window ?: HostWindow({ snapshot() }, this).also { window = it }
            w.open()
        }
    }

    // ---- the buttons ----

    override fun copyCode() {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(config.code), null) }
    }

    override fun setAutostart(on: Boolean) {
        Thread {
            val error = runCatching { autostart.setEnabled(on) }.exceptionOrNull()
            refreshAutostart()
            if (error != null) EventQueue.invokeLater {
                JOptionPane.showMessageDialog(window, error.message ?: error.toString(), title, JOptionPane.ERROR_MESSAGE)
            }
        }.apply { isDaemon = true }.start()
    }

    override fun hideWindow() {
        EventQueue.invokeLater {
            if (HostTray.available()) window?.isVisible = false
            else quit() // no tray icon to bring it back: closing the window ends the program
        }
    }

    override fun quit() {
        EventQueue.invokeLater {
            val r = JOptionPane.showConfirmDialog(window, "AFK 호스트를 끌까요?\nPC에서 돌던 AFK 봇이 모두 서버에서 나가요.", title, JOptionPane.OK_CANCEL_OPTION)
            if (r == JOptionPane.OK_OPTION) Thread { System.exit(0) }.start()
        }
    }

    override fun openLog() = open(logFile)
    override fun openFolder() = open(dir)

    private fun open(f: File) {
        runCatching { Desktop.getDesktop().open(f) }.onFailure {
            JOptionPane.showMessageDialog(window, f.absolutePath, title, JOptionPane.INFORMATION_MESSAGE)
        }
    }
}

/** IPv4 addresses of this PC on its local networks. */
internal fun lanAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .map { it.hostAddress }
}.getOrDefault(emptyList())
