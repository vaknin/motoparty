package com.kivan.motoparty.link

import com.kivan.motoparty.core.CloseReason
import com.kivan.motoparty.core.Role

/**
 * Host-side talk authority, PROTOCOL.md "Talk flow". Pure state machine: callers turn the
 * returned [Action] into broadcasts and audio start/stop. Not thread-safe (host thread only);
 * [noteActivity] may be called from audio threads because it only writes a volatile.
 */
class TalkController(private val nowMs: () -> Long, private val silenceMs: Long = SILENCE_MS) {
    sealed interface Action {
        data class Open(val by: String) : Action
        data class Close(val by: String, val reason: String) : Action
    }

    var isOpen = false
        private set

    /** [Role] of the side whose trigger opened the current (or last) talk. */
    var openedBy: String? = null
        private set

    @Volatile
    private var lastActivityMs = 0L

    /** A trigger fired on this phone: toggles. */
    fun onLocalTrigger(): Action = if (isOpen) close(Role.HOST, CloseReason.TRIGGER) else open(Role.HOST)

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
     * This phone's own microphone or call route failed after talk was already open (the route
     * change and the capture thread are asynchronous, so it can only be found out late). The
     * mirror image of the client's `talk.close{reason:"unavailable"}`: an ordinary close, with the
     * reason carried so the other side learns why.
     */
    fun onMicFailure(): Action? = if (isOpen) close(Role.HOST, CloseReason.UNAVAILABLE) else null

    /** Someone sent a non-DTX frame. */
    fun noteActivity() {
        lastActivityMs = nowMs()
    }

    /** Call periodically; closes after [silenceMs] without activity on either side. */
    fun tick(): Action? =
        if (isOpen && nowMs() - lastActivityMs >= silenceMs) close(Role.HOST, CloseReason.SILENCE) else null

    private fun open(by: String): Action {
        isOpen = true
        openedBy = by
        lastActivityMs = nowMs()
        return Action.Open(by)
    }

    private fun close(by: String, reason: String): Action {
        isOpen = false
        return Action.Close(by, reason)
    }

    companion object {
        const val SILENCE_MS = 10_000L
    }
}
