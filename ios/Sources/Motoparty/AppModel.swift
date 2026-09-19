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

/// Owns every component and implements the client side of PROTOCOL.md:
/// discovery → control → voice socket; talk and music flows; commands.
/// Everything here runs on the main queue.
final class AppModel: ObservableObject {
    // MARK: Published UI state
    @Published private(set) var link: LinkStatus = .idle
    @Published private(set) var talkOpen = false
    @Published private(set) var talkRequested = false
    @Published private(set) var listening = false
    @Published private(set) var hostState: HostState?
    @Published private(set) var nowPlaying: MusicLoad?
    @Published private(set) var musicPlaying = false
    @Published private(set) var downloading: String?
    @Published private(set) var lastHeard: String?
    @Published private(set) var lastAnnouncement: String?
    @Published private(set) var problem: String?
    @Published private(set) var rttMs: Double?
    @Published private(set) var driftMs: Double?
    @Published private(set) var audioRoute = ""

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
    private let cache = TrackCache()
    /// The player runs ahead of the host timeline by the output delay it has to
    /// cover: what the audio session measures for the current route plus the
    /// user's own trim (PROTOCOL.md, "Music flow" step 4).
    private lazy var player = SyncedPlayer(clock: clock) { [weak self] in
        guard let self else { return 0 }
        return self.session.outputLatencyMs + self.settings.latencyTrimMs
    }
    private let nowPlayingCenter = NowPlaying()
    private let transcriber = Transcriber()
    private let announcer = Announcer()

    // MARK: Link / music bookkeeping
    private var hostAddress: String?
    private var hostName = "host"
    private var voicePort = LinkDefaults.voicePort
    private var httpPort = LinkDefaults.httpPort
    private var loads: [String: MusicLoad] = [:]
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
        audioRoute = session.outputName
        // Ask for permissions at home, not on the road.
        session.requestRecordPermission { [weak self] granted in
            if !granted { self?.problem = "Microphone permission denied: talk will not work" }
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

        nowPlayingCenter.onButton = { [weak self] button in self?.remoteButton(button) }

        session.onInterruptionBegan = { [weak self] in self?.interruptionBegan() }
        session.onInterruptionEnded = { [weak self] shouldResume in self?.interruptionEnded(shouldResume) }
        session.onRouteChange = { [weak self] reason in self?.routeChanged(reason) }
        session.onMediaServicesReset = { [weak self] in self?.mediaServicesReset() }
        session.onMuteGesture = { [weak self] in
            guard let self else { return }
            self.remoteAction(self.settings.playPauseAction)
        }

        voiceEngine.onFailure = { [weak self] error in
            // The mic is gone mid-talk: same answer as failing to open it.
            self?.talkUnavailable(error.localizedDescription, weAsked: false)
        }

        player.onDrift = { [weak self] drift in self?.driftMs = drift }

        announcer.onSpeakingChanged = { [weak self] speaking in
            self?.player.volume = speaking ? 0.35 : 1.0
        }
    }

    // MARK: - Link

    private func startDiscovery() {
        link = .searching
        discovery.start()
    }

