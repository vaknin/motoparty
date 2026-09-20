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
) {
    /** The talk session that wants the audio, or null for closed. Written on Main. */
    @Volatile private var wanted: Int? = null
    /** Audio thread only: this talk holds one router reference. */
    private var routed = false
    /** The session the running voice engine belongs to (for failure reports). */
    @Volatile private var owner = 0

    /**
     * Whose voice engine is running: the session a capture-thread callback belongs to now. After a
     * collapsed close/open that is the *re-opened* talk, which is the one still waiting for its
     * live earcon ([LiveCue]).
     */
    val ownerSession: Int get() = owner

    /** Talk [session] opened. The job completes when the route and the voice engine are up. */
    fun open(session: Int): Job {
        wanted = session
        return audio.post("talk audio") { settle() }
    }

    /** Talk closed. The job completes when the route is back (or was kept for a re-open). */
    fun close(): Job {
        wanted = null
        return audio.post("talk teardown") { settle() }
    }

    private fun settle() {
        val want = wanted
        if (want != null) {
            bringUp(want)
            return
        }
        if (!routed) return // an earlier settle already tore down (or never brought up)
        voiceStop()
        val reopened = wanted
        if (reopened != null) {
            log("re-open during teardown: call route kept (session $reopened)")
            bringUp(reopened)
            return
        }
        routed = false
        exitCall()
        closedEarcon()
    }

    private fun bringUp(session: Int) {
        try {
            if (!routed) {
                routed = true
                enterCall()
            } else if (owner != session && voiceRunning()) {
                log("re-open before teardown: call route kept (session $session)")
            }
            owner = session
            // Not running = first open, or the engine of a collapsed talk failed meanwhile.
            if (!voiceRunning()) voiceStart { what, e -> onFailed(owner, what, e) }
        } catch (e: Throwable) {
            Log.e(TAG, "talk audio failed", e)
            onFailed(session, "call route failed", e)
        }
    }

    private companion object {
        const val TAG = "TalkAudio"
    }
}
