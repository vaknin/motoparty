package com.kivan.motoparty.spike

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.speech.SpeechRecognizer

/**
 * The cheap facts from RESEARCH §6 that only the device can answer. No audio, no mic, no UI.
 */
object PropsTest {

    fun run(context: Context) {
        SpikeLog.log("=== PROPS test ===")
        val am = context.getSystemService(AudioManager::class.java)

        SpikeLog.log(
            "props: Build ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), " +
                "Android ${Build.VERSION.RELEASE}, SDK_INT=${Build.VERSION.SDK_INT}"
        )
        SpikeLog.log(
            "props: PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED = " +
                "${am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)} " +
                "(null or \"false\" means MediaRecorder.AudioSource.UNPROCESSED is not usable)"
        )
        SpikeLog.log(
            "props: PROPERTY_OUTPUT_SAMPLE_RATE = ${am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)}"
        )
        SpikeLog.log(
            "props: PROPERTY_OUTPUT_FRAMES_PER_BUFFER = " +
                am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        )
        SpikeLog.log("props: AudioManager.mode = ${Names.audioMode(am.mode)}")
        SpikeLog.log("props: SpeechRecognizer.isRecognitionAvailable = ${SpeechRecognizer.isRecognitionAvailable(context)}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpikeLog.log(
                "props: SpeechRecognizer.isOnDeviceRecognitionAvailable = " +
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            )
        } else {
            SpikeLog.log("props: isOnDeviceRecognitionAvailable needs API 31")
        }
        SpikeLog.log("PROPS VERDICT: unprocessed=${am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)}, sdk=${Build.VERSION.SDK_INT}, onDeviceRecognizer=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)}")
    }
}
