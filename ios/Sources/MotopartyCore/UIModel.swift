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
    /// Index in the queue the rows were made from: `state.queue` for `rows`,
    /// the queue the host will have for `QueueEdits.visible`.
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

/// What the Queue tab sends for an edit: a `music.edit`, or a
/// `music.enqueue{mode: "end"}` (an Undo).
public enum QueueCommand: Equatable, Sendable {
    case edit(QueueEditOp, index: Int, id: String, to: Int? = nil)
    case enqueueEnd([EnqueueTrack])
}

/// The passenger's queue edits that the host's `state` has not confirmed yet
/// (audit UI3), so the screen shows what they do at once: a removed row is
/// gone, a dragged row stays where it was dropped, an undone removal is back
/// in its place. No row slides away and snaps back.
///
/// The edits are kept in the order they were sent, which is the order the
/// host applies them in. So `visible` is the queue the host will have once
/// it has read them all, and a row's `index` there is what the next
/// `music.edit` names (removals, moves and restores in flight all count).
///
/// An edit is over when a `state` shows it done (`queueChanged`). One that is
/// not confirmed within `timeoutMs` (the host ignored it: the queue had
/// moved on, the link dropped) is dropped together with every edit sent
/// after it, since each of those was worked out on a queue that had it; the
/// rows then show the host's queue again.
public struct QueueEdits: Equatable, Sendable {
    public static let timeoutMs: Double = 5_000

    private enum Op: Equatable, Sendable {
        /// `music.edit remove` of the row with this key.
        case remove(key: String, trackId: String)
        /// `music.edit move`: the row with this key ends up at index `to`.
        case move(key: String, to: Int)
        /// An Undo of a removal: `music.enqueue end`, then a `move` to `at`.
        /// Shown at `at` from the start; once a `state` has the enqueued
        /// copy, it is a `.move` of that copy.
        case restore(item: HostState.QueueItem, at: Int)
    }

    private struct Pending: Equatable, Sendable {
        var op: Op
        var atMs: Double
    }

    private var pending: [Pending] = []

    public init() {}

    public var isEmpty: Bool { pending.isEmpty }

    /// The rows to show: the host's queue with the edits in flight applied.
    /// Their keys and indexes are those of the queue the host will have.
    public func visible(_ queue: [HostState.QueueItem]) -> [QueueRow] {
        QueueRow.rows(pending.reduce(queue) { Self.apply($1.op, to: $0) })
    }

    /// The index to name in a `music.edit` for `row` (a row of `visible`).
    /// The host applies edits in order, so by the time it reads this one the
    /// edits in flight are done, and the row is where `visible` shows it.
    public func wireIndex(of row: QueueRow, in queue: [HostState.QueueItem]) -> Int {
        visible(queue).first { $0.key == row.key }?.index ?? row.index
    }

    /// Hides `rows` (rows of `visible`) and returns their removals, in the
    /// order to send them: from the bottom up, so no remove shifts the index
    /// of one sent after it. Each one's `at` is also where Undo puts it back.
    @discardableResult
    public mutating func remove(_ rows: [QueueRow], in queue: [HostState.QueueItem], nowMs: Double) -> [QueueUndo.Removed] {
        var removed: [QueueUndo.Removed] = []
        for row in rows.sorted(by: { $0.index > $1.index }) {
            guard let now = visible(queue).first(where: { $0.key == row.key }) else { continue }
            pending.append(Pending(op: .remove(key: now.key, trackId: now.item.id), atMs: nowMs))
            removed.append(QueueUndo.Removed(item: now.item, at: now.index))
        }
        return removed
    }

