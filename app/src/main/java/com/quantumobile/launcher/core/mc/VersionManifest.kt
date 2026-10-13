package com.quantumobile.launcher.core.mc

import com.quantumobile.launcher.core.arr
import com.quantumobile.launcher.core.obj
import com.quantumobile.launcher.core.parseJsonObject
import com.quantumobile.launcher.core.str
import kotlinx.serialization.json.JsonObject

data class ManifestEntry(val id: String, val type: String, val url: String, val sha1: String?, val releaseTime: String)

class VersionManifest(val latestRelease: String?, val latestSnapshot: String?, val versions: List<ManifestEntry>) {
    fun find(id: String) = versions.firstOrNull { it.id == id }

    companion object {
        const val URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

        fun parse(text: String): VersionManifest {
            val root = parseJsonObject(text)
            val versions = root.arr("versions").orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                ManifestEntry(
                    id = o.str("id") ?: return@mapNotNull null,
                    type = o.str("type") ?: "release",
                    url = o.str("url") ?: return@mapNotNull null,
                    sha1 = o.str("sha1"),
                    releaseTime = o.str("releaseTime").orEmpty(),
                )
            }
            val latest = root.obj("latest")
            return VersionManifest(latest?.str("release"), latest?.str("snapshot"), versions)
        }
    }
}
