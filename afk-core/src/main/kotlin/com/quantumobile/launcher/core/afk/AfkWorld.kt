package com.quantumobile.launcher.core.afk

import kotlinx.serialization.Serializable

/** What the bot knows about its surroundings, for the status panel. Null = not known (yet). */
@Serializable
data class AfkWorldInfo(
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    /** e.g. "minecraft:overworld". */
    val dimension: String? = null,
    /** 0 survival, 1 creative, 2 adventure, 3 spectator. */
    val gameMode: Int? = null,
    val health: Float? = null,
    val food: Int? = null,
    /** Time of day in ticks, 0–23999 (0 = 6:00). */
    val dayTime: Long? = null,
    /** Names in the server's player list (tab). */
    val players: List<String> = emptyList(),
    /** When this was last updated (epoch ms). */
    val updatedAt: Long = 0,
)
