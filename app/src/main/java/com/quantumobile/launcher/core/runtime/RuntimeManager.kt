package com.quantumobile.launcher.core.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.harmony.unpack200.Archive
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

data class JavaRuntime(val name: String, val home: File, val majorVersion: Int, val fullVersion: String)

/**
 * Manages Android-built OpenJDK runtimes (e.g. the `jre*-aarch64.tar.xz` builds used by Pojav-family
 * launchers). Runtimes are unpacked into internal storage so the bridge can dlopen them.
 */
class RuntimeManager(private val root: File) {

    fun list(): List<JavaRuntime> =
        root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull(::describe).sortedBy { it.majorVersion }

    /** Smallest installed runtime whose major version is ≥ [required], preferring an exact match. */
    fun pick(required: Int): JavaRuntime? {
        val all = list()
        return all.firstOrNull { it.majorVersion == required } ?: all.firstOrNull { it.majorVersion > required }
    }

    fun delete(name: String) = File(root, name).deleteRecursively()

    /** Extracts a `.tar.xz` / `.tar.gz` / `.tar` JRE archive. Returns the installed runtime. */
    fun install(name: String, archive: InputStream, compression: String): JavaRuntime =
        install(name, listOf(archive to compression))

    /** Extracts several archives into one runtime directory (e.g. a universal part plus a per-CPU part). */
    fun install(name: String, archives: List<Pair<InputStream, String>>): JavaRuntime {
        val staging = File(root, ".staging-${System.nanoTime()}")
        try {
            staging.mkdirs()
            for ((archive, compression) in archives) {
                val raw = BufferedInputStream(archive)
                val decompressed: InputStream = when (compression) {
                    "xz" -> XZCompressorInputStream(raw)
                    "gz" -> GzipCompressorInputStream(raw)
                    else -> raw
                }
                TarArchiveInputStream(decompressed).use { extractTar(it, staging) }
            }

            // Accept both flat archives and archives with a single top-level directory.
            val home = findHome(staging) ?: throw IOException("Archive does not look like a Java runtime (no 'release' file)")
            // Java 8 builds ship their jars Pack200-compressed; the JVM cannot start without rt.jar.
            unpackPackedJars(home)
            val info = describe(home) ?: throw IOException("Could not read Java version from runtime")
            val base = sanitize(name)
            val target = File(root, if (info.majorVersion.toString() in base) base else "$base-${info.majorVersion}")
            target.deleteRecursively()
            if (!home.renameTo(target)) throw IOException("Failed to move runtime into place")
            return describe(target)!!
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Expands every `*.jar.pack` next to where it lies (pure-Java Pack200, so it also works on Android).
     * This implementation only reads older Pack200 versions: it handles resource-only jars but not the class jars
     * of a JDK 8 runtime (`rt.jar.pack` …), which need the native `unpack200` tool — those fail here with a clear error
     * instead of leaving a runtime that can never start.
     */
    private fun unpackPackedJars(home: File) {
        val packed = home.walkTopDown().filter { it.isFile && it.name.endsWith(".jar.pack") }.toList()
        for (pack in packed) {
            val out = File(pack.parentFile, pack.name.removeSuffix(".pack"))
            try {
                Archive(pack.absolutePath, out.absolutePath).apply { setQuiet(true) }.unpack()
            } catch (e: Exception) {
                throw IOException(
                    "This runtime stores its libraries in Pack200 format (${pack.name}), which the launcher cannot expand yet. " +
                        "Java 8 runtimes are not supported; use Java 17 or newer. (${e.message})", e,
                )
            }
            pack.delete()
        }
    }

    private fun extractTar(tar: TarArchiveInputStream, dest: File) {
        val canonicalDest = dest.canonicalPath + File.separator
        while (true) {
            val entry = tar.nextEntry ?: break
            val out = File(dest, entry.name)
            if (!out.canonicalPath.startsWith(canonicalDest) && out.canonicalPath != dest.canonicalPath) {
                throw IOException("Blocked path traversal entry: ${entry.name}")
            }
            when {
                entry.isDirectory -> out.mkdirs()
                entry.isSymbolicLink -> {
                    out.parentFile?.mkdirs()
                    out.delete()
                    Files.createSymbolicLink(out.toPath(), File(entry.linkName).toPath())
                }
                else -> {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                    if (entry.mode and 0b001_001_001 != 0) out.setExecutable(true, false)
                }
            }
        }
    }

    private fun findHome(dir: File): File? {
        if (File(dir, "release").isFile) return dir
        return dir.listFiles().orEmpty().filter { it.isDirectory }.firstNotNullOfOrNull { child ->
            child.takeIf { File(it, "release").isFile }
        }
    }

    private fun describe(home: File): JavaRuntime? {
        val release = File(home, "release").takeIf { it.isFile } ?: return null
        val version = release.readLines()
            .firstOrNull { it.startsWith("JAVA_VERSION=") }
            ?.substringAfter('=')?.trim('"') ?: return null
        return JavaRuntime(home.name, home, parseMajor(version), version)
    }

    companion object {
        /** "1.8.0_402" → 8, "17.0.10" → 17, "21" → 21 */
        fun parseMajor(version: String): Int {
            val parts = version.split('.', '_', '-', '+')
            val first = parts[0].toIntOrNull() ?: return 0
            return if (first == 1) parts.getOrNull(1)?.toIntOrNull() ?: 0 else first
        }

        private fun sanitize(name: String) =
            name.substringBefore(".tar").replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "jre" }
    }
}
