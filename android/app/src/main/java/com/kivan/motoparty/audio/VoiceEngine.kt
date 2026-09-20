package com.kivan.motoparty.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
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
) {
    /** One [start]..[stop]. Only [onFailed] of the talk that failed is told. */
    private class Session(val onFailed: (what: String, e: Throwable) -> Unit)

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
        try {
            record.startRecording()
            t.step("startRecording")
            // A route that cannot give us the mic (a phone call took it) fails here, not above.
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not start" }
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
            if (readStartNanos != 0L) Log.i(TAG, captureLine(record, readStartNanos, slowestWorkNanos, longestReadNanos))
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
    }
}
