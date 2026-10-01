import Foundation

/// When an announcement (the host's `announce`: its earcon and the spoken
/// text) may start on the **media** route. Pure: no AVFoundation, no clock of
/// its own; every event carries its time and the caller does the speaking.
/// The iOS sibling of Android's `MediaCue` (F9b).
///
/// **The bug this closes.** A command ends the talk, so the host's spoken
/// reply ("Playing …") arrives right behind its `talk.close`. The client then
/// keeps the talk session for the "end" earcon (`endCueHold`, 0.22 s) and
/// switches to `.playback`, and the AirPods take about a second to bring A2DP
/// back. An announcement started in that window was cut: started before the
/// category change, the change cut it; started right after, its first words
/// went into the profile switch.
///
/// **The rule.** Outside a talk an announcement may start when
///
///  a. **the talk session has been given up**: `activate(.media)` has run
///     ([mediaRestored]); **and**
///  b. **only if the talk was on Bluetooth HFP**, iOS has reported a route
///     whose output is no longer HFP after that ([routeReported]), and
///     `settleMs` has passed since that report. Off HFP (the speaker, a wired
///     or USB headset) there is no profile switch, and (a) is all.
///
/// Asked for with no talk session held and none being given up, it starts at
/// once: the common case, which must stay instant. An announcement during an
/// open talk (the host's clarifying question) is not this class's business:
/// the talk route is up, and the app speaks it there at once.
///
/// [tick] is the safety net: `timeoutMs` after the request, or after the
/// release if that came later, the announcement starts anyway, logged
/// `fallback`, so a missing report costs a late announcement, never a silent
/// one; and the wait ends, so later ones do not wait too. Unlike Android, no
/// fallback fires while a talk session is held again (a talk re-opened before
/// the switch): it would speak into the talk. Those wait for the next release.
public struct AnnounceGate: Sendable {
    /// After this the announcement starts whatever the signals say. Android's
    /// `MediaCue.TIMEOUT_MS`; it is longer than the ~1-2 s the AirPods take
    /// to bring A2DP back, so it only fires when a report is missing.
    public static let timeoutMs: Double = 2_000
    /// How long after iOS reports the media route the announcement waits.
    /// A guess until measured on the iPhone (`ios/README.md`, "What only a
    /// real iPhone can answer"): the report may come when the route is
    /// configured, before the earbuds play A2DP audio, and a stream that has
    /// just started can lose its first ~100-200 ms. 0.25 s is a short pause
    /// after the "end" earcon and leaves 1.25 s of the host's 1.5 s resume
    /// lead before the music.
    public static let settleMs: Double = 250

    /// One announcement to start now. Offsets are from its request, so
    /// `spokeMs` reads as "how long it waited".
    public struct Release: Equatable, Sendable {
        /// The caller's handle: the gate never holds the announcement itself.
        public let id: Int
        /// The talk session was given up, ms after the request; 0 = before it.
        public let releasedMs: Double?
        /// Did it have to wait for iOS to report a non-HFP route?
        public let neededRoute: Bool
        /// That route's output (the log's spelling), or nil.
        public let route: String?
        /// That route reported, ms after the request; nil = it never was.
        public let routeMs: Double?
        public let spokeMs: Double
        /// The timer released it: a signal never arrived.
        public let fallback: Bool

        /// The line the device run reads, e.g. `announce gate: released +3 ms,
        /// route BluetoothA2DPOutput +840 ms, spoke +1090 ms (route)`.
        public var line: String {
            let routeText: String
            if !neededRoute {
                routeText = "n/a"
            } else if let routeMs {
                routeText = "\(route ?? "none") \(Self.offset(routeMs))"
            } else {
                routeText = "none"
            }
            let why = fallback ? "fallback" : (neededRoute ? "route" : (releasedMs == nil ? "now" : "released"))
            return "announce gate: released \(releasedMs.map(Self.offset) ?? "none"), route \(routeText), "
                + "spoke \(Self.offset(spokeMs)) (\(why))"
        }

        private static func offset(_ ms: Double) -> String { "+\(Int(ms.rounded())) ms" }
    }

    private enum Phase: Equatable, Sendable {
        /// Nothing held: announcements start at once.
        case free
        /// A talk session is up (or about to be): wait for its release.
        case held
        /// Released from HFP: waiting for iOS to report the media route.
        case draining
        /// Reported: waiting `settleMs` more, until `untilMs`.
        case settling(untilMs: Double)
    }

    private struct Request: Equatable, Sendable {
        let id: Int
        let atMs: Double
    }

