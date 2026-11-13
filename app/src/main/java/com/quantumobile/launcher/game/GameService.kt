package com.quantumobile.launcher.game

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.quantumobile.launcher.R
import com.quantumobile.launcher.service.Notifications
import org.libsdl.app.SDLActivity

/**
 * Keeps the `:game` process (and the JVM running in it) alive while the app is in the background, with a
 * notification to return to the game or quit it. Runs in the same process as [GameActivity].
 */
class GameService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * The user swiped the launcher/game card away in the recents screen: that means "close it", so the game must not
     * keep running unseen in the background (Home still does, on purpose). The JVM owns this process, so end it.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        quitGame(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) {
            quitGame(this)
            return START_NOT_STICKY
        }
        Notifications.ensureChannels(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, if (GameSession.launch?.sdl == true) Sdl3Activity::class.java else GameActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val quit = PendingIntent.getService(
            this, 1, Intent(this, GameService::class.java).setAction(ACTION_QUIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_GAME)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.game_running))
            .setContentText(getString(R.string.tap_to_return))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.quit_game), quit)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, Notifications.ID_GAME, notification, type)
        } catch (e: RuntimeException) {
            // Not fatal: the game still runs, it just may be stopped sooner when the app is in the background.
            stopSelf()
            return START_NOT_STICKY
        }

        if (wakeLock == null) {
            // Keeps the CPU running with the screen off so the server connection does not stall.
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuantuMobile:game").apply { setReferenceCounted(false); acquire(WAKE_LOCK_MAX_MS) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_QUIT = "com.quantumobile.launcher.QUIT_GAME"

        /** Safety cap so a stuck session cannot drain the battery forever (a long play session is well under this). */
        private const val WAKE_LOCK_MAX_MS = 12 * 60 * 60 * 1000L

        /** How long the game gets to save and exit by itself before the process is ended. */
        private const val QUIT_GRACE_MS = 5_000L
        @Volatile private var quitting = false

        /**
         * Ends the game and everything that keeps it in the background (notification, wake lock, process). Used when the
         * game exits, when its card is swiped away in the recents screen, and by the notification's Quit button.
         * [askGame]: a running 26.x game is first sent the window-close event so a singleplayer world is saved, and gets
         * [graceMs] to exit by itself. The JVM owns this process, so ending the process is the only way to be sure.
         */
        fun quitGame(context: Context, askGame: Boolean = true, graceMs: Long = QUIT_GRACE_MS) {
            val app = context.applicationContext
            runCatching { androidx.core.app.NotificationManagerCompat.from(app).cancel(Notifications.ID_GAME) }
            runCatching { app.stopService(Intent(app, GameService::class.java)) }
            if (quitting) return
            quitting = true
            val sdlRunning = GameSession.started && GameSession.launch?.sdl == true
            val asked = askGame && sdlRunning && runCatching { SDLActivity.nativeSendQuit() }.isSuccess
            val kill = Runnable { android.os.Process.killProcess(android.os.Process.myPid()) }
            // (askGame = false with a grace period: the caller already sent the quit event.)
            if (graceMs > 0 && sdlRunning && (asked || !askGame)) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(kill, graceMs)
            } else kill.run()
        }

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, GameService::class.java)) }
        }
    }
}
