package com.quantumobile.launcher.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.quantumobile.launcher.QuantumApp
import com.quantumobile.launcher.core.LaunchState
import com.quantumobile.launcher.core.instance.Instance
import kotlinx.coroutines.flow.StateFlow

/** Thin view of [QuantumApp]'s launch state; the work itself lives in the app so it survives screens closing. */
class LaunchViewModel(app: Application) : AndroidViewModel(app) {
    private val quantum get() = getApplication<QuantumApp>()

    val state: StateFlow<LaunchState> get() = quantum.launchState

    fun start(instance: Instance, width: Int, height: Int) = quantum.startLaunch(instance, width, height)
    fun cancel() = quantum.cancelLaunch()
    fun consumed() = quantum.consumeLaunchState()
}
