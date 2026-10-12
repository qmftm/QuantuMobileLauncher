package com.quantumobile.launcher.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.quantumobile.launcher.QuantumApp
import com.quantumobile.launcher.core.GameLaunch
import com.quantumobile.launcher.core.LaunchPreparer
import com.quantumobile.launcher.core.MissingRuntimeException
import com.quantumobile.launcher.core.instance.Instance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface LaunchState {
    data object Idle : LaunchState
    data class Working(val stage: String, val done: Int, val total: Int) : LaunchState
    data class Ready(val launch: GameLaunch) : LaunchState
    data class Failed(val message: String, val missingJava: Int? = null) : LaunchState
}

class LaunchViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<LaunchState>(LaunchState.Idle)
    val state: StateFlow<LaunchState> get() = _state
    private var job: Job? = null

    fun start(instance: Instance, width: Int, height: Int) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            try {
                val launch = LaunchPreparer(getApplication<QuantumApp>()).prepare(instance, width, height) { stage, done, total ->
                    _state.value = LaunchState.Working(stage, done, total)
                }
                _state.value = LaunchState.Ready(launch)
            } catch (e: CancellationException) {
                _state.value = LaunchState.Idle
                throw e
            } catch (e: MissingRuntimeException) {
                _state.value = LaunchState.Failed(e.message.orEmpty(), e.required)
            } catch (e: Exception) {
                _state.value = LaunchState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun consumed() {
        _state.value = LaunchState.Idle
    }
}
