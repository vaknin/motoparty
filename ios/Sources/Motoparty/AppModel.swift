#if os(iOS)
import AVFoundation
import Combine
import Foundation
import MotopartyCore

enum LinkStatus: Equatable {
    case idle
    case searching
    case connecting(String)
    case connected(String)

    var label: String {
        switch self {
        case .idle: "Idle"
        case .searching: "Searching for host…"
        case .connecting(let name): "Connecting to \(name)…"
        case .connected(let name): "Connected to \(name)"
        }
    }

    var isConnected: Bool { if case .connected = self { true } else { false } }
}

/// One list of browse results as the UI shows it: the answer to the newest
/// request for this list, or that request still in flight.
struct ResultList: Equatable {
    var items: [ResultItem] = []
    var loading = false
    var error: String?
    /// Request id whose answer this list is waiting for (or showing).
    fileprivate var requestId: Int?

    /// A request was made (so an empty list means "no results", not "not yet").
    var requested: Bool { requestId != nil }
}

/// Owns every component and implements the client side of PROTOCOL.md:
/// discovery → control → voice socket; talk and music flows; commands
/// spoken inside a talk.
/// Everything here runs on the main queue.
final class AppModel: ObservableObject {
    // MARK: Published UI state
    @Published private(set) var link: LinkStatus = .idle {
        // The app owns the volume keys only while connected (2026-09-29):
        // a press is an app volume step, a hold of volume up toggles talk.
        didSet { volumeKey.isArmed = link.isConnected }
    }
    @Published private(set) var talkOpen = false
    @Published private(set) var talkRequested = false
    @Published private(set) var hostState: HostState?
    @Published private(set) var nowPlaying: MusicLoad? {
        didSet { if nowPlaying?.id != oldValue?.id { musicStatusTracker.setCurrent(nowPlaying?.id) } }
    }
    @Published private(set) var musicPlaying = false
    @Published private(set) var downloading: String?
    /// "Loading…" / "Paused for talk" under now playing (`MusicStatus`).
    @Published private(set) var musicStatus: MusicStatus = .none
    @Published private(set) var lastHeard: String?
    @Published private(set) var lastAnnouncement: String?
    @Published private(set) var problem: String?
    @Published private(set) var rttMs: Double?
    @Published private(set) var driftMs: Double?
    @Published private(set) var audioRoute = ""
    /// The app volume level (`AppVolume`, 0...`maxLevel`): what talk, music
    /// and cues play at while linked, and where the next link starts. The
    /// system volume reads 15/16 while linked, so this is the only true one.
    @Published private(set) var volumeLevel = AppVolume.defaultLevel
    /// Search tab results (PROTOCOL.md "Browsing").
    @Published private(set) var searchResults = ResultList()
    /// What the newest search looked for: songs are played, the rest browsed.
    @Published private(set) var searchedKind: SearchKind = .songs
    /// Songs of the album or playlist being browsed, and which one it is.
    @Published private(set) var collectionResults = ResultList()
    @Published private(set) var browsedCollection: ResultItem?
    /// Recent searches and recently played tracks, local to this phone.
    @Published private(set) var history = BrowseHistory() {
        didSet { if history != oldValue { settings.browseHistory = history } }
    }

    let settings = AppSettings()

    // MARK: Components
    private let clock = HostClock()
    private let discovery = Discovery()
    private var control: ControlClient?
    private var voice: VoiceSocket?
    private let session = SessionController()
    private let voiceEngine = VoiceEngine()
    private let keepAlive = KeepAlive()
    private let earcons = EarconPlayer()
    private let localVolume = LocalVolume()
    /// The passenger's pocket keys while connected: app volume steps, and a
    /// hold of volume up toggles talk.
    private lazy var volumeKey = VolumeKey(localVolume: localVolume)
    private let cache = TrackCache()
    /// The player runs ahead of the host timeline by the output delay it has to
    /// cover: what the audio session measures for the current route plus the
    /// user's own trim (PROTOCOL.md, "Music flow" step 4).
    private lazy var player = SyncedPlayer(clock: clock) { [weak self] in
        guard let self else { return 0 }
        return self.session.outputLatencyMs + self.settings.latencyTrimMs
    }
    private let nowPlayingCenter = NowPlaying()
    /// Speech recognition on the talk's own mic (PROTOCOL.md "Commands").
    private let transcriber = Transcriber()
    /// The first-phrase gate of a talk this phone opened, from its live
    /// earcon on; nil otherwise (nothing here is a command).
    private var firstPhrase: FirstPhraseGate?
    /// Stops recognition when the first-phrase window has passed unused.
    private var firstPhraseTimer: Timer?
    private let announcer = Announcer()

