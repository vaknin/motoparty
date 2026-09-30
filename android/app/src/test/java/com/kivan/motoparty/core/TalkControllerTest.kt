package com.kivan.motoparty.core

import com.kivan.motoparty.link.TalkController
import com.kivan.motoparty.link.TalkController.Action
import com.kivan.motoparty.link.onClientJoined
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkControllerTest {
    private val talk = TalkController()

    @Test
    fun localTriggerToggles() {
        assertEquals(Action.Open("host"), talk.onLocalTrigger())
        assertTrue(talk.isOpen)
        assertEquals(Action.Close("host", "trigger"), talk.onLocalTrigger())
        assertFalse(talk.isOpen)
    }

    /** PROTOCOL.md "Commands": `play`/`resume`/`end` close the talk as the side that spoke. */
    @Test
    fun spokenCommandClosesAsTheSpeaker() {
        assertNull("nothing to close", talk.onCommandClose(Role.CLIENT))
        talk.onLocalTrigger()
        assertEquals(Action.Close("client", "trigger"), talk.onCommandClose(Role.CLIENT))
        assertFalse(talk.isOpen)
        talk.onClientOpenRequest()
        assertEquals(Action.Close("host", "trigger"), talk.onCommandClose(Role.HOST))
    }

    @Test
    fun clientRequestsAreDecidedByHost() {
        assertNull(talk.onClientCloseRequest())
        assertEquals(Action.Open("client"), talk.onClientOpenRequest())
        assertNull("already open", talk.onClientOpenRequest())
        assertEquals(Action.Close("client", "trigger"), talk.onClientCloseRequest())
    }

    /**
     * PROTOCOL.md "Talk flow" step 1. The client's mic failed right after our `talk.open`; that
     * is a close request like any other, and the reason is carried through so the rider learns
     * why. Talk is never negotiable, so there is no path here that keeps it open.
     */
    @Test
    fun clientUnavailableClosesTalkAndKeepsTheReason() {
        assertNull("nothing to close yet", talk.onClientCloseRequest(CloseReason.UNAVAILABLE))
        assertEquals(Action.Open("host"), talk.onLocalTrigger())
        assertEquals(
            Action.Close("client", "unavailable"),
            talk.onClientCloseRequest(CloseReason.UNAVAILABLE),
        )
        assertFalse(talk.isOpen)
    }

    @Test
    fun anyOtherClientCloseReasonIsATrigger() {
        talk.onLocalTrigger()
        assertEquals(Action.Close("client", "trigger"), talk.onClientCloseRequest(CloseReason.TRIGGER))
        talk.onLocalTrigger()
        assertEquals(Action.Close("client", "trigger"), talk.onClientCloseRequest(CloseReason.LINK))
    }

    /** The host needs this to decide whether *it* was the phone that asked, and so must earcon. */
    @Test
    fun openedByRecordsWhoTriggered() {
        assertNull(talk.openedBy)
        talk.onLocalTrigger()
        assertEquals(Role.HOST, talk.openedBy)
        talk.onLocalTrigger()
        talk.onClientOpenRequest()
        assertEquals(Role.CLIENT, talk.openedBy)
    }

    /**
     * The host's own mic can only fail asynchronously (the route change and the capture thread
     * both run off the host thread), i.e. after `talk.open` already went out. It is then an
     * ordinary close carrying the reason, the mirror of the client's "unavailable".
     */
    @Test
    fun ownMicFailureClosesTalkAsUnavailable() {
        assertNull("nothing open, nothing to close", talk.onMicFailure())
        talk.onClientOpenRequest()
        assertEquals(Action.Close("host", "unavailable"), talk.onMicFailure())
        assertFalse(talk.isOpen)
        assertNull("only once", talk.onMicFailure())
    }

    @Test
    fun linkLossClosesOnlyWhenOpen() {
        assertNull(talk.onLinkLost())
        talk.onClientOpenRequest()
        assertEquals(Action.Close("host", "link"), talk.onLinkLost())
    }

    /** PROTOCOL.md "Liveness": the same client back on a new socket keeps the talk; another name ends it. */
    @Test
    fun sameNameReconnectKeepsTheTalk() {
        assertNull("no talk", talk.onClientReplaced("iPhone", "Other"))
        talk.onClientOpenRequest()
        assertNull(talk.onClientReplaced("iPhone", "iPhone"))
        assertTrue(talk.isOpen)
        assertEquals("client", talk.openedBy)
        assertEquals(Action.Close("host", "link"), talk.onClientReplaced("iPhone", "Other"))
        assertFalse(talk.isOpen)
    }

    /** R3: a client joining a solo talk ends its commands; the rider is now in a conversation. */
    @Test
    fun clientJoiningASoloTalkEndsItsCommands() {
        val gate = FirstPhraseGate()
        gate.open(FirstPhraseGate.Role.SOLO)
        gate.live(1_000)
        assertEquals("next", gate.onPhrase("Next", 2_000))
        assertFalse(gate.isSpent(2_000))
        assertTrue(gate.onClientJoined())
        assertTrue("the recognizer may stop", gate.isSpent(2_001))
        assertNull("a command phrase is conversation now", gate.onPhrase("next", 2_500))
        assertNull("and an unparsed one gets no reply", gate.onPhrase("how is the road", 3_000))
        assertFalse("once", gate.onClientJoined())
        // The live earcon still to come (the client joined before it) opens no window either.
        gate.live(3_500)
        assertNull(gate.onPhrase("pause", 3_600))
    }

    /** A talk with a client already in it keeps its gate: a reconnect must not take the opener's window. */
    @Test
    fun clientJoiningLeavesAnOpenersWindowAlone() {
        val gate = FirstPhraseGate()
        gate.open(FirstPhraseGate.Role.OPENER)
        gate.live(1_000)
        assertFalse(gate.onClientJoined())
        assertEquals(FirstPhraseGate.Role.OPENER, gate.role)
        assertEquals("pause", gate.onPhrase("pause", 2_000))
    }

    /** H7: something holding the mic (the USB probe) blocks a press from opening, never from ending. */
    @Test
    fun heldMicBlocksOnlyTheOpen() {
        assertTrue(talk.localTriggerAllowed(micHeld = false))
        assertFalse(talk.localTriggerAllowed(micHeld = true))
        talk.onClientOpenRequest()
        assertTrue("a press must end the passenger's talk", talk.localTriggerAllowed(micHeld = true))
        assertEquals(Action.Close("host", "trigger"), talk.onLocalTrigger())
    }
}
