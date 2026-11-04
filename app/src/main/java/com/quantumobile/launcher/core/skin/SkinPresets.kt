package com.quantumobile.launcher.core.skin

/**
 * Ready-made choices. Nothing is bundled: a preset is a player's public skin or cape that Mojang serves, fetched when
 * it is picked and then kept in the library. The `MHF_` accounts are Mojang's own accounts for the game's characters.
 */
data class SkinPreset(val kind: SkinKind, val label: String, val player: String)

object SkinPresets {
    val skins = listOf(
        SkinPreset(SkinKind.SKIN, "스티브", "MHF_Steve"),
        SkinPreset(SkinKind.SKIN, "알렉스", "MHF_Alex"),
        SkinPreset(SkinKind.SKIN, "크리퍼", "MHF_Creeper"),
        SkinPreset(SkinKind.SKIN, "좀비", "MHF_Zombie"),
        SkinPreset(SkinKind.SKIN, "스켈레톤", "MHF_Skeleton"),
        SkinPreset(SkinKind.SKIN, "엔더맨", "MHF_Enderman"),
        SkinPreset(SkinKind.SKIN, "주민", "MHF_Villager"),
        SkinPreset(SkinKind.SKIN, "아이언 골렘", "MHF_Golem"),
        SkinPreset(SkinKind.SKIN, "블레이즈", "MHF_Blaze"),
        SkinPreset(SkinKind.SKIN, "헤로브라인", "MHF_Herobrine"),
        SkinPreset(SkinKind.SKIN, "노치", "Notch"),
        SkinPreset(SkinKind.SKIN, "jeb_", "jeb_"),
        SkinPreset(SkinKind.SKIN, "Dinnerbone", "Dinnerbone"),
    )

    val capes = listOf(
        SkinPreset(SkinKind.CAPE, "jeb_의 망토", "jeb_"),
        SkinPreset(SkinKind.CAPE, "Dinnerbone의 망토", "Dinnerbone"),
    )
}
