package com.quantumobile.launcher.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.quantumobile.launcher.QuantumApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the launcher process alive (and shows progress) while [QuantumApp.tasks] has
 * work, so installs and downloads continue after the user leaves the app or turns the screen off.
 */
class TaskService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as QuantumApp
        Notifications.ensureChannels(this)
        // startForeground must be called promptly after startForegroundService.
        promote(Notifications.tasks(this, app.tasks.tasks.value))
        if (observer == null) {
            observer = scope.launch {
                app.tasks.tasks.collect { tasks ->
                    if (tasks.isEmpty()) {
                        ServiceCompat.stopForeground(this@TaskService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        promote(Notifications.tasks(this@TaskService, tasks))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun promote(notification: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, Notifications.ID_TASKS, notification, type)
        } catch (e: RuntimeException) {
            // The OS may refuse (e.g. started from the background); the work itself runs on without the service.
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Best effort: if the OS refuses a background start the work simply runs without the service. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TaskService::class.java)) }
        }
    }
}
