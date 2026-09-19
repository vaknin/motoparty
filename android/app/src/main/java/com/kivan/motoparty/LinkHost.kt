package com.kivan.motoparty

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.view.KeyEvent
import com.kivan.motoparty.audio.AudioRouter
import com.kivan.motoparty.audio.AudioThread
import com.kivan.motoparty.audio.Earcons
import com.kivan.motoparty.audio.TalkAudio
import com.kivan.motoparty.audio.VoiceEngine
import com.kivan.motoparty.core.Announce
import com.kivan.motoparty.core.Bye
import com.kivan.motoparty.core.Command
import com.kivan.motoparty.core.CommandParser
import com.kivan.motoparty.core.CommandText
import com.kivan.motoparty.core.CloseReason
import com.kivan.motoparty.core.ControlAction
import com.kivan.motoparty.core.Earcon
import com.kivan.motoparty.core.Hello
import com.kivan.motoparty.core.PROTO_VERSION
import com.kivan.motoparty.core.Message
import com.kivan.motoparty.core.MusicControl
import com.kivan.motoparty.core.MusicError
import com.kivan.motoparty.core.MusicReady
import com.kivan.motoparty.core.Role
import com.kivan.motoparty.core.State
import com.kivan.motoparty.core.TalkClose
import com.kivan.motoparty.core.TalkOpen
import com.kivan.motoparty.link.ControlServer
import com.kivan.motoparty.link.Discovery
import com.kivan.motoparty.link.TalkController
import com.kivan.motoparty.link.VoiceSocket
import com.kivan.motoparty.music.Catalog
import com.kivan.motoparty.music.MusicController
import com.kivan.motoparty.music.Player
import com.kivan.motoparty.music.SyncController
import com.kivan.motoparty.music.TrackCache
import com.kivan.motoparty.music.TrackServer
import com.kivan.motoparty.trigger.TriggerKind
import com.kivan.motoparty.trigger.TriggerSource
import com.kivan.motoparty.trigger.Triggers
import com.kivan.motoparty.voicecmd.Announcer
import com.kivan.motoparty.voicecmd.Transcriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Wires the host together and is the single place where protocol decisions are made. Every
 * method runs on the main thread ([scope] is Main); sockets and audio have their own threads and
 * hand events over through channels.
 *
 * Audio routing and the voice engine are the one thing Main never does itself: they go through
 * [audio], a single serial thread (see [AudioThread]), because the Bluetooth route change alone
 * blocks for over a second. Protocol messages are still sent from Main *before* that work is
 * queued, so the client never waits for this phone's headset.
 */
class LinkHost(private val context: Context, private val scope: CoroutineScope) {
    private val settings = MotopartyApp.instance.settings
    private val clock: () -> Long = SystemClock::elapsedRealtime
    private val deviceName: String =
        AndroidSettings.Global.getString(context.contentResolver, AndroidSettings.Global.DEVICE_NAME) ?: Build.MODEL

    private val talk: TalkController = TalkController(clock)
    private val router = AudioRouter(context)
    /** Every route change and voice start/stop, in order, off Main. Failures come back on Main. */
    private val audio = AudioThread(scope) { what, e -> Hub.log("$what failed: $e") }
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val catalog = Catalog(http)
    private val cache = TrackCache(File(context.cacheDir, "tracks"), http, { catalog.resolveAudio(it).url }, scope)
    private val player: Player = Player(context, ::onMediaKey, onEnded = { music.onTrackEnded() })
    private val sync: SyncController = SyncController(player, scope, clock) { settings.value.latencyTrimMs }
    private val control: ControlServer = ControlServer(scope, clock, ::hello, ::state)
    private val voice: VoiceEngine = VoiceEngine(
        send = { ts, p -> voiceSocket.sendAudio(ts, p) },
        clockTs = { voiceSocket.currentTs() },
        onActivity = talk::noteActivity,
    )
    /** Route + voice engine for talk, collapsed to the latest open/close (see [TalkAudio]). */
    private val talkAudio = TalkAudio(
        audio,
        enterCall = router::enterCall,
        exitCall = router::exitCall,
        voiceStart = { onFailed -> voice.start(onFailed) },
        voiceStop = voice::stop,
        voiceRunning = { voice.isRunning },
        closedEarcon = { Earcons.play(Earcons.Kind.CLOSED, call = false) },
        // From the audio thread or a voice thread: hop to Main, where talk state lives.
        onFailed = { session, what, e -> scope.launch { onMicFailed(session, "$what: ${e.message}") } },
    )
    private val voiceSocket: VoiceSocket = VoiceSocket(clientIp = { control.clientAddress }, onPacket = { voice.onPacket(it) })
    private val trackServer = TrackServer(scope, cache::cached)
    private val discovery = Discovery(context, deviceName)
    private val transcriber = Transcriber(context)
    private val announcer = Announcer(context)
    private val music: MusicController = MusicController(
        scope, cache, sync, player, clock,
        send = control::send,
        hasClient = control::hasClient,
        onChanged = ::pushState,
        onError = { announce(it, Earcon.ERROR) },
    )
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var listenJob: Job? = null
    /**
     * Bumped on every talk open, on Main. Audio work finishes asynchronously, so a failure or the
     * live earcon of an earlier talk can reach Main after the next one opened; they carry the
     * number they were started with and are dropped when it is no longer current.
     */
    private var talkSession = 0
    private var clientName: String? = null

