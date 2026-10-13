package com.quantumobile.launcher.core.mods

import com.quantumobile.launcher.core.arr
import com.quantumobile.launcher.core.bool
import com.quantumobile.launcher.core.int
import com.quantumobile.launcher.core.long
import com.quantumobile.launcher.core.net.Http
import com.quantumobile.launcher.core.obj
import com.quantumobile.launcher.core.parseJsonObject
import com.quantumobile.launcher.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

class ModException(message: String) : IOException(message)

enum class ProjectType(val api: String, val folder: String, val usesLoader: Boolean) {
    MOD("mod", "mods", true),
    RESOURCEPACK("resourcepack", "resourcepacks", false),
    SHADER("shader", "shaderpacks", false);

    companion object {
        fun fromApi(value: String?) = entries.firstOrNull { it.api == value } ?: MOD
    }
}

/** What an instance can run: the Minecraft version and the Modrinth loaders it accepts (empty = vanilla). */
data class ModTarget(val gameVersion: String, val loaders: List<String>) {
    val isVanilla get() = loaders.isEmpty()

    companion object {
        private val FABRIC = Regex("""^fabric-loader-\d[^-]*-(.+)$""")
        private val QUILT = Regex("""^quilt-loader-\d[^-]*-(.+)$""")

        /**
         * Works out the target from a version id. [inheritsFrom] is the parent declared in the version JSON
         * (used for Forge/NeoForge and any other profile that names its vanilla parent).
         */
        fun detect(versionId: String, inheritsFrom: String?): ModTarget {
            FABRIC.matchEntire(versionId)?.let { return ModTarget(it.groupValues[1], listOf("fabric")) }
            // Quilt loads Fabric mods too.
            QUILT.matchEntire(versionId)?.let { return ModTarget(it.groupValues[1], listOf("quilt", "fabric")) }
            val lower = versionId.lowercase()
            val loader = when {
                "neoforge" in lower -> "neoforge"
                "forge" in lower -> "forge"
                "quilt" in lower -> "quilt"
                "fabric" in lower -> "fabric"
                else -> null
            }
            return ModTarget(inheritsFrom ?: versionId, listOfNotNull(loader))
        }
    }
}

data class ModHit(
    val projectId: String,
    val slug: String,
    val title: String,
    val author: String,
    val description: String,
    val iconUrl: String?,
    val downloads: Long,
    val type: ProjectType,
)

data class SearchPage(val hits: List<ModHit>, val total: Int, val offset: Int) {
    val hasMore get() = offset + hits.size < total
}

data class ModFile(val url: String, val filename: String, val sha1: String?, val size: Long, val primary: Boolean)
data class ModDependency(val projectId: String?, val versionId: String?, val type: String)

data class ModVersion(
    val id: String,
    val projectId: String,
    val name: String,
    val versionNumber: String,
    val versionType: String,
    val files: List<ModFile>,
    val dependencies: List<ModDependency>,
) {
    val primaryFile: ModFile? get() = files.firstOrNull { it.primary } ?: files.firstOrNull()
}

/** Client for the public Modrinth API (https://docs.modrinth.com/api). */
class ModrinthApi(
    private val base: String = BASE,
    private val fetch: suspend (String) -> String = { Http.getString(it) },
) {
    suspend fun search(
        query: String,
        type: ProjectType,
        target: ModTarget,
        offset: Int = 0,
        limit: Int = 20,
        index: String = if (query.isBlank()) "downloads" else "relevance",
    ): SearchPage {
        val facets = buildList {
            add(listOf("project_type:${type.api}"))
            add(listOf("versions:${target.gameVersion}"))
            if (type.usesLoader && target.loaders.isNotEmpty()) add(target.loaders.map { "categories:$it" })
        }
        val facetsJson = JsonArray(facets.map { group -> JsonArray(group.map(::JsonPrimitive)) }).toString()
        val url = "$base/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("facets", facetsJson)
            .addQueryParameter("index", index)
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        val root = parseJsonObject(fetch(url.toString()))
        val hits = root.arr("hits").orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseHit) }
        return SearchPage(hits, root.int("total_hits") ?: hits.size, root.int("offset") ?: offset)
    }

    /** Versions compatible with [target], newest first. */
    suspend fun versions(projectId: String, target: ModTarget, type: ProjectType = ProjectType.MOD): List<ModVersion> {
        val builder = "$base/project/$projectId/version".toHttpUrl().newBuilder()
            .addQueryParameter("game_versions", JsonArray(listOf(JsonPrimitive(target.gameVersion))).toString())
            .addQueryParameter("include_changelog", "false")
        if (type.usesLoader && target.loaders.isNotEmpty()) {
            builder.addQueryParameter("loaders", JsonArray(target.loaders.map(::JsonPrimitive)).toString())
        }
        val arr = com.quantumobile.launcher.core.LauncherJson.parseToJsonElement(fetch(builder.build().toString())) as JsonArray
        return arr.mapNotNull { (it as? JsonObject)?.let(::parseVersion) }
    }

    suspend fun version(versionId: String): ModVersion =
        parseVersion(parseJsonObject(fetch("$base/version/$versionId"))) ?: throw ModException("Malformed version $versionId")

    suspend fun projectTitle(projectId: String): String =
        parseJsonObject(fetch("$base/project/$projectId")).str("title") ?: projectId

    private fun parseHit(o: JsonObject): ModHit? {
        val id = o.str("project_id") ?: return null
        val title = o.str("title") ?: return null
        return ModHit(
            projectId = id,
            slug = o.str("slug").orEmpty(),
            title = title,
            author = o.str("author").orEmpty(),
            description = o.str("description").orEmpty(),
            iconUrl = o.str("icon_url"),
            downloads = o.long("downloads") ?: 0,
            type = ProjectType.fromApi(o.str("project_type")),
        )
    }

    private fun parseVersion(o: JsonObject): ModVersion? {
        val files = o.arr("files").orEmpty().mapNotNull { f ->
            val fo = f as? JsonObject ?: return@mapNotNull null
            ModFile(
                url = fo.str("url") ?: return@mapNotNull null,
                filename = fo.str("filename") ?: return@mapNotNull null,
                sha1 = fo.obj("hashes")?.str("sha1"),
                size = fo.long("size") ?: -1,
                primary = fo.bool("primary") == true,
            )
        }
        val deps = o.arr("dependencies").orEmpty().mapNotNull { d ->
            val dobj = d as? JsonObject ?: return@mapNotNull null
            ModDependency(dobj.str("project_id"), dobj.str("version_id"), dobj.str("dependency_type") ?: "optional")
        }
        return ModVersion(
            id = o.str("id") ?: return null,
            projectId = o.str("project_id") ?: return null,
            name = o.str("name").orEmpty(),
            versionNumber = o.str("version_number").orEmpty(),
            versionType = o.str("version_type") ?: "release",
            files = files,
            dependencies = deps,
        )
    }

    companion object {
        const val BASE = "https://api.modrinth.com/v2"

        /** Newest stable release if there is one, otherwise the newest of any kind (list is newest first). */
        fun pick(versions: List<ModVersion>): ModVersion? =
            versions.firstOrNull { it.versionType == "release" && it.primaryFile != null }
                ?: versions.firstOrNull { it.primaryFile != null }
    }
}
