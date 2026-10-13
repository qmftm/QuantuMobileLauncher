package com.quantumobile.launcher.core.mc

import com.quantumobile.launcher.core.bool
import com.quantumobile.launcher.core.long
import com.quantumobile.launcher.core.obj
import com.quantumobile.launcher.core.parseJsonObject
import com.quantumobile.launcher.core.str
import kotlinx.serialization.json.JsonObject

data class AssetObject(val path: String, val hash: String, val size: Long) {
    val objectPath get() = "${hash.substring(0, 2)}/$hash"
}

class AssetIndex(val objects: List<AssetObject>, val isVirtual: Boolean, val mapToResources: Boolean) {
    companion object {
        const val RESOURCES_URL = "https://resources.download.minecraft.net/"

        fun parse(text: String): AssetIndex {
            val root = parseJsonObject(text)
            val objects = root.obj("objects").orEmpty().mapNotNull { (path, value) ->
                val o = value as? JsonObject ?: return@mapNotNull null
                AssetObject(path, o.str("hash") ?: return@mapNotNull null, o.long("size") ?: -1)
            }
            return AssetIndex(objects, root.bool("virtual") == true, root.bool("map_to_resources") == true)
        }
    }
}
