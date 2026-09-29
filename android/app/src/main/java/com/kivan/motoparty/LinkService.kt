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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.kivan.motoparty.overlay.OverlayService
import com.kivan.motoparty.trigger.TriggerKind
import com.kivan.motoparty.trigger.TriggerSource
import com.kivan.motoparty.trigger.Triggers
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
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

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        acquireLocks()
        host = LinkHost(this, hostScope).also { it.start() }
        lifecycleScope.launch {
            Hub.status.distinctUntilChangedBy { Triple(it.clientName, it.talkOpen, it.nowPlaying?.title) }.collect {
                repost(it)
            }
        }
        // The overlay follows the setting for as long as the *service* lives: the activity may be
        // long gone (that is exactly when the rider cannot get rid of the buttons), so turning the
        // setting off — from the app, or by dropping the buttons on the X — has to be seen here.
        // A StateFlow replays its current value, so this also does the initial start.
        lifecycleScope.launch {
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
            ACTION_TALK -> Triggers.fire(TriggerKind.TALK, TriggerSource.UI)
            // The way back from a drag onto the X. Only the setting is written; the collector in
            // onCreate starts the overlay and re-posts this notification.
            ACTION_SHOW_OVERLAY -> MotopartyApp.instance.settings.update { it.copy(overlayEnabled = true) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        host?.stop(stopReason ?: appStopReason ?: "service destroyed by the system")
        appStopReason = null
        host = null
        hostScope.cancel()
        stopService(Intent(this, OverlayService::class.java))
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
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
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Link", NotificationManager.IMPORTANCE_LOW))
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        // The microphone type may only be claimed once RECORD_AUDIO is granted.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(Hub.status.value), types)
            Hub.micFgsType = types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0
        } catch (e: SecurityException) {
            // Microphone may only be claimed while the app is in the foreground (e.g. not on a
            // sticky restart); run without it until the activity is opened again.
            Log.w("LinkService", "startForeground without microphone: ${e.message}")
            Hub.micFgsType = false
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(Hub.status.value),
                types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE.inv(),
            )
        }
    }

    private fun repost(s: LinkStatus) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s))
    }

    private fun notification(s: LinkStatus): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(a: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, LinkService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = buildString {
            append(s.clientName?.let { "Linked to $it" } ?: "Waiting for passenger")
            if (s.talkOpen) append(" · TALKING")
            s.nowPlaying?.let { append(" · ${it.title}") }
        }
        // Talk and Stop always; "Show buttons" in between while the floating button is hidden.
        // (There is no "Command" action any more: commands are spoken inside a talk.)
        val buttonsHidden = !MotopartyApp.instance.settings.value.overlayEnabled &&
            AndroidSettings.canDrawOverlays(this)
        val middle: NotificationCompat.Builder.() -> Unit = {
            if (buttonsHidden) addAction(0, "Show buttons", action(ACTION_SHOW_OVERLAY, 4))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Motoparty")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, if (s.talkOpen) "End talk" else "Talk", action(ACTION_TALK, 1))
            .apply(middle)
            .addAction(0, "Stop", action(ACTION_STOP, 3))
            .build()
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
        private const val NOTIFICATION_ID = 1
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