    fun start() {
        for ((name, action) in listOf(
            "control :${ControlServer.PORT}" to control::start,
            "voice :${VoiceSocket.PORT}" to voiceSocket::start,
            "tracks :${TrackServer.PORT}" to trackServer::start,
            "nsd" to discovery::start,
        )) {
            try {
                action()
            } catch (e: Exception) {
                Hub.log("start $name failed: ${e.message}")
            }
        }
        Hub.log("host up as \"$deviceName\"")
        scope.launch { for (e in control.events) guarded("control event") { onControlEvent(e) } }
        scope.launch { Triggers.events.collect { guarded("trigger") { onTrigger(it.kind, it.source) } } }
        scope.launch { Hub.actions.collect { guarded("ui action") { onUiAction(it) } } }
        scope.launch {
            while (isActive) {
                delay(500)
                talk.tick()?.let(::applyTalk)
            }
        }
        scope.launch {
            while (isActive) {
                refreshStatus()
                delay(1000)
            }
        }
    }

    fun stop() {
        listenJob?.cancel()
        if (talk.isOpen) applyTalk(TalkController.Action.Close(Role.HOST, "link"))
        control.stop()
        voiceSocket.stop()
        trackServer.stop()
        discovery.stop()
        // Behind whatever talk teardown is still queued, then the thread retires. exitAll: a
        // cancelled recognizer's route-back arrives after shutdown and is dropped, so do it here.
        audio.post("shutdown") {
            voice.stop()
            router.exitAll()
        }
        audio.shutdown()
        player.release()
        announcer.release()
        Hub.status.update { LinkStatus(log = it.log) }
    }

    // ---- control channel ----

    private fun hello() = Hello(
        proto = PROTO_VERSION, role = Role.HOST, name = deviceName,
        voicePort = VoiceSocket.PORT, httpPort = TrackServer.PORT,
    )

    private fun state(): Message = State(
        talk = talk.isOpen,
        music = music.musicState(),
        queue = music.upcoming.map { it.toQueueItem() },
    )

    private fun pushState() {
        control.send(state())
        refreshStatus()
    }

    private fun onControlEvent(e: ControlServer.Event) {
        when (e) {
            is ControlServer.Event.ClientConnected -> {
                if (clientName != null) {
                    // A new hello replaced the previous client: its talk and readiness are void.
                    talk.onLinkLost()?.let(::applyTalk)
                    voiceSocket.forgetPeer()
                    music.onClientGone()
                }
                clientName = e.name
                Hub.log("client \"${e.name}\" connected from ${e.address.hostAddress}")
                music.onClientConnected()
            }
            is ControlServer.Event.ClientGone -> {
                Hub.log("client gone: ${e.reason}")
                clientName = null
                talk.onLinkLost()?.let(::applyTalk)
                voiceSocket.forgetPeer()
                music.onClientGone()
            }
            is ControlServer.Event.Received -> onMessage(e.message)
        }
        refreshStatus()
    }

    private fun onMessage(m: Message) {
        when (m) {
            is TalkOpen -> onClientTalkOpen()
            is TalkClose -> onClientTalkClose(m.reason)
            is MusicReady -> music.onClientReady(m.id)
            is MusicError -> music.onClientError(m.id, m.message)
            is MusicControl -> onMusicControl(m.action)
            is CommandText -> executeCommand(m.text, fromClient = true)
            is Bye -> Unit // the server closes the connection and reports ClientGone
            else -> Unit // unknown or host-to-client types: ignored per PROTOCOL.md
        }
    }