    // MARK: Link / music bookkeeping
    private var hostAddress: String?
    private var hostName = "host"
    private var voicePort = LinkDefaults.voicePort
    private var httpPort = LinkDefaults.httpPort
    private var loads: [String: MusicLoad] = [:]
    private var musicStatusTracker = MusicStatusTracker() {
        didSet {
            let status = musicStatusTracker.status
            if status != musicStatus { musicStatus = status }
        }
    }
    /// Tracks this connection has already answered with `music.ready`. A join
    /// re-sends music.load and state names the track too, and every answer
    /// makes the host re-send the anchor (a fresh A2DP seek), so answer once.
    private var readySent: Set<String> = []
    /// The host's current play anchor (from music.play or state), if playing.
    private var currentPlay: MusicPlay?
    private var started = false
    /// We have told the host we cannot open the mic for this talk; cleared by
    /// its `talk.close` or by the next TALK press.
    private var micUnavailable = false
    /// The host opened talk and we are waiting for the record-permission
    /// prompt. Cleared by the host's `talk.close` / `state{talk:false}`, so a
    /// late answer cannot open (or refuse) a talk that is already over.
    private var talkOpenPending = false
    private var statsTimer: Timer?
    /// Local monotonic time this talk opened, while its "live" earcon is still
    /// owed; nil once it has been played or the talk is over, so it is played
    /// at most once per talk open (Android's `LiveCue`, F7/F8/F9a).
    private var liveCueOpenedAtMs: Double?
    /// Safety net for the cue: a talk whose capture never delivers still beeps.
    private var liveCueTimer: Timer?
    /// Who opened the current talk, from the host's `talk.open{by}`. Nil for
    /// a talk learnt only from `state` (a join mid-talk): not ours, then.
    private var talkOpener: Role?
    /// How this phone takes part in the open talk (PROTOCOL.md "Host-mic
    /// talk"), fixed at its open; `.ownMic` while no talk is open.
    private var talkMode: TalkMode = .ownMic
    /// Last `music.search` / `music.browse` id; every request takes the next.
    private var lastRequestId = 0

    // MARK: - Lifecycle

