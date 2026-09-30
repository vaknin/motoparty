#if os(iOS)
import AVFoundation
import Foundation
import MotopartyCore

/// Plays the cached track in sync with the host: AVPlayer
/// `setRate(1, time:, atHostTime:)` starts playback at an exact CMClock host
/// time computed from the anchor (host clock → local clock → CMClock).
///
/// The player runs ahead of the host timeline by `outputDelay().ms` (the
/// user's latency trim, plus the measured `AVAudioSession.outputLatency` while
/// the "Compensate output latency" setting is on), so the *sound* lands on
/// the anchor. Drift is then checked against the anchor
/// and handled by `DriftController` (PROTOCOL.md "Music flow" step 4): under
/// 80 ms nothing, 80 ms - 1 s a playback-rate nudge of up to ±2 %, above 1 s a
/// re-seek — because every seek on A2DP costs a fresh few hundred ms of lag.
///
/// Gapless (PROTOCOL.md "Music flow" step 6): the player is an
/// `AVQueuePlayer`, and `queueNext` puts the next track behind the current
/// item. The change then happens on the player's own sample clock, where the
/// current item ends: no gap and no overlap, and whatever sync error the
/// phone had before the change (under 80 ms) it has after it, with nothing to
/// hear, and `DriftController` goes on against the new anchor. A second
/// player started with `setRate(_:time:atHostTime:)` would land exactly on
/// the new anchor instead, and turn that same error into an audible gap or
/// overlap at every change.
/// Main-queue API.
final class SyncedPlayer {
    private var player = AVQueuePlayer()
    private let clock: HostClock
    /// How far ahead of the host timeline this phone's player must run for its
    /// output to be heard on time, read again at every start and drift check.
    private let outputDelay: () -> OutputDelay

    private(set) var currentId: String?
    private(set) var anchor: MusicAnchor?
    private var durationMs: Int64?
    /// Bumped by every schedule/pause so stale async completions do nothing.
    private var generation = 0
    private var statusObservation: NSKeyValueObservation?
    private var itemObservation: NSKeyValueObservation?
    /// The track queued behind the current one, and what it takes over with.
    private var gapless = GaplessTracker()
    private var nextItem: AVPlayerItem?
    private var nextDurationMs: Int64?
    /// The item `load` made current. `AVQueuePlayer.currentItem` can lag a
    /// moment behind it, and a `music.next` may follow its `music.play` at once.
    private var loadedItem: AVPlayerItem?
    /// What the `music.next` held back in `gapless.deferred` is queued with.
    private var deferredNext: (url: URL, durationMs: Int64?)?
    /// The host's `music.play` for the queued track came before the player
    /// got there: if it never does, this starts the track the usual way.
    private var changeWatchdog: Timer?
    /// The output delay and host time the running (or scheduled) start was
    /// planned with.
    /// A start is on its way (seek, preroll, or waiting to retry).
    private var startPending = false
    /// Playing, or about to: what a `music.next` can be queued behind.
    private var isActive: Bool { (isPlaying || startPending) && (player.currentItem ?? loadedItem) != nil }
    private var usedDelayMs: Double?
    private var startAtHostMs: Double?
    private var driftTimer: Timer?
    private var retries = 0
    private var drift = DriftController()

    var onDrift: ((Double) -> Void)?
    /// The player changed over to the queued track: `id` on this anchor is
    /// what is playing now.
    var onAdvance: ((String, MusicAnchor) -> Void)?
    /// The queued track could not be played; nothing is playing any more.
    var onQueueFailed: (() -> Void)?

    init(clock: HostClock, outputDelay: @escaping () -> OutputDelay) {
        self.clock = clock
        self.outputDelay = outputDelay
        configure()
    }

    private func configure() {
        // Required for setRate(_:time:atHostTime:).
        player.automaticallyWaitsToMinimizeStalling = false
        // `.advance` only while a next track is queued: a lone item stays
        // current (paused at its end), so the same track can be played again.
        player.actionAtItemEnd = .pause
        applyVolume()
        itemObservation = player.observe(\.currentItem, options: [.new]) { [weak self] _, _ in
            DispatchQueue.main.async { self?.currentItemChanged() }
        }
    }

