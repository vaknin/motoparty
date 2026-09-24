package com.kivan.motoparty.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

/** A transport command from an outside controller, in [com.kivan.motoparty.core.ControlAction] terms. */
enum class RemoteAction { PAUSE, RESUME, NEXT, PREVIOUS }

/**
 * ExoPlayer for local cached files, plus the MediaSession that makes us the target of headset
 * buttons (AirPods presses, BLE remotes) and of outside controllers (KDE Connect, the lock screen,
 * a watch). Main thread only. Timing decisions live in [SyncController]; this class only does what
 * it is told.
 */
class Player(
    context: Context,
    /** A headset/remote key press (KeyEvent keycode). Return true if consumed. */
    private val onMediaKey: (keyCode: Int) -> Boolean,
    /** A transport command from an outside controller: must go through the music authority. */
    private val onRemote: (RemoteAction) -> Unit,
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

    private val session: MediaSession = MediaSession.Builder(context, SessionPlayer(exo))
        .setId("motoparty")
        .setCallback(object : MediaSession.Callback {
            /**
             * Every controller gets the transport commands, trusted or not. media3's default gives
             * an untrusted one read access only, and KDE Connect is untrusted here: package
             * visibility hides it from us ("Package org.kde.kdeconnect_tp doesn't exist"), so the
             * notification-listener check fails and its pause was silently dropped. What any
             * controller can do is bounded by [SessionPlayer]: play/pause/next/previous.
             */
            @OptIn(UnstableApi::class)
            override fun onConnectAsync(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): ListenableFuture<MediaSession.ConnectionResult> =
                Futures.immediateFuture(MediaSession.ConnectionResult.AcceptedResultBuilder(session).build())

            @OptIn(UnstableApi::class)
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

    /**
     * What the session shows outside controllers. Driving the ExoPlayer directly would pause one
     * phone behind [MusicController]'s back (the passenger's keeps playing, the anchor still says
     * playing), so play/pause/next/previous become [RemoteAction]s and everything else that moves
     * the timeline (seek, speed, the playlist) is not offered. Next is offered although the
     * ExoPlayer only ever holds one item: the queue is [MusicController]'s.
     */
    @OptIn(UnstableApi::class)
    private inner class SessionPlayer(player: ExoPlayer) : ForwardingPlayer(player) {
        override fun play() = onRemote(RemoteAction.RESUME)
        override fun pause() = onRemote(RemoteAction.PAUSE)
        override fun setPlayWhenReady(playWhenReady: Boolean) =
            onRemote(if (playWhenReady) RemoteAction.RESUME else RemoteAction.PAUSE)
        // A remote Stop must not throw away the ride's queue.
        override fun stop() = onRemote(RemoteAction.PAUSE)
        override fun seekToNext() = onRemote(RemoteAction.NEXT)
        override fun seekToNextMediaItem() = onRemote(RemoteAction.NEXT)
        override fun seekToPrevious() = onRemote(RemoteAction.PREVIOUS)
        override fun seekToPreviousMediaItem() = onRemote(RemoteAction.PREVIOUS)

        // The session checks every incoming command against these (media3 1.11:
        // ConnectedControllersManager.isPlayerCommandAvailable), so a hidden one is refused even
        // though onAvailableCommandsChanged still reports the ExoPlayer's own set. Do not wrap
        // the listeners to fix that: `by` delegation skips Player.Listener's Java default
        // methods, which silently cuts the session off from every playback event.
        override fun getAvailableCommands(): Media3Player.Commands = offered(super.getAvailableCommands())
        override fun isCommandAvailable(command: Int) = getAvailableCommands().contains(command)

        private fun offered(commands: Media3Player.Commands) = commands.buildUpon()
            .removeAll(*HIDDEN)
            .addAll(*TRANSPORT)
            .build()
    }

    private companion object {
        val TRANSPORT = intArrayOf(
            Media3Player.COMMAND_PLAY_PAUSE,
            Media3Player.COMMAND_STOP,
            Media3Player.COMMAND_SEEK_TO_NEXT,
            Media3Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Media3Player.COMMAND_SEEK_TO_PREVIOUS,
            Media3Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
        val HIDDEN = intArrayOf(
            Media3Player.COMMAND_PREPARE,
            Media3Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Media3Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Media3Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Media3Player.COMMAND_SEEK_BACK,
            Media3Player.COMMAND_SEEK_FORWARD,
            Media3Player.COMMAND_SET_SPEED_AND_PITCH,
            Media3Player.COMMAND_SET_SHUFFLE_MODE,
            Media3Player.COMMAND_SET_REPEAT_MODE,
            Media3Player.COMMAND_SET_MEDIA_ITEM,
            Media3Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Media3Player.COMMAND_SET_PLAYLIST_METADATA,
            Media3Player.COMMAND_SET_VOLUME,
        )
    }
}
