package com.kivan.motoparty.spike

import android.media.AudioManager
import android.media.AudioRecordingConfiguration

/**
 * The silent-fallback detector (RESEARCH §3.2 trap 4).
 *
 * This app never records. So ANY active recording configuration that appears while the
 * recognition session is open was opened by the recognizer — which means it ignored
 * `EXTRA_AUDIO_SOURCE` and went to the mic instead of our pipe. That failure is otherwise
 * invisible: the recognizer just returns nothing, or returns whatever the room said.
 *
 * Both mechanisms are used, because either can miss: the callback only fires on *changes*
 * (a recording already running when we register produces no event), and a poll can step over
 * a recording shorter than its interval.
 */
class RecordingWatch(private val am: AudioManager, private val clock: SpikeLog.Clock) {

    @Volatile
    private var sawRecording = false

    @Volatile
    private var running = false

    private var poller: Thread? = null

    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            report("callback", configs ?: emptyList())
        }
    }

    fun start() {
        running = true
        am.registerAudioRecordingCallback(callback, Main.handler)
        report("baseline", am.activeRecordingConfigurations)
        poller = Thread({
            while (running) {
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (!running) return@Thread
                val configs = try {
                    am.activeRecordingConfigurations
                } catch (t: Throwable) {
                    SpikeLog.err("rec-watch: poll failed", t); return@Thread
                }
                if (configs.isNotEmpty()) report("poll", configs)
            }
        }, "rec-watch").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        poller?.interrupt()
        poller = null
        runCatching { am.unregisterAudioRecordingCallback(callback) }
        report("final", am.activeRecordingConfigurations)
    }

    /** True if any recording was active at any point after [start]. */
    fun micWasOpened(): Boolean = sawRecording

    private fun report(why: String, configs: List<AudioRecordingConfiguration>) {
        if (configs.isEmpty()) {
            SpikeLog.log("${clock.stamp()} rec-watch[$why]: no active recordings")
            return
        }
        // A recording present in the very first "baseline" read predates our session, so it is
        // not evidence about the recognizer — flag it, but do not count it.
        if (why != "baseline") sawRecording = true
        for (c in configs) {
            SpikeLog.log(
                "${clock.stamp()} rec-watch[$why]: clientSource=${Names.audioSource(c.clientAudioSource)} " +
                    "effectiveSource=${Names.audioSource(c.audioSource)} " +
                    "sessionId=${c.clientAudioSessionId} " +
                    "silenced=${c.isClientSilenced} " +
                    "clientFormat=${c.clientFormat.sampleRate}Hz/${c.clientFormat.channelCount}ch " +
                    "device=${Names.device(c.audioDevice)}"
            )
        }
    }
}