    func start() {
        guard !started else { return }
        started = true
        wireCallbacks()
        do {
            try session.activate(.media)
        } catch {
            problem = "Audio session: \(error.localizedDescription)"
        }
        keepAlive.start()
        volumeLevel = settings.appVolumeLevel
        history = settings.browseHistory
        volumeKey.armLevel = volumeLevel
        volumeKey.start()
        // The volume slider needs the window and a layout pass: make it now,
        // long before the first park (the window is up by the next turn).
        DispatchQueue.main.async { [weak self] in self?.localVolume.prepare() }
        audioRoute = session.outputName
        // Ask for permissions at home, not on the road.
        session.requestRecordPermission { [weak self] granted in
            if !granted { self?.problem = "Microphone permission denied: talk works only with the host's mic" }
        }
        Transcriber.requestAuthorization { [weak self] granted in
            if !granted { self?.problem = "Speech recognition not authorised: voice commands disabled" }
        }
        statsTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.refreshStats()
        }
        startDiscovery()
    }

    func reconnect() {
        control?.stop(sendBye: true)
        linkLost(reason: "manual reconnect")
    }

    private func wireCallbacks() {
        discovery.onFound = { [weak self] candidate in self?.connect(to: candidate) }
        discovery.onWrongProto = { [weak self] name, proto in
            self?.problem = "\(name) speaks protocol \(proto), this app speaks \(Hello.currentProto): update one of them"
        }

        nowPlayingCenter.onButton = { [weak self] button in self?.remoteButton(button) }

        session.onInterruptionBegan = { [weak self] in self?.interruptionBegan() }
        session.onInterruptionEnded = { [weak self] shouldResume in self?.interruptionEnded(shouldResume) }
        session.onRouteChange = { [weak self] reason in self?.routeChanged(reason) }
        session.onMediaServicesReset = { [weak self] in self?.mediaServicesReset() }
        volumeKey.onHold = { [weak self] in self?.talkButton() }
        volumeKey.onGains = { [weak self] gains in self?.applyGains(gains) }
        volumeKey.onLevel = { [weak self] level in
            guard let self else { return }
            self.volumeLevel = level
            self.settings.appVolumeLevel = level
        }
        session.onMuteGesture = { [weak self] in
            guard let self else { return }
            // The earbuds sit inside the helmet: no gesture starts or ends a talk (2026-09-29).
            Log.app.info("remote button: mute gesture, talk=\(self.talkOpen) (ignored)")
        }

        voiceEngine.onFailure = { [weak self] error in
            // The mic (or, host-mic, the playback) is gone mid-talk: same
            // answer as failing to open it.
            self?.talkUnavailable(error.localizedDescription, weAsked: false)
        }

        // The capture sink delivered its first buffer (the mic is live) or,
        // in a host-mic talk, the playback is running: the earcon may mean it
        // (Android F7 — never a fixed delay).
        voiceEngine.onLive = { [weak self] in self?.playLiveCue(fallback: false) }

        player.onDrift = { [weak self] drift in self?.driftMs = drift }

        transcriber.onPhrase = { [weak self] phrase in self?.heard(phrase) }

        announcer.onSpeakingChanged = { [weak self] speaking in
            self?.player.duck = speaking ? 0.35 : 1.0
        }
    }

    /// One app volume for everything this phone plays (`AppVolume`): talk
    /// (with the limiter's boost at the top level), music, earcons and
    /// announcements.
    private func applyGains(_ gains: AppVolume.Gains) {
        voiceEngine.setGain(volume: gains.talkVolume, boostDb: gains.talkBoostDb)
        player.gain = gains.musicVolume
        earcons.volume = gains.cueVolume
        announcer.volume = gains.cueVolume
    }

    // MARK: - Link

    private func startDiscovery() {
        link = .searching
        discovery.start(preferredName: settings.lastHostName)
    }

    private func connect(to candidate: HostCandidate) {
        link = .connecting(candidate.name)
        hostName = candidate.name
        if let txt = candidate.txt {
            voicePort = txt.voicePort
            httpPort = txt.httpPort
        }
        // A new connection may be a different host (or a restarted one):
        // its clock and its view of our cache start from scratch.
        clock.reset()
        readySent = []
        let client = ControlClient(endpoint: candidate.endpoint, name: settings.deviceName, clock: clock)
        client.delegate = self
        control = client
        client.start()
    }

    private func startVoiceSocket() {
        voice?.stop()
        voice = nil
        guard let hostAddress else { return }
        let socket = VoiceSocket(host: hostAddress, port: voicePort)
        socket.onPacket = { [weak self] packet in self?.voiceEngine.receive(packet) }
        socket.start()
        voice = socket
    }

    private func linkLost(reason: String) {
        Log.app.info("link lost: \(reason, privacy: .public)")
        control = nil
        voice?.stop()
        voice = nil
        hostAddress = nil
        if talkOpen { closeTalkLocally() }
        talkRequested = false
        talkOpener = nil
        talkOpenPending = false
        micUnavailable = false
        musicStatusTracker.talkClosed()
        musicStatusTracker.linkLost()
        // An answer can no longer arrive for a request in flight.
        failPending(&searchResults, "Link lost")
        failPending(&collectionResults, "Link lost")
        // Music keeps playing locally along the last anchor (the host does the same).
        link = .searching
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
            guard let self, self.control == nil else { return }
            self.startDiscovery()
        }
    }

    private func send(_ message: ControlMessage) {
        control?.send(message)
    }

    // MARK: - Incoming messages

    private func handle(_ message: ControlMessage) {
        switch message {
        case .hello(let hello):
            guard hello.role == .host else { return }
            guard hello.proto == Hello.currentProto else {
                problem = "\(hello.name) speaks protocol \(hello.proto), this app speaks \(Hello.currentProto): update one of them"
                Log.link.error("hello.proto \(hello.proto) != \(Hello.currentProto); dropping the link")
                control?.stop(sendBye: true)
                control = nil
                link = .idle
                return
            }
            hostName = hello.name
            settings.lastHostName = hello.name
            link = .connected(hello.name)
            let newVoice = hello.voicePort ?? LinkDefaults.voicePort
            httpPort = hello.httpPort ?? LinkDefaults.httpPort
            if newVoice != voicePort || voice == nil {
                voicePort = newVoice
                startVoiceSocket()
            }
        case .state(let state):
            apply(state)
        case .talkOpen(let open):
            musicStatusTracker.talkOpened()
            talkOpener = open.by
            openTalkLocally(TalkMode(open: open))
        case .talkClose(let close):
            musicStatusTracker.talkClosed()
            talkOpener = nil
            micUnavailable = false
            talkOpenPending = false
            if talkOpen {
                closeTalkLocally()
            } else {
                // Answer to our own talk.open: talk never opened and
                // state.talk stayed false (PROTOCOL.md "Talk flow" steps 1
                // and 4), so there is no session to tear down and no music
                // that was paused — only the "requesting" state to clear.
                talkRefused(close.reason)
            }
        case .musicLoad(let load):
            // Also re-sent (current + next) right after a client joins.
            loads[load.id] = load
            musicStatusTracker.load(load.id)
            if nowPlaying?.id == load.id { nowPlaying = load }
            prefetch(load)
        case .musicPlay(let play):
            currentPlay = play
            musicPlaying = true
            musicStatusTracker.play(play.id)
            if nowPlaying?.id != play.id { nowPlaying = loads[play.id] ?? nowPlaying }
            startMusicIfPossible()
        case .musicPause(let pause):
            currentPlay = nil
            musicPlaying = false
            musicStatusTracker.pause(pause.id)
            player.pause(atMs: pause.positionMs)
            updateNowPlaying()
        case .musicStop:
            currentPlay = nil
            musicPlaying = false
            musicStatusTracker.stop()
            nowPlaying = nil
            player.stop()
            updateNowPlaying()
        case .announce(let announce):
            lastAnnouncement = announce.text
            if let earcon = announce.earcon { earcons.play(earcon) }
            announcer.speak(announce.text, language: settings.speechLanguage)
        case .musicResults(let results):
            receive(results)
        case .bye, .ping, .pong, .musicReady, .musicError, .musicControl, .commandText,
             .musicSearch, .musicBrowse, .musicEnqueue, .musicEdit, .unknown:
            break
        }
    }

    private func apply(_ state: HostState) {
        hostState = state
        // Talk state is authoritative on the host; heal missed messages.
        if !state.talk { talkOpenPending = false }
        // The host holds its music from its talk decision on, before this
        // state's music (already paused for the talk) is applied.
        if state.talk != musicStatusTracker.talkOpen {
            state.talk ? musicStatusTracker.talkOpened() : musicStatusTracker.talkClosed()
        }
        // A talk learnt from state (a join mid-talk) opens in the mode its
        // `mic` says, exactly as from talk.open.
        if state.talk != talkOpen { state.talk ? openTalkLocally(TalkMode(state: state)) : closeTalkLocally() }

        guard let music = state.music else {
            if currentPlay != nil || player.currentId != nil {
                currentPlay = nil
                player.stop()
            }
            musicPlaying = false
            musicStatusTracker.stop()
            nowPlaying = nil
            updateNowPlaying()
            return
        }

        if loads[music.id] == nil {
            // The host re-sends music.load after a join; until then use the fixed path.
            let load = MusicLoad(id: music.id, path: "/track/\(music.id).m4a", title: music.title,
                                 artist: music.artist, durationMs: music.durationMs)
            loads[music.id] = load
            musicStatusTracker.load(music.id)
            prefetch(load)
        }
        nowPlaying = loads[music.id]
        history.sawCurrent(BrowseHistory.Track(music))
        musicPlaying = music.playing
        musicStatusTracker.hostState(id: music.id, playing: music.playing)
        if music.playing {
            let play = MusicPlay(id: music.id, positionMs: music.positionMs, atHostTimeMs: music.atHostTimeMs)
            currentPlay = play
            startMusicIfPossible()
        } else if player.isPlaying || currentPlay != nil {
            currentPlay = nil
            player.pause(atMs: music.positionMs)
        }
        updateNowPlaying()
    }

    // MARK: - Music

    private func prefetch(_ load: MusicLoad) {
        guard let hostAddress else { return }
        if !cache.isCached(load.id) {
            downloading = load.title
            musicStatusTracker.downloadStarted(load.id)
        }
        cache.fetch(id: load.id, host: hostAddress, port: httpPort, path: load.path) { [weak self] outcome in
            guard let self else { return }
            if self.downloading == load.title { self.downloading = nil }
            if case .ready = outcome {
                self.musicStatusTracker.downloadFinished(load.id, ok: true)
            } else {
                self.musicStatusTracker.downloadFinished(load.id, ok: false)
            }
            switch outcome {
            case .ready:
                if self.readySent.insert(load.id).inserted {
                    self.send(.musicReady(MusicReady(id: load.id)))
                }
                if self.currentPlay?.id == load.id { self.startMusicIfPossible() }
            case .failed(let why):
                self.send(.musicError(MusicError(id: load.id, message: why)))
            }
        }
    }

    /// Plays `currentPlay` if the track is cached and nothing needs the mic.
    private func startMusicIfPossible() {
        guard let play = currentPlay, !talkOpen else { return }
        guard cache.isCached(play.id) else {
            if let load = loads[play.id] { prefetch(load) }
            return
        }
        player.load(id: play.id, url: cache.localURL(for: play.id))
        player.play(anchor: MusicAnchor(play), durationMs: loads[play.id]?.durationMs)
        updateNowPlaying()
    }

    private func updateNowPlaying() {
        let load = nowPlaying
        nowPlayingCenter.update(title: load?.title, artist: load?.artist, album: load?.album,
                                durationMs: load?.durationMs, positionMs: player.positionMs,
                                playing: musicPlaying && !talkOpen, talking: talkOpen)
    }

    // MARK: - Talk

    /// TALK, and a hold of volume up while connected (`VolumeKey`).
    func talkButton() {
        guard control != nil, link.isConnected else {
            earcons.play("error")
            return
        }
        if talkOpen {
            requestTalkClose()
        } else {
            talkRequested = true
            micUnavailable = false
            send(.talkOpen(TalkOpen(by: .client)))
        }
    }

    private func requestTalkClose(_ reason: TalkCloseReason = .trigger) {
        send(.talkClose(TalkClose(by: .client, reason: reason)))
    }

    /// The host could not open talk (`reason: "unavailable"`) or closed it
    /// before it ever opened. Nothing was set up, so nothing is torn down.
    private func talkRefused(_ reason: TalkCloseReason) {
        guard talkRequested else { return }
        talkRequested = false
        earcons.play("error")
        if reason == .unavailable { problem = "The other phone could not open its microphone" }
    }

    /// Host decided talk is open: pause music, switch the headset to call mode,
    /// open the mic, earcon when live. A host-mic talk instead keeps the media
    /// session, opens no mic and only plays (`openHostMicTalk`).
    private func openTalkLocally(_ mode: TalkMode) {
        let weAsked = talkRequested
        talkRequested = false
        guard !talkOpen else { return }
        // The host's state{talk:true} follows its talk.open, and may cross our
        // talk.close: don't fail (and earcon) twice for the same open.
        guard !micUnavailable else { return }
        if mode == .hostMic { return openHostMicTalk(weAsked: weAsked) }
        switch session.recordPermission {
        case .granted:
            break
        case .undetermined:
            // Never asked (or asked while the app was starting): ask now and
            // come back. A refusal is a "cannot", like any other. The host's
            // state{talk:true} follows its talk.open: ask only once.
            guard !talkOpenPending else { return }
            talkOpenPending = true
            session.requestRecordPermission { [weak self] granted in
                guard let self else { return }
                // The host closed talk (or the link dropped) while the prompt
                // was up: there is nothing left to open or to refuse.
                guard self.talkOpenPending else { return }
                self.talkOpenPending = false
                guard granted else { return self.talkUnavailable("microphone permission denied", weAsked: weAsked) }
                self.talkRequested = weAsked
                self.openTalkLocally(mode)
            }
            return
        default:
            talkUnavailable("microphone permission denied", weAsked: weAsked)
            return
        }
        talkOpen = true
        talkMode = .ownMic
        Log.audio.info("talk mode: \(TalkMode.ownMic.logLabel, privacy: .public)")
        announcer.stop()
        player.suspend()
        keepAlive.stop()
        volumeKey.settle()
        do {
            try session.activate(.talk)
            voice?.beginCapture()
            let transcriber = transcriber
            try voiceEngine.start(
                sendAudio: { [weak self] data in self?.voice?.sendAudio(data) },
                skipFrame: { [weak self] in self?.voice?.skipFrame() },
                tee: { buffer in transcriber.append(buffer) }
            )
            session.armMuteGesture()
            armLiveCue()
        } catch {
            // A call in progress, a route that failed, an engine that would
            // not start: this phone cannot talk.
            talkUnavailable(error.localizedDescription, weAsked: weAsked)
            return
        }
        audioRoute = session.outputName
        updateNowPlaying()
    }

    /// A host-mic talk (PROTOCOL.md "Host-mic talk"): the host captures both
    /// riders. No microphone here (no permission check, no input node, no
    /// recogniser, no audio sent: the voice socket's keepalives go on), and
    /// the session stays the media one (`.listen` = `.playback`), so the
    /// earbuds stay on A2DP. Music pauses and the rider's voice plays through
    /// the usual jitter buffer, decoder and app volume. "Cannot" here only
    /// means the playback would not start.
    private func openHostMicTalk(weAsked: Bool) {
        talkOpen = true
        talkMode = .hostMic
        Log.audio.info("talk mode: \(TalkMode.hostMic.logLabel, privacy: .public)")
        announcer.stop()
        player.suspend()
        keepAlive.stop()
        // Same session as music, so no volume jump is expected; settle anyway
        // (cheap), in case iOS still reports a change around the activation.
        volumeKey.settle()
        do {
            try session.activate(.listen)
            try voiceEngine.startReceiveOnly()
            armLiveCue()
        } catch {
            talkUnavailable(error.localizedDescription, weAsked: weAsked)
            return
        }
        audioRoute = session.outputName
        updateNowPlaying()
    }

    /// The fallback's delay, Android's `LiveCue.TIMEOUT_MS` (F9a).
    private static let liveCueTimeout: TimeInterval = 3.5

    /// The "live" earcon is owed from now on. It is played when the capture
    /// sink delivers its first buffer — the beep means "your mic is live, talk
    /// now", so it may never be a fixed delay (Android F7/F8/F9a) — and at the
    /// latest `liveCueTimeout` later, so a missing signal costs a late beep
    /// but never a silent one.
    private func armLiveCue() {
        liveCueTimer?.invalidate()
        liveCueOpenedAtMs = MonotonicClock.nowMs()
        liveCueTimer = Timer.scheduledTimer(withTimeInterval: Self.liveCueTimeout, repeats: false) { [weak self] _ in
            self?.playLiveCue(fallback: true)
        }
    }

    /// Whichever of the two came first wins; the talk beeps once or not at all.
    private func playLiveCue(fallback: Bool) {
        guard talkOpen, let openedAtMs = liveCueOpenedAtMs else { return }
        cancelLiveCue()
        let ms = Int((MonotonicClock.nowMs() - openedAtMs).rounded())
        let why = fallback ? "fallback" : talkMode.liveSignal
        Log.audio.info("live cue: fired +\(ms) ms (\(why, privacy: .public))")
        earcons.play("live")
        startRecognition()
    }

    /// A talk that ended before its microphone was live never gets its beep.
    private func cancelLiveCue() {
        liveCueTimer?.invalidate()
        liveCueTimer = nil
        liveCueOpenedAtMs = nil
    }

    /// This phone cannot open its microphone (PROTOCOL.md "Talk flow" step 1).
    /// Tell the host — which then closes talk — and go straight back to the
    /// music session. Talk is not negotiable: this is only ever "cannot".
    private func talkUnavailable(_ why: String, weAsked: Bool) {
        problem = talkMode == .hostMic ? "Could not play the talk: \(why)" : "Could not open the mic: \(why)"
        Log.audio.error("talk unavailable: \(why, privacy: .public)")
        micUnavailable = true
        cancelLiveCue()
        send(.talkClose(TalkClose(by: .client, reason: .unavailable)))
        if weAsked { earcons.play("error") }
        talkRequested = false
        talkOpen = false
        talkMode = .ownMic
        session.disarmMuteGesture()
        stopRecognition()
        voiceEngine.stop()
        restoreMediaRoute()
        updateNowPlaying()
        // Music stays held: the host paused its own music when it opened this
        // talk, so the last anchor no longer describes its timeline. It sends
        // music.play (now + resumeLeadMs) once it has closed talk, exactly as
        // after any other talk (PROTOCOL.md "Talk flow" step 4).
    }

    /// Host decided talk is over: back to A2DP. The host resumes music with
    /// music.play (resumeLeadMs covers the profile switch).
    private func closeTalkLocally() {
        talkRequested = false
        talkOpener = nil
        guard talkOpen else { return }
        talkOpen = false
        talkMode = .ownMic
        cancelLiveCue()
        session.disarmMuteGesture()
        stopRecognition()
        voiceEngine.stop()
        restoreMediaRoute()
        earcons.play("end")
        updateNowPlaying()
    }

    private func restoreMediaRoute() {
        volumeKey.settle()
        do { try session.activate(.media) } catch {
            Log.audio.error("media route failed: \(error.localizedDescription, privacy: .public)")
        }
        keepAlive.start()
        audioRoute = session.outputName
    }

    // MARK: - Commands inside talk

    /// The first phrase decides (PROTOCOL.md "Commands"): only in a talk this
    /// phone opened, and only its first phrase within `FIRST_PHRASE_MS` of the
    /// live earcon, which is now. The client is never solo, and in a talk the
    /// host opened it does not recognise at all.
    private func startRecognition() {
        // Host-mic: the host recognises the passenger's first phrase, and this
        // phone never sends command.text (it has no mic to recognise anyway).
        guard talkMode.recognisesCommands(opener: talkOpener) else { return }
        let now = MonotonicClock.nowMs()
        firstPhrase = FirstPhraseGate(role: .opener, liveAtMs: now)
        firstPhraseTimer?.invalidate()
        // A phrase still in progress at the deadline would arrive too late
        // anyway. A little past it, since the window's edge is inclusive.
        firstPhraseTimer = Timer.scheduledTimer(withTimeInterval: FirstPhraseGate.firstPhraseMs / 1000 + 0.05,
                                                repeats: false) { [weak self] _ in
            self?.stopRecognitionIfSpent()
        }
        // Recognition that fails costs the command, never the talk.
        transcriber.start(language: settings.speechLanguage)
    }

    /// One phrase recognised on this phone's mic during a talk it opened. A
    /// command only if it is the first phrase, in time, and parses;
    /// everything else is conversation, never sent or acted on.
    private func heard(_ phrase: String) {
        guard talkOpen, var gate = firstPhrase else { return }
        let kind = gate.classify(phrase, nowMs: MonotonicClock.nowMs())
        firstPhrase = gate
        Log.voice.info("heard: \"\(phrase, privacy: .public)\" (\(kind.label, privacy: .public))")
        if case .command(let text) = kind {
            lastHeard = text
            runCommand(text)
        }
        stopRecognitionIfSpent()
    }

    /// Nothing more this talk can be a command: stop listening.
    private func stopRecognitionIfSpent() {
        guard let gate = firstPhrase, gate.isSpent(atMs: MonotonicClock.nowMs()) else { return }
        Log.voice.info("first phrase spent: recognition off for this talk")
        stopRecognition()
    }

    /// Volume is local (PROTOCOL.md "Commands"): the same parser the host runs
    /// decides, and a volume result never leaves this phone. Everything else,
    /// `end` included, goes to the host, which decides what it does to the
    /// talk (play/resume/end close it with the usual talk.close).
    private func runCommand(_ text: String) {
        switch CommandParser.parse(text) {
        case .volumeUp:
            earcons.play(volumeKey.step(up: true) ? "ok" : "error")
        case .volumeDown:
            earcons.play(volumeKey.step(up: false) ? "ok" : "error")
        default:
            send(.commandText(CommandText(text: text, lang: settings.speechLanguage)))
        }
    }

    private func stopRecognition() {
        transcriber.stop()
        firstPhrase = nil
        firstPhraseTimer?.invalidate()
        firstPhraseTimer = nil
    }

    // MARK: - Buttons

    /// Lock screen, Control Center, or a headset: music only. The earbuds sit
    /// inside the helmet, so no button starts or ends a talk (2026-09-29).
    private func remoteButton(_ button: NowPlaying.Button) {
        Log.app.info("remote button: \(String(describing: button), privacy: .public), talk=\(self.talkOpen)")
        switch button {
        case .playPause: musicControl(musicPlaying ? .pause : .resume)
        // Buds send "pause" when taken out of the ear (a helmet coming off):
        // that must not stop the ride's music.
        case .pause: break
        case .next: musicControl(.next)
        case .previous: musicControl(.previous)
        }
    }

    func musicControl(_ action: MusicAction) {
        send(.musicControl(MusicControl(action: action)))
    }

    // MARK: - Browsing

    /// How long a search or browse may take before the list gives up. The host
    /// searches over the phone's mobile data, which can be slow on the road.
    private static let resultsTimeout: TimeInterval = 20

    /// Asks the host to search. Only the newest search's answer is shown.
    func search(_ kind: SearchKind, query: String) {
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return }
        searchedKind = kind
        if link.isConnected { history.searched(kind, query: query) }
        request(&searchResults, .musicSearch(MusicSearch(id: nextRequestId(), kind: kind, query: query)))
    }

    /// Asks the host for the songs of an album or playlist result.
    func browse(_ collection: ResultItem) {
        browsedCollection = collection
        request(&collectionResults, .musicBrowse(MusicBrowse(id: nextRequestId(), ref: collection.ref)))
    }

    /// Queues song results. From a collection, the tracks name it as their
    /// album and its cover stands in for tracks without their own.
    func enqueue(_ mode: EnqueueMode, songs: [ResultItem], from collection: ResultItem? = nil) {
        let tracks = songs.map {
            EnqueueTrack(id: $0.ref, title: $0.title, artist: $0.artist, album: collection?.title,
                         durationMs: $0.durationMs ?? 0, art: $0.art)
        }
        enqueue(mode, tracks: tracks, art: collection?.art)
    }

    /// Sent during a talk too: play by touch (`now`, or a `jump` edit) ends
    /// the talk on the host, which sends the usual `talk.close` (PROTOCOL.md
    /// "Browsing" step 3), so nothing here waits for the talk or closes it.
    func enqueue(_ mode: EnqueueMode, tracks: [EnqueueTrack], art: String? = nil) {
        let message = MusicEnqueue(mode: mode, tracks: tracks, art: art).fitted()
        guard !message.tracks.isEmpty else { return }
        send(.musicEnqueue(message))
    }

    /// Plays a track from the Search tab's history now.
    func playAgain(_ track: BrowseHistory.Track) {
        enqueue(.now, tracks: [track.enqueueTrack])
    }

    func clearRecentSearches() {
        history.clearSearches()
    }

    /// Changes the upcoming queue. `index`/`id` name `state.queue[index]` for
    /// jump and remove; the host ignores the edit if the queue moved since.
    func editQueue(_ op: QueueEditOp, index: Int? = nil, id: String? = nil) {
        send(.musicEdit(MusicEdit(op: op, index: index, id: id)))
    }

    private func nextRequestId() -> Int {
        lastRequestId += 1
        return lastRequestId
    }

    private func request(_ list: inout ResultList, _ message: ControlMessage) {
        let id: Int
        switch message {
        case .musicSearch(let m): id = m.id
        case .musicBrowse(let m): id = m.id
        default: return
        }
        guard link.isConnected, control != nil else {
            list = ResultList(error: "Not connected to the host")
            return
        }
        list = ResultList(loading: true, requestId: id)
        send(message)
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.resultsTimeout) { [weak self] in
            guard let self else { return }
            if self.searchResults.requestId == id { self.failPending(&self.searchResults, "The host did not answer") }
            if self.collectionResults.requestId == id { self.failPending(&self.collectionResults, "The host did not answer") }
        }
    }

    /// Answers to anything but a list's newest request are stale: dropped.
    private func receive(_ results: MusicResults) {
        func fill(_ list: inout ResultList) {
            list.items = results.items
            list.error = results.items.isEmpty ? results.error : nil
            list.loading = false
        }
        if searchResults.requestId == results.id { fill(&searchResults) }
        else if collectionResults.requestId == results.id { fill(&collectionResults) }
    }

    private func failPending(_ list: inout ResultList, _ why: String) {
        guard list.loading else { return }
        list.loading = false
        list.error = why
    }

    // MARK: - Audio session events

    private func interruptionBegan() {
        // Phone call, Siri, alarm… the system has stopped our audio.
        player.suspend()
        if talkOpen {
            // The mic is gone after talk opened (PROTOCOL.md "Talk flow" step 4).
            requestTalkClose(.unavailable)
            closeTalkLocally()
        }
    }

    private func interruptionEnded(_ shouldResume: Bool) {
        // interruptionBegan ended talk, so unless one has started since, the
        // session belongs in media mode — not in the route last activated
        // (.talk when switching back failed during the call).
        if talkOpen {
            session.reactivate()
            return
        }
        restoreMediaRoute()
        if shouldResume, currentPlay != nil { player.resume() }
    }

    private func routeChanged(_ reason: AVAudioSession.RouteChangeReason) {
        audioRoute = session.outputName
        volumeKey.settle()
        switch reason {
        case .oldDeviceUnavailable:
            // Headset gone: don't blast music out of the speaker.
            if !session.hasHeadphones { player.suspend() }
        case .newDeviceAvailable:
            if currentPlay != nil, !talkOpen { player.resume() }
        default:
            break
        }
    }

    private func mediaServicesReset() {
        Log.audio.error("media services were reset; rebuilding audio")
        stopRecognition()
        voiceEngine.stop()
        keepAlive.rebuild()
        if talkOpen {
            // The host is the authority on talk: tell it, as after an
            // interruption, instead of leaving it in a talk whose mic here
            // is gone until someone presses again.
            requestTalkClose(.unavailable)
            closeTalkLocally()
        } else {
            restoreMediaRoute()
        }
        if currentPlay != nil { player.resume() }
    }

    private func refreshStats() {
        rttMs = clock.rttMs
        audioRoute = session.outputName
    }

    // MARK: - UI helpers

    /// Current track position in ms, from the host anchor when synced.
    func displayPositionMs() -> Double? {
        guard let music = hostState?.music else { return nil }
        guard music.playing, let hostNow = clock.hostNowMs() else { return Double(music.positionMs) }
        return max(0, min(Double(music.durationMs), music.anchor.expectedPositionMs(hostNowMs: hostNow)))
    }

    func adjustTrim(by deltaMs: Double) {
        settings.latencyTrimMs = max(-500, min(1_000, settings.latencyTrimMs + deltaMs))
        if currentPlay != nil, player.isPlaying { player.resume() }
    }
}

// MARK: - ControlClientDelegate

extension AppModel: ControlClientDelegate {
    func controlDidConnect(_ client: ControlClient, hostAddress: String?) {
        guard client === control else { return }
        self.hostAddress = hostAddress
        link = .connected(hostName)
        startVoiceSocket()
    }

    func control(_ client: ControlClient, didReceive message: ControlMessage) {
        guard client === control else { return }
        handle(message)
    }

    func controlDidDisconnect(_ client: ControlClient, reason: String) {
        guard client === control else { return }
        linkLost(reason: reason)
    }
}
#endif
