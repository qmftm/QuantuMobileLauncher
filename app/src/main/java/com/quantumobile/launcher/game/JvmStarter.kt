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

    fun run(l: GameLaunch): Int {
        NativeBridge.redirectOutput(l.logPath)
        startLogcatTail(l.logPath)
        val layout = JreLayout(File(l.javaHome))
        for (e in l.env) {
            val i = e.indexOf('=')
            if (i > 0) runCatching { Os.setenv(e.substring(0, i), e.substring(i + 1), true) }
        }
        val ld = layout.ldLibraryPath(l.libraryPaths)
        runCatching {
            Os.setenv("LD_LIBRARY_PATH", ld, true)
            Os.setenv("PATH", "${File(l.javaHome, "bin")}:${System.getenv("PATH").orEmpty()}", true)
        }
        println("[launcher] JAVA_HOME=${l.javaHome} libDir=${layout.libDir} jvmDir=${layout.jvmDir}")
        println("[launcher] LD_LIBRARY_PATH=$ld")
        val full = "${layout.jvmDir}:$ld"
        JREUtils.setLdLibraryPath(full)
        JREUtils.setLdLibraryPath(full)
        for (lib in layout.preloadList()) {
            if (!JREUtils.dlopen(lib.path)) println("[launcher] preload failed: ${lib.name}")
        }
        JREUtils.dlopen(File(l.nativeLibDir, "libopenal.so").path)
        val chdir = JREUtils.chdir(l.gameDir)
        if (chdir != 0) println("[launcher] chdir failed: $chdir")
        println("[launcher] java ${l.args.joinToString(" ")}")
        System.out.flush()
        val rc = VMLauncher.launchJVM((listOf("java") + l.args).toTypedArray())
        Log.i(TAG, "JVM exited with $rc")
        return rc
    }

    /** Native libraries log to logcat; mirror the relevant tags into the game log so users can share one file. */
    private fun startLogcatTail(logPath: String) {
        Thread({
            runCatching {
                val p = ProcessBuilder("logcat", "-v", "brief", "-s", "jrelog", "LIBGL", "QMLNative", "AndroidRuntime", "DEBUG", "libc", "linker")
                    .redirectErrorStream(true).start()
                java.io.FileWriter(logPath, true).use { w ->
                    p.inputStream.bufferedReader().forEachLine { w.write(it); w.write("\n"); w.flush() }
                }
            }
        }, "log-tail").apply { isDaemon = true }.start()
    }
}
