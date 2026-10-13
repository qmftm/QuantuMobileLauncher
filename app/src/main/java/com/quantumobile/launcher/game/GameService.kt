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

/**
 * Keeps the `:game` process (and the JVM running in it) alive while the app is in the background, with a
 * notification to return to the game or quit it. Runs in the same process as [GameActivity].
 */
class GameService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            // The JVM owns this process; ending it is the only reliable way to stop the game.
            android.os.Process.killProcess(android.os.Process.myPid())
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

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, GameService::class.java)) }
        }
    }
}
