package com.quantumobile.launcher.core.mods

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun resourcePackOrderAndToggleKeepOtherOptions() {
        val dir = tmp.newFolder("game")
        File(dir, "options.txt").writeText("fov:0.5\nresourcePacks:[\"vanilla\",\"file/A.zip\"]\nlang:ko_kr\n")
        val pm = PackManager(dir)
        assertEquals(listOf("A.zip"), pm.enabledResourcePacks().filter { it != "vanilla" })

        pm.setResourcePackEnabled("B.zip", true)
        assertEquals(listOf("vanilla", "A.zip", "B.zip"), pm.enabledResourcePacks())
        pm.movePack("A.zip", up = true) // A goes on top of B
        assertEquals(listOf("vanilla", "B.zip", "A.zip"), pm.enabledResourcePacks())
        pm.setResourcePackEnabled("B.zip", false)
        assertEquals(listOf("vanilla", "A.zip"), pm.enabledResourcePacks())

        val text = File(dir, "options.txt").readText()
        assertTrue(text.contains("fov:0.5") && text.contains("lang:ko_kr"))
        assertEquals(1, Regex("resourcePacks:").findAll(text).count())
    }

    @Test fun shaderSelectionWritesIrisAndOptifine() {
        val dir = tmp.newFolder("game")
        val pm = PackManager(dir)
        assertNull(pm.activeShader())
        pm.setActiveShader("Complementary.zip")
        assertEquals("Complementary.zip", pm.activeShader())
        assertTrue(File(dir, "optionsshaders.txt").readText().contains("shaderPack=Complementary.zip"))
        pm.setActiveShader(null)
        assertNull(pm.activeShader())
    }

    @Test fun importRejectsNonZipAndStripsPath() {
        val dir = tmp.newFolder("game")
        val pm = PackManager(dir)
        assertEquals("x.zip", pm.import(ProjectType.RESOURCEPACK, "../../x.zip", "data".byteInputStream()))
        assertTrue(File(dir, "resourcepacks/x.zip").isFile)
        runCatching { pm.import(ProjectType.SHADER, "evil.jar", "d".byteInputStream()) }.let { assertTrue(it.isFailure) }
        assertEquals(listOf("x.zip"), pm.list(ProjectType.RESOURCEPACK).map { it.name })
    }
}