    private let timeoutMs: Double
    private let settleMs: Double
    private var phase = Phase.free
    private var releasedAt: Double?
    private var routeAt: Double?
    private var route: String?
    private var neededRoute = false
    private var pending: [Request] = []

    public init(timeoutMs: Double = AnnounceGate.timeoutMs, settleMs: Double = AnnounceGate.settleMs) {
        self.timeoutMs = timeoutMs
        self.settleMs = settleMs
    }

    /// Whether announcements are being held now.
    public var isHolding: Bool { phase != .free }
    /// How many are waiting.
    public var pendingCount: Int { pending.count }

    /// An announcement is wanted. Returns its `Release` when it may start
    /// now; nil when it waits, and the caller keeps `id` until a later call
    /// hands it back.
    public mutating func request(id: Int, atMs: Double) -> Release? {
        guard phase == .free else {
            pending.append(Request(id: id, atMs: atMs))
            return nil
        }
        return Release(id: id, releasedMs: nil, neededRoute: false, route: nil, routeMs: nil,
                       spokeMs: 0, fallback: false)
    }

    /// An own-mic talk takes the talk session (`activate(.talk)`). Whatever
    /// waits keeps waiting, for the next release: an announcement is still
    /// true whatever the route does.
    public mutating func talkSessionHeld(atMs: Double) {
        phase = .held
        releasedAt = nil
        routeAt = nil
        route = nil
        neededRoute = false
    }

    /// `activate(.media)` has run: condition (a). `wasBluetoothHFP`: the talk
    /// session's output was HFP just before it, so (b) applies.
    public mutating func mediaRestored(wasBluetoothHFP: Bool, atMs: Double) -> [Release] {
        guard phase == .held else { return [] }
        releasedAt = atMs
        neededRoute = wasBluetoothHFP
        guard wasBluetoothHFP else { return flush(atMs: atMs) }
        phase = .draining
        return []
    }

    /// iOS posted a route change; `outputIsBluetoothHFP` and `output`
    /// describe the route as it is now. A non-HFP output after an HFP release
    /// is condition (b); the release follows `settleMs` later ([tick]).
    public mutating func routeReported(outputIsBluetoothHFP: Bool, output: String, atMs: Double) -> [Release] {
        guard phase == .draining, !outputIsBluetoothHFP else { return [] }
        routeAt = atMs
        route = output
        guard settleMs > 0 else { return flush(atMs: atMs) }
        phase = .settling(untilMs: atMs + settleMs)
        return []
    }

    /// The timers: the settle delay, and the fallback.
    public mutating func tick(atMs: Double) -> [Release] {
        if case .settling(let until) = phase, atMs >= until { return flush(atMs: atMs) }
        guard phase != .held else { return [] }
        let due = pending.filter { atMs >= deadline(of: $0) }
        guard !due.isEmpty else { return [] }
        pending.removeAll { request in due.contains(request) }
        let fired = due.map { release($0, atMs: atMs, fallback: true) }
        // The report never came (or the settle was overtaken): stop waiting
        // for it, or every later announcement waits as long.
        return fired + flush(atMs: atMs)
    }

    /// When the caller should call [tick] next; nil = no timer needed.
    public var nextDeadlineMs: Double? {
        guard phase != .held else { return nil }
        let fallback = pending.map(deadline(of:)).min()
        if case .settling(let until) = phase { return min(until, fallback ?? until) }
        return fallback
    }

    /// After a media-services reset: the audio system is new and whatever
    /// waited is dropped (the announcer is rebuilt too). Returns the ids.
    public mutating func reset() -> [Int] {
        let dropped = pending.map(\.id)
        pending.removeAll()
        phase = .free
        releasedAt = nil
        routeAt = nil
        route = nil
        neededRoute = false
        return dropped
    }

    private func deadline(of request: Request) -> Double {
        max(request.atMs, releasedAt ?? request.atMs) + timeoutMs
    }

    private mutating func flush(atMs: Double) -> [Release] {
        let out = pending.map { release($0, atMs: atMs, fallback: false) }
        pending.removeAll()
        phase = .free
        return out
    }

    private func release(_ request: Request, atMs: Double, fallback: Bool) -> Release {
        Release(id: request.id,
                releasedMs: releasedAt.map { max(0, $0 - request.atMs) },
                neededRoute: neededRoute,
                route: route,
                routeMs: routeAt.map { max(0, $0 - request.atMs) },
                spokeMs: max(0, atMs - request.atMs),
                fallback: fallback)
    }
}
