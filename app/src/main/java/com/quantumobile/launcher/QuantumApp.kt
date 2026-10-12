package com.quantumobile.launcher

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.quantumobile.launcher.core.LaunchPreparer
import com.quantumobile.launcher.core.LaunchStages
import com.quantumobile.launcher.core.LaunchState
import com.quantumobile.launcher.core.LauncherPaths
import com.quantumobile.launcher.core.MissingRuntimeException
import com.quantumobile.launcher.core.Settings
import com.quantumobile.launcher.core.auth.AccountStore
import com.quantumobile.launcher.core.instance.Instance
import com.quantumobile.launcher.core.instance.InstanceStore
import com.quantumobile.launcher.core.mc.VersionRepository
import com.quantumobile.launcher.core.runtime.RuntimeManager
import com.quantumobile.launcher.core.tasks.TaskRunner
import com.quantumobile.launcher.service.Notifications
import com.quantumobile.launcher.service.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class QuantumApp : Application() {
    val paths by lazy { LauncherPaths.from(this).also { it.ensureDirs() } }
    val settings by lazy { Settings(this) }
    val accounts by lazy { AccountStore(paths.accountsFile) }
    val instances by lazy { InstanceStore(paths.instancesFile) }
    val versions by lazy { VersionRepository(paths) }
    val runtimes by lazy { RuntimeManager(paths.runtimes) }

    /** Long-running work (game install, runtime and mod downloads) that must outlive any single screen. */
    val tasks by lazy {
        TaskRunner(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) { active -> if (active) TaskService.start(this) }
    }

    private val _launchState = MutableStateFlow<LaunchState>(LaunchState.Idle)
    val launchState: StateFlow<LaunchState> get() = _launchState
    private var launchJob: Job? = null

    private var startedActivities = 0
    /** True while any launcher screen is visible. */
    val isForeground get() = startedActivities > 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) { startedActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** Installs missing files and prepares the launch. Keeps running in the background; see [launchState]. */
    fun startLaunch(instance: Instance, width: Int, height: Int) {
        if (launchJob?.isActive == true) return
        _launchState.value = LaunchState.Working("Account", 0, 1)
        launchJob = tasks.launch(
            getString(R.string.preparing_game, instance.name),
            block = { reporter ->
                LaunchPreparer(this).prepare(instance, width, height) { stage, done, total ->
                    _launchState.value = LaunchState.Working(stage, done, total)
                    reporter.report(getString(LaunchStages.label(stage)), if (total > 1) done * 100 / total else -1)
                }
            },
            onResult = { result ->
                result.onSuccess { launch ->
                    if (isForeground) {
                        _launchState.value = LaunchState.Ready(launch)
                    } else {
                        // Starting an activity from the background is blocked; let the user tap a notification.
                        _launchState.value = LaunchState.Idle
                        Notifications.readyToPlay(this, launch)
                    }
                }.onFailure { e ->
                    val message = e.message ?: e.javaClass.simpleName
                    _launchState.value = LaunchState.Failed(message, (e as? MissingRuntimeException)?.required)
                    if (!isForeground) Notifications.done(this, getString(R.string.launch_failed), message)
                }
            },
        )
    }

    fun cancelLaunch() {
        launchJob?.cancel()
        _launchState.value = LaunchState.Idle
    }

    fun consumeLaunchState() {
        if (_launchState.value !is LaunchState.Working) _launchState.value = LaunchState.Idle
    }

    companion object {
        lateinit var instance: QuantumApp
            private set
    }
}
