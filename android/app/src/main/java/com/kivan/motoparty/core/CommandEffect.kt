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
    /** A volume command changes the call stream (what the rider hears in a talk), not media. */
    val callVolume: Boolean = false,
) {
    enum class Reply {
        /** No talk: the media route, as before (F9b's MediaCue decides when). */
        MEDIA,
        /** Spoken in the talk, on the call route, now. */
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
            return when (cmd) {
                // Music cannot play in a talk: these end it, and the music and the reply wait for
                // the headset to come back to media mode (the old command path spoke across it).
                is Command.Play, Command.Resume -> CommandEffect(by, Reply.AFTER_CLOSE)
                Command.End -> CommandEffect(by, Reply.NONE)
                // The talk stays open; pause cancels the resume after it, next/previous pick what
                // resumes. Volume is local: only the rider's own changes the call stream.
                Command.Pause, Command.Next, Command.Previous, Command.Unknown -> CommandEffect(null, Reply.CALL)
                Command.VolumeUp, Command.VolumeDown -> CommandEffect(null, Reply.CALL, callVolume = !fromClient)
            }
        }
    }
}
