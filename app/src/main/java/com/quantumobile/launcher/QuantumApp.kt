package com.quantumobile.launcher

import android.app.Application
import com.quantumobile.launcher.core.LauncherPaths
import com.quantumobile.launcher.core.Settings
import com.quantumobile.launcher.core.auth.AccountStore
import com.quantumobile.launcher.core.instance.InstanceStore
import com.quantumobile.launcher.core.mc.VersionRepository
import com.quantumobile.launcher.core.runtime.RuntimeManager

class QuantumApp : Application() {
    val paths by lazy { LauncherPaths.from(this).also { it.ensureDirs() } }
    val settings by lazy { Settings(this) }
    val accounts by lazy { AccountStore(paths.accountsFile) }
    val instances by lazy { InstanceStore(paths.instancesFile) }
    val versions by lazy { VersionRepository(paths) }
    val runtimes by lazy { RuntimeManager(paths.runtimes) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: QuantumApp
            private set
    }
}
