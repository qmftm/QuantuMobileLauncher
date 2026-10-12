package com.quantumobile.launcher.core

import com.quantumobile.launcher.core.auth.Account
import com.quantumobile.launcher.core.mc.GameInstaller
import com.quantumobile.launcher.core.mc.LaunchArgs
import com.quantumobile.launcher.core.mc.LaunchSpec
import com.quantumobile.launcher.core.mc.MavenCoords
import com.quantumobile.launcher.core.mc.RuleEnv
import com.quantumobile.launcher.core.mc.Rules
import com.quantumobile.launcher.core.mc.VersionInfo
import com.quantumobile.launcher.core.runtime.RuntimeManager
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {

    private fun rules(json: String) = LauncherJson.parseToJsonElement(json) as JsonArray

    @Test
    fun rulesFollowLastMatchWins() {
        val env = RuleEnv()
        assertTrue(Rules.isAllowed(null, env))
        assertTrue(Rules.isAllowed(rules("""[{"action":"allow"},{"action":"disallow","os":{"name":"osx"}}]"""), env))
        assertFalse(Rules.isAllowed(rules("""[{"action":"allow","os":{"name":"osx"}}]"""), env))
        assertTrue(Rules.isAllowed(rules("""[{"action":"allow","os":{"name":"linux"}}]"""), env))
        assertFalse(Rules.isAllowed(rules("""[{"action":"allow","features":{"is_demo_user":true}}]"""), env))
        assertTrue(
            Rules.isAllowed(
                rules("""[{"action":"allow","features":{"has_custom_resolution":true}}]"""),
                env.copy(features = mapOf("has_custom_resolution" to true)),
            )
        )
    }

    @Test
    fun mavenPaths() {
        assertEquals(
            "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar",
            MavenCoords.parse("org.lwjgl:lwjgl:3.3.3:natives-linux").path,
        )
        assertEquals("net/fabricmc/fabric-loader/0.16.5/fabric-loader-0.16.5.jar", MavenCoords.parse("net.fabricmc:fabric-loader:0.16.5").path)
        assertEquals("a/b/c/1/c-1.zip", MavenCoords.parse("a.b:c:1@zip").path)
        assertEquals("org.ow2.asm:asm", MavenCoords.parse("org.ow2.asm:asm:9.7").key)
    }

    private val vanilla = parseJsonObject(
        """
        {
          "id": "1.21.1", "type": "release", "mainClass": "net.minecraft.client.main.Main",
          "assets": "17", "assetIndex": {"id": "17", "url": "https://x/17.json", "sha1": "abc", "size": 1},
          "downloads": {"client": {"url": "https://x/client.jar", "sha1": "def", "size": 2}},
          "javaVersion": {"majorVersion": 21},
          "libraries": [
            {"name": "org.ow2.asm:asm:9.3", "downloads": {"artifact": {"path": "org/ow2/asm/asm/9.3/asm-9.3.jar", "url": "https://l/asm-9.3.jar", "sha1": "1", "size": 1}}},
            {"name": "org.lwjgl:lwjgl:3.3.3", "downloads": {"artifact": {"path": "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar", "url": "https://l/lwjgl.jar", "sha1": "2", "size": 1}}},
            {"name": "org.lwjgl:lwjgl:3.3.3:natives-linux", "downloads": {"artifact": {"path": "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar", "url": "https://l/n.jar", "sha1": "3", "size": 1}}, "rules": [{"action": "allow", "os": {"name": "linux"}}]},
            {"name": "ca.weblite:java-objc-bridge:1.1", "downloads": {"artifact": {"path": "ca/weblite/x.jar", "url": "https://l/objc.jar", "sha1": "4", "size": 1}}, "rules": [{"action": "allow", "os": {"name": "osx"}}]}
          ],
          "arguments": {
            "game": ["--username", "${'$'}{auth_player_name}", "--version", "${'$'}{version_name}", "--accessToken", "${'$'}{auth_access_token}",
                     {"rules": [{"action": "allow", "features": {"is_demo_user": true}}], "value": "--demo"},
                     {"rules": [{"action": "allow", "features": {"has_custom_resolution": true}}], "value": ["--width", "${'$'}{resolution_width}", "--height", "${'$'}{resolution_height}"]}],
            "jvm": [{"rules": [{"action": "allow", "os": {"name": "osx"}}], "value": ["-XstartOnFirstThread"]},
                    "-Djava.library.path=${'$'}{natives_directory}", "-cp", "${'$'}{classpath}"]
          }
        }
        """
    )

    private val fabric = parseJsonObject(
        """
        {
          "id": "fabric-loader-0.16.5-1.21.1", "inheritsFrom": "1.21.1", "type": "release",
          "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
          "arguments": {"game": [], "jvm": ["-DFabricMcEmu= net.minecraft.client.main.Main "]},
          "libraries": [
            {"name": "org.ow2.asm:asm:9.7", "url": "https://maven.fabricmc.net/"},
            {"name": "net.fabricmc:fabric-loader:0.16.5", "url": "https://maven.fabricmc.net/"}
          ]
        }
        """
    )

    @Test
    fun inheritsFromMerge() {
        val merged = VersionInfo.merge(fabric, VersionInfo.parse(vanilla))
        assertEquals("fabric-loader-0.16.5-1.21.1", merged.id)
        assertEquals("1.21.1", merged.jarId)
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", merged.mainClass)
        assertEquals(21, merged.javaMajorVersion)
        assertEquals("17", merged.assets)

        val libs = GameInstaller.classpathLibraries(merged, RuleEnv(), lwjglOverride = false)
        // Fabric's newer ASM wins, desktop natives and macOS-only libraries are dropped.
        assertEquals(listOf("org.ow2.asm:asm:9.7", "net.fabricmc:fabric-loader:0.16.5", "org.lwjgl:lwjgl:3.3.3"), libs.map { it.name })
        assertEquals(
            "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.16.5/fabric-loader-0.16.5.jar",
            libs[1].downloadUrl,
        )
        val noLwjgl = GameInstaller.classpathLibraries(merged, RuleEnv(), lwjglOverride = true)
        assertFalse(noLwjgl.any { it.coords.group == "org.lwjgl" })
    }

    @Test
    fun buildsModernArguments() {
        val version = VersionInfo.merge(fabric, VersionInfo.parse(vanilla))
        val args = LaunchArgs.build(spec(version))
        val main = args.indexOf("net.fabricmc.loader.impl.launch.knot.KnotClient")
        val jvm = args.subList(0, main)
        val game = args.subList(main + 1, args.size)

        assertEquals("-Xmx2048M", jvm[1])
        assertTrue("-Djava.library.path=/natives" in jvm)
        assertFalse("-XstartOnFirstThread" in jvm)
        assertEquals("/lib/a.jar:/versions/1.21.1/1.21.1.jar", jvm[jvm.indexOf("-cp") + 1])
        assertTrue("-DFabricMcEmu= net.minecraft.client.main.Main " in jvm)
        assertEquals(
            listOf("--username", "Steve", "--version", version.id, "--accessToken", "token", "--width", "1920", "--height", "1080"),
            game,
        )
    }

    @Test
    fun buildsLegacyArguments() {
        val legacy = VersionInfo.parse(
            parseJsonObject(
                """{"id":"1.8.9","mainClass":"net.minecraft.client.main.Main","assets":"1.8",
                   "minecraftArguments":"--username ${'$'}{auth_player_name} --assetsDir ${'$'}{game_assets} --uuid ${'$'}{auth_uuid}"}"""
            )
        )
        val args = LaunchArgs.build(spec(legacy))
        assertEquals(8, legacy.javaMajorVersion)
        assertTrue("-cp" in args)
        val game = args.subList(args.indexOf("net.minecraft.client.main.Main") + 1, args.size)
        assertEquals(listOf("--username", "Steve", "--assetsDir", "/assets", "--uuid", "abcd", "--width", "1920", "--height", "1080"), game)
    }

    @Test
    fun legacyAssetsUseVirtualDirectory() {
        val v = VersionInfo.parse(parseJsonObject("""{"id":"1.5.2","mainClass":"M","assets":"legacy","minecraftArguments":"${'$'}{game_assets}"}"""))
        assertEquals("/assets/virtual/legacy", LaunchArgs.variables(spec(v))["game_assets"])
    }

    private fun spec(version: VersionInfo) = LaunchSpec(
        version = version, playerName = "Steve", playerUuid = "abcd", accessToken = "token", userType = "msa", xuid = "",
        gameDir = "/game", assetsRoot = "/assets", librariesDir = "/lib", nativesDir = "/natives",
        classpath = listOf("/lib/a.jar", "/versions/1.21.1/1.21.1.jar"), width = 1920, height = 1080, memoryMb = 2048,
        launcherName = "test", launcherVersion = "1",
    )

    @Test
    fun offlineUuidMatchesVanilla() {
        // Value produced by vanilla servers for OfflinePlayer:Notch
        assertEquals("b50ad385829d3141a2167e7d7539ba7f", Account.offlineUuid("Notch"))
        assertTrue(Account.isValidOfflineName("Steve_123"))
        assertFalse(Account.isValidOfflineName("ab"))
        assertFalse(Account.isValidOfflineName("bad name"))
    }

    @Test
    fun javaVersionParsing() {
        assertEquals(8, RuntimeManager.parseMajor("1.8.0_402"))
        assertEquals(17, RuntimeManager.parseMajor("17.0.10"))
        assertEquals(21, RuntimeManager.parseMajor("21"))
    }

    @Test
    fun argumentSplitting() {
        assertEquals(listOf("-Xss4M", "-Dfoo=a b", "-Dx="), LaunchPreparer.splitArgs("""  -Xss4M "-Dfoo=a b"   -Dx= """))
        assertEquals(emptyList<String>(), LaunchPreparer.splitArgs("   "))
    }

    @Test
    fun missingRuntimeMessageNamesRequirementAndInstalled() {
        val msg = LaunchPreparer.missingRuntimeMessage("fabric-loader-0.19.5-26.3", 25, listOf(8, 21))
        assertTrue(msg.contains("Java 25"))
        assertTrue(msg.contains("Java 8, Java 21"))
        assertTrue(LaunchPreparer.missingRuntimeMessage("x", 17, emptyList()).contains("none"))
    }
}
