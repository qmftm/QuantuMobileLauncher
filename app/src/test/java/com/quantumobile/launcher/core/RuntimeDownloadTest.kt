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

    @Test
    fun bestPicksExactThenLowestNewer() {
        fun src(n: String, j: Int) = RuntimeSource(n, j, null, "https://x/$n.tar.xz")
        val all = listOf(src("j8", 8), src("j21", 21), src("j17", 17), src("j25", 25))
        assertEquals("j17", RuntimeCatalog.best(all, 17)!!.name)
        assertEquals("j21", RuntimeCatalog.best(all, 18)!!.name) // newer JRE runs older games
        assertEquals("j25", RuntimeCatalog.best(all, 25)!!.name)
        assertNull(RuntimeCatalog.best(all, 26))
        assertNull(RuntimeCatalog.best(emptyList(), 8))
    }

    private fun tar(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(XZCompressorOutputStream(out)).use { t ->
            for ((name, bytes) in files) {
                t.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong(); mode = 493 })
                t.write(bytes); t.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun installsMultiPartRuntimeIntoOneDirectory() = runBlocking {
        val universal = tar("lib/modules" to "modules".toByteArray(), "conf/x" to "c".toByteArray())
        val bin = tar("release" to "JAVA_VERSION=\"21.0.1\"\n".toByteArray(), "bin/java" to "#!/bin/sh".toByteArray(), "lib/server/libjvm.so" to "jvm".toByteArray())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/universal.tar.xz" -> MockResponse().setBody(Buffer().write(universal))
                "/bin-arm64.tar.xz" -> MockResponse().setBody(Buffer().write(bin))
                else -> MockResponse().setResponseCode(404)
            }
        }
        val source = RuntimeSource(
            "OpenJDK 21", 21, "arm64-v8a",
            listOf(
                com.quantumobile.launcher.core.runtime.RuntimePart("$base/universal.tar.xz", sha(universal), universal.size.toLong()),
                com.quantumobile.launcher.core.runtime.RuntimePart("$base/bin-arm64.tar.xz", sha(bin), bin.size.toLong()),
            ),
        )
        val manager = RuntimeManager(File(root, "runtimes"))
        var last = 0L; var total = 0L
        val rt = downloader(manager).install(source) { d, t -> last = d; total = t }

        assertEquals("jre21-arm64-v8a", rt.home.name)
        assertEquals(21, rt.majorVersion)
        for (f in listOf("release", "bin/java", "lib/server/libjvm.so", "lib/modules", "conf/x")) assertTrue(f, File(rt.home, f).exists())
        assertEquals(total, last) // smooth progress across both parts
        assertEquals((universal.size + bin.size).toLong(), total)
        assertTrue(File(rt.home, "bin/java").canExecute())
    }

    @Test
    fun badChecksumInSecondPartInstallsNothing() = runBlocking {
        val universal = tar("lib/modules" to "m".toByteArray())
        val bin = tar("release" to "JAVA_VERSION=\"17.0.1\"\n".toByteArray())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/u.tar.xz" -> MockResponse().setBody(Buffer().write(universal))
                "/b.tar.xz" -> MockResponse().setBody(Buffer().write(bin))
                else -> MockResponse().setResponseCode(404)
            }
        }
        val source = RuntimeSource("x", 17, null, listOf(
            com.quantumobile.launcher.core.runtime.RuntimePart("$base/u.tar.xz", sha(universal)),
            com.quantumobile.launcher.core.runtime.RuntimePart("$base/b.tar.xz", "0".repeat(64)),
        ))
        val manager = RuntimeManager(File(root, "runtimes"))
        try { downloader(manager).install(source); fail("expected checksum failure") } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("b.tar.xz"))
        }
        assertTrue(manager.list().isEmpty())
        assertTrue(File(root, "cache").listFiles().orEmpty().isEmpty()) // every temp part removed
    }

    private fun fixture(name: String) = javaClass.classLoader!!.getResourceAsStream("pack200/$name")!!.readBytes()

    @Test
    fun unpacksResourceOnlyPack200JarsFromARealJdk8() {
        // lib/security/policy/unlimited/local_policy.jar.pack taken from a real Android JDK 8 build.
        val archive = tar(
            "release" to "JAVA_VERSION=\"17.0.1\"\n".toByteArray(),
            "lib/security/local_policy.jar.pack" to fixture("local_policy.jar.pack"),
        )
        val rt = RuntimeManager(File(root, "runtimes")).install("jre-pack", archive.inputStream(), "xz")
        assertFalse(File(rt.home, "lib/security/local_policy.jar.pack").exists())
        java.util.zip.ZipFile(File(rt.home, "lib/security/local_policy.jar")).use { z ->
            assertTrue(z.getInputStream(z.getEntry("default_local.policy")).readBytes().decodeToString().contains("CryptoAllPermission"))
        }
    }

    @Test
    fun runtimesNeedingNativeUnpackFailClearlyAndInstallNothing() {
        // Class jars in the newer Pack200 format (as in JDK 8's rt.jar.pack) cannot be expanded here.
        val archive = tar(
            "release" to "JAVA_VERSION=\"1.8.0_442\"\n".toByteArray(),
            "lib/rt.jar.pack" to fixture("jdk8_rt_header.bin"), // first bytes of a real JDK 8 rt.jar.pack
        )
        val manager = RuntimeManager(File(root, "runtimes"))
        try {
            manager.install("jre8", archive.inputStream(), "xz")
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("Java 8 runtimes are not supported"))
        }
        assertTrue(manager.list().isEmpty())
        assertTrue(File(root, "runtimes").listFiles().orEmpty().none { it.isDirectory }) // staging cleaned up
    }

    @Test
    fun catalogAcceptsPartsAndRejectsInsecureParts() {
        val all = RuntimeCatalog.parse(
            """{"runtimes":[
              {"name":"Split","java":17,"abi":"arm64-v8a","parts":[{"url":"https://x/u.tar.xz","sha256":"AB","size":10},{"url":"https://x/b.tar.xz","size":5}],"sizeMb":1},
              {"name":"HalfInsecure","java":17,"parts":[{"url":"https://x/u.tar.xz"},{"url":"http://x/b.tar.xz"}]},
              {"name":"NoParts","java":17,"parts":[]}]}"""
        )
        assertEquals(listOf("Split"), all.map { it.name })
        assertEquals(listOf("ab", null), all[0].parts.map { it.sha256 })
        assertEquals(15L, all[0].parts.sumOf { it.size })
        assertEquals("jre17-arm64-v8a", all[0].installName)
    }

    @Test
    fun bundledCatalogIsWellFormed() {
        val text = File("src/main/assets/runtimes.json").readText()
        val all = RuntimeCatalog.parse(text)
        assertEquals(11, all.size) // Java 25 has no x86 build
        for (java in listOf(17, 21, 25)) {
            for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64")) { // every common CPU
                assertNotNull("Java $java for $abi", all.firstOrNull { it.java == java && it.abi == abi })
            }
        }
        for (s in all) {
            assertEquals(2, s.parts.size) // universal + per-CPU
            for (p in s.parts) {
                assertTrue(p.url, p.url.startsWith("https://raw.githubusercontent.com/ZalithLauncher/ZalithLauncher/ed8a5c873b25f19d08aef5bdfc1084fd9aff836c/"))
                assertTrue(p.sha256!!, Regex("[0-9a-f]{64}").matches(p.sha256!!))
                assertTrue(p.size > 1_000_000)
            }
        }
        // A device with an arm64 CPU that can also run 32-bit gets the 64-bit build first.
        val best = RuntimeCatalog.best(RuntimeCatalog.forDevice(all, listOf("arm64-v8a", "armeabi-v7a")), 25)!!
        assertEquals("arm64-v8a", best.abi)
    }
}
