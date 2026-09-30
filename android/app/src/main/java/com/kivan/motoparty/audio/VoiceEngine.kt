package com.kivan.motoparty.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.kivan.motoparty.core.JitterBuffer
import com.kivan.motoparty.core.TalkStats
import com.kivan.motoparty.core.VoicePacket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Full-duplex talk audio: mic -> Opus -> [send]; [onPacket] -> jitter buffer -> Opus -> speaker.
 * Runs on two dedicated audio-priority threads between [start] and [stop]. Call mode routing
 * is the caller's job ([AudioRouter]), and [start]/[stop] belong on the one [AudioThread] with it.
 */
class VoiceEngine(
    private val send: (ts: Long, payload: ByteArray) -> Unit,
    /** The sender's running 16 kHz clock (PROTOCOL.md: random start, runs while talk is closed). */
    private val clockTs: () -> Long,
    /**
     * The capture loop read its first frame of this session, i.e. the microphone is delivering
     * (F7: half of what the "live" earcon means; see [LiveCue]). Called once per [start], from
     * the capture thread with that thread's `elapsedRealtime`, so it must not block: the host
     * hops to Main itself.
     */
    private val onCaptureUp: (atMs: Long) -> Unit = {},
    /**
     * The **headset's own** microphone signal is arriving: the recorder is routed to a Bluetooth SCO
     * input and has delivered a run of non-silent frames ([MicLive]; F9a: the last third of what the
     * "live" earcon means). Same contract as [onCaptureUp] — once per [start], from the capture
     * thread with its `elapsedRealtime`, must not block.
     */
    private val onMicLive: (atMs: Long) -> Unit = {},
    /**
     * Opens a WAV dump for this capture session, or returns null when the debug setting is off —
     * which is what it does on every ride that is not a microphone test (Stage A of the wired-mic
     * plan). Called once per [start], from the capture thread before the first read; [PcmDump] is
     * built so that neither this call nor a frame ever blocks that thread.
     */
    private val openDump: () -> PcmDump? = { null },
    /**
     * F9c: called on the capture thread before the recorder is opened. On a route that needs SCO it
     * blocks until the framework's communication device is SCO, a bounded timeout, or
     * `stillWanted()` turns false, and returns whether SCO is up; null = nothing to wait for (any
     * other route, e.g. the no-headset speaker). A recorder opened before SCO lands on the built-in
     * mic and stays near-silent after it migrates; the re-open ([CaptureReopen]) is the backstop.
     */
    private val awaitCaptureRoute: (stillWanted: () -> Boolean) -> Boolean? = { null },
) {
    /** One [start]..[stop]. Only [onFailed] of the talk that failed is told. */
    private class Session(val onFailed: (what: String, e: Throwable) -> Unit)

    /**
     * One `AudioRecord` routing report, handed from the routing thread to the capture loop. [gen]:
     * which recorder of this session it is about (F9c re-opens it).
     */
    private class RouteReport(val gen: Int, val type: Int?, val atMs: Long)

    private val jitter = JitterBuffer { SystemClock.elapsedRealtime() }
    /**
     * The running session; the loops run while it is still theirs. Not a plain flag: a loop that
     * outlives [stop]'s join (stuck opening the mic during a route switch) would otherwise carry
     * on under the next [start], next to its replacement.
     */
    private val session = AtomicReference<Session?>(null)
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null

    @Volatile var framesSent = 0L; private set
    @Volatile var framesPlayed = 0L; private set
    /** Every frame the capture loop encoded, sent or dropped as DTX: proof the loop is alive. */
    @Volatile var framesCaptured = 0L; private set
    /**
     * When the running engine read its first frame (`elapsedRealtime`), or null before that.
     * Cleared by [start] only: an engine carried across a collapsed close/open (see [TalkAudio])
     * keeps it, which is exactly the answer the re-opened talk needs — its microphone is already
     * live and no new [onCaptureUp] will ever come.
     */
    @Volatile var captureUpAtMs: Long? = null; private set
    /**
     * When the running engine established the headset's own mic signal (`elapsedRealtime`), or null
     * before that. Carried across a collapsed close/open exactly like [captureUpAtMs]: the earpieces
     * are already in the call, and no new [onMicLive] will ever come.
     */
    @Volatile var micLiveAtMs: Long? = null; private set
    /**
     * Where the in-talk recognizer gets its audio, or null (no talk, API < 33, or it failed). Set
     * and cleared on Main while the engine runs; read by the capture loop once per frame. Frames
     * go in raw, before the encoder, so DTX cannot cut a command (PROTOCOL.md "Commands").
     */
    @Volatile var tee: PcmTee? = null
    /** F9a's condition (c), fed by the capture loop; only that loop and [start] touch it. */
    private val micLive = MicLive()
    private var underrunsAtStart = 0

    /** A session is running (false after [stop], or after a loop failed on its own). */
    val isRunning: Boolean get() = session.get() != null

    val jitterTargetMs: Int get() = synchronized(jitter) { jitter.targetMs }
    val underruns: Int get() = synchronized(jitter) { jitter.underruns }

    fun onPacket(packet: VoicePacket) {
        if (session.get() == null) return
        synchronized(jitter) {
            if (packet.kind == VoicePacket.KIND_KEEPALIVE) {
                jitter.insertKeepalive(packet.seq)
                return
            }
            jitter.insert(packet.seq, packet.ts, packet.payload)
        }
    }

    /**
     * [onFailed]: one of the two loops of this talk died — in practice the microphone could not
     * be opened. Called from that loop's thread; the host turns it into PROTOCOL.md's
     * `talk.close{reason:"unavailable"}` on Main.
     */
    @Synchronized
    fun start(onFailed: (what: String, e: Throwable) -> Unit = { _, _ -> }) {
        if (session.get() != null) return
        val t = StepTimer()
        synchronized(jitter) {
            jitter.reset()
            underrunsAtStart = jitter.underruns
        }
        framesSent = 0
        framesPlayed = 0
        framesCaptured = 0
        captureUpAtMs = null
        micLiveAtMs = null
        micLive.reset()
        val s = Session(onFailed)
        session.set(s)
        // L9: the "live" beep of this talk goes to the call route. Have its track built (on the
        // earcons' own thread) while the capture below waits for the route, so firing it is only
        // `play()`. Released by [stop] if the talk ends before it was live.
        Earcons.prepare(Earcons.Kind.LIVE, call = true)
        captureThread = thread(name = "voice-capture") { runCatching { captureLoop(s) }.onFailure { fail(s, "capture", it) } }
        playbackThread = thread(name = "voice-playback") { runCatching { playbackLoop(s) }.onFailure { fail(s, "playback", it) } }
        Log.i(TAG, t.line("start"))
    }

    /** Idempotent, and safe after a loop failed on its own (which ends the session early). */
    @Synchronized
    fun stop() {
        val wasRunning = session.getAndSet(null) != null || captureThread != null
        val t = StepTimer()
        if (wasRunning) Earcons.discardPrepared()
        captureThread?.join(500)
        t.step("join capture")
        playbackThread?.join(500)
        t.step("join playback")
        val stuck = listOfNotNull(captureThread, playbackThread).filter { it.isAlive }.joinToString { it.name }
        captureThread = null
        playbackThread = null
        if (wasRunning) {
            Log.i(TAG, stats())
            Log.i(TAG, t.line("stop") + if (stuck.isEmpty()) "" else ", still running: $stuck")
        }
    }

    /**
     * One line per talk, for the bench: the host cannot otherwise count its own receive loss.
     * The arithmetic and the format live in [TalkStats] (unit-tested; a bench parser reads it).
     */
    fun stats(): String = synchronized(jitter) {
        TalkStats(
            captured = framesCaptured, sent = framesSent, received = jitter.received,
            played = framesPlayed, seqSpan = jitter.seqSpan, late = jitter.underruns - underrunsAtStart,
            fec = jitter.fecUsed, plc = jitter.concealed, keepalives = jitter.keepalives,
            jitterTargetMs = jitter.targetMs, shed = jitter.shed,
            depthMeanMs = jitter.meanDepthMs, depthMaxMs = jitter.maxDepthMs,
        ).line()
    }

    /** A loop threw. Only interesting while talk is meant to be running; teardown races are not. */
    private fun fail(s: Session, what: String, e: Throwable) {
        Log.e(TAG, what, e)
        // Ending the session stops the other loop too: half-duplex talk is not talk. Only the
        // first failure of a still-current session reports.
        if (session.compareAndSet(s, null)) s.onFailed(what, e)
    }

    /**
     * One opened recorder with its effects, replaced whole by a re-open (F9c). [gen] tells a late
     * routing report of a released recorder from one of its replacement.
     */
    private class Mic(val record: AudioRecord, val aec: AcousticEchoCanceler?, val ns: NoiseSuppressor?, val gen: Int) {
        var listener: AudioRouting.OnRoutingChangedListener? = null
        private var released = false

        /** Idempotent: a re-open that failed half-way leaves the old one released already. */
        fun release() {
            if (released) return
            released = true
            listener?.let { l -> runCatching { record.removeOnRoutingChangedListener(l) } }
            runCatching { record.stop() }
            record.release()
            aec?.release()
            ns?.release()
        }
    }

    /** A `VOICE_COMMUNICATION` recorder with AEC + NS on its session, not started yet. */
    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before talk can open.
    private fun openMic(gen: Int): Mic {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // 200 ms of slack (was 80): read() still returns as soon as one frame is there, so this
        // adds no latency, but a loop that stalls (GC, a blocked send) no longer overruns it.
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 2 * CAPTURE_BUFFER_FRAMES),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("AudioRecord not initialised")
        }
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } else null
        val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } else null
        return Mic(record, aec, ns, gen)
    }

    private fun captureLoop(s: Session) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val t = StepTimer()
        // Everything the F9a diagnostics print is measured from here ("session-start +0 ms").
        val startedAtMs = SystemClock.elapsedRealtime()
        // F9c: on an SCO route, open the recorder only once the framework has SCO as its
        // communication device (bounded; null = nothing to wait for). A recorder opened before that
        // lands on the built-in mic and stays near-silent after it migrates ([CaptureReopen]).
        val waited = runCatching { awaitCaptureRoute { session.get() === s } }
            .onFailure { Log.w(TAG, "capture route wait failed: $it") }.getOrNull()
        if (waited != null) {
            val ms = SystemClock.elapsedRealtime() - startedAtMs
            Log.i(TAG, "capture waited for sco $ms ms" + if (waited) "" else " (not up, opening anyway)")
            t.step("wait for sco")
        }
        if (session.get() !== s) return
        var mic = openMic(0)
        t.step("AudioRecord")
        val encoder = OpusEncoder()
        // Off by default; when it is on, the file is opened by the dump's own writer thread, so
        // this costs the capture thread one pool allocation and nothing else.
        val dump = runCatching { openDump() }.onFailure { Log.w(TAG, "capture dump refused: $it") }.getOrNull()
        t.step("effects+encoder")
        val pcm = ShortArray(FRAME)
        // Continue the running clock; frames then advance it by exactly 320 samples each.
        var ts = clockTs()
        // Capture diagnostics (bench finding 4: ~83 % of the expected frames on some talks).
        var readStartNanos = 0L
        var slowestWorkNanos = 0L
        var longestReadNanos = 0L
        // F9a diagnostics: the loudest sample per 100 ms of the first 4 s, and the input's route
        // story. One line per talk, at 4 s or at stop — whichever comes first.
        val peaks = IntArray(TRACE_BUCKETS)
        var bucketsFilled = 0
        var traceLogged = false
        var firstRoutedType: Int? = null
        var firstRoutedAtMs = 0L
        var lastRoutedType: Int? = null
        var routeSeen = false
        // F9c: the current recorder's last known (non-null) input, and a pending re-open.
        val reopen = CaptureReopen()
        var lastKnownType: Int? = null
        var reopenFrom: Int? = null
        var reopenedAtMs: Long? = null
        /**
         * A routing report, from either source. The capture thread is the only one that gets here,
         * so the counters above need no lock.
         */
        fun noteRouted(type: Int?, atMs: Long) {
            if (routeSeen && type == lastRoutedType) return
            routeSeen = true
            lastRoutedType = type
            Log.i(TAG, "capture routed to ${ScoRule.describe(type)} +${atMs - startedAtMs} ms")
            if (firstRoutedType == null && type != null) {
                firstRoutedType = type
                firstRoutedAtMs = atMs
            }
            val was = lastKnownType
            if (type != null) lastKnownType = type
            if (reopen.routed(type)) {
                // Migrated from another input onto SCO: this recorder's SCO signal is not trusted
                // (F9c), so MicLive does not hear of it; the re-opened recorder's report counts.
                reopenFrom = was
                return
            }
            // null = the framework has not said yet, which is not "somewhere else".
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) micLive.routedToSco(atMs)
            else if (type != null) micLive.routedElsewhere()
        }
        // The routing listener (API 24+) runs on its own thread — never Main, which a talk open can
        // block — and only hands the report over; the capture loop picks it up on its next frame.
        val routeReport = AtomicReference<RouteReport?>(null)
        val routeThread = HandlerThread("voice-route")
        var routeHandler: Handler? = null
        val routePollPending = AtomicBoolean(false)
        fun startMic(m: Mic) {
            val l = AudioRouting.OnRoutingChangedListener { routing ->
                routeReport.set(RouteReport(m.gen, routing.routedDevice?.type, SystemClock.elapsedRealtime()))
            }
            val handler = routeHandler ?: Handler(routeThread.looper).also { routeHandler = it }
            m.record.addOnRoutingChangedListener(l, handler)
            m.listener = l
            m.record.startRecording()
            // A route that cannot give us the mic (a phone call took it) fails here, not above.
            check(m.record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not start" }
            // The route is often already known here; if it is not, the listener and the periodic
            // re-read below answer for it.
            noteRouted(m.record.routedDevice?.type, SystemClock.elapsedRealtime())
        }
        try {
            routeThread.start()
            startMic(mic)
            t.step("startRecording")
            readStartNanos = System.nanoTime()
            while (session.get() === s) {
                val readFrom = System.nanoTime()
                var got = 0
                while (got < FRAME && session.get() === s) {
                    val n = mic.record.read(pcm, got, FRAME - got)
                    if (n < 0) error("AudioRecord.read: $n")
                    got += n
                }
                if (session.get() !== s) break
                val workFrom = System.nanoTime()
                val atMs = SystemClock.elapsedRealtime()
                // The raw frame's loudest sample, before the encoder touches it: one pass over 320
                // shorts, no allocation. This is F9a's "is the headset's mic really delivering".
                var peak = 0
                for (v in pcm) {
                    val a = if (v < 0) -v.toInt() else v.toInt()
                    if (a > peak) peak = a
                }
                // The frame exactly as the microphone delivered it, before the encoder or anything
                // else touches it: that is the recording an A/B of two microphones is made from.
                dump?.offer(pcm)
                // Same frame, same rule, for the recognizer: never blocks (see [PcmTee]).
                tee?.offer(pcm)
                // A report of a recorder already replaced is about nothing that exists any more.
                routeReport.getAndSet(null)?.let { if (it.gen == mic.gen) noteRouted(it.type, it.atMs) }
                // Backstop while the input device is still unknown, every ~500 ms. The getter can
                // binder into the audio server and was seen blocked for over a second during a
                // route change (longer than the recorder's buffer), so the routing thread reads it
                // and hands the answer over like a listener report; one read in flight at most.
                if (micLive.scoRoutedAtMs == null && reopenFrom == null && framesCaptured % ROUTE_RECHECK_FRAMES == 0L &&
                    routePollPending.compareAndSet(false, true)
                ) {
                    val m = mic
                    val posted = routeHandler?.post {
                        // A recorder released meanwhile throws or says null; its `gen` is stale anyway.
                        runCatching { m.record.routedDevice?.type }
                            .onSuccess { routeReport.set(RouteReport(m.gen, it, SystemClock.elapsedRealtime())) }
                        routePollPending.set(false)
                    }
                    if (posted != true) routePollPending.set(false)
                }
                micLive.frame(peak, atMs)?.let { at ->
                    micLiveAtMs = at
                    // Only posts to Main, exactly like onCaptureUp.
                    runCatching { onMicLive(at) }.onFailure { Log.w(TAG, "onMicLive failed: $it") }
                }
                val bucket = ((atMs - startedAtMs) / TRACE_BUCKET_MS).toInt()
                if (bucket < TRACE_BUCKETS) {
                    if (peak > peaks[bucket]) peaks[bucket] = peak
                    bucketsFilled = maxOf(bucketsFilled, bucket + 1)
                } else if (!traceLogged) {
                    Log.i(TAG, micTrace(startedAtMs, firstRoutedType, firstRoutedAtMs, reopenedAtMs, peaks, bucketsFilled))
                    traceLogged = true
                }
                if (framesCaptured == 0L) {
                    t.step("first frame")
                    Log.i(TAG, t.line("capture up"))
                    // The mic is delivering: half of the "live" earcon's condition (F7). Cheap and
                    // never blocking — the callback only posts to Main.
                    val at = SystemClock.elapsedRealtime()
                    captureUpAtMs = at
                    runCatching { onCaptureUp(at) }.onFailure { Log.w(TAG, "onCaptureUp failed: $it") }
                } else {
                    longestReadNanos = maxOf(longestReadNanos, workFrom - readFrom)
                }
                val packet = encoder.encode(pcm)
                framesCaptured++
                // PROTOCOL.md: frames encoded in DTX (incl. comfort-noise updates) are not sent.
                if (!encoder.inDtx && packet.size > 2) {
                    send(ts, packet)
                    framesSent++
                }
                ts = (ts + FRAME) and 0xffffffffL
                slowestWorkNanos = maxOf(slowestWorkNanos, System.nanoTime() - workFrom)
                val from = reopenFrom
                if (from != null && session.get() === s) {
                    // F9c: the recorder migrated onto SCO from another input. Release it and open a
                    // fresh one (with fresh effects) on this thread; playback, the session, the
                    // encoder, the dump and the tee carry on untouched.
                    reopenFrom = null
                    val reopenFromMs = SystemClock.elapsedRealtime()
                    val old = mic
                    old.release()
                    mic = openMic(old.gen + 1)
                    reopen.reopened()
                    lastKnownType = null
                    routeSeen = false
                    // Its first report goes to MicLive as usual: the live cue counts this recorder.
                    startMic(mic)
                    val now = SystemClock.elapsedRealtime()
                    reopenedAtMs = now
                    Log.i(
                        TAG,
                        "capture reopened on ${ScoRule.describe(mic.record.routedDevice?.type)} +${now - startedAtMs} ms " +
                            "(was ${ScoRule.describe(from)}, took ${now - reopenFromMs} ms)",
                    )
                    // The frames the re-open cost are time the stream really lost: keep the sender's
                    // clock honest instead of playing them out late.
                    val lostFrames = (SystemClock.elapsedRealtime() - reopenFromMs) / VoicePacket.FRAME_MS
                    ts = (ts + lostFrames * FRAME) and 0xffffffffL
                }
            }
        } finally {
            // Returns at once: the writer drains what is queued and closes the file by itself,
            // because this teardown has 500 ms before [stop] gives up on it.
            dump?.close()
            routeThread.quitSafely() // safe on a thread that never started (no looper)
            if (readStartNanos != 0L) {
                // A re-open that failed half-way leaves a released recorder here: no line, no crash.
                runCatching { captureLine(mic.record, readStartNanos, slowestWorkNanos, longestReadNanos) }
                    .onSuccess { Log.i(TAG, it) }
                if (!traceLogged) {
                    Log.i(TAG, micTrace(startedAtMs, firstRoutedType, firstRoutedAtMs, reopenedAtMs, peaks, bucketsFilled))
                }
            }
            mic.release()
            encoder.close()
        }
    }

    /**
     * `capture: read N frames in N ms (N expected), device N frames, slowest encode+send N ms,
     * longest read wait N ms`. `device` is the recorder's own frame position (AudioRecord
     * timestamp; -1 if it has none): well above `read` × 320 means frames were overrun, i.e. this
     * loop fell behind. `read` well below `expected` with `device` just as low means the input
     * itself did not deliver (route switch), not the loop.
     */
    private fun captureLine(record: AudioRecord, fromNanos: Long, workNanos: Long, readNanos: Long): String {
        val ms = (System.nanoTime() - fromNanos) / 1_000_000
        val stamp = AudioTimestamp()
        val device = if (record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) stamp.framePosition / FRAME else -1L
        return "capture: read $framesCaptured frames in $ms ms (${ms / VoicePacket.FRAME_MS} expected), device $device frames, " +
            "slowest encode+send ${workNanos / 1_000_000} ms, longest read wait ${readNanos / 1_000_000} ms"
    }

    /**
     * One line per talk, for the next device run (F9a): what the input was routed to and when, when
     * the headset's mic signal was established, and the level the mic actually delivered over the
     * first [TRACE_BUCKETS] × [TRACE_BUCKET_MS] ms — the loudest sample per bucket, e.g.
     *
     *     mic trace: session-start +0 ms routed=builtin_mic@+31 ms, sco@+142 ms, reopened@+120 ms,
     *     live@+388 ms, peaks/100ms: 0 0 0 4 7 812 1033 …
     *
     * `reopened@` (F9c) is the last time the recorder was re-opened after migrating onto SCO, or
     * `none`; `sco@` is then the re-opened recorder's SCO moment, the only one [MicLive] counts.
     *
     * Zeros until the earpieces join the call and level from then on is what this fix assumes; level
     * *before* `sco@` is the built-in mic and is ignored by [MicLive]; low level between `sco@` and
     * real speech is the case that needs [MicLive.PEAK_THRESHOLD] raised.
     */
    private fun micTrace(startedAtMs: Long, routedType: Int?, routedAtMs: Long, reopenedAtMs: Long?, peaks: IntArray, filled: Int): String {
        fun off(v: Long?): String = if (v == null) "none" else "+${v - startedAtMs} ms"
        val routed = if (routedType == null) "none" else "${ScoRule.describe(routedType)}@${off(routedAtMs)}"
        return "mic trace: session-start +0 ms routed=$routed, sco@${off(micLive.scoRoutedAtMs)}, " +
            "reopened@${off(reopenedAtMs)}, " +
            "live@${off(micLive.liveAtMs)}, peaks/${TRACE_BUCKET_MS}ms: " +
            (0 until filled).joinToString(" ") { peaks[it].toString() }
    }

    private fun playbackLoop(s: Session) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val t = StepTimer()
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(min, FRAME * 2 * 3))
            .build()
        t.step("AudioTrack")
        val decoder = OpusDecoder()
        val pcm = ShortArray(FRAME)
        var writes = 0L
        var writesFrom = 0L
        // L3: the blocking write keeps the buffer full, so its size is delay in front of every
        // frame. Ask for two frames' worth; the track clamps that to what it can do, and every
        // underrun from then on grows it a frame, back to the full buffer at worst.
        val capacity = runCatching { track.bufferCapacityInFrames }.getOrDefault(0)
        val granted = runCatching { track.setBufferSizeInFrames(FRAME * PLAYBACK_BUFFER_FRAMES) }
            .onFailure { Log.w(TAG, "playback buffer size refused: $it") }.getOrDefault(-1)
        val buffer = if (granted > 0 && capacity >= granted) GrowOnUnderrun(granted, FRAME, capacity) else null
        var startUpUnderruns = 0
        try {
            track.play()
            t.step("play")
            writesFrom = System.nanoTime()
            while (session.get() === s) {
                val out = synchronized(jitter) { jitter.pull() }
                val n = when (out) {
                    is JitterBuffer.Out.Play -> decoder.decode(out.payload, pcm)
                    is JitterBuffer.Out.Fec -> decoder.decodeFec(out.successor, pcm)
                    JitterBuffer.Out.Conceal -> decoder.conceal(pcm)
                    JitterBuffer.Out.Silence -> 0
                }
                if (n <= 0) pcm.fill(0)
                // Blocking write paces this loop at the output clock, one frame per 20 ms.
                track.write(pcm, 0, FRAME)
                if (writes++ == 0L) {
                    t.step("first write")
                    Log.i(TAG, t.line("playback up"))
                }
                if (buffer != null && writes % UNDERRUN_CHECK_FRAMES == 0L) {
                    val count = track.underrunCount
                    if (writes == UNDERRUN_CHECK_FRAMES) {
                        // The track starting on an empty buffer is not a buffer too small.
                        startUpUnderruns = count
                        buffer.baseline(count)
                    } else {
                        buffer.underruns(count)?.let { size ->
                            val now = runCatching { track.setBufferSizeInFrames(size) }.getOrDefault(-1)
                            Log.i(TAG, "playback track: underrun $count, buffer -> $now frames")
                        }
                    }
                }
                if (out is JitterBuffer.Out.Play) framesPlayed++
            }
        } finally {
            if (writesFrom != 0L) {
                // More frames written than expected = the output took a burst (e.g. re-routed
                // mid-talk), which moves the jitter buffer's play-out clock ahead of the stream.
                val ms = (System.nanoTime() - writesFrom) / 1_000_000
                val reanchors = synchronized(jitter) { jitter.reanchors }
                Log.i(TAG, "playback: wrote $writes frames in $ms ms (${ms / VoicePacket.FRAME_MS} expected), $reanchors re-anchors")
                // `playback track: mode low_latency, buffer 640 of 1924 frames (asked 640, granted
                // 640, grew 0x), underruns 0 (0 at start-up)`: what the device gave, for L3.
                runCatching {
                    "playback track: mode ${performanceMode(track.performanceMode)}, " +
                        "buffer ${track.bufferSizeInFrames} of $capacity frames " +
                        "(asked ${FRAME * PLAYBACK_BUFFER_FRAMES}, granted $granted, grew ${buffer?.grown ?: 0}x), " +
                        "underruns ${track.underrunCount} ($startUpUnderruns at start-up)"
                }.onSuccess { Log.i(TAG, it) }
            }
            runCatching { track.stop() }
            track.release()
            decoder.close()
        }
    }

    companion object {
        const val RATE = VoicePacket.SAMPLE_RATE
        const val FRAME = VoicePacket.FRAME_SAMPLES
        private const val TAG = "VoiceEngine"
        private const val CAPTURE_BUFFER_FRAMES = 10
        /** The talk track's buffer to start with: 40 ms (L3); it grows on underruns. */
        private const val PLAYBACK_BUFFER_FRAMES = 2
        /** Look at the track's underrun count every 10 writes (200 ms); the first look is the baseline. */
        private const val UNDERRUN_CHECK_FRAMES = 10L

        /** `AudioTrack.getPerformanceMode()` as a word, for the log lines of both engines. */
        fun performanceMode(mode: Int): String = when (mode) {
            AudioTrack.PERFORMANCE_MODE_LOW_LATENCY -> "low_latency"
            AudioTrack.PERFORMANCE_MODE_NONE -> "none"
            AudioTrack.PERFORMANCE_MODE_POWER_SAVING -> "power_saving"
            else -> "mode$mode"
        }

        /** `mic trace:` covers the first [TRACE_BUCKETS] × [TRACE_BUCKET_MS] ms = 4 s of capture. */
        private const val TRACE_BUCKET_MS = 100L
        private const val TRACE_BUCKETS = 40

        /**
         * How often the capture loop re-reads `AudioRecord.getRoutedDevice()` while the input device
         * is still not a Bluetooth SCO one: 25 frames = ~500 ms. Only a backstop for a routing
         * callback that never comes (F9a) — the listener is the real source.
         */
        private const val ROUTE_RECHECK_FRAMES = 25L
    }
}
