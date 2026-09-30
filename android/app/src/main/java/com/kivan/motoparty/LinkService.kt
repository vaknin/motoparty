package com.kivan.motoparty

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaStyleNotificationHelper
import com.kivan.motoparty.music.MediaControls
import com.kivan.motoparty.overlay.OverlayService
import com.kivan.motoparty.trigger.TriggerKind
import com.kivan.motoparty.trigger.TriggerSource
import com.kivan.motoparty.trigger.Triggers
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the whole host (types microphone|mediaPlayback|connectedDevice).
 * It keeps the CPU and Wi-Fi awake so the control/voice sockets answer with the screen off.
 */
class LinkService : LifecycleService() {
    private var host: LinkHost? = null
    /** Host scope: a failing handler is logged instead of taking the whole service down. */
    private val hostScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e("LinkService", "uncaught in host", e); Hub.log("error: $e") },
    )
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    /** Everything that re-posts the notification; cancelled first in [onDestroy]. */
    private val collectors = mutableListOf<Job>()

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        acquireLocks()
        host = LinkHost(this, hostScope).also { it.start() }
        // The same three things the notification's own actions do, pressed in the system's media
        // controls (shade, lock screen), where Android 13+ draws the session's buttons instead.
        MediaControls.onButton = { button ->
            when (button) {
                MediaControls.Button.TALK, MediaControls.Button.END_TALK -> onTalkPressed()
                MediaControls.Button.STOP -> {
                    stopReason = "media controls Stop"
                    stopSelf()
                }
                MediaControls.Button.SHOW_BUTTONS -> showOverlay()
            }
        }
        collectors += lifecycleScope.launch {
            Hub.status.distinctUntilChangedBy { listOf(it.clientName, it.talkOpen, it.nowPlaying?.title, it.micOff) }.collect {
                repost(it)
            }
        }
        // The overlay follows the setting for as long as the *service* lives: the activity may be
        // long gone (that is exactly when the rider cannot get rid of the buttons), so turning the
        // setting off — from the app, or by dropping the buttons on the X — has to be seen here.
        // A StateFlow replays its current value, so this also does the initial start.
        collectors += lifecycleScope.launch {
            MotopartyApp.instance.settings.flow.map { it.overlayEnabled }.distinctUntilChanged().collect {
                maybeStartOverlay(this@LinkService)
                repost(Hub.status.value) // the "Show buttons" action appears / disappears with it
            }
        }
    }

    /** Why the service is going away, for the "host stopping" log line; unset = the system. */
    private var stopReason: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Re-claim the service types: RECORD_AUDIO may have been granted since onCreate.
        if (intent?.action == null) startInForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                stopReason = "notification Stop"
                stopSelf()
            }
            ACTION_TALK -> onTalkPressed()
            ACTION_SHOW_OVERLAY -> showOverlay()
        }
        return START_STICKY
    }

    private fun onTalkPressed() {
        // A press on our notification is a while-in-use exemption: the way to get the
        // microphone type back after a restart in the background refused it.
        if (!Hub.micFgsType) startInForeground()
        Triggers.fire(TriggerKind.TALK, TriggerSource.UI)
    }

    /**
     * The way back from a drag onto the X. Only the setting is written; the collector in
     * onCreate starts the overlay and re-posts this notification.
     */
    private fun showOverlay() = MotopartyApp.instance.settings.update { it.copy(overlayEnabled = true) }

    override fun onDestroy() {
        // First: host.stop() resets Hub.status, and a collector still alive would run inline
        // (Main.immediate) and post "Waiting for passenger" after the system took the foreground
        // notification away — a leftover whose Talk button restarts the host.
        collectors.forEach { it.cancel() }
        collectors.clear()
        MediaControls.onButton = {}
        // The `bye` this queues is written by ControlServer's own drain scope, so it still goes
        // out after hostScope is cancelled below, and Main does not wait for it.
        host?.stop(stopReason ?: appStopReason ?: "service destroyed by the system")
        appStopReason = null
        host = null
        hostScope.cancel()
        stopService(Intent(this, OverlayService::class.java))
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
        // Last, and belt and braces: nothing of ours may stay in the shade once the host is gone.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    /** Android 15+: a foreground-service type ran out of time; the system stops us right after. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopReason = "foreground service timeout (type $fgsType)"
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Hub.log("app swiped away from recents (host keeps running)")
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        // Same id as ever, so the rider's settings for it survive; creating it again with a new
        // name is how a channel is renamed (it was "Link").
        nm.createNotificationChannel(NotificationChannel(CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW))
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        // The microphone type may only be claimed once RECORD_AUDIO is granted, and only while
        // the app is in the foreground (e.g. not on a sticky restart): run without it until the
        // activity is opened or Talk is pressed.
        val wantMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val wasOff = Hub.status.value.micOff
        val mic = wantMic && foreground(types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, micOff = false)
        if (!mic) foreground(types, micOff = true)
        Hub.micFgsType = mic
        Hub.status.update { it.copy(micOff = !mic) }
        if (!mic && !wasOff) {
            Hub.log("microphone off: " + if (wantMic) "service type refused, open the app or press Talk" else "RECORD_AUDIO not granted")
        } else if (mic && wasOff) {
            Hub.log("microphone restored")
        }
    }

    /**
     * One `startForeground` attempt; false when the system refused it. Both refusals are caught:
     * `SecurityException` (a type's precondition is not met) and `IllegalStateException`, which
     * `ForegroundServiceStartNotAllowedException` is. The second thrown out of `onCreate` on a
     * restart in the background would crash the process, and the system would restart it again.
     */
    private fun foreground(types: Int, micOff: Boolean): Boolean = try {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(Hub.status.value.copy(micOff = micOff)), types)
        true
    } catch (e: SecurityException) {
        Log.w("LinkService", "startForeground(types $types) refused: $e")
        false
    } catch (e: IllegalStateException) {
        Log.w("LinkService", "startForeground(types $types) not allowed: $e")
        false
    }

    private fun repost(s: LinkStatus) {
        if (host == null) return // stopping or stopped: see onDestroy
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s))
    }

    /**
     * The one foreground notification. While a track is loaded (and the microphone is fine) it is
     * a media notification on the player's session: the shade and the lock screen show the cover,
     * the progress and previous / play / next, with Talk and Stop next to them. Otherwise it is the
     * plain one — also while the microphone is off, because the media controls would hide that text.
     */
    @OptIn(UnstableApi::class)
    private fun notification(s: LinkStatus): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(a: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, LinkService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (s.micOff) MIC_OFF_TEXT else buildString {
            append(s.clientName?.let { "Passenger connected: $it" } ?: "Waiting for passenger")
            if (s.talkOpen) append(" · TALKING")
            s.nowPlaying?.let { append(" · ${it.title}") }
        }
        // Talk and Stop always; "Show buttons" in between while the floating button is hidden.
        // (There is no "Command" action any more: commands are spoken inside a talk.)
        val buttonsHidden = !MotopartyApp.instance.settings.value.overlayEnabled &&
            AndroidSettings.canDrawOverlays(this)
        val middle: NotificationCompat.Builder.() -> Unit = {
            if (buttonsHidden) addAction(R.drawable.ic_action_show_buttons, "Show buttons", action(ACTION_SHOW_OVERLAY, 4))
        }
        // The media controls' buttons say the same as the actions below (Android 13+ draws those
        // from the session, older versions draw the actions).
        MediaControls.show(talkOpen = s.talkOpen, overlayHidden = buttonsHidden)
        val session = MediaControls.session.takeIf { NotificationKind.of(s.micOff, s.nowPlaying != null) == NotificationKind.MEDIA }
        val style: NotificationCompat.Builder.() -> Unit = {
            if (session != null) setStyle(MediaStyleNotificationHelper.MediaStyle(session).setShowActionsInCompactView(0))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_motoparty)
            .setColor(getColor(R.color.brand_talk))
            .setContentTitle("Motoparty")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(
                if (s.talkOpen) R.drawable.ic_action_end_talk else R.drawable.ic_action_talk,
                if (s.talkOpen) "End talk" else "Talk",
                action(ACTION_TALK, 1),
            )
            .apply(middle)
            .addAction(R.drawable.ic_action_stop, "Stop", action(ACTION_STOP, 3))
            .apply(style)
            .build()
    }

    /** Which of the two looks the foreground notification has; pure, for the test. */
    enum class NotificationKind {
        PLAIN, MEDIA;

        companion object {
            fun of(micOff: Boolean, trackLoaded: Boolean): NotificationKind =
                if (trackLoaded && !micOff) MEDIA else PLAIN
        }
    }

    @SuppressLint("WakelockTimeout") // Held for exactly as long as the link runs.
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "motoparty:link").apply { acquire() }
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "motoparty:link").apply { acquire() }
    }

    companion object {
        private const val CHANNEL = "link"
        /** What Settings → Notifications shows for it. */
        const val CHANNEL_NAME = "Ride status"
        private const val NOTIFICATION_ID = 1
        /** Shown instead of the link line while the service has no microphone type. */
        const val MIC_OFF_TEXT = "Microphone off – tap to restore"
        const val ACTION_STOP = "com.kivan.motoparty.STOP"
        const val ACTION_TALK = "com.kivan.motoparty.TALK"
        const val ACTION_SHOW_OVERLAY = "com.kivan.motoparty.SHOW_OVERLAY"
        /** Set by [stop] just before stopService, so onDestroy can tell the app's button apart. */
        @Volatile private var appStopReason: String? = null

        fun start(context: Context) {
            appStopReason = null
            context.startForegroundService(Intent(context, LinkService::class.java))
        }

        /** The app's Stop button. */
        fun stop(context: Context) {
            appStopReason = "Stop button in the app"
            context.stopService(Intent(context, LinkService::class.java))
        }

        fun maybeStartOverlay(context: Context) {
            val enabled = MotopartyApp.instance.settings.value.overlayEnabled
            if (enabled && AndroidSettings.canDrawOverlays(context)) {
                context.startService(Intent(context, OverlayService::class.java))
            } else {
                context.stopService(Intent(context, OverlayService::class.java))
            }
        }
    }
}
