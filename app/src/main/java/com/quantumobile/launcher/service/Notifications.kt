package com.quantumobile.launcher.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.quantumobile.launcher.R
import com.quantumobile.launcher.core.GameLaunch
import com.quantumobile.launcher.core.tasks.TaskInfo
import com.quantumobile.launcher.game.GameActivity
import com.quantumobile.launcher.ui.MainActivity
import java.util.concurrent.atomic.AtomicInteger

object Notifications {
    const val CHANNEL_TASKS = "tasks"
    const val CHANNEL_GAME = "game"
    const val CHANNEL_DONE = "done"
    const val ID_TASKS = 1
    const val ID_GAME = 2
    private val nextDoneId = AtomicInteger(100)

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_TASKS, context.getString(R.string.channel_tasks), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_GAME, context.getString(R.string.channel_game), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, context.getString(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Ongoing notification shown while installs and downloads run. */
    fun tasks(context: Context, tasks: List<TaskInfo>): Notification {
        val first = tasks.firstOrNull()
        val title = when {
            first == null -> context.getString(R.string.app_name)
            tasks.size > 1 -> context.getString(R.string.tasks_running, tasks.size)
            else -> first.title
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_TASKS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(first?.message?.takeIf { it.isNotEmpty() && tasks.size == 1 })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
        val percent = if (tasks.size == 1) first!!.percent else -1
        if (percent >= 0) builder.setProgress(100, percent, false) else builder.setProgress(0, 0, true)
        return builder.build()
    }

    /** One-off notification for work that finished while the app was in the background. */
    fun done(context: Context, title: String, text: String?, intent: PendingIntent? = null) {
        if (!canNotify(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(intent ?: openApp(context))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(nextDoneId.getAndIncrement(), n)
    }

    /** "Ready to play": tapping starts the game directly with the prepared launch. */
    fun readyToPlay(context: Context, launch: GameLaunch) {
        val intent = PendingIntent.getActivity(
            context, 1, GameActivity.intent(context, launch),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        done(context, context.getString(R.string.ready_to_play), context.getString(R.string.tap_to_play), intent)
    }

    fun canNotify(context: Context) =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
