package com.quantumobile.launcher.core

import com.quantumobile.launcher.core.mods.ModInstaller
import com.quantumobile.launcher.core.mods.ModTarget
import com.quantumobile.launcher.core.mods.ModrinthApi
import com.quantumobile.launcher.core.mods.ProjectType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/** Talks to the real Modrinth API. Opt-in: QML_NETWORK_TESTS=1. */
class LiveModrinthTest {
    @Test
    fun searchesAndInstallsRealMods() = runBlocking {
        assumeTrue(System.getenv("QML_NETWORK_TESTS") == "1")
        val api = ModrinthApi()
        val target = ModTarget("1.21.1", listOf("fabric"))

        val page = api.search("sodium", ProjectType.MOD, target)
        assertTrue(page.hits.isNotEmpty())
        val sodium = page.hits.first { it.slug == "sodium" }
        println("search → ${page.total} hits, first=${sodium.title} (${sodium.downloads} downloads)")

        val newest = api.search("", ProjectType.MOD, ModTarget("26.3", listOf("fabric")))
        println("26.3 fabric popular → ${newest.hits.take(3).map { it.title }}")
        assertTrue(newest.hits.isNotEmpty())
        assertTrue(api.search("", ProjectType.RESOURCEPACK, target).hits.isNotEmpty())
        assertTrue(api.search("", ProjectType.SHADER, target).hits.isNotEmpty())

        val dir = Files.createTempDirectory("qml-live").toFile()
        try {
            val installer = ModInstaller(api, dir)
            // Mods commonly depend on Fabric API; find one that does so the dependency path is exercised.
            val withDeps = page.hits.plus(api.search("", ProjectType.MOD, target).hits)
                .firstNotNullOfOrNull { hit ->
                    installer.resolve(hit.projectId, hit.title, hit.type, target).takeIf { it.items.size > 1 }
                }
            val plan = withDeps ?: installer.resolve(sodium.projectId, sodium.title, sodium.type, target)
            println("plan → " + plan.items.map { "${it.title}${if (it.dependency) " (dep)" else ""} ${it.version.versionNumber}" })
            installer.apply(plan)
            val jars = File(dir, "mods").listFiles().orEmpty()
            println("installed → " + jars.map { "${it.name} ${it.length()}B" })
            assertTrue(jars.isNotEmpty() && jars.all { it.length() > 1000 }) // sha1 already verified by the downloader
            assertTrue(installer.store.list().size == plan.items.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}

private typealias File = java.io.File
