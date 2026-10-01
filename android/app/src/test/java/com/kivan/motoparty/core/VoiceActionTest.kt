package com.kivan.motoparty.core

import com.kivan.motoparty.core.CommandEffect.Reply
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROTOCOL.md "Commands", *Voice actions*: the grammar's commands as actions, and a list's effect on the talk. */
class VoiceActionTest {
    @Test
    fun everyCommandMapsOneToOne() {
        assertEquals(listOf(VoiceAction.Play(Command.Kind.ALBUM, "play")), CommandParser.parse("play album play").toActions())
        assertEquals(
            listOf(VoiceAction.Add(null, "", Command.Where.NEXT, 3)),
            CommandParser.parse("queue next 3 similar").toActions(),
        )
        assertEquals(listOf(VoiceAction.Tell(VoiceAction.About.TRACK)), CommandParser.parse("what's playing").toActions())
        assertEquals(listOf(VoiceAction.End), CommandParser.parse("hang up").toActions())
        assertEquals(listOf(VoiceAction.VolumeDown), CommandParser.parse("quieter").toActions())
        assertEquals(emptyList<VoiceAction>(), CommandParser.parse("watch out for that truck").toActions())
    }

    /** `fixtures/commands.json`: whatever parses is one action, whose canonical form says the same. */
    @Test
    fun theGrammarFixtureGivesOneActionEach() {
        val cases = Fixtures.load("commands.json").jsonObject["cases"]!!.jsonArray
        for (c in cases) {
            val text = c.jsonObject["text"]?.jsonPrimitive?.content ?: continue
            val cmd = CommandParser.parse(text)
            val actions = cmd.toActions()
            if (cmd == Command.Unknown) assertTrue(text, actions.isEmpty()) else assertEquals(text, 1, actions.size)
        }
    }

    @Test
    fun canonicalFormsAreTheFixtures() {
        assertEquals("""{"type":"play","kind":"similar"}""", VoiceAction.Play(null, "").canonical().toString())
        assertEquals(
            """{"type":"add","kind":"song","query":"yellow","where":"next","count":2}""",
            VoiceAction.Add(Command.Kind.SONG, "yellow", Command.Where.NEXT, 2).canonical().toString(),
        )
        assertEquals("""{"type":"remove","artist":"moby"}""", VoiceAction.Remove(artist = "moby").canonical().toString())
        assertEquals("""{"type":"move","at":[3,1],"to":1}""", VoiceAction.Move(listOf(3, 1), 1).canonical().toString())
        assertEquals("""{"type":"seek","by":-30}""", VoiceAction.Seek(by = -30).canonical().toString())
        assertEquals("""{"type":"volumeUp"}""", VoiceAction.VolumeUp.canonical().toString())
    }

    @Test
    fun anyActionEndsTheTalkAndOnlyEndHasNoReply() {
        val list = listOf(VoiceAction.Remove(at = listOf(1)), VoiceAction.Add(Command.Kind.SONG, "yellow", Command.Where.NEXT, null))
        assertEquals(CommandEffect(Role.HOST, Reply.AFTER_CLOSE), CommandEffect.of(list, talkOpen = true, fromClient = false))
        assertEquals(CommandEffect(Role.CLIENT, Reply.AFTER_CLOSE), CommandEffect.of(list, talkOpen = true, fromClient = true))
        assertEquals(CommandEffect(Role.HOST, Reply.NONE), CommandEffect.of(listOf(VoiceAction.End), talkOpen = true, fromClient = false))
        assertEquals(CommandEffect(Role.HOST, Reply.AFTER_CLOSE), CommandEffect.of(listOf(VoiceAction.End, VoiceAction.Next), talkOpen = true, fromClient = false))
        assertEquals(CommandEffect(null, Reply.CALL), CommandEffect.of(emptyList(), talkOpen = true, fromClient = false))
        assertEquals(CommandEffect(null, Reply.MEDIA), CommandEffect.of(list, talkOpen = false, fromClient = false))
    }

    /** The passenger's volume is their own: it does not count, the rest of the list does. */
    @Test
    fun thePassengersVolumeDoesNotEndTheTalk() {
        assertEquals(CommandEffect(null, Reply.CALL), CommandEffect.of(listOf(VoiceAction.VolumeUp), talkOpen = true, fromClient = true))
        assertEquals(
            CommandEffect(Role.CLIENT, Reply.AFTER_CLOSE),
            CommandEffect.of(listOf(VoiceAction.VolumeUp, VoiceAction.Next), talkOpen = true, fromClient = true),
        )
    }

    @Test
    fun repeatTogglesOffQueueTrack() {
        assertEquals(RepeatMode.QUEUE, RepeatMode.OFF.toggled())
        assertEquals(RepeatMode.TRACK, RepeatMode.QUEUE.toggled())
        assertEquals(RepeatMode.OFF, RepeatMode.TRACK.toggled())
        assertEquals(null, RepeatMode.OFF.wire)
        assertEquals("queue", RepeatMode.QUEUE.wire)
    }
}
