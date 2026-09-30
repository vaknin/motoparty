package com.kivan.motoparty.core

/**
 * PROTOCOL.md "Commands", *Effect on the talk*: what a parsed command does to the talk that is
 * (or is not) open when it arrives, and where its spoken reply goes. The music itself is the
 * host's business; this only decides the order. Pure, so it is tested without a phone.
 */
data class CommandEffect(
    /** Close the talk as soon as the command parses, with this `talk.close.by`; null = leave it. */
    val closeBy: String?,
    val reply: Reply,
) {
    enum class Reply {
        /** No talk: the media route, as before (F9b's MediaCue decides when). */
        MEDIA,
        /** Spoken in the talk, on the call route, now (only "Didn't catch that"). */
        CALL,
        /** After the talk this command closed is torn down and the headset is back in media mode. */
        AFTER_CLOSE,
        /** Nothing to say: the talk's own CLOSED earcon is the acknowledgement. */
        NONE,
    }

    companion object {
        /** [fromClient]: the passenger spoke it (their `command.text`), else this phone's rider. */
        fun of(cmd: Command, talkOpen: Boolean, fromClient: Boolean): CommandEffect {
            val by = if (fromClient) Role.CLIENT else Role.HOST
            if (!talkOpen) {
                // "end" with no talk has nothing to end; everything else is as it always was.
                return CommandEffect(null, if (cmd == Command.End) Reply.NONE else Reply.MEDIA)
            }
            // Every command ends the talk it is spoken in: it was opened to give that command.
            // Music cannot play in a talk, so the music and any reply wait for the headset to come
            // back to media mode.
            return when (cmd) {
                is Command.Play, Command.Resume, Command.Pause, Command.Next, Command.Previous,
                Command.NowPlaying, Command.Shuffle -> CommandEffect(by, Reply.AFTER_CLOSE)
                Command.End -> CommandEffect(by, Reply.NONE)
                // Volume is local: the rider's own changes this phone's media volume after the
                // close; one from the client should never have been sent and counts as unparsed.
                Command.VolumeUp, Command.VolumeDown ->
                    if (fromClient) CommandEffect(null, Reply.CALL) else CommandEffect(by, Reply.AFTER_CLOSE)
                // Not a command: nothing ends (solo: "Didn't catch that", spoken in the talk).
                Command.Unknown -> CommandEffect(null, Reply.CALL)
            }
        }
    }
}
