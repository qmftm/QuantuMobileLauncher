package com.quantumobile.launcher.core

import com.quantumobile.launcher.core.runtime.RuntimeCatalog
import com.quantumobile.launcher.core.runtime.RuntimeDownloader
import com.quantumobile.launcher.core.runtime.RuntimeManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Downloads real runtimes from the bundled catalog. Opt-in: QML_NETWORK_TESTS=1 (about 60 MB). */
class LiveRuntimeTest {
    @Test
    fun installsRealRuntimesFromTheBundledCatalog() = runBlocking {
        assumeTrue(System.getenv("QML_NETWORK_TESTS") == "1")
        val catalog = RuntimeCatalog.parse(File("src/main/assets/runtimes.json").readText())
        val root = Files.createTempDirectory("qml-live-rt").toFile()
        try {
            val manager = RuntimeManager(File(root, "runtimes"))
            val downloader = RuntimeDownloader(manager, File(root, "cache"))
            val device = RuntimeCatalog.forDevice(catalog, listOf("arm64-v8a", "armeabi-v7a"))

            // What auto-download would pick for Minecraft versions that need these Java versions.
            val expected = mapOf(17 to "17.0.10", 21 to "21.0.1", 25 to "25.0.3")
            for ((required, version) in expected) {
                val source = RuntimeCatalog.best(device, required)!!
                assertEquals(required, source.java)
                assertEquals("arm64-v8a", source.abi)
                var last = 0L; var total = 0L
                val rt = downloader.install(source) { d, t -> last = d; total = t }
                println("Java $required → ${rt.name} ${rt.fullVersion} (${source.sizeMb} MB, progress $last/$total)")
                assertEquals(version, rt.fullVersion)
                assertEquals(required, rt.majorVersion)
                assertEquals(total, last)
                for (f in listOf("release", "bin/java", "lib/server/libjvm.so", "lib/libjli.so", "lib/libjava.so", "lib/modules", "conf/security/java.security")) {
                    assertTrue("$required: $f", File(rt.home, f).exists())
                }
                assertNotNull(manager.pick(required))
            }
            // Java 8 is not offered, so a game needing it gets the lowest newer runtime we have.
            assertEquals(17, manager.pick(8)!!.majorVersion)
            assertEquals(3, manager.list().size)
        } finally {
            root.deleteRecursively()
        }
    }
}
