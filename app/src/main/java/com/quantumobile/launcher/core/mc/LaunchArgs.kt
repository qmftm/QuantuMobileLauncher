package com.quantumobile.launcher.core.mc

import com.quantumobile.launcher.core.arr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Everything needed to turn a resolved version into a Java command line. */
data class LaunchSpec(
    val version: VersionInfo,
    val playerName: String,
    val playerUuid: String,
    val accessToken: String,
    val userType: String,
    val xuid: String,
    val gameDir: String,
    val assetsRoot: String,
    val librariesDir: String,
    val nativesDir: String,
    val classpath: List<String>,
    val width: Int,
    val height: Int,
    val memoryMb: Int,
    val launcherName: String,
    val launcherVersion: String,
    val extraJvmArgs: List<String> = emptyList(),
    val loggingConfigPath: String? = null,
)

object LaunchArgs {
    private val PLACEHOLDER = Regex("""\$\{([^}]+)}""")

    fun substitute(value: String, vars: Map<String, String>): String =
        PLACEHOLDER.replace(value) { m -> vars[m.groupValues[1]] ?: m.value }

    /** Expands a modern `arguments.game`/`arguments.jvm` array, honouring per-entry rules. */
    fun expand(args: JsonArray?, env: RuleEnv, vars: Map<String, String>): List<String> {
        val out = ArrayList<String>()
        args?.forEach { element ->
            when (element) {
                is JsonPrimitive -> out += substitute(element.content, vars)
                is JsonObject -> if (Rules.isAllowed(element.arr("rules"), env)) {
                    when (val value = element["value"]) {
                        is JsonPrimitive -> out += substitute(value.content, vars)
                        is JsonArray -> value.forEach { out += substitute((it as JsonPrimitive).content, vars) }
                        else -> Unit
                    }
                }
                else -> Unit
            }
        }
        return out
    }

    fun variables(spec: LaunchSpec): Map<String, String> {
        val v = spec.version
        val assetsDir = if (v.assets == "legacy" || v.assets == "pre-1.6") "${spec.assetsRoot}/virtual/${v.assets}" else spec.assetsRoot
        return mapOf(
            "auth_player_name" to spec.playerName,
            "version_name" to v.id,
            "game_directory" to spec.gameDir,
            "assets_root" to spec.assetsRoot,
            "game_assets" to assetsDir,
            "assets_index_name" to v.assets,
            "auth_uuid" to spec.playerUuid,
            "auth_access_token" to spec.accessToken,
            "auth_session" to "token:${spec.accessToken}:${spec.playerUuid}",
            "auth_xuid" to spec.xuid,
            "clientid" to "",
            "user_type" to spec.userType,
            "user_properties" to "{}",
            "version_type" to v.type,
            "natives_directory" to spec.nativesDir,
            "launcher_name" to spec.launcherName,
            "launcher_version" to spec.launcherVersion,
            "classpath" to spec.classpath.joinToString(":"),
            "classpath_separator" to ":",
            "library_directory" to spec.librariesDir,
            "resolution_width" to spec.width.toString(),
            "resolution_height" to spec.height.toString(),
        )
    }

    /** Full argument list after the `java` executable: JVM options, main class, game options. */
    fun build(spec: LaunchSpec): List<String> {
        val v = spec.version
        val vars = variables(spec)
        val env = RuleEnv(features = mapOf("has_custom_resolution" to true))

        val jvm = ArrayList<String>()
        jvm += "-Xms${minOf(512, spec.memoryMb)}M"
        jvm += "-Xmx${spec.memoryMb}M"
        jvm += spec.extraJvmArgs
        spec.loggingConfigPath?.let { path -> v.logging?.let { jvm += substitute(it.argument, mapOf("path" to path)) } }
        jvm += "-Dlog4j2.formatMsgNoLookups=true"

        if (v.jvmArguments != null) {
            jvm += expand(v.jvmArguments, env, vars)
        } else {
            jvm += "-Djava.library.path=${spec.nativesDir}"
            jvm += "-cp"
            jvm += vars.getValue("classpath")
        }

        val game = if (v.gameArguments != null) {
            expand(v.gameArguments, env, vars)
        } else {
            v.legacyArguments.orEmpty().split(' ').filter { it.isNotBlank() }.map { substitute(it, vars) } +
                listOf("--width", spec.width.toString(), "--height", spec.height.toString())
        }
        return jvm + v.mainClass + game
    }
}
