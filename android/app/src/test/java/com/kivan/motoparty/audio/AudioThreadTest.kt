package com.kivan.motoparty.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** [AudioThread] on the JVM: `android.util.Log` no-ops (`isReturnDefaultValues`). */
class AudioThreadTest {
    /** Stands in for Main: failures must come back here, not on the audio thread. */
    private val main: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(main)
    private val failures = Collections.synchronizedList(mutableListOf<Pair<String, Throwable>>())
    private val audio = AudioThread(scope) { what, e -> failures += what to e }

    @After
    fun tearDown() {
        audio.shutdown()
        main.close()
    }

    @Test
    fun postsRunInOrderOneAtATimeOnOneThread() = runBlocking {
        val ran = Collections.synchronizedList(mutableListOf<Int>())
        val threads = Collections.synchronizedSet(mutableSetOf<String>())
        var inside = 0
        var overlapped = false
        val jobs = (0 until 50).map { i ->
            audio.post("step $i") {
                if (++inside > 1) overlapped = true
                threads += Thread.currentThread().name
                if (i % 7 == 0) Thread.sleep(2) // an uneven workload must not reorder anything
                ran += i
                inside--
            }
        }
        withTimeout(5_000) { jobs.last().join() }
        assertEquals((0 until 50).toList(), ran.toList())
        assertEquals(setOf("motoparty-audio"), threads.toSet()) // a plain thread: no coroutine suffix
        assertFalse(overlapped)
        assertTrue(jobs.all { it.isCompleted })
    }

    @Test
    fun failureReachesSharedHandlerOnCallerScopeAndThreadSurvives() = runBlocking {
        val boom = IllegalStateException("route")
        val failed = audio.post("talk audio") { throw boom }
        withTimeout(5_000) { failed.join() }
        // The job completes normally: a failure is reported, not rethrown into the joiner.
        assertFalse(failed.isCancelled)
        val after = CompletableDeferred<Unit>()
        audio.post("next") { after.complete(Unit) }
        withTimeout(5_000) { after.await() }
        withTimeout(5_000) { while (failures.isEmpty()) delay(5) }
        assertEquals(1, failures.size)
        assertEquals("talk audio", failures[0].first)
        assertSame(boom, failures[0].second)
    }

    @Test
    fun ownHandlerReplacesSharedOneAndRunsOnCallerScope() = runBlocking {
        val got = CompletableDeferred<Pair<String, Throwable>>()
        val boom = RuntimeException("mic")
        audio.post("talk audio", onError = { got.complete(Thread.currentThread().name to it) }) { throw boom }
        val (thread, e) = withTimeout(5_000) { got.await() }
        assertTrue(thread, thread.startsWith("test-main")) // debug builds append " @coroutine#n"
        assertSame(boom, e)
        // Flush both threads, then check the shared handler stayed quiet.
        withTimeout(5_000) { audio.post("flush") {}.join() }
        withTimeout(5_000) { scope.launch {}.join() }
        assertTrue(failures.isEmpty())
    }

    @Test
    fun failureIsDeliveredBeforeTheJobCompletes() = runBlocking {
        // LinkHost relies on this: onMicFailed closes talk before the live-earcon waiter wakes.
        val order = Collections.synchronizedList(mutableListOf<String>())
        val gate = CountDownLatch(1)
        val job = audio.post("talk audio", onError = { order += "error" }) {
            gate.await(5, TimeUnit.SECONDS)
            error("no route")
        }
        scope.launch { job.join(); order += "joined" }
        withTimeout(5_000) { scope.launch {}.join() } // the waiter is now suspended in join()
        gate.countDown()
        withTimeout(5_000) { while (order.size < 2) delay(5) }
        assertEquals(listOf("error", "joined"), order.toList())
    }

    @Test
    fun cancellingTheWaiterDoesNotCancelTheWork() = runBlocking {
        val gate = CountDownLatch(1)
        val ran = CountDownLatch(2)
        audio.post("slow") { gate.await(5, TimeUnit.SECONDS); ran.countDown() }
        val queued = audio.post("exit call") { ran.countDown() }
        val waiter = scope.launch { queued.join() }
        waiter.cancelAndJoin()
        gate.countDown()
        assertTrue(ran.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { queued.join() }
    }

    @Test
    fun postAfterShutdownIsDroppedButQueuedWorkStillRuns() = runBlocking {
        val gate = CountDownLatch(1)
        val ran = Collections.synchronizedList(mutableListOf<String>())
        audio.post("slow") { gate.await(5, TimeUnit.SECONDS); ran += "slow" }
        val queued = audio.post("teardown") { ran += "teardown" }
        audio.shutdown()
        val late = audio.post("late") { ran += "late" }
        // Already complete, so a waiter never hangs on work that will not happen.
        assertTrue(late.isCompleted)
        gate.countDown()
        withTimeout(5_000) { queued.join() }
        assertEquals(listOf("slow", "teardown"), ran.toList())
        assertTrue(failures.isEmpty())
    }
}
