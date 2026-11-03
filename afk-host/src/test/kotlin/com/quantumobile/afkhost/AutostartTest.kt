package com.quantumobile.afkhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AutostartTest {
    @get:Rule val tmp = TemporaryFolder()

    private val winCmd = listOf("C:\\Users\\김철수\\AFK Host\\QuantuMobile-AFK-Background.exe", "--background")

    @Test
    fun windowsCommandLineQuotesProgramAndSpacedArgs() {
        assertEquals(
            "\"C:\\Users\\김철수\\AFK Host\\QuantuMobile-AFK-Background.exe\" --background",
            Autostart.windowsCommandLine(winCmd),
        )
        assertEquals("\"C:\\java\\javaw.exe\" -jar \"C:\\a b\\h.jar\" --background",
            Autostart.windowsCommandLine(listOf("C:\\java\\javaw.exe", "-jar", "C:\\a b\\h.jar", "--background")))
    }

    @Test
    fun windowsRegFileIsUtf16WithEscapedValue() {
        val bytes = Autostart.windowsRegFile(winCmd)
        assertEquals(0xFF.toByte(), bytes[0]); assertEquals(0xFE.toByte(), bytes[1])
        val text = String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        assertTrue(text.startsWith("Windows Registry Editor Version 5.00\r\n"))
        assertTrue(text.contains("[HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run]\r\n"))
        // .reg strings escape \ and ": the stored value is "C:\Users\김철수\AFK Host\…exe" --background
        assertTrue(text.contains("\"QuantuMobile-AFK\"=\"\\\"C:\\\\Users\\\\김철수\\\\AFK Host\\\\QuantuMobile-AFK-Background.exe\\\" --background\"\r\n"))
    }

    @Test
    fun windowsEnableImportsRegFileAndDisableDeletesValue() {
        val calls = mutableListOf<List<String>>()
        var registered = false
        val state = tmp.newFolder("state")
        val a = Autostart(winCmd, os = "Windows 11", home = tmp.root, env = { null }, stateDir = state, exec = { cmd ->
            calls += cmd
            when (cmd[1]) {
                "import" -> { assertTrue(File(cmd[2]).isFile); registered = true; 0 }
                "query" -> if (registered) 0 else 1
                "delete" -> { registered = false; 0 }
                else -> 1
            }
        })
        assertFalse(a.isEnabled())
        a.setEnabled(true)
        assertTrue(a.isEnabled())
        assertFalse("temporary .reg file removed", File(state, "autostart.reg").exists())
        a.setEnabled(false)
        assertEquals(listOf("reg", "delete", Autostart.RUN_KEY, "/v", "QuantuMobile-AFK", "/f"), calls.last())
        assertFalse(a.isEnabled())
    }

    @Test
    fun linuxWritesXdgAutostartEntry() {
        val config = tmp.newFolder("config")
        val cmd = listOf("/opt/java 21/bin/java", "-jar", "/home/u/afk\$host.jar", "--background")
        val a = Autostart(cmd, os = "Linux", home = tmp.root, env = { if (it == "XDG_CONFIG_HOME") config.path else null }, stateDir = tmp.newFolder("s"))
        a.setEnabled(true)
        val f = File(config, "autostart/quantumobile-afk.desktop")
        assertTrue(a.isEnabled())
        val exec = f.readLines().first { it.startsWith("Exec=") }
        assertEquals("Exec=\"/opt/java 21/bin/java\" \"-jar\" \"/home/u/afk\\\\\$host.jar\" \"--background\"", exec)
        a.setEnabled(false)
        assertFalse(f.exists())
    }

    @Test
    fun macWritesLaunchAgent() {
        val a = Autostart(listOf("/usr/bin/java", "-jar", "/Users/u/a&b.jar", "--background"), os = "Mac OS X", home = tmp.root, stateDir = tmp.newFolder("s"))
        a.setEnabled(true)
        val plist = File(tmp.root, "Library/LaunchAgents/com.quantumobile.afk.plist").readText()
        assertTrue(plist.contains("<string>/Users/u/a&amp;b.jar</string>"))
        assertTrue(plist.contains("<key>RunAtLoad</key><true/>"))
        a.setEnabled(false)
        assertFalse(a.isEnabled())
    }

    @Test
    fun refreshRepointsAfterTheProgramMoved() {
        val config = tmp.newFolder("config")
        val state = tmp.newFolder("s")
        Autostart(listOf("/old/java", "-jar", "/old/h.jar"), os = "Linux", home = tmp.root, env = { config.path }, stateDir = state).setEnabled(true)
        Autostart(listOf("/new/java", "-jar", "/new/h.jar"), os = "Linux", home = tmp.root, env = { config.path }, stateDir = state).refresh()
        assertTrue(File(config, "autostart/quantumobile-afk.desktop").readText().contains("/new/h.jar"))
    }

    @Test
    fun refreshDoesNothingWhenOff() {
        val config = tmp.newFolder("config")
        Autostart(listOf("/x/java"), os = "Linux", home = tmp.root, env = { config.path }, stateDir = tmp.newFolder("s")).refresh()
        assertFalse(File(config, "autostart/quantumobile-afk.desktop").exists())
    }

    @Test(expected = IllegalStateException::class)
    fun cannotEnableWithoutACommand() {
        Autostart(null, os = "Linux", home = tmp.root, env = { null }, stateDir = tmp.newFolder("s")).setEnabled(true)
    }

    @Test
    fun trayIconDraws() {
        val img = HostTray.image(true, 32)
        assertEquals(32, img.width)
    }
}
