package com.quantumobile.launcher.core.mc

import com.quantumobile.launcher.core.arr
import com.quantumobile.launcher.core.long
import com.quantumobile.launcher.core.obj
import com.quantumobile.launcher.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class Artifact(val path: String?, val url: String, val sha1: String?, val size: Long) {
    companion object {
        fun parse(o: JsonObject) = Artifact(
            path = o.str("path"),
            url = o.str("url").orEmpty(),
            sha1 = o.str("sha1"),
            size = o.long("size") ?: -1,
        )
    }
}

/** Maven coordinate `group:artifact:version[:classifier][@extension]`. */
data class MavenCoords(
    val group: String,
    val artifact: String,
    val version: String,
    val classifier: String?,
    val extension: String,
) {
    /** Identity used to de-duplicate libraries (same artifact in different versions). */
    val key get() = listOfNotNull(group, artifact, classifier).joinToString(":")

    val path: String
        get() = buildString {
            append(group.replace('.', '/')).append('/').append(artifact).append('/').append(version).append('/')
            append(artifact).append('-').append(version)
            if (classifier != null) append('-').append(classifier)
            append('.').append(extension)
        }

    companion object {
        fun parse(name: String): MavenCoords {
            val (coords, ext) = name.split('@', limit = 2).let { it[0] to (it.getOrNull(1) ?: "jar") }
            val parts = coords.split(':')
            require(parts.size >= 3) { "Invalid maven coordinate: $name" }
            return MavenCoords(parts[0], parts[1], parts[2], parts.getOrNull(3), ext)
        }
    }
}

data class Library(
    val name: String,
    val coords: MavenCoords,
    val artifact: Artifact?,
    val mavenRepo: String?,
    val rules: JsonArray?,
    /** Old-style entries that only carry per-OS native classifiers. */
    val nativesOnly: Boolean,
) {
    val path: String get() = artifact?.path ?: coords.path

    /** True for desktop native jars (`natives-linux` etc.). They are x86/glibc builds and useless on Android. */
    val isDesktopNative: Boolean get() = nativesOnly || coords.classifier?.startsWith("natives") == true

    val downloadUrl: String
        get() = artifact?.url?.takeIf { it.isNotEmpty() }
            ?: ((mavenRepo ?: DEFAULT_REPO).trimEnd('/') + "/" + coords.path)

    companion object {
        const val DEFAULT_REPO = "https://libraries.minecraft.net/"

        fun parse(o: JsonObject): Library {
            val name = o.str("name") ?: error("Library without name")
            val artifact = o.obj("downloads")?.obj("artifact")?.let(Artifact::parse)
            val hasNatives = o.obj("natives") != null
            return Library(
                name = name,
                coords = MavenCoords.parse(name),
                artifact = artifact,
                mavenRepo = o.str("url"),
                rules = o.arr("rules"),
                nativesOnly = hasNatives && artifact == null,
            )
        }
    }
}
