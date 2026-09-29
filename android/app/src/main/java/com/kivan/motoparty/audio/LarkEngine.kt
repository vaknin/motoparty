package com.kivan.motoparty.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.kivan.motoparty.core.VoicePacket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The host-mic talk's audio (PROTOCOL.md "Host-mic talk", 2026-09-29): one USB stereo receiver
 * (Hollyland Lark A1 RX) carries both riders — left the rider (TX1, pink), right the passenger
 * (TX2, yellow). The sibling of [VoiceEngine] for this mode; the earbud path is untouched.
 *
 * One capture thread, every 20 ms ([LarkPipeline] does the signal work):
 * - the raw 48 kHz stereo frame → the capture dump (when on) and [LarkLevels];
 * - the rider → high-pass → 16 kHz → Opus → [send] (unless DTX), and to the recognizer's [tee]
 *   when the host opened the talk (or it is solo);
 * - the passenger → high-pass → a local `AudioTrack` (USAGE_MEDIA, 48 kHz mono) — the rider's
 *   headset on A2DP, or the speaker — and, decimated, to the [tee] when the client opened the talk.
 *
 * What it never does: take call mode, set a communication device, attach AEC/NS (the Lark's own
 * processing and the high-pass are the whole chain), or play anyone their own voice. The recorder
 * is `AudioSource.MIC` steered with `setPreferredDevice(usb)`, exactly the capture
 * [UsbStereoProbe] proved on the Pixel (48 kHz, 2 ch, A2DP untouched).
 *
 * The capture thread never blocks except in `AudioRecord.read`, which paces it: the dump and the
 * tee are pool-backed and drop when full, the passenger's `AudioTrack` is written non-blocking and
 * a frame it cannot take is dropped and counted. Binder calls for the log line run on the routing
 * thread.
 *
 * The recorder must be on the receiver: [LarkRouteCheck] on every routing report and every ~500 ms.
 * Routed elsewhere (unplugged, refused), a read error, or a receiver not present at [start] fails
 * the session, which the host turns into `talk.close{by:"host", reason:"unavailable"}`.
 *
 * [start]/[stop] belong on the [AudioThread], like [VoiceEngine]'s.
 */
