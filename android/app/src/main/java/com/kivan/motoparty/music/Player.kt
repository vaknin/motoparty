package com.kivan.motoparty.music

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.kivan.motoparty.R
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

/** What [MusicController] drives: [PlayerControls] plus loading, stopping and ducking. */
interface LocalPlayer : PlayerControls {
    fun load(track: Track, file: File)
    fun stop()
    var volume: Float

    /**
     * Gapless (PROTOCOL.md "Music flow" step 6): put [track] behind the loaded one, so the player
     * goes on to it by itself, without a gap, and then reports it through [onAdvanced]. Replaces
     * a track queued earlier. The defaults are a player that cannot: nothing is ever queued.
     */
    fun queueNext(track: Track, file: File): Boolean = false

    /** Take back what [queueNext] queued, if it has not started. */
    fun clearNext() = Unit

    /** The loaded track's real length once the player knows it, else null. */
    val durationMs: Long? get() = null

    /**
     * Called with the id when the player has gone on to the queued track by itself ([loadedId]
     * is already that id). Set by [MusicController].
     */
    var onAdvanced: ((id: String) -> Unit)?
        get() = null
        set(_) = Unit
}

/** A transport command from an outside controller, in [com.kivan.motoparty.core.ControlAction] terms. */
enum class RemoteAction { PAUSE, RESUME, NEXT, PREVIOUS }

/**
 * The media session as the rest of the app sees it: the foreground notification is a media
 * notification built on [session] (`LinkService`), and the system's media controls (shade, lock
 * screen) carry up to two buttons of ours next to previous / play / next. Main thread only.
 *
 * The buttons are session custom commands, because on Android 13+ the media controls ignore the
 * notification's own actions and draw what the session's playback state offers.
 */
object MediaControls {
    enum class Button(val action: String, val label: String) {
        TALK("com.kivan.motoparty.session.TALK", "Talk"),
        END_TALK("com.kivan.motoparty.session.END_TALK", "End talk"),
        STOP("com.kivan.motoparty.session.STOP", "Stop"),
        SHOW_BUTTONS("com.kivan.motoparty.session.SHOW_BUTTONS", "Show buttons"),
    }

    /** The running host's session, or null while no [Player] exists. */
    var session: MediaSession? = null
        internal set

    /** A press on one of [buttons] in the system's media controls. Set by the service. */
    var onButton: (Button) -> Unit = {}

    /** What the media controls offer right now; [Player] mirrors it into the session. */
    var buttons: List<Button> = buttonsFor(talkOpen = false, overlayHidden = false)
        private set
    internal var onButtonsChanged: ((List<Button>) -> Unit)? = null

    /**
     * Two slots next to the transport. The first is always the talk toggle, worded like the
     * notification action. The second is Stop — unless the floating button is hidden, when the way
     * to get it back matters more on a ride than switching the host off (that is still in the app).
     */
    fun buttonsFor(talkOpen: Boolean, overlayHidden: Boolean): List<Button> = listOf(
        if (talkOpen) Button.END_TALK else Button.TALK,
        if (overlayHidden) Button.SHOW_BUTTONS else Button.STOP,
    )

    fun show(talkOpen: Boolean, overlayHidden: Boolean) {
        val next = buttonsFor(talkOpen, overlayHidden)
        if (next == buttons) return
        buttons = next
        onButtonsChanged?.invoke(next)
    }
}

/**
 * ExoPlayer for local cached files, plus the MediaSession that makes us the target of headset
 * buttons (AirPods presses, BLE remotes) and of outside controllers (KDE Connect, the lock screen,
 * a watch). Main thread only. Timing decisions live in [SyncController]; this class only does what
 * it is told.
 */
