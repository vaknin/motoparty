package com.kivan.motoparty.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * [TalkAudio] on the real [AudioThread] with a fake router (ref-counted like [AudioRouter]) and a
 * fake voice engine. Bench finding (2026-09-19 D1 run 2): open → close → open 300 ms apart ran
 * the whole open, close and open again and reached HFP 2124 ms after the last request.
 */
class TalkAudioTest {
    private val audio = AudioThread(CoroutineScope(Dispatchers.Unconfined)) { _, _ -> }
    private val ops = Collections.synchronizedList(mutableListOf<String>())
    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private val failures = Collections.synchronizedList(mutableListOf<Pair<Int, String>>())

    @Volatile private var depth = 0
    @Volatile private var voiceOn = false
    @Volatile private var larkOn = false
    @Volatile private var voiceFail: ((String, Throwable) -> Unit)? = null
    @Volatile private var enterGate: CountDownLatch? = null
    @Volatile private var stopGate: CountDownLatch? = null
    @Volatile private var exitGate: CountDownLatch? = null
    @Volatile private var enterThrows = false
    @Volatile private var jitterMs = 0

    private fun pause() { if (jitterMs > 0) Thread.sleep(Random.nextLong(jitterMs.toLong())) }

    private val talk = TalkAudio(
        audio,
        enterCall = {
            depth++ // counts itself first, like AudioRouter.enterCall
            ops += "enter"
            enterGate?.await(5, TimeUnit.SECONDS)
            pause()
            if (enterThrows) error("no route")
        },
        exitCall = {
            if (depth > 0) depth--
            ops += "exit"
            exitGate?.await(5, TimeUnit.SECONDS)
            pause()
        },
        voiceStart = { onFailed ->
            ops += "start"
            voiceOn = true
            voiceFail = onFailed
        },
        voiceStop = {
            ops += "stop"
            stopGate?.await(5, TimeUnit.SECONDS)
            pause()
            voiceOn = false
        },
        voiceRunning = { voiceOn },
        closedEarcon = { ops += "earcon" },
        onFailed = { session, what, _ -> failures += session to what },
        log = { logs += it },
        larkStart = { onFailed ->
            ops += "lark start"
            larkOn = true
            voiceFail = onFailed
        },
        larkStop = {
            ops += "lark stop"
            larkOn = false
        },
        larkRunning = { larkOn },
    )

    @After
    fun tearDown() = audio.shutdown()

    private fun Job.await() = runBlocking { withTimeout(5_000) { join() } }

    @Test
    fun closeAndReopenQueuedBehindASlowOpenCollapse() {
        enterGate = CountDownLatch(1)
        talk.open(1)
        while ("enter" !in ops) Thread.sleep(1) // the HFP switch is in flight...
        talk.close() // ...when the close and the re-open are requested
        val last = talk.open(2)
        enterGate!!.countDown()
        last.await()
        assertEquals(listOf("enter", "start"), ops.toList()) // no teardown, no second switch
        assertEquals(1, depth)
        assertTrue(logs.toString(), logs.any { it.startsWith("re-open before teardown") })

        talk.close().await()
        assertEquals(listOf("enter", "start", "stop", "exit", "earcon"), ops.toList())
        assertEquals(0, depth)
    }

    @Test
    fun reopenWhileTheTeardownIsStoppingTheVoiceKeepsTheRoute() {
        talk.open(1).await()
        stopGate = CountDownLatch(1)
        talk.close()
        while ("stop" !in ops) Thread.sleep(1) // voice.stop is joining its threads...
        val last = talk.open(2) // ...when talk re-opens
        stopGate!!.countDown()
        last.await()
        assertEquals(listOf("enter", "start", "stop", "start"), ops.toList()) // no exit + enter
        assertEquals(1, depth)
        assertTrue(voiceOn)
        assertTrue(logs.toString(), logs.any { it.startsWith("re-open during teardown") })

        talk.close().await()
        assertEquals(0, depth)
        assertEquals("earcon", ops.last())
    }