class LarkEngine(
    private val context: Context,
    private val send: (ts: Long, payload: ByteArray) -> Unit,
    /** The sender's running 16 kHz clock, shared with [VoiceEngine] (PROTOCOL.md "Voice"). */
    private val clockTs: () -> Long,
    /** First frame read; once per [start], from the capture thread. Must not block (the host hops to Main). */
    private val onCaptureUp: (atMs: Long) -> Unit = {},
    /** A 48 kHz stereo dump for this session, or null (setting off). Called on the capture thread. */
    private val openDump: () -> PcmDump? = { null },
    /** The lines the device run reads (`lark: …`, `lark stats: …`); from any thread. */
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    /** What to capture from; set on Main before the talk's audio is brought up, read at [start]. */
    data class Config(val deviceId: Int, val name: String, val swap: Boolean)

    private class Session(val onFailed: (what: String, e: Throwable) -> Unit)

    @Volatile var config: Config? = null

    /**
     * Recognise the passenger (right, after swap) instead of the rider: the client opened this
     * host-mic talk (PROTOCOL.md "Commands"). Read once per frame, so a collapsed re-open that
     * changes the opener takes effect on the next frame.
     */
    @Volatile var asrPassenger = false

    /** The in-talk recognizer's input (16 kHz mono), or null. Set on Main, read per frame. */
    @Volatile var tee: PcmTee? = null

    private val session = AtomicReference<Session?>(null)
    private var captureThread: Thread? = null

    @Volatile var captureUpAtMs: Long? = null; private set
    @Volatile var framesSent = 0L; private set
    @Volatile var framesPlayed = 0L; private set
    @Volatile var framesDropped = 0L; private set

    /** Client voice packets that arrived during this session: dropped (PROTOCOL.md "Voice"). */
    private val clientAudio = AtomicLong(0)
    private val clientAudioLogged = AtomicBoolean(false)

    val isRunning: Boolean get() = session.get() != null

    /**
     * A voice packet from the client. In a host-mic talk the client sends keepalives only; audio
     * that still arrives (an older client) is dropped and counted, logged once per talk.
     */
    fun onPacket(packet: VoicePacket) {
        if (session.get() == null || packet.kind != VoicePacket.KIND_AUDIO) return
        clientAudio.incrementAndGet()
        if (clientAudioLogged.compareAndSet(false, true)) log("lark: client audio in a host-mic talk, dropping it")
    }

    @Synchronized
    fun start(onFailed: (what: String, e: Throwable) -> Unit = { _, _ -> }) {
        if (session.get() != null) return
        val cfg = config ?: run {
            onFailed("lark", IllegalStateException("no receiver configured"))
            return
        }
        framesSent = 0
        framesPlayed = 0
        framesDropped = 0
        captureUpAtMs = null
        clientAudio.set(0)
        clientAudioLogged.set(false)
        val s = Session(onFailed)
        session.set(s)
        captureThread = thread(name = "lark-capture") { runCatching { captureLoop(s, cfg) }.onFailure { fail(s, it) } }
    }

    /** Idempotent, and safe after the loop failed on its own. */
    @Synchronized
    fun stop() {
        session.set(null)
        val t = captureThread ?: return
        t.join(500)
        captureThread = null
        if (t.isAlive) log("lark: capture thread still running after stop")
    }

    private fun fail(s: Session, e: Throwable) {
        Log.e(TAG, "lark capture", e)
        if (session.compareAndSet(s, null)) s.onFailed("lark", e)
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before talk can open.
    private fun captureLoop(s: Session, cfg: Config) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val t = StepTimer()
        val am = context.getSystemService(AudioManager::class.java)
        val usb = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == cfg.deviceId }
            ?: error("receiver #${cfg.deviceId} is gone")
        val pipeline = LarkPipeline(cfg.swap)
        val frameSamples = pipeline.frameSamples
        val format = AudioFormat.Builder()
            .setSampleRate(LarkPipeline.RATE_IN)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val minIn = AudioRecord.getMinBufferSize(LarkPipeline.RATE_IN, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minIn, frameSamples * 2 * CAPTURE_BUFFER_FRAMES))
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("AudioRecord not initialised")
        }
        // Built inside the try below, so a failure half-way still releases the recorder.
        var track: AudioTrack? = null
        var encoder: OpusEncoder? = null
        val routeThread = HandlerThread("lark-route")
        var dump: PcmDump? = null
        val levels = LarkLevels()
        val check = LarkRouteCheck(cfg.deviceId)
        val routedReport = AtomicReference<Int?>(null)
        val routeReported = AtomicBoolean(false)
        var startedAtMs = 0L
        var playErrorLogged = false
        try {
            val out = passengerTrack().also { track = it }
            val enc = OpusEncoder().also { encoder = it }
            val preferred = record.setPreferredDevice(usb)
            routeThread.start()
            val routeHandler = Handler(routeThread.looper)
            record.addOnRoutingChangedListener(
                AudioRouting.OnRoutingChangedListener { r ->
                    routedReport.set(r.routedDevice?.id)
                    routeReported.set(true)
                },
                routeHandler,
            )
            t.step("AudioRecord")
            dump = runCatching { openDump() }.onFailure { log("lark: capture dump refused: $it") }.getOrNull()
            out.play()
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not start" }
            startedAtMs = SystemClock.elapsedRealtime()
            t.step("startRecording")
            val raw = ShortArray(frameSamples)
            var ts = clockTs()
            var frames = 0L
            fun judge(routedId: Int?, atMs: Long) {
                if (check.check(routedId, atMs - startedAtMs) == LarkRouteCheck.Verdict.WRONG) {
                    val where = routedId?.let { id -> am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id } }
                    error("recorder routed to ${where?.let(::name) ?: routedId?.let { "#$it" } ?: "nothing"}, not the receiver")
                }
            }
            judge(record.routedDevice?.id, startedAtMs)
            while (session.get() === s) {
                var got = 0
                while (got < frameSamples && session.get() === s) {
                    val n = record.read(raw, got, frameSamples - got)
                    if (n < 0) error("AudioRecord.read: $n")
                    got += n
                }
                if (session.get() !== s) break
                val atMs = SystemClock.elapsedRealtime()
                dump?.offer(raw)
                levels.add(raw, frameSamples / 2)
                val passengerAsr = asrPassenger
                val tee = tee
                pipeline.process(raw, passengerDown16 = passengerAsr && tee != null)
                tee?.offer(if (passengerAsr) pipeline.passenger16 else pipeline.rider16)
                // The passenger into the rider's ears: never wait for the output.
                val w = out.write(pipeline.passenger48, 0, pipeline.passenger48.size, AudioTrack.WRITE_NON_BLOCKING)
                if (w == pipeline.passenger48.size) {
                    framesPlayed++
                } else {
                    framesDropped++
                    if (w < 0 && !playErrorLogged) {
                        playErrorLogged = true
                        log("lark: passenger playback write failed: $w")
                    }
                }
                if (routeReported.getAndSet(false)) judge(routedReport.get(), atMs)
                else if (frames % ROUTE_RECHECK_FRAMES == 0L) judge(record.routedDevice?.id, atMs)
                if (frames == 0L) {
                    t.step("first frame")
                    Log.i(TAG, t.line("capture up"))
                    captureUpAtMs = atMs
                    runCatching { onCaptureUp(atMs) }.onFailure { Log.w(TAG, "onCaptureUp failed: $it") }
                    // Binder calls: on the routing thread, never on this one.
                    routeHandler.post { runCatching { log(routedLine(am, record, out, preferred)) } }
                }
                val packet = enc.encode(pipeline.rider16)
                if (!enc.inDtx && packet.size > 2) {
                    send(ts, packet)
                    framesSent++
                }
                ts = (ts + VoicePacket.FRAME_SAMPLES) and 0xffffffffL
                frames++
            }
        } finally {
            dump?.close()
            routeThread.quitSafely()
            runCatching { record.stop() }
            record.release()
            track?.let { runCatching { it.stop() }; it.release() }
            encoder?.close()
            if (startedAtMs != 0L) {
                log(levels.line(LarkPipeline.RATE_IN, framesSent, framesPlayed, framesDropped, clientAudio.get()))
                levels.silentLines(LarkPipeline.RATE_IN, cfg.swap).forEach(log)
            }
        }
    }

    /** USAGE_MEDIA so it goes where the music goes (the AirPods on A2DP, else the speaker). */
    private fun passengerTrack(): AudioTrack {
        val rate = LarkPipeline.RATE_IN
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            // A few frames of slack: the USB input and the output run on different clocks, and a
            // full buffer drops a frame rather than letting the delay grow.
            .setBufferSizeInBytes(maxOf(min, rate / 50 * 2 * PLAYBACK_BUFFER_FRAMES))
            .build()
    }

    /**
     * `lark: routed usb_device#27 "Lark A1" (preferred accepted=true) format 48000 Hz 2 ch, mode 0,
     * media out [bt_a2dp], passenger out bt_a2dp`.
     */
    private fun routedLine(am: AudioManager, record: AudioRecord, track: AudioTrack, preferred: Boolean): String {
        val f = record.format
        val media = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            am.getAudioDevicesForAttributes(attrs).joinToString { ScoRule.describe(it.type) }
        } else {
            "?"
        }
        return "lark: routed ${record.routedDevice?.let(::name) ?: "none"} (preferred accepted=$preferred) " +
            "format ${f.sampleRate} Hz ${f.channelCount} ch, mode ${am.mode}, media out [$media], " +
            "passenger out ${ScoRule.describe(track.routedDevice?.type)}"
    }

    private fun name(d: AudioDeviceInfo) = "${ScoRule.describe(d.type)}#${d.id} \"${d.productName}\""

    companion object {
        private const val TAG = "LarkEngine"
        /** 200 ms of recorder slack, as [VoiceEngine]. */
        private const val CAPTURE_BUFFER_FRAMES = 10
        /** 60 ms of playback buffer. */
        private const val PLAYBACK_BUFFER_FRAMES = 3
        /** Re-read the route every 25 frames (~500 ms), as a backstop to the listener. */
        private const val ROUTE_RECHECK_FRAMES = 25L
    }
}
