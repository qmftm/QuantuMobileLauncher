package com.quantumobile.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Battery optimisation exemption, so Android does not cut the AFK bot's connections in Doze. */
object BatteryOptimization {
    fun isIgnoring(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /**
     * Asks directly ("Allow QuantuMobile to always run in the background?"); falls back to the system list.
     * The direct request is allowed here because keeping a server connection open for hours is this feature's purpose.
     */
    @SuppressLint("BatteryLife")
    fun request(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        val ok = runCatching { context.startActivity(direct) }.isSuccess
        if (!ok) runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}
