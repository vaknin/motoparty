package com.kivan.motoparty.voicecmd

import com.kivan.motoparty.core.RepeatMode
import com.kivan.motoparty.music.VoiceWindow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstAnswerTest {
    /** A reply to our question: its window carries `asked`. */
    private val reply = VoiceWindow.build(
        "any", "en-US", null, 0, RepeatMode.OFF, emptyList(), emptyList(), null,
        VoiceWindow.Asked("play an album by moby", "Which Moby album?"),
    ).input
    private fun after(ms: Long, answer: Interpreter.Answer) = Interpreter { _ -> delay(ms); answer }
    private val window = VoiceWindow.build("play something by movie", "en-US", null, 0, RepeatMode.OFF, emptyList(), emptyList(), null).input
    private suspend fun Interpreter.ask() = interpret(window)

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
        val never = Interpreter { _ ->
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
        ).interpret(reply)
        assertEquals(Interpreter.Text("3.5"), answer)
        assertEquals(Interpreter.Text("3.1"), FirstAnswer(
            listOf("3.5" to after(3_000, Interpreter.Text("3.5")), "3.1" to after(10, Interpreter.Text("3.1"))),
            noReplies = setOf("3.1"),
        ).ask())
    }

    private fun withBackup(vararg all: Pair<String, Interpreter>, backup: Interpreter, noReplies: Set<String> = emptySet()) =
        FirstAnswer(all.toList(), noReplies, backup = "gemma" to backup, backupAfterMs = 4_000, limitMs = 6_000)

    @Test
    fun theBackupIsNotAskedWhenAFlashLiteAnswersInTime() = runTest {
        var asked = false
        val gemma = Interpreter { _ -> asked = true; Interpreter.Text("gemma") }
        assertEquals(Interpreter.Text("3.5"), withBackup("3.5" to after(3_000, Interpreter.Text("3.5")), backup = gemma).ask())
        assertEquals(false, asked)
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test
    fun theBackupStartsAsSoonAsAllRefuse() = runTest {
        val answer = withBackup(
            "3.5" to after(400, Interpreter.Failed("HTTP 429")),
            "3.1" to after(500, Interpreter.Failed("HTTP 503")),
            backup = after(1_200, Interpreter.Text("gemma")),
        ).ask()
        assertEquals(Interpreter.Text("gemma"), answer)
        assertEquals(1_700L, testScheduler.currentTime)
    }

    @Test
    fun theBackupJoinsAfterFourSecondsAndCanWin() = runTest {
        val answer = withBackup(
            "3.5" to after(5_900, Interpreter.Failed("timeout")),
            "3.1" to after(5_500, Interpreter.Text("3.1")),
            backup = after(1_200, Interpreter.Text("gemma")),
        ).ask()
        assertEquals(Interpreter.Text("gemma"), answer)
        assertEquals(5_200L, testScheduler.currentTime)
    }

    @Test
    fun nothingIsWaitedForPastTheLimit() = runTest {
        val answer = withBackup(
            "3.5" to after(5_900, Interpreter.Failed("timeout (sent 400)")),
            "3.1" to after(60_000, Interpreter.Text("late")),
            backup = after(30_000, Interpreter.Text("late")),
        ).ask()
        assertEquals(Interpreter.Failed("3.5: timeout (sent 400); 3.1: timeout; gemma: timeout"), answer)
        assertEquals(6_000L, testScheduler.currentTime)
    }

    @Test
    fun aReplyNeverGoesToTheBackupWhenItIsExcluded() = runTest {
        val answer = withBackup(
            "3.5" to after(100, Interpreter.Failed("HTTP 503")),
            backup = after(10, Interpreter.Text("gemma")),
            noReplies = setOf("gemma"),
        ).interpret(reply)
        assertEquals(Interpreter.Failed("3.5: HTTP 503"), answer)
    }
}
