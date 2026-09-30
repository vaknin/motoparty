package com.kivan.motoparty.link

import com.kivan.motoparty.core.CloseReason
import com.kivan.motoparty.core.FirstPhraseGate
import com.kivan.motoparty.core.Role

/**
 * Host-side talk authority, PROTOCOL.md "Talk flow". Pure state machine: callers turn the
 * returned [Action] into broadcasts and audio start/stop. Not thread-safe (host thread only).
 * Talk ends on a trigger, never on silence: the AirPods mic never goes DTX, so a silence close
 * could not fire (user, 2026-09-29).
 */
class TalkController {
    sealed interface Action {
        data class Open(val by: String) : Action
        data class Close(val by: String, val reason: String) : Action
    }

    var isOpen = false
        private set

    /** [Role] of the side whose trigger opened the current (or last) talk. */
    var openedBy: String? = null
        private set

    /** A trigger fired on this phone: toggles. */
    fun onLocalTrigger(): Action = if (isOpen) close(Role.HOST, CloseReason.TRIGGER) else open(Role.HOST)

    /**
     * May a press on this phone go ahead while something else holds the microphone ([micHeld]:
     * the debug USB probe)? Only an *open* is blocked: a press always ends an open talk, or the
     * rider would be left in a talk they cannot end.
     */
    fun localTriggerAllowed(micHeld: Boolean): Boolean = isOpen || !micHeld

    fun onClientOpenRequest(): Action? = if (isOpen) null else open(Role.CLIENT)

    /**
     * The client asks to end talk. [reason] is echoed only when it is
     * [CloseReason.UNAVAILABLE] — the client's own microphone failed right after our `talk.open`
     * (PROTOCOL.md "Talk flow" step 1) — so the other side learns why; anything else is an
     * ordinary trigger. Either way this is a close request, never a refusal to be negotiated.
     */
    fun onClientCloseRequest(reason: String = CloseReason.TRIGGER): Action? = when {
        !isOpen -> null
        reason == CloseReason.UNAVAILABLE -> close(Role.CLIENT, CloseReason.UNAVAILABLE)
        else -> close(Role.CLIENT, CloseReason.TRIGGER)
    }

    fun onLinkLost(): Action? = if (isOpen) close(Role.HOST, CloseReason.LINK) else null

    /**
     * A client `hello` replaced the connection of the client named [previous] (PROTOCOL.md
     * "Liveness"). The same [name] is that client back on a new socket before we noticed the old
     * one die: the talk carries on. Another name is another client: link loss for the talk.
     */
    fun onClientReplaced(previous: String, name: String): Action? = if (previous == name) null else onLinkLost()

    /**
     * This phone's own microphone or call route failed after talk was already open (the route
     * change and the capture thread are asynchronous, so it can only be found out late). The
     * mirror image of the client's `talk.close{reason:"unavailable"}`: an ordinary close, with the
     * reason carried so the other side learns why.
     */
    fun onMicFailure(): Action? = if (isOpen) close(Role.HOST, CloseReason.UNAVAILABLE) else null

    /**
     * A spoken command ends the talk (PROTOCOL.md "Commands": `play`, `resume`, `end`), or a play
     * by touch does ("Browsing" step 3: a `now` enqueue, a jump): closed like a press by the side
     * that spoke or touched, [by].
     */
    fun onCommandClose(by: String): Action? = if (isOpen) close(by, CloseReason.TRIGGER) else null

    private fun open(by: String): Action {
        isOpen = true
        openedBy = by
        return Action.Open(by)
    }

    private fun close(by: String, reason: String): Action {
        isOpen = false
        return Action.Close(by, reason)
    }
}

/**
 * A client connected while a talk is open. A solo talk stops being one: from here the rider is
 * talking to someone, so no later phrase may be a command (PROTOCOL.md "Commands": only the
 * opener's first phrase is, and a talk that was already running has long spent it). The gate is
 * re-opened as one that never commands; true when it was solo, and the caller then stops its
 * recognizer. Any other gate is left alone (a same-name reconnect keeps its window).
 */
fun FirstPhraseGate.onClientJoined(): Boolean {
    if (role != FirstPhraseGate.Role.SOLO) return false
    open(FirstPhraseGate.Role.OTHER)
    return true
}
