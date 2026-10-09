package com.kivan.motoparty.core

import com.kivan.motoparty.core.RideSpeech.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DIBS.md "What dibs says": when dibs's lines are spoken in the helmet, and how short. */
class RideSpeechTest {
    @Test
    fun withNoMusicALineIsSaidAtOnce() {
        val s = RideSpeech()
        assertEquals("dibs: Done, tests pass.", s.onLine(Line("1", "Done, tests pass."), 0, playing = false, busy = false))
    }

    @Test
    fun duringASongLinesWaitForTheNextOneAndComeTogether() {
        val s = RideSpeech()
        assertNull(s.onLine(Line("1", "Lyrics toggle done"), 0, playing = true, busy = false))
        assertNull(s.onLine(Line("2", "Honda firmware builds"), 1_000, playing = true, busy = false))
        assertNull(s.tick(60_000, playing = true, busy = false))
        assertTrue(s.holding)
        assertEquals("dibs: Lyrics toggle done. dibs: Honda firmware builds.", s.onSongStart(busy = false))
        assertFalse(s.holding)
    }

    @Test
    fun aLongSongDoesNotHoldThemForEver() {
        val s = RideSpeech(holdMaxMs = 300_000)
        s.onLine(Line("1", "Done."), 0, playing = true, busy = false)
        assertNull(s.tick(299_999, playing = true, busy = false))
        assertEquals("dibs: Done.", s.tick(300_000, playing = true, busy = false))
    }

    @Test
    fun aQuestionComesFirstAndAtOnceButNeverIntoATalk() {
        val s = RideSpeech()
        s.onLine(Line("1", "Done."), 0, playing = true, busy = false)
        // In a talk nothing is said, question or not.
        assertNull(s.onLine(Line("2", "Install the library or skip?", question = true), 0, playing = true, busy = true))
        assertNull(s.onSongStart(busy = true))
        assertEquals(
            "dibs asks: Install the library or skip? It waits in the app. dibs: Done.",
            s.tick(1_000, playing = true, busy = false),
        )
    }

    @Test
    fun aLineComesOnceAndBundlesStopAtThree() {
        val s = RideSpeech()
        for (i in 1..5) s.onLine(Line("$i", "Line $i"), 0, playing = true, busy = false)
        assertNull(s.onLine(Line("1", "Line 1"), 0, playing = true, busy = false))
        assertEquals("dibs: Line 1. dibs: Line 2. dibs: Line 3. And 2 more in the app.", s.onSongStart(busy = false))
    }

    @Test
    fun quietCountsWhatCameMeanwhile() {
        val s = RideSpeech()
        s.onLine(Line("1", "Held."), 0, playing = true, busy = false)
        assertEquals("Quiet. dibs stays in the app.", s.quiet())
        assertNull(s.onLine(Line("2", "While quiet."), 0, playing = false, busy = false))
        assertNull(s.onSongStart(busy = false))
        assertEquals("Talking again. 2 lines came meanwhile; they're in the app.", s.talk())
        assertEquals("dibs: Back.", s.onLine(Line("3", "Back."), 0, playing = false, busy = false))
    }

    @Test
    fun spokenLinesHaveNoCodeAndOneShortSentence() {
        assertEquals("Fixed the crash in.", RideSpeech.spoken("Fixed the crash in `src/talk.rs`. Then I ran the tests."))
        assertEquals("Read it here.", RideSpeech.spoken("Read it [here](https://x.y/z)."))
        assertEquals("The page is ready.", RideSpeech.spoken("**The page** is ready"))
        assertNull(RideSpeech.spoken("`cargo test` ```"))
        assertEquals("Use a cheap part, e.g. an ESP32.", RideSpeech.spoken("Use a cheap part, e.g. an ESP32. It costs little."))
        val long = (1..40).joinToString(" ") { "word$it" }
        val cut = RideSpeech.spoken(long)!!
        assertEquals(RideSpeech.WORDS_MAX, cut.split(' ').size)
        assertTrue(cut.endsWith("…"))
    }
}
