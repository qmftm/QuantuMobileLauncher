package com.quantumobile.launcher.game

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.quantumobile.launcher.QuantumApp
import com.quantumobile.launcher.core.GameLaunch
import com.quantumobile.launcher.databinding.ActivityGameBinding
import net.kdt.pojavlaunch.utils.JREUtils
import org.lwjgl.glfw.CallbackBridge
import kotlin.system.exitProcess

/**
 * Hosts the running game. Lives in the separate `:game` process because the JVM owns the process
 * once started and terminates it on exit.
 */
class GameActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var binding: ActivityGameBinding
    private lateinit var launch: GameLaunch
    private var scale = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallbackBridge.ensureLoaded()
        launch = intentLaunch(intent) ?: GameSession.launch ?: run { finish(); return }
        GameSession.launch = launch
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        val settings = (application as QuantumApp).settings
        scale = settings.resolutionPercent / 100f
        val sensitivity = settings.mouseSensitivity / 100f

        binding.surface.framebufferScale = scale
        binding.surface.sensitivity = sensitivity
        binding.surface.holder.addCallback(this)
        binding.controls.reloadLayout()
        binding.controls.apply {
            opacity = settings.controlsOpacity / 100f
            this.sensitivity = sensitivity
            framebufferScale = scale
            onShowKeyboard = ::toggleKeyboard
        }
        startGrabWatcher()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = NativeBridge.tapKey(Glfw.KEY_ESCAPE)
        })
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val fbWidth = (binding.surface.width * scale).toInt().coerceAtLeast(1)
        val fbHeight = (binding.surface.height * scale).toInt().coerceAtLeast(1)
        if (scale < 1f && (width != fbWidth || height != fbHeight)) {
            holder.setFixedSize(fbWidth, fbHeight) // triggers another surfaceChanged at render size
            return
        }
        // Order matters: the size must be known before the window is bound, and both before the JVM starts.
        CallbackBridge.sendScreenSize(width, height)
        JREUtils.setupBridgeWindow(holder.surface)
        startJvmOnce()
    }

    // The native side keeps rendering state across surface loss and re-binds on the next setupBridgeWindow.
    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

    private fun startJvmOnce() {
        if (GameSession.started) return
        GameSession.started = true
        GameService.start(applicationContext)
        val l = launch
        // A big stack: the JVM's main thread runs on this thread and Minecraft recurses deeply.
        Thread(null, {
            val started = System.currentTimeMillis()
            var rc = -1
            try {
                rc = JvmStarter.run(l)
            } catch (t: Throwable) {
                JvmStarter.logFailure(l.logPath, t)
            }
            // Only reached when the JVM returned (or could not start): a normal quit ends the process inside the JVM.
            val quick = System.currentTimeMillis() - started < QUICK_EXIT_MS
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (rc != 0 || quick) {
                    // Show why instead of just closing: the log viewer lives in the launcher process.
                    runCatching {
                        startActivity(
                            Intent(applicationContext, com.quantumobile.launcher.ui.LogActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                .putExtra(com.quantumobile.launcher.ui.LogActivity.EXTRA_EXIT_CODE, rc),
                        )
                    }
                }
                binding.root.postDelayed({ exitProcess(rc) }, 600)
            }
        }, "Minecraft-Main", 64L * 1024 * 1024).start()
    }

    private fun startGrabWatcher() {
        val watcher = object : Runnable {
            var last = false
            override fun run() {
                val now = NativeBridge.isCursorGrabbed()
                if (now != last) {
                    last = now
                    binding.surface.updatePointerCapture(now)
                }
                binding.root.postDelayed(this, 150)
            }
        }
        binding.root.post(watcher)
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        binding.surface.requestFocus()
        imm.showSoftInput(binding.surface, InputMethodManager.SHOW_FORCED)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Let volume keys keep working; everything else goes to the game.
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.source and android.view.InputDevice.SOURCE_MOUSE != 0) {
            // Right mouse button on some devices is reported as BACK.
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        return binding.surface.handleKey(event) || super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars() else binding.controls.releaseAll()
    }

    override fun onPause() {
        // Held keys/buttons would otherwise stay "pressed" in the game while it runs unseen in the background.
        binding.controls.releaseAll()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(binding.surface.windowToken, 0)
        super.onPause()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val EXTRA_LAUNCH = "launch"
        private const val QUICK_EXIT_MS = 20_000L

        fun intent(context: Context, launch: GameLaunch) =
            Intent(context, GameActivity::class.java).putExtra(EXTRA_LAUNCH, launch)

        @Suppress("DEPRECATION")
        private fun intentLaunch(intent: Intent): GameLaunch? =
            if (android.os.Build.VERSION.SDK_INT >= 33) intent.getSerializableExtra(EXTRA_LAUNCH, GameLaunch::class.java)
            else intent.getSerializableExtra(EXTRA_LAUNCH) as? GameLaunch
    }
}
