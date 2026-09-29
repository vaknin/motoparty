#if os(iOS)
import AVFoundation
import Foundation
import MotopartyCore

/// The passenger's pocket keys: while the link is up the app owns the volume.
/// The system volume is parked at 15/16 and every key reading is put back
/// there; a single press of up or down is one app volume level, and a hold of
/// volume up toggles talk (the decisions are `VolumeKeyGate`'s, in
/// MotopartyCore). iOS has no API for the keys, so this watches
/// `AVAudioSession.outputVolume` by KVO and parks through `LocalVolume`'s
/// hidden slider. KVO only fires while the session is active; it always is
/// here (KeepAlive's silent engine between talks, the voice engine during one).
///
/// Loudness is app gain (`AppVolume`): `onGains` hands the level's gains to
/// talk, music and cues whenever it changes, and `AppVolume.unity` while
/// disarmed, when the system volume is the volume again.
///
/// Main queue only.
final class VolumeKey {
    /// A hold: toggle talk, exactly like the TALK button.
    var onHold: (() -> Void)?
    /// The app level changed (or arming/disarming): apply these gains.
    var onGains: ((AppVolume.Gains) -> Void)?

    private let localVolume: LocalVolume
    private let session = AVAudioSession.sharedInstance()
    private var gate = VolumeKeyGate()
    private var observation: NSKeyValueObservation?
    private var settleEnd: DispatchWorkItem?

    init(localVolume: LocalVolume) {
        self.localVolume = localVolume
    }

    func start() {
        guard observation == nil else { return }
        observation = session.observe(\.outputVolume, options: [.new]) { [weak self] _, change in
            guard let volume = change.newValue else { return }
            DispatchQueue.main.async { self?.observe(volume) }
        }
    }

    /// What talk, music and cues play at now.
    var gains: AppVolume.Gains { gate.armed ? AppVolume.gains(level: gate.level) : AppVolume.unity }

    /// Armed while the link is up; disarmed, the keys are plain system volume.
    var isArmed: Bool {
        get { gate.armed }
        set {
            guard newValue != gate.armed else { return }
            let volume = session.outputVolume
            let action = gate.setArmed(newValue, volume: volume, nowMs: MonotonicClock.nowMs())
            // Quieter first, then louder, so nothing blares in between: the
            // gain drops before the system volume is raised to the park, and
            // the system volume drops before the gain goes back to unity.
            if newValue {
                Log.audio.info("volume key: armed at system \(volume, privacy: .public) → level \(self.gate.level)")
                onGains?(gains)
                perform(action)
            } else {
                settleEnd?.cancel()
                settleEnd = nil
                Log.audio.info("volume key: disarmed at level \(self.gate.level)")
                perform(action)
                onGains?(gains)
            }
        }
    }

    /// The route or category is changing: its volume jump is not a key. Parks
    /// now, and again when the window is over (the jump may come late).
    func settle() {
        let action = gate.settle(nowMs: MonotonicClock.nowMs())
        guard action != .none else { return }
        perform(action)
        settleEnd?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.settleEnd = nil
            self.perform(self.gate.endSettle(nowMs: MonotonicClock.nowMs()))
        }
        settleEnd = work
        // A little past the window: the gate's clock decides, not this timer.
        DispatchQueue.main.asyncAfter(deadline: .now() + (VolumeKeyGate.settleMs + 50) / 1000, execute: work)
    }

    /// A spoken `volume up` / `volume down`: one app level while armed, one
    /// system step (as before) while disarmed. False if the volume could not
    /// be changed.
    func step(up: Bool) -> Bool {
        guard let level = gate.nudge(by: up ? 1 : -1) else {
            return up ? localVolume.up() : localVolume.down()
        }
        Log.audio.info("volume key: command \(up ? "up" : "down", privacy: .public) → level \(level)")
        onGains?(gains)
        return true
    }

    private func observe(_ volume: Float) {
        let action = gate.observe(volume, nowMs: MonotonicClock.nowMs())
        switch action {
        case .step, .toggle, .absorb:
            // Every key with its gap, so the real key-repeat timing shows up
            // in the log (the repeat-gap constants are tuned from it).
            if gate.lastKey == .up {
                let gap = gate.lastGapMs.map { "+\(Int($0.rounded())) ms" } ?? "first"
                let what: String
                switch action {
                case .step(let level): what = "→ level \(level)"
                case .toggle: what = "hold"
                default: what = "absorbed"
                }
                Log.audio.info("volume key: up \(gap, privacy: .public) (\(self.gate.burstCount)/\(VolumeKeyGate.holdSteps)) \(what, privacy: .public)")
            } else {
                Log.audio.info("volume key: down → level \(self.gate.level)")
            }
        case .park:
            Log.audio.info("volume key: settling, \(volume, privacy: .public) is no key")
        default:
            break
        }
        perform(action)
    }

    private func perform(_ action: VolumeKeyGate.Action) {
        switch action {
        case .none, .ownReset:
            break
        case .park, .absorb:
            park()
        case .step:
            park()
            onGains?(gains)
        case .toggle(let level):
            Log.audio.info("volume key: talk toggle, level back to \(level)")
            park()
            onGains?(gains)
            onHold?()
        case .release(let volume):
            Log.audio.info("volume key: system volume back to \(volume, privacy: .public)")
            if !localVolume.set(volume) { Log.audio.error("volume key: release failed") }
        }
    }

    private func park() {
        if !localVolume.set(VolumeKeyGate.parkVolume) { Log.audio.error("volume key: park failed") }
    }
}
#endif
