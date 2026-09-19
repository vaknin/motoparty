import Foundation

/// `positionMs` is the track position at host time `atHostTimeMs`
/// (music.play / state.music). PROTOCOL.md, Music flow.
public struct MusicAnchor: Equatable, Sendable {
    public var positionMs: Int64
    public var atHostTimeMs: Int64

    public init(positionMs: Int64, atHostTimeMs: Int64) {
        self.positionMs = positionMs
        self.atHostTimeMs = atHostTimeMs
    }

    public init(_ play: MusicPlay) {
        self.init(positionMs: play.positionMs, atHostTimeMs: play.atHostTimeMs)
    }

    /// expected = positionMs + (hostNow - atHostTimeMs). Before the anchor
    /// time this is below positionMs (the track has not started yet).
    public func expectedPositionMs(hostNowMs: Double) -> Double {
        Double(positionMs) + (hostNowMs - Double(atHostTimeMs))
    }

    /// Where *this phone's player* should be at `hostNowMs`. `trimMs` is the
    /// phone's output-latency trim: positive means its audio comes out that
    /// much late (Bluetooth), so the player runs that much ahead.
    public func targetPlayerPositionMs(hostNowMs: Double, trimMs: Double) -> Double {
        expectedPositionMs(hostNowMs: hostNowMs) + trimMs
    }

    /// Two anchors describe the same playback timeline if they predict the same
    /// position for every host time (the host may re-anchor in each `state`).
    /// Then a playing player needs no re-seek.
    public func isSameTimeline(as other: MusicAnchor, toleranceMs: Double = 20) -> Bool {
        abs(Double(positionMs - atHostTimeMs) - Double(other.positionMs - other.atHostTimeMs)) <= toleranceMs
    }

    /// When and where to start the local player.
    ///
    /// If the anchor is at least `minLeadMs` in the future, start exactly at
    /// it (position + trim at atHostTimeMs). Otherwise (late join, resync) start
    /// `minLeadMs` from now at the position expected then. Returns nil if that
    /// position is past `durationMs`.
    public func startPlan(hostNowMs: Double, trimMs: Double, minLeadMs: Double = 150,
                          durationMs: Int64? = nil) -> StartPlan? {
        let startHost = max(Double(atHostTimeMs), hostNowMs + minLeadMs)
        let position = max(0, targetPlayerPositionMs(hostNowMs: startHost, trimMs: trimMs))
        if let durationMs, position >= Double(durationMs) { return nil }
        return StartPlan(positionMs: position, atHostTimeMs: startHost)
    }
}

public struct StartPlan: Equatable, Sendable {
    /// Player position to start from.
    public var positionMs: Double
    /// Host-clock time at which that position must be playing.
    public var atHostTimeMs: Double
}

/// What the local player should do about the drift measured at one check.
public enum DriftAction: Equatable, Sendable {
    /// Nothing to change: keep playing at the rate that is already set.
    case hold
    /// Set the playback rate to this (1.0 releases a correction). Always
    /// within `DriftCheck.minRate ... DriftCheck.maxRate`.
    case rate(Double)
    /// More than `DriftCheck.seekAboveMs` out: restart from the anchor
    /// (`MusicAnchor.startPlan`) at rate 1.0.
    case reseek
}

public struct DriftDecision: Equatable, Sendable {
    /// player position - target position; positive means the player is ahead.
    public var errorMs: Double
    public var action: DriftAction
    /// How long to wait before measuring again.
    public var nextCheckMs: Double

    public init(errorMs: Double, action: DriftAction, nextCheckMs: Double) {
        self.errorMs = errorMs
        self.action = action
        self.nextCheckMs = nextCheckMs
    }
}