    /// After a media-services reset (audit M11): the old AVPlayer is dead.
    /// Drops it and everything it held; the caller plays the host's last
    /// anchor again.
    func rebuild() {
        stop()
        itemObservation = nil
        player = AVQueuePlayer()
        configure()
    }

    var isPlaying: Bool { player.rate != 0 }
    var positionMs: Double {
        let t = player.currentTime()
        return t.isNumeric ? t.seconds * 1000 : 0
    }
    /// The app volume (`AppVolume.Gains.musicVolume`, 0...1). AVPlayer cannot
    /// boost above 1, so the top app level plays music at unity.
    var gain: Float = 1 { didSet { applyVolume() } }
    /// The announcer ducks music while it speaks (0...1).
    var duck: Float = 1 { didSet { applyVolume() } }

    private func applyVolume() {
        player.volume = min(1, max(0, gain * duck))
    }

    /// Where the player should be now (anchor + output delay), for the log.
    var targetPositionMs: Double? {
        guard let anchor, let hostNow = clock.hostNowMs() else { return nil }
        return anchor.targetPlayerPositionMs(hostNowMs: hostNow, trimMs: outputDelay().ms)
    }

    private func makeItem(_ url: URL) -> AVPlayerItem {
        let item = AVPlayerItem(url: url)
        // Drift is corrected by playing at up to ±2 %; without a pitch-keeping
        // algorithm that would be audible as a tuning change. .spectral is the
        // high-quality one and this is music, not speech.
        item.audioTimePitchAlgorithm = .spectral
        return item
    }

    /// Makes `id` the current item (no-op if it already is).
    private func load(id: String, url: URL) {
        guard currentId != id || player.currentItem == nil else { return }
        generation += 1
        currentId = id
        anchor = nil
        stopDriftTimer()
        drift.reset()
        player.removeAllItems()
        let item = makeItem(url)
        loadedItem = item
        player.replaceCurrentItem(with: item)
    }

