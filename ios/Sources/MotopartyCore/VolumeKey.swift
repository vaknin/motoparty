import Foundation

/// The app's own volume while the link is up ("the app owns the volume",
/// 2026-09-29). The system volume is parked at `VolumeKeyGate.parkVolume` and
/// loudness is app gain instead: one level, 0...`maxLevel`, that talk,
/// music, earcons and announcements all follow.
///
/// Levels are dB: `unityLevel` is 0 dB (the parked system volume as it is),
/// every level below is `stepDb` quieter, 0 is silence. The one level above
/// unity wins back the system step that parking gives up: talk reaches it
/// with a boost behind a peak limiter; AVPlayer and AVAudioPlayer cannot go
/// above 1.0, so music and earcons stop at unity.
public enum AppVolume {
    /// 16 steps from silence, like the hardware keys.
    public static let maxLevel = 16
    /// 0 dB: the level the parked system volume (15/16) plays at unchanged.
    public static let unityLevel = 15
    /// dB per level. 16 levels over ~45 dB, near the system curve's range.
    public static let stepDb: Float = 3

    /// The same loudness for everything: the link is down and the system
    /// volume does the work.
    public static let unity = Gains(talkVolume: 1, talkBoostDb: 0, musicVolume: 1, cueVolume: 1)

    /// What each output plays at for one app level.
    public struct Gains: Equatable, Sendable {
        /// Talk output volume (the voice engine's main mixer), 0...1.
        public var talkVolume: Float
        /// Talk boost above unity (the peak limiter's pre-gain), dB, >= 0.
        public var talkBoostDb: Float
        /// Music player volume, 0...1 (before the announcer's duck).
        public var musicVolume: Float
        /// Earcons and announcements, 0...1.
        public var cueVolume: Float

        public init(talkVolume: Float, talkBoostDb: Float, musicVolume: Float, cueVolume: Float) {
            self.talkVolume = talkVolume
            self.talkBoostDb = talkBoostDb
            self.musicVolume = musicVolume
            self.cueVolume = cueVolume
        }
    }

    public static func clamp(_ level: Int) -> Int { min(maxLevel, max(0, level)) }

    /// dB of `level` against unity; nil for 0 (silence).
    public static func db(_ level: Int) -> Float? {
        let level = clamp(level)
        return level == 0 ? nil : Float(level - unityLevel) * stepDb
    }

    /// Linear gain of `level`, uncapped (the top level is above 1).
    public static func linear(_ level: Int) -> Float {
        guard let db = db(level) else { return 0 }
        return powf(10, db / 20)
    }

    public static func gains(level: Int) -> Gains {
        let linear = linear(level)
        let capped = min(1, linear)
        return Gains(talkVolume: capped, talkBoostDb: max(0, db(level) ?? 0),
                     musicVolume: capped, cueVolume: capped)
    }

    /// Arming: the level whose loudness matches the system volume `volume`
    /// (0...1) had, one level per hardware step, so nothing jumps.
    public static func level(forSystemVolume volume: Float) -> Int {
        clamp(Int((volume * Float(maxLevel)).rounded()))
    }

    /// Disarming: the system volume that plays like `level` did.
    public static func systemVolume(forLevel level: Int) -> Float {
        Float(clamp(level)) / Float(maxLevel)
    }
}

