package com.kivan.motoparty.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.kivan.motoparty.core.JitterBuffer
import com.kivan.motoparty.core.VoicePacket
import kotlin.concurrent.thread

/**
 * Full-duplex talk audio: mic -> Opus -> [send]; [onPacket] -> jitter buffer -> Opus -> speaker.
 * Runs on two dedicated audio-priority threads between [start] and [stop]. Call mode routing
 * is the caller's job ([AudioRouter]).
 */
class VoiceEngine(
    private val send: (ts: Long, payload: ByteArray) -> Unit,
    /** The sender's running 16 kHz clock (PROTOCOL.md: random start, runs while talk is closed). */
    private val clockTs: () -> Long,
    /** A kind-1 packet was sent or received (drives the 10 s silence close). */
    private val onActivity: () -> Unit,
) {
    private val jitter = JitterBuffer { SystemClock.elapsedRealtime() }
    @Volatile private var running = false
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null

    @Volatile var framesSent = 0L; private set
    @Volatile var framesPlayed = 0L; private set

    val jitterTargetMs: Int get() = synchronized(jitter) { jitter.targetMs }
    val underruns: Int get() = synchronized(jitter) { jitter.underruns }

    fun onPacket(packet: VoicePacket) {
        if (!running) return
        synchronized(jitter) {
            if (packet.kind == VoicePacket.KIND_KEEPALIVE) {
                jitter.insertKeepalive(packet.seq)
                return
            }
            jitter.insert(packet.seq, packet.ts, packet.payload)
        }
        onActivity() // every kind-1 packet is voice activity: senders drop DTX frames
    }

    @Synchronized
    fun start() {
        if (running) return
        synchronized(jitter) { jitter.reset() }
        running = true
        captureThread = thread(name = "voice-capture") { runCatching { captureLoop() }.onFailure { Log.e(TAG, "capture", it) } }
        playbackThread = thread(name = "voice-playback") { runCatching { playbackLoop() }.onFailure { Log.e(TAG, "playback", it) } }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        captureThread?.join(500)
        playbackThread?.join(500)
        captureThread = null
        playbackThread = null
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before talk can open.
    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 2 * 4),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialised" }
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } else null
        val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } else null
        val encoder = OpusEncoder()
        val pcm = ShortArray(FRAME)
        // Continue the running clock; frames then advance it by exactly 320 samples each.
        var ts = clockTs()
        try {
            record.startRecording()
            while (running) {
                var got = 0
                while (got < FRAME && running) {
                    val n = record.read(pcm, got, FRAME - got)
                    if (n < 0) error("AudioRecord.read: $n")
                    got += n
                }
                if (!running) break
                val packet = encoder.encode(pcm)
                // PROTOCOL.md: frames encoded in DTX (incl. comfort-noise updates) are not sent.
                if (!encoder.inDtx && packet.size > 2) {
                    send(ts, packet)
                    framesSent++
                    onActivity()
                }
                ts = (ts + FRAME) and 0xffffffffL
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            aec?.release()
            ns?.release()
            encoder.close()
        }
    }

    private fun playbackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
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
        val decoder = OpusDecoder()
        val pcm = ShortArray(FRAME)
        try {
            track.play()
            while (running) {
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
                if (out is JitterBuffer.Out.Play) framesPlayed++
            }
        } finally {
            runCatching { track.stop() }
            track.release()
            decoder.close()
        }
    }

    companion object {
        const val RATE = VoicePacket.SAMPLE_RATE
        const val FRAME = VoicePacket.FRAME_SAMPLES
        private const val TAG = "VoiceEngine"
    }
}
