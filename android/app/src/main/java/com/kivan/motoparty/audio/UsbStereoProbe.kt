package com.kivan.motoparty.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The Lark A1 receiver recorded as stereo — rider on L, passenger on R — while the AirPods stay on
 * A2DP music. Two debug instruments on one recorder:
 *
 * - [start], gate S4 of `research/MIC.md` §6 (2026-09-28): one 15 s clip per source —
 *   `UNPROCESSED`, `MIC`, `CAMCORDER` — each logged with the route, the format it got, and
 *   [StereoStats]'s verdict. It passed on all three.
 * - [startLong] / [stopLong]: one recording from `MIC` until stopped (or [LONG_MAX_BYTES]), for the
 *   ride that A/Bs the kit's noise cancelling and doubles as the RF-dropout check (A3).
 *
 * Deliberately **not** the talk path: talk takes `MODE_IN_COMMUNICATION` and the AirPods' call
 * link, and `VOICE_COMMUNICATION` can only ever get the mono `voip_tx` input port. This never
 * touches the audio mode or the communication device: 48 kHz stereo 16-bit,
 * `setPreferredDevice(usb)`, no effects attached. WAVs go next to the talk dumps. A beep in the ears
 * (media stream) starts each recording; two beeps end it. The route is re-read every second, because
 * a receiver pulled out mid-ride would otherwise be replaced by the phone's own mic in silence.
 *
 * The one thing it cannot see is the mixPort the policy picked: that is read with
 * `adb shell dumpsys media.audio_policy` while it runs.
 */
