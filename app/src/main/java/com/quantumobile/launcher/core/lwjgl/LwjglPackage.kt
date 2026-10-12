package com.quantumobile.launcher.core.lwjgl

import android.content.Context
import com.quantumobile.launcher.core.arr
import com.quantumobile.launcher.core.long
import com.quantumobile.launcher.core.obj
import com.quantumobile.launcher.core.parseJsonObject
import com.quantumobile.launcher.core.str
import kotlinx.serialization.json.JsonObject

data class LwjglFile(val name: String, val url: String, val sha256: String, val size: Long)

/**
 * The Android build of LWJGL 3 the game runs on instead of Mojang's desktop one: a merged jar with an Android GLFW
 * backend, plus native libraries per ABI. Described by the bundled `lwjgl.json`.
 */
data class LwjglPackage(val jar: LwjglFile, val natives: Map<String, List<LwjglFile>>) {
    /** First of the device's ABIs (in preference order) that this package has natives for. */
    fun abiFor(supportedAbis: List<String>): String? = supportedAbis.firstOrNull { it in natives }

    fun totalBytes(abi: String): Long = jar.size + natives[abi].orEmpty().sumOf { it.size }

    companion object {
        const val ASSET = "lwjgl.json"

        fun parse(text: String): LwjglPackage {
            val root = parseJsonObject(text)
            val jar = file(root.obj("jar") ?: error("lwjgl.json has no jar"))
            val natives = root.obj("natives").orEmpty().mapValues { (_, files) ->
                (files as kotlinx.serialization.json.JsonArray).map { file(it as JsonObject) }
            }
            return LwjglPackage(jar, natives)
        }

        private fun file(o: JsonObject): LwjglFile {
            val url = o.str("url")?.takeIf { it.startsWith("https://") } ?: error("LWJGL file needs an https url")
            val sha = o.str("sha256")?.lowercase()?.takeIf { it.length == 64 } ?: error("LWJGL file needs a sha256")
            return LwjglFile(o.str("name") ?: url.substringAfterLast('/'), url, sha, o.long("size") ?: -1)
        }

        fun loadBundled(context: Context): LwjglPackage =
            parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })
    }
}
