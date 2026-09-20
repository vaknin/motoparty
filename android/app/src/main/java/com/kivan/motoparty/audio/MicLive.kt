package com.kivan.motoparty.audio

/**
 * "The headset's own microphone signal is really arriving" (F9a, 2026-09-20). Pure: no Android, no
 * clock of its own — every event carries the time it happened.
 *
 * Why this exists. F8 made the live earcon wait for the audio framework's communication device
 * ([ScoWatch]), which is honest about *this phone*: on the 2026-09-20 device run
 * (`tools/bench/results/2026-09-20-t3b-talk-f8`, Pixel 8 / Android 17 / AirPods Pro) the beep came
 * +329…+598 ms after the Bluetooth stack's last `BTA_AG_SCO_OPEN_ST`, with the HAL path applied and
 * the stream started 0.3–0.5 s *before* the beep. The user still heard the beep cut off — the
 * AirPods begin rendering call audio some varying time after the phone begins sending it, and the
 * phone is told nothing about that. The one thing that does travel back over the link is the
 * headset's **microphone**: while the earpieces are not yet in the call, no audio comes from them.
 * The same run also shows capture starting on the built-in mic (`(null) => microphones ->
 * voip-capture-0`) and being moved to `bluetooth-sco-headset-microphones` ~0.1 s later, so "the
 * first captured frame" is not "the first frame from the headset".
 *
 * So the rule here, fed by [VoiceEngine]'s capture loop:
 *
 *  1. the recorder is routed to a Bluetooth SCO **input** ([routedToSco], from
 *     `AudioRecord.getRoutedDevice()`; [routedElsewhere] undoes it while the beep is still pending —
 *     the built-in-mic start above, or an SCO flap);
 *  2. **after** that moment, [framesNeeded] consecutive 20 ms frames are not digital silence
 *     (peak |sample| above [peakThreshold]).
 *
 * A frame read before the SCO moment never counts, however loud it was: that was the built-in mic,
 * or the rider's own bike noise on it. One silent frame inside the run restarts the count, so a
 * single non-zero sample of switching noise cannot open the gate.
 *
 * Exactly one fire per [reset] (i.e. per [VoiceEngine.start]); [LiveCue] holds what the earcon then
 * does with it.
 */
class MicLive(
    /** Consecutive non-silent 20 ms frames required after the SCO moment. */
    private val framesNeeded: Int = FRAMES_NEEDED,
    /** A frame counts as signal when its peak |sample| is **above** this. */
    private val peakThreshold: Int = PEAK_THRESHOLD,
) {
    private var scoAt: Long? = null
    private var liveAt: Long? = null
    private var run = 0

    /** When the recorder became routed to a Bluetooth SCO input, or null: not (yet) routed there. */
    val scoRoutedAtMs: Long? get() = scoAt

    /** When the headset's mic signal was first established, or null: not yet. */
    val liveAtMs: Long? get() = liveAt

    val isLive: Boolean get() = liveAt != null

    /** A new [VoiceEngine.start]: nothing the previous engine saw applies. */
    @Synchronized
    fun reset() {
        scoAt = null
        liveAt = null
        run = 0
    }

    /**
     * The recorder's input is a Bluetooth SCO device as of [atMs]. The first report counts; later
     * ones (a periodic re-read of the same state) change nothing.
     */
    @Synchronized
    fun routedToSco(atMs: Long) {
        if (liveAt != null || scoAt != null) return
        scoAt = atMs
        run = 0
    }

    /**
     * The recorder's input is some other device (the built-in mic at the start of a talk, or after
     * an SCO drop). Before the mic was established that undoes condition 1; after it, nothing —
     * the signal is reported once per engine and a flap's honesty is [LiveCue]'s `scoDisconnected`.
     */
    @Synchronized
    fun routedElsewhere() {
        if (liveAt != null) return
        scoAt = null
        run = 0
    }

    /**
     * One captured 20 ms frame, read at [atMs], whose loudest sample is [peak] (|sample|). Returns
     * the moment the headset's mic counts as live — once — or null.
     */
    @Synchronized
    fun frame(peak: Int, atMs: Long): Long? {
        if (liveAt != null) return null
        val sco = scoAt ?: return null
        if (atMs < sco) return null // read before the headset was the source: not its signal
        if (peak <= peakThreshold) {
            run = 0
            return null
        }
        if (++run < framesNeeded) return null
        liveAt = atMs
        return atMs
    }

    companion object {
        /**
         * 10 frames = 200 ms of continuous signal. To be tuned from the `VoiceEngine: mic trace:`
         * line of the next device run (F9a): raise it if the trace shows level before the headset
         * is really live, lower it if the beep is comfortably late everywhere.
         */
        const val FRAMES_NEEDED = 10

        /**
         * Digital silence is exactly 0 on this path; 16 of 32767 (−66 dBFS) is the smallest level
         * that cannot be a rounding artefact. Same caveat: the `mic trace:` peaks of the next
         * device run say whether the SCO input delivers real zeros before the headset joins (then
         * this can stay tiny) or a low hiss (then it must go above that hiss).
         */
        const val PEAK_THRESHOLD = 16
    }
}
