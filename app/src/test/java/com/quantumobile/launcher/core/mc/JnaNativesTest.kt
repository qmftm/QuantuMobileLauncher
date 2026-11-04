package com.quantumobile.launcher.core.mc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class JnaNativesTest {
    @Test fun findsTheGamesJnaVersion() {
        assertEquals("5.19.1", JnaNatives.jnaVersion(listOf("org.lwjgl:lwjgl:3.4.3", "net.java.dev.jna:jna-platform:5.19.1", "net.java.dev.jna:jna:5.19.1")))
        assertNull(JnaNatives.jnaVersion(listOf("net.java.dev.jna:jna-platform:5.19.1")))
        assertNull(JnaNatives.jnaVersion(emptyList()))
    }

    @Test fun extractsTheAbisLibrary() {
        val dir = Files.createTempDirectory("jna").toFile()
        val aar = File(dir, "jna.aar")
        ZipOutputStream(aar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("jni/arm64-v8a/libjnidispatch.so")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("jni/x86/libjnidispatch.so")); zip.write(byteArrayOf(9)); zip.closeEntry()
        }
        val dest = File(dir, "out/libjnidispatch.so")
        JnaNatives.extract(aar, "arm64-v8a", dest)
        assertArrayEquals(byteArrayOf(1, 2, 3), dest.readBytes())
    }

    @Test(expected = IOException::class) fun missingAbiFails() {
        val dir = Files.createTempDirectory("jna").toFile()
        val aar = File(dir, "jna.aar")
        ZipOutputStream(aar.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("classes.jar")); zip.closeEntry() }
        JnaNatives.extract(aar, "arm64-v8a", File(dir, "out/libjnidispatch.so"))
    }
}