/// The passenger's pocket keys (2026-09-29): while the link is up, the
/// hardware volume keys are the app's. A single press of up or down is one
/// `AppVolume` level; a **hold** of volume up toggles talk, like TALK, with no
/// net volume change. Unlinked, they are plain system volume keys.
///
/// iOS reports no key presses, only the system output volume. So while armed
/// the volume is parked at `parkVolume`, one step below max, and every key
/// reading is put back there: both keys then always move it (at max an up
/// would make no reading). The readings caused by those resets are the gate's
/// own and are ignored.
///
/// A held key repeats: one step, the initial repeat delay, then fast regular
/// steps. The first press of a burst steps at once (responsive); a burst whose
/// first gap is within `firstRepeatGapMs` and whose following gaps are each
/// within `repeatGapMs`, `holdSteps` readings long, is a hold: talk toggles
/// once, the steps the burst made are taken back, and the rest of the hold is
/// absorbed. Human taps are slower and irregular, so three quick taps stay
/// three steps.
///
/// Pure: the app feeds every volume reading (`AVAudioSession.outputVolume`,
/// 0...1) and performs the returned action (park the system volume, apply the
/// level's gains, toggle talk). Every time is passed in, in ms on one
/// monotonic clock.
public struct VolumeKeyGate: Sendable {
    public enum Key: Sendable, Equatable { case up, down }

    public enum Action: Equatable, Sendable {
        /// Nothing to do: disarmed, or a reading already at the park.
        case none
        /// The reading a park asked for has arrived; not a key.
        case ownReset
        /// Not a key (arming, a route jump while settling, settle over): put
        /// the system volume back to `parkVolume`.
        case park
        /// A key press: the app level is now `level`; park.
        case step(level: Int)
        /// A hold of volume up: toggle talk; the burst's steps are taken back,
        /// the app level is `level` again; park.
        case toggle(level: Int)
        /// The same hold still repeating after its toggle: park, nothing else.
        case absorb
        /// Disarmed: set the system volume to `volume`, the app level's loudness.
        case release(volume: Float)
    }

    /// One step of a hardware volume key.
    public static let step: Float = 1.0 / 16.0
    /// Where the system volume is kept while armed: one step below max, so
    /// both keys always make a reading.
    public static let parkVolume: Float = 1 - step
    /// `HOLD_STEPS`: readings in one burst that make a hold (the press and
    /// three repeats). Three taps can never be one.
    public static let holdSteps = 4
    /// `FIRST_REPEAT_GAP_MS`: the longest gap from a burst's first press to its
    /// second step (iOS's initial key-repeat delay fits under it).
    public static let firstRepeatGapMs: Double = 700
    /// `REPEAT_GAP_MS`: the longest gap between the following steps of a hold
    /// (key repeat is fast and regular; human taps are not), and how long a
    /// toggled hold must be quiet before a press counts again. Tune both from
    /// the `volume key: up +<gap> ms` lines of the device log.
    public static let repeatGapMs: Double = 200
    /// After a route or category change the volume jumps to the new route's
    /// level (A2DP and HFP keep their own): readings are not keys for this
    /// long, and the volume is parked again when it ends.
    public static let settleMs: Double = 1_500
    /// How long the reading of a park is waited for.
    public static let ownChangeMs: Double = 1_000
    /// Readings are floats; anything closer than this is the same level.
    static let tolerance: Float = 0.01

    public private(set) var armed = false
    /// The app level (meaningful while armed).
    public private(set) var level = AppVolume.unityLevel
    /// Up steps in the current burst (for the log).
    public private(set) var burstCount = 0
    /// Gap from the previous up step to the latest one; nil for the first
    /// since arming, a down or a settle (for the log).
    public private(set) var lastGapMs: Double?
    /// The latest key (for the log).
    public private(set) var lastKey: Key?

    /// The latest reading.
    private var reading: Float?
    /// A park is on its way until then.
    private var expectedUntilMs: Double = -.infinity
    /// Readings are not keys until then; nil when not settling.
    private var settleUntilMs: Double?
    /// The app level before the burst's first step: where a hold puts it back.
    private var burstStartLevel = AppVolume.unityLevel
    private var lastUpMs: Double?
    /// This burst already toggled: the rest of it is absorbed.
    private var toggled = false

    public init() {}

