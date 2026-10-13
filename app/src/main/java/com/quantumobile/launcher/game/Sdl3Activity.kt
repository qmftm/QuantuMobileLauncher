package com.quantumobile.launcher.game

import android.os.Bundle
import android.view.WindowManager
import com.quantumobile.launcher.core.GameLaunch
import net.kdt.pojavlaunch.utils.JREUtils
import org.libsdl.app.SDLActivity
import org.lwjgl.glfw.CallbackBridge
import kotlin.system.exitProcess

/**
 * Hosts Minecraft 26.x, which opens its window through SDL3 (LWJGL 3.4). SDL's own Android glue (org.libsdl.app, unmodified)
 * owns the surface and forwards touch/keys/mouse to libSDL3; the game's JVM runs on SDL's "main" thread, where an SDL app's
 * main loop would normally live. Lives in the `:game` process like [GameActivity].
 */
class Sdl3Activity : SDLActivity() {
    private var launch: GameLaunch? = null

    override fun getLibraries(): Array<String> = arrayOf("SDL3")

    override fun getArguments(): Array<String> = emptyArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        // libpojavexec provides the JVM launcher (VMLauncher) and the log redirection; the GLFW part stays idle here.
        CallbackBridge.ensureLoaded()
        launch = GameActivity.intentLaunch(intent) ?: GameSession.launch
        launch?.let { GameSession.launch = it }
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (launch == null) finish()
    }

    /** Called by SDL on its main thread once the surface exists. Blocks until the JVM returns. */
    override fun main() {
        val l = launch ?: return
        if (GameSession.started) return
        GameSession.started = true
        val done = java.util.concurrent.CountDownLatch(1)
        GameRunner.start(applicationContext, l) { rc, showLog ->
            if (showLog) GameRunner.showLog(applicationContext, rc)
            mSurface?.postDelayed({ exitProcess(rc) }, 600) ?: exitProcess(rc)
            done.countDown()
        }
        done.await()
    }
}
