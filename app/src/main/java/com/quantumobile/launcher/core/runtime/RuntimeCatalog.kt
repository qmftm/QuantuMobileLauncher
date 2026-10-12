package com.quantumobile.launcher.core.runtime

import com.quantumobile.launcher.core.arr
import com.quantumobile.launcher.core.int
import com.quantumobile.launcher.core.net.Http
import com.quantumobile.launcher.core.parseJsonObject
import com.quantumobile.launcher.core.str
import kotlinx.serialization.json.JsonObject

/** A downloadable Android-built JRE archive. */
data class RuntimeSource(
    val name: String,
    val java: Int,
    /** Android ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`); null means any. */
    val abi: String?,
    val url: String,
    val sha256: String? = null,
    val sizeMb: Int? = null,
) {
    private val path get() = url.substringBefore('?').substringBefore('#')

    /** Last URL segment, e.g. `jre25-arm64.tar.xz`; used to name the installed runtime directory. */
    val fileName: String get() = path.substringAfterLast('/').ifEmpty { "jre" }

    val compression: String
        get() = when {
            path.endsWith(".xz") -> "xz"
            path.endsWith(".gz") || path.endsWith(".tgz") -> "gz"
            else -> "tar"
        }

    companion object {
        /** Builds a source from a pasted link; the Java version is read from the archive after install. */
        fun fromUrl(url: String) = RuntimeSource(name = "custom", java = 0, abi = null, url = url.trim())
    }
}

/**
 * JSON index of runtimes:
 * `{"runtimes":[{"name":"Temurin 25","java":25,"abi":"arm64-v8a","url":"https://…tar.xz","sha256":"…","sizeMb":60}]}`
 */
object RuntimeCatalog {
    const val DEFAULT_URL = "https://raw.githubusercontent.com/qmftm/QuantuMobileLauncher/main/runtimes.json"

    fun parse(text: String): List<RuntimeSource> =
        parseJsonObject(text).arr("runtimes").orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val url = o.str("url")?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            RuntimeSource(
                name = o.str("name") ?: url.substringAfterLast('/'),
                java = o.int("java") ?: return@mapNotNull null,
                abi = o.str("abi"),
                url = url,
                sha256 = o.str("sha256")?.lowercase(),
                sizeMb = o.int("sizeMb"),
            )
        }

    /** Entries usable on this device (matching ABI, in the device's preference order), newest Java first. */
    fun forDevice(all: List<RuntimeSource>, supportedAbis: List<String>): List<RuntimeSource> =
        all.filter { it.abi == null || it.abi in supportedAbis }
            .sortedWith(compareBy<RuntimeSource> { it.abi?.let(supportedAbis::indexOf) ?: Int.MAX_VALUE }.thenByDescending { it.java })

    suspend fun fetch(url: String = DEFAULT_URL): List<RuntimeSource> = parse(Http.getString(url))
}