/// Thresholds and the rate formula for the drift rule in PROTOCOL.md, "Music
/// flow" step 4. Every play or seek on A2DP restarts the output with 350-700 ms
/// of fresh lag, so a re-seek to fix small drift only makes new drift. Instead
/// the rate is nudged by up to ±5 % until the player is back on the anchor, and
/// only drift above 1 s re-seeks. Same numbers as Android's `SyncController`.
public enum DriftCheck {
    /// Between checks while in sync.
    public static let intervalMs: Double = 10_000
    /// Between checks while a correction is running, and right after a
    /// re-seek or a start (Android: EARLY_CHECK_MS).
    public static let correctingIntervalMs: Double = 2_000
    /// In sync at or below this (PROTOCOL.md: "correct 80 ms - 1 s of drift").
    public static let resyncMs: Double = 80
    /// Above this a rate nudge would take too long: re-seek.
    public static let seekAboveMs: Double = 1_000
    /// Hysteresis: a running correction is held until the drift is this small,
    /// not merely back under `resyncMs`, so noise around the 80 ms boundary
    /// cannot toggle the rate on and off at every check.
    public static let releaseMs: Double = 40
    /// Drift is spread over this long: rate = 1 - drift / nudgeWindowMs, so a
    /// 80 ms error asks for 0.98 and is gone in ~4 s (Android: NUDGE_WINDOW_MS).
    public static let nudgeWindowMs: Double = 4_000
    /// PROTOCOL.md: "by up to ±5 %".
    public static let maxRateDelta: Double = 0.05
    public static let minRate: Double = 1 - maxRateDelta
    public static let maxRate: Double = 1 + maxRateDelta
    /// The rate is quantised to 0.5 % steps, so a rate change is only asked for
    /// when it is worth telling the player about.
    public static let rateSteps: Double = 200

    /// Proportional rate for an error, clamped to ±5 % and quantised.
    /// Positive error (the player is ahead) gives a rate below 1.
    public static func rate(forErrorMs errorMs: Double) -> Double {
        let raw = 1 - errorMs / nudgeWindowMs
        let clamped = min(maxRate, max(minRate, raw))
        return (clamped * rateSteps).rounded() / rateSteps
    }
}

/// The per-player drift correction state machine. Pure and platform-free: the
/// player only measures (its own position vs. the anchor) and applies what this
/// returns. `rate` is the correction currently engaged.
public struct DriftController: Equatable, Sendable {
    public private(set) var rate: Double = 1

    public init() {}

    public var isCorrecting: Bool { rate != 1 }

    /// Call after a (re)start or seek: the player is back at rate 1.0.
    public mutating func reset() { rate = 1 }

    /// errorMs = player position - target position (positive: player ahead).
    public mutating func decide(errorMs: Double) -> DriftDecision {
        let magnitude = abs(errorMs)
        if magnitude > DriftCheck.seekAboveMs {
            rate = 1
            return DriftDecision(errorMs: errorMs, action: .reseek,
                                 nextCheckMs: DriftCheck.correctingIntervalMs)
        }
        if magnitude > DriftCheck.resyncMs {
            let wanted = DriftCheck.rate(forErrorMs: errorMs)
            let action: DriftAction = wanted == rate ? .hold : .rate(wanted)
            rate = wanted
            return DriftDecision(errorMs: errorMs, action: action,
                                 nextCheckMs: DriftCheck.correctingIntervalMs)
        }
        if isCorrecting {
            // Under 80 ms but a correction is running: hold it until the error
            // is comfortably inside the band (hysteresis), then go back to 1.0.
            if magnitude > DriftCheck.releaseMs {
                return DriftDecision(errorMs: errorMs, action: .hold,
                                     nextCheckMs: DriftCheck.correctingIntervalMs)
            }
            rate = 1
            return DriftDecision(errorMs: errorMs, action: .rate(1), nextCheckMs: DriftCheck.intervalMs)
        }
        return DriftDecision(errorMs: errorMs, action: .hold, nextCheckMs: DriftCheck.intervalMs)
    }

    /// The player's position is its position on the *track* timeline, so it is
    /// the right measurement whatever rate the correction is running at; the
    /// target comes from the anchor only.
    public mutating func decide(anchor: MusicAnchor, playerPositionMs: Double, hostNowMs: Double,
                                trimMs: Double) -> DriftDecision {
        let target = anchor.targetPlayerPositionMs(hostNowMs: hostNowMs, trimMs: trimMs)
        return decide(errorMs: playerPositionMs - target)
    }
}
