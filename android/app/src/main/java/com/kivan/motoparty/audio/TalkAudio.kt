package com.kivan.motoparty.audio

import android.util.Log
import kotlinx.coroutines.Job

/**
 * Talk's share of the [AudioThread]: the call route (one [AudioRouter] reference) and the
 * [VoiceEngine], driven to the *latest* wanted state instead of replaying every request.
 *
 * Every [open]/[close] records what is wanted (on Main) and posts one [settle]. A settle compares
 * wanted with what is actually set up and does only the difference, so an open → close → open that
 * queued up behind a slow route switch costs nothing extra: the close's settle finds talk wanted
 * again and leaves the route and the voice engine alone. A close whose teardown already started
 * re-checks after `voice.stop` (which can take ~0.5 s) and keeps the route if talk was re-opened
 * meanwhile, skipping the exitCall + enterCall pair (~1.8 s on the 2026-09-19 bench).
 *
 * What stays as before (layer 1, see HANDOFF.md section 4):
 * - final state = the last request: the last request's settle runs after it and reads it;
 * - one router reference at most, entered when [routed] flips on and exited when it flips off,
 *   both on the audio thread; [routed] is set *before* enterCall, which counts itself before it
 *   can throw, so the exit that follows balances even a failed enter;
 * - talk closed and settled means exitCall ran, so the phone is back in MODE_NORMAL unless the
 *   recognizer still holds its own reference;
 * - failures carry the session that wanted the audio ([onFailed]), and the host ignores ones
 *   that are no longer current. A voice engine kept across a collapsed close/open reports under
 *   the session that owns it now ([owner]).
 *
 * A host-mic talk (`open(session, lark = true)`, PROTOCOL.md "Host-mic talk") takes no call route:
 * only the [LarkEngine] is started and stopped, the phone stays in MODE_NORMAL. A collapsed re-open
 * that changes mode stops the other engine first, and an earbud talk's route collapsed into a
 * host-mic talk is exited.
 */
class TalkAudio(
    private val audio: AudioThread,
    private val enterCall: () -> Unit,
    private val exitCall: () -> Unit,
    private val voiceStart: (onFailed: (what: String, e: Throwable) -> Unit) -> Unit,
    private val voiceStop: () -> Unit,
    private val voiceRunning: () -> Boolean,
    /** Played on the audio thread right after a real teardown (not after a collapsed one). */
    private val closedEarcon: () -> Unit,
    /** From the audio thread or a voice thread; the host hops to Main and checks [session]. */
    private val onFailed: (session: Int, what: String, e: Throwable) -> Unit,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    /**
     * The host-mic talk's engine ([LarkEngine]; PROTOCOL.md "Host-mic talk"). A talk opened with
     * `lark = true` takes **no call route**: no enterCall, no exitCall, the phone stays in
     * MODE_NORMAL; only this engine is started and stopped.
     */
    private val larkStart: (onFailed: (what: String, e: Throwable) -> Unit) -> Unit = { f -> f("lark", IllegalStateException("no lark engine")) },
    private val larkStop: () -> Unit = {},
    private val larkRunning: () -> Boolean = { false },
) {
    /** What a talk wants: its session and its microphone mode (fixed for the talk). */
    private data class Want(val session: Int, val lark: Boolean)

    /** The talk that wants the audio, or null for closed. Written on Main. */
    @Volatile private var wanted: Want? = null
    /** Audio thread only: this talk holds one router reference. */
    private var routed = false
    /** Audio thread only: the engine this class started and has not stopped (null = none; true = lark). */
    private var engine: Boolean? = null
    /** The session the running voice engine belongs to (for failure reports). */
    @Volatile private var owner = 0

    /**
     * Whose voice engine is running: the session a capture-thread callback belongs to now. After a
     * collapsed close/open that is the *re-opened* talk, which is the one still waiting for its
     * live earcon ([LiveCue]).
     */
    val ownerSession: Int get() = owner

    /**
     * Talk [session] opened; [lark]: a host-mic talk. The job completes when the route (if any)
     * and the engine are up.
     */
    fun open(session: Int, lark: Boolean = false): Job {
        wanted = Want(session, lark)
        return audio.post("talk audio") { settle() }
    }

    /** Talk closed. The job completes when the route is back (or was kept for a re-open). */
    fun close(): Job {
        wanted = null
        return audio.post("talk teardown") { settle() }
    }

    private fun running(lark: Boolean) = if (lark) larkRunning() else voiceRunning()

    private fun stopEngine() {
        when (engine) {
            true -> larkStop()
            false -> voiceStop()
            null -> Unit
        }
        engine = null
    }

    private fun settle() {
        val want = wanted
        if (want != null) {
            bringUp(want)
            return
        }
        if (!routed && engine == null) return // an earlier settle already tore down (or never brought up)
        val was = engine
        stopEngine()
        val reopened = wanted
        if (reopened != null && (reopened.lark == was || was == null)) {
            log("re-open during teardown: ${if (reopened.lark) "lark kept" else "call route kept"} (session ${reopened.session})")
            bringUp(reopened)
            return
        }
        // A re-open in the other mode is brought up by its own settle, after this teardown; the
        // talk is open again, so no closed earcon for it.
        if (routed) {
            routed = false
            exitCall()
        }
        if (reopened == null) closedEarcon()
    }

    private fun bringUp(want: Want) {
        val session = want.session
        try {
            // A collapsed re-open that changed mode: the other engine goes first.
            if (engine != null && engine != want.lark) stopEngine()
            if (want.lark) {
                // An earbud talk's call route, collapsed into a host-mic talk: give it back.
                if (routed) {
                    routed = false
                    exitCall()
                }
                if (owner != session && engine == true && larkRunning()) log("re-open before teardown: lark kept (session $session)")
            } else if (!routed) {
                routed = true
                enterCall()
            } else if (owner != session && voiceRunning()) {
                log("re-open before teardown: call route kept (session $session)")
            }
            owner = session
            // Not running = first open, or the engine of a collapsed talk failed meanwhile.
            if (!running(want.lark)) {
                engine = want.lark
                if (want.lark) larkStart { what, e -> onFailed(owner, what, e) }
                else voiceStart { what, e -> onFailed(owner, what, e) }
            } else {
                engine = want.lark
            }
        } catch (e: Throwable) {
            Log.e(TAG, "talk audio failed", e)
            onFailed(session, if (want.lark) "lark failed" else "call route failed", e)
        }
    }

    private companion object {
        const val TAG = "TalkAudio"
    }
}
