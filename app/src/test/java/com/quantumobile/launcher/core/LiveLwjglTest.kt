package com.quantumobile.launcher.core

import com.quantumobile.launcher.core.lwjgl.LwjglInstaller
import com.quantumobile.launcher.core.lwjgl.LwjglPackage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/** Downloads the real LWJGL package for arm64. Opt-in: QML_NETWORK_TESTS=1 (about 23 MB). */
class LiveLwjglTest {
    @Test
    fun installsTheRealPackage() = runBlocking {
        assumeTrue(System.getenv("QML_NETWORK_TESTS") == "1")
        val pkg = LwjglPackage.parse(File("src/main/assets/lwjgl.json").readText())
        val root = Files.createTempDirectory("qml-live-lwjgl").toFile()
        try {
            val paths = LauncherPaths(File(root, "mc"), File(root, "internal"), "/native", File(root, "cache"))
            val installer = LwjglInstaller(paths)
            var last = 0; var total = 0
            installer.install(pkg, "arm64-v8a") { d, t -> last = d; total = t }
            assertTrue(installer.isInstalled(pkg, "arm64-v8a"))
            assertEquals(total, last)
            println("installed ${paths.lwjgl.listFiles()!!.size} jar + ${installer.nativesDir("arm64-v8a").listFiles()!!.size} natives")

            ZipFile(File(paths.lwjgl, "lwjgl-glfw-classes.jar")).use { z ->
                for (c in listOf("org/lwjgl/glfw/GLFW.class", "org/lwjgl/glfw/CallbackBridge.class", "org/lwjgl/opengl/GL.class",
                    "org/lwjgl/system/Library.class", "org/lwjgl/stb/STBImage.class", "org/lwjgl/openal/AL.class")) {
                    assertTrue(c, z.getEntry(c) != null)
                }
            }
            // ELF magic and AArch64 machine type on every native
            for (so in installer.nativesDir("arm64-v8a").listFiles()!!) {
                val head = so.inputStream().use { it.readNBytes(20) }
                assertTrue(so.name, head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte())
                assertEquals(so.name, 0xB7, head[18].toInt() and 0xff) // e_machine = EM_AARCH64 (183)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
