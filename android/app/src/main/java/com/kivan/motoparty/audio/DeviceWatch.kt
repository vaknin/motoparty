package com.kivan.motoparty.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Build
import android.os.HandlerThread
import com.kivan.motoparty.music.MediaRoute
import com.kivan.motoparty.music.OutputRoute

/**
 * Asks the framework what audio devices exist and watches them come and go ([DeviceRoster] holds
 * the rule and the wording). Stage A of the wired-mic plan (2026-09-20): the bench cannot answer
 * "does the boom mic appear at all, and as what" from a log that never lists a device.
 *
 * Everything runs on one daemon thread, like [ScoWatch] and for the same reason: `getDevices()` and
 * the registration are binder calls into the audio service, which our own route switch holds locks
 * in, and neither Main nor the audio thread may wait on that. Registering the callback makes the
 * framework report the current devices straight away, so there is no separate first read.
 */
class DeviceWatch(
    private val context: Context,
    /** One line per change, to `Hub.log`: logcat for the bench and the in-app list for the rider. */
    private val log: (String) -> Unit,
    /**
     * The output music plays on, whenever it changes ([MediaRoute.pick]); from this class's thread.
     * The latency trim follows it.
     */
    private val onMediaRoute: (OutputRoute) -> Unit = {},
    /**
     * The whole roster, whenever it changed; from this class's thread. A host-mic talk ends when
     * its receiver leaves it (PROTOCOL.md "Host-mic talk").
     */
    private val onDevices: (List<DeviceRoster.Dev>) -> Unit = {},
) {
    /** Every device as of the last report, or null before the first ([DeviceRoster.current]). */
    val devices: List<DeviceRoster.Dev>? get() = roster.current

    /**
     * The short roster for the UI, or null before the first report. Volatile and published rather
     * than queried, exactly like [AudioRouter.selectedDevice]: the status refresh runs on Main once
     * a second and must not make a binder call to do it.
     */
    @Volatile
    var summary: String? = null
        private set

    private val roster = DeviceRoster()
    /** The last route reported to [onMediaRoute]; this class's thread only. */
    private var mediaRoute: OutputRoute? = null
    private var thread: HandlerThread? = null
    private var callback: AudioDeviceCallback? = null

    fun start() {
        val t = HandlerThread("motoparty-devices").apply { isDaemon = true }
        t.start()
        thread = t
        val handler = Handler(t.looper)
        handler.post {
            val am = context.getSystemService(AudioManager::class.java) ?: return@post
            val cb = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh(am)
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh(am)
            }
            runCatching { am.registerAudioDeviceCallback(cb, handler) }
                .onSuccess { callback = cb }
                .onFailure { log("audio device callback refused: $it") }
            // Belt and braces: if the registration was refused, at least say what is there now.
            refresh(am)
        }
    }

    fun stop() {
        callback?.let { cb ->
            runCatching {
                context.getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(cb)
            }
        }
        callback = null
        thread?.quitSafely()
        thread = null
    }

    /**
     * The whole list, every time, and [DeviceRoster] works out whether anything actually moved: the
     * callback's own added/removed arrays are only the delta, and a delta cannot be checked against
     * the state we hold.
     */
    private fun refresh(am: AudioManager) {
        val devices = runCatching { am.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS) }
            .onFailure { log("audio devices unreadable: $it") }
            .getOrNull() ?: return
        val list = devices.map {
            DeviceRoster.Dev(it.id, it.type, it.productName?.toString().orEmpty(), it.isSource, it.channelCounts.toList())
        }
        val changed = roster.update(list)
        for (line in changed) log(line)
        summary = roster.summary()
        if (changed.isNotEmpty()) roster.current?.let(onDevices)

        fun out(d: AudioDeviceInfo) = MediaRoute.Out(d.type, d.address.orEmpty(), d.productName?.toString().orEmpty())
        val forMedia = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            runCatching { am.getAudioDevicesForAttributes(media).map(::out) }.getOrNull()
        } else {
            null
        }
        val route = MediaRoute.pick(forMedia, devices.filter { it.isSink }.map(::out))
        if (route != mediaRoute) {
            mediaRoute = route
            onMediaRoute(route)
        }
    }
}
