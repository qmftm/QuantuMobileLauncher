package com.quantumobile.launcher.core.mc

import com.quantumobile.launcher.core.net.DownloadTask
import com.quantumobile.launcher.core.net.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * JNA's own jar only carries desktop natives (linked against glibc's libc.so.6), which Android cannot load.
 * JNA also refuses a native from any other release, so the Android build must match the game's JNA version
 * exactly: it comes from that version's .aar on Maven Central (jni/<abi>/libjnidispatch.so).
 */
class JnaNatives(
    private val libraries: File,
    private val nativesRoot: File,
    private val downloader: Downloader = Downloader(),
) {
    /**
     * Returns the folder holding a matching libjnidispatch.so for [abi], ready for `-Djna.boot.library.path`,
     * or null when the game does not use JNA.
     */
    suspend fun ensure(version: VersionInfo, abi: String): File? = withContext(Dispatchers.IO) {
        val jna = jnaVersion(version.libraries.map { it.name }) ?: return@withContext null
        val dir = File(nativesRoot, "$jna/$abi")
        val lib = File(dir, LIB_NAME)
        if (lib.isFile && lib.length() > 0) return@withContext dir

        val aar = File(libraries, "net/java/dev/jna/jna/$jna/jna-$jna.aar")
        downloader.download(DownloadTask("$MAVEN/net/java/dev/jna/jna/$jna/jna-$jna.aar", aar))
        try {
            extract(aar, abi, lib)
        } catch (e: IOException) {
            aar.delete() // a broken download: fetch it again next time
            throw e
        }
        dir
    }

    companion object {
        private const val MAVEN = "https://repo1.maven.org/maven2"
        private const val LIB_NAME = "libjnidispatch.so"

        /** The version of `net.java.dev.jna:jna` among [libraryNames] (maven coordinates). */
        fun jnaVersion(libraryNames: List<String>): String? = libraryNames.firstNotNullOfOrNull { name ->
            runCatching { MavenCoords.parse(name) }.getOrNull()
                ?.takeIf { it.group == "net.java.dev.jna" && it.artifact == "jna" && it.classifier == null }
                ?.version
        }

        /** Copies jni/[abi]/libjnidispatch.so out of [aar] to [dest]. */
        fun extract(aar: File, abi: String, dest: File) {
            ZipFile(aar).use { zip ->
                val entry = zip.getEntry("jni/$abi/$LIB_NAME") ?: throw IOException("${aar.name} has no $LIB_NAME for $abi")
                dest.parentFile?.mkdirs()
                val tmp = File(dest.path + ".part")
                zip.getInputStream(entry).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (!tmp.renameTo(dest)) {
                    tmp.delete()
                    throw IOException("Cannot write ${dest.path}")
                }
            }
        }
    }
}
