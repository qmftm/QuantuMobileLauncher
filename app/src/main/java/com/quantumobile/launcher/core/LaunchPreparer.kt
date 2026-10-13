package com.quantumobile.launcher.core

import android.os.Build
import com.quantumobile.launcher.BuildConfig
import com.quantumobile.launcher.QuantumApp
import com.quantumobile.launcher.core.auth.Account
import com.quantumobile.launcher.core.auth.MicrosoftAuth
import com.quantumobile.launcher.core.instance.Instance
import com.quantumobile.launcher.core.lwjgl.LwjglInstaller
import com.quantumobile.launcher.core.lwjgl.LwjglPackage
import com.quantumobile.launcher.core.mc.GameInstaller
import com.quantumobile.launcher.core.mc.LaunchArgs
import com.quantumobile.launcher.core.mc.LaunchSpec
import com.quantumobile.launcher.core.mc.ProgressListener
import com.quantumobile.launcher.core.mc.RuleEnv
import com.quantumobile.launcher.core.runtime.RuntimeCatalog
import com.quantumobile.launcher.core.runtime.RuntimeDownloader
import java.io.File
import java.io.Serializable

/** Everything the game process needs; passed to GameActivity through the intent. */
data class GameLaunch(
    val javaHome: String,
    val args: ArrayList<String>,
    val gameDir: String,
    val env: ArrayList<String>,
    val logPath: String,
    /** The app's own native library directory (libpojavexec, libopenal ...). */
    val nativeLibDir: String,
    /** Extra directories for the dynamic linker (the downloaded LWJGL natives). */
    val libraryPaths: ArrayList<String>,
    /** Minecraft 26.x and newer: SDL3 window, LWJGL 3.4 (see [com.quantumobile.launcher.core.mc.AndroidSupport]). */
    val sdl: Boolean = false,
) : Serializable

/** The only renderer the bridge supports: GL4ES on top of GLES 2/3. */
private const val RENDERER_LIB = "libgl4es_114.so"

open class LaunchException(message: String) : Exception(message)

/** No installed runtime satisfies [required]; the UI offers to download one. */
class MissingRuntimeException(val required: Int, message: String) : LaunchException(message)

/** Runs in the launcher process: authenticates, installs files and builds the command line. */
class LaunchPreparer(private val app: QuantumApp) {

