package com.kivan.motoparty.core

import com.kivan.motoparty.core.CommandEffect.Reply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROTOCOL.md "Commands", *Effect on the talk*. */
class CommandEffectTest {
    private val play = Command.Play(Command.Kind.ALBUM, "abbey road")
    private val all = listOf(
        play, Command.Pause, Command.Resume, Command.Next, Command.Previous,
        Command.VolumeUp, Command.VolumeDown, Command.End, Command.NowPlaying, Command.Shuffle, Command.Unknown,
    )

    @Test
    fun playAndResumeEndTheTalkByWhoeverSpokeAndReplyAfterTheSwitch() {
        for (cmd in listOf(play, Command.Resume)) {
            assertEquals(CommandEffect(Role.HOST, Reply.AFTER_CLOSE), CommandEffect.of(cmd, talkOpen = true, fromClient = false))
            assertEquals(CommandEffect(Role.CLIENT, Reply.AFTER_CLOSE), CommandEffect.of(cmd, talkOpen = true, fromClient = true))
        }
    }

    @Test
    fun endClosesTheTalkWithoutAReply() {
        assertEquals(CommandEffect(Role.HOST, Reply.NONE), CommandEffect.of(Command.End, talkOpen = true, fromClient = false))
        assertEquals(CommandEffect(Role.CLIENT, Reply.NONE), CommandEffect.of(Command.End, talkOpen = true, fromClient = true))
        // Nothing to end.
        assertEquals(CommandEffect(null, Reply.NONE), CommandEffect.of(Command.End, talkOpen = false, fromClient = false))
    }

    @Test
    fun theRestLeaveTheTalkOpenAndReplyInIt() {
        for (cmd in listOf(Command.Pause, Command.Next, Command.Previous, Command.NowPlaying, Command.Shuffle, Command.Unknown)) {
            for (fromClient in listOf(false, true)) {
                assertEquals("$cmd", CommandEffect(null, Reply.CALL), CommandEffect.of(cmd, talkOpen = true, fromClient = fromClient))
            }
        }
    }

    /** Volume is local: in a talk the rider's own changes the call stream; the client's is refused. */
    @Test
    fun volumeInATalkIsTheCallStreamForTheRiderOnly() {
        for (cmd in listOf(Command.VolumeUp, Command.VolumeDown)) {
            val rider = CommandEffect.of(cmd, talkOpen = true, fromClient = false)
            assertNull(rider.closeBy)
            assertEquals(Reply.CALL, rider.reply)
            assertTrue(rider.callVolume)
            assertFalse(CommandEffect.of(cmd, talkOpen = true, fromClient = true).callVolume)
            assertFalse(CommandEffect.of(cmd, talkOpen = false, fromClient = false).callVolume)
        }
    }

    @Test
    fun withoutATalkNothingClosesAndRepliesUseTheMediaRoute() {
        for (cmd in all - Command.End) {
            assertEquals("$cmd", CommandEffect(null, Reply.MEDIA), CommandEffect.of(cmd, talkOpen = false, fromClient = false))
        }
    }
}
