package com.quantumobile.launcher.core

import com.quantumobile.launcher.core.tasks.TaskRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRunnerTest {
    private val events = mutableListOf<Boolean>()
    private fun runner() = TaskRunner(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)) { events += it }

    @Test
    fun reportsProgressAndDeliversResult() = runBlocking {
        val runner = runner()
        val gate = CompletableDeferred<Unit>()
        var result: Result<String>? = null
        val job = runner.launch("Install", { rep ->
            rep.report("Downloading", 40)
            gate.await()
            "ok"
        }) { result = it }

        val info = runner.tasks.value.single()
        assertEquals("Install", info.title)
        assertEquals("Downloading", info.message)
        assertEquals(40, info.percent)
        assertTrue(runner.isActive)

        gate.complete(Unit)
        job.join()
        assertEquals("ok", result!!.getOrThrow())
        assertFalse(runner.isActive)
        assertEquals(listOf(true, false), events)
    }

    @Test
    fun failureIsDeliveredAndTaskRemoved() = runBlocking {
        val runner = runner()
        var result: Result<Unit>? = null
        runner.launch<Unit>("Boom", { throw IllegalStateException("nope") }) { result = it }.join()
        assertEquals("nope", result!!.exceptionOrNull()!!.message)
        assertTrue(runner.tasks.value.isEmpty())
    }

    @Test
    fun cancellationRemovesTaskWithoutCallingResult() = runBlocking {
        val runner = runner()
        var called = false
        val job = runner.launch<Unit>("Long", { CompletableDeferred<Unit>().await() }) { called = true }
        assertTrue(runner.isActive)
        job.cancelAndJoin()
        assertFalse(called)
        assertFalse(runner.isActive)
        assertEquals(listOf(true, false), events)
    }

    @Test
    fun serviceStaysActiveUntilLastTaskEnds() = runBlocking {
        val runner = runner()
        val a = CompletableDeferred<Unit>()
        val b = CompletableDeferred<Unit>()
        val ja = runner.launch("A", { a.await() })
        val jb = runner.launch("B", { b.await() })
        assertEquals(listOf(true), events) // one start for two tasks
        a.complete(Unit); ja.join()
        assertEquals(listOf(true), events)
        assertEquals(listOf("B"), runner.tasks.value.map { it.title })
        b.complete(Unit); jb.join()
        assertEquals(listOf(true, false), events)
        assertNull(runner.tasks.value.firstOrNull())
    }

    @Test
    fun taskSurvivesTheCallerGoingAway() = runBlocking {
        // The runner's scope, not the caller's (a screen's lifecycle scope), owns the work.
        val runner = runner()
        val gate = CompletableDeferred<Unit>()
        var done = false
        val callerJob = kotlinx.coroutines.Job()
        val callerScope = CoroutineScope(Dispatchers.Unconfined + callerJob)
        callerScope.launch { runner.launch("Download", { gate.await(); done = true }) }
        callerJob.cancelAndJoin() // the screen is destroyed mid-download
        assertTrue(runner.isActive)
        gate.complete(Unit)
        assertTrue(done)
        assertFalse(runner.isActive)
    }
}