    /**
     * The 2026-09-20 AirPods bench (`results/2026-09-20-d1-talk-4`): the re-open reached the state
     * machine 11 ms *after* `exitCall` returned, and the system then processed our
     * `clearCommunicationDevice` (173.078) after the next `enterCall` had already set the device
     * (173.077). With Main no longer blocked the re-open arrives during the teardown, and the
     * worst case left is this one — it arrives while `exitCall` itself is inside the Bluetooth
     * stack, where nothing can call it back. What must hold then: the request is taken at once
     * (no Main thread waits for the audio thread), and the route is re-entered exactly once,
     * after the exit has finished — never a clear and a set issued back to back.
     */
    @Test
    fun reopenWhileExitCallIsBlockedReentersTheRouteExactlyOnce() {
        talk.open(1).await()
        exitGate = CountDownLatch(1)
        talk.close()
        while ("exit" !in ops) Thread.sleep(1) // the switch back to A2DP is in flight...
        val last = talk.open(2) // ...when talk re-opens
        assertFalse("open must not wait for the audio thread", last.isCompleted)
        assertEquals(listOf("enter", "start", "stop", "exit"), ops.toList())

        exitGate!!.countDown()
        last.await()
        // One enter after the exit finished, and no second exit: the pair is never issued twice.
        assertEquals(listOf("enter", "start", "stop", "exit", "earcon", "enter", "start"), ops.toList())
        assertEquals(1, depth)
        assertTrue(voiceOn)

        exitGate = null
        talk.close().await()
        assertEquals(0, depth)
        assertEquals(2, ops.count { it == "exit" })
    }

    @Test
    fun anySequenceEndsInTheLastRequestedStateWithBalancedReferences() {
        jitterMs = 3
        val rnd = Random(7)
        repeat(40) { round ->
            var session = 0
            var open = false
            var last: Job? = null
            repeat(rnd.nextInt(1, 8)) {
                open = !open
                last = if (open) talk.open(++session) else talk.close()
                if (rnd.nextBoolean()) Thread.sleep(rnd.nextLong(4))
            }
            last!!.await()
            assertEquals("round $round depth", if (open) 1 else 0, depth)
            assertEquals("round $round voice", open, voiceOn)
            if (open) talk.close().await()
            assertEquals(0, depth)
            val enters = ops.count { it == "enter" }
            assertEquals("every enter has its exit", enters, ops.count { it == "exit" })
            assertEquals("an earcon per real teardown", enters, ops.count { it == "earcon" })
        }
    }

    @Test
    fun failedRouteReportsItsSessionAndTheCloseStillBalancesIt() {
        enterThrows = true
        talk.open(1).await()
        assertEquals(listOf(1 to "call route failed"), failures.toList())
        assertEquals(1, depth) // counted before it threw
        talk.close().await()
        assertEquals(0, depth)
    }

    @Test
    fun voiceKeptAcrossACollapsedReopenReportsUnderTheNewSession() {
        enterGate = CountDownLatch(1)
        talk.open(1)
        talk.close()
        val last = talk.open(2)
        enterGate!!.countDown()
        last.await()
        voiceFail!!("capture", IllegalStateException("AudioRecord did not start"))
        assertEquals(listOf(2 to "capture"), failures.toList())
    }

    @Test
    fun reopenAfterTheCarriedVoiceDiedStartsItAgain() {
        talk.open(1).await()
        voiceOn = false // its loop failed on its own; talk 1's failure was reported
        talk.close()
        talk.open(2).await()
        assertTrue(voiceOn)
        assertEquals(1, depth)
    }

    // ---- host-mic talk (PROTOCOL.md "Host-mic talk"): no call route at all ----

    @Test
    fun larkTalkTakesNoCallRoute() {
        talk.open(1, lark = true).await()
        assertEquals(listOf("lark start"), ops.toList())
        assertEquals(0, depth)
        talk.close().await()
        assertEquals(listOf("lark start", "lark stop", "earcon"), ops.toList())
        assertEquals(0, depth)
    }

    @Test
    fun earbudTalkCollapsedIntoALarkTalkGivesTheRouteBack() {
        enterGate = CountDownLatch(1)
        talk.open(1)
        while ("enter" !in ops) Thread.sleep(1)
        talk.close()
        val last = talk.open(2, lark = true)
        enterGate!!.countDown()
        last.await()
        assertEquals(listOf("enter", "start", "stop", "exit", "lark start"), ops.toList())
        assertEquals(0, depth)
        assertTrue(larkOn)
        assertFalse(voiceOn)
        talk.close().await()
        assertEquals("earcon", ops.last())
        assertEquals(1, ops.count { it == "earcon" })
    }

    @Test
    fun larkTalkReopenedWhileStoppingKeepsLarkAndAFailureReportsTheNewSession() {
        talk.open(1, lark = true).await()
        talk.close()
        talk.open(2, lark = true).await()
        assertTrue(larkOn)
        assertEquals(0, ops.count { it == "enter" })
        voiceFail!!("lark", IllegalStateException("unplugged"))
        assertEquals(listOf(2 to "lark"), failures.toList())
    }

    @Test
    fun larkThenEarbudsReentersTheCallRoute() {
        talk.open(1, lark = true).await()
        talk.close().await()
        talk.open(2).await()
        assertEquals(listOf("lark start", "lark stop", "earcon", "enter", "start"), ops.toList())
        assertEquals(1, depth)
        talk.close().await()
        assertEquals(0, depth)
    }
}