    suspend fun prepare(instance: Instance, screenWidth: Int, screenHeight: Int, listener: ProgressListener): GameLaunch {
        val paths = app.paths
        val settings = app.settings

        listener.onProgress("Account", 0, 1)
        val account = refreshedAccount()

        val lwjgl = ensureLwjgl(listener)

        listener.onProgress("Version", 0, 1)
        val version = app.versions.resolve(instance.versionId)
        val sdl = com.quantumobile.launcher.core.mc.AndroidSupport.needsSdl(version.libraries.map { it.name })
        if (sdl && "arm64-v8a" !in Build.SUPPORTED_ABIS) {
            throw LaunchException("${version.id} needs the SDL3 backend, which is only built for 64-bit ARM (arm64-v8a) devices.")
        }
        val gameDir = instance.gameDir(paths).apply { mkdirs() }

        GameInstaller(paths).install(version, gameDir, listener, useMojangLwjgl = sdl)

        val runtime = app.runtimes.pick(version.javaMajorVersion)
            ?: installRuntimeAutomatically(version.javaMajorVersion, listener)
            ?: throw MissingRuntimeException(
                version.javaMajorVersion,
                missingRuntimeMessage(version.id, version.javaMajorVersion, app.runtimes.list().map { it.majorVersion }),
            )

        // 26.x brings its own LWJGL 3.4.3 jars (the bundled 3.3.3 GLFW build cannot run it).
        val lwjglOverride = !sdl && GameInstaller.hasLwjglOverride(paths)
        val classpath = buildList {
            if (lwjglOverride) paths.lwjgl.listFiles { f -> f.name.endsWith(".jar") }!!.sorted().forEach { add(it.absolutePath) }
            GameInstaller.classpathLibraries(version, RuleEnv(), lwjglOverride).forEach {
                add(File(paths.libraries, it.path).absolutePath)
            }
            add(paths.versionJar(version.jarId).absolutePath)
        }

        val scale = settings.resolutionPercent / 100f
        val width = (screenWidth * scale).toInt()
        val height = (screenHeight * scale).toInt()
        val memory = if (instance.memoryMb > 0) instance.memoryMb else settings.memoryMb
        val tmpDir = File(paths.cacheDir, "jvm-tmp").apply { mkdirs() }

        val extraJvm = buildList {
            add("-Djava.io.tmpdir=${tmpDir.absolutePath}")
            add("-Duser.home=${paths.gameRoot.parent}")
            add("-Dos.name=Linux")
            add("-Djava.home=${runtime.home.absolutePath}")
            if (!sdl) {
                add("-Dglfwstub.windowWidth=$width")
                add("-Dglfwstub.windowHeight=$height")
                add("-Dglfwstub.initEgl=false")
            }
            add("-Dorg.lwjgl.vulkan.libname=libvulkan.so")
            add("-Djdk.lang.Process.launchMechanism=FORK")
            add("-Dnet.minecraft.clientmodname=QuantuMobileLauncher")
            add("-XX:ActiveProcessorCount=${Runtime.getRuntime().availableProcessors()}")
            val nativesDir = lwjgl?.let { LwjglInstaller(paths).nativesDir(it.abi) }
            if (sdl) {
                // LWJGL 3.4.3 natives and SDL3 ship inside the app; OpenAL and FreeType come from the downloaded set.
                val own = paths.nativeLibDir
                add("-Dorg.lwjgl.librarypath=$own")
                add("-Dorg.lwjgl.sdl.libname=$own/libSDL3.so")
                nativesDir?.let {
                    add("-Dorg.lwjgl.openal.libname=${File(it, "libopenal.so").absolutePath}")
                    add("-Dorg.lwjgl.freetype.libname=${File(it, "libfreetype.so").absolutePath}")
                    add("-Djna.boot.library.path=${it.absolutePath}")
                }
            } else if (nativesDir != null) {
                add("-Dorg.lwjgl.librarypath=${nativesDir.absolutePath}")
                add("-Dorg.lwjgl.freetype.libname=${File(nativesDir, "libfreetype.so").absolutePath}")
                add("-Djna.boot.library.path=${nativesDir.absolutePath}")
                val gl = File(nativesDir, RENDERER_LIB)
                add("-Dorg.lwjgl.opengl.libname=${if (gl.isFile) gl.absolutePath else RENDERER_LIB}")
            } else {
                add("-Dorg.lwjgl.librarypath=${paths.nativeLibDir}")
                add("-Dorg.lwjgl.opengl.libname=${RENDERER_LIB}")
            }
            add("-Dorg.lwjgl.util.NoChecks=true")
            add("-Dfml.earlyprogresswindow=false")
            add("-Dfml.earlyWindowControl=false")
            add("-Dloader.disable_forked_guis=true")
            addAll(splitArgs(settings.jvmArgs))
            addAll(splitArgs(instance.extraJvmArgs))
        }

        val spec = LaunchSpec(
            version = version,
            playerName = account.username,
            playerUuid = account.uuid,
            accessToken = account.accessToken,
            userType = account.userType,
            xuid = account.xuid,
            gameDir = gameDir.absolutePath,
            assetsRoot = paths.assets.absolutePath,
            librariesDir = paths.libraries.absolutePath,
            // ${natives_directory}: newer versions create java/, jna/, lwjgl/ and netty/ folders under it, so it must be writable
            // (the app's own library folder is read-only).
            nativesDir = File(paths.internalRoot, "natives").apply { mkdirs() }.absolutePath,
            classpath = classpath,
            width = width,
            height = height,
            memoryMb = memory,
            launcherName = "QuantuMobileLauncher",
            launcherVersion = BuildConfig.VERSION_NAME,
            extraJvmArgs = extraJvm,
            loggingConfigPath = version.logging?.let { File(paths.assets, "log_configs/${it.id}").absolutePath },
        )

        val nativesPath = lwjgl?.let { LwjglInstaller(paths).nativesDir(it.abi).absolutePath }
        val libraryPaths = arrayListOf<String>().apply {
            if (sdl) add(paths.nativeLibDir)
            if (nativesPath != null) add(nativesPath)
            if (!sdl) add(paths.nativeLibDir)
        }
        val env = arrayListOf(
            "POJAV_NATIVEDIR=${paths.nativeLibDir}",
            "JAVA_HOME=${runtime.home.absolutePath}",
            "HOME=${paths.gameRoot.parent}",
            "TMPDIR=${tmpDir.absolutePath}",
            "LIBGL_MIPMAP=3",
            "LIBGL_NOERROR=1",
            "LIBGL_NOINTOVLHACK=1",
            "LIBGL_NORMALIZE=1",
            "LIBGL_ES=2",
            "FORCE_VSYNC=false",
            "MESA_GLSL_CACHE_DIR=${paths.cacheDir.absolutePath}",
            "POJAV_RENDERER=opengles2",
            "AWTSTUB_WIDTH=$width",
            "AWTSTUB_HEIGHT=$height",
        )

        if (sdl) {
            // MobileGlues is SDL's EGL library: it hands the game a desktop-GL-looking context on top of the phone's GLES driver.
            env += "SDL_EGL_LIBRARY=${paths.nativeLibDir}/libmobileglues.so"
            env += "MG_DIR_PATH=${File(paths.internalRoot, "mobileglues").apply { mkdirs() }.absolutePath}"
            env += "SDL_VIDEODRIVER=android"
            env += "SDL_AUDIODRIVER=android"
        }

        paths.logs.mkdirs()
        listener.onProgress("Starting", 1, 1)
        return GameLaunch(
            javaHome = runtime.home.absolutePath,
            args = ArrayList(
                LaunchArgs.ensureLibraryPath(LaunchArgs.build(spec), libraryPaths) +
                    // 26.x defaults to Vulkan, whose shader compiler (shaderc) is not available yet: use the OpenGL backend.
                    if (sdl) listOf("--graphicsBackend", "opengl") else emptyList(),
            ),
            gameDir = gameDir.absolutePath,
            env = env,
            logPath = paths.latestLog.absolutePath,
            nativeLibDir = paths.nativeLibDir,
            libraryPaths = libraryPaths,
            sdl = sdl,
        )
    }

