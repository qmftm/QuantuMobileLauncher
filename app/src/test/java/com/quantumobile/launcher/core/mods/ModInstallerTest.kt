package com.quantumobile.launcher.core.mods

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModInstallerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer().apply {
            enqueue(MockResponse().setBody("pack-one"))
            enqueue(MockResponse().setBody("pack-two"))
            start()
        }
    }

    @After fun stop() = server.shutdown()

    private fun item(n: Int) = PlanItem(
        "p$n", "Pack $n", ProjectType.RESOURCEPACK,
        ModVersion("v$n", "p$n", "Pack $n", "1.$n", "release",
            listOf(ModFile(server.url("/pack$n.zip").toString(), "pack$n.zip", null, -1, true)), emptyList()),
        dependency = false,
    )

    /** Progress is reported on the thread that called apply (the UI thread in the app), not on IO workers. */
    @Test fun progressCallbacksRunOnTheCallersThread() = runBlocking {
        val dir = tmp.newFolder("game")
        val installer = ModInstaller(ModrinthApi(), dir, allowedPrefix = server.url("/").toString())
        val caller = Thread.currentThread()
        val seen = mutableListOf<Thread>()
        val done = installer.apply(InstallPlan(listOf(item(1), item(2)), emptyList())) { _, _, _ -> seen += Thread.currentThread() }

        assertEquals(2, done.size)
        assertTrue(seen.isNotEmpty() && seen.all { it === caller })
        assertEquals("pack-one", File(dir, "resourcepacks/pack1.zip").readText())
        assertEquals(listOf("p1", "p2"), installer.store.list().map { it.projectId })
    }

    @Test fun refusesFilesOutsideTheAllowedHost() = runBlocking {
        val installer = ModInstaller(ModrinthApi(), tmp.newFolder("game"), allowedPrefix = "https://cdn.modrinth.com/")
        val result = runCatching { installer.apply(InstallPlan(listOf(item(1)), emptyList())) }
        assertTrue(result.exceptionOrNull() is ModException)
    }

    private fun installed(type: ProjectType, name: String, id: String = "p1") =
        InstalledMod(id, "v1", "1.0", "T", type.api, name)

    @Test fun modsAreDisabledByRenamingAndRemoveCleansUp() {
        val dir = tmp.newFolder("game")
        val installer = ModInstaller(ModrinthApi(), dir)
        val mod = installed(ProjectType.MOD, "a.jar")
        File(dir, "mods").mkdirs(); File(dir, "mods/a.jar").writeText("x")
        installer.store.put(mod)

        assertTrue(installer.isEnabled(mod))
        installer.setEnabled(mod, false)
        assertTrue(!installer.isEnabled(mod) && File(dir, "mods/a.jar.disabled").isFile && !File(dir, "mods/a.jar").exists())
        installer.setEnabled(mod, true)
        assertTrue(installer.isEnabled(mod) && !File(dir, "mods/a.jar.disabled").exists())

        installer.setEnabled(mod, false)
        installer.remove(mod)
        assertTrue(File(dir, "mods").listFiles().isNullOrEmpty())
        assertTrue(installer.store.list().isEmpty())
    }

    @Test fun packsAndShadersToggleThroughGameSettings() {
        val dir = tmp.newFolder("game")
        val installer = ModInstaller(ModrinthApi(), dir)
        val rp = installed(ProjectType.RESOURCEPACK, "r.zip", "rp")
        val sh = installed(ProjectType.SHADER, "s.zip", "sh")
        File(dir, "resourcepacks").mkdirs(); File(dir, "resourcepacks/r.zip").writeText("x")
        File(dir, "shaderpacks").mkdirs(); File(dir, "shaderpacks/s.zip").writeText("x")
        installer.store.put(rp); installer.store.put(sh)

        assertTrue(!installer.isEnabled(rp) && !installer.isEnabled(sh))
        installer.setEnabled(rp, true); installer.setEnabled(sh, true)
        assertTrue(installer.isEnabled(rp) && installer.isEnabled(sh))
        installer.setEnabled(sh, false)
        assertTrue(!installer.isEnabled(sh))

        installer.setEnabled(sh, true)
        installer.remove(rp); installer.remove(sh)
        val pm = PackManager(dir)
        assertTrue("r.zip" !in pm.enabledResourcePacks())
        assertEquals(null, pm.activeShader())
        assertTrue(!File(dir, "resourcepacks/r.zip").exists() && !File(dir, "shaderpacks/s.zip").exists())
    }
}