@OptIn(UnstableApi::class) // the session's buttons and the notification controller are media3 "unstable" API
class Player(
    private val context: Context,
    /** A headset/remote key press (KeyEvent keycode). Return true if consumed. */
    private val onMediaKey: (keyCode: Int) -> Boolean,
    /** A transport command from an outside controller: must go through the music authority. */
    private val onRemote: (RemoteAction) -> Unit,
    private val onEnded: () -> Unit,
    /** ExoPlayer gave up on the loaded track; it is unloaded here. For [MusicController.onPlayerError]. */
    private val onError: (message: String) -> Unit = {},
    /** The output the music was on went away (earbuds dropped). For [MusicController.onBecomingNoisy]. */
    private val onNoisy: () -> Unit = {},
) : LocalPlayer {
    private val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            // No automatic focus handling: we pause/resume around talk ourselves, and a nav
            // prompt must not stop the shared music on one phone only.
            false,
        )
        // ExoPlayer's own handling would pause this phone only; see [noisy].
        .setHandleAudioBecomingNoisy(false)
        .build()

    /**
     * The earbuds dropped and Android is about to move the music to the speaker: the shared music
     * pauses instead, on both phones, so it goes through [onNoisy] and not the ExoPlayer.
     */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            Log.i(TAG, "audio becoming noisy")
            onNoisy()
        }
    }

    private val session: MediaSession = MediaSession.Builder(context, SessionPlayer(exo))
        .setId("motoparty")
        .apply {
            // A tap on the media controls opens the app, like a tap on the notification did.
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                setSessionActivity(PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE))
            }
        }
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
            ): ListenableFuture<MediaSession.ConnectionResult> {
                // The full default sets for everyone (what the one-argument builder gave before
                // media3 deprecated it; the two-argument one would cut untrusted controllers down).
                val result = MediaSession.ConnectionResult.AcceptedResultBuilder()
                    .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS)
                    .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                // Our own buttons (Talk, Stop) are for the system's media controls only, which
                // media3 reaches through [notificationController]; KDE Connect and the like keep
                // to the transport.
                if (session.isMediaNotificationController(controller)) {
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    MediaControls.Button.entries.forEach { commands.add(SessionCommand(it.action, Bundle.EMPTY)) }
                    result.setAvailableSessionCommands(commands.build())
                    result.setMediaButtonPreferences(commandButtons(MediaControls.buttons))
                }
                return Futures.immediateFuture(result.build())
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> {
                val button = MediaControls.Button.entries.firstOrNull { it.action == customCommand.customAction }
                    ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                Log.i(TAG, "media controls: ${button.label}")
                MediaControls.onButton(button)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

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

    /**
     * media3 only puts custom buttons into the platform session's playback state — which is what
     * the system's media controls draw — for the "media notification controller". A
     * `MediaSessionService` connects one by itself; for a standalone session like ours the app
     * connects it (the documented way: [MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG]).
     * It sends nothing; it only has to be connected.
     */
    @OptIn(UnstableApi::class)
    private val notificationController: ListenableFuture<MediaController> =
        MediaController.Builder(context, session.token)
            .setConnectionHints(Bundle().apply { putBoolean(MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, true) })
            .buildAsync()

    override var loadedId: String? = null
        private set
    /** The track [queueNext] put behind the loaded one. */
    private var queuedId: String? = null
    override var onAdvanced: ((id: String) -> Unit)? = null

    init {
        exo.addListener(object : Media3Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Media3Player.STATE_ENDED) onEnded()
            }

            /**
             * ExoPlayer is IDLE now and stays so until the next prepare(): forget the loaded
             * track so [load] really loads again, and let the controller decide what to play.
             */
            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "player error ${error.errorCodeName} on $loadedId at ${exo.currentPosition} ms", error)
                loadedId = null
                queuedId = null
                onError("${error.errorCodeName}: ${error.message}")
            }

            /**
             * The loaded track ran out and the queued one took over without a gap. The played
             * item is dropped, so the loaded track is always item 0 (that is no transition of its
             * own: the current item stays). Anything else — [load]'s setMediaItem, a removal —
             * is not a track change to report.
             */
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason != Media3Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || mediaItem == null) return
                val id = mediaItem.mediaId
                Log.i(TAG, "gapless: $loadedId -> $id")
                loadedId = id
                queuedId = null
                val played = exo.currentMediaItemIndex
                if (played > 0) exo.removeMediaItems(0, played)
                onAdvanced?.invoke(id)
            }
        })
        ContextCompat.registerReceiver(
            context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        session.setMediaButtonPreferences(commandButtons(MediaControls.buttons))
        MediaControls.onButtonsChanged = { session.setMediaButtonPreferences(commandButtons(it)) }
        MediaControls.session = session
    }

    /** [MediaControls.buttons] as the session wants them: after the transport, in order. */
    @OptIn(UnstableApi::class)
    private fun commandButtons(buttons: List<MediaControls.Button>): ImmutableList<CommandButton> =
        ImmutableList.copyOf(
            buttons.map { b ->
                CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                    .setCustomIconResId(
                        when (b) {
                            MediaControls.Button.TALK -> R.drawable.ic_action_talk
                            MediaControls.Button.END_TALK -> R.drawable.ic_action_end_talk
                            MediaControls.Button.STOP -> R.drawable.ic_action_stop
                            MediaControls.Button.SHOW_BUTTONS -> R.drawable.ic_action_show_buttons
                        },
                    )
                    .setDisplayName(b.label)
                    .setSessionCommand(SessionCommand(b.action, Bundle.EMPTY))
                    .setSlots(CommandButton.SLOT_OVERFLOW)
                    .build()
            },
        )

    private fun item(track: Track, file: File) = MediaItem.Builder()
        .setMediaId(track.id)
        .setUri(Uri.fromFile(file))
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album)
                // The cover for the shade and the lock screen: the session loads and caches the
                // bitmap itself, off Main, and simply shows none when the URL cannot be fetched.
                .setArtworkUri(track.art?.let(Uri::parse))
                .build(),
        )
        .build()

    override fun load(track: Track, file: File) {
        if (loadedId == track.id) return
        exo.playWhenReady = false
        exo.setMediaItem(item(track, file))
        exo.prepare()
        loadedId = track.id
        queuedId = null
    }

    override fun queueNext(track: Track, file: File): Boolean {
        if (loadedId == null) return false
        if (queuedId == track.id) return true
        clearNext()
        exo.addMediaItem(item(track, file))
        queuedId = track.id
        return true
    }

    override fun clearNext() {
        val from = exo.currentMediaItemIndex + 1
        if (exo.mediaItemCount > from) exo.removeMediaItems(from, exo.mediaItemCount)
        queuedId = null
    }

    override val durationMs: Long? get() = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 }

    override fun seekTo(positionMs: Long) = exo.seekTo(positionMs.coerceAtLeast(0))
    override fun play() {
        exo.playWhenReady = true
    }

    override fun pause() {
        exo.playWhenReady = false
    }

    override fun stop() {
        exo.stop()
        exo.clearMediaItems()
        loadedId = null
        queuedId = null
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

    override var volume: Float
        get() = exo.volume
        set(v) {
            exo.volume = v
        }

    fun release() {
        context.unregisterReceiver(noisy)
        if (MediaControls.session === session) {
            MediaControls.session = null
            MediaControls.onButtonsChanged = null
        }
        MediaController.releaseFuture(notificationController)
        session.release()
        exo.release()
    }

    /**
     * What the session shows outside controllers. Driving the ExoPlayer directly would pause one
     * phone behind [MusicController]'s back (the passenger's keeps playing, the anchor still says
     * playing), so play/pause/next/previous become [RemoteAction]s and everything else that moves
     * the timeline (seek, speed, the playlist) is not offered. Next is offered although the
     * ExoPlayer holds the current item and at most the gapless next one: the queue is [MusicController]'s.
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
        const val TAG = "Player"
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
