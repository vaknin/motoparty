package com.kivan.motoparty.core

import com.kivan.motoparty.link.TalkController
import com.kivan.motoparty.link.TalkController.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkControllerTest {
    private var now = 0L
    private val talk = TalkController({ now })

    @Test
    fun localTriggerToggles() {
        assertEquals(Action.Open("host"), talk.onLocalTrigger())
        assertTrue(talk.isOpen)
        assertEquals(Action.Close("host", "trigger"), talk.onLocalTrigger())
        assertFalse(talk.isOpen)
    }

    @Test
    fun clientRequestsAreDecidedByHost() {
        assertNull(talk.onClientCloseRequest())
        assertEquals(Action.Open("client"), talk.onClientOpenRequest())
        assertNull("already open", talk.onClientOpenRequest())
        assertEquals(Action.Close("client", "trigger"), talk.onClientCloseRequest())
    }

    @Test
    fun closesAfterTenSecondsOfMutualSilence() {
        talk.onLocalTrigger()
        now = 9_000
        talk.noteActivity()
        now = 18_999
        assertNull(talk.tick())
        now = 19_000
        assertEquals(Action.Close("host", "silence"), talk.tick())
        assertNull(talk.tick())
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
        assertEquals(Action.Close("client", "trigger"), talk.onClientCloseRequest(CloseReason.SILENCE))
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

    @Test
    fun linkLossClosesOnlyWhenOpen() {
        assertNull(talk.onLinkLost())
        talk.onClientOpenRequest()
        assertEquals(Action.Close("host", "link"), talk.onLinkLost())
    }
}
