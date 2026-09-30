import Foundation

// The decisions behind the iPhone's screens (audit round 3): wording, state
// mapping, row keys, image sizes. Pure, so they are tested on Linux; the
// SwiftUI views only draw what these return.

// MARK: - Times

public enum TrackTime {
    /// "m:ss", or "h:mm:ss" from an hour on. Negative and NaN read as 0:00.
    public static func clock(_ ms: Double) -> String {
        let total = ms.isFinite ? max(0, Int(ms / 1000)) : 0
        let (h, m, s) = (total / 3600, total / 60 % 60, total % 60)
        let ss = s < 10 ? "0\(s)" : "\(s)"
        guard h > 0 else { return "\(m):\(ss)" }
        return "\(h):\(m < 10 ? "0\(m)" : "\(m)"):\(ss)"
    }

    /// Non-empty parts joined with " · ".
    public static func joined(_ parts: [String?]) -> String {
        parts.compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

/// Where the current track is, on the host's timeline: along its play anchor
/// while it plays and the clock is synced, else where it is parked.
public enum PlaybackPosition {
    public static func ms(anchor: MusicAnchor?, hostNowMs: Double?, parkedMs: Double,
                          durationMs: Int64?) -> Double {
        var position = parkedMs
        if let anchor, let hostNowMs { position = anchor.expectedPositionMs(hostNowMs: hostNowMs) }
        guard position.isFinite else { return 0 }
        if let durationMs, durationMs > 0 { position = min(position, Double(durationMs)) }
        return max(0, position)
    }
}

// MARK: - Talk

/// What the TALK button shows. `connecting` covers both waits: the host has
/// not answered the press yet, and the talk is open but its microphone (or,
/// host-mic, its playback) is not live yet (the 1–1.5 s headset switch).
public enum TalkPhase: Equatable, Sendable {
    case idle
    case connecting
    case live(hostMic: Bool)

    public init(requested: Bool, open: Bool, live: Bool, mode: TalkMode) {
        if open {
            self = live ? .live(hostMic: mode == .hostMic) : .connecting
        } else {
            self = requested ? .connecting : .idle
        }
    }

    public var buttonTitle: String {
        switch self {
        case .idle: "TALK"
        case .connecting: "Connecting…"
        case .live: "END TALK"
        }
    }

    /// Next to the running timer while live.
    public var caption: String? {
        switch self {
        case .idle, .connecting: nil
        case .live(let hostMic): hostMic ? "Talking · rider's mic" : "Talking"
        }
    }

    public var isLive: Bool { if case .live = self { true } else { false } }
    public var isConnecting: Bool { self == .connecting }

    /// What a press does, for VoiceOver.
    public var accessibilityLabel: String {
        switch self {
        case .idle: "Talk"
        case .connecting: "Connecting talk"
        case .live: "End talk"
        }
    }
}

// MARK: - Link wording

public enum LinkWording {
    /// "Looking for Pixel 8…": the rider's phone by the name it last had, so
    /// the passenger is never told about a "host".
    public static func searching(lastHost: String?) -> String {
        let name = lastHost?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return name.isEmpty ? "Looking for the Pixel…" : "Looking for \(name)…"
    }

    public static func connecting(_ name: String) -> String { "Connecting to \(name)…" }
    public static func connected(_ name: String) -> String { "Connected to \(name)" }
}

// MARK: - Notices

/// The one problem line on Ride. It goes when the user dismisses it or when
/// the thing it complained about next works (audit UI1).
public struct Notice: Equatable, Sendable {
    public enum Kind: Equatable, Sendable {
        /// The audio session would not activate.
        case audio
        /// A talk could not open, here or on the other phone.
        case talk
        /// The host speaks another protocol version.
        case link
    }

    public enum Event: Equatable, Sendable {
        case audioSessionActivated
        case talkOpened
        case connected
        /// Reconnect in Settings.
        case reconnectAsked
    }

    public var kind: Kind
    public var text: String

    public init(_ kind: Kind, _ text: String) {
        self.kind = kind
        self.text = text
    }

    public func isCleared(by event: Event) -> Bool {
        switch (kind, event) {
        case (.audio, .audioSessionActivated), (.audio, .talkOpened): true
        case (.talk, .talkOpened): true
        case (.link, .connected), (.link, .reconnectAsked): true
        default: false
        }
    }

    /// How long "Heard: …" and the host's announcement stay on Ride.
    public static let transientLineMs: Double = 6_000
}

// MARK: - Queue

public enum QueueText {
    /// The Queue tab's badge: nothing for an empty queue, "99+" past 99.
    public static func badge(_ count: Int) -> String? {
        if count <= 0 { return nil }
        return count > 99 ? "99+" : "\(count)"
    }

    public static func upNext(_ count: Int) -> String {
        count > 0 ? "Up next · \(count)" : "Up next"
    }

    /// Under "Clear the queue?".
    public static func clearMessage(_ count: Int) -> String {
        (count == 1 ? "The upcoming song is removed." : "The \(count) upcoming songs are removed.")
            + " The current song keeps playing."
    }

    public static func songs(_ count: Int) -> String {
        count == 1 ? "1 song" : "\(count) songs"
    }
}

/// One row of the upcoming queue. `key` stays the same while the entry is in
/// the queue, whatever is removed above it: the track id plus which
/// occurrence of that id it is (a track can be queued twice).
public struct QueueRow: Equatable, Identifiable, Sendable {
    public let key: String
    /// Index in the host's `state.queue`.
    public let index: Int
    public let item: HostState.QueueItem
    public var id: String { key }

    public static func rows(_ queue: [HostState.QueueItem]) -> [QueueRow] {
        var seen: [String: Int] = [:]
        return queue.enumerated().map { index, item in
            let occurrence = seen[item.id, default: 0]
            seen[item.id] = occurrence + 1
            return QueueRow(key: "\(item.id)#\(occurrence)", index: index, item: item)
        }
    }
}

/// Rows the passenger removed that the host's `state` has not confirmed yet
/// (audit UI3): hidden at once, so a row never slides out and snaps back.
/// A removal the host never confirms comes back after `timeoutMs`.
public struct QueueRemovals: Equatable, Sendable {
    public static let timeoutMs: Double = 5_000

    private struct Pending: Equatable, Sendable {
        var key: String
        var trackId: String
        var atMs: Double
    }

    private var pending: [Pending] = []

    public init() {}

    public var isEmpty: Bool { pending.isEmpty }

    /// The rows to show: the host's queue without the removals in flight.
    public func visible(_ queue: [HostState.QueueItem]) -> [QueueRow] {
        let hidden = Set(pending.map(\.key))
        return QueueRow.rows(queue).filter { !hidden.contains($0.key) }
    }

    /// The index to name in a `music.edit` for `row`. The host applies edits
    /// in order, so by the time it reads this one the removals in flight
    /// above the row are gone from its queue.
    public func wireIndex(of row: QueueRow, in queue: [HostState.QueueItem]) -> Int {
        let hidden = Set(pending.map(\.key))
        let above = QueueRow.rows(queue).filter { $0.index < row.index && hidden.contains($0.key) }.count
        return row.index - above
    }

    public mutating func remove(_ row: QueueRow, nowMs: Double) {
        guard !pending.contains(where: { $0.key == row.key }) else { return }
        pending.append(Pending(key: row.key, trackId: row.item.id, atMs: nowMs))
    }

    /// A `state` brought another queue. A removal is over once the queue has
    /// fewer entries of its track than before (removed, or played); until
    /// then it stays hidden, e.g. while only the head of the queue moved on.
    public mutating func queueChanged(from old: [HostState.QueueItem], to new: [HostState.QueueItem]) {
        guard !pending.isEmpty else { return }
        func count(_ queue: [HostState.QueueItem], _ id: String) -> Int { queue.filter { $0.id == id }.count }
        let keys = Set(QueueRow.rows(new).map(\.key))
        pending.removeAll { count(new, $0.trackId) < count(old, $0.trackId) || !keys.contains($0.key) }
    }

    /// Drops removals older than `timeoutMs`. Returns whether any was dropped.
    @discardableResult
    public mutating func expire(nowMs: Double) -> Bool {
        let before = pending.count
        pending.removeAll { nowMs - $0.atMs >= Self.timeoutMs }
        return pending.count != before
    }

    /// When the oldest removal in flight gives up.
    public var nextExpiryMs: Double? {
        pending.map(\.atMs).min().map { $0 + Self.timeoutMs }
    }
}

// MARK: - Search

public enum SearchWording {
    public static let prompt = "Songs, albums, artists"
    public static let emptyTitle = "Search YouTube Music"
    public static let emptyDetail = "Tap a song to play it. Albums and playlists open."

    public static func nothingFound(_ query: String) -> String {
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return query.isEmpty ? "No results" : "Nothing found for “\(query)”"
    }

    /// Under a failed search or album: what to do about it.
    public static let retryHint = "Try again when there is signal."
    public static let emptyCollection = "No songs in this one"
}

// MARK: - Voice commands

/// The command list on Ride: what the first phrase of a talk may be
/// (PROTOCOL.md "Commands"), one chip each.
public struct VoiceCommandChip: Equatable, Sendable {
    /// Said as written.
    public var words: String
    /// A placeholder the rider replaces ("song"), shown dimmed.
    public var argument: String?
    /// What it does, where the words alone don't say.
    public var note: String?

    public init(_ words: String, argument: String? = nil, note: String? = nil) {
        self.words = words
        self.argument = argument
        self.note = note
    }

    public static let all: [VoiceCommandChip] = [
        VoiceCommandChip("play", argument: "song"),
        VoiceCommandChip("play album", argument: "name"),
        VoiceCommandChip("play artist", argument: "name"),
        VoiceCommandChip("play playlist", argument: "name"),
        VoiceCommandChip("pause"),
        VoiceCommandChip("resume"),
        VoiceCommandChip("next"),
        VoiceCommandChip("previous"),
        VoiceCommandChip("louder"),
        VoiceCommandChip("quieter"),
        VoiceCommandChip("what's playing"),
        VoiceCommandChip("shuffle"),
        VoiceCommandChip("over", note: "only ends the talk"),
    ]

    public var accessibilityText: String {
        TrackTime.joined([argument.map { "\(words) \($0)" } ?? words, note]).replacingOccurrences(of: " · ", with: ", ")
    }
}

// MARK: - Images

/// Cover art comes over the Pixel's mobile data: ask Google's image servers
/// for the size the row needs instead of the 544 px the host names.
public enum ArtURL {
    /// The sizes asked for, so one cover is fetched in at most three.
    public static let buckets = [144, 288, 544]

    /// The smallest bucket that covers `points` at `scale`.
    public static func pixels(points: Double, scale: Double) -> Int {
        let wanted = points * max(1, scale)
        return buckets.first { Double($0) >= wanted } ?? buckets[buckets.count - 1]
    }

    /// `…=w544-h544-l90-rj` → `…=w144-h144-l90-rj`, `…=s544` → `…=s144` on
    /// googleusercontent.com / ggpht.com; every other URL is returned as is.
    public static func sized(_ url: String, pixels: Int) -> String {
        guard pixels > 0, let host = URLComponents(string: url)?.host?.lowercased(),
              host == "googleusercontent.com" || host.hasSuffix(".googleusercontent.com")
                || host == "ggpht.com" || host.hasSuffix(".ggpht.com"),
              let equals = url.lastIndex(of: "="), !url[equals...].contains("/") else { return url }
        var changed = false
        let options = url[url.index(after: equals)...].split(separator: "-", omittingEmptySubsequences: false).map { part -> String in
            guard let first = part.first, "whs".contains(first), part.count > 1,
                  part.dropFirst().allSatisfy({ $0.isASCII && $0.isNumber }) else { return String(part) }
            changed = true
            return "\(first)\(pixels)"
        }
        guard changed else { return url }
        return String(url[...equals]) + options.joined(separator: "-")
    }
}

// MARK: - Lock screen

/// What the lock screen and Control Center show (audit UI6, improvement 7).
public struct LockScreenInfo: Equatable, Sendable {
    public var title: String
    public var artist: String
    public var album: String?
    public var durationMs: Int64?
    public var positionMs: Double
    public var playing: Bool
    /// Identifies the cover, so it is only loaded when it changes.
    public var art: String?

    /// `track`: the current track, if any. During a talk the artist line says
    /// who the talk is with and the clock stands still.
    public init(track: MusicLoad?, art: String?, talking: Bool, riderName: String,
                positionMs: Double, playing: Bool) {
        title = track?.title ?? "Motoparty"
        let rider = riderName.trimmingCharacters(in: .whitespacesAndNewlines)
        if talking {
            artist = rider.isEmpty ? "Talking" : "Talking with \(rider)"
        } else {
            artist = track?.artist ?? ""
        }
        album = track?.album
        durationMs = track.map(\.durationMs).flatMap { $0 > 0 ? $0 : nil }
        self.positionMs = track == nil ? 0 : max(0, positionMs)
        self.playing = playing && !talking && track != nil
        self.art = track == nil ? nil : art
    }
}
