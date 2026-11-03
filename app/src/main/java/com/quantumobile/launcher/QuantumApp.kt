package com.quantumobile.launcher

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import com.quantumobile.launcher.core.LaunchPreparer
import com.quantumobile.launcher.core.LaunchStages
import com.quantumobile.launcher.core.LaunchState
import com.quantumobile.launcher.core.LauncherPaths
import com.quantumobile.launcher.core.MissingRuntimeException
import com.quantumobile.launcher.core.Settings
import com.quantumobile.launcher.core.afk.AfkFatalException
import com.quantumobile.launcher.core.afk.AfkManager
import com.quantumobile.launcher.core.afk.AfkServer
import com.quantumobile.launcher.core.afk.proto.ServerPing
import com.quantumobile.launcher.core.afk.proto.SrvLookup
import com.quantumobile.launcher.ui.AfkIcons
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.quantumobile.launcher.core.afk.AfkStore
import com.quantumobile.launcher.core.afk.proto.AfkClient
import com.quantumobile.launcher.core.auth.Account
import com.quantumobile.launcher.core.auth.AccountStore
import com.quantumobile.launcher.core.auth.AuthException
import com.quantumobile.launcher.core.auth.MicrosoftAuth
import com.quantumobile.launcher.core.instance.Instance
import com.quantumobile.launcher.core.instance.InstanceStore
import com.quantumobile.launcher.core.lwjgl.LwjglInstaller
import com.quantumobile.launcher.core.lwjgl.LwjglPackage
import com.quantumobile.launcher.core.mc.VersionRepository
import com.quantumobile.launcher.core.runtime.RuntimeCatalog
import com.quantumobile.launcher.core.runtime.RuntimeDownloader
import com.quantumobile.launcher.core.runtime.RuntimeManager
import com.quantumobile.launcher.core.tasks.TaskRunner
import com.quantumobile.launcher.service.AfkService
import com.quantumobile.launcher.service.NetworkMonitor
import com.quantumobile.launcher.service.Notifications
import com.quantumobile.launcher.service.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class QuantumApp : Application() {
    val paths by lazy { LauncherPaths.from(this).also { it.ensureDirs() } }
    val settings by lazy { Settings(this) }
    val accounts by lazy { AccountStore(paths.accountsFile) }
    val instances by lazy { InstanceStore(paths.instancesFile) }
    val versions by lazy { VersionRepository(paths) }
    val runtimes by lazy { RuntimeManager(paths.runtimes) }
    val network by lazy { NetworkMonitor(this) }

    /** AFK bots: one lightweight connection per server whose switch is on (no game client involved). */
    val afk by lazy {
        AfkManager(
            store = AfkStore(paths.afkFile),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            connector = AfkClient(dnsServers = { network.dnsServers() }),
            account = ::afkAccount,
            network = network.online,
            onServerIcon = { id, png -> runCatching { AfkIcons.saveServerIcon(this, id, png) } },
        )
    }
    private val accountRefresh = Mutex()

    /** Long-running work (game install, runtime and mod downloads) that must outlive any single screen. */
    val tasks by lazy {
        TaskRunner(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) { active -> if (active) TaskService.start(this) }
    }

    private val _launchState = MutableStateFlow<LaunchState>(LaunchState.Idle)
    val launchState: StateFlow<LaunchState> get() = _launchState
    private var launchJob: Job? = null

    private var startedActivities = 0
    /** True while any launcher screen is visible. */
    val isForeground get() = startedActivities > 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashLogger()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) { startedActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * Writes any crash to `logs/crash-<process>.txt` (next to the game log) before the process dies, so a user who says
     * "the app closed" can share what actually happened. Runs in both the launcher and the game process.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val process = if (Build.VERSION.SDK_INT >= 28) getProcessName().substringAfter(':', "main") else "main"
                val dir = java.io.File(getExternalFilesDir(null) ?: filesDir, "logs").apply { mkdirs() }
                java.io.File(dir, "crash-$process.txt").writeText(
                    "${java.util.Date()}  v${BuildConfig.VERSION_NAME}  thread=${thread.name}\n" +
                        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n" +
                        android.util.Log.getStackTraceString(error),
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Installs missing files and prepares the launch. Keeps running in the background; see [launchState]. */
    fun startLaunch(instance: Instance, width: Int, height: Int) {
        if (launchJob?.isActive == true) return
        _launchState.value = LaunchState.Working("Account", 0, 1)
        launchJob = tasks.launch(
            getString(R.string.preparing_game, instance.name),
            block = { reporter ->
                LaunchPreparer(this).prepare(instance, width, height) { stage, done, total ->
                    _launchState.value = LaunchState.Working(stage, done, total)
                    reporter.report(getString(LaunchStages.label(stage)), if (total > 1) done * 100 / total else -1)
                }
            },
            onResult = { result ->
                result.onSuccess { launch ->
                    if (isForeground) {
                        _launchState.value = LaunchState.Ready(launch)
                    } else {
                        // Starting an activity from the background is blocked; let the user tap a notification.
                        _launchState.value = LaunchState.Idle
                        Notifications.readyToPlay(this, launch)
                    }
                }.onFailure { e ->
                    val message = e.message ?: e.javaClass.simpleName
                    _launchState.value = LaunchState.Failed(message, (e as? MissingRuntimeException)?.required)
                    if (!isForeground) Notifications.done(this, getString(R.string.launch_failed), message)
                }
            },
        )
    }

    /** True when the Android LWJGL build is installed for this device's ABI. */
    fun isLwjglInstalled(): Boolean {
        val pkg = LwjglPackage.loadBundled(this)
        val abi = pkg.abiFor(Build.SUPPORTED_ABIS.toList()) ?: return false
        return LwjglInstaller(paths).isInstalled(pkg, abi)
    }

    /** Downloads and installs the Android LWJGL build in the background; [onDone] runs on the main thread. */
    fun installLwjgl(onDone: (Result<Unit>) -> Unit = {}) {
        val pkg = LwjglPackage.loadBundled(this)
        val abi = pkg.abiFor(Build.SUPPORTED_ABIS.toList())
            ?: return onDone(Result.failure(IllegalStateException("No LWJGL build for ${Build.SUPPORTED_ABIS.joinToString()}")))
        val title = getString(R.string.installing_lwjgl)
        tasks.launch(
            title,
            block = { reporter ->
                LwjglInstaller(paths).install(pkg, abi) { done, total -> reporter.report(title, if (total > 0) done * 100 / total else -1) }
            },
            onResult = { result ->
                if (!isForeground) {
                    result.onSuccess { Notifications.done(this, title, getString(R.string.lwjgl_done)) }
                        .onFailure { Notifications.done(this, getString(R.string.lwjgl_failed), it.message) }
                }
                onDone(result)
            },
        )
    }

    private var prefetching = false

    /**
     * Called when the launcher opens: fetches what the game needs in the background so the first Play is quick.
     * Quiet on failure (the same downloads are retried, with errors shown, when the user presses Play).
     */
    fun prefetchComponents() {
        if (prefetching) return
        prefetching = true
        if (settings.autoLwjgl && !isLwjglInstalled()) installLwjgl { prefetching = false }
        else prefetching = false

        // A default Java so the most common modern versions start immediately; other versions are fetched on demand.
        if (settings.autoRuntime && runtimes.list().isEmpty()) {
            val title = getString(R.string.downloading_runtime, "Java 21")
            tasks.launch(title, block = { reporter ->
                val sources = RuntimeCatalog.forDevice(RuntimeCatalog.load(this, settings.runtimeCatalogUrl), Build.SUPPORTED_ABIS.toList())
                val source = RuntimeCatalog.best(sources, 21) ?: return@launch null
                RuntimeDownloader(runtimes, paths.cacheDir).install(source) { done, total ->
                    reporter.report(title, if (total > 0) (done * 100 / total).toInt() else -1)
                }
            })
        }
    }

    /** Resumes the AFK bots for servers whose switch is on and keeps them alive with [AfkService]. */
    fun startAfk() {
        afk.start()
        if (afk.servers.value.any { it.enabled }) AfkService.start(this)
    }

    /** Pings [server] once to save its list icon (for servers that have not been switched on yet). */
    fun fetchAfkServerIcon(server: AfkServer, onDone: () -> Unit = {}) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val srv = if (server.port == AfkServer.DEFAULT_PORT) SrvLookup.resolve(server.host, network.dnsServers()) else null
                ServerPing.query(srv?.host ?: server.host, srv?.port ?: server.port, 8_000).favicon
                    ?.let { AfkIcons.saveServerIcon(this@QuantumApp, server.id, it) }
            }
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    /** The account an AFK bot joins with, refreshed first if its Microsoft token is about to expire. */
    private suspend fun afkAccount(id: String): Account = accountRefresh.withLock {
        val account = accounts.accounts.value.firstOrNull { it.id == id }
            ?: throw AfkFatalException(getString(R.string.afk_account_missing))
        if (!account.needsRefresh) return@withLock account
        try {
            MicrosoftAuth.refresh(account).also { accounts.upsert(it, select = false) }
        } catch (e: AuthException) {
            throw AfkFatalException(getString(R.string.afk_login_expired))
        }
    }

    fun cancelLaunch() {
        launchJob?.cancel()
        _launchState.value = LaunchState.Idle
    }

    fun consumeLaunchState() {
        if (_launchState.value !is LaunchState.Working) _launchState.value = LaunchState.Idle
    }

    companion object {
        lateinit var instance: QuantumApp
            private set
    }
}
