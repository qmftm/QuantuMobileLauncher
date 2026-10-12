package com.quantumobile.launcher.core

import android.content.Context
import java.io.File

/**
 * Directory layout.
 *
 * Game files live in app-specific external storage (`Android/data/<pkg>/files/.minecraft`) so users can
 * add mods/resource packs with a file manager. Java runtimes must live in internal storage because
 * Android does not allow executing or dlopen()ing code from external storage.
 */
class LauncherPaths(
    val gameRoot: File,
    val internalRoot: File,
    val nativeLibDir: String,
    val cacheDir: File,
) {
    val versions = File(gameRoot, "versions")
    val libraries = File(gameRoot, "libraries")
    val assets = File(gameRoot, "assets")
    val instances = File(gameRoot, "instances")
    val logs = File(gameRoot.parentFile ?: gameRoot, "logs")
    val runtimes = File(internalRoot, "runtimes")
    val lwjgl = File(internalRoot, "lwjgl3")
    val lwjglNatives = File(internalRoot, "lwjgl-natives")
    val accountsFile = File(internalRoot, "accounts.json")
    val instancesFile = File(internalRoot, "instances.json")
    val latestLog get() = File(logs, "latest.log")

    fun versionJson(id: String) = File(versions, "$id/$id.json")
    fun versionJar(id: String) = File(versions, "$id/$id.jar")
    fun nativesDir(id: String) = File(versions, "$id/natives")

    fun ensureDirs() {
        listOf(gameRoot, versions, libraries, assets, instances, logs, runtimes, lwjgl).forEach { it.mkdirs() }
    }

    companion object {
        fun from(context: Context): LauncherPaths {
            val external = context.getExternalFilesDir(null) ?: context.filesDir
            return LauncherPaths(
                gameRoot = File(external, ".minecraft"),
                internalRoot = context.filesDir,
                nativeLibDir = context.applicationInfo.nativeLibraryDir,
                cacheDir = context.cacheDir,
            )
        }
    }
}
