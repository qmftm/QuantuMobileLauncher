package com.quantumobile.launcher.core

import com.quantumobile.launcher.BuildConfig
import com.quantumobile.launcher.QuantumApp
import com.quantumobile.launcher.core.auth.Account
import com.quantumobile.launcher.core.auth.MicrosoftAuth
import com.quantumobile.launcher.core.instance.Instance
import com.quantumobile.launcher.core.mc.GameInstaller
import com.quantumobile.launcher.core.mc.LaunchArgs
import com.quantumobile.launcher.core.mc.LaunchSpec
import com.quantumobile.launcher.core.mc.ProgressListener
import com.quantumobile.launcher.core.mc.RuleEnv
import java.io.File
import java.io.Serializable

/** Everything the game process needs; passed to GameActivity through the intent. */
data class GameLaunch(
    val javaHome: String,
    val args: ArrayList<String>,
    val gameDir: String,
    val env: ArrayList<String>,
    val logPath: String,
) : Serializable

class LaunchException(message: String) : Exception(message)

/** Runs in the launcher process: authenticates, installs files and builds the command line. */
class LaunchPreparer(private val app: QuantumApp) {

    suspend fun prepare(instance: Instance, screenWidth: Int, screenHeight: Int, listener: ProgressListener): GameLaunch {
        val paths = app.paths
        val settings = app.settings

        listener.onProgress("Account", 0, 1)
        val account = refreshedAccount()

        listener.onProgress("Version", 0, 1)
        val version = app.versions.resolve(instance.versionId)
        val gameDir = instance.gameDir(paths).apply { mkdirs() }

        GameInstaller(paths).install(version, gameDir, listener)

        val runtime = app.runtimes.pick(version.javaMajorVersion)
            ?: throw LaunchException(missingRuntimeMessage(version.id, version.javaMajorVersion, app.runtimes.list().map { it.majorVersion }))

        val lwjglOverride = GameInstaller.hasLwjglOverride(paths)
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
            add("-Dorg.lwjgl.librarypath=${paths.nativeLibDir}")
            add("-Dorg.lwjgl.opengl.libname=${settings.rendererLibrary}")
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
            nativesDir = paths.nativeLibDir,
            classpath = classpath,
            width = width,
            height = height,
            memoryMb = memory,
            launcherName = "QuantuMobileLauncher",
            launcherVersion = BuildConfig.VERSION_NAME,
            extraJvmArgs = extraJvm,
            loggingConfigPath = version.logging?.let { File(paths.assets, "log_configs/${it.id}").absolutePath },
        )

        val env = arrayListOf(
            "JAVA_HOME=${runtime.home.absolutePath}",
            "HOME=${paths.gameRoot.parent}",
            "TMPDIR=${tmpDir.absolutePath}",
            "LD_LIBRARY_PATH=${paths.nativeLibDir}",
            "QML_RENDERER=${settings.renderer}",
            "LIBGL_MIPMAP=3",
            "LIBGL_NOINTOVLHACK=1",
            "LIBGL_NORMALIZE=1",
        )
        if (settings.renderer == com.quantumobile.launcher.core.Settings.RENDERER_ZINK) {
            env += "GALLIUM_DRIVER=zink"
            env += "MESA_GL_VERSION_OVERRIDE=4.6"
        }

        paths.logs.mkdirs()
        listener.onProgress("Starting", 1, 1)
        return GameLaunch(
            javaHome = runtime.home.absolutePath,
            args = ArrayList(LaunchArgs.build(spec)),
            gameDir = gameDir.absolutePath,
            env = env,
            logPath = paths.latestLog.absolutePath,
        )
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
