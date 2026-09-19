package com.kivan.motoparty.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player as Media3Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import java.io.File

/**
 * ExoPlayer for local cached files, plus the MediaSession that makes us the target of headset
 * buttons (AirPods presses, BLE remotes). Main thread only. Timing decisions live in
 * [SyncController]; this class only does what it is told.
 */
class Player(
    context: Context,
    /** A headset/remote key press (KeyEvent keycode). Return true if consumed. */
    private val onMediaKey: (keyCode: Int) -> Boolean,
    private val onEnded: () -> Unit,
) : PlayerControls {
    private val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            // No automatic focus handling: we pause/resume around talk ourselves, and a nav
            // prompt must not stop the shared music on one phone only.
            false,
        )
        .setHandleAudioBecomingNoisy(false)
        .build()

    private val session: MediaSession = MediaSession.Builder(context, exo)
        .setId("motoparty")
        .setCallback(object : MediaSession.Callback {
            override fun onMediaButtonEvent(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo,
                intent: Intent,
            ): Boolean {
                @Suppress("DEPRECATION")
                val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                if (key.action != KeyEvent.ACTION_DOWN) return true
                if (key.repeatCount > 0) return true
                return onMediaKey(key.keyCode)
            }
        })
        .build()

    override var loadedId: String? = null
        private set

    init {
        exo.addListener(object : Media3Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Media3Player.STATE_ENDED) onEnded()
            }
        })
    }

    fun load(track: Track, file: File) {
        if (loadedId == track.id) return
        val item = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(Uri.fromFile(file))
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album).build(),
            )
            .build()
        exo.playWhenReady = false
        exo.setMediaItem(item)
        exo.prepare()
        loadedId = track.id
    }

    override fun seekTo(positionMs: Long) = exo.seekTo(positionMs.coerceAtLeast(0))
    override fun play() {
        exo.playWhenReady = true
    }

    override fun pause() {
        exo.playWhenReady = false
    }

    fun stop() {
        exo.stop()
        exo.clearMediaItems()
        loadedId = null
    }

    override val isPlaying: Boolean get() = exo.playWhenReady
    override val positionMs: Long get() = exo.currentPosition
    override val isReady: Boolean get() = exo.playbackState == Media3Player.STATE_READY

    /** For the sync trace: is sound really coming out (not just requested), and the ExoPlayer state. */
    override val traceInfo: String
        get() = ", playing ${exo.isPlaying}, whenReady ${exo.playWhenReady}, state ${exo.playbackState}"

    /** Playback speed (pitch preserved); used to nudge the timeline instead of seeking. */
    override var speed: Float
        get() = exo.playbackParameters.speed
        set(v) {
            exo.setPlaybackSpeed(v)
        }

    var volume: Float
        get() = exo.volume
        set(v) {
            exo.volume = v
        }

    fun release() {
        session.release()
        exo.release()
    }
}
