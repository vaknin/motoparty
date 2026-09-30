import Foundation

// What the Live Activity (lock screen, Dynamic Island) shows and when it is
// started, updated and ended. Pure: the ActivityKit side is
// `Motoparty/Music/LiveActivity.swift`, the views are `MotopartyWidgets`.

/// The activity's content: display only. It carries the finished lines, so
/// two equal values look the same and nothing is sent for them.
public struct LiveActivityState: Codable, Hashable, Sendable {
    public enum Mode: String, Codable, Sendable {
        /// No track and no talk: the line says how the link is.
        case idle
        case paused
        case playing
        /// A talk is open and not live yet.
        case connecting
        case talking
    }

    /// How the link to the rider's phone is, for a ride with nothing playing.
    public enum Link: Equatable, Sendable {
        /// Never linked since the app started: nothing to show yet.
        case none
        case linked
        /// Linked before and looked for again.
        case lost
    }

    /// The track, or "Motoparty" with none.
    public var title: String
    /// The artist, who the talk is with, or how the link is.
    public var detail: String
    public var mode: Mode
    /// When the talk went live, for its running clock; nil outside a live talk.
    public var talkSince: Date?

    public init(title: String, detail: String, mode: Mode, talkSince: Date? = nil) {
        self.title = title; self.detail = detail; self.mode = mode; self.talkSince = talkSince
    }

    /// What to show, or nil for no activity: before the first link with
    /// neither a track nor a talk. From the first link on there is always
    /// something, because an activity can only be started while the app is in
    /// front, and the music usually starts with the phone in a pocket.
    public init?(track: MusicLoad?, playing: Bool, talkOpen: Bool, talkLive: Bool, talkLiveSince: Date?,
                 riderName: String, link: Link) {
        let rider = riderName.trimmingCharacters(in: .whitespacesAndNewlines)
        title = track?.title ?? "Motoparty"
        if talkOpen {
            if talkLive {
                mode = .talking
                detail = rider.isEmpty ? "Talking" : "Talking with \(rider)"
                talkSince = talkLiveSince
            } else {
                mode = .connecting
                detail = "Connecting…"
            }
        } else if let track {
            mode = playing ? .playing : .paused
            detail = track.artist
        } else {
            mode = .idle
            switch link {
            case .none: return nil
            case .linked: detail = LinkWording.connected(rider.isEmpty ? "the Pixel" : rider)
            case .lost: detail = LinkWording.searching(lastHost: rider)
            }
        }
    }
}

/// Decides each call to ActivityKit from what is wanted and what is already
/// up: nothing for an unchanged state, so the model can ask as often as it
/// likes (audit UI improvement 17).
public struct LiveActivityTracker: Sendable {
    public enum Step: Equatable, Sendable {
        case none
        case start(LiveActivityState)
        case update(LiveActivityState)
        case end
    }

    /// What the running activity shows; nil with none running.
    public private(set) var shown: LiveActivityState?
    /// The user swiped it away: it stays away until the app is next opened or
    /// there was nothing to show.
    public private(set) var dismissed = false

    public init() {}

    /// `canStart`: the app is in front and Live Activities are allowed. Only
    /// asked when an activity would be started.
    public mutating func step(wanted: LiveActivityState?, canStart: @autoclosure () -> Bool) -> Step {
        guard let wanted else {
            dismissed = false
            guard shown != nil else { return .none }
            shown = nil
            return .end
        }
        guard let shown else {
            guard !dismissed, canStart() else { return .none }
            self.shown = wanted
            return .start(wanted)
        }
        guard shown != wanted else { return .none }
        self.shown = wanted
        return .update(wanted)
    }

    /// The request was refused: nothing is up, and the next step asks again.
    public mutating func startFailed() {
        shown = nil
    }

    /// The activity is gone without this app ending it: swiped away by the
    /// user, or ended by the system (its 8 hours are over).
    public mutating func lost(byUser: Bool) {
        shown = nil
        if byUser { dismissed = true }
    }

    /// The app came to the front: a dismissed activity may come back.
    public mutating func foregrounded() {
        dismissed = false
    }
}