class UsbStereoProbe(
    private val context: Context,
    private val dir: File,
    private val log: (String) -> Unit,
    /** true while a long recording runs, false when it has ended; called on the probe's thread. */
    private val onLong: (Boolean) -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    /** Starts the three S4 clips on their own thread; false if anything is already recording. */
    fun start(): Boolean = launch(long = false)

    /** Starts one recording that runs until [stopLong]; false if anything is already recording. */
    fun startLong(): Boolean = launch(long = true)

    /** Ends a long recording within one frame. Harmless when none is running. */
    fun stopLong() = stopRequested.set(true)

    private fun launch(long: Boolean): Boolean {
        if (!running.compareAndSet(false, true)) return false
        stopRequested.set(false)
        thread(name = "usb-probe", isDaemon = true) {
            try {
                if (long) onLong(true)
                run(long)
            } catch (e: Exception) {
                log("usb probe failed: $e")
            } finally {
                running.set(false)
                if (long) onLong(false)
            }
        }
        return true
    }

    private fun run(long: Boolean) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val am = context.getSystemService(AudioManager::class.java)
        val usb = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        if (usb == null) {
            log("usb probe: no USB input — plug the receiver into the phone")
            return
        }
        log(
            "usb probe: ${name(usb)}, channels ${usb.channelCounts.toList()}, rates ${usb.sampleRates.toList()}, " +
                "unprocessed supported=${am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)}, " +
                "${state(am)}",
        )
        val tones = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME) }.getOrNull()
        try {
            if (long) {
                record(am, usb, "ride", MediaRecorder.AudioSource.MIC, tones, clipMs = null, maxBytes = LONG_MAX_BYTES)
            } else {
                for ((label, source) in SOURCES) {
                    record(am, usb, label, source, tones, clipMs = CLIP_MS, maxBytes = PcmDump.MAX_BYTES)
                    SystemClock.sleep(GAP_MS)
                }
            }
        } finally {
            tones?.release()
        }
        log("usb probe: done, ${state(am)}")
    }

    /** One WAV from [source]: for [clipMs], or until [stopLong] when it is null. */
    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted before the service runs at all.
    private fun record(
        am: AudioManager,
        usb: AudioDeviceInfo,
        label: String,
        source: Int,
        tones: ToneGenerator?,
        clipMs: Long?,
        maxBytes: Long,
    ) {
        val format = AudioFormat.Builder()
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, FRAME * 2 * BUFFER_FRAMES))
                .build()
        } catch (e: Exception) {
            log("usb probe $label: refused: $e")
            return
        }
        var dump: PcmDump? = null
        try {
            val preferred = record.setPreferredDevice(usb)
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                log("usb probe $label: did not start")
                return
            }
            dump = PcmDump(file(label), RATE, FRAME, channels = 2, maxBytes = maxBytes, log = log).also { it.start() }
            tones?.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_MS)
            log(
                if (clipMs != null) "usb probe $label: speak now — pink TX counts to five, then yellow TX (${clipMs / 1000} s)"
                else "usb probe $label: recording until stopped",
            )
            val stats = StereoStats(RATE)
            val pcm = ShortArray(FRAME)
            val startedAt = SystemClock.elapsedRealtime()
            var routed = record.routedDevice?.id
            var nextCheck = startedAt + ROUTE_CHECK_MS
            var nextProgress = startedAt + PROGRESS_MS
            while (!stopRequested.get() && (clipMs == null || SystemClock.elapsedRealtime() < startedAt + clipMs)) {
                var got = 0
                while (got < FRAME) {
                    val n = record.read(pcm, got, FRAME - got)
                    if (n < 0) error("AudioRecord.read: $n")
                    got += n
                }
                dump.offer(pcm)
                stats.add(pcm)
                val now = SystemClock.elapsedRealtime()
                if (now >= nextCheck) {
                    nextCheck = now + ROUTE_CHECK_MS
                    val d = record.routedDevice
                    if (d?.id != routed) {
                        routed = d?.id
                        log("usb probe $label: +${(now - startedAt) / 1000} s route changed to ${d?.let(::name) ?: "none"}")
                    }
                }
                if (clipMs == null && now >= nextProgress) {
                    nextProgress = now + PROGRESS_MS
                    log("usb probe $label: ${(now - startedAt) / 60_000} min, ${dump.dropped} dropped")
                }
            }
            tones?.startTone(ToneGenerator.TONE_PROP_ACK, ACK_MS)
            val got = record.format
            log(
                "usb probe $label: routed ${record.routedDevice?.let(::name) ?: "none"} " +
                    "(preferred ${name(usb)} accepted=$preferred), format ${got.sampleRate} Hz " +
                    "${got.channelCount} ch, ${state(am)}",
            )
            log("usb probe $label: ${stats.line()}")
        } finally {
            runCatching { record.stop() }
            record.release()
            dump?.close()
        }
    }

    private fun file(label: String): File {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return File(dir, "usb-$label-$stamp.wav")
    }

    /** The two things the probe must leave alone: the audio mode, and music going to the AirPods. */
    private fun state(am: AudioManager): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "mode ${am.mode}"
        val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val out = am.getAudioDevicesForAttributes(media).joinToString { ScoRule.describe(it.type) }
        return "mode ${am.mode}, media out [$out]"
    }

    private fun name(d: AudioDeviceInfo) = "${ScoRule.describe(d.type)}#${d.id} \"${d.productName}\""

    companion object {
        const val RATE = 48_000

        /** 20 ms of interleaved stereo: 960 frames × 2 samples. */
        const val FRAME = RATE / 50 * 2

        /** 48 kHz stereo 16-bit is 192 kB/s, so this is ~2 h 25 min: longer than any ride's A/B. */
        const val LONG_MAX_BYTES = 1600L * 1024 * 1024

        private const val BUFFER_FRAMES = 10
        private const val CLIP_MS = 15_000L
        private const val GAP_MS = 2_000L
        private const val ROUTE_CHECK_MS = 1_000L
        private const val PROGRESS_MS = 5 * 60_000L
        private const val TONE_MS = 150

        /** The ACK tone is two bursts; shorter than this cuts the second off. */
        private const val ACK_MS = 400
        private const val TONE_VOLUME = 60

        /** In the order S4 asks for: the source most likely to be left alone first. */
        private val SOURCES = listOf(
            "unprocessed" to MediaRecorder.AudioSource.UNPROCESSED,
            "mic" to MediaRecorder.AudioSource.MIC,
            "camcorder" to MediaRecorder.AudioSource.CAMCORDER,
        )
    }
}
