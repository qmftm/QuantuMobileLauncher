package com.quantumobile.launcher.core.runtime

import android.content.Context
import com.quantumobile.launcher.core.arr
import com.quantumobile.launcher.core.int
import com.quantumobile.launcher.core.long
import com.quantumobile.launcher.core.net.Http
import com.quantumobile.launcher.core.parseJsonObject
import com.quantumobile.launcher.core.str
import kotlinx.serialization.json.JsonObject

/** One archive of a runtime. Compression is derived from the file extension. */
data class RuntimePart(val url: String, val sha256: String? = null, val size: Long = -1) {
    private val path get() = url.substringBefore('?').substringBefore('#')
    val fileName: String get() = path.substringAfterLast('/').ifEmpty { "jre" }
    val compression: String
        get() = when {
            path.endsWith(".xz") -> "xz"
            path.endsWith(".gz") || path.endsWith(".tgz") -> "gz"
            else -> "tar"
        }
}

/**
 * A downloadable Android-built JRE. Runtimes are often published as several archives (a platform-independent
 * "universal" part plus one per CPU); all [parts] are extracted into the same directory.
 */
data class RuntimeSource(
    val name: String,
    val java: Int,
    /** Android ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`); null means any. */
    val abi: String?,
    val parts: List<RuntimePart>,
    val sizeMb: Int? = null,
) {
    constructor(name: String, java: Int, abi: String?, url: String, sha256: String? = null, sizeMb: Int? = null) :
        this(name, java, abi, listOf(RuntimePart(url, sha256)), sizeMb)

    val url: String get() = parts.first().url
    val sha256: String? get() = parts.first().sha256
    val fileName: String get() = parts.first().fileName
    val compression: String get() = parts.first().compression

    /** Directory name for the installed runtime, e.g. `jre17-arm64-v8a`; custom links use the archive name. */
    val installName: String get() = if (java > 0) "jre$java" + (abi?.let { "-$it" } ?: "") else fileName

    companion object {
        /** Builds a source from a pasted link; the Java version is read from the archive after install. */
        fun fromUrl(url: String) = RuntimeSource(name = "custom", java = 0, abi = null, url = url.trim())
    }
}

/** JSON index of runtimes; see `app/src/main/assets/runtimes.json` for the bundled default. */
object RuntimeCatalog {
    const val ASSET = "runtimes.json"

    fun parse(text: String): List<RuntimeSource> =
        parseJsonObject(text).arr("runtimes").orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val parts = parseParts(o) ?: return@mapNotNull null
            RuntimeSource(
                name = o.str("name") ?: parts.first().fileName,
                java = o.int("java") ?: return@mapNotNull null,
                abi = o.str("abi"),
                parts = parts,
                sizeMb = o.int("sizeMb"),
            )
        }

    /** `parts` array, or a single `url`/`sha256`. Anything that is not https makes the entry unusable. */
    private fun parseParts(o: JsonObject): List<RuntimePart>? {
        val array = o.arr("parts")
        val raw = if (array != null) array.map { it as? JsonObject } else listOf(o)
        if (raw.isEmpty()) return null
        return raw.map { po ->
            val url = po?.str("url")?.takeIf { it.startsWith("https://") } ?: return null
            RuntimePart(url, po.str("sha256")?.lowercase(), po.long("size") ?: -1)
        }
    }

    /** Entries usable on this device (matching ABI, in the device's preference order), newest Java first. */
    fun forDevice(all: List<RuntimeSource>, supportedAbis: List<String>): List<RuntimeSource> =
        all.filter { it.abi == null || it.abi in supportedAbis }
            .sortedWith(compareBy<RuntimeSource> { it.abi?.let(supportedAbis::indexOf) ?: Int.MAX_VALUE }.thenByDescending { it.java })

    /** Exact Java match first, otherwise the lowest newer version (a newer JRE runs older Minecraft versions). */
    fun best(sources: List<RuntimeSource>, required: Int): RuntimeSource? =
        sources.filter { it.java == required }.firstOrNull()
            ?: sources.filter { it.java > required }.minByOrNull { it.java }

    fun loadBundled(context: Context): List<RuntimeSource> =
        parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })

    /** The bundled catalog, or the one at [customUrl] when the user has set one. */
    suspend fun load(context: Context, customUrl: String): List<RuntimeSource> =
        if (customUrl.isBlank()) loadBundled(context) else parse(Http.getString(customUrl.trim()))
}