    /// A drag of row `from` of `visible`, dropped before row `destination`
    /// (SwiftUI's `onMove` offset, counted before the move). The row stays
    /// where it was dropped; returns the `music.edit move` to send, or nil
    /// when the queue would be the same.
    @discardableResult
    public mutating func move(from: Int, insertBefore destination: Int, in queue: [HostState.QueueItem],
                              nowMs: Double) -> QueueCommand? {
        let rows = visible(queue)
        guard rows.indices.contains(from) else { return nil }
        // A row dropped below where it was ends up one above the gap.
        let to = min(max(destination > from ? destination - 1 : destination, 0), rows.count - 1)
        let op = Op.move(key: rows[from].key, to: to)
        let items = rows.map(\.item)
        // Not moved, or moved past a copy of itself.
        guard to != from, Self.apply(op, to: items) != items else { return nil }
        pending.append(Pending(op: op, atMs: nowMs))
        return .edit(.move, index: from, id: rows[from].item.id, to: to)
    }

    /// The Undo of a removal (`QueueUndo`): each song back at its `at`, the
    /// lowest first, so each lands where it was. Shown at once. The protocol
    /// has no insert (Android's Undo is the host's own `insert`), so it is a
    /// `music.enqueue end` followed by a `music.edit move` from the end to
    /// `at`. Nothing when no song is loaded: an enqueue would start playing
    /// it, and Android's Undo does nothing then either.
    public mutating func restore(_ removed: [QueueUndo.Removed], in queue: [HostState.QueueItem],
                                 nothingLoaded: Bool, nowMs: Double) -> [QueueCommand] {
        guard !nothingLoaded else { return [] }
        var commands: [QueueCommand] = []
        for r in removed.sorted(by: { $0.at < $1.at }) {
            let end = visible(queue).count
            pending.append(Pending(op: .restore(item: r.item, at: r.at), atMs: nowMs))
            commands.append(.enqueueEnd([EnqueueTrack(r.item)]))
            if r.at < end {
                commands.append(.edit(.move, index: end, id: r.item.id, to: r.at))
            }
        }
        return commands
    }

    /// A `state` brought another queue: the edits it shows done are over.
    /// A removal is done once the queue has fewer copies of its track than
    /// before (removed, or played); until then it stays hidden, e.g. while
    /// only the head of the queue moved on. A move is done once its row is
    /// where it was dropped. A restore is half done once the queue has one
    /// more copy of its track (enqueued at the end), and then waits for its
    /// move. An edit whose row is gone is over too.
    public mutating func queueChanged(from old: [HostState.QueueItem], to new: [HostState.QueueItem]) {
        guard !pending.isEmpty else { return }
        let before = Self.counts(old), after = Self.counts(new)
        var fewer: [String: Int] = [:], more: [String: Int] = [:]
        for (id, n) in before { fewer[id] = max(0, n - after[id, default: 0]) }
        for (id, n) in after { more[id] = max(0, n - before[id, default: 0]) }
        var list = new
        var kept: [Pending] = []
        for var p in pending {
            let rows = QueueRow.rows(list)
            switch p.op {
            case let .remove(key, trackId):
                if fewer[trackId, default: 0] > 0 {
                    fewer[trackId, default: 0] -= 1
                    continue
                }
                if !rows.contains(where: { $0.key == key }) { continue }
            case let .move(key, to):
                guard let row = rows.first(where: { $0.key == key }) else { continue }
                if row.index == min(to, rows.count - 1) { continue }
            case let .restore(item, at):
                if more[item.id, default: 0] > 0 {
                    more[item.id, default: 0] -= 1
                    // The copy just enqueued: the last one.
                    guard let copy = rows.last(where: { $0.item.id == item.id }) else { continue }
                    if copy.index == min(at, rows.count - 1) { continue }
                    p.op = .move(key: copy.key, to: at)
                }
            }
            kept.append(p)
            list = Self.apply(p.op, to: list)
        }
        pending = kept
    }

    /// Drops the edits that have waited `timeoutMs` and every edit sent
    /// after them. Returns whether any was dropped.
    @discardableResult
    public mutating func expire(nowMs: Double) -> Bool {
        guard let first = pending.firstIndex(where: { nowMs - $0.atMs >= Self.timeoutMs }) else { return false }
        pending.removeSubrange(first...)
        return true
    }