    /// Plays `id` along `anchor`. If it is already playing on the same
    /// timeline, only the anchor is updated (no audible re-seek); if it is
    /// the queued track on its own anchor, nothing is touched: the player
    /// changes over by itself (PROTOCOL.md "Music flow" step 6).
    ///
    /// `source`: a `music.play` message for the current track also takes a
    /// queued track out, without a seek; a `state` repeating the anchor does not.
    func play(id: String, url: URL, anchor newAnchor: MusicAnchor, durationMs: Int64?,
              source: GaplessTracker.PlaySource) {
        switch gapless.play(id: id, anchor: newAnchor, currentId: currentId, currentAnchor: anchor,
                            playing: isActive, source: source) {
        case .awaitChange:
            guard changeWatchdog == nil else { return }
            changeWatchdog = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: false) { [weak self] _ in
                guard let self else { return }
                self.changeWatchdog = nil
                guard self.gapless.pending?.id == id else { return }
                Log.music.error("gapless: no change to \(id, privacy: .public); starting it")
                // The track after it, if the host already named it, is
                // queued again behind the restarted one.
                let after = self.gapless.takeDeferred()
                let afterPayload = self.deferredNext
                self.dropNext()
                self.play(id: id, url: url, anchor: newAnchor, durationMs: durationMs, source: .state)
                if let after, let afterPayload {
                    _ = self.queueNext(after, url: afterPayload.url, durationMs: afterPayload.durationMs)
                }
            }
        case .stale:
            // The host is still on the track this phone just left (it
            // changes early by its output delay): nothing to reload.
            Log.music.info("gapless: \(id, privacy: .public) on its old anchor ignored after the change")
        case .takeBack:
            Log.music.info("gapless: queued track taken back by the host")
            removeNextItem()
            self.durationMs = durationMs
            anchor = newAnchor
        case .keep:
            self.durationMs = durationMs
            anchor = newAnchor
        case .start:
            dropNext()
            load(id: id, url: url)
            self.durationMs = durationMs
            anchor = newAnchor
            retries = 0
            schedule()
            startDriftTimer()
        }
    }

    /// `music.next`: queues the track behind the current item. `url` is nil
    /// when it is not cached; then (and when nothing is playing) it starts on
    /// its `music.play` as without this.
    /// False when nothing is playing or starting yet, so the caller can
    /// offer it again after the start it is waiting for.
    @discardableResult
    func queueNext(_ next: MusicNext, url: URL?, durationMs: Int64?) -> Bool {
        let playing = isActive && anchor != nil
        let action = gapless.next(next, playing: playing, ready: url != nil)
        switch action {
        case .keep:
            nextDurationMs = durationMs
        case .later:
            // The host is already on the queued track: this is the one after.
            deferredNext = url.map { ($0, durationMs) }
            Log.music.info("gapless: \(next.id, privacy: .public) waits for the change (cached=\(url != nil))")
        case .ignore:
            removeNextItem()
            Log.music.info("gapless: \(next.id, privacy: .public) not queued (playing=\(playing), cached=\(url != nil))")
        case .queue, .replace:
            removeNextItem()
            guard let url else { return playing }
            let item = makeItem(url)
            // After nil: at the end of the queue, which holds the current item only.
            guard player.canInsert(item, after: nil) else {
                _ = gapless.cancel()
                return playing
            }
            player.insert(item, after: nil)
            player.actionAtItemEnd = .advance
            nextItem = item
            nextDurationMs = durationMs
            Log.music.info("gapless: \(next.id, privacy: .public) queued for host time \(next.atHostTimeMs)")
        }
        return playing
    }

    /// A `state` (PROTOCOL.md "Music flow" step 6): the queued track is taken
    /// out when the host is not playing, has nothing loaded, or is on a track
    /// that is neither the current nor the queued one.
    func hostState(musicId: String?, playing: Bool) {
        guard gapless.state(musicId: musicId, playing: playing, currentId: currentId) else { return }
        Log.music.info("gapless: queued track dropped (state: \(musicId ?? "no music", privacy: .public), playing=\(playing))")
        removeNextItem()
    }

    /// Takes the queued track out (pause, stop, a talk, a seek, another play).
    private func dropNext() {
        if gapless.cancel() { Log.music.info("gapless: queued track dropped") }
        removeNextItem()
    }

    private func removeNextItem() {
        changeWatchdog?.invalidate()
        changeWatchdog = nil
        player.actionAtItemEnd = .pause
        deferredNext = nil
        guard let item = nextItem else { return }
        nextItem = nil
        nextDurationMs = nil
        player.remove(item)
    }

    /// KVO on the queue's current item: the change to the queued track.
    private func currentItemChanged() {
        guard let item = nextItem else { return }
        if player.currentItem === item, let next = gapless.advanced(fromId: currentId, fromAnchor: anchor) {
            generation += 1
            loadedItem = item
            changeWatchdog?.invalidate()
            changeWatchdog = nil
            nextItem = nil
            player.actionAtItemEnd = .pause
            currentId = next.id
            anchor = next.anchor
            durationMs = nextDurationMs
            nextDurationMs = nil
            if let hostNow = clock.hostNowMs() {
                // 0 when the player got here exactly when its anchor wants it to.
                let early = Double(next.atHostTimeMs) - hostNow - (usedDelayMs ?? 0)
                Log.music.info("gapless: now \(next.id, privacy: .public), changed \(Int(early)) ms early")
            }
            onAdvance?(next.id, next.anchor)
            // The first check soon: a duration the host saw differently shows here.
            scheduleDriftCheck(afterMs: DriftCheck.correctingIntervalMs)
            // A music.next that came between the host's change and this one.
            let payload = deferredNext
            deferredNext = nil
            if let after = gapless.takeDeferred(), let payload {
                queueNext(after, url: payload.url, durationMs: payload.durationMs)
            }
        } else if player.currentItem == nil {
            // The queue skipped an item it could not play.
            Log.music.error("gapless: the queued track did not play")
            dropNext()
            currentId = nil
            anchor = nil
            stopDriftTimer()
            onQueueFailed?()
        }
    }

    /// Host said pause: stop now and park at `positionMs`.
    func pause(atMs positionMs: Int64?) {
        dropNext()
        generation += 1
        startPending = false
        anchor = nil
        stopDriftTimer()
        player.pause()
        drift.reset()
        if let positionMs {
            player.seek(to: CMTime(value: positionMs, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
        }
    }

    /// Local pause for talk / voice command; keeps the item and anchor.
    func suspend() {
        dropNext()
        generation += 1
        startPending = false
        stopDriftTimer()
        player.pause()
        drift.reset()
    }

    /// After a local suspend: rejoin the host's timeline from the last anchor.
    func resume() {
        guard anchor != nil else { return }
        retries = 0
        schedule()
        startDriftTimer()
    }

    func stop() {
        dropNext()
        generation += 1
        startPending = false
        stopDriftTimer()
        statusObservation = nil
        player.pause()
        drift.reset()
        player.removeAllItems()
        loadedItem = nil
        currentId = nil
        anchor = nil
        usedDelayMs = nil
        startAtHostMs = nil
    }

    /// The route may have settled since the start was planned (right after a
    /// talk closes `outputLatency` can still be the HFP route's, audit M2):
    /// read the output delay again and, if it moved, plan the start again, or
    /// restart once when it is already playing out of the in-sync band.
    func rereadOutputDelay() {
        guard anchor != nil, isPlaying, !startPending, let used = usedDelayMs, let startAt = startAtHostMs,
              let hostNow = clock.hostNowMs() else { return }
        let now = outputDelay().ms
        let verdict = OutputDelay.reread(usedMs: used, nowMs: now, started: hostNow >= startAt)
        guard verdict != .keep else { return }
        Log.music.info("output delay \(Int(used)) → \(Int(now)) ms: \(verdict == .replan ? "start planned again" : "restart", privacy: .public)")
        retries = 0
        schedule()
        startDriftTimer()
    }

    // MARK: - Scheduling

    /// (Re)starts playback from the anchor. Always at rate 1.0: a seek drops
    /// any running drift correction.
    private func schedule() {
        guard let item = player.currentItem, anchor != nil else { return }
        generation += 1
        let gen = generation
        startPending = true
        player.pause()
        drift.reset()
        whenReady(item) { [weak self] in
            guard let self, gen == self.generation else { return }
            // Preroll at the planned position so the start is instant.
            guard let plan = self.plan(minLeadMs: 150, delayMs: self.outputDelay().ms) else { return self.finishedTrack() }
            self.player.seek(to: CMTime(seconds: plan.positionMs / 1000, preferredTimescale: 1000),
                             toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
                guard let self, gen == self.generation else { return }
                // preroll(atRate:) raises an ObjC exception unless the player is ready.
                guard self.player.status == .readyToPlay, self.player.rate == 0 else { return self.retryLater() }
                self.player.preroll(atRate: 1) { [weak self] _ in
                    DispatchQueue.main.async { self?.start(gen: gen) }
                }
            }
        }
    }

    private func start(gen: Int) {
        guard gen == generation else { return }
        // Re-plan from now: seek + preroll took an unknown amount of time.
        let delay = outputDelay()
        guard let plan = plan(minLeadMs: 60, delayMs: delay.ms) else { return finishedTrack() }
        guard let hostTime = clock.cmHostTime(forHostMs: plan.atHostTimeMs) else {
            Log.music.error("no clock sync yet; retrying")
            retryLater()
            return
        }
        let itemTime = CMTime(seconds: plan.positionMs / 1000, preferredTimescale: 1000)
        player.setRate(1, time: itemTime, atHostTime: hostTime)
        startPending = false
        usedDelayMs = delay.ms
        startAtHostMs = plan.atHostTimeMs
        let lead = Int(plan.atHostTimeMs - (clock.hostNowMs() ?? plan.atHostTimeMs))
        // Everything audit M2 needs to decide with a click track.
        Log.music.info("start \(self.currentId ?? "?", privacy: .public) at \(Int(plan.positionMs)) ms in \(lead) ms; outputLatency \(delay.outputLatencyMs, format: .fixed(precision: 1)) ms (\(delay.compensate ? "counted" : "not counted", privacy: .public)), trim \(Int(delay.trimMs)) ms, ahead by \(Int(delay.ms)) ms, route \(delay.route, privacy: .public)")
    }

    /// Start/seek target: the anchor position plus this phone's output delay,
    /// so playback begins early by exactly the delay its own output adds.
    private func plan(minLeadMs: Double, delayMs: Double) -> StartPlan? {
        guard let anchor, let hostNow = clock.hostNowMs() else { return nil }
        return anchor.startPlan(hostNowMs: hostNow, trimMs: delayMs, minLeadMs: minLeadMs,
                                durationMs: durationMs)
    }

    private func finishedTrack() {
        if clock.hostNowMs() == nil { retryLater(); return }
        // Anchor says we are past the end: stay paused until the next play.
        startPending = false
        player.pause()
    }

    private func retryLater() {
        guard retries < 20 else { startPending = false; return }
        retries += 1
        let gen = generation
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            guard let self, gen == self.generation else { return }
            self.schedule()
        }
    }

    private func whenReady(_ item: AVPlayerItem, _ block: @escaping () -> Void) {
        if item.status == .readyToPlay, player.status == .readyToPlay {
            block()
            return
        }
        statusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            DispatchQueue.main.async {
                guard let self else { return }
                switch item.status {
                case .readyToPlay:
                    self.statusObservation = nil
                    block()
                case .failed:
                    self.statusObservation = nil
                    self.startPending = false
                    Log.music.error("item failed: \(item.error?.localizedDescription ?? "?", privacy: .public)")
                default:
                    break
                }
            }
        }
    }

    // MARK: - Drift

    /// First check soon after a (re)start: that is when the A2DP output lag
    /// shows up. Each check then decides when the next one is.
    private func startDriftTimer() {
        scheduleDriftCheck(afterMs: DriftCheck.correctingIntervalMs)
    }

    private func scheduleDriftCheck(afterMs: Double) {
        stopDriftTimer()
        driftTimer = Timer.scheduledTimer(withTimeInterval: afterMs / 1000, repeats: false) { [weak self] _ in
            self?.checkDrift()
        }
    }

    private func stopDriftTimer() {
        driftTimer?.invalidate()
        driftTimer = nil
    }

    private func checkDrift() {
        // No anchor means pause/stop already stopped the timer; not playing yet
        // means a start is still pending, so look again shortly.
        guard let anchor else { return }
        guard isPlaying, let hostNow = clock.hostNowMs() else {
            scheduleDriftCheck(afterMs: DriftCheck.correctingIntervalMs)
            return
        }
        // A start scheduled for an anchor still ahead (up to ~1.5 s, audit
        // M1): the player waits at its start position, which is not drift.
        if let startAt = startAtHostMs, hostNow < startAt + 200 {
            scheduleDriftCheck(afterMs: DriftCheck.correctingIntervalMs)
            return
        }
        // positionMs is a position on the track's own timeline, so it is the
        // right measurement whatever rate a correction is running at.
        let decision = drift.decide(anchor: anchor, playerPositionMs: positionMs,
                                    hostNowMs: hostNow, trimMs: outputDelay().ms)
        onDrift?(decision.errorMs)
        switch decision.action {
        case .hold:
            break
        case .rate(let rate):
            Log.music.info("drift \(Int(decision.errorMs)) ms → rate \(rate)")
            setRateWhilePlaying(rate)
        case .reseek:
            Log.music.info("drift \(Int(decision.errorMs)) ms → reseek")
            retries = 0
            schedule()
        }
        scheduleDriftCheck(afterMs: decision.nextCheckMs)
    }

    /// Setting `rate` on a paused AVPlayer starts playback, so only ever touch
    /// it while already playing. Playback is never started this way: that goes
    /// through `setRate(1, time:, atHostTime:)` in `start(gen:)`, which also
    /// puts the rate back to 1.
    private func setRateWhilePlaying(_ rate: Double) {
        guard player.rate != 0 else { return }
        player.rate = Float(rate)
    }
}
#endif
