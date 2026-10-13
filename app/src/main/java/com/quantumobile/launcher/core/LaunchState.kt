package com.quantumobile.launcher.core

import androidx.annotation.StringRes
import com.quantumobile.launcher.R

sealed interface LaunchState {
    data object Idle : LaunchState
    data class Working(val stage: String, val done: Int, val total: Int) : LaunchState
    data class Ready(val launch: GameLaunch) : LaunchState
    data class Failed(val message: String, val missingJava: Int? = null) : LaunchState
}

object LaunchStages {
    @StringRes
    fun label(stage: String): Int = when (stage) {
        "Account" -> R.string.stage_account
        "Runtime" -> R.string.stage_runtime
        "LWJGL" -> R.string.stage_lwjgl
        "Version" -> R.string.stage_version
        "Client" -> R.string.stage_client
        "Libraries" -> R.string.stage_libraries
        "Assets" -> R.string.stage_assets
        "Starting", "Done" -> R.string.stage_starting
        else -> R.string.preparing
    }
}
