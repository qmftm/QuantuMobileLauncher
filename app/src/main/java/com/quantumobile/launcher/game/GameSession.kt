package com.quantumobile.launcher.game

import com.quantumobile.launcher.core.GameLaunch

/** State of the single game session in the `:game` process. */
object GameSession {
    @Volatile var launch: GameLaunch? = null
    @Volatile var started = false
}