    /** The LWJGL build in use: which ABI's natives are installed. Null when the user manages LWJGL jars manually. */
    private class LwjglInUse(val abi: String)

    /**
     * Makes sure the Android LWJGL build is installed (downloading it when the setting allows). Without it the game
     * would run on Mojang's desktop LWJGL, whose x86/glibc natives cannot load on Android.
     */
    private suspend fun ensureLwjgl(listener: ProgressListener): LwjglInUse? {
        val pkg = LwjglPackage.loadBundled(app)
        val abi = pkg.abiFor(Build.SUPPORTED_ABIS.toList()) ?: return null
        val installer = LwjglInstaller(app.paths)
        if (!installer.isInstalled(pkg, abi)) {
            if (!app.settings.autoLwjgl) {
                // Honour manually imported jars; otherwise the launch cannot work, so say so clearly.
                if (GameInstaller.hasLwjglOverride(app.paths)) return null
                throw LaunchException("The Android LWJGL libraries are not installed. Install them from Settings, or turn on automatic installation.")
            }
            listener.onProgress("LWJGL", 0, 100)
            try {
                installer.install(pkg, abi) { done, total -> listener.onProgress("LWJGL", if (total > 0) done * 100 / total else 0, 100) }
            } catch (e: java.io.IOException) {
                throw LaunchException("Could not download the LWJGL libraries: ${e.message}")
            }
        }
        return LwjglInUse(abi)
    }

    /**
     * Downloads the right Java runtime from the catalog when the user has none, so the first launch "just works".
     * Returns null (and lets the caller show the normal error) when the setting is off, the catalog is unreachable
     * or has nothing for this device.
     */
    private suspend fun installRuntimeAutomatically(required: Int, listener: ProgressListener): com.quantumobile.launcher.core.runtime.JavaRuntime? {
        if (!app.settings.autoRuntime) return null
        listener.onProgress("Runtime", 0, 100)
        val sources = runCatching {
            RuntimeCatalog.forDevice(RuntimeCatalog.load(app, app.settings.runtimeCatalogUrl), Build.SUPPORTED_ABIS.toList())
        }.getOrNull() ?: return null
        val source = RuntimeCatalog.best(sources, required) ?: return null
        RuntimeDownloader(app.runtimes, app.paths.cacheDir).install(source) { done, total ->
            listener.onProgress("Runtime", if (total > 0) (done * 100 / total).toInt() else 0, 100)
        }
        return app.runtimes.pick(required)
    }

    private suspend fun refreshedAccount(): Account {
        val account = app.accounts.selected ?: throw LaunchException("Add an account first")
        if (!account.needsRefresh) return account
        return MicrosoftAuth.refresh(account).also { app.accounts.upsert(it) }
    }

    companion object {
        fun missingRuntimeMessage(versionId: String, required: Int, installed: List<Int>): String {
            val have = if (installed.isEmpty()) "none" else installed.joinToString { "Java $it" }
            return "$versionId needs Java $required or newer, but the installed runtimes are: $have.\n\n" +
                "Import an Android-built Java $required runtime (.tar.xz) in Settings → Import Java runtime. " +
                "Desktop JDKs do not work on Android. Java 25 builds are new, so you may need a recent or self-built JRE."
        }

        /** Splits a user-entered argument string on whitespace, honouring double quotes. */
        fun splitArgs(input: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var quoted = false
            var has = false
            for (c in input) {
                when {
                    c == '"' -> { quoted = !quoted; has = true }
                    c.isWhitespace() && !quoted -> {
                        if (has) out += cur.toString()
                        cur.clear(); has = false
                    }
                    else -> { cur.append(c); has = true }
                }
            }
            if (has) out += cur.toString()
            return out
        }
    }
}
