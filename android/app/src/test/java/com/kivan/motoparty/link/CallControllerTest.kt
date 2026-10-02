package com.kivan.motoparty.link

import com.kivan.motoparty.link.CallController.Caller
import com.kivan.motoparty.link.CallController.State
import com.kivan.motoparty.link.CallPhrase.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallControllerTest {
    private var now = 1_000L
    private val events = mutableListOf<String>()
    private val call = CallController(
        clock = { now },
        muteLocal = { events += "mute $it" },
        closeTalk = { events += "closeTalk" },
        announceLocal = { events += "announce ${CallPhrase.announcement(it, "en-US").first}" },
        startListen = { events += "listen" },
        stopListen = { events += "stopListen" },
        accept = { events += "accept" },
        reject = { events += "reject" },
        hangUp = { events += "hangUp" },
    )

    private fun advance(ms: Long) {
        // The host ticks every 250 ms; the controller must not care how often.
        var left = ms
        while (left > 0) {
            val step = minOf(250L, left)
            now += step
            left -= step
            call.tick()
        }
    }

    @Test
    fun ringingMutesClosesTalkAndListensBeforeAnyName() {
        call.onRinging()
        assertEquals(listOf("mute true", "closeTalk", "listen"), events)
        assertEquals(State.RINGING, call.state)
        assertTrue(call.muted)
        assertTrue(call.listening)
    }

    @Test
    fun theNameArrivingAnnouncesAtOnce() {
        call.onRinging()
        events.clear()
        advance(400)
        assertTrue("nothing before the name or the wait", events.isEmpty())
        call.onCaller(Caller("Dana", "+972501234567"))
        assertEquals(listOf("announce Call from Dana"), events)
    }

    @Test
    fun noNameAfterTheWaitIsAnIncomingCall() {
        call.onRinging()
        events.clear()
        advance(CallController.NAME_WAIT_MS - 250)
        assertTrue(events.isEmpty())
        advance(250)
        assertEquals(listOf("announce Incoming call"), events)
        // A name that comes later updates the card but is not a second announcement.
        call.onCaller(Caller("Dana", null))
        assertEquals(1, events.size)
        assertEquals("Dana", call.caller?.name)
    }

    @Test
    fun repeatsOnceAfterEightSecondsAndNeverAgain() {
        call.onRinging()
        call.onCaller(Caller("Dana", null))
        events.clear()
        advance(CallController.REPEAT_MS - 250)
        assertTrue(events.isEmpty())
        advance(250)
        assertEquals(listOf("announce Call from Dana"), events)
        advance(60_000)
        assertEquals("only twice in all", 1, events.size)
    }

    @Test
    fun noRepeatOnceAnswered() {
        call.onRinging()
        call.onCaller(Caller("Dana", null))
        call.onOffhook()
        events.clear()
        advance(20_000)
        assertTrue(events.isEmpty())
    }

    @Test
    fun answeredStopsListeningAndStaysMuted() {
        call.onRinging()
        events.clear()
        call.onOffhook()
        assertEquals(listOf("stopListen"), events)
        assertEquals(State.OFFHOOK, call.state)
        assertTrue(call.muted)
        assertFalse(call.listening)
        assertFalse(call.outgoing)
    }

    @Test
    fun hangUpUnmutesAndForgetsTheCaller() {
        call.onRinging()
        call.onCaller(Caller("Dana", null))
        call.onOffhook()
        events.clear()
        call.onIdle()
        assertEquals(listOf("mute false"), events)
        assertEquals(State.IDLE, call.state)
        assertFalse(call.muted)
        assertNull(call.caller)
    }

    @Test
    fun missedOrDeclinedCallUnmutesAndStopsListening() {
        call.onRinging()
        events.clear()
        call.onIdle()
        assertEquals(listOf("stopListen", "mute false"), events)
    }

    @Test
    fun declineByVoiceRejectsOnceThenUnmutesOnIdle() {
        call.onRinging()
        events.clear()
        assertEquals(Decision.REJECT, call.onPhrase("decline"))
        assertEquals(listOf("stopListen", "reject"), events)
        assertNull("only the first decision counts", call.onPhrase("answer"))
        call.decline()
        assertEquals(2, events.size)
        call.onIdle()
        assertEquals(listOf("stopListen", "reject", "mute false"), events)
    }

    @Test
    fun answerByVoiceAccepts() {
        call.onRinging()
        events.clear()
        assertNull("conversation is ignored", call.onPhrase("who is it"))
        assertTrue(events.isEmpty())
        assertEquals(Decision.ACCEPT, call.onPhrase("Answer."))
        assertEquals(listOf("stopListen", "accept"), events)
        call.onOffhook()
        assertEquals("no second stopListen", 2, events.size)
    }

    @Test
    fun buttonsWorkWhileRingingOnly() {
        call.answer()
        call.decline()
        call.end()
        assertTrue("nothing when idle", events.isEmpty())
        call.onRinging()
        events.clear()
        call.answer()
        assertEquals(listOf("stopListen", "accept"), events)
        call.decline()
        assertEquals("already decided", 2, events.size)
    }

    @Test
    fun endWhileRingingDeclinesAndOnACallHangsUp() {
        call.onRinging()
        events.clear()
        call.end()
        assertEquals(listOf("stopListen", "reject"), events)
        call.onIdle()
        call.onRinging()
        call.onOffhook()
        events.clear()
        call.end()
        assertEquals(listOf("hangUp"), events)
    }

    @Test
    fun phrasesAfterTheRingAreIgnored() {
        assertNull(call.onPhrase("answer"))
        call.onRinging()
        call.onOffhook()
        assertNull(call.onPhrase("hang up"))
        assertTrue(events.none { it == "reject" || it == "accept" })
    }

    @Test
    fun offhookWithoutRingIsAnOutgoingCall() {
        call.onOffhook()
        assertEquals(listOf("mute true", "closeTalk"), events)
        assertTrue(call.outgoing)
        advance(20_000)
        assertEquals("no announcement, no listening", 2, events.size)
        call.onIdle()
        assertEquals(listOf("mute true", "closeTalk", "mute false"), events)
        assertFalse(call.outgoing)
    }

    @Test
    fun repeatedStatesAreIgnored() {
        call.onIdle()
        assertTrue(events.isEmpty())
        call.onRinging()
        call.onRinging()
        assertEquals(listOf("mute true", "closeTalk", "listen"), events)
        call.onOffhook()
        call.onOffhook()
        assertEquals(4, events.size)
        call.onIdle()
        call.onIdle()
        assertEquals(listOf("mute true", "closeTalk", "listen", "stopListen", "mute false"), events)
    }

    /** A second (waiting) call during a call rings as RINGING on some phones: nothing restarts. */
    @Test
    fun ringDuringACallChangesNothing() {
        call.onOffhook()
        events.clear()
        call.onRinging()
        assertTrue(events.isEmpty())
        assertEquals(State.OFFHOOK, call.state)
    }

    @Test
    fun aCallerBeforeTheRingIsDropped() {
        call.onCaller(Caller("Dana", null))
        assertNull(call.caller)
        call.onRinging()
        assertNull(call.caller)
    }

    @Test
    fun aNewCallAfterOneEndedStartsClean() {
        call.onRinging()
        call.onPhrase("decline")
        call.onIdle()
        events.clear()
        call.onRinging()
        assertEquals(Decision.ACCEPT, call.onPhrase("pick up"))
        assertTrue("accept" in events)
    }

    @Test
    fun onChangeFollowsEveryVisibleChange() {
        var changes = 0
        val c = CallController(
            clock = { now }, muteLocal = {}, closeTalk = {}, announceLocal = {}, startListen = {}, stopListen = {},
            accept = {}, reject = {}, hangUp = {}, onChange = { changes++ },
        )
        c.onRinging()
        c.onCaller(Caller("Dana", null))
        c.onCaller(Caller("Dana", null))
        c.onOffhook()
        c.onIdle()
        assertEquals(4, changes)
    }

    // ---- the phrase matcher ----

    @Test
    fun acceptWords() {
        for (p in listOf("answer", "Answer it", "pick up", "accept", "yes", "Yes!", "ok yes answer", "take it")) {
            assertEquals(p, Decision.ACCEPT, CallPhrase.match(p))
        }
    }

    @Test
    fun rejectWords() {
        for (p in listOf("decline", "reject", "ignore", "no", "No.", "hang up", "ignore it", "no thanks")) {
            assertEquals(p, Decision.REJECT, CallPhrase.match(p))
        }
    }

    @Test
    fun aNegatedAnswerIsADecline() {
        assertEquals(Decision.REJECT, CallPhrase.match("don't answer"))
        assertEquals(Decision.REJECT, CallPhrase.match("don’t pick up"))
        assertEquals(Decision.REJECT, CallPhrase.match("do not answer"))
    }

    @Test
    fun theFirstWordDecides() {
        assertEquals(Decision.REJECT, CallPhrase.match("no answer"))
        assertEquals(Decision.ACCEPT, CallPhrase.match("yes no"))
    }

    @Test
    fun wholeWordsOnlyAndShortPhrasesOnly() {
        assertNull(CallPhrase.match("unknown"))
        assertNull(CallPhrase.match("nobody"))
        assertNull(CallPhrase.match("yesterday"))
        assertNull(CallPhrase.match("answering"))
        assertNull(CallPhrase.match(""))
        assertNull(CallPhrase.match("who is calling"))
        assertNull("conversation that contains a word", CallPhrase.match("I told him yes we are riding today"))
    }

    @Test
    fun hebrewWords() {
        assertEquals(Decision.ACCEPT, CallPhrase.match("תענה"))
        assertEquals(Decision.ACCEPT, CallPhrase.match("כן"))
        assertEquals(Decision.REJECT, CallPhrase.match("תדחה"))
        assertEquals(Decision.REJECT, CallPhrase.match("לא"))
    }

    // ---- what is spoken ----

    @Test
    fun announcementInEnglish() {
        assertEquals("Call from Dana" to "en-US", CallPhrase.announcement(Caller("Dana", "+1"), "en-US"))
        assertEquals("Call from unknown number" to "en-GB", CallPhrase.announcement(Caller(null, "+1"), "en-GB"))
        assertEquals("Call from unknown number" to "en-US", CallPhrase.announcement(Caller(null, ""), "en-US"))
        assertEquals("Incoming call" to "en-US", CallPhrase.announcement(null, "en-US"))
    }

    /** A Hebrew contact name is read by the Hebrew voice, whatever the recognition language. */
    @Test
    fun announcementInHebrew() {
        assertEquals("שיחה מאמא" to "he-IL", CallPhrase.announcement(Caller("אמא", null), "en-US"))
        assertEquals("שיחה נכנסת" to "he-IL", CallPhrase.announcement(null, "he-IL"))
        assertEquals("שיחה ממספר לא ידוע" to "he-IL", CallPhrase.announcement(Caller(null, "050"), "iw-IL"))
        assertEquals("Call from Dana" to "en-US", CallPhrase.announcement(Caller("Dana", null), "fr-FR"))
    }

    @Test
    fun cardLabel() {
        assertEquals("Dana", CallPhrase.label(Caller("Dana", "+1")))
        assertEquals("+972501234567", CallPhrase.label(Caller(null, "+972501234567")))
        assertEquals("Unknown number", CallPhrase.label(Caller(null, "")))
        assertEquals("Incoming call", CallPhrase.label(null))
    }
}
