package com.kivan.motoparty

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.view.KeyEvent
import com.kivan.motoparty.audio.AudioModeWatch
import com.kivan.motoparty.audio.AudioRouter
import com.kivan.motoparty.audio.AudioThread
import com.kivan.motoparty.audio.DeviceWatch
import com.kivan.motoparty.audio.Earcons
import com.kivan.motoparty.audio.LarkEngine
import com.kivan.motoparty.audio.LarkPipeline
import com.kivan.motoparty.audio.LiveCue
import com.kivan.motoparty.audio.MediaCue
import com.kivan.motoparty.audio.PcmDump
import com.kivan.motoparty.audio.ScoWatch
import com.kivan.motoparty.audio.TalkAudio
import com.kivan.motoparty.audio.TalkMic
import com.kivan.motoparty.audio.UsbStereoProbe
import com.kivan.motoparty.audio.VoiceEngine
import com.kivan.motoparty.core.Announce
import com.kivan.motoparty.core.Bye
import com.kivan.motoparty.core.Command
import com.kivan.motoparty.core.CommandEffect
import com.kivan.motoparty.core.CommandParser
import com.kivan.motoparty.core.CommandText
import com.kivan.motoparty.core.Codec
import com.kivan.motoparty.core.EditOp
import com.kivan.motoparty.core.EnqueueMode
import com.kivan.motoparty.core.TouchPlay
import com.kivan.motoparty.core.FirstPhraseGate
import com.kivan.motoparty.core.CloseReason
import com.kivan.motoparty.core.ControlAction
import com.kivan.motoparty.core.Earcon
import com.kivan.motoparty.core.Hello
import com.kivan.motoparty.core.Interpretation
import com.kivan.motoparty.core.MainLag
import com.kivan.motoparty.core.Mic
import com.kivan.motoparty.core.PROTO_VERSION
import com.kivan.motoparty.core.Message
import com.kivan.motoparty.core.MusicBrowse
import com.kivan.motoparty.core.MusicControl
import com.kivan.motoparty.core.MusicEdit
import com.kivan.motoparty.core.MusicEnqueue
import com.kivan.motoparty.core.MusicResults
import com.kivan.motoparty.core.MusicSearch
import com.kivan.motoparty.core.ResultItem
import com.kivan.motoparty.core.MusicError
import com.kivan.motoparty.core.MusicReady
import com.kivan.motoparty.core.QueueItem
import com.kivan.motoparty.core.Role
import com.kivan.motoparty.core.SearchKind
import com.kivan.motoparty.core.State
import com.kivan.motoparty.core.TalkClose
import com.kivan.motoparty.core.TalkOpen
import com.kivan.motoparty.core.wireType
import com.kivan.motoparty.link.ControlServer
import com.kivan.motoparty.link.Discovery
import com.kivan.motoparty.link.StateFit
import com.kivan.motoparty.link.TalkController
import com.kivan.motoparty.link.onClientJoined
import com.kivan.motoparty.link.VoiceSocket
import com.kivan.motoparty.music.Catalog
import com.kivan.motoparty.music.CollectionDownloads
import com.kivan.motoparty.music.CollectionItem
import com.kivan.motoparty.music.DownloadPriority
import com.kivan.motoparty.music.isValidTrackId
import com.kivan.motoparty.music.Track
import com.kivan.motoparty.music.MusicController
import com.kivan.motoparty.music.NetworkWatch
import com.kivan.motoparty.music.Player
import com.kivan.motoparty.music.RemoteAction
import com.kivan.motoparty.music.SyncController
import com.kivan.motoparty.music.remuxWebmToMp4
import com.kivan.motoparty.music.TrackCache
import com.kivan.motoparty.music.OutputRoute
import com.kivan.motoparty.music.TrackCaches
import com.kivan.motoparty.music.VoiceQueue
import com.kivan.motoparty.music.TrackServer
import com.kivan.motoparty.trigger.TriggerKind
import com.kivan.motoparty.trigger.TriggerSource
import com.kivan.motoparty.trigger.Triggers
import com.kivan.motoparty.voicecmd.Announcer
import com.kivan.motoparty.voicecmd.CloudGemini
import com.kivan.motoparty.voicecmd.FirstAnswer
import com.kivan.motoparty.voicecmd.RateGuard
import com.kivan.motoparty.voicecmd.Interpreter
import com.kivan.motoparty.voicecmd.TalkRecognizer
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
    private val history = MotopartyApp.instance.history
    private val clock: () -> Long = SystemClock::elapsedRealtime
    private val deviceName: String =
        AndroidSettings.Global.getString(context.contentResolver, AndroidSettings.Global.DEVICE_NAME) ?: Build.MODEL

    private val talk: TalkController = TalkController()
    // The route teardown invalidates the cached link state at once, so a talk opened in the window
    // before the framework reports the teardown cannot see a stale "connected" (F8). The same two
    // edges are what a media-route sound waits for, in the other direction (F9b, [MediaCue]) —
    // both callbacks run on the audio thread, so their order is the route's own and the hops to
    // Main keep it.
    private val router = AudioRouter(
        context,
        onRouteHeld = { val at = clock(); scope.launch { onRouteHeld(at) } },
        onRouteReleased = { wasSco ->
            val at = clock()
            sco.onRouteReleased()
            scope.launch { onRouteReleased(wasSco, at) }
        },
    )
    /** Every route change and voice start/stop, in order, off Main. Failures come back on Main. */
    private val audio = AudioThread(scope) { what, e -> Hub.log("$what failed: $e") }
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val catalog = Catalog(http)
    /** Understands phrases the grammar does not parse; null in a build with no Gemini API key. */
    private val interpreter: Interpreter? = BuildConfig.GEMINI_API_KEY.takeIf { it.isNotEmpty() }?.let { key ->
        fun raw(id: Int) = context.resources.openRawResource(id).bufferedReader().use { it.readText() }.trimEnd()
        val prompt = raw(R.raw.interpret_prompt)
        val schema = raw(R.raw.interpret_schema)
        FirstAnswer(
            CloudGemini.MODELS.map { model -> model.removePrefix("gemini-") to CloudGemini(http, model, key, prompt, schema, clock) },
            noReplies = CloudGemini.NO_REPLIES.map { it.removePrefix("gemini-") }.toSet(),
            backup = CloudGemini.BACKUP_MODEL to CloudGemini(
                http, CloudGemini.BACKUP_MODEL, key, prompt, schema, clock, RateGuard(CloudGemini.BACKUP_PER_MINUTE),
            ),
            backupAfterMs = CloudGemini.BACKUP_AFTER_MS,
            limitMs = Interpretation.INTERPRET_TIMEOUT_MS,
        )
    }
    /** PROTOCOL.md "Commands", *Interpretation*: smart commands are on and there is a key. */
    private val interprets: Boolean get() = interpreter != null && settings.value.smartCommands
    /** The `interpret` of the last `hello` built, to send a new one when the setting changes. */
    private var helloInterprets = false
    /** Opus (remuxed to MP4) by default; `tracks/` stays the AAC one it always was. */
    private val caches = TrackCaches(
        opusCache = TrackCache(
            File(context.cacheDir, "tracks-opus"), http, { source(it, opus = true) }, scope,
            remux = ::remuxWebmToMp4,
            onChange = { scope.launch { refreshCached() } },
            log = Hub::log,
        ),
        aacCache = TrackCache(
            File(context.cacheDir, "tracks"), http, { source(it, opus = false) }, scope, maxBytes = 256L shl 20,
            onChange = { scope.launch { refreshCached() } },
            log = Hub::log,
        ),
    )
    /** The Search tab's album/playlist Download button: whole collections into the active cache. */
    private val downloads = CollectionDownloads(
        scope,
        ensure = { caches.ensure(it, DownloadPriority.COLLECTION) },
        cached = { caches.cached(it) },
        onProgress = { p -> Hub.status.update { it.copy(downloads = p) } },
        log = Hub::log,
    )
    private val player: Player = Player(
        context, ::onMediaKey, ::onRemoteControl,
        onEnded = { music.onTrackEnded() },
        onError = { music.onPlayerError(it) },
        // The earbuds dropped: pause both phones instead of playing on the Pixel's speaker.
        onNoisy = { music.onBecomingNoisy() },
    )
    private val network = NetworkWatch(context) { music.onNetworkBack() }
    /**
     * The output music plays on (from [devices]) and the trim in force for it. Main only. The trim
     * is per route since the first two-phone run (2026-09-29): the AirPods value applied on the
     * speaker put the Pixel ~240 ms ahead.
     */
    private var outputRoute: OutputRoute = OutputRoute.SPEAKER
    private var activeTrimMs: Int = settings.value.trims.of(outputRoute)
    private val sync: SyncController = SyncController(
        player, scope, clock,
        trimMs = { activeTrimMs },
        routeName = { outputRoute.name },
    )
    private val control: ControlServer = ControlServer(scope, clock, ::hello, { lastState }, log = Hub::log)
    private val voice: VoiceEngine = VoiceEngine(
        send = { ts, p, n -> voiceSocket.sendAudio(ts, p, n) },
        clockTs = { voiceSocket.currentTs() },
        // From the capture thread, on its first frame: never block it, hop to Main.
        onCaptureUp = { atMs -> scope.launch { onCaptureUp(atMs) } },
        // Same thread, same rule: the headset's own mic signal is arriving (F9a).
        onMicLive = { atMs -> scope.launch { onMicLive(atMs) } },
        // Also from the capture thread, once per talk: null unless the debug setting is on.
        openDump = ::openCaptureDump,
        // F9c: on an SCO route, the recorder opens once SCO is the communication device (bounded),
        // so it does not start on the built-in mic. Not on the speaker or any other route.
        awaitCaptureRoute = { stillWanted ->
            if (router.needsSco) sco.awaitConnected(CAPTURE_SCO_WAIT_MS, stillWanted) else null
        },
    )
    /**
     * The host-mic talk's engine (PROTOCOL.md "Host-mic talk"): the Lark receiver's two channels,
     * rider sent, passenger played here. Used instead of [voice] for a talk whose [talkMic] is
     * [TalkMic.Lark]; never with a call route.
     */
    private val lark: LarkEngine = LarkEngine(
        context,
        send = { ts, p, n -> voiceSocket.sendAudio(ts, p, n) },
        clockTs = { voiceSocket.currentTs() },
        onCaptureUp = { atMs -> scope.launch { onCaptureUp(atMs) } },
        openDump = { openCaptureDump(lark = true) },
        log = Hub::log,
    )
    /** Route + voice engine for talk, collapsed to the latest open/close (see [TalkAudio]). */
    private val talkAudio = TalkAudio(
        audio,
        enterCall = router::enterCall,
        exitCall = router::exitCall,
        voiceStart = { onFailed -> voice.start(onFailed) },
        voiceStop = voice::stop,
        voiceRunning = { voice.isRunning },
        // From the audio thread, right after exitCall returned — which is *not* proof that the
        // media route is back (6 ms once on the bench). Hop to Main and let [MediaCue] decide.
        closedEarcon = { scope.launch { mediaSound(MediaCue.Kind.CLOSED) { earcon(Earcons.Kind.CLOSED) } } },
        // From the audio thread or a voice thread: hop to Main, where talk state lives.
        onFailed = { session, what, e -> scope.launch { onMicFailed(session, "$what: ${e.message}") } },
        larkStart = { onFailed -> lark.start(onFailed) },
        larkStop = lark::stop,
        larkRunning = { lark.isRunning },
    )
    private val voiceSocket: VoiceSocket = VoiceSocket(clientIp = { control.clientAddress }, onPacket = {
        // Only the running engine takes it; in a host-mic talk that one drops and counts client audio.
        voice.onPacket(it)
        lark.onPacket(it)
    })
    private val trackServer = TrackServer(scope, lookup = { caches.active.cached(it) })
    private val discovery = Discovery(context, deviceName, Hub::log)
    private val announcer = Announcer(context, earconPlayer = { kind, call -> earcon(kind, call) })
    /** In-talk speech recognition on the talk's own capture (PROTOCOL.md "Commands"). Main only. */
    private val recognizer = TalkRecognizer(
        context,
        // Only the engine that is running reads its tee.
        attach = {
            voice.tee = it
            lark.tee = it
        },
        onPhrase = ::onPhrase,
        log = Hub::log,
    )
    /** Which of our rider's phrases is a command: the first-phrase rule, per talk. Main only. */
    private val phrases = FirstPhraseGate()
    /** The client's `command.text` for the current talk has been acted on (it gets one). Main only. */
    private var clientCommanded = false

    /** A clarifying question of this talk (PROTOCOL.md "Commands", *The clarifying question*). */
    private class Question(
        val session: Int,
        val fromClient: Boolean,
        /** The first request, the question it got and the command text to fall back on. */
        val phrase: String,
        val text: String,
        val fallback: String?,
    ) {
        /** The reply came (or the wait for it ended): nothing later is one. */
        var answered = false
    }

    /** The one question of the open talk, asked or answered; null = none yet. Main only. */
    private var question: Question? = null
    /**
     * The microphone of the current (or last) talk, chosen at its open and fixed for it
     * (PROTOCOL.md "Host-mic talk"). Main only.
     */
    private var talkMic: TalkMic? = null
    /** A host-mic talk the client opened: the recognizer listens to the passenger's channel. Main only. */
    private var passengerAsr = false
    /** The talk session whose `talk asr:` line was logged. Main only. */
    private var asrLoggedSession = 0

    /** A talk is open and it is a host-mic talk. */
    private val larkTalk: Boolean get() = talk.isOpen && talkMic is TalkMic.Lark
    private val music: MusicController = MusicController(
        scope, caches, sync, player, clock,
        send = control::send,
        hasClient = control::hasClient,
        onChanged = ::pushState,
        onError = { announce(it, Earcon.ERROR) },
        log = Hub::log,
        // "Recently played" on the Search tab: whoever queued it, once it really started.
        onStarted = { t -> history.update { it.played(t) } },
        online = { network.online },
    )
    private val audioManager = context.getSystemService(AudioManager::class.java)
    /** `AudioManager.getMode()` cached off Main; see [AudioModeWatch] and [micAvailable]. */
    private val audioMode = AudioModeWatch(context)
    /**
     * Is call audio really flowing over the Bluetooth link? Cached off Main: the other half of the
     * live earcon's truth. Since F8 that is the framework's communication device, not a broadcast.
     */
    /** Every audio device the phone has, and every plug and unplug (Stage A of the wired mic). */
    private val devices = DeviceWatch(
        context, Hub::log,
        onMediaRoute = { route -> scope.launch { onOutputRoute(route) } },
        onDevices = { list -> scope.launch { onDevices(list) } },
    )
    private val sco = ScoWatch(
        context,
        // Raw, every report, before the F8 dedupe: the `earpiece|none` that ends a teardown is the
        // signal a media-route sound waits for (F9b), and the dedupe swallows it.
        onDevice = { type, atMs -> scope.launch { onCommunicationDevice(type, atMs) } },
    ) { connected, atMs -> scope.launch { onScoState(connected, atMs) } }
    /** Decides when the "live" earcon may be played (F7). Touched on Main only. */
    private val liveCue = LiveCue()
    /** Decides when a sound that plays on the *media* route may be played (F9b). Main only. */
    private val mediaCue = MediaCue()
    /** The sounds [mediaCue] is holding, by the id it knows them under. Main only. */
    private val pendingSounds = HashMap<Int, () -> Unit>()
    private var mediaSoundSeq = 0
    /** The teardown of the last talk that closed; a command that closed it waits on this. */
    private var talkClosed: Job? = null
    /**
     * Bumped on every talk open, on Main. Audio work finishes asynchronously, so a failure or the
     * live earcon of an earlier talk can reach Main after the next one opened; they carry the
     * number they were started with and are dropped when it is no longer current.
     */
    private var talkSession = 0
    private var clientName: String? = null
    /** Debug, gate S4: the Lark receiver as stereo, outside the talk path. Files beside the dumps. */
    private val usbProbe = UsbStereoProbe(
        context,
        File(context.getExternalFilesDir(null) ?: context.filesDir, "captures"),
        Hub::log,
        onLong = { on -> Hub.status.update { it.copy(longRecording = on) } },
    )

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
        audioMode.start()
        sco.start()
        devices.start()
        Hub.log("host up as \"$deviceName\"")
        refreshCached()
        scope.launch {
            for (e in control.events) {
                noteMainLag(e)
                guarded("control event") { onControlEvent(e) }
            }
        }
        scope.launch { Triggers.events.collect { guarded("trigger") { onTrigger(it.kind, it.source) } } }
        scope.launch { Hub.actions.collect { guarded("ui action") { onUiAction(it) } } }
        // The rider edited a trim (or anything else): re-apply only if the active one moved.
        scope.launch {
            settings.flow.collect {
                guarded("settings") {
                    applyTrim()
                    checkLarkPresence()
                    // The client follows our latest `hello` (smart commands switched on or off).
                    if (interprets != helloInterprets) control.send(hello())
                }
            }
        }
        scope.launch {
            while (isActive) {
                refreshStatus()
                delay(1000)
            }
        }
    }

    /** [why]: what stopped the host, for the log (a silent stop once looked like a network fault). */
    fun stop(why: String) {
        Hub.log("host stopping: $why")
        usbProbe.stopLong()
        if (talk.isOpen) applyTalk(TalkController.Action.Close(Role.HOST, "link"))
        control.stop()
        voiceSocket.stop()
        trackServer.stop()
        discovery.stop()
        recognizer.stop()
        // Behind whatever talk teardown is still queued, then the thread retires. exitAll: a
        // route-back still queued behind the shutdown would be dropped, so do it here.
        audio.post("shutdown") {
            voice.stop()
            lark.stop()
            router.exitAll()
        }
        audio.shutdown()
        audioMode.stop()
        sco.stop()
        devices.stop()
        player.release()
        network.release()
        announcer.release()
        clearAnnounce?.cancel()
        Hub.status.value = LinkStatus()
        Hub.diagnostics.value = Diagnostics()
    }

    // ---- control channel ----

    private fun hello(): Hello {
        helloInterprets = interprets
        return Hello(
            proto = PROTO_VERSION, role = Role.HOST, name = deviceName,
            voicePort = VoiceSocket.PORT, httpPort = TrackServer.PORT,
            interpret = true.takeIf { helloInterprets },
        )
    }

    private fun state(): Message = StateFit.fit(
        State(
            talk = talk.isOpen,
            music = music.musicState(),
            // With the length and the cover, so the client's queue rows look like the host's.
            // A track of unknown length (0) sends none.
            queue = music.upcoming.map {
                QueueItem(it.id, it.title, it.artist, durationMs = it.durationMs.takeIf { d -> d > 0 }, art = it.art)
            },
            // PROTOCOL.md "state": only while the open talk is a host-mic one, so a client that
            // joins mid-talk opens it receive-only too.
            mic = if (larkTalk) Mic.HOST else null,
        ),
    )

    /**
     * The last [state] built, for the `hello` + `state` a new connection gets at once: that is
     * sent from the accept thread, and [state] reads Main-only things (talk, queue, anchor). Every
     * change goes through [pushState], so this is what a client already connected was last told.
     */
    @Volatile
    private var lastState: Message = State(talk = false, queue = emptyList())

    private fun pushState() {
        val s = state()
        lastState = s
        control.send(s)
        refreshStatus()
    }

    /**
     * How long a message sat in the channel before Main got to it (see [MainLag]). Anything over
     * 100 ms means Main was blocked — on the 2026-09-20 bench by an audio-service binder call —
     * and the protocol decision it carries (a talk re-open, above all) was made too late to
     * collapse with the teardown in flight. One line per occurrence, nothing when Main is idle.
     */
    private fun noteMainLag(e: ControlServer.Event) {
        if (e !is ControlServer.Event.Received) return
        MainLag.lineIfLate(e.message.wireType, clock() - e.atMs)?.let(Hub::log)
    }

    private fun onControlEvent(e: ControlServer.Event) {
        when (e) {
            is ControlServer.Event.ClientConnected -> {
                val previous = clientName
                if (previous != null) {
                    // A new hello replaced the previous client: its readiness is void, and so is
                    // its talk unless it is the same client back on a new socket (PROTOCOL.md
                    // "Liveness"). Voice follows the source of its next valid packet.
                    val wasOpen = talk.isOpen
                    talk.onClientReplaced(previous, e.name)?.let(::applyTalk)
                    if (wasOpen && talk.isOpen) Hub.log("talk kept: \"${e.name}\" reconnected")
                    voiceSocket.forgetPeer()
                    music.onClientGone()
                }
                clientName = e.name
                Hub.log("client \"${e.name}\" connected from ${e.address.hostAddress}")
                // A solo talk has company now: the rider's phrases are conversation from here,
                // never commands and never "Didn't catch that" (which the passenger would hear).
                if (talk.isOpen && phrases.onClientJoined()) {
                    Hub.log("talk: client joined a solo talk, commands off for the rest of it")
                    recognizer.stop()
                    Hub.status.update { it.copy(commandWindow = false) }
                }
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
            is MusicControl -> onMusicControl(m.action, "client")
            is CommandText -> onClientCommand(m.text)
            is MusicSearch -> onClientSearch(m.id) {
                if (m.kind == SearchKind.SONGS) {
                    catalog.searchSongs(m.query).also(::preResolveTop).map { ResultItem(it.id, it.title, it.artist, durationMs = it.durationMs, art = it.art) }
                } else {
                    catalog.searchCollections(m.kind == SearchKind.ALBUMS, m.query)
                        .map { ResultItem(it.id, it.title, it.artist, count = it.count, art = it.art) }
                }
            }
            is MusicBrowse -> onClientSearch(m.id) {
                if (!isValidTrackId(m.ref)) throw IllegalArgumentException("bad ref")
                // Per-item art is left out: the client already has the collection's cover.
                catalog.browse(m.ref).map { ResultItem(it.id, it.title, it.artist, durationMs = it.durationMs) }
            }
            is MusicEnqueue -> onClientEnqueue(m)
            is MusicEdit -> onClientEdit(m)
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
        // The USB probe holds the receiver and cannot be cut short (its files would be lost): the
        // passenger is refused like any other "mic unavailable", rather than given a talk that
        // records neither properly.
        if (!talk.isOpen && usbProbe.isRunning) {
            control.send(TalkClose(Role.HOST, CloseReason.UNAVAILABLE))
            Hub.log("talk.open refused: usb probe running")
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
            if (weAsked) errorEarcon()
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
        if (weAsked) errorEarcon()
    }

    /**
     * Turn a [TalkController] decision into wire messages (on Main, first) and audio work (queued
     * on [audio], in order). Nothing here blocks Main, so the timers, the resume and the next
     * protocol decision keep running while the headset switches profile.
     */
    private fun applyTalk(action: TalkController.Action) {
        when (action) {
            is TalkController.Action.Open -> {
                // PROTOCOL.md "Host-mic talk": decided here, once, and fixed for the talk.
                val swap = settings.value.larkSwap
                val mic = TalkMic.choose(devices.devices, settings.value.larkTalk)
                talkMic = mic
                Hub.log(TalkMic.line(mic, swap))
                talkOnEarbudsFallback = mic is TalkMic.Earbuds && TalkMic.missing(devices.devices, settings.value.larkTalk) != null
                if (talkOnEarbudsFallback) {
                    Hub.log("talk mic warning: Lark receiver not detected (${(mic as TalkMic.Earbuds).reason}), this talk uses the earbud mics")
                }
                // A host-mic talk holds no call route, so nothing else drops a CLOSED earcon still
                // waiting from the talk before (it once played into this one).
                for (id in mediaCue.dropClosed()) {
                    pendingSounds.remove(id)
                    Hub.log("media cue: closed dropped, talk re-opened")
                }
                val larkMic = mic as? TalkMic.Lark
                // The host recognises the opener's channel: the passenger's when the client opened.
                passengerAsr = larkMic != null && action.by == Role.CLIENT && control.hasClient()
                if (larkMic != null) {
                    lark.config = LarkEngine.Config(larkMic.id, larkMic.name, swap)
                    lark.asrPassenger = passengerAsr
                }
                control.send(TalkOpen(action.by, mic = if (larkMic != null) Mic.HOST else null))
                music.onTalkOpen(duck = settings.value.duckDuringTalk)
                pushState()
                val session = ++talkSession
                liveCue.open(session, clock())
                // PROTOCOL.md "Commands": only the opener's first phrase can be a command, and
                // with no client every phrase is one. The window starts at our live earcon.
                // In a host-mic talk the client opened, this phone recognises for the opener (the
                // passenger), with the window from our own live earcon.
                phrases.open(
                    when {
                        !control.hasClient() -> FirstPhraseGate.Role.SOLO
                        action.by == Role.HOST || passengerAsr -> FirstPhraseGate.Role.OPENER
                        else -> FirstPhraseGate.Role.OTHER
                    },
                    interpret = interprets,
                )
                clientCommanded = false
                question = null
                // The Ride tab: "Connecting…" until the live earcon, and the command list while a
                // phrase of ours can still be a command.
                Hub.status.update {
                    it.copy(talkLive = false, talkClosing = false, heard = null, commandWindow = phrases.role != FirstPhraseGate.Role.OTHER)
                }
                // Switching the headset to HFP blocks for about a second; messages went out first.
                // A failure here is this phone's "cannot open the microphone" case. enterCall
                // counts itself before it can throw, so the close that follows balances it.
                val opened = talkAudio.open(session, lark = larkMic != null)
                Hub.log("talk open (by ${action.by})")
                scope.launch {
                    // The route is decided once this returns; the SCO link is not up yet.
                    opened.join()
                    // A collapsed re-open kept a running engine, whose first frame — and whose
                    // established headset mic (F9a) — are behind us; no new callback will come.
                    val isLark = larkMic != null
                    (if (isLark) lark.captureUpAtMs else voice.captureUpAtMs)?.let {
                        fireLive(liveCue.captureUp(session, it))
                        startRecognizer(session)
                    }
                    if (!isLark) voice.micLiveAtMs?.let { fireLive(liveCue.micLive(session, it)) }
                    // `sco.connected` can only still be true here if the route was never released
                    // (a re-open that kept it), which is exactly when it may count — see F8 and
                    // ScoWatch.onRouteReleased. A host-mic talk has no call route: the live beep
                    // waits for the capture only.
                    fireLive(liveCue.route(session, !isLark && router.needsSco, !isLark && sco.connected, clock()))
                }
                scope.launch {
                    // The beep may be late, never missing (a signal that never came).
                    delay(LiveCue.TIMEOUT_MS + 50)
                    fireLive(liveCue.tick(session, clock()))
                }
            }
            is TalkController.Action.Close -> {
                control.send(TalkClose(action.by, action.reason))
                pushState()
                // A talk that closed before its microphone was live never gets its go-beep.
                liveCue.close()
                // EOF to the recognizer; the voice engine stops teeing at once.
                recognizer.stop()
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
                talkClosed = closed
                Hub.log("talk closed (by ${action.by}, ${action.reason})")
                // The Ride tab says "Ending…" while the headset switches back.
                Hub.status.update { it.copy(talkLive = false, talkClosing = true, heard = null, commandWindow = false) }
                scope.launch {
                    closed.join()
                    if (talkClosed === closed) Hub.status.update { it.copy(talkClosing = false) }
                }
                if (resuming) scope.launch {
                    closed.join()
                    sync.release(cold = true)
                }
            }
        }
    }

    /**
     * The capture loop of the talk that owns the voice engine read its first frame (on Main, via
     * [VoiceEngine.onCaptureUp]). Half of what the "live" earcon means; [LiveCue] holds the rule.
     */
    private fun onCaptureUp(atMs: Long) {
        fireLive(liveCue.captureUp(talkAudio.ownerSession, atMs))
        startRecognizer(talkAudio.ownerSession)
    }

    // ---- commands inside a talk (PROTOCOL.md "Commands") ----

    /**
     * The talk [session]'s microphone is delivering: listen to it for commands. Once per talk
     * ([TalkRecognizer.start] ignores a second call); a stale session is ignored, and so is a talk
     * whose first phrase can no longer come from us — one the client opened, or one whose window
     * already passed (a late capture).
     */
    private fun startRecognizer(session: Int) {
        if (!talk.isOpen || session != talkSession) return
        if (phrases.isSpent(clock())) return
        if (larkTalk && asrLoggedSession != session) {
            asrLoggedSession = session
            // The physical channel: the rider is left unless the stickers were swapped.
            val right = passengerAsr != settings.value.larkSwap
            Hub.log("talk asr: listening on ${if (right) "right" else "left"} (${if (passengerAsr) "passenger" else "rider"})")
        }
        recognizer.start(session, settings.value.asrLanguage)
    }

    /**
     * Talk [session]'s first phrase is used or its window has passed: no later phrase can be a
     * command, so stop recognising for the rest of the talk (PROTOCOL.md allows it; it saves the
     * recognizer's work). Never in a solo talk, where every phrase counts.
     */
    private fun stopRecognizerIfSpent(session: Int) {
        if (!talk.isOpen || session != talkSession) return
        if (phrases.role != FirstPhraseGate.Role.OPENER || !phrases.isSpent(clock())) return
        Hub.log("talk recognizer: first phrase spent, stopping")
        recognizer.stop()
        Hub.status.update { it.copy(commandWindow = false) }
    }

    /**
     * One phrase the rider said during talk [session] (on Main, from [TalkRecognizer]). Logged
     * locally, every one of them, as `heard: "<text>" (command|conversation)`. Conversation never
     * goes on the wire.
     */
    private fun onPhrase(session: Int, text: String) {
        if (!talk.isOpen || session != talkSession) {
            Hub.log("heard: \"$text\" (after the talk, ignored)")
            return
        }
        // With no client every phrase is a command, so a reply of ours that the talk microphone
        // hears back ("Next: …" → "Didn't catch that" → …) must not become the next one.
        val solo = phrases.role == FirstPhraseGate.Role.SOLO
        if (solo && announcer.spokeWithin(ECHO_MS)) {
            Hub.log("heard: \"$text\" (own speech, ignored)")
            return
        }
        // Our question, heard back by the talk microphone, is not its own reply.
        if (awaitsReply(passengerAsr) && announcer.spokeWithin(ASK_ECHO_MS)) {
            Hub.log("heard: \"$text\" (the question itself, ignored)")
            return
        }
        val command = phrases.onPhrase(text, clock())
        val who = if (passengerAsr) "passenger, " else ""
        Hub.log("heard: \"$text\" ($who${if (command != null) "command" else "conversation"})")
        Hub.status.update { it.copy(heard = text) }
        // Before the command: a `play` closes the talk, and with it this check's reason to run.
        stopRecognizerIfSpent(session)
        if (command == null) return
        if (passengerAsr) onPassengerCommand(command) else submitCommand(command)
    }

    /**
     * The passenger's first phrase in a host-mic talk the client opened (PROTOCOL.md "Commands"):
     * acted on exactly as if it had come as the client's `command.text`, and it spends the
     * client's one command for the talk. Volume is the passenger's own (their phone's keys): a
     * volume phrase is ignored, with no `announce`.
     */
    private fun onPassengerCommand(text: String) {
        // The reply to our question goes on as it is, volume words and all.
        if (awaitsReply(fromClient = true)) return submitCommand(text, fromClient = true)
        if (clientCommanded) {
            Hub.log("command: \"$text\" (passenger) ignored: not the talk's first")
            return
        }
        clientCommanded = true
        val cmd = CommandParser.parse(text)
        if (cmd == Command.VolumeUp || cmd == Command.VolumeDown) {
            Hub.log("command: \"$text\" (passenger) ignored: volume is the passenger's own")
            return
        }
        submitCommand(text, fromClient = true)
    }

    /**
     * The headset's own microphone signal is arriving (on Main, via [VoiceEngine.onMicLive] /
     * [MicLive]): the last third of the "live" earcon's truth, and the only one of the three that
     * has travelled back from the earpieces (F9a).
     */
    private fun onMicLive(atMs: Long) {
        fireLive(liveCue.micLive(talkAudio.ownerSession, atMs))
    }

    /**
     * Bluetooth call audio started or stopped flowing ([ScoWatch], on Main). The other half. Since
     * F8 this is the audio framework's communication device becoming (or ceasing to be) `bt_sco`,
     * which the bench shows landing after the SCO link is really open.
     */
    private fun onScoState(connected: Boolean, atMs: Long) {
        if (connected) fireLive(liveCue.scoConnected(atMs)) else liveCue.scoDisconnected()
    }

    /**
     * Play the "live" earcon, once per talk open. `call = true`: it is a call-route sound and must
     * follow the headset that is in call mode. The guards are the old ones — talk still open, and
     * still the same talk — because [LiveCue]'s decision is made from events that travel.
     *
     * One line per talk for the bench, e.g.
     * `live cue: session 8, capture up +1310 ms, sco +1240 ms, mic +1502 ms, fired +1502 ms (both)`.
     */
    private fun fireLive(fire: LiveCue.Fire?) {
        if (fire == null) return
        if (!talk.isOpen || fire.session != talkSession) return
        Hub.log(fire.line())
        // A host-mic talk holds no call route: the beep goes where the music goes (A2DP).
        if (settings.value.liveBeep) earcon(Earcons.Kind.LIVE, call = !larkTalk)
        Hub.status.update { it.copy(talkLive = true) }
        // The first phrase's window runs from here; once it has passed, stop listening.
        phrases.live(clock())
        if (phrases.role == FirstPhraseGate.Role.OPENER) scope.launch {
            delay(FirstPhraseGate.FIRST_PHRASE_MS + 1)
            stopRecognizerIfSpent(fire.session)
        }
    }

    // ---- sounds on the media route (F9b) ----

    /**
     * Every sound that plays on the **media** route goes through here: the CLOSED and ERROR
     * earcons, the OK earcon of a local volume change, and the spoken replies. [MediaCue] holds
     * the rule; [play] is what actually makes the sound (an `earcon(…)` post, or the announcer).
     *
     * With no call route held and none being torn down — the common case — [play] runs **now**, on
     * this same Main turn, and the line says `played +0 ms`. Otherwise the sound is kept here until
     * the route is really gone, and the 2 s timer below guarantees it is never simply lost.
     *
     * Main only (the audio thread hops here first): [pendingSounds] and [mediaCue] are not shared.
     */
    private fun mediaSound(kind: MediaCue.Kind, play: () -> Unit) {
        val id = ++mediaSoundSeq
        val now = clock()
        val fire = mediaCue.request(id, kind, now)
        if (fire != null) {
            playMedia(fire, play)
            return
        }
        pendingSounds[id] = play
        scope.launch {
            // The sound can be late, never missing.
            delay(MediaCue.TIMEOUT_MS + 50)
            playMedia(mediaCue.tick(clock()))
        }
    }

    /** This app took a call route: whatever is waiting for the media route waits for the next one. */
    private fun onRouteHeld(atMs: Long) {
        // A CLOSED earcon still waiting when talk is open again is a lie; it is dropped for good.
        for (id in mediaCue.routeHeld(atMs)) {
            pendingSounds.remove(id)
            Hub.log("media cue: closed dropped, talk re-opened")
        }
    }

    /**
     * `exitCall` returned at nesting depth 0. Off SCO that is the whole of it and everything
     * waiting plays now; an SCO route also has to be seen *gone* by the framework
     * ([onCommunicationDevice]), because that teardown is the thing the CLOSED beep used to be
     * played into.
     */
    private fun onRouteReleased(wasSco: Boolean, atMs: Long) {
        playMedia(mediaCue.routeReleased(wasSco, atMs))
    }

    /** The framework's communication device, raw ([ScoWatch]'s second callback, F9b). */
    private fun onCommunicationDevice(type: Int?, atMs: Long) {
        playMedia(mediaCue.device(type, atMs))
    }

    private fun playMedia(plays: List<MediaCue.Play>) {
        for (p in plays) pendingSounds.remove(p.id)?.let { playMedia(p, it) }
    }

    /**
     * One line per sound for the bench, e.g.
     * `media cue: closed, released +6 ms, device earpiece +415 ms, played +415 ms (both)`.
     */
    private fun playMedia(p: MediaCue.Play, play: () -> Unit) {
        Hub.log(p.line())
        play()
    }

    // ---- the debug capture dump (Stage A of the wired-mic plan) ----

    /**
     * One WAV file per talk, named by the wall clock, or null when the rider has not turned the
     * debug setting on — which is every ordinary ride. This is what an A/B of two microphones is
     * made of; nothing in this app could record its own capture before.
     *
     * Called on the `voice-capture` thread (`THREAD_PRIORITY_URGENT_AUDIO`), once, before the first
     * frame is read: [PcmDump.start] only allocates its pool and spawns the writer that does the
     * file work, and the `exists` loop below is one `stat` per talk — a recording is never silently
     * overwritten by a second talk in the same second.
     *
     * The files land in the app's external files dir, which `adb pull` reaches without root:
     * `/sdcard/Android/data/com.kivan.motoparty/files/captures/`.
     */
    private fun openCaptureDump(lark: Boolean = false): PcmDump? {
        if (!settings.value.captureDump) return null
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "captures")
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        // A host-mic talk dumps the receiver's raw 48 kHz stereo (rider L, passenger R, no swap,
        // no filter): the file an A/B of the two TXs is made from.
        val base = if (lark) "capture-lark-$stamp" else "capture-$stamp"
        var file = File(dir, "$base.wav")
        var n = 2
        while (file.exists()) file = File(dir, "$base-${n++}.wav")
        return if (lark) {
            PcmDump(
                file = file,
                sampleRate = LarkPipeline.RATE_IN,
                frameSamples = LarkPipeline.RATE_IN / 50 * 2,
                channels = 2,
                maxBytes = LARK_DUMP_MAX_BYTES,
                log = Hub::log,
            )
        } else {
            PcmDump(
                file = file,
                sampleRate = VoiceEngine.RATE,
                frameSamples = VoiceEngine.FRAME,
                log = Hub::log,
            )
        }.also { it.start() }
    }

    // ---- triggers ----

    private fun onTrigger(kind: TriggerKind, source: TriggerSource) {
        Hub.log("trigger $kind from $source")
        // One action everywhere since option A (2026-09-29): a press toggles talk. Commands are
        // spoken inside it.
        val solo = !talk.isOpen && !control.hasClient()
        // Our own trigger with no usable mic: error earcon, and no talk.open goes out.
        if (!talk.isOpen && !micAvailable()) {
            errorEarcon()
            return
        }
        // The USB probe holds the receiver (51 s, or a whole ride); a talk opening under it
        // would record neither properly. Only the open is blocked: a press still ends a talk.
        if (!talk.localTriggerAllowed(micHeld = usbProbe.isRunning)) {
            errorEarcon()
            Hub.log("talk: usb probe running")
            return
        }
        // With nobody connected the talk is solo: the whole talk path (call route, capture, live
        // beep) with no far end. It is how a command is given alone — every phrase is one — and,
        // with the capture-dump setting on, how a microphone is recorded on a ride alone. The
        // bench greps the first half of this line.
        if (solo) {
            Hub.log("talk: no client connected, recording solo" + if (settings.value.captureDump) " (WAV)" else " (no WAV)")
        }
        applyTalk(talk.onLocalTrigger())
    }

    /**
     * Headset and media keys control the music only. The earbuds sit inside the helmet, so no
     * press starts or ends a talk (2026-09-29): talk is the overlay, the notification or the UI.
     */
    private fun onMediaKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                Hub.log("music control ${if (music.isPlaying) "pause" else "resume"} from mediakey")
                music.togglePlayPause()
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                Hub.log("music control next from mediakey")
                music.next()
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                Hub.log("music control previous from mediakey")
                music.previous()
            }
            else -> return false
        }
        return true
    }

    /**
     * The device roster changed ([DeviceWatch]). A host-mic talk whose receiver is gone ends as
     * `unavailable` (PROTOCOL.md "Host-mic talk"); the next talk falls back to the earbuds. Main.
     */
    private fun onDevices(list: List<com.kivan.motoparty.audio.DeviceRoster.Dev>) {
        checkLarkPresence()
        val mic = talkMic as? TalkMic.Lark ?: return
        if (!larkTalk) return
        if (list.any { it.isInput && it.id == mic.id }) return
        onMicFailed(talkSession, "lark receiver unplugged")
    }

    /**
     * Is the Lark receiver there while the setting wants it ([TalkMic.missing])? Logs each change —
     * `lark receiver: not detected (no USB input), talk will use the earbud mics` and
     * `lark receiver: detected` — and updates the Ride tab's warning at once. Main.
     */
    private fun checkLarkPresence() {
        val list = devices.devices ?: return
        val missing = TalkMic.missing(list, settings.value.larkTalk)
        if (larkPresenceKnown && missing == larkMissing) return
        val was = larkMissing
        val first = !larkPresenceKnown
        larkPresenceKnown = true
        larkMissing = missing
        when {
            missing != null -> Hub.log(
                "lark receiver: not detected ($missing), talk will use the earbud mics" +
                    if (talk.isOpen && talkMic is TalkMic.Lark) " (lost during a talk)" else "",
            )
            was != null && !first -> (TalkMic.choose(list, true) as? TalkMic.Lark)?.let { Hub.log("lark receiver: detected \"${it.name}\"") }
        }
        refreshStatus()
    }

    /** [TalkMic.missing] as last seen; [larkPresenceKnown] false until the first roster. Main only. */
    private var larkMissing: String? = null
    private var larkPresenceKnown = false
    /** The talk that is open fell back to the earbud mics with the setting on. Main only. */
    private var talkOnEarbudsFallback = false

    /** The music moved to another output ([DeviceWatch]); the trim follows it. Main. */
    private fun onOutputRoute(route: OutputRoute) {
        Hub.log("media route: ${route.name}${if (route.bluetooth) " (Bluetooth)" else ""}")
        outputRoute = route
        applyTrim()
        refreshStatus()
    }

    /** Make the trim of the current route the active one, telling [sync] if it (or the route) changed. */
    private fun applyTrim() {
        val trim = settings.value.trims.of(outputRoute)
        if (trim == activeTrimMs && outputRoute.name == trimRouteName) return
        val was = activeTrimMs
        activeTrimMs = trim
        trimRouteName = outputRoute.name
        sync.retrim(was, trim, outputRoute.name)
    }

    /** The route [activeTrimMs] was last applied for, so a route change with an equal trim still logs. */
    private var trimRouteName: String? = null

    // ---- commands ----

    /**
     * The passenger's `command.text`. PROTOCOL.md "Commands": the host acts on it only while a
     * talk is open, the client opened it, and it is that talk's first `command.text`; any other is
     * logged and dropped, with no `announce`. The window is the client's to keep.
     */
    private fun onClientCommand(text: String) {
        val why = when {
            !talk.isOpen -> "no talk open"
            // The host recognises the passenger itself (PROTOCOL.md "Commands"); the client never should.
            larkTalk -> "host-mic talk"
            talk.openedBy != Role.CLIENT -> "the host opened the talk"
            clientCommanded && !awaitsReply(fromClient = true) -> "not the talk's first"
            else -> null
        }
        if (why != null) {
            Hub.log("command: \"$text\" (client) ignored: $why")
            return
        }
        clientCommanded = true
        submitCommand(text, fromClient = true)
    }

    /**
     * A command candidate from any source. One the grammar parses (except a `play …`), and every
     * one while smart commands are off, goes straight to [executeCommand]. Otherwise the interpreter is asked what
     * was meant (PROTOCOL.md "Commands", *Interpretation*) and its answer, as a command text, is
     * executed as if the same side had said it in those words. Conversation and every failure do
     * nothing: the talk stays open, nothing is said (solo and typed: "Didn't catch that"). An
     * `ask` answer becomes our one question of the talk ([ask]), and the phrase after it its reply.
     */
    private fun submitCommand(text: String, fromClient: Boolean = false) {
        val interpreter = interpreter
        // The reply to our question is always interpreted, with the question as its context.
        val reply = question?.takeIf { awaitsReply(fromClient) }
        // The grammar's own `play …` and `queue …` are interpreted too (names get repaired, a vague
        // one may be asked about); if the interpreter does not settle it, it is executed as spoken.
        val parsed = CommandParser.parse(text)
        val spoken = text.takeIf { reply == null && (parsed is Command.Play || parsed is Command.Queue) }
        if (interpreter == null || !interprets || (reply == null && spoken == null && parsed != Command.Unknown)) {
            return executeCommand(text, fromClient)
        }
        reply?.answered = true
        // The answer counts only for the talk (or the absence of one) the phrase was said in.
        val talkWas = talk.isOpen
        val session = talkSession
        // Where an unparsed phrase gets "Didn't catch that": typed, or spoken in a solo talk.
        val solo = !talkWas || phrases.role == FirstPhraseGate.Role.SOLO
        val current = music.current
        val playing = current?.let { "${it.title} – ${it.artist}" }
        val upNext = music.upcoming.take(Interpretation.INTERPRET_UP_NEXT).map { it.title }
        scope.launch {
            val t0 = clock()
            val answer = interpreter.interpret(
                text, settings.value.asrLanguage, playing, current?.album, upNext, reply?.let { Interpreter.Asked(it.phrase, it.text) },
            )
            val outcome = (answer as? Interpreter.Text)?.let { Interpretation.outcome(it.text) }
            val stale = talk.isOpen != talkWas || talkSession != session
            // One question a talk, and only in a talk: otherwise an `ask` is its fallback.
            val ask = (outcome as? Interpretation.Ask)?.takeIf { talkWas && question == null }
            // What a reply falls back on when it settles nothing: never on "never mind".
            val fallback = reply?.fallback?.takeIf { answer is Interpreter.Failed || outcome is Interpretation.Ask }
            val command = when (outcome) {
                is Interpretation.Do -> outcome.text
                is Interpretation.Ask -> if (ask != null) null else outcome.fallback ?: fallback ?: spoken
                null -> fallback ?: spoken
            }
            val what = when {
                outcome is Interpretation.Ask -> "ask \"${outcome.question}\" (fallback ${outcome.fallback ?: "none"})"
                answer is Interpreter.Failed -> "failed: ${answer.why}"
                outcome == null -> "conversation"
                else -> command
            }
            val volume = command == "volume up" || command == "volume down"
            val note = when {
                stale -> " (dropped: the talk changed)"
                command != null && volume && fromClient -> " (ignored: volume is the passenger's own)"
                command != null && command == spoken -> " (played as spoken)"
                command != null && command != (outcome as? Interpretation.Do)?.text -> " (the fallback)"
                else -> ""
            }
            val of = if (reply != null) " (reply to \"${reply.text}\")" else ""
            val by = (answer as? Interpreter.Text)?.by?.let { " via ${it.removePrefix("gemini-")}" }.orEmpty()
            Hub.log("interpret: \"$text\"${if (fromClient) " (client)" else ""}$of → $what in ${clock() - t0} ms$by$note")
            when {
                stale -> Unit
                ask != null -> ask(Question(session, fromClient, text, ask.question, ask.fallback))
                // The unparsed text itself: [executeCommand] answers it "Didn't catch that".
                command == null -> if (solo) executeCommand(text, fromClient)
                volume && fromClient -> Unit
                else -> executeCommand(command, fromClient)
            }
        }
    }

    /** Is the next phrase of that side (the passenger's, [fromClient]) the reply to our question? */
    private fun awaitsReply(fromClient: Boolean): Boolean =
        question?.let { !it.answered && it.fromClient == fromClient && talk.isOpen && it.session == talkSession } == true

    /**
     * Ask [q] aloud in the talk and give the side that spoke one more phrase (PROTOCOL.md
     * "Commands", *The clarifying question*). With no reply in time its fallback is played.
     */
    private fun ask(q: Question) {
        question = q
        // The client's own reply comes as a second `command.text`; [onClientCommand] lets it in.
        if (!q.fromClient || passengerAsr) {
            phrases.ask(clock())
            startRecognizer(q.session)
            Hub.status.update { it.copy(commandWindow = true) }
        }
        announce(q.text, earcon = null, inTalk = true, ask = true)
        scope.launch {
            delay(Interpretation.ANSWER_MS + Interpretation.ANSWER_GRACE_MS)
            stopRecognizerIfSpent(q.session)
            if (question !== q || q.answered || !talk.isOpen || talkSession != q.session) return@launch
            q.answered = true
            Hub.log("ask: no reply to \"${q.text}\"${q.fallback?.let { ", the fallback: $it" } ?: ", nothing to fall back on"}")
            q.fallback?.let { executeCommand(it, q.fromClient) }
        }
    }

    /**
     * A command: typed on the Ride screen, the passenger's `command.text` ([fromClient], already
     * let through by [onClientCommand]), or our rider's first phrase in a talk (any phrase, solo). What it does to the talk and where its reply is heard is
     * [CommandEffect]'s decision (PROTOCOL.md "Commands", *Effect on the talk*); this carries it out.
     */
    private fun executeCommand(text: String, fromClient: Boolean = false) {
        Hub.log("command: \"$text\"${if (fromClient) " (client)" else ""}")
        val cmd = CommandParser.parse(text)
        val effect = CommandEffect.of(cmd, talk.isOpen, fromClient)
        val inTalk = effect.reply == CommandEffect.Reply.CALL
        // What must happen before the talk closes, on the music the talk paused: `resume` parks
        // the track and `next`/`previous` choose it, so the close resumes it the usual way (held
        // over the route switch); `pause` cancels that resume; `play` drops it, so the old music
        // does not come back for the second before the new track does.
        var hadResume = false
        // The spoken reply, for a failure or a command with nothing else to show for itself. A
        // command whose result is the music has none ("Spoken replies" in PROTOCOL.md).
        var said: Pair<String, String>? = null
        when (cmd) {
            Command.Resume -> {
                if (!music.canResume) said = "Nothing to resume" to Earcon.ERROR
                music.resume()
            }
            is Command.Play -> if (effect.closeBy != null) hadResume = music.beforePlayEndsTalk(clock() + settings.value.resumeLeadMs)
            Command.Pause -> music.pause()
            Command.Next -> {
                // On the last track `next` parks it and `current` stays: that is the end of the queue.
                if (!music.hasNext) said = "End of queue" to Earcon.OK
                music.next()
            }
            Command.Previous -> {
                music.previous()
                if (music.current == null) said = "Nothing to play" to Earcon.OK
            }
            Command.NowPlaying -> said = music.current.let { MusicController.nowPlayingLine(it) to if (it != null) Earcon.OK else Earcon.ERROR }
            Command.Shuffle -> said = if (music.shuffleUpcoming()) "Shuffled" to Earcon.OK else "Nothing to shuffle" to Earcon.ERROR
            Command.Unknown -> said = "Didn't catch that" to Earcon.ERROR
            // `end` has no reply: the talk's closing earcon is the acknowledgement.
            Command.End -> if (effect.closeBy == null) Hub.log("end: no talk to end")
            Command.VolumeUp, Command.VolumeDown, is Command.Queue -> Unit
        }
        val closed: Job? = effect.closeBy?.let { by ->
            talk.onCommandClose(by)?.let(::applyTalk)
            talkClosed
        }
        /** Now, or once the talk this command closed is down and the headset is back in media mode. */
        fun afterClose(block: () -> Unit) {
            if (closed == null) block() else scope.launch { closed.join(); block() }
        }
        when (cmd) {
            is Command.Play -> scope.launch {
                Hub.status.update { it.copy(busy = "Searching ${cmd.kind.word} \"${cmd.query}\"") }
                val found = try {
                    catalog.search(cmd.kind, cmd.query)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                } finally {
                    Hub.status.update { it.copy(busy = null) }
                }
                // The music and a failure's reply come after the headset is back in media mode.
                closed?.join()
                when (found) {
                    is Catalog.Result -> {
                        music.setQueue(found.tracks)
                        Hub.log("playing ${found.label}")
                        Hub.status.update { it.copy(error = null) }
                    }
                    else -> {
                        // The talk is closed anyway; the music it paused comes back.
                        if (hadResume) music.resume()
                        announce(searchFailure(found as Exception, cmd.query), Earcon.ERROR)
                    }
                }
            }
            is Command.Queue -> scope.launch {
                val source = music.current
                val what = cmd.kind?.let { "${it.word} \"${cmd.query}\"" } ?: "similar music"
                Hub.status.update { it.copy(busy = "Searching $what") }
                val found = try {
                    when {
                        cmd.kind != null -> catalog.search(cmd.kind, cmd.query).tracks.let { tracks ->
                            if (!VoiceQueue.wantsCurrentAlbum(cmd, tracks, source)) tracks
                            else catalog.albumContaining(source!!)?.also { Hub.log("queue: using ${it.label}, which holds the playing track") }?.tracks ?: tracks
                        }
                        source != null -> catalog.similar(source.id)
                        else -> null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                } finally {
                    Hub.status.update { it.copy(busy = null) }
                }
                // The talk paused the music; it is back before anything is added or said.
                closed?.join()
                val current = music.current
                val added = (found as? List<*>)?.filterIsInstance<Track>()?.let { VoiceQueue.pick(cmd, it, current, music.upcoming) }
                when {
                    found is Exception -> announce(searchFailure(found, cmd.query), Earcon.ERROR)
                    found == null -> announce("Nothing playing", Earcon.ERROR)
                    added.isNullOrEmpty() -> announce("Nothing to add", Earcon.ERROR)
                    else -> {
                        if (cmd.where == Command.Where.INSTEAD) music.clearUpcoming()
                        music.enqueue(if (cmd.where == Command.Where.NEXT) EnqueueMode.NEXT else EnqueueMode.END, added)
                        Hub.log("queued ${cmd.where.name.lowercase()}: ${added.size} track(s) of $what")
                        Hub.status.update { it.copy(error = null) }
                        // With nothing loaded they just started playing: the music says so.
                        if (current != null) announce(VoiceQueue.reply(cmd.where, added), Earcon.OK)
                    }
                }
            }
            Command.VolumeUp -> afterClose { onVolumeCommand(fromClient, AudioManager.ADJUST_RAISE, inTalk) }
            Command.VolumeDown -> afterClose { onVolumeCommand(fromClient, AudioManager.ADJUST_LOWER, inTalk) }
            else -> when (val reply = said) {
                // Done, and the music says so: a failure's banner has nothing left to say.
                null -> if (cmd != Command.End) Hub.status.update { it.copy(error = null) }
                else -> afterClose { announce(reply.first, reply.second, inTalk) }
            }
        }
    }

    /** The spoken reply to a `play` whose search threw [e]. */
    private fun searchFailure(e: Exception, query: String): String = when (e) {
        is Catalog.NotFound -> "Couldn't find $query"
        is IOException -> "No coverage"
        else -> {
            Hub.log("search failed: $e")
            "Search failed"
        }
    }

    private fun onMusicControl(action: String, from: String) {
        Hub.log("music control $action from $from")
        // Values outside this set never get here: the codec drops them as malformed.
        when (action) {
            ControlAction.PAUSE -> music.pause()
            ControlAction.RESUME -> music.resume()
            ControlAction.NEXT -> music.next()
            ControlAction.PREVIOUS -> music.previous()
        }
    }

    /**
     * Play/pause/next/previous from an outside controller (KDE Connect, lock screen, watch): a
     * request like the client's `music.control`, so it acts on the whole ride. Silent, since no
     * one spoke.
     */
    private fun onRemoteControl(action: RemoteAction) {
        onMusicControl(
            when (action) {
                RemoteAction.PAUSE -> ControlAction.PAUSE
                RemoteAction.RESUME -> ControlAction.RESUME
                RemoteAction.NEXT -> ControlAction.NEXT
                RemoteAction.PREVIOUS -> ControlAction.PREVIOUS
            },
            from = "remote",
        )
    }

    /**
     * PROTOCOL.md "Commands", *Volume is local*: "volume up/down" changes only the phone it was
     * spoken on and is never sent. Our rider's utterance changes this phone, with an earcon and
     * no `announce` — the passenger must not be told. A volume utterance arriving from the client
     * means the client failed to handle it locally, and is answered "Didn't catch that".
     *
     * Always the media volume: a volume phrase ends the talk it was spoken in, and this runs
     * after that close.
     */
    private fun onVolumeCommand(fromClient: Boolean, direction: Int, inTalk: Boolean) {
        if (fromClient) {
            announce("Didn't catch that", Earcon.ERROR, inTalk)
            return
        }
        // Two steps: one step is barely audible under a helmet. Off Main with the earcon, in the
        // same block: adjustStreamVolume is a binder call into the audio service like any other.
        audio.post("volume") {
            repeat(2) { audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) }
        }
        // A media-route sound like every other (F9b): the talk it was spoken in is closed by now.
        mediaSound(MediaCue.Kind.OK) { earcon(Earcons.Kind.OK) }
        Hub.log("volume ${if (direction == AudioManager.ADJUST_RAISE) "up" else "down"} (local, media)")
    }

    /**
     * The passenger is told over the wire at once. This phone's own earcon + speech are either a
     * media-route sound that waits for the media route (F9b) — the reply to a command that ended
     * a talk follows that talk's `exitCall` by a few ms, exactly the teardown the first syllable
     * used to be lost in — or, [inTalk], spoken now on the call route the headset is in.
     */
    private fun announce(text: String, earcon: String?, inTalk: Boolean = false, ask: Boolean = false) {
        control.send(Announce(text, earcon, ask = true.takeIf { ask }))
        val kind = when (earcon) {
            Earcon.OK -> Earcons.Kind.OK
            Earcon.ERROR -> Earcons.Kind.ERROR
            else -> null
        }
        val language = settings.value.asrLanguage
        // In a host-mic talk there is no call route to speak on: the media route, now.
        if (inTalk && !larkTalk) announcer.announce(text, kind, language, call = true)
        else mediaSound(MediaCue.Kind.ANNOUNCE) { announcer.announce(text, kind, language) }
        // A failure stays as a banner until it is dismissed or something succeeds; a success is
        // a line that goes away by itself, so it never sits under a later song (UA3).
        val failed = earcon == Earcon.ERROR
        Hub.status.update { it.copy(lastAnnounce = if (failed) null else text, error = if (failed) text else null) }
        clearAnnounce?.cancel()
        clearAnnounce = if (failed) null else scope.launch {
            delay(ANNOUNCE_SHOWN_MS)
            Hub.status.update { if (it.lastAnnounce == text) it.copy(lastAnnounce = null) else it }
        }
        Hub.log("announce: $text")
    }

    /** Takes [LinkStatus.lastAnnounce] off the screen again. Main only. */
    private var clearAnnounce: Job? = null

    /** The "that did not work" tone, on the media route like every other non-call sound. */
    private fun errorEarcon() {
        mediaSound(MediaCue.Kind.ERROR) { earcon(Earcons.Kind.ERROR) }
    }

    /** Where a track downloads from. Off Main; the log line says which stream a run got. */
    private suspend fun source(id: String, opus: Boolean): TrackCache.Source {
        val a = catalog.resolveAudio(id, opus)
        Hub.log("track $id: itag ${a.itag}${if (a.webm) " (Opus, remuxed to MP4)" else ""}")
        return TrackCache.Source(a.url, a.webm)
    }

    // ---- UI ----

    private fun onUiAction(a: UiAction) {
        when (a) {
            is UiAction.Search -> uiSearch(a.kind, a.query)
            is UiAction.Browse -> uiBrowse(a.collection)
            is UiAction.CloseBrowse -> {
                uiBrowseJob?.cancel()
                Hub.status.update { it.copy(browse = null) }
            }
            is UiAction.Enqueue -> enqueue(a.mode, a.tracks, Role.HOST)
            is UiAction.Jump -> jump(a.index, a.id, Role.HOST)
            is UiAction.Remove -> music.remove(a.index, a.id)
            is UiAction.ClearQueue -> music.clearUpcoming()
            is UiAction.Restore -> restore(a.index, a.track)
            is UiAction.DismissError -> Hub.status.update { it.copy(error = null) }
            is UiAction.Download -> downloads.start(a.collection.id, a.tracks.map { it.id })
            is UiAction.CancelDownload -> downloads.cancel(a.collectionId)
            is UiAction.Command -> submitCommand(a.text)
            is UiAction.Control -> onMusicControl(a.action, "ui")
            is UiAction.UsbStereoProbe -> when {
                talk.isOpen -> Hub.log("usb probe: not during a talk")
                !usbProbe.start() -> Hub.log("usb probe: already running")
            }
            is UiAction.LongRecording -> when {
                Hub.status.value.longRecording -> usbProbe.stopLong()
                talk.isOpen -> Hub.log("usb probe: not during a talk")
                !usbProbe.startLong() -> Hub.log("usb probe: already running")
            }
        }
    }

    /** Undo of a removal on the Queue tab: [track] back where it was. */
    private fun restore(index: Int, track: Track) {
        if (music.current == null) Hub.log("undo: nothing loaded, ignored") else music.insert(index, track)
    }

    private var uiSearchJob: Job? = null
    private var uiBrowseJob: Job? = null

    /** The top song results are the likely taps: have their stream addresses looked up already. */
    private fun preResolveTop(songs: List<Track>) = caches.preResolve(songs.take(PRE_RESOLVE_TOP).map { it.id })

    private fun uiSearch(kind: String, query: String) {
        history.update { it.searched(kind, query) }
        uiSearchJob?.cancel()
        Hub.status.update { it.copy(search = SearchState(kind, query, loading = true)) }
        uiSearchJob = scope.launch {
            val done = try {
                if (kind == SearchKind.SONGS) {
                    SearchState(kind, query, songs = catalog.searchSongs(query).also(::preResolveTop))
                } else {
                    SearchState(kind, query, collections = catalog.searchCollections(kind == SearchKind.ALBUMS, query))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Hub.log("search failed: $e")
                SearchState(kind, query, error = failure(e))
            }
            Hub.status.update { it.copy(search = done) }
        }
    }

    private fun uiBrowse(c: CollectionItem) {
        uiBrowseJob?.cancel()
        Hub.status.update { it.copy(browse = BrowseState(c)) }
        uiBrowseJob = scope.launch {
            val done = try {
                BrowseState(c, loading = false, tracks = catalog.browse(c.id).map { it.copy(art = c.art ?: it.art) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Hub.log("browse ${c.id} failed: $e")
                BrowseState(c, loading = false, error = failure(e))
            }
            Hub.status.update { it.copy(browse = done) }
        }
    }

    /** Short text for a failed search or browse, for either screen. */
    private fun failure(e: Exception) = if (e is IOException) "No coverage" else "Search failed"

    // ---- browsing (PROTOCOL.md "Browsing") ----

    private var clientSearchJob: Job? = null

    /** A newer request replaces an older one; the client only shows its newest `id` anyway. */
    private fun onClientSearch(id: Long, block: suspend () -> List<ResultItem>) {
        clientSearchJob?.cancel()
        clientSearchJob = scope.launch {
            val reply = try {
                MusicResults(id, block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Hub.log("client search failed: $e")
                MusicResults(id, emptyList(), failure(e))
            }
            control.send(Codec.fit(reply))
        }
    }

    private fun onClientEnqueue(m: MusicEnqueue) {
        val tracks = m.tracks.filter { isValidTrackId(it.id) }.map {
            Track(it.id, it.title, it.artist, it.album, it.durationMs, it.art ?: m.art)
        }
        Hub.log("client enqueue ${m.mode}: ${tracks.size} track(s)")
        enqueue(m.mode, tracks, Role.CLIENT)
    }

    private fun onClientEdit(m: MusicEdit) {
        val applied = when (m.op) {
            EditOp.CLEAR -> { music.clearUpcoming(); true }
            EditOp.JUMP -> m.index != null && m.id != null && jump(m.index, m.id, Role.CLIENT)
            EditOp.REMOVE -> m.index != null && m.id != null && music.remove(m.index, m.id)
            else -> false
        }
        if (!applied) Hub.log("client edit ${m.op} ${m.index} ignored: the queue changed")
    }

    // ---- play by touch (PROTOCOL.md "Browsing" step 3) ----

    /** An enqueue from either screen; a `now` one is a play by touch, which ends an open talk. */
    private fun enqueue(mode: String, tracks: List<Track>, by: String) {
        if (tracks.isEmpty()) return
        if (TouchPlay.enqueueEndsTalk(mode)) playByTouch(by) { music.enqueue(mode, tracks) } else music.enqueue(mode, tracks)
    }

    /** A jump from either screen: a play by touch. A stale one (the queue moved) changes nothing, talk included. */
    private fun jump(index: Int, id: String, by: String): Boolean {
        if (!music.canJump(index, id)) return false
        playByTouch(by) { music.jump(index, id) }
        return true
    }

    /**
     * [play] (a `now` enqueue or a jump, by [by]'s touch). With a talk open it ends that talk exactly
     * like a spoken `play` ([executeCommand]): the resume the talk held is dropped, the new track
     * starts no earlier than the usual resume lead, the talk closes as `talk.close{by, "trigger"}`,
     * and our own player is held over the switch back to media mode — the resume path's hold, since
     * here the queue changes at once rather than after a search.
     */
    private fun playByTouch(by: String, play: () -> Unit) {
        if (!talk.isOpen) return play()
        Hub.log("play by touch (by $by) ends the talk")
        music.beforePlayEndsTalk(clock() + settings.value.resumeLeadMs)
        talk.onCommandClose(by)?.let(::applyTalk)
        val closed = talkClosed
        sync.hold()
        play()
        scope.launch {
            closed?.join()
            sync.release(cold = true)
        }
    }

    /** The "downloaded" marks: which tracks the active cache holds. Main; after every download. */
    private fun refreshCached() {
        val ids = caches.active.ids()
        Hub.status.update { if (it.cached == ids) it else it.copy(cached = ids) }
    }

    /** The cache [refreshCached] last listed: the Opus one until a client cannot decode it. */
    private var cachedOpus = true

    private fun refreshStatus() {
        if (caches.opus != cachedOpus) {
            cachedOpus = caches.opus
            refreshCached()
        }
        val now = clock()
        Hub.status.update {
            it.copy(
                running = true,
                nsdName = discovery.registeredName.value,
                clientName = clientName,
                talkOpen = talk.isOpen,
                larkMissing = larkMissing,
                talkOnEarbudsFallback = talk.isOpen && talkOnEarbudsFallback,
                nowPlaying = music.current,
                playing = music.isPlaying,
                musicPhase = music.phase,
                // The anchor, not a position: equal from one second to the next, so an idle
                // second changes nothing here and the screens stay as they are (UA4).
                anchor = sync.anchor?.let { a -> PlaybackAnchor(a.positionMs, a.atHostTimeMs, a.playing) },
                queue = music.upcoming,
                outputRoute = outputRoute,
            )
        }
        Hub.diagnostics.value = Diagnostics(
            clientAddress = control.clientAddress?.hostAddress,
            clientSkewMs = control.lastPingSkewMs,
            lastPingAgeMs = if (control.hasClient()) now - control.lastRxAtMs else null,
            jitterTargetMs = voice.jitterTargetMs,
            underruns = voice.underruns,
            udpIn = voiceSocket.packetsIn.get(),
            udpOut = voiceSocket.packetsOut.get(),
            audioDevice = router.selectedDevice,
            audioDevices = devices.summary,
            lastDriftMs = sync.lastDriftMs,
            cacheMb = caches.active.sizeBytes() / (1024 * 1024),
        )
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
     * `MODE_IN_COMMUNICATION` is not treated as busy — that is the mode our own talk puts the
     * device in.
     *
     * Every check here is local: a permission lookup, a flag and the mode [AudioModeWatch] keeps
     * cached. Asking `AudioManager` for the mode directly would be a binder call into the audio
     * service, which our own `exitCall` can hold for over a second — and this runs on the path of
     * the client's `talk.open`, i.e. exactly then (2026-09-20 bench).
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
        if (audioMode.inPhoneCall) {
            Hub.log("microphone unavailable: phone call in progress")
            return false
        }
        return true
    }

    /**
     * Earcons never play on Main: building an `AudioTrack` (even a MODE_STATIC one) goes through
     * audioserver, which is busy for the whole of a route switch — up to ~1.3 s on the AirPods.
     * They go on the *audio thread* rather than a thread of their own, so their order against
     * `enterCall`/`exitCall` is defined: the closed and error tones are built after the route is
     * back (media mode) and the live one inside call mode, exactly as before. The price is that
     * an error earcon requested during a switch is heard after it, which for a "that did not
     * work" tone is fine.
     */
    private fun earcon(kind: Earcons.Kind, call: Boolean = false) {
        audio.post("earcon") { Earcons.play(kind, call) }
    }

    private fun hasMic() =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** Song results whose stream address is looked up before a tap (each is one extraction). */
        private const val PRE_RESOLVE_TOP = 3
        /** In a solo talk, phrases this soon after our own speech are taken for its echo. */
        private const val ECHO_MS = 2_000L
        /** Shorter than any spoken reply takes to be recognised after our question ends. */
        private const val ASK_ECHO_MS = 500L
        /** How long a spoken reply stays on the Ride tab. */
        private const val ANNOUNCE_SHOWN_MS = 6_000L
        /**
         * How long the capture thread waits for SCO before opening the recorder anyway (F9c). The
         * SCO link came up ~1.6–2.9 s after the press on the F8 bench, i.e. well inside this after
         * `enterCall`; past it the re-open is the backstop, and the live cue's 3.5 s fallback runs.
         */
        private const val CAPTURE_SCO_WAIT_MS = 2_500L
        /** 48 kHz stereo 16-bit is 192 kB/s: ~35 min per host-mic talk dump. */
        private const val LARK_DUMP_MAX_BYTES = 400L * 1024 * 1024
    }
}
