package com.quantumobile.launcher.core

import com.quantumobile.launcher.core.runtime.RuntimeCatalog
import com.quantumobile.launcher.core.runtime.RuntimeDownloader
import com.quantumobile.launcher.core.runtime.RuntimeManager
import com.quantumobile.launcher.core.runtime.RuntimeSource
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class RuntimeDownloadTest {
    private lateinit var server: MockWebServer
    private lateinit var root: File
    private lateinit var archive: ByteArray
    private val base get() = server.url("/").toString().trimEnd('/')

    private fun jreArchive(version: String): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(XZCompressorOutputStream(out)).use { tar ->
            val release = "JAVA_VERSION=\"$version\"\n".toByteArray()
            tar.putArchiveEntry(TarArchiveEntry("jre25/release").apply { size = release.size.toLong() })
            tar.write(release); tar.closeArchiveEntry()
            val bin = "#!/bin/sh\n".toByteArray()
            tar.putArchiveEntry(TarArchiveEntry("jre25/bin/java").apply { size = bin.size.toLong(); mode = 493 })
            tar.write(bin); tar.closeArchiveEntry()
        }
        return out.toByteArray()
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("qml-rt").toFile()
        archive = jreArchive("25.0.1")
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when (request.path) {
                    "/jre25-arm64.tar.xz" -> MockResponse().setBody(Buffer().write(archive))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        root.deleteRecursively()
    }

    private fun downloader(manager: RuntimeManager) = RuntimeDownloader(manager, File(root, "cache"), allowInsecure = true)

    @Test
    fun downloadsVerifiesAndInstalls() = runBlocking {
        val manager = RuntimeManager(File(root, "runtimes"))
        val sha = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }
        var lastDone = 0L
        val runtime = downloader(manager).install(
            RuntimeSource("Test 25", 25, null, "$base/jre25-arm64.tar.xz", sha256 = sha),
        ) { done, _ -> lastDone = done }

        assertEquals(25, runtime.majorVersion)
        assertEquals("25.0.1", runtime.fullVersion)
        assertEquals(archive.size.toLong(), lastDone)
        assertNotNull(manager.pick(25))
        assertTrue(File(runtime.home, "bin/java").canExecute())
        assertFalse("temp download must be cleaned up", File(root, "cache/runtime-download.part").exists())
    }

    @Test
    fun rejectsChecksumMismatchAndInstallsNothing() = runBlocking {
        val manager = RuntimeManager(File(root, "runtimes"))
        try {
            downloader(manager).install(RuntimeSource("x", 25, null, "$base/jre25-arm64.tar.xz", sha256 = "0".repeat(64)))
            fail("expected checksum failure")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Checksum mismatch"))
        }
        assertTrue(manager.list().isEmpty())
        assertFalse(File(root, "cache/runtime-download.part").exists())
    }

    @Test
    fun reportsHttpErrors() = runBlocking {
        try {
            downloader(RuntimeManager(File(root, "runtimes"))).install(RuntimeSource.fromUrl("$base/missing.tar.xz"))
            fail("expected HTTP error")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("404"))
        }
    }

    @Test
    fun refusesPlainHttpByDefault() = runBlocking {
        try {
            RuntimeDownloader(RuntimeManager(File(root, "runtimes")), File(root, "cache"))
                .install(RuntimeSource.fromUrl("$base/jre25-arm64.tar.xz"))
            fail("expected https-only failure")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("https"))
        }
    }

    @Test
    fun sourceDerivesNameAndCompression() {
        val s = RuntimeSource.fromUrl("https://example.com/dl/jre21-aarch64.tar.xz?token=1")
        assertEquals("jre21-aarch64.tar.xz", s.fileName)
        assertEquals("xz", s.compression)
        assertEquals("gz", RuntimeSource.fromUrl("https://x/y.tgz").compression)
        assertEquals("tar", RuntimeSource.fromUrl("https://x/y.tar").compression)
    }

    @Test
    fun catalogParsesFiltersByAbiAndSkipsBadEntries() {
        val all = RuntimeCatalog.parse(
            """{"_comment":"x","runtimes":[
              {"name":"A","java":21,"abi":"arm64-v8a","url":"https://x/a.tar.xz","sha256":"ABC","sizeMb":50},
              {"name":"B","java":25,"abi":"arm64-v8a","url":"https://x/b.tar.xz"},
              {"name":"C","java":25,"abi":"x86_64","url":"https://x/c.tar.xz"},
              {"name":"D","java":17,"url":"https://x/d.tar.xz"},
              {"name":"insecure","java":25,"url":"http://x/e.tar.xz"},
              {"name":"nojava","url":"https://x/f.tar.xz"}]}"""
        )
        assertEquals(listOf("A", "B", "C", "D"), all.map { it.name })
        assertEquals("abc", all[0].sha256)

        val arm = RuntimeCatalog.forDevice(all, listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals(listOf("B", "A", "D"), arm.map { it.name }) // ABI-specific first, newest Java first, no x86_64
        assertNull(RuntimeCatalog.forDevice(all, listOf("x86")).firstOrNull { it.abi == "arm64-v8a" })
        assertTrue(RuntimeCatalog.parse("""{"runtimes":[]}""").isEmpty())
    }
}
