package com.kivan.motoparty.core

import com.kivan.motoparty.core.DibsPhrase.Said
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DIBS.md "Saying it": which phrases are for dibs, and what of them is sent. */
class DibsPhraseTest {
    @Test
    fun theWordsAfterDibsAreTheMessageAsRecognised() {
        assertEquals(
            Said.Message("In motoparty, make the lyrics toggle remember its setting"),
            DibsPhrase.parse("dibs, in motoparty, make the lyrics toggle remember its setting"),
        )
        assertEquals(Said.Message("What's new?"), DibsPhrase.parse("Hey dibs what's new?"))
        assertEquals(Said.Message("Pause everything"), DibsPhrase.parse("okay, dibs: pause everything"))
        assertEquals(Said.Message("Idea: a lean-angle meter"), DibsPhrase.parse("Dibs idea: a lean-angle meter"))
        // Other scripts keep every word after "dibs".
        assertEquals(Said.Message("שלום עולם"), DibsPhrase.parse("dibs, שלום עולם"))
        assertEquals(Said.Message("Привет мир"), DibsPhrase.parse("dibs привет мир"))
    }

    @Test
    fun onlyAPhraseThatStartsWithDibs() {
        assertNull(DibsPhrase.parse("play dibs"))
        assertNull(DibsPhrase.parse("tell dibs to stop"))
        assertNull(DibsPhrase.parse("dibsy is a song"))
        assertNull(DibsPhrase.parse("next"))
        // "dibs" alone says nothing to send.
        assertNull(DibsPhrase.parse("dibs"))
        assertNull(DibsPhrase.parse("hey dibs!"))
    }

    @Test
    fun quietAndTalkStayOnThisPhone() {
        assertEquals(Said.Quiet, DibsPhrase.parse("dibs, quiet"))
        assertEquals(Said.Quiet, DibsPhrase.parse("Dibs be quiet."))
        assertEquals(Said.Talk, DibsPhrase.parse("dibs talk"))
        assertEquals(Said.Talk, DibsPhrase.parse("dibs, you can talk"))
        // Anything longer is a message.
        assertEquals(Said.Message("Talk to the Honda task"), DibsPhrase.parse("dibs talk to the Honda task"))
    }

    @Test
    fun cancelIsTheWholePhraseNotAWordInIt() {
        assertTrue(DibsPhrase.isCancel("Cancel."))
        assertTrue(DibsPhrase.isCancel("no"))
        assertTrue(DibsPhrase.isCancel("never mind"))
        assertTrue(DibsPhrase.isCancel("don't send"))
        // The read-back heard back by the microphone is not a cancel, whatever it says.
        assertFalse(DibsPhrase.isCancel("to dibs cancel the build"))
        assertFalse(DibsPhrase.isCancel("no wait send it"))
    }

    @Test
    fun theGateLetsDibsThroughWithoutTheInterpreter() {
        val gate = FirstPhraseGate()
        gate.open(FirstPhraseGate.Role.OPENER, interpret = false)
        gate.live(0)
        assertEquals("dibs what's running", gate.onPhrase("dibs, what's running?", 1_000))
        val other = FirstPhraseGate()
        other.open(FirstPhraseGate.Role.OTHER)
        assertNull(other.onPhrase("dibs, what's running?", 1_000))
    }
}
