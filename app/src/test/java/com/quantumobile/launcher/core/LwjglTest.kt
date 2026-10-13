package com.quantumobile.launcher.core

import com.quantumobile.launcher.core.lwjgl.LwjglFile
import com.quantumobile.launcher.core.lwjgl.LwjglInstaller
import com.quantumobile.launcher.core.lwjgl.LwjglPackage
import com.quantumobile.launcher.core.net.Downloader
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class LwjglTest {
    private lateinit var server: MockWebServer
    private lateinit var root: File
    private lateinit var paths: LauncherPaths
    private val files = mapOf(
        "/glfw.jar" to "jar-bytes".toByteArray(),
        "/liblwjgl.so" to "lwjgl-native".toByteArray(),
        "/libgl4es.so" to "gl4es-native".toByteArray(),
    )

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun file(name: String, path: String, bad: Boolean = false) = files.getValue(path).let {
        LwjglFile(name, server.url(path).toString(), if (bad) "0".repeat(64) else sha(it), it.size.toLong())
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("qml-lwjgl").toFile()
        paths = LauncherPaths(File(root, "mc"), File(root, "internal"), "/native", File(root, "cache"))
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    files[request.path]?.let { MockResponse().setBody(Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
            }
            start()
        }
    }

    @After
    fun tearDown() { server.shutdown(); root.deleteRecursively() }

    private fun pkg(badNative: Boolean = false) = LwjglPackage(
        jar = file("lwjgl-glfw-classes.jar", "/glfw.jar"),
        natives = mapOf("arm64-v8a" to listOf(file("liblwjgl.so", "/liblwjgl.so", badNative), file("libgl4es_114.so", "/libgl4es.so"))),
    )

    // The installer's downloader must accept the mock server's plain-http URLs.
    private fun installer() = LwjglInstaller(paths, Downloader(OkHttpClient()))

    @Test
    fun installsJarAndNativesAndReportsInstalled() = runBlocking {
        val inst = installer()
        val p = pkg()
        assertFalse(inst.isInstalled(p, "arm64-v8a"))
        // Files download in parallel, so progress callbacks may arrive out of order: track the maximum, not the last value.
        var last = 0; var total = 0
        inst.install(p, "arm64-v8a") { d, t -> synchronized(this) { last = maxOf(last, d); total = t } }
        assertTrue(inst.isInstalled(p, "arm64-v8a"))
        assertEquals(total, last)
        assertEquals("jar-bytes", File(paths.lwjgl, "lwjgl-glfw-classes.jar").readText())
        assertEquals("lwjgl-native", File(inst.nativesDir("arm64-v8a"), "liblwjgl.so").readText())
        assertTrue(GameInstallerCompat.hasOverride(paths))
    }

    @Test
    fun installReplacesStaleJarsAndNatives() = runBlocking {
        paths.lwjgl.mkdirs()
        File(paths.lwjgl, "old-lwjgl.jar").writeText("x")                          // a manual import that would clash
        installer().nativesDir("arm64-v8a").mkdirs()
        File(installer().nativesDir("arm64-v8a"), "libstale.so").writeText("x")
        installer().install(pkg(), "arm64-v8a")
        assertFalse(File(paths.lwjgl, "old-lwjgl.jar").exists())
        assertFalse(File(installer().nativesDir("arm64-v8a"), "libstale.so").exists())
    }

    @Test
    fun badChecksumFailsAndIsNotReportedInstalled() = runBlocking {
        val inst = installer()
        try { inst.install(pkg(badNative = true), "arm64-v8a"); fail("expected checksum failure") } catch (e: IOException) {
            // The downloader wraps the cause ("Failed to download …"); the checksum failure is inside.
            assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it.message.orEmpty().contains("SHA-256 mismatch") })
        }
        assertFalse(File(inst.nativesDir("arm64-v8a"), "liblwjgl.so").exists()) // never keeps a file that failed verification
    }

    @Test
    fun detectsCorruptedOrTruncatedFiles() = runBlocking {
        val inst = installer(); val p = pkg()
        inst.install(p, "arm64-v8a")
        File(inst.nativesDir("arm64-v8a"), "libgl4es_114.so").writeText("short")
        assertFalse(inst.isInstalled(p, "arm64-v8a"))
        inst.install(p, "arm64-v8a") // repairs by re-downloading only what is wrong
        assertTrue(inst.isInstalled(p, "arm64-v8a"))
    }

    @Test
    fun picksTheFirstSupportedAbi() {
        val p = pkg()
        assertEquals("arm64-v8a", p.abiFor(listOf("x86", "arm64-v8a")))
        assertNull(p.abiFor(listOf("mips")))
    }

    @Test
    fun bundledManifestIsWellFormed() {
        val p = LwjglPackage.parse(File("src/main/assets/lwjgl.json").readText())
        assertTrue(p.jar.url.startsWith("https://raw.githubusercontent.com/PojavLauncherTeam/PojavLauncher/b12ad048157b3aa255d078c235dd4571e1900309/"))
        assertEquals(setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"), p.natives.keys)
        val required = listOf("liblwjgl.so", "liblwjgl_opengl.so", "liblwjgl_stb.so", "libopenal.so", "libgl4es_114.so", "libfreetype.so", "libjnidispatch.so")
        for ((abi, libs) in p.natives) {
            assertTrue(abi, libs.map { it.name }.containsAll(required))
            for (f in libs + p.jar) {
                assertTrue(f.url, f.url.startsWith("https://"))
                assertTrue(f.name, Regex("[0-9a-f]{64}").matches(f.sha256))
                assertTrue(f.name, f.size > 10_000)
            }
        }
        assertEquals("arm64-v8a", p.abiFor(listOf("arm64-v8a", "armeabi-v7a")))
    }
}

/** The same override check the launcher uses, without needing Android classes. */
private object GameInstallerCompat {
    fun hasOverride(paths: LauncherPaths) = com.quantumobile.launcher.core.mc.GameInstaller.hasLwjglOverride(paths)
}
