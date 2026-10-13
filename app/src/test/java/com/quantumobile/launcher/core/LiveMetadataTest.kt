package com.quantumobile.launcher.core

import com.quantumobile.launcher.core.mc.GameInstaller
import com.quantumobile.launcher.core.mc.LaunchArgs
import com.quantumobile.launcher.core.mc.LaunchSpec
import com.quantumobile.launcher.core.mc.RuleEnv
import com.quantumobile.launcher.core.mc.VersionInfo
import com.quantumobile.launcher.core.mc.VersionManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URL
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import com.quantumobile.launcher.core.mc.VersionRepository
import com.quantumobile.launcher.core.net.Downloader

/** Checks parsing against real Mojang/Fabric metadata. Opt-in: run with QML_NETWORK_TESTS=1. */
class LiveMetadataTest {
    private fun get(url: String) = URL(url).readText()

    @Test
    fun resolvesRealVersions() {
        assumeTrue(System.getenv("QML_NETWORK_TESTS") == "1")
        val manifest = VersionManifest.parse(get(VersionManifest.URL))
        assertTrue(manifest.versions.size > 500)

        for (id in listOf("1.21.1", "1.16.5", "1.12.2", "1.7.10", "1.5.2")) {
            val info = VersionInfo.parse(parseJsonObject(get(manifest.find(id)!!.url)))
            val libs = GameInstaller.classpathLibraries(info, RuleEnv(), lwjglOverride = false)
            assertTrue(id, libs.none { it.isDesktopNative })
            assertTrue(id, libs.all { it.downloadUrl.startsWith("https://") })
            val args = LaunchArgs.build(
                LaunchSpec(info, "Steve", "u", "t", "msa", "", "/g", "/a", "/l", "/n", libs.map { it.path }, 1280, 720, 2048, "t", "1")
            )
            assertTrue(id, args.none { it.contains("\${") })
            println("$id → Java ${info.javaMajorVersion}, ${libs.size} libs, ${args.size} args")
        }

        val fabric = parseJsonObject(get("https://meta.fabricmc.net/v2/versions/loader/1.21.1/0.16.5/profile/json"))
        val merged = VersionInfo.merge(fabric, VersionInfo.parse(parseJsonObject(get(manifest.find("1.21.1")!!.url))))
        assertEquals("1.21.1", merged.jarId)
        val asm = GameInstaller.classpathLibraries(merged, RuleEnv(), false).filter { it.coords.key == "org.ow2.asm:asm" }
        assertEquals(1, asm.size)
    }

    @Test
    fun installsSmallVersionEndToEnd() = runBlocking {
        assumeTrue(System.getenv("QML_NETWORK_TESTS") == "1")
        val root = Files.createTempDirectory("qml").toFile()
        try {
            val paths = LauncherPaths(File(root, ".minecraft"), File(root, "internal"), "/natives", File(root, "cache"))
            val version = VersionRepository(paths).resolve("1.5.2")
            val gameDir = File(root, "game")
            GameInstaller(paths).install(version, gameDir) { _, _, _ -> }

            val jar = paths.versionJar("1.5.2")
            assertEquals(version.clientDownload!!.sha1, Downloader.sha1(jar))
            GameInstaller.classpathLibraries(version, RuleEnv(), false).forEach {
                assertTrue(it.path, File(paths.libraries, it.path).isFile)
            }
            // Pre-1.6 assets are copied out of the object store.
            assertTrue(File(gameDir, "resources").walk().any { it.isFile } || File(paths.assets, "virtual").walk().any { it.isFile })
        } finally {
            root.deleteRecursively()
        }
    }
}
