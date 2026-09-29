package com.kivan.motoparty.core

import com.kivan.motoparty.core.PhraseGate.Heard
import org.junit.Assert.assertEquals
import org.junit.Test

/** PROTOCOL.md "Commands", *Wake word*: arming, its 5 s window, and the solo talk. */
class PhraseGateTest {
    private val gate = PhraseGate()

    @Test
    fun wakeWordMakesACommandEverythingElseIsConversation() {
        assertEquals(Heard.Command("next"), gate.onPhrase("Moto party next", solo = false, nowMs = 0))
        assertEquals(Heard.Conversation, gate.onPhrase("next", solo = false, nowMs = 100))
        assertEquals(Heard.Conversation, gate.onPhrase("look at that bike", solo = false, nowMs = 200))
    }

    @Test
    fun bareWakeWordArmsTheNextPhraseForFiveSeconds() {
        assertEquals(Heard.Armed, gate.onPhrase("Moto party", solo = false, nowMs = 1_000))
        assertEquals(Heard.Command("play album abbey road"), gate.onPhrase("Play album Abbey Road!", solo = false, nowMs = 6_000))
        // One phrase only: the next one is conversation again.
        assertEquals(Heard.Conversation, gate.onPhrase("pause", solo = false, nowMs = 6_500))
    }

    @Test
    fun armingExpires() {
        assertEquals(Heard.Armed, gate.onPhrase("motoparty.", solo = false, nowMs = 0))
        assertEquals(Heard.Conversation, gate.onPhrase("pause", solo = false, nowMs = 5_001))
    }

    @Test
    fun bareWakeWordWhileArmedReArms() {
        assertEquals(Heard.Armed, gate.onPhrase("Moto party", solo = false, nowMs = 0))
        assertEquals(Heard.Armed, gate.onPhrase("Moto party", solo = false, nowMs = 4_000))
        assertEquals(Heard.Command("next"), gate.onPhrase("next", solo = false, nowMs = 8_500))
    }

    @Test
    fun armedPhraseWithItsOwnWakeWordIsStripped() {
        gate.onPhrase("Moto party", solo = false, nowMs = 0)
        assertEquals(Heard.Command("pause"), gate.onPhrase("moto party pause", solo = false, nowMs = 1_000))
    }

    @Test
    fun soloTalkMakesEveryPhraseACommand() {
        assertEquals(Heard.Command("play pink floyd"), gate.onPhrase("Play Pink Floyd", solo = true, nowMs = 0))
        assertEquals(Heard.Command("next"), gate.onPhrase("Moto party next", solo = true, nowMs = 10))
        assertEquals(Heard.Armed, gate.onPhrase("Moto party", solo = true, nowMs = 20))
        assertEquals(Heard.Conversation, gate.onPhrase("...", solo = true, nowMs = 30))
    }

    @Test
    fun resetDisarms() {
        gate.onPhrase("Moto party", solo = false, nowMs = 0)
        gate.reset()
        assertEquals(Heard.Conversation, gate.onPhrase("next", solo = false, nowMs = 100))
    }
}
