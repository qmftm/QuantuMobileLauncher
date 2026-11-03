package com.quantumobile.launcher.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.quantumobile.launcher.QuantumApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the launcher process (where the AFK bots run) alive while at least one server's switch is on: a foreground
 * service with a per-server status notification, plus a partial wake lock and a Wi-Fi lock so the connections do
 * not stall with the screen off. Both locks are held only while the service runs.
 */
class AfkService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as QuantumApp
        val afk = app.afk
        if (intent?.action == ACTION_STOP_ALL) {
            afk.stopAll()
            shutdown()
            return START_NOT_STICKY
        }
        afk.start() // also covers a restart by the system after the process was killed
        val servers = afk.servers.value
        Notifications.ensureChannels(this)
        // startForeground must follow startForegroundService promptly, even if we stop right away.
        promote(Notifications.afk(this, servers, afk.statuses.value))
        if (servers.none { it.enabled }) {
            shutdown()
            return START_NOT_STICKY
        }
        acquireLocks()
        if (observer == null) {
            observer = scope.launch {
                combine(afk.servers, afk.statuses) { s, st -> s to st }.distinctUntilChanged().collect { (list, statuses) ->
                    if (list.none { it.enabled }) shutdown()
                    else promote(Notifications.afk(this@AfkService, list, statuses))
                }
            }
        }
        // Sticky: if Android kills the process, it restarts this service and the bots reconnect.
        return START_STICKY
    }

    private fun promote(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, Notifications.ID_AFK, notification, type)
            foreground = true
        } catch (e: RuntimeException) {
            // Refused (e.g. started from the background on a strict OS build): the bots keep running while the process
            // lives, just without this service. A denied notification permission does not land here; the service
            // still runs, its notification is simply hidden.
            foreground = false
            stopSelf()
        }
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuantuMobile:afk")
                .apply { setReferenceCounted(false); acquire(WAKE_LOCK_MS) }
            // A timed lock is cleaned up by the OS if this process dies; renew it while the service is alive.
            scope.launch {
                while (true) {
                    delay(WAKE_LOCK_RENEW_MS)
                    wakeLock?.acquire(WAKE_LOCK_MS)
                }
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = runCatching { wm.createWifiLock(mode, "QuantuMobile:afk").apply { setReferenceCounted(false); acquire() } }.getOrNull()
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    private fun shutdown() {
        releaseLocks()
        if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP_ALL = "com.quantumobile.launcher.AFK_STOP_ALL"
        private const val WAKE_LOCK_MS = 2 * 60 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 60 * 60 * 1000L

        /** Best effort: if the OS refuses a background start, the bots still run while the app process lives. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, AfkService::class.java)) }
        }
    }
}