    /// When the oldest edit in flight gives up.
    public var nextExpiryMs: Double? {
        pending.map(\.atMs).min().map { $0 + Self.timeoutMs }
    }

    private static func apply(_ op: Op, to queue: [HostState.QueueItem]) -> [HostState.QueueItem] {
        var queue = queue
        func index(_ key: String) -> Int? { QueueRow.rows(queue).firstIndex { $0.key == key } }
        switch op {
        case let .remove(key, _):
            if let i = index(key) { queue.remove(at: i) }
        case let .move(key, to):
            if let i = index(key) {
                let item = queue.remove(at: i)
                queue.insert(item, at: min(max(to, 0), queue.count))
            }
        case let .restore(item, at):
            queue.insert(item, at: min(max(at, 0), queue.count))
        }
        return queue
    }

    private static func counts(_ queue: [HostState.QueueItem]) -> [String: Int] {
        queue.reduce(into: [:]) { $0[$1.id, default: 0] += 1 }
    }
}

/// The banner after a removal or a clear, with Undo: Android's snackbar
/// (`MainScreen.kt`, `confirmation` in `Logic.kt`). As there, the edit is
/// sent at once, and Undo puts the songs back afterwards.
public struct QueueUndo: Equatable, Sendable {
    /// How long Undo is offered: Material's `SnackbarDuration.Long`, as on Android.
    public static let durationMs: Double = 10_000

    /// A removed song and the index its `music.edit remove` named.
    public struct Removed: Equatable, Sendable {
        public let item: HostState.QueueItem
        public let at: Int
        public init(item: HostState.QueueItem, at: Int) { self.item = item; self.at = at }

        public var command: QueueCommand { .edit(.remove, index: at, id: item.id) }
    }

    public enum Action: Equatable, Sendable {
        /// Put removed songs back where they were (`QueueEdits.restore`).
        case restore([Removed])
        /// Queue these at the end again: the Undo of a clear, as on Android.
        case enqueue([HostState.QueueItem])
    }

    public let text: String
    public let action: Action
    /// When the banner goes by itself.
    public let untilMs: Double

    /// "Removed: <title>", as on Android; nil when nothing was removed.
    public static func removed(_ removed: [Removed], nowMs: Double) -> QueueUndo? {
        guard let first = removed.first else { return nil }
        let text = removed.count == 1 ? "Removed: \(first.item.title)" : "Removed \(QueueText.songs(removed.count))"
        return QueueUndo(text: text, action: .restore(removed), untilMs: nowMs + durationMs)
    }

    /// "Queue cleared"; nil when there was nothing to clear.
    public static func cleared(_ queue: [HostState.QueueItem], nowMs: Double) -> QueueUndo? {
        guard !queue.isEmpty else { return nil }
        return QueueUndo(text: "Queue cleared", action: .enqueue(queue), untilMs: nowMs + durationMs)
    }

    /// What Undo sends; `edits` shows a restore at once.
    public func undo(_ edits: inout QueueEdits, queue: [HostState.QueueItem], nothingLoaded: Bool,
                     nowMs: Double) -> [QueueCommand] {
        switch action {
        case let .restore(removed):
            return edits.restore(removed, in: queue, nothingLoaded: nothingLoaded, nowMs: nowMs)
        case let .enqueue(items):
            return items.isEmpty ? [] : [.enqueueEnd(items.map(EnqueueTrack.init))]
        }
    }
}

extension EnqueueTrack {
    /// A queued song sent back to the host (an Undo).
    public init(_ item: HostState.QueueItem) {
        self.init(id: item.id, title: item.title, artist: item.artist, durationMs: item.durationMs ?? 0, art: item.art)
    }
}

// MARK: - Search

public enum SearchWording {
    public static let prompt = "Songs, albums, artists"
    public static let emptyTitle = "Search YouTube Music"
    public static let emptyDetail = "Tap a song to play it. Albums, playlists and artists open."

