package com.quantumobile.launcher.core.mc

import com.quantumobile.launcher.core.arr
import com.quantumobile.launcher.core.int
import com.quantumobile.launcher.core.long
import com.quantumobile.launcher.core.obj
import com.quantumobile.launcher.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class AssetIndexRef(val id: String, val url: String, val sha1: String?, val size: Long)
data class LoggingRef(val argument: String, val id: String, val url: String, val sha1: String?, val size: Long)

/**
 * A fully resolved version (after `inheritsFrom` merging). [jarId] names the version whose client jar is
 * used — for mod loader profiles this is the vanilla parent.
 */
class VersionInfo(val id: String, val json: JsonObject, val jarId: String) {
    val type: String get() = json.str("type") ?: "release"
    val mainClass: String get() = json.str("mainClass") ?: error("Version $id has no mainClass")

    val assetIndex: AssetIndexRef?
        get() = json.obj("assetIndex")?.let {
            AssetIndexRef(it.str("id") ?: return null, it.str("url") ?: return null, it.str("sha1"), it.long("size") ?: -1)
        }

    val assets: String get() = json.str("assets") ?: assetIndex?.id ?: "legacy"

    val clientDownload: Artifact? get() = json.obj("downloads")?.obj("client")?.let(Artifact::parse)

    /** Major Java version required; very old manifests omit it and run on Java 8. */
    val javaMajorVersion: Int get() = json.obj("javaVersion")?.int("majorVersion") ?: 8

    val libraries: List<Library> get() = json.arr("libraries").orEmpty().map { Library.parse(it as JsonObject) }

    val gameArguments: JsonArray? get() = json.obj("arguments")?.arr("game")
    val jvmArguments: JsonArray? get() = json.obj("arguments")?.arr("jvm")
    val legacyArguments: String? get() = json.str("minecraftArguments")

    val logging: LoggingRef?
        get() {
            val client = json.obj("logging")?.obj("client") ?: return null
            val file = client.obj("file") ?: return null
            return LoggingRef(
                argument = client.str("argument") ?: return null,
                id = file.str("id") ?: return null,
                url = file.str("url") ?: return null,
                sha1 = file.str("sha1"),
                size = file.long("size") ?: -1,
            )
        }

    companion object {
        fun parse(json: JsonObject): VersionInfo {
            val id = json.str("id") ?: error("Version JSON without id")
            return VersionInfo(id, json, json.str("jar") ?: id)
        }

        /** Merges a child profile (e.g. Fabric) onto its resolved parent, following launcher semantics. */
        fun merge(child: JsonObject, parent: VersionInfo): VersionInfo {
            val merged = parent.json.toMutableMap()
            for ((key, value) in child) {
                when (key) {
                    "inheritsFrom" -> Unit
                    // Child libraries first so they win classpath de-duplication.
                    "libraries" -> merged[key] = JsonArray((value as JsonArray) + parent.json.arr("libraries").orEmpty())
                    "arguments" -> {
                        val parentArgs = parent.json.obj("arguments")
                        val childArgs = value as JsonObject
                        val keys = (parentArgs?.keys.orEmpty() + childArgs.keys)
                        merged[key] = JsonObject(keys.associateWith { k ->
                            JsonArray(parentArgs?.arr(k).orEmpty() + childArgs.arr(k).orEmpty())
                        })
                    }
                    else -> merged[key] = value
                }
            }
            val childId = child.str("id") ?: error("Version JSON without id")
            val jarId = child.str("jar") ?: if (child.obj("downloads")?.obj("client") != null) childId else parent.jarId
            return VersionInfo(childId, JsonObject(merged), jarId)
        }
    }
}
