package com.quantumobile.launcher.core.mc

import com.quantumobile.launcher.core.net.Http
import com.quantumobile.launcher.core.obj
import com.quantumobile.launcher.core.parseJsonObject
import com.quantumobile.launcher.core.str
import com.quantumobile.launcher.core.bool
import com.quantumobile.launcher.core.LauncherJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Installs Fabric loader profiles using the official Fabric meta API. */
class FabricInstaller(private val versions: VersionRepository) {

    suspend fun latestStableLoader(): String {
        val list = LauncherJson.parseToJsonElement(Http.getString("$META/versions/loader")) as JsonArray
        val loaders = list.mapNotNull { it as? JsonObject }
        val chosen = loaders.firstOrNull { it.bool("stable") == true } ?: loaders.first()
        return chosen.str("version") ?: error("Malformed Fabric loader list")
    }

    /** Writes the Fabric profile JSON and returns the new version id. */
    suspend fun install(gameVersion: String, loaderVersion: String? = null): String {
        val loader = loaderVersion ?: latestStableLoader()
        val text = Http.getString("$META/versions/loader/$gameVersion/$loader/profile/json")
        val id = parseJsonObject(text).str("id") ?: error("Malformed Fabric profile")
        versions.saveProfile(id, text)
        return id
    }

    companion object {
        const val META = "https://meta.fabricmc.net/v2"
    }
}