    // ---- talk ----

    /**
     * A client asked to talk. PROTOCOL.md "Talk flow" step 1: talk is not negotiable, so the only
     * answer other than `talk.open` is `talk.close{by:"host",reason:"unavailable"}` when this
     * phone cannot open its microphone. Nothing is broadcast and `state.talk` stays false; the
     * phone that asked — the client — plays the error earcon.
     */
    private fun onClientTalkOpen() {
        if (!micAvailable()) {
            control.send(TalkClose(Role.HOST, CloseReason.UNAVAILABLE))
            Hub.log("talk.open refused: microphone unavailable")
            return
        }
        talk.onClientOpenRequest()?.let(::applyTalk)
    }

    /**
     * A client `talk.close`. `reason:"unavailable"` right after our `talk.open` means *its* mic
     * failed; it is still a close request, and if the rider here was the one who triggered, this
     * phone is the one that asked, so it plays the error earcon.
     */
    private fun onClientTalkClose(reason: String) {
        val weAsked = talk.openedBy == Role.HOST
        val action = talk.onClientCloseRequest(reason) ?: return
        applyTalk(action)
        if (reason == CloseReason.UNAVAILABLE) {
            Hub.log("client microphone unavailable")
            if (weAsked) Earcons.play(Earcons.Kind.ERROR, call = false)
        }
    }

    /**
     * Our own microphone or call route failed. On Main, always: the audio thread reports it here.
     *
     * Before talk opens this is caught by [micAvailable]; afterwards the route change and the
     * capture thread are asynchronous, so the same condition can only show up late. PROTOCOL.md
     * "Talk flow" step 1 gives the reason for it — talk closes with
     * `talk.close{by:"host",reason:"unavailable"}`, which is broadcast like any close because
     * `talk.open` already went out, and the phone that asked plays the error earcon.
     */
    private fun onMicFailed(session: Int, why: String) {
        if (!talk.isOpen || session != talkSession) return
        Hub.log("microphone unavailable: $why")
        val weAsked = talk.openedBy == Role.HOST
        val action = talk.onMicFailure() ?: return
        applyTalk(action)
        if (weAsked) Earcons.play(Earcons.Kind.ERROR, call = false)
    }

    /**
     * Turn a [TalkController] decision into wire messages (on Main, first) and audio work (queued
     * on [audio], in order). Nothing here blocks Main, so the timers, the resume and the next
     * protocol decision keep running while the headset switches profile.
     */
    private fun applyTalk(action: TalkController.Action) {
        when (action) {
            is TalkController.Action.Open -> {
                control.send(TalkOpen(action.by))
                music.onTalkOpen(duck = settings.value.duckDuringTalk)
                pushState()
                val session = ++talkSession
                // Switching the headset to HFP blocks for about a second; messages went out first.
                // A failure here is this phone's "cannot open the microphone" case. enterCall
                // counts itself before it can throw, so the close that follows balances it.
                val opened = talkAudio.open(session)
                Hub.log("talk open (by ${action.by})")
                scope.launch {
                    // Give the headset a moment past the switch so the earcon is audible.
                    opened.join()
                    delay(LIVE_EARCON_DELAY_MS)
                    if (talk.isOpen && session == talkSession) {
                        audio.post("live earcon") { Earcons.play(Earcons.Kind.LIVE, call = true) }
                    }
                }
            }
            is TalkController.Action.Close -> {
                control.send(TalkClose(action.by, action.reason))
                pushState()
                // The client is told to resume at now + resumeLeadMs and is not kept waiting for
                // our headset. Our own player is a different matter: A2DP does not exist again
                // until exitCall has finished, and a play() into a route that is still being
                // rebuilt is the "known-bad start" that costs ~20 s of drift correction. So hold
                // the local player over the switch and rejoin the (unchanged) timeline after it —
                // on time if the route is back before the anchor, mid-track if it is not.
                val resuming = music.pausedForTalk
                if (resuming) sync.hold()
                music.onTalkClose(settings.value.resumeLeadMs.toLong())
                val closed = talkAudio.close()
                Hub.log("talk closed (by ${action.by}, ${action.reason})")
                if (resuming) scope.launch {
                    closed.join()
                    sync.release(cold = true)
                }
            }
        }
    }

    // ---- triggers ----

