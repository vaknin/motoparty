package com.kivan.motoparty.ui

import com.kivan.motoparty.LinkStatus
import com.kivan.motoparty.PlaybackAnchor
import com.kivan.motoparty.UiAction
import com.kivan.motoparty.core.EnqueueMode
import com.kivan.motoparty.music.CollectionItem
import com.kivan.motoparty.music.MusicPhase
import com.kivan.motoparty.music.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private fun track(id: String, title: String = id) = Track(id, title, "Artist", durationMs = 200_000)

    private val running = LinkStatus(running = true, nsdName = "Pixel 8", nowPlaying = track("now"))

    // ---- the playback position, from the anchor ----

    @Test
    fun anchorMovesOnlyWhilePlaying() {
        assertEquals(12_500, PlaybackAnchor(10_000, atMs = 1_000, playing = true).at(3_500))
        assertEquals(10_000, PlaybackAnchor(10_000, atMs = 1_000, playing = false).at(99_000))
    }

    @Test
    fun anchorStaysInsideTheTrack() {
        // A start scheduled ahead of now is not a negative position.
        assertEquals(0, PlaybackAnchor(0, atMs = 5_000, playing = true).at(4_000))
        // Past the end (the next track has not been anchored yet) stops at the length.
        assertEquals(200_000, PlaybackAnchor(199_000, atMs = 0, playing = true).at(5_000, durationMs = 200_000))
        // An unknown length does not clamp.
        assertEquals(204_000, PlaybackAnchor(199_000, atMs = 0, playing = true).at(5_000))
    }

    @Test
    fun anchorFraction() {
        assertEquals(0.5f, PlaybackAnchor(100_000, 0, playing = false).fraction(0, 200_000), 1e-6f)
        assertEquals(1f, PlaybackAnchor(300_000, 0, playing = false).fraction(0, 200_000), 1e-6f)
        assertEquals(0f, PlaybackAnchor(100_000, 0, playing = false).fraction(0, 0), 1e-6f)
    }

    /** UA4: a second later with nothing new is an equal status, so the StateFlow emits nothing. */
    @Test
    fun idleSecondIsAnEqualStatus() {
        val a = running.copy(playing = true, anchor = PlaybackAnchor(10_000, 1_000, true), queue = listOf(track("a")))
        val b = running.copy(playing = true, anchor = PlaybackAnchor(10_000, 1_000, true), queue = listOf(track("a")))
        assertEquals(a, b)
    }

    // ---- talk ----

    @Test
    fun talkPhases() {
        assertEquals(TalkPhase.IDLE, TalkPhase.of(running))
        assertEquals(TalkPhase.OPENING, TalkPhase.of(running.copy(talkOpen = true)))
        assertEquals(TalkPhase.LIVE, TalkPhase.of(running.copy(talkOpen = true, talkLive = true)))
        assertEquals(TalkPhase.CLOSING, TalkPhase.of(running.copy(talkClosing = true)))
        // A re-open during the teardown of the talk before is opening, not closing.
        assertEquals(TalkPhase.OPENING, TalkPhase.of(running.copy(talkOpen = true, talkClosing = true)))
        // A stale "live" without a talk is nothing.
        assertEquals(TalkPhase.IDLE, TalkPhase.of(running.copy(talkLive = true)))
    }

    @Test
    fun talkWordsMatchTheReference() {
        assertEquals("TALK", TalkPhase.IDLE.label)
        assertEquals("END TALK", TalkPhase.LIVE.label)
    }

    // ---- wording ----

    @Test
    fun linkLines() {
        assertEquals(LinkLine.Kind.OFF, LinkLine.of(LinkStatus()).kind)
        assertEquals("Starting…", LinkLine.of(LinkStatus(running = true)).title)
        val waiting = LinkLine.of(LinkStatus(running = true, nsdName = "Pixel 8"))
        assertEquals("Waiting for the passenger", waiting.title)
        assertEquals("Ready — open Motoparty on the iPhone", waiting.detail)
        val linked = LinkLine.of(LinkStatus(running = true, nsdName = "Pixel 8", clientName = "Dana's iPhone"))
        assertEquals("Passenger connected", linked.title)
        assertEquals("Dana's iPhone", linked.detail)
    }

    @Test
    fun phaseWords() {
        assertEquals("Downloading song…", phaseText(MusicPhase.LOADING, null))
        assertEquals("Waiting for the passenger…", phaseText(MusicPhase.WAITING_CLIENT, null))
        assertEquals("Waiting for iPhone…", phaseText(MusicPhase.WAITING_CLIENT, "iPhone"))
    }

    @Test
    fun badge() {
        assertEquals("7", badgeText(7))
        assertEquals("99", badgeText(99))
        assertEquals("99+", badgeText(100))
    }

    // ---- confirmations ----

    @Test
    fun enqueueConfirmations() {
        val one = listOf(track("a", "Time"))
        val many = List(12) { track("t$it") }
        assertEquals("Playing next: Time", confirmation(UiAction.Enqueue(EnqueueMode.NEXT, one), running)?.text)
        assertEquals("Added to queue: Time", confirmation(UiAction.Enqueue(EnqueueMode.END, one), running)?.text)
        assertEquals("Added 12 songs", confirmation(UiAction.Enqueue(EnqueueMode.END, many), running)?.text)
        assertEquals("Playing next: 12 songs", confirmation(UiAction.Enqueue(EnqueueMode.NEXT, many), running)?.text)
        // A play shows itself on the Ride tab.
        assertNull(confirmation(UiAction.Enqueue(EnqueueMode.NOW, one), running))
        // With nothing loaded "add" plays at once (MusicController.enqueue): no "added" either.
        assertNull(confirmation(UiAction.Enqueue(EnqueueMode.END, one), running.copy(nowPlaying = null)))
        assertNull(confirmation(UiAction.Enqueue(EnqueueMode.END, emptyList()), running))
    }

    @Test
    fun nothingIsConfirmedWhileTheHostIsOff() {
        assertNull(confirmation(UiAction.Enqueue(EnqueueMode.END, listOf(track("a"))), LinkStatus()))
    }

    @Test
    fun removeOffersUndoAtTheSamePlace() {
        val s = running.copy(queue = listOf(track("a"), track("b", "Time"), track("c")))
        val c = confirmation(UiAction.Remove(1, "b"), s)!!
        assertEquals("Removed: Time", c.text)
        assertEquals(UiAction.Restore(1, track("b", "Time")), c.undo)
        // The queue moved under the tap: the host ignores the remove, so nothing is claimed.
        assertNull(confirmation(UiAction.Remove(1, "c"), s))
        assertNull(confirmation(UiAction.Remove(9, "b"), s))
    }

    @Test
    fun clearOffersTheQueueBack() {
        val q = listOf(track("a"), track("b"))
        val c = confirmation(UiAction.ClearQueue, running.copy(queue = q))!!
        assertEquals("Queue cleared", c.text)
        assertEquals(UiAction.Enqueue(EnqueueMode.END, q), c.undo)
        assertNull(confirmation(UiAction.ClearQueue, running))
    }

    @Test
    fun downloadAndTheRest() {
        val album = CollectionItem("a1", "Album", "Artist", 3, null)
        assertEquals("Downloading 3 songs", confirmation(UiAction.Download(album, List(3) { track("t$it") }), running)?.text)
        assertNull(confirmation(UiAction.Jump(0, "a"), running))
        assertNull(confirmation(UiAction.Control("pause"), running))
    }

    // ---- queue keys ----

    @Test
    fun queueKeysSurviveARemoval() {
        val q = listOf(track("a"), track("b"), track("c"))
        val before = queueKeys(q)
        assertEquals(before.drop(1), queueKeys(q.drop(1)))
        assertEquals(3, before.toSet().size)
    }

    @Test
    fun queueKeysAreUniqueForASongQueuedTwice() {
        val keys = queueKeys(listOf(track("a"), track("b"), track("a"), track("a")))
        assertEquals(listOf("a#1", "b#1", "a#2", "a#3"), keys)
    }

    // ---- permissions ----

    @Test
    fun permanentDenialOpensSettings() {
        // Answered at once, still denied, no rationale: the system did not ask.
        assertTrue(deniedForGood(granted = false, showRationale = false, answeredAfterMs = 40))
        // The rider read a dialog and said no: that is an answer, not a dead button.
        assertFalse(deniedForGood(granted = false, showRationale = false, answeredAfterMs = 2_500))
        // Denied once, may be asked again.
        assertFalse(deniedForGood(granted = false, showRationale = true, answeredAfterMs = 40))
        assertFalse(deniedForGood(granted = true, showRationale = false, answeredAfterMs = 40))
    }

    // ---- languages ----

    @Test
    fun languages() {
        assertEquals("English (United States)", languageName("en-US"))
        assertEquals("Hebrew (Israel)", languageName("he-IL"))
        assertTrue("en-US" in speechLanguages("en-US"))
        assertEquals(1, speechLanguages("en-US").count { it == "en-US" })
        // A value set before the list existed is kept, and can be picked again.
        assertEquals("sv-SE", speechLanguages("sv-SE").last())
    }

    // ---- the microphone meter ----

    @Test
    fun micLevelIsZeroForSilenceAndFullForLoud() {
        assertEquals(0f, micLevel(0), 0f)
        assertEquals(0f, micLevel(-5), 0f)
        // -48 dB is a peak of about 130: the hiss of an open mic shows nothing.
        assertEquals(0f, micLevel(100), 0f)
        assertEquals(1f, micLevel(32768), 0f)
        // -6 dB and above is a full bar.
        assertEquals(1f, micLevel(16500), 0f)
    }

    @Test
    fun micLevelIsEvenInDecibels() {
        // Each doubling of the sample (6 dB) is the same step: 6 / 42 of the bar.
        val step = micLevel(2000) - micLevel(1000)
        assertEquals(6.02f / 42f, step, 0.002f)
        assertEquals(step, micLevel(8000) - micLevel(4000), 0.002f)
        // Ordinary speech (about -20 dB) is well up the bar, not a sliver as it would be linearly.
        assertEquals(0.66f, micLevel(3277), 0.02f)
    }

    @Test
    fun meterJumpsUpAndFallsSlowly() {
        assertEquals(0.8f, meterStep(shown = 0.1f, level = 0.8f, elapsedMs = 60), 0f)
        // Silence after a syllable: down by 60/450 of the bar per poll, not to zero at once.
        assertEquals(0.8f - 60f / 450f, meterStep(shown = 0.8f, level = 0f, elapsedMs = 60), 0.0001f)
        // The fall never goes below what the mic says, nor below empty.
        assertEquals(0.75f, meterStep(shown = 0.8f, level = 0.75f, elapsedMs = 60), 0f)
        assertEquals(0f, meterStep(shown = 0.05f, level = 0f, elapsedMs = 60), 0f)
        assertEquals(0f, meterStep(shown = 1f, level = 0f, elapsedMs = 5_000), 0f)
        // A clock that did not move (or went back) changes nothing.
        assertEquals(0.5f, meterStep(shown = 0.5f, level = 0f, elapsedMs = 0), 0f)
        assertEquals(0.5f, meterStep(shown = 0.5f, level = 0f, elapsedMs = -20), 0f)
    }

    @Test
    fun smartCommandHintsNeedTheInterpreterAndMatchTheIphone() {
        val words = SMART_COMMANDS.flatten()
        // Only Gemini understands them: the grammar does not.
        for (w in words) assertEquals(w, com.kivan.motoparty.core.Command.Unknown, com.kivan.motoparty.core.CommandParser.parse(w))
        assertEquals(words.size, words.toSet().size)
        assertTrue(words.size in 6..8)
        // The iPhone's VoiceCommandChip.smart (ParityTests), word for word.
        assertEquals(
            listOf(
                "repeat this song", "go back 30 seconds", "start over", "what's next",
                "remove the next song", "move the last song to next", "clear the queue", "undo",
            ),
            words,
        )
    }
}
