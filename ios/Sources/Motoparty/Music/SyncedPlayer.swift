#if os(iOS)
import AVFoundation
import Foundation
import MotopartyCore

/// Plays the cached track in sync with the host: AVPlayer
/// `setRate(1, time:, atHostTime:)` starts playback at an exact CMClock host
/// time computed from the anchor (host clock → local clock → CMClock).
///
/// The player runs ahead of the host timeline by `outputDelayMs()` (the
/// measured `AVAudioSession.outputLatency` plus the user's latency trim), so
/// the *sound* lands on the anchor. Drift is then checked against the anchor
/// and handled by `DriftController` (PROTOCOL.md "Music flow" step 4): under
/// 80 ms nothing, 80 ms - 1 s a playback-rate nudge of up to ±2 %, above 1 s a
/// re-seek — because every seek on A2DP costs a fresh few hundred ms of lag.
/// Main-queue API.
final class SyncedPlayer {
    private let player = AVPlayer()
    private let clock: HostClock
    /// How far ahead of the host timeline this phone's player must run for its
    /// output to be heard on time (output latency + user trim, ms).
    private let outputDelayMs: () -> Double

    private(set) var currentId: String?
    private(set) var anchor: MusicAnchor?
    private var durationMs: Int64?
    /// Bumped by every schedule/pause so stale async completions do nothing.
    private var generation = 0
    private var statusObservation: NSKeyValueObservation?
    private var driftTimer: Timer?
    private var retries = 0
    private var drift = DriftController()

    var onDrift: ((Double) -> Void)?

    init(clock: HostClock, outputDelayMs: @escaping () -> Double) {
        self.clock = clock
        self.outputDelayMs = outputDelayMs
        // Required for setRate(_:time:atHostTime:).
        player.automaticallyWaitsToMinimizeStalling = false
        player.actionAtItemEnd = .pause
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

    /// Makes `id` the current item (no-op if it already is).
    func load(id: String, url: URL) {
        guard currentId != id else { return }
        generation += 1
        currentId = id
        anchor = nil
        stopDriftTimer()
        drift.reset()
        let item = AVPlayerItem(url: url)
        // Drift is corrected by playing at up to ±2 %; without a pitch-keeping
        // algorithm that would be audible as a tuning change. .spectral is the
        // high-quality one and this is music, not speech.
        item.audioTimePitchAlgorithm = .spectral
        player.replaceCurrentItem(with: item)
    }

    /// Plays along `anchor`. If already playing on the same timeline, only
    /// the anchor is updated (no audible re-seek).
    func play(anchor newAnchor: MusicAnchor, durationMs: Int64?) {
        self.durationMs = durationMs
        if isPlaying, let old = anchor, old.isSameTimeline(as: newAnchor) {
            anchor = newAnchor
            return
        }
        anchor = newAnchor
        retries = 0
        schedule()
        startDriftTimer()
    }

    /// Host said pause: stop now and park at `positionMs`.
    func pause(atMs positionMs: Int64?) {
        generation += 1
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
        generation += 1
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
        generation += 1
        stopDriftTimer()
        statusObservation = nil
        player.pause()
        drift.reset()
        player.replaceCurrentItem(with: nil)
        currentId = nil
        anchor = nil
    }

    // MARK: - Scheduling

    /// (Re)starts playback from the anchor. Always at rate 1.0: a seek drops
    /// any running drift correction.
    private func schedule() {
        guard let item = player.currentItem, anchor != nil else { return }
        generation += 1
        let gen = generation
        player.pause()
        drift.reset()
        whenReady(item) { [weak self] in
            guard let self, gen == self.generation else { return }
            // Preroll at the planned position so the start is instant.
            guard let plan = self.plan(minLeadMs: 150) else { return self.finishedTrack() }
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
        guard let plan = plan(minLeadMs: 60) else { return finishedTrack() }
        guard let hostTime = clock.cmHostTime(forHostMs: plan.atHostTimeMs) else {
            Log.music.error("no clock sync yet; retrying")
            retryLater()
            return
        }
        let itemTime = CMTime(seconds: plan.positionMs / 1000, preferredTimescale: 1000)
        player.setRate(1, time: itemTime, atHostTime: hostTime)
        Log.music.info("start \(self.currentId ?? "?", privacy: .public) at \(Int(plan.positionMs)) ms")
    }

    /// Start/seek target: the anchor position plus this phone's output delay,
    /// so playback begins early by exactly the delay its own output adds.
    private func plan(minLeadMs: Double) -> StartPlan? {
        guard let anchor, let hostNow = clock.hostNowMs() else { return nil }
        return anchor.startPlan(hostNowMs: hostNow, trimMs: outputDelayMs(), minLeadMs: minLeadMs,
                                durationMs: durationMs)
    }

    private func finishedTrack() {
        if clock.hostNowMs() == nil { retryLater(); return }
        // Anchor says we are past the end: stay paused until the next play.
        player.pause()
    }

    private func retryLater() {
        guard retries < 20 else { return }
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
        // positionMs is a position on the track's own timeline, so it is the
        // right measurement whatever rate a correction is running at.
        let decision = drift.decide(anchor: anchor, playerPositionMs: positionMs,
                                    hostNowMs: hostNow, trimMs: outputDelayMs())
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
