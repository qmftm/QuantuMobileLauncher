package com.quantumobile.launcher.core.tasks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** [percent] is 0..100, or -1 when the progress is unknown. */
data class TaskInfo(val id: Long, val title: String, val message: String = "", val percent: Int = -1)

class TaskReporter internal constructor(private val update: (message: String, percent: Int) -> Unit) {
    fun report(message: String, percent: Int = -1) = update(message, percent)
}

/**
 * Runs long operations (installing the game, downloading runtimes and mods) in an application-wide scope so
 * they keep going when the user leaves a screen or the app. [onActiveChanged] fires when the first task starts
 * and when the last one ends; the app uses it to start and stop the foreground service that keeps the
 * process alive.
 */
class TaskRunner(
    private val scope: CoroutineScope,
    private val onActiveChanged: (active: Boolean) -> Unit = {},
) {
    private val _tasks = MutableStateFlow<List<TaskInfo>>(emptyList())
    val tasks: StateFlow<List<TaskInfo>> get() = _tasks
    private var nextId = 0L

    val isActive get() = _tasks.value.isNotEmpty()

    /** [onResult] runs after the task ends normally or with an error; it is not called when cancelled. */
    fun <T> launch(title: String, block: suspend (TaskReporter) -> T, onResult: (Result<T>) -> Unit = {}): Job {
        val id = register(title)
        return scope.launch {
            val result: Result<T>
            try {
                result = try {
                    Result.success(block(TaskReporter { message, percent -> update(id, message, percent) }))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            } finally {
                finish(id)
            }
            onResult(result)
        }
    }

    @Synchronized
    private fun register(title: String): Long {
        val id = ++nextId
        val wasEmpty = _tasks.value.isEmpty()
        _tasks.value = _tasks.value + TaskInfo(id, title)
        if (wasEmpty) onActiveChanged(true)
        return id
    }

    @Synchronized
    private fun update(id: Long, message: String, percent: Int) {
        // Downloads report once per file (thousands of times); only a visible change is worth a notification update.
        if (_tasks.value.any { it.id == id && it.message == message && it.percent == percent }) return
        _tasks.value = _tasks.value.map { if (it.id == id) it.copy(message = message, percent = percent) else it }
    }

    @Synchronized
    private fun finish(id: Long) {
        _tasks.value = _tasks.value.filterNot { it.id == id }
        if (_tasks.value.isEmpty()) onActiveChanged(false)
    }
}
