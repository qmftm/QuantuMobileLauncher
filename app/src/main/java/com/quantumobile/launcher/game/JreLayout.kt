package com.quantumobile.launcher.game

import java.io.File

/** Where the pieces of an unpacked Java runtime live; differs between Java 8 (`lib/<arch>`) and newer builds (`lib`). */
class JreLayout(val home: File) {
    val libDir: File = run {
        val arch = osArch()
        val archDir = arch?.let { File(home, "lib/$it") }
        if (archDir != null && archDir.isDirectory) archDir else File(home, "lib")
    }
    val jvmDir: File = File(libDir, "server").let { if (File(it, "libjvm.so").isFile) it else File(libDir, "client") }
    val jliDir: File = File(libDir, "jli")

    private fun osArch(): String? = runCatching {
        File(home, "release").readLines()
            .firstOrNull { it.startsWith("OS_ARCH=") }
            ?.substringAfter('=')?.trim('"', ' ')
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Search path for the dynamic linker, mirroring what the Android system libs and the app need. */
    fun ldLibraryPath(extra: List<String>): String {
        val sys = listOf("/system/lib64", "/vendor/lib64", "/vendor/lib64/hw", "/system/lib", "/vendor/lib", "/vendor/lib/hw")
        return (listOf(jliDir.path, libDir.path) + sys + extra).filter { it.isNotEmpty() }.joinToString(":")
    }

    /** Libraries that must be loaded (globally) before the JVM starts, in order. */
    fun preloadList(): List<File> {
        val first = listOf("libjli.so", "libjvm.so", "libverify.so", "libjava.so", "libnet.so", "libnio.so", "libawt.so", "libawt_headless.so", "libfreetype.so", "libfontmanager.so")
        val out = LinkedHashSet<File>()
        for (n in first) {
            listOf(File(jliDir, n), File(jvmDir, n), File(libDir, n)).firstOrNull { it.isFile }?.let { out += it }
        }
        libDir.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.sortedBy { it.path }.forEach { out += it }
        return out.toList()
    }
}