    /// The link came up (`true`) or went down; `volume` is the system volume
    /// now. Arming starts at the level matching it and parks; disarming hands
    /// the loudness back to the system volume.
    public mutating func setArmed(_ armed: Bool, volume: Float, nowMs: Double) -> Action {
        guard armed != self.armed else { return .none }
        self.armed = armed
        reading = volume
        settleUntilMs = nil
        resetBurst()
        lastKey = nil
        if armed {
            level = AppVolume.level(forSystemVolume: volume)
            return park(nowMs)
        }
        expectedUntilMs = -.infinity
        return .release(volume: AppVolume.systemVolume(forLevel: level))
    }

    /// The route or the session category is about to change, or just did:
    /// nothing counts for `settleMs`; park now and again at `endSettle`. A
    /// hold that already toggled stays absorbed (talk opening is exactly when
    /// the key is still held).
    public mutating func settle(nowMs: Double) -> Action {
        guard armed else { return .none }
        settleUntilMs = nowMs + Self.settleMs
        if !toggled { resetBurst() }
        return park(nowMs)
    }

    /// Called once `settleMs` after each `settle`: parks when the latest
    /// window is over (an earlier call's timer does nothing).
    public mutating func endSettle(nowMs: Double) -> Action {
        guard armed, let until = settleUntilMs, nowMs >= until else { return .none }
        settleUntilMs = nil
        return park(nowMs)
    }

    /// A spoken `volume up`/`volume down` while armed: the app level moves by
    /// `delta` (clamped). Nil when disarmed (the system volume is the volume).
    public mutating func nudge(by delta: Int) -> Int? {
        guard armed else { return nil }
        level = AppVolume.clamp(level + delta)
        return level
    }

    /// One volume reading, at `nowMs`.
    public mutating func observe(_ volume: Float, nowMs: Double) -> Action {
        let previous = reading
        reading = volume
        guard armed else { return .none }
        if abs(volume - Self.parkVolume) <= Self.tolerance {
            // A key always moves the volume off the park; landing on it is
            // the gate's own reset.
            guard nowMs <= expectedUntilMs else { return .none }
            expectedUntilMs = -.infinity
            return .ownReset
        }
        if let until = settleUntilMs {
            if nowMs < until {
                // A held key keeps its toggled hold alive across the jump.
                if toggled { lastUpMs = nowMs }
                return park(nowMs)
            }
            settleUntilMs = nil
        }
        // Compared with the latest reading, not the park: a repeat can land
        // before the reset of the step before it.
        guard let previous, abs(volume - previous) > Self.tolerance else { return park(nowMs) }
        return volume > previous ? up(nowMs) : down(nowMs)
    }

    private mutating func up(_ nowMs: Double) -> Action {
        let gap = lastUpMs.map { nowMs - $0 }
        lastUpMs = nowMs
        lastGapMs = gap
        lastKey = .up
        if toggled, let gap, gap <= Self.repeatGapMs {
            burstCount += 1
            return absorb(nowMs)
        }
        let bound = burstCount == 1 ? Self.firstRepeatGapMs : Self.repeatGapMs
        if !toggled, burstCount >= 1, let gap, gap <= bound {
            burstCount += 1
        } else {
            burstCount = 1
            burstStartLevel = level
            toggled = false
        }
        if burstCount >= Self.holdSteps {
            toggled = true
            level = burstStartLevel
            _ = park(nowMs)
            return .toggle(level: level)
        }
        level = AppVolume.clamp(level + 1)
        _ = park(nowMs)
        return .step(level: level)
    }

    private mutating func down(_ nowMs: Double) -> Action {
        resetBurst()
        lastKey = .down
        level = AppVolume.clamp(level - 1)
        _ = park(nowMs)
        return .step(level: level)
    }

    private mutating func absorb(_ nowMs: Double) -> Action {
        _ = park(nowMs)
        return .absorb
    }

    private mutating func resetBurst() {
        burstCount = 0
        lastGapMs = nil
        lastUpMs = nil
        toggled = false
    }

    /// The app is about to set the volume to `parkVolume`: its reading is not
    /// a key.
    private mutating func park(_ nowMs: Double) -> Action {
        expectedUntilMs = nowMs + Self.ownChangeMs
        return .park
    }
}
