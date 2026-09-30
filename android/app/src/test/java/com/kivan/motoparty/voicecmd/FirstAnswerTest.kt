package com.kivan.motoparty.voicecmd

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstAnswerTest {
    private fun after(ms: Long, answer: Interpreter.Answer) = Interpreter { _, _, _, _, _, _ -> delay(ms); answer }
    private suspend fun Interpreter.ask() = interpret("play something by movie", "en-US", null, null, emptyList(), null)

    @Test
    fun theFirstTextWins() = runTest {
        val answer = FirstAnswer(listOf(
            "slow" to after(5_000, Interpreter.Text("slow")),
            "fast" to after(1_000, Interpreter.Text("fast")),
        )).ask()
        assertEquals(Interpreter.Text("fast"), answer)
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test
    fun aFailureWaitsForTheOther() = runTest {
        val answer = FirstAnswer(listOf(
            "a" to after(100, Interpreter.Failed("HTTP 503")),
            "b" to after(2_000, Interpreter.Text("b")),
        )).ask()
        assertEquals(Interpreter.Text("b"), answer)
    }

    @Test
    fun allFailedNamesEachReason() = runTest {
        val answer = FirstAnswer(listOf(
            "3.5" to after(6_000, Interpreter.Failed("timeout")),
            "3.1" to after(10, Interpreter.Failed("rate limited")),
        )).ask()
        assertEquals(Interpreter.Failed("3.5: timeout; 3.1: rate limited"), answer)
    }

    @Test
    fun theLoserIsCancelled() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val never = Interpreter { _, _, _, _, _, _ ->
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        FirstAnswer(listOf("never" to never, "fast" to after(10, Interpreter.Text("x")))).ask()
        assertTrue(cancelled.isCompleted)
    }

    @Test
    fun aReplyGoesOnlyToTheOthers() = runTest {
        val answer = FirstAnswer(
            listOf("3.5" to after(3_000, Interpreter.Text("3.5")), "3.1" to after(10, Interpreter.Text("3.1"))),
            noReplies = setOf("3.1"),
        ).interpret("any", "en-US", null, null, emptyList(), Interpreter.Asked("play an album by moby", "Which Moby album?"))
        assertEquals(Interpreter.Text("3.5"), answer)
        assertEquals(Interpreter.Text("3.1"), FirstAnswer(
            listOf("3.5" to after(3_000, Interpreter.Text("3.5")), "3.1" to after(10, Interpreter.Text("3.1"))),
            noReplies = setOf("3.1"),
        ).ask())
    }
}