    private fun onTrigger(kind: TriggerKind, source: TriggerSource) {
        Hub.log("trigger $kind from $source")
        when (kind) {
            TriggerKind.TALK -> {
                if (!talk.isOpen && !control.hasClient()) {
                    Earcons.play(Earcons.Kind.ERROR, call = false)
                    Hub.log("talk: no client connected")
                    return
                }
                // Our own trigger with no usable mic: error earcon, and no talk.open goes out.
                if (!talk.isOpen && !micAvailable()) {
                    Earcons.play(Earcons.Kind.ERROR, call = false)
                    return
                }
                listenJob?.cancel()
                applyTalk(talk.onLocalTrigger())
            }
            TriggerKind.MUSIC -> listenForCommand()
        }
    }

    private fun onMediaKey(keyCode: Int): Boolean {
        val s = settings.value
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK ->
                if (s.headsetPlayPause == "talk") Triggers.fire(TriggerKind.TALK, TriggerSource.MEDIA_BUTTON)
                else music.togglePlayPause()
            KeyEvent.KEYCODE_MEDIA_NEXT ->
                if (s.headsetNext == "command") Triggers.fire(TriggerKind.MUSIC, TriggerSource.MEDIA_BUTTON)
                else music.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> music.previous()
            else -> return false
        }
        return true
    }

    private fun listenForCommand() {
        if (talk.isOpen) {
            Hub.log("command ignored: talk is open")
            return
        }
        if (listenJob?.isActive == true) {
            listenJob?.cancel()
            return
        }
        if (!hasMic()) {
            announce("Microphone permission missing", Earcon.ERROR)
            return
        }
        listenJob = scope.launch {
            Hub.status.update { it.copy(listening = true) }
            sync.hold()
            // Queued, never cancelled: the block always runs, so enter and exit stay paired even
            // if this job is cancelled while waiting for the route.
            val entered = audio.post("recognizer route") { router.enterCall() }
            var routeBack: Job? = null
            fun leaveCall() {
                if (routeBack == null) routeBack = audio.post("recognizer route back") { router.exitCall() }
            }
            try {
                entered.join()
                delay(SCO_SETTLE_MS)
                Earcons.play(Earcons.Kind.LISTEN, call = true)
                delay(250)
                val r = transcriber.listen(settings.value.asrLanguage)
                leaveCall()
                when (r) {
                    is Transcriber.Result.Text -> {
                        Hub.log("heard: \"${r.text}\"")
                        executeCommand(r.text)
                    }
                    is Transcriber.Result.Failed -> {
                        Hub.log("recognition failed: error ${r.error}")
                        announce("Didn't catch that", Earcon.ERROR)
                    }
                }
            } finally {
                leaveCall()
                Hub.status.update { it.copy(listening = false) }
                // In its own coroutine: this one may be cancelled, and the music must not restart
                // before the route is back (cold, for the same reason as after talk).
                val back = routeBack
                scope.launch {
                    back?.join()
                    sync.release(cold = true)
                }
            }
        }
    }

    // ---- commands ----

    /** [fromClient] distinguishes the passenger's utterance from our own rider's. */
    private fun executeCommand(text: String, fromClient: Boolean = false) {
        Hub.log("command: \"$text\"")
        when (val cmd = CommandParser.parse(text)) {
            is Command.Play -> scope.launch {
                Hub.status.update { it.copy(busy = "Searching ${cmd.kind.word} \"${cmd.query}\"") }
                try {
                    val result = catalog.search(cmd.kind, cmd.query)
                    music.setQueue(result.tracks)
                    announce("Playing ${result.label}", Earcon.OK)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Catalog.NotFound) {
                    announce("Couldn't find ${cmd.query}", Earcon.ERROR)
                } catch (e: IOException) {
                    announce("No coverage", Earcon.ERROR)
                } catch (e: Exception) {
                    Hub.log("search failed: $e")
                    announce("Search failed", Earcon.ERROR)
                } finally {
                    Hub.status.update { it.copy(busy = null) }
                }
            }
            Command.Pause -> { music.pause(); announce("Paused", Earcon.OK) }
            Command.Resume -> { music.resume(); announce("Resuming", Earcon.OK) }
            Command.Next -> { music.next(); announce(music.current?.let { "Next: ${it.title}" } ?: "End of queue", Earcon.OK) }
            Command.Previous -> { music.previous(); announce(music.current?.let { "Playing ${it.title}" } ?: "Nothing to play", Earcon.OK) }
            Command.VolumeUp -> onVolumeCommand(fromClient, AudioManager.ADJUST_RAISE)
            Command.VolumeDown -> onVolumeCommand(fromClient, AudioManager.ADJUST_LOWER)
            Command.Unknown -> announce("Didn't catch that", Earcon.ERROR)
        }
    }

    private fun onMusicControl(action: String) {
        // Values outside this set never get here: the codec drops them as malformed.
        when (action) {
            ControlAction.PAUSE -> music.pause()
            ControlAction.RESUME -> music.resume()
            ControlAction.NEXT -> music.next()
            ControlAction.PREVIOUS -> music.previous()
        }
    }

    /**
     * PROTOCOL.md "Commands", *Volume is local*: "volume up/down" changes only the phone it was
     * spoken on and is never sent. Our rider's utterance changes this phone, with an earcon and
     * no `announce` — the passenger must not be told. A volume utterance arriving from the client
     * means the client failed to handle it locally, and is answered "Didn't catch that".
     */
    private fun onVolumeCommand(fromClient: Boolean, direction: Int) {
        if (fromClient) {
            announce("Didn't catch that", Earcon.ERROR)
            return
        }
        // Two steps: one step is barely audible under a helmet.
        repeat(2) { audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) }
        Earcons.play(Earcons.Kind.OK, call = false)
        Hub.log("volume ${if (direction == AudioManager.ADJUST_RAISE) "up" else "down"} (local)")
    }

    private fun announce(text: String, earcon: String?) {
        control.send(Announce(text, earcon))
        val kind = when (earcon) {
            Earcon.OK -> Earcons.Kind.OK
            Earcon.ERROR -> Earcons.Kind.ERROR
            else -> null
        }
        announcer.announce(text, kind, settings.value.asrLanguage)
        Hub.status.update { it.copy(lastAnnounce = text) }
        Hub.log("announce: $text")
    }

    // ---- UI ----

    private fun onUiAction(a: UiAction) {
        when (a) {
            is UiAction.Search -> scope.launch {
                Hub.status.update { it.copy(busy = "Searching \"${a.query}\"") }
                val results = runCatching { catalog.searchSongs(a.query) }.getOrElse {
                    Hub.log("search failed: $it"); emptyList()
                }
                Hub.status.update { it.copy(busy = null, searchResults = results) }
            }
            is UiAction.Play -> music.setQueue(a.tracks, a.index)
            is UiAction.Command -> executeCommand(a.text)
            is UiAction.Control -> onMusicControl(a.action)
        }
    }

    private fun refreshStatus() {
        val now = clock()
        Hub.status.update {
            it.copy(
                running = true,
                nsdName = discovery.registeredName.value,
                clientName = clientName,
                clientAddress = control.clientAddress?.hostAddress,
                clientSkewMs = control.lastPingSkewMs,
                lastPingAgeMs = if (control.hasClient()) now - control.lastRxAtMs else null,
                talkOpen = talk.isOpen,
                jitterTargetMs = voice.jitterTargetMs,
                underruns = voice.underruns,
                udpIn = voiceSocket.packetsIn.get(),
                udpOut = voiceSocket.packetsOut.get(),
                audioDevice = router.selectedDevice,
                nowPlaying = music.current,
                playing = music.isPlaying,
                positionMs = sync.anchor?.expectedAt(now)?.coerceAtLeast(0) ?: 0,
                queue = music.upcoming,
                lastDriftMs = sync.lastDriftMs,
                cacheMb = cache.sizeBytes() / (1024 * 1024),
            )
        }
    }

    /** One bad event must not stop the loop that delivers the next one. */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("LinkHost", "$what failed", e)
            Hub.log("$what failed: $e")
        }
    }

    /**
     * Can this phone open its microphone right now? The "unavailable" cases of PROTOCOL.md
     * "Talk flow" step 1. Deliberately *not* a preference: there is no way to decline talk, only
     * to be unable to serve it.
     *
     * `MODE_IN_COMMUNICATION` is not treated as busy — that is the mode our own talk and our own
     * recognizer put the device in.
     */
    private fun micAvailable(): Boolean {
        if (!hasMic()) {
            Hub.log("microphone unavailable: RECORD_AUDIO not granted")
            return false
        }
        if (!Hub.micFgsType) {
            Hub.log("microphone unavailable: service has no microphone type")
            return false
        }
        val mode = audioManager.mode
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE) {
            Hub.log("microphone unavailable: phone call in progress")
            return false
        }
        return true
    }

    private fun hasMic() =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val LIVE_EARCON_DELAY_MS = 400L
        private const val SCO_SETTLE_MS = 700L
    }
}
