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
    /** A kind-1 packet was sent or received (drives the 20 s silence close). */
    private val onActivity: () -> Unit,
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
) {
    /** One [start]..[stop]. Only [onFailed] of the talk that failed is told. */
    private class Session(val onFailed: (what: String, e: Throwable) -> Unit)

    /** One `AudioRecord` routing report, handed from the routing thread to the capture loop. */
    private class RouteReport(val type: Int?, val atMs: Long)

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
        onActivity() // every kind-1 packet is voice activity: senders drop DTX frames
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
        captureThread = thread(name = "voice-capture") { runCatching { captureLoop(s) }.onFailure { fail(s, "capture", it) } }
        playbackThread = thread(name = "voice-playback") { runCatching { playbackLoop(s) }.onFailure { fail(s, "playback", it) } }
        Log.i(TAG, t.line("start"))
    }

    /** Idempotent, and safe after a loop failed on its own (which ends the session early). */
    @Synchronized
    fun stop() {
        val wasRunning = session.getAndSet(null) != null || captureThread != null
        val t = StepTimer()
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
            jitterTargetMs = jitter.targetMs,
        ).line()
    }

    /** A loop threw. Only interesting while talk is meant to be running; teardown races are not. */
    private fun fail(s: Session, what: String, e: Throwable) {
        Log.e(TAG, what, e)
        // Ending the session stops the other loop too: half-duplex talk is not talk. Only the
        // first failure of a still-current session reports.
        if (session.compareAndSet(s, null)) s.onFailed(what, e)
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before talk can open.
    private fun captureLoop(s: Session) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val t = StepTimer()
        // Everything the F9a diagnostics print is measured from here ("session-start +0 ms").
        val startedAtMs = SystemClock.elapsedRealtime()
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // 200 ms of slack (was 80): read() still returns as soon as one frame is there, so this
        // adds no latency, but a loop that stalls (GC, a blocked send) no longer overruns it.
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 2 * CAPTURE_BUFFER_FRAMES),
        )
        t.step("AudioRecord")
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialised" }
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } else null
        val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } else null
        val encoder = OpusEncoder()
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
        /**
         * A routing report, from either source. The capture thread is the only one that gets here,
         * so the counters above need no lock.
         */
        fun noteRouted(type: Int?, atMs: Long) {
            if (routeSeen && type == lastRoutedType) return
            routeSeen = true
            lastRoutedType = type
            Log.i(TAG, "capture routed to ${inputName(type)} +${atMs - startedAtMs} ms")
            if (firstRoutedType == null && type != null) {
                firstRoutedType = type
                firstRoutedAtMs = atMs
            }
            // null = the framework has not said yet, which is not "somewhere else".
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) micLive.routedToSco(atMs)
            else if (type != null) micLive.routedElsewhere()
        }
        // The routing listener (API 24+) runs on its own thread — never Main, which a talk open can
        // block — and only hands the report over; the capture loop picks it up on its next frame.
        val routeReport = AtomicReference<RouteReport?>(null)
        val routeListener = AudioRouting.OnRoutingChangedListener { routing ->
            routeReport.set(RouteReport(routing.routedDevice?.type, SystemClock.elapsedRealtime()))
        }
        val routeThread = HandlerThread("voice-route")
        try {
            routeThread.start()
            record.addOnRoutingChangedListener(routeListener, Handler(routeThread.looper))
            record.startRecording()
            t.step("startRecording")
            // A route that cannot give us the mic (a phone call took it) fails here, not above.
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not start" }
            // The route is often already known here; if it is not, the listener and the periodic
            // re-read below answer for it.
            noteRouted(record.routedDevice?.type, SystemClock.elapsedRealtime())
            readStartNanos = System.nanoTime()
            while (session.get() === s) {
                val readFrom = System.nanoTime()
                var got = 0
                while (got < FRAME && session.get() === s) {
                    val n = record.read(pcm, got, FRAME - got)
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
                routeReport.getAndSet(null)?.let { noteRouted(it.type, it.atMs) }
                // Backstop while the input device is still unknown: a getter read inside a loop that
                // runs anyway (every ~500 ms), never a sleep.
                if (micLive.scoRoutedAtMs == null && framesCaptured % ROUTE_RECHECK_FRAMES == 0L) {
                    noteRouted(record.routedDevice?.type, atMs)
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
                    Log.i(TAG, micTrace(startedAtMs, firstRoutedType, firstRoutedAtMs, peaks, bucketsFilled))
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
                    onActivity()
                }
                ts = (ts + FRAME) and 0xffffffffL
                slowestWorkNanos = maxOf(slowestWorkNanos, System.nanoTime() - workFrom)
            }
        } finally {
            runCatching { record.removeOnRoutingChangedListener(routeListener) }
            routeThread.quitSafely() // safe on a thread that never started (no looper)
            if (readStartNanos != 0L) {
                Log.i(TAG, captureLine(record, readStartNanos, slowestWorkNanos, longestReadNanos))
                if (!traceLogged) {
                    Log.i(TAG, micTrace(startedAtMs, firstRoutedType, firstRoutedAtMs, peaks, bucketsFilled))
                }
            }
            runCatching { record.stop() }
            record.release()
            aec?.release()
            ns?.release()
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
     *     mic trace: session-start +0 ms routed=builtin_mic@+31 ms, sco@+142 ms, live@+388 ms,
     *     peaks/100ms: 0 0 0 4 7 812 1033 …
     *
     * Zeros until the earpieces join the call and level from then on is what this fix assumes; level
     * *before* `sco@` is the built-in mic and is ignored by [MicLive]; low level between `sco@` and
     * real speech is the case that needs [MicLive.PEAK_THRESHOLD] raised.
     */
    private fun micTrace(startedAtMs: Long, routedType: Int?, routedAtMs: Long, peaks: IntArray, filled: Int): String {
        fun off(v: Long?): String = if (v == null) "none" else "+${v - startedAtMs} ms"
        val routed = if (routedType == null) "none" else "${inputName(routedType)}@${off(routedAtMs)}"
        return "mic trace: session-start +0 ms routed=$routed, sco@${off(micLive.scoRoutedAtMs)}, " +
            "live@${off(micLive.liveAtMs)}, peaks/${TRACE_BUCKET_MS}ms: " +
            (0 until filled).joinToString(" ") { peaks[it].toString() }
    }

    /** The input device for the F9a log lines, in [ScoRule]'s spelling where it has one. */
    private fun inputName(type: Int?): String = when (type) {
        null -> "none"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin_mic"
        else -> ScoRule.describe(type)
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
                if (out is JitterBuffer.Out.Play) framesPlayed++
            }
        } finally {
            if (writesFrom != 0L) {
                // More frames written than expected = the output took a burst (e.g. re-routed
                // mid-talk), which moves the jitter buffer's play-out clock ahead of the stream.
                val ms = (System.nanoTime() - writesFrom) / 1_000_000
                val reanchors = synchronized(jitter) { jitter.reanchors }
                Log.i(TAG, "playback: wrote $writes frames in $ms ms (${ms / VoicePacket.FRAME_MS} expected), $reanchors re-anchors")
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
