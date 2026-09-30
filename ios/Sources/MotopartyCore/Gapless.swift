import Foundation

/// The client's bookkeeping for a gapless track change (PROTOCOL.md "Music
/// flow" step 6). The host names the track that follows the current one and
/// the host time it starts at (`music.next`); the player queues it behind the
/// current item, and from the change on `{id, 0, atHostTimeMs}` is the anchor.
///
/// Pure: the player reports what it is doing and applies what this returns.
public struct GaplessTracker: Equatable, Sendable {
    /// The queued track and the anchor it takes over with.
    public struct Next: Equatable, Sendable {
        public var id: String
        public var atHostTimeMs: Int64

        public init(id: String, atHostTimeMs: Int64) {
            self.id = id
            self.atHostTimeMs = atHostTimeMs
        }

        public var anchor: MusicAnchor { MusicAnchor(positionMs: 0, atHostTimeMs: atHostTimeMs) }
    }

    /// What the player does about a `music.next`.
    public enum NextAction: Equatable, Sendable {
        /// Queue the track behind the current item.
        case queue
        /// Another track was queued: take it out, queue this one.
        case replace
        /// The same track is already queued: nothing to do in the player
        /// (its start time, if it moved, is already taken over).
        case keep
        /// The host has already changed over to the queued track and this
        /// phone has not yet: the message names the track *after* the queued
        /// one. The queued item stays; `takeDeferred` hands this one back
        /// once the player has changed over.
        case later
        /// Not possible now (nothing playing, or the track is not ready):
        /// the track starts on its `music.play`, with a gap, as without this.
        /// A track queued before is taken out.
        case ignore
    }

    /// Where a play came from. The host repeats the current anchor in a
    /// `state` on every queue edit, and sends the same thing as a
    /// `music.play` message to take a `music.next` back.
    public enum PlaySource: Equatable, Sendable {
        /// A `music.play` message.
        case message
        /// A playing `state.music`, or the app applying the last play again
        /// (download finished, route hold released).
        case state
    }

    /// What the player does about a `music.play` (or a playing `state.music`).
    public enum PlayAction: Equatable, Sendable {
        /// Today's path: make the track current and start it from the anchor.
        /// A queued track is taken out first.
        case start
        /// The current track on the timeline it is already playing: only the
        /// anchor is refreshed. A queued track stays queued (the current
        /// track still ends when it said).
        case keep
        /// As `keep` (no seek, no restart), and the queued track is taken
        /// out: a `music.play` message for the current track takes the
        /// `music.next` back, also when it repeats the anchor unchanged.
        case takeBack
        /// The queued track on its own anchor, before the player got there:
        /// do not seek or restart, the player changes over by itself.
        case awaitChange
        /// The track this phone just changed over from, still on its old
        /// anchor: the host is a moment behind (this phone changes early by
        /// its output delay). Nothing to do, the current anchor stays.
        case stale
    }

    public private(set) var pending: Next?
    /// A `music.next` that arrived after the host's change to `pending` and
    /// before this phone's: the track after `pending`.
    public private(set) var deferred: MusicNext?
    /// The host has changed over to `pending` (its `state` or `music.play`
    /// named it on the pending anchor); this phone has not yet.
    public private(set) var hostChanged = false
    /// What this phone changed over from, until something else starts.
    private var previous: Next?

    public init() {}

    /// `music.next`. `playing`: the current track is playing on an anchor.
    /// `ready`: the named track is cached and decodable.
    public mutating func next(_ next: MusicNext, playing: Bool, ready: Bool) -> NextAction {
        let new = Next(id: next.id, atHostTimeMs: next.atHostTimeMs)
        if playing, hostChanged, let old = pending {
            // The queued track again (same start): nothing new.
            if old.id == new.id, old.anchor.isSameTimeline(as: new.anchor) { return .keep }
            // Otherwise the track after it (on repeat it has the same id and
            // a later start). Never in place of the queued one.
            deferred = ready ? next : nil
            return .later
        }
        guard playing, ready else {
            clear()
            return .ignore
        }
        defer { pending = new }
        guard let old = pending else { return .queue }
        return old.id == new.id ? .keep : .replace
    }

    /// `music.play{id, anchor}` or a playing `state.music`. `currentId` /
    /// `currentAnchor`: what the player is on; `playing`: it is playing (or
    /// scheduled to start).
    public mutating func play(id: String, anchor: MusicAnchor, currentId: String?,
                              currentAnchor: MusicAnchor?, playing: Bool,
                              source: PlaySource) -> PlayAction {
        // The queued track first: on repeat it has the current track's id.
        if playing, let next = pending, next.id == id, next.anchor.isSameTimeline(as: anchor) {
            hostChanged = true
            return .awaitChange
        }
        if playing, id == currentId, let currentAnchor, currentAnchor.isSameTimeline(as: anchor) {
            guard source == .message, pending != nil else { return .keep }
            pending = nil
            deferred = nil
            hostChanged = false
            return .takeBack
        }
        // After the current-track check: on repeat the old and the new track
        // share the id, and only the anchor tells them apart.
        if playing, let previous, previous.id == id, previous.anchor.isSameTimeline(as: anchor) {
            return .stale
        }
        clear()
        return .start
    }