    private func connect(to candidate: HostCandidate) {
        link = .connecting(candidate.name)
        hostName = candidate.name
        if let txt = candidate.txt {
            voicePort = txt.voicePort
            httpPort = txt.httpPort
        }
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
        talkOpenPending = false
        micUnavailable = false
        if listening { transcriber.cancel(); listening = false; restoreMediaRoute() }
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
            hostName = hello.name
            link = .connected(hello.name)
            let newVoice = hello.voicePort ?? LinkDefaults.voicePort
            httpPort = hello.httpPort ?? LinkDefaults.httpPort
            if newVoice != voicePort || voice == nil {
                voicePort = newVoice
                startVoiceSocket()
            }
        case .state(let state):
            apply(state)
        case .talkOpen:
            openTalkLocally()
        case .talkClose(let close):
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
            if nowPlaying?.id == load.id { nowPlaying = load }
            prefetch(load)
        case .musicPlay(let play):
            currentPlay = play
            musicPlaying = true
            if nowPlaying?.id != play.id { nowPlaying = loads[play.id] ?? nowPlaying }
            startMusicIfPossible()
        case .musicPause(let pause):
            currentPlay = nil
            musicPlaying = false
            player.pause(atMs: pause.positionMs)
            updateNowPlaying()
        case .musicStop:
            currentPlay = nil
            musicPlaying = false
            nowPlaying = nil
            player.stop()
            updateNowPlaying()
        case .announce(let announce):
            lastAnnouncement = announce.text
            if let earcon = announce.earcon { earcons.play(earcon) }
            announcer.speak(announce.text, language: settings.speechLanguage)
        case .bye, .ping, .pong, .musicReady, .musicError, .musicControl, .commandText, .unknown:
            break
        }
    }

    private func apply(_ state: HostState) {
        hostState = state
        // Talk state is authoritative on the host; heal missed messages.
        if !state.talk { talkOpenPending = false }
        if state.talk != talkOpen { state.talk ? openTalkLocally() : closeTalkLocally() }

        guard let music = state.music else {
            if currentPlay != nil || player.currentId != nil {
                currentPlay = nil
                player.stop()
            }
            musicPlaying = false
            nowPlaying = nil
            updateNowPlaying()
            return
        }

        if loads[music.id] == nil {
            // The host re-sends music.load after a join; until then use the fixed path.
            let load = MusicLoad(id: music.id, path: "/track/\(music.id).m4a", title: music.title,
                                 artist: music.artist, durationMs: music.durationMs)
            loads[music.id] = load
            prefetch(load)
        }
        nowPlaying = loads[music.id]
        musicPlaying = music.playing
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
        if !cache.isCached(load.id) { downloading = load.title }
        cache.fetch(id: load.id, host: hostAddress, port: httpPort, path: load.path) { [weak self] outcome in
            guard let self else { return }
            if self.downloading == load.title { self.downloading = nil }
            switch outcome {
            case .ready:
                self.send(.musicReady(MusicReady(id: load.id)))
                if self.currentPlay?.id == load.id { self.startMusicIfPossible() }
            case .failed(let why):
                self.send(.musicError(MusicError(id: load.id, message: why)))
            }
        }
    }

    /// Plays `currentPlay` if the track is cached and nothing needs the mic.
    private func startMusicIfPossible() {
        guard let play = currentPlay, !talkOpen, !listening else { return }
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

    private func requestTalkClose() {
        send(.talkClose(TalkClose(by: .client, reason: .trigger)))
    }

    /// The host could not open talk (`reason: "unavailable"`) or closed it
    /// before it ever opened. Nothing was set up, so nothing is torn down.
    private func talkRefused(_ reason: TalkCloseReason) {
        guard talkRequested else { return }
        talkRequested = false
        earcons.play("error")
        if reason == .unavailable { problem = "The other phone could not open its microphone" }
    }

    /// Host decided talk is open: pause music, switch AirPods to call mode,
    /// open the mic, earcon when live.
    private func openTalkLocally() {
        let weAsked = talkRequested
        talkRequested = false
        guard !talkOpen else { return }
        // The host's state{talk:true} follows its talk.open, and may cross our
        // talk.close: don't fail (and earcon) twice for the same open.
        guard !micUnavailable else { return }
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
                self.openTalkLocally()
            }
            return
        default:
            talkUnavailable("microphone permission denied", weAsked: weAsked)
            return
        }
        talkOpen = true
        if listening { transcriber.cancel(); listening = false }
        announcer.stop()
        player.suspend()
        keepAlive.stop()
        do {
            try session.activate(.talk)
            voice?.beginCapture()
            try voiceEngine.start(
                sendAudio: { [weak self] data in self?.voice?.sendAudio(data) },
                skipFrame: { [weak self] in self?.voice?.skipFrame() }
            )
            session.armMuteGesture()
            earcons.play("live")
        } catch {
            // A call in progress, a route that failed, an engine that would
            // not start: this phone cannot talk.
            talkUnavailable(error.localizedDescription, weAsked: weAsked)
            return
        }
        audioRoute = session.outputName
        updateNowPlaying()
    }

    /// This phone cannot open its microphone (PROTOCOL.md "Talk flow" step 1).
    /// Tell the host — which then closes talk — and go straight back to the
    /// music session. Talk is not negotiable: this is only ever "cannot".
    private func talkUnavailable(_ why: String, weAsked: Bool) {
        problem = "Could not open the mic: \(why)"
        Log.audio.error("talk unavailable: \(why, privacy: .public)")
        micUnavailable = true
        send(.talkClose(TalkClose(by: .client, reason: .unavailable)))
        if weAsked { earcons.play("error") }
        talkRequested = false
        talkOpen = false
        session.disarmMuteGesture()
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
        guard talkOpen else { return }
        talkOpen = false
        session.disarmMuteGesture()
        voiceEngine.stop()
        restoreMediaRoute()
        earcons.play("end")
        updateNowPlaying()
    }

    private func restoreMediaRoute() {
        do { try session.activate(.media) } catch {
            Log.audio.error("media route failed: \(error.localizedDescription, privacy: .public)")
        }
        keepAlive.start()
        audioRoute = session.outputName
    }

    // MARK: - Voice command

    func commandButton() {
        if listening {
            transcriber.finish()
            return
        }
        guard !talkOpen else { return }
        guard link.isConnected else {
            earcons.play("error")
            return
        }
        listening = true
        announcer.stop()
        player.suspend()
        keepAlive.stop()
        do {
            try session.activate(.command)
            earcons.play("ok")
            try transcriber.start(language: settings.speechLanguage, maxSeconds: settings.commandMaxSeconds) { [weak self] text in
                self?.commandFinished(text)
            }
        } catch {
            problem = error.localizedDescription
            commandFinished(nil)
        }
        audioRoute = session.outputName
    }

    private func commandFinished(_ text: String?) {
        listening = false
        restoreMediaRoute()
        if let text {
            lastHeard = text
            // Volume is local (PROTOCOL.md "Commands"): the same parser the
            // host runs decides, and a volume result never leaves this phone.
            switch CommandParser.parse(text) {
            case .volumeUp:
                earcons.play(localVolume.up() ? "ok" : "error")
            case .volumeDown:
                earcons.play(localVolume.down() ? "ok" : "error")
            default:
                send(.commandText(CommandText(text: text, lang: settings.speechLanguage)))
            }
        } else {
            earcons.play("error")
            announcer.speak("Didn't catch that", language: "en-US")
        }
        // Rejoin the host's music timeline: the current anchor, which may be
        // a newer music.play (another track, a seek) that arrived while
        // listening and was held — not the player's last anchor.
        startMusicIfPossible()
    }

    // MARK: - Buttons

    private func remoteButton(_ button: NowPlaying.Button) {
        switch button {
        case .playPause: remoteAction(settings.playPauseAction)
        case .pause: if settings.pauseCommandTriggers { remoteAction(settings.playPauseAction) }
        case .next: remoteAction(settings.nextTrackAction)
        case .previous: remoteAction(settings.previousTrackAction)
        }
    }

    func remoteAction(_ action: RemoteAction) {
        switch action {
        case .talk: talkButton()
        case .command: commandButton()
        case .playPause: musicControl(musicPlaying ? .pause : .resume)
        case .next: musicControl(.next)
        case .previous: musicControl(.previous)
        case .none: break
        }
    }

    func musicControl(_ action: MusicAction) {
        send(.musicControl(MusicControl(action: action)))
    }

    // MARK: - Audio session events

    private func interruptionBegan() {
        // Phone call, Siri, alarm… the system has stopped our audio.
        player.suspend()
        if talkOpen {
            requestTalkClose()
            closeTalkLocally()
        }
        if listening { transcriber.cancel(); listening = false }
    }

    private func interruptionEnded(_ shouldResume: Bool) {
        // interruptionBegan ended talk and the voice command, so unless one
        // has started since, the session belongs in media mode — not in the
        // route last activated (a voice command's HFP route, or .talk when
        // switching back failed during the call).
        if talkOpen || listening {
            session.reactivate()
            return
        }
        restoreMediaRoute()
        if shouldResume, currentPlay != nil { player.resume() }
    }

    private func routeChanged(_ reason: AVAudioSession.RouteChangeReason) {
        audioRoute = session.outputName
        switch reason {
        case .oldDeviceUnavailable:
            // AirPods gone: don't blast music out of the speaker.
            if !session.hasHeadphones { player.suspend() }
        case .newDeviceAvailable:
            if currentPlay != nil, !talkOpen, !listening { player.resume() }
        default:
            break
        }
    }

    private func mediaServicesReset() {
        Log.audio.error("media services were reset; rebuilding audio")
        voiceEngine.stop()
        keepAlive.rebuild()
        if talkOpen {
            // The host is the authority on talk: tell it, as after an
            // interruption, instead of leaving it in a talk whose mic here
            // is gone until its 10 s silence timeout.
            requestTalkClose()
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
