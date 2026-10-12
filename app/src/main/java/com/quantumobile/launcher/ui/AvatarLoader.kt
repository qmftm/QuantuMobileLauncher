package com.quantumobile.launcher.ui

import android.widget.ImageView
import androidx.lifecycle.LifecycleCoroutineScope
import com.quantumobile.launcher.R
import com.quantumobile.launcher.core.auth.Account
import com.quantumobile.launcher.core.auth.AccountType

/** Minecraft skin heads from mc-heads.net. */
object AvatarLoader {
    fun load(view: ImageView, account: Account?, scope: LifecycleCoroutineScope) {
        val key = when {
            account == null -> null
            account.type == AccountType.MICROSOFT -> account.uuid
            else -> "MHF_Steve" // offline players have no skin of their own
        }
        RemoteImage.load(view, key?.let { "https://mc-heads.net/avatar/$it/128" }, scope, R.drawable.ic_person)
    }
}