    /// A `state`: `musicId` is its `music.id` (nil when `music` is absent),
    /// `playing` its `music.playing`. True when the queued track is to be
    /// taken out: the host is not playing, or is on a track that is neither
    /// this phone's current nor its queued one (a skip; the new track may
    /// still be downloading), so the current one must not run on into the
    /// old next. While the host plays, the track this phone just changed
    /// over from does not count as another one.
    public mutating func state(musicId: String?, playing: Bool, currentId: String?) -> Bool {
        guard pending != nil else { return false }
        if playing, let musicId, musicId == currentId || musicId == pending?.id || musicId == previous?.id {
            return false
        }
        return cancel()
    }

    /// Pause, stop, a talk, or any local suspend: the queued track (if any)
    /// is to be taken out. True when there was one.
    public mutating func cancel() -> Bool {
        defer { clear() }
        return pending != nil
    }

    /// The player moved on to the queued item: its id and anchor are the
    /// current ones from here on. `from`: the track and anchor it was on.
    /// `takeDeferred` afterwards gives the `music.next` that waited for this.
    public mutating func advanced(fromId: String? = nil, fromAnchor: MusicAnchor? = nil) -> Next? {
        guard let next = pending else { return nil }
        pending = nil
        hostChanged = false
        if let fromId, let fromAnchor {
            // Any anchor on the old timeline will do: keep its zero point.
            previous = Next(id: fromId, atHostTimeMs: fromAnchor.atHostTimeMs - fromAnchor.positionMs)
        } else {
            previous = nil
        }
        return next
    }

    /// The `music.next` that arrived between the host's change and this
    /// phone's, to be applied now (through `next`), once.
    public mutating func takeDeferred() -> MusicNext? {
        defer { deferred = nil }
        return deferred
    }

    private mutating func clear() {
        pending = nil
        deferred = nil
        hostChanged = false
        previous = nil
    }
}

/// How far ahead of the host timeline the player runs (audit M2): the latency
/// trim, plus the route's measured output latency while that is compensated.
public struct OutputDelay: Equatable, Sendable {
    /// `AVAudioSession.outputLatency` for the current route.
    public var outputLatencyMs: Double
    /// The user's latency trim.
    public var trimMs: Double
    /// Setting "Compensate output latency".
    public var compensate: Bool
    /// The output route, for the log only.
    public var route: String

    public init(outputLatencyMs: Double, trimMs: Double, compensate: Bool, route: String = "") {
        self.outputLatencyMs = outputLatencyMs
        self.trimMs = trimMs
        self.compensate = compensate
        self.route = route
    }

    public var ms: Double { (compensate ? outputLatencyMs : 0) + trimMs }

    /// What to do when the delay is read again after a route change (right
    /// after a talk closes the session can still report the HFP route).
    public enum Reread: Equatable, Sendable {
        case keep
        /// The start is still ahead: plan it again, nothing is heard of it.
        case replan
        /// Already playing on the old delay and out of the in-sync band: one
        /// restart from the anchor instead of 10-20 s of rate correction.
        case restart
    }

    /// `usedMs`: the delay the running (or scheduled) start was planned with.
    public static func reread(usedMs: Double, nowMs: Double, started: Bool) -> Reread {
        let change = abs(nowMs - usedMs)
        if started { return change > DriftCheck.resyncMs ? .restart : .keep }
        return change >= replanAboveMs ? .replan : .keep
    }

    /// A smaller change is not worth a second seek and preroll.
    public static let replanAboveMs: Double = 10
}

/// TALK while no talk is open (PROTOCOL.md "Talk flow" step 1): the first
/// press asks the host, a second one before the host decided takes the
/// request back, and a request unanswered for `timeoutMs` is dropped.
public enum TalkPress: Equatable, Sendable {
    /// Send `talk.open{by:"client"}`.
    case request
    /// Send `talk.close{by:"client", reason:"trigger"}` and clear the request.
    case cancelRequest
    /// A talk is open: send `talk.close{by:"client", reason:"trigger"}`.
    case close

    public static let timeoutMs: Double = 5_000

    public static func action(talkOpen: Bool, requested: Bool) -> TalkPress {
        if talkOpen { return .close }
        return requested ? .cancelRequest : .request
    }

    /// Whether a request made at `requestedAtMs` is dropped by `nowMs`.
    public static func isExpired(requestedAtMs: Double, nowMs: Double) -> Bool {
        nowMs - requestedAtMs >= timeoutMs
    }
}

/// When the app owns the volume keys (audit H9): while connected, and for
/// `graceMs` after a link loss, so a hotspot hiccup does not disarm and
/// re-arm them (two system-volume jumps and HUD flashes each time).
public struct VolumeKeyArming: Equatable, Sendable {
    public static let graceMs: Double = 10_000

    public private(set) var armed = false
    /// When the link was lost, while the keys are still held armed.
    private var lostAtMs: Double?

    public init() {}

    /// The link changed. `searching`: looking for or connecting to a host
    /// (false for idle: the app gave up, the keys go back at once).
    /// Returns when to call `expire` (nil: no timer needed).
    public mutating func link(connected: Bool, searching: Bool, nowMs: Double) -> Double? {
        if connected {
            armed = true
            lostAtMs = nil
            return nil
        }
        guard armed, searching else {
            armed = false
            lostAtMs = nil
            return nil
        }
        // Still armed: the loss itself starts the grace, later steps of the
        // same reconnect (searching → connecting → searching) don't extend it.
        if lostAtMs == nil { lostAtMs = nowMs }
        return lostAtMs! + Self.graceMs
    }

    /// The grace timer fired. True when the keys are to be disarmed now.
    public mutating func expire(nowMs: Double) -> Bool {
        guard armed, let lostAtMs, nowMs >= lostAtMs + Self.graceMs else { return false }
        armed = false
        self.lostAtMs = nil
        return true
    }
}
