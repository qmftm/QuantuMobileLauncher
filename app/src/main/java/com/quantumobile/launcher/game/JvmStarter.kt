package com.quantumobile.launcher.game

import android.system.Os
import android.util.Log
import com.oracle.dalvik.VMLauncher
import com.quantumobile.launcher.core.GameLaunch
import java.io.File
import net.kdt.pojavlaunch.utils.JREUtils

/** Prepares the process the way PojavLauncher does and hands control to the JVM (does not return until it exits). */
object JvmStarter {
    private const val TAG = "JvmStarter"

    /** Present while the game process is expected to be alive; still there later means it died without returning. */
    fun runningMarker(logPath: String) = File(File(logPath).parentFile, "game.running")

    /** Appends [error] to the game log (used when starting the JVM itself fails). */
    /** Appends a launcher line to the game log. (Kotlin's println goes to logcat on Android, not to the redirected stdout.) */
    private fun logLine(text: String) {
        Log.i(TAG, text)
        runCatching { java.io.FileWriter(currentLog, true).use { it.write(text + "\n") } }
    }

    @Volatile private var currentLog = ""

    fun logFailure(logPath: String, error: Throwable) {
        runCatching {
            java.io.FileWriter(logPath, true).use { it.write("\n[launcher] FAILED: ${Log.getStackTraceString(error)}\n") }
        }
    }

    /** Hides the login token: the log gets shared when asking for help. */
    internal fun redact(args: List<String>): List<String> =
        args.mapIndexed { i, a -> if (i > 0 && args[i - 1] == "--accessToken") "<hidden>" else a }

    fun run(l: GameLaunch): Int {
        val marker = runningMarker(l.logPath)
        runCatching { marker.parentFile?.mkdirs(); marker.writeText(System.currentTimeMillis().toString()) }
        try {
            return start(l)
        } finally {
            marker.delete() // only reached when the JVM returned; a crash/exit inside it leaves the marker behind
        }
    }

    private fun start(l: GameLaunch): Int {
        currentLog = l.logPath
        NativeBridge.redirectOutput(l.logPath)
        startLogcatTail(l.logPath)
        logLine("[launcher] stage: output redirected, ABI=${android.os.Build.SUPPORTED_ABIS.joinToString()}, API ${android.os.Build.VERSION.SDK_INT}")
        val layout = JreLayout(File(l.javaHome))
        logLine("[launcher] stage: java home exists=${File(l.javaHome).isDirectory}, libjvm exists=${File(layout.jvmDir, "libjvm.so").isFile}")
        for (e in l.env) {
            val i = e.indexOf('=')
            if (i > 0) runCatching { Os.setenv(e.substring(0, i), e.substring(i + 1), true) }
        }
        val ld = layout.ldLibraryPath(l.libraryPaths)
        runCatching {
            Os.setenv("LD_LIBRARY_PATH", ld, true)
            Os.setenv("PATH", "${File(l.javaHome, "bin")}:${System.getenv("PATH").orEmpty()}", true)
        }
        logLine("[launcher] JAVA_HOME=${l.javaHome} libDir=${layout.libDir} jvmDir=${layout.jvmDir}")
        logLine("[launcher] LD_LIBRARY_PATH=$ld")
        val full = "${layout.jvmDir}:$ld"
        JREUtils.setLdLibraryPath(full)
        JREUtils.setLdLibraryPath(full)
        // The Java runtime links against libc++_shared.so, which only the app ships; it must be loaded first.
        val cxx = File(l.nativeLibDir, "libc++_shared.so")
        if (!cxx.isFile) logLine("[launcher] libc++_shared.so is missing from ${l.nativeLibDir}")
        else if (!JREUtils.dlopen(cxx.path)) logLine("[launcher] could not load ${cxx.path}")
        for (lib in layout.preloadList()) {
            if (!JREUtils.dlopen(lib.path)) logLine("[launcher] preload failed: ${lib.path}")
        }
        l.libraryPaths.map { File(it, "libopenal.so") }.firstOrNull { it.isFile }?.let { JREUtils.dlopen(it.path) }
        if (l.sdl) {
            // Global scope, so MobileGlues' glXGetProcAddress can resolve its own gl* symbols by name (as PojavLauncher does).
            val mg = File(l.nativeLibDir, "libmobileglues.so")
            if (!JREUtils.dlopen(mg.path)) logLine("[launcher] could not load ${mg.path}")
        }
        val chdir = JREUtils.chdir(l.gameDir)
        if (chdir != 0) logLine("[launcher] chdir failed: $chdir")
        logLine("[launcher] stage: starting JVM")
        logLine("[launcher] java ${redact(l.args).joinToString(" ")}")
        System.out.flush()
        val rc = VMLauncher.launchJVM((listOf("java") + l.args).toTypedArray())
        logLine("[launcher] launchJVM returned $rc" + if (rc == -1) " (libjli.so could not be loaded or has no JLI_Launch; see the native lines above)" else "")
        return rc
    }

    /** Mirrors this process' logcat output (linker errors, System.out, ART) and native crash dumps into the game log. */
    private fun startLogcatTail(logPath: String) {
        val pid = android.os.Process.myPid().toString()
        for (args in listOf(
            listOf("logcat", "-v", "brief", "--pid=$pid"),
            listOf("logcat", "-v", "brief", "-s", "DEBUG", "libc", "AndroidRuntime"),
        )) {
            Thread({
                runCatching {
                    val p = ProcessBuilder(args).redirectErrorStream(true).start()
                    java.io.FileWriter(logPath, true).use { w ->
                        p.inputStream.bufferedReader().forEachLine { w.write(it); w.write("\n"); w.flush() }
                    }
                }
            }, "log-tail").apply { isDaemon = true }.start()
        }
    }
}
