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

    /// `lastHost`: the rider's phone by the name it last had.
    func label(lastHost: String?) -> String {
        switch self {
        case .idle: "Idle"
        case .searching: LinkWording.searching(lastHost: lastHost)
        case .connecting(let name): LinkWording.connecting(name)
        case .connected(let name): LinkWording.connected(name)
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

/// Numbers only Settings → Diagnostics reads, kept out of `AppModel` so that a
/// new round trip or drift value redraws nothing else (audit UI4).
final class LinkStats: ObservableObject {
    @Published fileprivate(set) var rttMs: Double?
    @Published fileprivate(set) var driftMs: Double?
    @Published fileprivate(set) var audioRoute = ""
    /// The output whose music sync offset Settings edits (`LatencyTrims`).
    @Published fileprivate(set) var outputRoute = OutputRoute.speaker
}

/// Owns every component and implements the client side of PROTOCOL.md:
/// discovery → control → voice socket; talk and music flows; commands
/// spoken inside a talk.
/// Everything here runs on the main queue.
final class AppModel: ObservableObject {
    // MARK: Published UI state
    @Published private(set) var link: LinkStatus = .idle {
        // The app owns the volume keys while connected (2026-09-29): a press
        // is an app volume step, a hold of volume up toggles talk. They stay
        // armed through a short link loss (`VolumeKeyArming`, audit H9).
        didSet {
            updateVolumeKeyArming()
            if link.isConnected { everLinked = true }
            updateLiveActivity()
        }
    }
    @Published private(set) var talkOpen = false
    /// The open talk's "live" earcon has played: its microphone (host-mic:
    /// its playback) is up. Until then the button says "Connecting…".
    @Published private(set) var talkLive = false
    /// When the open talk went live, for the running timer on TALK.
    @Published private(set) var talkLiveSince: Date?
    /// TALK was pressed and the host has not decided yet (at most
    /// `TalkPress.timeoutMs`; a second press takes the request back).
    @Published private(set) var talkRequested = false {
        didSet { if talkRequested != oldValue { armTalkRequestTimeout() } }
    }
    /// A phrase of the open talk can still be a command (its first-phrase
    /// window, or the reply to the host's question): Ride shows the command
    /// list in place of now playing, as Android does (`commandWindow`).
    @Published private(set) var commandWindow = false
    /// The host's latest `hello.interpret`: it interprets unparsed first
    /// phrases (smart commands), so Ride lists what those understand too.
    @Published private(set) var hostInterprets = false
    /// "Added to queue: …" after a touch enqueue (`Toast`), at the bottom of
    /// Search and Queue until `untilMs`.
    @Published private(set) var toast: Toast?
    @Published private(set) var hostState: HostState?
    @Published private(set) var nowPlaying: MusicLoad? {
        didSet { if nowPlaying?.id != oldValue?.id { musicStatusTracker.setCurrent(nowPlaying?.id) } }
    }
    @Published private(set) var musicPlaying = false
    /// "Downloading song…" / "Paused for talk" under now playing (`MusicStatus`).
    @Published private(set) var musicStatus: MusicStatus = .none
    /// The command this phone heard, and the host's last announcement: each
    /// shown for `Notice.transientLineMs`, then gone (audit UI1).
    @Published private(set) var lastHeard: String? {
        didSet { expireLine(lastHeard, "heard") { $0.lastHeard = nil } }
    }
    @Published private(set) var lastAnnouncement: String? {
        didSet { expireLine(lastAnnouncement, "announcement") { $0.lastAnnouncement = nil } }
    }
    /// The problem line on Ride: dismissed by the user, or cleared by the
    /// next success of what it is about (`Notice.isCleared`).
    @Published private(set) var problem: Notice?
    /// The passenger refused a permission: Ride shows a card with "Open
    /// Settings". Talk still works with the rider's microphone set.
    @Published private(set) var micDenied = false
    @Published private(set) var speechDenied = false
    /// The rider's phone, by the name it last had ("Looking for …").
    @Published private(set) var lastHostName: String?
    /// The headset went away while music could play: this phone keeps its
    /// music silent (never the pocket loudspeaker) until a headset is back or
    /// the passenger presses Play here. The host's music is not touched.
    @Published private(set) var musicHeldForRoute = false
    /// The app volume level (`AppVolume`, 0...`maxLevel`): what talk, music
    /// and cues play at while linked, and where the next link starts. The
    /// system volume reads 15/16 while linked, so this is the only true one.
    @Published private(set) var volumeLevel = AppVolume.defaultLevel
    /// Search tab results (PROTOCOL.md "Browsing").
    @Published private(set) var searchResults = ResultList()
    /// What the newest search looked for: songs are played, the rest browsed.
    @Published private(set) var searchedKind: SearchKind = .songs
    /// The words of the newest search ("Nothing found for …", Try again).
    @Published private(set) var searchedQuery = ""
    /// Songs of the album or playlist being browsed, and which one it is.
    @Published private(set) var collectionResults = ResultList()
    @Published private(set) var browsedCollection: ResultItem?
    /// The host's cached tracks (the song rows' marks) and its album and
    /// playlist downloads (PROTOCOL.md "Browsing" step 6).
    @Published private(set) var hostDownloads = HostDownloads()
    /// Recent searches and recently played tracks, local to this phone.
    @Published private(set) var history = BrowseHistory() {
        didSet { if history != oldValue { settings.browseHistory = history } }
    }

    let settings = AppSettings()
    let stats = LinkStats()

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
    /// cover: the user's own trim (PROTOCOL.md, "Music flow" step 4) plus,
    /// while the setting says so, what the audio session measures for the
    /// current route (audit M2).
    private lazy var player = SyncedPlayer(clock: clock) { [weak self] in
        self?.outputDelay() ?? OutputDelay(outputLatencyMs: 0, trimMs: 0, compensate: false)
    }
    private let nowPlayingCenter = NowPlaying()
    /// The Live Activity (lock screen, Dynamic Island): display only.
    private let liveActivity = LiveActivityController()
    /// Linked at least once since the app started: from then on the Live
    /// Activity is up, also with nothing playing.
    private var everLinked = false
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
    /// The host whose clock the estimate in `clock` belongs to.
    private var clockHostName: String?
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
    @Published private(set) var talkMode: TalkMode = .ownMic
    /// Last `music.search` / `music.browse` id; every request takes the next.
    private var lastRequestId = 0
    private var arming = VolumeKeyArming()
    private var armingTimer: Timer?
    private var talkRequestedAtMs: Double = 0
    private var talkRequestTimer: Timer?
    /// A spoken volume command's close of its talk, waiting for the `ok` earcon.
    private var volumeClose: DispatchWorkItem?
    /// A spoken volume command that waits for the media route (`ClientCommand`).
    private var mediaVolumeStep: Bool?
    /// The address of the current or last link, probed first after a link
    /// loss (PROTOCOL.md "Discovery" step 5), and when that link came up.
    private var lastLinkedAddress: String?
    private var connectedAtMs: Double = 0
    /// The switch back to the media session, held while the "end" earcon
    /// plays on the talk route (audit M10).
    private var mediaRestore: DispatchWorkItem?
    /// A `music.play` arrived during that hold: start once the session is back.
    private var musicWaitsForMediaRoute = false
    /// A `music.next` that came while the start of its `music.play` was still
    /// waiting (download, the end-earcon hold): queued right after that start.
    private var heldNext: MusicNext?
    /// Where the host parked the current track (`music.pause`, a paused
    /// `state.music`); nil while it plays along `currentPlay`.
    private var parkedAtMs: Double?
    private var lineTimers: [String: Timer] = [:]
    /// Voids the output-delay re-reads of an earlier route switch.
    private var rereadGeneration = 0

    // MARK: - Lifecycle

    func start() {
        guard !started else { return }
        started = true
        wireCallbacks()
        do {
            try session.activate(.media)
        } catch {
            problem = Notice(.audio, "Audio session: \(error.localizedDescription)")
        }
        keepAlive.start()
        volumeLevel = settings.appVolumeLevel
        history = settings.browseHistory
        lastHostName = settings.lastHostName
        volumeKey.armLevel = volumeLevel
        volumeKey.start()
        // The volume slider needs the window and a layout pass: make it now,
        // long before the first park (the window is up by the next turn).
        DispatchQueue.main.async { [weak self] in self?.localVolume.prepare() }
        noteAudioRoute()
        // Ask for permissions at home, not on the road.
        session.requestRecordPermission { [weak self] _ in self?.refreshPermissions() }
        Transcriber.requestAuthorization { [weak self] _ in self?.refreshPermissions() }
        statsTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.refreshStats()
        }
        startDiscovery()
    }

    func reconnect() {
        // The user asks: a host set aside for its protocol version gets
        // another try (it may have been updated).
        discovery.unblock()
        clearProblem(on: .reconnectAsked)
        control?.stop(sendBye: true)
        linkLost(reason: "manual reconnect")
    }

    private func wireCallbacks() {
        discovery.onFound = { [weak self] candidate in self?.connect(to: candidate) }
        discovery.onWrongProto = { [weak self] name, proto in
            self?.showProtoMismatch("\(name) speaks protocol \(proto), this app speaks \(Hello.currentProto): update one of them. Reconnect in Settings tries it again")
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
            if self.volumeLevel != level { self.volumeLevel = level }
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

        player.onDrift = { [weak self] drift in
            guard let self else { return }
            // Whole milliseconds are all Diagnostics shows.
            let rounded = drift.rounded()
            if self.stats.driftMs != rounded { self.stats.driftMs = rounded }
        }
        // The queued track took over (PROTOCOL.md "Music flow" step 6),
        // slightly before the host's state / music.play says so, or without
        // them when the link is down.
        player.onAdvance = { [weak self] id, anchor in
            guard let self else { return }
            self.currentPlay = MusicPlay(id: id, positionMs: anchor.positionMs, atHostTimeMs: anchor.atHostTimeMs)
            self.parkedAtMs = nil
            self.musicStatusTracker.play(id)
            if self.nowPlaying?.id != id, let load = self.loads[id] { self.nowPlaying = load }
            self.updateNowPlaying()
        }
        player.onQueueFailed = { [weak self] in self?.startMusicIfPossible() }

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

    private func startDiscovery(lastAddress: String? = nil) {
        link = .searching
        discovery.start(preferredName: settings.lastHostName, lastAddress: lastAddress)
    }

    private func updateVolumeKeyArming() {
        let now = MonotonicClock.nowMs()
        let searching: Bool
        switch link {
        case .searching, .connecting: searching = true
        case .idle, .connected: searching = false
        }
        armingTimer?.invalidate()
        armingTimer = nil
        if let expireAtMs = arming.link(connected: link.isConnected, searching: searching, nowMs: now) {
            armingTimer = Timer.scheduledTimer(withTimeInterval: max(0, expireAtMs - now) / 1000 + 0.01,
                                               repeats: false) { [weak self] _ in
                guard let self, self.arming.expire(nowMs: MonotonicClock.nowMs()) else { return }
                self.volumeKey.isArmed = false
            }
        }
        if volumeKey.isArmed != arming.armed { volumeKey.isArmed = arming.armed }
    }

    private func connect(to candidate: HostCandidate) {
        link = .connecting(candidate.name)
        hostName = candidate.name
        if let txt = candidate.txt {
            voicePort = txt.voicePort
            httpPort = txt.httpPort
        }
        // A new connection's view of our cache starts from scratch. The clock
        // window is kept for the same host (PROTOCOL.md "Clock"; a rebooted
        // one steps by far more than the reset threshold) and dropped for
        // another.
        noteClockHost(candidate.name)
        readySent = []
        let client = ControlClient(endpoint: candidate.endpoint, name: settings.deviceName, clock: clock)
        client.delegate = self
        control = client
        client.start()
    }

    private func noteClockHost(_ name: String) {
        guard name != clockHostName else { return }
        clockHostName = name
        clock.reset()
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
        // A link that held is looked for again at once; one that fell right
        // away (or never came up) waits a second, so a host that accepts and
        // drops cannot make this spin.
        let held = link.isConnected && MonotonicClock.nowMs() - connectedAtMs >= 1_000
        control = nil
        // The voice engine first: its capture queue sends on the socket.
        if talkOpen { closeTalkLocally() }
        voice?.stop()
        voice = nil
        hostAddress = nil
        assign(\.hostInterprets, false)
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
        let lastAddress = lastLinkedAddress
        if held { return startDiscovery(lastAddress: lastAddress) }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
            guard let self, self.control == nil, !self.link.isConnected, self.link != .idle else { return }
            self.startDiscovery(lastAddress: lastAddress)
        }
    }

    private func send(_ message: ControlMessage) {
        control?.send(message)
    }

    // MARK: - Incoming messages

    private func showProtoMismatch(_ text: String) {
        problem = Notice(.link, text)
    }

    /// PROTOCOL.md "Control channel": the host speaks another protocol
    /// version. Shown, and that host is left alone until the user taps
    /// Reconnect; discovery goes on for other hosts.
    private func protoMismatch(_ message: ControlMessage) {
        let name: String
        let theirs: String
        if case .hello(let hello) = message {
            name = hello.name
            theirs = "speaks protocol \(hello.proto)"
        } else {
            name = hostName
            theirs = "refused this app's protocol"
        }
        showProtoMismatch("\(name) \(theirs), this app speaks \(Hello.currentProto): update one of them. Reconnect in Settings tries it again")
        Log.link.error("protocol mismatch with \(name, privacy: .public); not connecting to it again until asked")
        discovery.block(name: name, address: hostAddress ?? lastLinkedAddress)
        if name != hostName { discovery.block(name: hostName, address: nil) }
        // Not the address to look for first either.
        lastLinkedAddress = nil
        control?.stop(sendBye: true)
        linkLost(reason: "protocol mismatch")
    }

    private func handle(_ message: ControlMessage) {
        if ProtoMismatch.isMismatch(message) { return protoMismatch(message) }
        switch message {
        case .hello(let hello):
            guard hello.role == .host else { return }
            hostName = hello.name
            noteClockHost(hello.name)
            settings.lastHostName = hello.name
            if lastHostName != hello.name { lastHostName = hello.name }
            link = .connected(hello.name)
            clearProblem(on: .connected)
            let newVoice = hello.voicePort ?? LinkDefaults.voicePort
            httpPort = hello.httpPort ?? LinkDefaults.httpPort
            assign(\.hostInterprets, hello.interpret ?? false)
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
            if nowPlaying?.id == load.id, nowPlaying != load {
                nowPlaying = load
                // The album (and any corrected title) reaches the lock screen.
                updateNowPlaying()
            }
            prefetch(load)
        case .musicPlay(let play):
            currentPlay = play
            parkedAtMs = nil
            heldNext = nil
            assign(\.musicPlaying, true)
            musicStatusTracker.play(play.id)
            if nowPlaying?.id != play.id { nowPlaying = loads[play.id] ?? nowPlaying }
            startMusicIfPossible(source: .message)
            // Also when the track is still downloading or the route holds it:
            // the lock screen follows the host's timeline (audit UI6).
            updateNowPlaying()
        case .musicPause(let pause):
            // Also the end of the queue: the host parks the last track at 0,
            // and it stays loaded here, paused at 0.
            currentPlay = nil
            parkedAtMs = Double(pause.positionMs)
            heldNext = nil
            assign(\.musicPlaying, false)
            musicStatusTracker.pause(pause.id)
            player.pause(atMs: pause.positionMs)
            updateNowPlaying()
        case .musicNext(let next):
            // Queued behind the playing track when it is here and decodable
            // (the cache only keeps checked files); otherwise it starts on
            // its music.play, as before (PROTOCOL.md "Music flow" step 6).
            heldNext = queueNext(next) || currentPlay == nil || talkOpen ? nil : next
        case .musicStop:
            currentPlay = nil
            parkedAtMs = nil
            heldNext = nil
            assign(\.musicPlaying, false)
            musicStatusTracker.stop()
            assign(\.nowPlaying, nil)
            player.stop()
            updateNowPlaying()
        case .announce(let announce):
            lastAnnouncement = announce.text
            if let earcon = announce.earcon { earcons.play(earcon) }
            announcer.speak(announce.text, language: settings.speechLanguage)
            if announce.ask == true { awaitReply() }
        case .musicResults(let results):
            receive(results)
        case .musicDownloads(let downloads):
            assign(\.hostDownloads, HostDownloads(downloads))
        case .bye, .ping, .pong, .musicReady, .musicError, .musicControl, .commandText,
             .musicSearch, .musicBrowse, .musicEnqueue, .musicEdit, .musicDownload, .unknown:
            break
        }
    }

    private func apply(_ state: HostState) {
        // Most states repeat the last one: only a real change redraws (UI4).
        assign(\.hostState, state)
        // The host's voice search, for the status line (absent: none runs).
        if state.busy != musicStatusTracker.busy { musicStatusTracker.hostBusy(state.busy) }
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

        // Before anything else: a state that is not the playing current or
        // queued track takes the queued one out (PROTOCOL.md "Music flow" 6).
        player.hostState(musicId: state.music?.id, playing: state.music?.playing ?? false)
        if state.music?.playing != true { heldNext = nil }

        guard let music = state.music else {
            if currentPlay != nil || player.currentId != nil {
                currentPlay = nil
                player.stop()
            }
            parkedAtMs = nil
            assign(\.musicPlaying, false)
            musicStatusTracker.stop()
            assign(\.nowPlaying, nil)
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
        assign(\.nowPlaying, loads[music.id])
        var seen = history
        if seen.sawCurrent(BrowseHistory.Track(music)) { history = seen }
        assign(\.musicPlaying, music.playing)
        musicStatusTracker.hostState(id: music.id, playing: music.playing)
        parkedAtMs = music.playing ? nil : Double(music.positionMs)
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
            musicStatusTracker.downloadStarted(load.id)
        }
        cache.fetch(id: load.id, host: hostAddress, port: httpPort, path: load.path) { [weak self] outcome in
            guard let self else { return }
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
    /// `source`: `.message` only for the `music.play` message itself, which
    /// also takes a queued track back; every re-application is `.state`.
    private func startMusicIfPossible(source: GaplessTracker.PlaySource = .state) {
        guard let play = currentPlay, !talkOpen else { return }
        // Held for the route: only a headset (or Play on this phone) ends it.
        // Asked here too, in case no route change announced the headset.
        if musicHeldForRoute, session.hasHeadphones { musicHeldForRoute = false }
        guard !musicHeldForRoute else { return updateNowPlaying() }
        guard cache.isCached(play.id) else {
            if let load = loads[play.id] { prefetch(load) }
            return
        }
        // The "end" earcon still has the talk session: start on the media
        // one (the anchor is resumeLeadMs ahead, so nothing is lost).
        guard mediaRestore == nil else {
            musicWaitsForMediaRoute = true
            return
        }
        player.play(id: play.id, url: cache.localURL(for: play.id), anchor: MusicAnchor(play),
                    durationMs: loads[play.id]?.durationMs, source: source)
        // The host sends music.next right after a music.play (talk end,
        // mid-track join): one that came before this start could happen.
        if let next = heldNext {
            heldNext = nil
            _ = queueNext(next)
        }
        updateNowPlaying()
    }

    /// False when the player has nothing playing or starting to queue behind.
    private func queueNext(_ next: MusicNext) -> Bool {
        let ready = !talkOpen && !musicHeldForRoute && cache.isCached(next.id)
        return player.queueNext(next, url: ready ? cache.localURL(for: next.id) : nil,
                                durationMs: loads[next.id]?.durationMs)
    }

    private func outputDelay() -> OutputDelay {
        OutputDelay(outputLatencyMs: session.outputLatencyMs, trimMs: settings.trims.of(session.outputRoute),
                    compensate: settings.compensateOutputLatency, route: session.describe)
    }

    /// For the click-track session that decides audit M2.
    private func logOutputDelay(_ when: String) {
        let delay = outputDelay()
        let target = player.targetPositionMs.map { "\(Int($0)) ms" } ?? "none"
        Log.music.info("output delay \(when, privacy: .public): outputLatency \(delay.outputLatencyMs, format: .fixed(precision: 1)) ms (\(delay.compensate ? "counted" : "not counted", privacy: .public)), trim \(Int(delay.trimMs)) ms, ahead by \(Int(delay.ms)) ms, target \(target, privacy: .public), route \(delay.route, privacy: .public)")
    }

    /// Right after the session switches back to media, `outputLatency` can
    /// still be the old (HFP) route's. Read it again once the route has had
    /// time to settle, and let the player fix a start planned on the old one.
    private func rereadOutputDelayWhenSettled() {
        rereadGeneration += 1
        let generation = rereadGeneration
        logOutputDelay("at the switch to media")
        for seconds in [1.0, 2.5, 5.0] {
            DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { [weak self] in
                guard let self, generation == self.rereadGeneration, !self.talkOpen else { return }
                self.logOutputDelay("+\(seconds) s")
                self.player.rereadOutputDelay()
            }
        }
    }

    /// The lock screen and Control Center: the host's timeline, not the local
    /// player's (which is not there yet while a track downloads).
    private func updateNowPlaying() {
        let track = nowPlaying
        let art = hostState?.music.flatMap { $0.id == track?.id ? $0.art : nil }
        nowPlayingCenter.update(LockScreenInfo(track: track, art: art, talking: talkOpen, riderName: hostName,
                                               positionMs: trackPositionMs() ?? 0,
                                               playing: musicPlaying && !musicHeldForRoute))
        updateLiveActivity()
    }

    /// Called on every change of what it shows; the controller only tells
    /// ActivityKit about a state that differs from the one that is up.
    private func updateLiveActivity() {
        let linked: LiveActivityState.Link = link.isConnected ? .linked : everLinked && link != .idle ? .lost : .none
        liveActivity.show(LiveActivityState(track: nowPlaying, playing: musicPlaying && !musicHeldForRoute,
                                            talkOpen: talkOpen, talkLive: talkLive, talkLiveSince: talkLiveSince,
                                            riderName: link.isConnected ? hostName : lastHostName ?? "",
                                            link: linked))
    }

    /// Where the current track is on the host's timeline; nil with no track.
    private func trackPositionMs() -> Double? {
        guard let track = nowPlaying else { return nil }
        let anchor = currentPlay.flatMap { $0.id == track.id ? MusicAnchor($0) : nil }
        return PlaybackPosition.ms(anchor: anchor, hostNowMs: clock.hostNowMs(),
                                   parkedMs: parkedAtMs ?? player.positionMs, durationMs: track.durationMs)
    }

    /// Rejoins the host's timeline after a local suspend (interruption,
    /// media reset, trim change), unless talk or the route holds the music.
    private func resumeMusic() {
        guard currentPlay != nil, !talkOpen, !musicHeldForRoute else { return }
        player.resume()
    }

    /// The passenger asked for music on this phone: whatever the route is.
    private func releaseRouteHold() {
        guard musicHeldForRoute else { return }
        Log.audio.info("music: route hold released by the user")
        musicHeldForRoute = false
        startMusicIfPossible()
    }

    /// The Ride screen's play/pause button. While the route holds the music
    /// it is a Play for this phone only; the host keeps playing as it was.
    func playPauseButton() {
        if musicHeldForRoute, musicPlaying { return releaseRouteHold() }
        musicControl(musicPlaying ? .pause : .resume)
    }

    // MARK: - Talk

    /// TALK, and a hold of volume up while connected (`VolumeKey`).
    func talkButton() {
        guard control != nil, link.isConnected else {
            earcons.play("error")
            return
        }
        switch TalkPress.action(talkOpen: talkOpen, requested: talkRequested) {
        case .close:
            requestTalkClose()
        case .cancelRequest:
            // Pressed again before the host decided (PROTOCOL.md "Talk flow"
            // step 1): the host, if it opened the talk meanwhile, closes it.
            talkRequested = false
            requestTalkClose()
        case .request:
            talkRequested = true
            micUnavailable = false
            send(.talkOpen(TalkOpen(by: .client)))
        }
    }

    /// A request the host never answers is dropped after `TalkPress.timeoutMs`
    /// (the button is TALK again); any answer, or a second press, ends the wait.
    private func armTalkRequestTimeout() {
        talkRequestTimer?.invalidate()
        talkRequestTimer = nil
        guard talkRequested else { return }
        talkRequestedAtMs = MonotonicClock.nowMs()
        talkRequestTimer = Timer.scheduledTimer(withTimeInterval: TalkPress.timeoutMs / 1000 + 0.01,
                                                repeats: false) { [weak self] _ in
            guard let self, self.talkRequested,
                  TalkPress.isExpired(requestedAtMs: self.talkRequestedAtMs, nowMs: MonotonicClock.nowMs()) else { return }
            Log.app.info("talk request unanswered: dropped")
            self.talkRequested = false
            // The phone is in a pocket: say that nothing opened.
            self.earcons.play("error")
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
        if reason == .unavailable { problem = Notice(.talk, "\(hostName) could not open its microphone") }
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
        cancelMediaRestore()
        Log.audio.info("talk mode: \(TalkMode.ownMic.logLabel, privacy: .public)")
        announcer.stop()
        player.suspend()
        keepAlive.stop()
        volumeKey.settle()
        do {
            try session.activate(.talk)
            // This talk's socket, fixed here: the capture queue must not read
            // `voice`, which the main queue clears when the link drops.
            let socket = voice
            socket?.beginCapture()
            let transcriber = transcriber
            try voiceEngine.start(
                sendAudio: { data in socket?.sendAudio(data) },
                skipFrame: { socket?.skipFrame() },
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
        noteAudioRoute()
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
        // An own-mic talk that closed a moment ago may still hold the talk
        // session for its earcon: this one runs on the media session.
        if mediaRestore != nil { restoreMediaRoute() }
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
        noteAudioRoute()
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
        // The beep is a setting (Settings → "Beep when the mic is live"),
        // on by default (unlike Android); TALK turns red either way. The live
        // moment itself (recognition, the timer) does not depend on it.
        if settings.liveBeep { earcons.play("live") }
        talkLive = true
        talkLiveSince = Date()
        updateLiveActivity()
        clearProblem(on: .talkOpened)
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
        problem = Notice(.talk, talkMode == .hostMic ? "Could not play the talk: \(why)" : "Could not open the mic: \(why)")
        Log.audio.error("talk unavailable: \(why, privacy: .public)")
        micUnavailable = true
        cancelLiveCue()
        send(.talkClose(TalkClose(by: .client, reason: .unavailable)))
        if weAsked { earcons.play("error") }
        talkRequested = false
        talkOpen = false
        talkMode = .ownMic
        endTalkLive()
        // The permission may be what failed: the card says what to do.
        refreshPermissions()
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

    /// How long the talk session is kept for the "end" earcon (190 ms) after
    /// an own-mic talk.
    private static let endCueHold: TimeInterval = 0.22

    /// Host decided talk is over: back to A2DP. The host resumes music with
    /// music.play (resumeLeadMs covers the profile switch).
    ///
    /// The "end" earcon of an own-mic talk plays on the talk route, which is
    /// up, and the session goes back to media right after it (audit M10):
    /// played after the switch it fell into the ~1 s the buds take to bring
    /// A2DP back. Waiting for A2DP instead would put the cue on top of the
    /// resuming music; this costs 0.22 s of the host's 1.5 s resume lead and
    /// moves no anchor. `cue: false` (an interruption, a media reset): no
    /// earcon can play, switch at once.
    private func closeTalkLocally(cue: Bool = true) {
        talkRequested = false
        talkOpener = nil
        volumeClose?.cancel()
        volumeClose = nil
        guard talkOpen else { return }
        let ownMic = talkMode == .ownMic
        talkOpen = false
        talkMode = .ownMic
        endTalkLive()
        cancelLiveCue()
        session.disarmMuteGesture()
        stopRecognition()
        voiceEngine.stop()
        if cue, ownMic {
            earcons.play("end")
            let work = DispatchWorkItem { [weak self] in self?.restoreMediaRoute() }
            mediaRestore = work
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.endCueHold, execute: work)
        } else {
            // A host-mic talk never left the media session.
            restoreMediaRoute()
            if cue { earcons.play("end") }
        }
        updateNowPlaying()
    }

    private func cancelMediaRestore() {
        mediaRestore?.cancel()
        mediaRestore = nil
        musicWaitsForMediaRoute = false
    }

    private func restoreMediaRoute() {
        let musicWaits = musicWaitsForMediaRoute
        cancelMediaRestore()
        defer {
            rereadOutputDelayWhenSettled()
            if musicWaits { startMusicIfPossible() }
        }
        volumeKey.settle()
        do { try session.activate(.media) } catch {
            Log.audio.error("media route failed: \(error.localizedDescription, privacy: .public)")
        }
        if let up = mediaVolumeStep {
            // A volume command spoken while disarmed: the media volume now.
            mediaVolumeStep = nil
            earcons.play(volumeKey.step(up: up) ? "ok" : "error")
        }
        keepAlive.start()
        noteAudioRoute()
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
        firstPhrase = FirstPhraseGate(role: .opener, liveAtMs: now, interpret: hostInterprets)
        assign(\.commandWindow, true)
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
    /// command only if it is the first phrase, in time, and parses (or the
    /// host interprets, and decides); everything else is conversation, never
    /// sent or acted on.
    private func heard(_ phrase: String) {
        guard talkOpen, var gate = firstPhrase else { return }
        let kind = gate.classify(phrase, nowMs: MonotonicClock.nowMs())
        firstPhrase = gate
        Log.voice.info("heard: \"\(phrase, privacy: .public)\" (\(kind.label, privacy: .public))")
        switch kind {
        case .command(let text):
            lastHeard = text
            runCommand(text)
        case .reply(let text):
            // The host's to understand: not parsed here, volume words included.
            lastHeard = text
            send(.commandText(CommandText(text: text, lang: settings.speechLanguage)))
        case .conversation:
            break
        }
        stopRecognitionIfSpent()
    }

    /// The host asked a clarifying question (PROTOCOL.md "The clarifying
    /// question"): in a talk this phone opened, its next phrase within
    /// `ANSWER_MS` is the reply, so recognition (stopped once the first
    /// phrase was spent) runs again for that one phrase.
    private func awaitReply() {
        guard talkOpen, talkMode.recognisesCommands(opener: talkOpener) else { return }
        let now = MonotonicClock.nowMs()
        var gate = FirstPhraseGate(role: .opener, liveAtMs: now, interpret: true)
        gate.ask(atMs: now)
        firstPhrase = gate
        assign(\.commandWindow, true)
        firstPhraseTimer?.invalidate()
        firstPhraseTimer = Timer.scheduledTimer(withTimeInterval: FirstPhraseGate.answerMs / 1000 + 0.05,
                                                repeats: false) { [weak self] _ in
            self?.stopRecognitionIfSpent()
        }
        Log.voice.info("question from the host: listening for the reply")
        transcriber.start(language: settings.speechLanguage)
    }

    /// Nothing more this talk can be a command: stop listening.
    private func stopRecognitionIfSpent() {
        guard let gate = firstPhrase, gate.isSpent(atMs: MonotonicClock.nowMs()) else { return }
        Log.voice.info("first phrase spent: recognition off for this talk")
        stopRecognition()
    }

    /// The `ok` earcon (100 ms) of a spoken volume command plays on the talk
    /// route before the close is asked for, so the "end" earcon follows it.
    private static let okCueHold: TimeInterval = 0.12

    /// Every command ends the talk (PROTOCOL.md "Commands"). The host does it
    /// for what it is sent. Volume is local: the same parser the host runs
    /// decides, a volume result never leaves this phone, and this phone then
    /// closes the talk itself, as a TALK press would.
    private func runCommand(_ text: String) {
        switch ClientCommand.route(text, volumeArmed: volumeKey.isArmed) {
        case .volume(let up, .inTalk):
            // The app level: what music plays at as much as the talk.
            earcons.play(volumeKey.step(up: up) ? "ok" : "error")
            let work = DispatchWorkItem { [weak self] in
                guard let self else { return }
                self.volumeClose = nil
                if self.talkOpen { self.requestTalkClose() }
            }
            volumeClose = work
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.okCueHold, execute: work)
        case .volume(let up, .afterMediaRoute):
            mediaVolumeStep = up
            requestTalkClose()
        case .send(let text):
            send(.commandText(CommandText(text: text, lang: settings.speechLanguage)))
        }
    }

    private func stopRecognition() {
        transcriber.stop()
        firstPhrase = nil
        assign(\.commandWindow, false)
        firstPhraseTimer?.invalidate()
        firstPhraseTimer = nil
    }

    // MARK: - Buttons

    /// Lock screen, Control Center, or a headset: music only. The earbuds sit
    /// inside the helmet, so no button starts or ends a talk (2026-09-29).
    /// Pause pauses and Play only resumes (user decision U-D1, 2026-09-30):
    /// a bud taken out of the ear pauses the ride's music, and one put back
    /// in while it plays changes nothing.
    private func remoteButton(_ button: NowPlaying.Button) {
        Log.app.info("remote button: \(String(describing: button), privacy: .public), talk=\(self.talkOpen)")
        switch button {
        case .playPause: playPauseButton()
        case .play: musicControl(.resume)
        case .pause: musicControl(.pause)
        case .next: musicControl(.next)
        case .previous: musicControl(.previous)
        }
    }

    func musicControl(_ action: MusicAction) {
        // Play pressed on this phone means play here, headset or not.
        if action == .resume { releaseRouteHold() }
        send(.musicControl(MusicControl(action: action)))
    }

    /// The host's repeat mode, as the repeat button shows it.
    var repeatSetting: RepeatSetting { RepeatSetting(hostState?.music?.repeat) }

    /// The Ride screen's repeat button: asks the host for the mode after the
    /// one in its last `state` (off → queue → track); the button changes when
    /// the host's `state` says so (PROTOCOL.md "Repeat by touch").
    func repeatButton() {
        send(.musicControl(.setRepeat(repeatSetting.next)))
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
        searchedQuery = query
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
    ///
    /// `confirm`: say what it did ("Added to queue: …", `Toast`), as Android's
    /// snackbar does; off for the Queue tab's Undo, which has its own banner.
    func enqueue(_ mode: EnqueueMode, tracks: [EnqueueTrack], art: String? = nil, confirm: Bool = true) {
        let message = MusicEnqueue(mode: mode, tracks: tracks, art: art).fitted()
        guard !message.tracks.isEmpty else { return }
        if mode == .now { releaseRouteHold() }
        let nothingLoaded = nowPlaying == nil
        guard link.isConnected else { return }
        send(.musicEnqueue(message))
        if confirm, let text = Toast.enqueued(mode, titles: message.tracks.map(\.title), nothingLoaded: nothingLoaded) {
            showToast(Toast(text, nowMs: MonotonicClock.nowMs()))
        }
    }

    /// Shows `toast` in place of any other, until its `untilMs`.
    private func showToast(_ toast: Toast) {
        self.toast = toast
        lineTimers["toast"]?.invalidate()
        lineTimers["toast"] = Timer.scheduledTimer(withTimeInterval: Toast.durationMs / 1000, repeats: false) { [weak self] _ in
            guard let self, self.toast == toast else { return }
            self.toast = nil
        }
    }

    /// The collection page's Download button: every song of it into the
    /// host's cache, or (while that runs) stop it. Progress and the marks come
    /// back in `music.downloads` (PROTOCOL.md "Browsing" step 6).
    func downloadButton(_ collection: ResultItem, songs: [ResultItem]) {
        let button = hostDownloads.button(ref: collection.ref, songs: songs.map(\.ref))
        if button.isRunning {
            send(.musicDownload(.stop(collection.ref)))
        } else if !button.isDone, !songs.isEmpty {
            send(.musicDownload(.start(collection.ref, ids: Array(songs.prefix(MusicEnqueue.maxTracks).map(\.ref)))))
        }
    }

    /// Plays a track from the Search tab's history now.
    func playAgain(_ track: BrowseHistory.Track) {
        enqueue(.now, tracks: [track.enqueueTrack])
    }

    func clearRecentSearches() {
        history.clearSearches()
    }

    /// Changes the upcoming queue. `index`/`id` name `state.queue[index]` for
    /// jump, remove and move; the host ignores the edit if the queue moved
    /// since. `to` (move only) is where the track ends up.
    func editQueue(_ op: QueueEditOp, index: Int? = nil, id: String? = nil, to: Int? = nil) {
        if op == .jump { releaseRouteHold() }
        send(.musicEdit(MusicEdit(op: op, index: index, id: id, to: to)))
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
        // Phone call, Siri, alarm… the system has stopped our audio, the
        // silent engine included: stop it for real, so that the end of the
        // interruption starts it again (a locked phone is suspended without).
        player.suspend()
        if talkOpen {
            // The mic is gone after talk opened (PROTOCOL.md "Talk flow" step 4).
            requestTalkClose(.unavailable)
            closeTalkLocally(cue: false)
            // A state{talk:true} already on its way must not reopen the talk
            // into the call; the host's talk.close clears this.
            micUnavailable = true
        } else {
            // A talk closed a moment ago: its held switch back to media is
            // dropped, `interruptionEnded` makes it.
            cancelMediaRestore()
        }
        // After the talk teardown, which starts it.
        keepAlive.stop()
    }

    private func interruptionEnded(_ shouldResume: Bool) {
        // interruptionBegan ended talk, so unless one has started since, the
        // session belongs in media mode — not in the route last activated
        // (.talk when switching back failed during the call).
        if talkOpen {
            session.reactivate()
            return
        }
        micUnavailable = false
        restoreMediaRoute()
        if shouldResume { resumeMusic() }
    }

    private func routeChanged(_ reason: AVAudioSession.RouteChangeReason) {
        noteAudioRoute()
        volumeKey.settle()
        switch reason {
        case .oldDeviceUnavailable:
            // Headset gone: don't blast music out of the speaker, now or at
            // the host's next state / music.play.
            if !session.hasHeadphones {
                player.suspend()
                if !musicHeldForRoute { Log.audio.info("music: held, the headset is gone") }
                musicHeldForRoute = true
                updateNowPlaying()
            }
        case .newDeviceAvailable:
            if !musicHeldForRoute { resumeMusic() }
        default:
            // The route the output latency was read for may be this one now.
            if !talkOpen { player.rereadOutputDelay() }
        }
        endRouteHoldIfHeadset()
    }

    /// A headset is back: catch up to the host's current anchor (not the
    /// player's own, which is from before the hold).
    private func endRouteHoldIfHeadset() {
        guard musicHeldForRoute, session.hasHeadphones else { return }
        Log.audio.info("music: headset back, hold ended")
        musicHeldForRoute = false
        startMusicIfPossible()
        updateNowPlaying()
    }

    private func mediaServicesReset() {
        Log.audio.error("media services were reset; rebuilding audio")
        // Every player and engine made before the reset is dead (Apple:
        // dispose of them and make new ones, audit M11). The voice engine
        // builds a new AVAudioEngine at every talk anyway.
        stopRecognition()
        voiceEngine.stop()
        announcer.rebuild()
        earcons.reset()
        player.rebuild()
        keepAlive.rebuild()
        if talkOpen {
            // The host is the authority on talk: tell it, as after an
            // interruption, instead of leaving it in a talk whose mic here
            // is gone until someone presses again.
            requestTalkClose(.unavailable)
            closeTalkLocally(cue: false)
            // As in `interruptionBegan`: no reopening from a stale state.
            micUnavailable = true
        } else {
            restoreMediaRoute()
        }
        // The new player has nothing loaded: play the host's last anchor
        // again (nothing, if the host had paused or a talk holds the music).
        startMusicIfPossible()
    }

    private func refreshStats() {
        // Whole milliseconds are all Diagnostics shows; an unchanged value
        // publishes nothing (audit UI4).
        let rtt = clock.rttMs.map { $0.rounded() }
        if stats.rttMs != rtt { stats.rttMs = rtt }
        noteAudioRoute()
        endRouteHoldIfHeadset()
    }

    private func noteAudioRoute() {
        let name = session.outputName
        if stats.audioRoute != name { stats.audioRoute = name }
        let route = session.outputRoute
        if stats.outputRoute != route { stats.outputRoute = route }
    }

    // MARK: - UI helpers

    /// Assigns only a changed value: `@Published` announces every assignment,
    /// equal or not, and every tab listens (audit UI4).
    private func assign<T: Equatable>(_ keyPath: ReferenceWritableKeyPath<AppModel, T>, _ value: T) {
        if self[keyPath: keyPath] != value { self[keyPath: keyPath] = value }
    }

    /// Takes `text` (if it was just set) off the screen after a few seconds.
    private func expireLine(_ text: String?, _ key: String, clear: @escaping (AppModel) -> Void) {
        lineTimers[key]?.invalidate()
        lineTimers[key] = nil
        guard text != nil else { return }
        lineTimers[key] = Timer.scheduledTimer(withTimeInterval: Notice.transientLineMs / 1000,
                                               repeats: false) { [weak self] _ in
            if let self { clear(self) }
        }
    }

    private func clearProblem(on event: Notice.Event) {
        if let problem, problem.isCleared(by: event) { self.problem = nil }
    }

    /// The ✕ on the problem line.
    func dismissProblem() {
        if problem != nil { problem = nil }
    }

    private func endTalkLive() {
        if talkLive { talkLive = false }
        if talkLiveSince != nil { talkLiveSince = nil }
    }

    /// What the permissions card shows. Also called when the app comes back
    /// to the foreground (from the Settings app, say).
    func refreshPermissions() {
        assign(\.micDenied, session.recordPermission == .denied)
        assign(\.speechDenied, Transcriber.isDenied)
    }

    /// The name the link pill and Settings show for the link's state.
    var linkLabel: String { link.label(lastHost: lastHostName) }

    /// The search that failed or found nothing, once more.
    func retrySearch() {
        search(searchedKind, query: searchedQuery)
    }

    /// Current track position in ms on the host's timeline (its play anchor
    /// when synced), for the Ride card and the mini player.
    func displayPositionMs() -> Double? {
        trackPositionMs()
    }

    /// Settings → "Music sync offset": the trim of the output in use now
    /// (`LatencyTrims`, one per route, as on Android). A playing track
    /// re-syncs to it.
    func setTrim(_ ms: Double) {
        let route = session.outputRoute
        noteAudioRoute()
        guard ms != settings.trims.of(route) else { return }
        settings.trims = settings.trims.with(route, ms)
        if player.isPlaying { resumeMusic() }
    }

    /// Settings → "Compensate output latency" (audit M2); a playing track
    /// re-syncs to it, as for the trim.
    func setCompensateOutputLatency(_ on: Bool) {
        settings.compensateOutputLatency = on
        logOutputDelay("setting changed")
        if player.isPlaying { resumeMusic() }
    }
}

// MARK: - ControlClientDelegate

extension AppModel: ControlClientDelegate {
    func controlDidConnect(_ client: ControlClient, hostAddress: String?) {
        guard client === control else { return }
        self.hostAddress = hostAddress
        if let hostAddress { lastLinkedAddress = hostAddress }
        if !link.isConnected { connectedAtMs = MonotonicClock.nowMs() }
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
