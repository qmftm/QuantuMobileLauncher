package com.quantumobile.launcher.game

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import com.quantumobile.launcher.QuantumApp
import com.quantumobile.launcher.core.GameLaunch
import org.libsdl.app.SDLActivity
import org.lwjgl.glfw.CallbackBridge

/**
 * Hosts Minecraft 26.x, which opens its window through SDL3 (LWJGL 3.4). SDL's own Android glue (org.libsdl.app, unmodified)
 * owns the surface; the launcher's touch overlay sits on top and feeds [NativeBridge], which turns the game controls into
 * SDL key/mouse events. The game's JVM runs on SDL's "main" thread, where an SDL app's main loop would normally live.
 * Lives in the `:game` process like [GameActivity].
 */
class Sdl3Activity : SDLActivity() {
    private var launch: GameLaunch? = null
    private var controls: TouchControlsView? = null

    override fun getLibraries(): Array<String> = arrayOf("SDL3")

    override fun getArguments(): Array<String> = emptyArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        // libpojavexec provides the JVM launcher (VMLauncher) and the log redirection; the GLFW part stays idle here.
        CallbackBridge.ensureLoaded()
        NativeBridge.sdl = true
        launch = GameActivity.intentLaunch(intent) ?: GameSession.launch
        launch?.let { GameSession.launch = it }
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (launch == null) { finish(); return }
        addControls()
    }

    /**
     * SDL re-decides the screen orientation when the game opens its (resizable) window: with no hint it picks "follow the
     * device", which turns the game portrait. This launcher's game screen is always landscape.
     */
    override fun setOrientationBis(w: Int, h: Int, resizable: Boolean, hint: String?) {
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun addControls() {
        val settings = (application as QuantumApp).settings
        val view = TouchControlsView(this).apply {
            reloadLayout()
            opacity = settings.controlsOpacity / 100f
            sensitivity = settings.mouseSensitivity / 100f
            framebufferScale = 1f
            onShowKeyboard = ::toggleKeyboard
        }
        controls = view
        mLayout.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Window size for the overlay's cursor maths; the surface is full-screen.
        mLayout.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            NativeBridge.sdlWidth = r - l
            NativeBridge.sdlHeight = b - t
        }
    }

    private fun toggleKeyboard() {
        mSurface?.let {
            it.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(it, InputMethodManager.SHOW_FORCED)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Back = Escape (pause menu / close screens); SDL would only see it as "AC_BACK", which the game ignores.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) NativeBridge.tapKey(Glfw.KEY_ESCAPE)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        controls?.releaseAll() // held keys would stay "pressed" in the game while it runs unseen
        super.onPause()
    }

    /** Called by SDL on its main thread once the surface exists. Blocks until the JVM returns. */
    override fun main() {
        val l = launch ?: return
        if (GameSession.started) return
        GameSession.started = true
        val done = java.util.concurrent.CountDownLatch(1)
        GameRunner.start(applicationContext, l) { rc, showLog ->
            if (showLog) GameRunner.showLog(applicationContext, rc)
            mSurface?.postDelayed({ kotlin.system.exitProcess(rc) }, 600) ?: kotlin.system.exitProcess(rc)
            done.countDown()
        }
        done.await()
    }
}
