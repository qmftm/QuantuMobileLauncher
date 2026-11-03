package com.quantumobile.afkhost

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * "Start with the computer": registers [command] (the host in background mode, no console window) to run at login.
 * Windows: a value under HKCU\…\Run; macOS: a LaunchAgent; Linux: an XDG autostart entry. No admin rights needed.
 */
class Autostart(
    /** What to run at login; null when this way of running cannot be registered (e.g. from an IDE). */
    val command: List<String>?,
    os: String = System.getProperty("os.name").orEmpty(),
    private val home: File = File(System.getProperty("user.home")),
    private val env: (String) -> String? = System::getenv,
    private val stateDir: File = HostFiles.dir(),
    private val exec: (List<String>) -> Int = ::runQuietly,
) {
    enum class Platform { WINDOWS, MAC, LINUX }

    val platform = os.lowercase().let {
        when {
            it.contains("win") -> Platform.WINDOWS
            it.contains("mac") || it.contains("darwin") -> Platform.MAC
            else -> Platform.LINUX
        }
    }

    private val savedFile get() = File(stateDir, "autostart.txt")

    fun isEnabled(): Boolean = when (platform) {
        Platform.WINDOWS -> exec(listOf("reg", "query", RUN_KEY, "/v", NAME)) == 0
        Platform.MAC -> plistFile().isFile
        Platform.LINUX -> desktopFile().isFile
    }

    /** Turns autostart on or off; throws with a user-facing message when it cannot. */
    fun setEnabled(on: Boolean) = if (on) enable() else disable()

    private fun enable() {
        val cmd = command ?: throw IllegalStateException("이 실행 방식에서는 자동 실행을 설정할 수 없어요 (exe 또는 jar로 실행해 주세요)")
        when (platform) {
            Platform.WINDOWS -> {
                // reg import reads the value literally, so paths with spaces, quotes or Korean need no shell escaping.
                val reg = File(stateDir, "autostart.reg")
                reg.writeBytes(windowsRegFile(cmd))
                val rc = try { exec(listOf("reg", "import", reg.path)) } finally { reg.delete() }
                check(rc == 0) { "자동 실행을 등록하지 못했어요 (reg import: $rc)" }
            }
            Platform.MAC -> plistFile().apply { parentFile.mkdirs(); writeText(macPlist(cmd)) }
            Platform.LINUX -> desktopFile().apply { parentFile.mkdirs(); writeText(linuxDesktopEntry(cmd)) }
        }
        savedFile.writeText(cmd.joinToString("\n"))
    }

    private fun disable() {
        when (platform) {
            Platform.WINDOWS -> if (isEnabled()) {
                val rc = exec(listOf("reg", "delete", RUN_KEY, "/v", NAME, "/f"))
                check(rc == 0) { "자동 실행을 끄지 못했어요 (reg delete: $rc)" }
            }
            Platform.MAC -> plistFile().delete()
            Platform.LINUX -> desktopFile().delete()
        }
        savedFile.delete()
    }

    /** If autostart is on but registered for another location (the folder was moved or updated), points it here. */
    fun refresh() {
        val cmd = command ?: return
        val saved = savedFile.takeIf { it.isFile }?.readText()
        if (saved != cmd.joinToString("\n") && isEnabled()) runCatching { enable() }
    }

    fun plistFile() = File(home, "Library/LaunchAgents/$MAC_LABEL.plist")

    fun desktopFile() = File(env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, ".config"), "autostart/quantumobile-afk.desktop")

    companion object {
        const val NAME = "QuantuMobile-AFK"
        const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val MAC_LABEL = "com.quantumobile.afk"
        /** The windowless launcher packed next to QuantuMobile-AFK.exe in the Windows zip. */
        const val BACKGROUND_LAUNCHER = "QuantuMobile-AFK-Background"
        const val BACKGROUND_FLAG = "--background"

        /**
         * How this running host would start itself in the background: the windowless exe (Windows zip),
         * else `javaw/java -jar <this jar> --background`. Null when not running from either.
         */
        fun currentCommand(extra: List<String> = emptyList(), os: String = System.getProperty("os.name").orEmpty()): List<String>? {
            val windows = os.lowercase().contains("win")
            System.getProperty("jpackage.app-path")?.let { app ->
                val exe = File(app)
                val bg = File(exe.parentFile, "$BACKGROUND_LAUNCHER.exe")
                return listOf(if (windows && bg.isFile) bg.path else exe.path, BACKGROUND_FLAG) + extra
            }
            val jar = runCatching { File(Autostart::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull()
                ?.takeIf { it.isFile && it.name.endsWith(".jar") } ?: return null
            val java = File(System.getProperty("java.home"), "bin/" + if (windows) "javaw.exe" else "java").takeIf { it.isFile } ?: return null
            return listOf(java.path, "-jar", jar.path, BACKGROUND_FLAG) + extra
        }

        /** One Windows command line: every argument with a space (and the program) in double quotes. */
        fun windowsCommandLine(cmd: List<String>): String = cmd.mapIndexed { i, a ->
            if (i == 0 || a.isEmpty() || a.any { it == ' ' || it == '\t' }) "\"${a.replace("\"", "\\\"")}\"" else a
        }.joinToString(" ")

        /** A .reg file (UTF-16LE with BOM, as regedit writes them) setting the Run value to [cmd]. */
        fun windowsRegFile(cmd: List<String>): ByteArray {
            val value = windowsCommandLine(cmd).replace("\\", "\\\\").replace("\"", "\\\"")
            val text = "Windows Registry Editor Version 5.00\r\n\r\n" +
                "[HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run]\r\n" +
                "\"$NAME\"=\"$value\"\r\n"
            return byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        }

        fun macPlist(cmd: List<String>): String {
            fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            return """
                |<?xml version="1.0" encoding="UTF-8"?>
                |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                |<plist version="1.0">
                |<dict>
                |  <key>Label</key><string>$MAC_LABEL</string>
                |  <key>ProgramArguments</key>
                |  <array>
                |${cmd.joinToString("\n") { "    <string>${esc(it)}</string>" }}
                |  </array>
                |  <key>RunAtLoad</key><true/>
                |</dict>
                |</plist>
                |""".trimMargin()
        }

        /** An XDG autostart entry; Exec arguments quoted per the Desktop Entry spec. */
        fun linuxDesktopEntry(cmd: List<String>): String {
            // Inside a quoted argument, " ` $ \ take a backslash, and the key-file format doubles backslashes again.
            fun quote(a: String) = "\"" + a.map { c ->
                when (c) {
                    '"', '`', '$' -> "\\\\$c"
                    '\\' -> "\\\\\\\\"
                    '%' -> "%%"
                    else -> c.toString()
                }
            }.joinToString("") + "\""
            return """
                |[Desktop Entry]
                |Type=Application
                |Name=QuantuMobile AFK
                |Comment=QuantuMobile AFK host
                |Exec=${cmd.joinToString(" ") { quote(it) }}
                |Terminal=false
                |NoDisplay=true
                |X-GNOME-Autostart-enabled=true
                |""".trimMargin()
        }

        private fun runQuietly(cmd: List<String>): Int = try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if (p.waitFor(20, TimeUnit.SECONDS)) p.exitValue() else { p.destroy(); -1 }
        } catch (e: Exception) {
            -1
        }
    }
}
