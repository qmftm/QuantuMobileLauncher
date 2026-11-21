package com.quantumobile.afkhost.ui

import com.quantumobile.afkhost.HostSnapshot
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.Timer
import javax.swing.WindowConstants

/** The host's window. Closing it only hides it (the host keeps running); [HostUiActions.quit] ends the program. */
class HostWindow(private val snapshot: () -> HostSnapshot, private val actions: HostUiActions) : JFrame("QuantuMobile AFK 호스트") {
    private val panel = HostPanel(actions)
    private val timer = Timer(1000) { if (isVisible) panel.update(snapshot()) }

    init {
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        iconImages = listOf(16, 32, 64, 128).map { Logo.image(it) }
        contentPane = panel
        minimumSize = Dimension(840, 620)
        pack()
        setLocationRelativeTo(null)
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) { actions.hideWindow() }
        })
        panel.update(snapshot())
        timer.start()
    }

    /** Shows the window in front, also when it was hidden or minimised. */
    fun open() {
        panel.update(snapshot())
        isVisible = true
        extendedState = extendedState and JFrame.ICONIFIED.inv()
        toFront()
        requestFocus()
    }

    fun close() { timer.stop(); dispose() }
}
