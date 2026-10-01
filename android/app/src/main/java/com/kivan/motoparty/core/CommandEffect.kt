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
        fun of(cmd: Command, talkOpen: Boolean, fromClient: Boolean): CommandEffect = of(cmd.toActions(), talkOpen, fromClient)

        /**
         * The same for a list of voice actions (PROTOCOL.md "Commands", *Voice actions*, *Running a
         * list*): a list with any action ends the talk, as a command does. An empty list is an
         * unparsed phrase.
         */
        fun of(actions: List<VoiceAction>, talkOpen: Boolean, fromClient: Boolean): CommandEffect {
            val by = if (fromClient) Role.CLIENT else Role.HOST
            val onlyEnd = actions.isNotEmpty() && actions.all { it == VoiceAction.End }
            if (!talkOpen) {
                // "end" with no talk has nothing to end; everything else is as it always was.
                return CommandEffect(null, if (onlyEnd) Reply.NONE else Reply.MEDIA)
            }
            // Volume is local: the rider's own changes this phone's media volume after the close;
            // one from the client should never have been sent and counts as unparsed.
            val acting = if (fromClient) actions.filterNot { it.isVolume } else actions
            return when {
                // Not a command: nothing ends (solo: "Didn't catch that", spoken in the talk).
                acting.isEmpty() -> CommandEffect(null, Reply.CALL)
                // `end` has nothing to say: the talk's own closing earcon is the acknowledgement.
                onlyEnd -> CommandEffect(by, Reply.NONE)
                // Every command ends the talk it is spoken in: it was opened to give that command.
                // Music cannot play in a talk, so the music and any reply wait for the headset to
                // come back to media mode.
                else -> CommandEffect(by, Reply.AFTER_CLOSE)
            }
        }
    }
}
