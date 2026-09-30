package com.kivan.motoparty.core

import com.kivan.motoparty.core.CommandEffect.Reply
import org.junit.Assert.assertEquals
import org.junit.Test

/** PROTOCOL.md "Commands", *Effect on the talk*. */
class CommandEffectTest {
    private val play = Command.Play(Command.Kind.ALBUM, "abbey road")
    private val all = listOf(
        play, Command.Pause, Command.Resume, Command.Next, Command.Previous,
        Command.VolumeUp, Command.VolumeDown, Command.End, Command.NowPlaying, Command.Shuffle, Command.Unknown,
    )

    @Test
    fun everyCommandEndsTheTalkByWhoeverSpokeAndRepliesAfterTheSwitch() {
        for (cmd in listOf(play, Command.Resume, Command.Pause, Command.Next, Command.Previous, Command.NowPlaying, Command.Shuffle, Command.Queue(Command.Where.NEXT, 3, null, ""))) {
            assertEquals("$cmd", CommandEffect(Role.HOST, Reply.AFTER_CLOSE), CommandEffect.of(cmd, talkOpen = true, fromClient = false))
            assertEquals("$cmd", CommandEffect(Role.CLIENT, Reply.AFTER_CLOSE), CommandEffect.of(cmd, talkOpen = true, fromClient = true))
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
    fun anUnparsedPhraseLeavesTheTalkOpenAndIsAnsweredInIt() {
        for (fromClient in listOf(false, true)) {
            assertEquals(CommandEffect(null, Reply.CALL), CommandEffect.of(Command.Unknown, talkOpen = true, fromClient = fromClient))
        }
    }

    /** Volume is local: the rider's own ends the talk (media volume after it); the client's is refused like an unparsed phrase. */
    @Test
    fun volumeInATalkEndsItForTheRiderOnly() {
        for (cmd in listOf(Command.VolumeUp, Command.VolumeDown)) {
            assertEquals(CommandEffect(Role.HOST, Reply.AFTER_CLOSE), CommandEffect.of(cmd, talkOpen = true, fromClient = false))
            assertEquals(CommandEffect(null, Reply.CALL), CommandEffect.of(cmd, talkOpen = true, fromClient = true))
        }
    }

    @Test
    fun withoutATalkNothingClosesAndRepliesUseTheMediaRoute() {
        for (cmd in all - Command.End) {
            assertEquals("$cmd", CommandEffect(null, Reply.MEDIA), CommandEffect.of(cmd, talkOpen = false, fromClient = false))
        }
    }
}
