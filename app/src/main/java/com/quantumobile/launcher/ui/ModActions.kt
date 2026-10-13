package com.quantumobile.launcher.ui

import com.quantumobile.launcher.QuantumApp
import com.quantumobile.launcher.R
import com.quantumobile.launcher.core.mods.InstalledMod
import com.quantumobile.launcher.core.mods.ModHit
import com.quantumobile.launcher.core.mods.ModInstaller
import com.quantumobile.launcher.core.mods.ModTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Download / enable / remove behaviour shared by the Mods and Visual screens. */
object ModActions {
    /** Project ids being downloaded right now; screens watch this to show a spinner on those rows. */
    val active = MutableStateFlow<Set<String>>(emptySet())

    class Outcome(val installed: List<InstalledMod>, val missing: List<String>)

    /** Buttons for a list row: download when not installed, otherwise on/off and remove. */
    fun actionsFor(mod: InstalledMod?, enabled: Boolean): List<RowAction> = when {
        mod == null -> listOf(RowAction.DOWNLOAD)
        enabled -> listOf(RowAction.DISABLE, RowAction.REMOVE)
        else -> listOf(RowAction.ENABLE, RowAction.REMOVE)
    }

    /**
     * Resolves dependencies and downloads [hit] as an app-level task, so it carries on in the background (with a
     * progress notification) whether or not the screen stays open. [onResult] runs on the main thread.
     */
    fun download(app: QuantumApp, installer: ModInstaller, target: ModTarget, hit: ModHit, onResult: (Result<Outcome>) -> Unit) {
        if (hit.projectId in active.value) return
        active.update { it + hit.projectId }
        val title = app.getString(R.string.installing_mods, hit.title)
        app.tasks.launch(
            title,
            block = { reporter ->
                try {
                    reporter.report(app.getString(R.string.resolving), -1)
                    val plan = installer.resolve(hit.projectId, hit.title, hit.type, target)
                    val done = installer.apply(plan) { i, n, name ->
                        reporter.report(app.getString(R.string.installing_item, name, minOf(i + 1, n), n), i * 100 / n)
                    }
                    Outcome(done, plan.missing)
                } finally {
                    active.update { it - hit.projectId }
                }
            },
            onResult = { result ->
                result.onSuccess { out ->
                    if (!app.isForeground) {
                        com.quantumobile.launcher.service.Notifications.done(app, title, app.getString(R.string.installed_count, out.installed.size))
                    }
                }.onFailure { e ->
                    if (!app.isForeground) com.quantumobile.launcher.service.Notifications.done(app, title, e.message)
                }
                onResult(result)
            },
        )
    }
}
