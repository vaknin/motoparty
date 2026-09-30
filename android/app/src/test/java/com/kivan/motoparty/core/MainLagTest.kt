package com.kivan.motoparty.core

import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The "Main was busy" line a bench parser greps for, and the message names it contains.
 * Both are pure, so the format is pinned here instead of on the device.
 */
class MainLagTest {
    @Test
    fun lineFormatIsWhatTheBenchParses() {
        assertEquals(
            "control message talk.open waited 930 ms for Main",
            MainLag.line("talk.open", 930),
        )
    }

    @Test
    fun onlyWaitsOfAtLeastTheThresholdAreLogged() {
        assertNull(MainLag.lineIfLate("ping", 0))
        assertNull(MainLag.lineIfLate("talk.open", MainLag.THRESHOLD_MS - 1))
        assertEquals(
            "control message talk.open waited 100 ms for Main",
            MainLag.lineIfLate("talk.open", MainLag.THRESHOLD_MS),
        )
    }

    @Test
    fun everyWireTypeMatchesItsSerialName() {
        val samples: List<Pair<Message, KSerializer<out Message>>> = listOf(
            Hello(PROTO_VERSION, Role.CLIENT, "P") to Hello.serializer(),
            Ping(1, 2) to Ping.serializer(),
            Pong(1, 2, 3, 4) to Pong.serializer(),
            TalkOpen(Role.CLIENT) to TalkOpen.serializer(),
            TalkClose(Role.HOST, CloseReason.UNAVAILABLE) to TalkClose.serializer(),
            MusicLoad("i", "p", "t", "a", null, 1) to MusicLoad.serializer(),
            MusicReady("i") to MusicReady.serializer(),
            MusicError("i", "m") to MusicError.serializer(),
            MusicPlay("i", 0, 0) to MusicPlay.serializer(),
            MusicPause("i", 0) to MusicPause.serializer(),
            MusicNext("i", 0) to MusicNext.serializer(),
            MusicStop to MusicStop.serializer(),
            MusicControl(ControlAction.NEXT) to MusicControl.serializer(),
            CommandText("t", "en-US") to CommandText.serializer(),
            MusicSearch(1, SearchKind.SONGS, "q") to MusicSearch.serializer(),
            MusicBrowse(1, "r") to MusicBrowse.serializer(),
            MusicResults(1, emptyList()) to MusicResults.serializer(),
            MusicEnqueue(EnqueueMode.NOW, emptyList()) to MusicEnqueue.serializer(),
            MusicEdit(EditOp.CLEAR) to MusicEdit.serializer(),
            Announce("t", Earcon.OK) to Announce.serializer(),
            State(false, null, emptyList()) to State.serializer(),
            Bye("r") to Bye.serializer(),
        )
        for ((message, serializer) in samples) {
            assertEquals(serializer.descriptor.serialName, message.wireType)
        }
        // An unknown type logs whatever came in on the wire.
        assertEquals("future.thing", UnknownMessage("future.thing").wireType)
    }
}
