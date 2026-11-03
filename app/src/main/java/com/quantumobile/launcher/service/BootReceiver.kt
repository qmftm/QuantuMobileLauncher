package com.quantumobile.launcher.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.quantumobile.launcher.QuantumApp

/** After a reboot (or an app update), turns the AFK bot back on for servers whose switch was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as QuantumApp
        if (!app.settings.afkAutoStart) return
        if (app.afk.servers.value.none { it.enabled }) return
        app.startAfk()
    }
}