    public static func nothingFound(_ query: String) -> String {
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return query.isEmpty ? "No results" : "Nothing found for “\(query)”"
    }

    /// Over the results once the box was edited after the search, so they
    /// are not taken for the new words' (Android's `SearchTab`, UA10). Nil
    /// while the box still holds the searched words, or is empty.
    public static func staleHeading(searched: String, box: String) -> String? {
        let box = box.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !searched.isEmpty, !box.isEmpty, box != searched else { return nil }
        return "Results for “\(searched)”"
    }

    /// Under a failed search or album: what to do about it.
    public static let retryHint = "Try again when there is signal."
    public static let emptyCollection = "No songs in this one"

    /// The artist page (2026-10-02): its two sections, and an answer with
    /// neither songs nor albums.
    public static let topSongs = "Top songs"
    public static let albumsAndSingles = "Albums and singles"
    public static let emptyArtist = "Nothing found for this artist"
}

// MARK: - Settings

public enum SpeechLanguages {
    /// The speech languages offered in Settings, as BCP-47 tags: Android's
    /// 14 (`SPEECH_LANGUAGES` in `ui/Logic.kt`), in its order.
    public static let all = [
        "en-US", "en-GB", "en-AU", "en-IN", "de-DE", "es-ES", "es-US", "fr-FR", "it-IT", "nl-NL", "pt-BR", "pl-PL",
        "ru-RU", "he-IL",
    ]

    /// `all`, plus `current` when it is not one of them (set before the list
    /// changed), so the picker still shows it.
    public static func offered(current: String) -> [String] {
        all.contains(current) || current.isEmpty ? all : all + [current]
    }
}

// MARK: - Toasts

/// A short line at the bottom of the app after a touch action, with nothing
/// to press: Android's `SnackbarDuration.Short` snackbar.
public struct Toast: Equatable, Sendable {
    /// Material's `SnackbarDuration.Short`, as on Android.
    public static let durationMs: Double = 4_000

    public let text: String
    /// When it goes by itself.
    public let untilMs: Double

    public init(_ text: String, nowMs: Double) {
        self.text = text
        untilMs = nowMs + Self.durationMs
    }

    /// What a touch enqueue says, as on Android (`confirmation` in
    /// `ui/Logic.kt`): "Playing next: X", "Added to queue: X", "Added N
    /// songs". Nothing for a play (the screen shows it), for no songs, or
    /// while nothing is loaded (the host then plays it at once, and Ride
    /// shows that).
    public static func enqueued(_ mode: EnqueueMode, titles: [String], nothingLoaded: Bool) -> String? {
        guard let first = titles.first, mode != .now, !nothingLoaded else { return nil }
        let what = titles.count == 1 ? first : QueueText.songs(titles.count)
        if mode == .next { return "Playing next: \(what)" }
        return titles.count == 1 ? "Added to queue: \(what)" : "Added \(what)"
    }
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
        VoiceCommandChip("queue", argument: "song", note: "adds it to the queue"),
        VoiceCommandChip("queue next", argument: "song", note: "plays it after this one"),
        VoiceCommandChip("over", note: "only ends the talk"),
    ]

    /// What smart commands also understand (PROTOCOL.md "Voice actions"),
    /// shown only while the host interprets (`hello.interpret`). None of them
    /// is in the grammar. The same words as Android's `SMART_COMMANDS`
    /// (`ui/Logic.kt`): keep the two lists identical.
    public static let smart: [VoiceCommandChip] = [
        VoiceCommandChip("repeat this song"),
        VoiceCommandChip("go back 30 seconds"),
        VoiceCommandChip("start over"),
        VoiceCommandChip("what's next"),
        VoiceCommandChip("remove the next song"),
        VoiceCommandChip("move the last song to next"),
        VoiceCommandChip("clear the queue"),
        VoiceCommandChip("undo"),
    ]

    /// The chips for a host that does (`interprets`) or does not interpret.
    public static func shown(interprets: Bool) -> [VoiceCommandChip] {
        interprets ? all + smart : all
    }

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
