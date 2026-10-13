package com.quantumobile.launcher.game

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.quantumobile.launcher.core.GameLaunch

/** Starts the JVM for either window backend (GLFW bridge activity or SDL3 activity) and reports how it ended. */
object GameRunner {
    private const val QUICK_EXIT_MS = 20_000L

    /** Runs the JVM on the calling thread's behalf in a big-stack thread; [onExit] runs on the main thread. */
    fun start(context: Context, l: GameLaunch, onExit: (rc: Int, showLog: Boolean) -> Unit): Thread {
        GameService.start(context)
        // A big stack: the JVM's main thread runs on this thread and Minecraft recurses deeply.
        return Thread(null, {
            val started = System.currentTimeMillis()
            var rc = -1
            try {
                rc = JvmStarter.run(l)
            } catch (t: Throwable) {
                JvmStarter.logFailure(l.logPath, t)
            }
            // Only reached when the JVM returned (or could not start): a normal quit ends the process inside the JVM.
            val quick = System.currentTimeMillis() - started < QUICK_EXIT_MS
            Handler(Looper.getMainLooper()).post { onExit(rc, rc != 0 || quick) }
        }, "Minecraft-Main", 64L * 1024 * 1024).also { it.start() }
    }

    /** Shows why the game ended: the log viewer lives in the launcher process. */
    fun showLog(context: Context, rc: Int) {
        runCatching {
            context.startActivity(
                Intent(context, com.quantumobile.launcher.ui.LogActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra(com.quantumobile.launcher.ui.LogActivity.EXTRA_EXIT_CODE, rc),
            )
        }
    }
}
