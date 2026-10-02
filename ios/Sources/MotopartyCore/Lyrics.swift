import Foundation

/// Synced lyrics (PROTOCOL.md "Tracks", "Lyrics", 2026-10-02): the host's
/// `GET /lyrics/<id>.json`, lines with their words already timed by the
/// host so both phones light up the same word at the same moment. Shown only
/// while this phone's lyrics toggle is on.
public struct Lyrics: Codable, Equatable, Sendable {
    public var id: String
    public var source: String?
    /// Sorted by `ms` (track position).
    public var lines: [LyricLine]

    public init(id: String, source: String? = nil, lines: [LyricLine]) {
        self.id = id; self.source = source; self.lines = lines
    }

    /// A 200 body; nil when it is not one (unknown fields are ignored).
    public static func decode(_ data: Data) -> Lyrics? {
        try? JSONDecoder().decode(Lyrics.self, from: data)
    }
}

/// One line. Empty `text` and no `words`: an instrumental break.
public struct LyricLine: Codable, Equatable, Sendable {
    public var ms: Int64
    public var text: String
    public var words: [LyricWord]

    public init(ms: Int64, text: String, words: [LyricWord]) {
        self.ms = ms; self.text = text; self.words = words
    }

    // A break may come without `text` or `words` at all.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ms = try c.decode(Int64.self, forKey: .ms)
        text = try c.decodeIfPresent(String.self, forKey: .text) ?? ""
        words = try c.decodeIfPresent([LyricWord].self, forKey: .words) ?? []
    }

    public var isBreak: Bool { words.isEmpty }
}

public struct LyricWord: Codable, Equatable, Sendable {
    public var ms: Int64
    public var text: String

    public init(ms: Int64, text: String) {
        self.ms = ms; self.text = text
    }
}

// MARK: - Timeline

/// Where lyrics position `t` is: the current line (nil before the first) and
/// how many of its words are sung (`ms <= t`).
public struct LyricsPosition: Equatable, Sendable {
    public var line: Int?
    public var sung: Int

    public init(line: Int?, sung: Int) {
        self.line = line; self.sung = sung
    }
}

extension Lyrics {
    /// PROTOCOL.md "Timeline": the last line with `ms <= t`, and the words of
    /// it with `ms <= t`. Binary search: this runs every frame while shown.
    public func position(atMs t: Int64) -> LyricsPosition {
        var lo = 0, hi = lines.count // first line with ms > t
        while lo < hi {
            let mid = (lo + hi) / 2
            if lines[mid].ms <= t { lo = mid + 1 } else { hi = mid }
        }
        guard lo > 0 else { return LyricsPosition(line: nil, sung: 0) }
        let line = lo - 1
        let sung = lines[line].words.reduce(0) { $0 + ($1.ms <= t ? 1 : 0) }
        return LyricsPosition(line: line, sung: sung)
    }

    /// The line before `line` (nil: none, or before the first line).
    public func previous(_ line: Int?) -> LyricLine? {
        guard let line, line > 0, line - 1 < lines.count else { return nil }
        return lines[line - 1]
    }

    /// Up to `count` lines after `line`; before the first line (nil), the
    /// first ones.
    public func upcoming(_ line: Int?, count: Int) -> [LyricLine] {
        let start = (line ?? -1) + 1
        guard start < lines.count, count > 0 else { return [] }
        return Array(lines[start..<min(lines.count, start + count)])
    }
}

/// This phone's lyrics offset per track (PROTOCOL.md: ±0.2 s steps, like the
/// output-latency trim): the lyrics position is `positionMs - offsetMs`, so a
/// negative offset shows them sooner. Persisted as JSON by `AppSettings`; a
/// track at 0 has no entry.
public struct LyricsOffsets: Equatable, Codable, Sendable {
    public static let stepMs: Int64 = 200
    public static let limitMs: Int64 = 10_000

    public var byTrack: [String: Int64]

    public init(byTrack: [String: Int64] = [:]) {
        self.byTrack = byTrack.mapValues(Self.clamp).filter { $0.value != 0 }
    }

    public func of(_ id: String) -> Int64 { byTrack[id] ?? 0 }

    /// The offset of `id` moved by `steps` steps (negative: sooner).
    public func stepped(_ id: String, by steps: Int) -> LyricsOffsets {
        var offsets = self
        let ms = Self.clamp(of(id) + Int64(steps) * Self.stepMs)
        offsets.byTrack[id] = ms == 0 ? nil : ms
        return offsets
    }

    public static func clamp(_ ms: Int64) -> Int64 { min(limitMs, max(-limitMs, ms)) }

    /// The lyrics position for track position `positionMs` (the host's
    /// timeline, as the Ride card shows it).
    public static func lyricsMs(positionMs: Double, offsetMs: Int64) -> Int64 {
        guard positionMs.isFinite else { return 0 }
        return Int64(positionMs.rounded(.down)) - offsetMs
    }

    public func encoded() -> Data? { try? JSONEncoder().encode(self) }

    public static func load(_ data: Data?) -> LyricsOffsets {
        guard let data, let offsets = try? JSONDecoder().decode(LyricsOffsets.self, from: data) else {
            return LyricsOffsets()
        }
        return LyricsOffsets(byTrack: offsets.byTrack)
    }
}

// MARK: - Fetch policy

/// One id's lyrics request (PROTOCOL.md): 200 = found, 404 = none (final for
/// this `music.load`), 503 = the host is still looking: again after 5 s, at
/// most 6 times, then given up until the id is loaded again. A request that
/// gets no answer at all counts as a 503 (the link may be coming back); any
/// other status is final like a 404.
public struct LyricsFetch: Equatable, Sendable {
    public static let retryDelayMs: Int64 = 5_000
    public static let maxRetries = 6

    public enum Phase: Equatable, Sendable {
        /// A request is out.
        case fetching
        /// A 503 came; the next request goes out after `retryDelayMs`.
        case waiting
        case found(Lyrics)
        /// 404, any other final status, or a body that is not lyrics.
        case missing
        /// Six 503s in a row after the first request.
        case gaveUp
    }

    public enum Step: Equatable, Sendable {
        case done
        case retry(afterMs: Int64)
    }

    public private(set) var phase: Phase = .fetching
    /// Requests made again after a 503.
    public private(set) var retries = 0

    public init() {}

    /// The answer to the request that is out: `status` nil for no answer,
    /// `body` the 200's data.
    public mutating func answered(status: Int?, body: Data? = nil) -> Step {
        guard phase == .fetching else { return .done }
        switch status {
        case 200:
            if let body, let lyrics = Lyrics.decode(body) {
                phase = .found(lyrics)
            } else {
                phase = .missing
            }
            return .done
        case 503, nil:
            guard retries < Self.maxRetries else {
                phase = .gaveUp
                return .done
            }
            phase = .waiting
            return .retry(afterMs: Self.retryDelayMs)
        default:
            phase = .missing
            return .done
        }
    }

    /// The retry's request goes out; false when there is none to make.
    public mutating func retryStarted() -> Bool {
        guard phase == .waiting else { return false }
        retries += 1
        phase = .fetching
        return true
    }

    /// Still asking the host (a request out, or waiting to ask again).
    public var isPending: Bool { phase == .fetching || phase == .waiting }

    public var lyrics: Lyrics? {
        if case .found(let lyrics) = phase { return lyrics }
        return nil
    }
}

// MARK: - LRC to lines

/// PROTOCOL.md "LRC to lines" and "Words": what the host serves, made from an
/// LRC text. The client only decodes the host's lines; this is here so the
/// rules are tested against the same vectors as the Kotlin and Python ones.
/// Works on Unicode scalars throughout: a Swift `Character` would join `\r\n`
/// into one, and a word's length is its count of code points.
public enum LRC {
    /// Per code point of the words before a word, while that fits before the
    /// next line.
    public static let msPerCodePoint: Int64 = 75

    public static func parse(_ lrc: String) -> [LyricLine] {
        var stamped: [(ms: Int64, order: Int, text: String)] = []
        for raw in lrc.unicodeScalars.split(separator: "\n", omittingEmptySubsequences: false) {
            var line = Array(raw)
            if line.last == "\r" { line.removeLast() }
            var stamps: [Int64] = []
            var i = 0
            while case let (ms, end)? = stamp(line, at: i) {
                stamps.append(ms)
                i = end
            }
            guard !stamps.isEmpty else { continue }
            let text = trimmed(line[i...])
            for ms in stamps { stamped.append((ms, stamped.count, text)) }
        }
        // Stable: equal times keep source order.
        stamped.sort { ($0.ms, $0.order) < ($1.ms, $1.order) }
        return stamped.indices.map { n in
            let next = n + 1 < stamped.count ? stamped[n + 1].ms : nil
            return LyricLine(ms: stamped[n].ms, text: stamped[n].text,
                             words: words(of: stamped[n].text, at: stamped[n].ms, nextMs: next))
        }
    }

    /// The words of a line's text and when each starts.
    public static func words(of text: String, at lineMs: Int64, nextMs: Int64?) -> [LyricWord] {
        let pieces = text.unicodeScalars.split(whereSeparator: isBlank)
        let total = Int64(pieces.reduce(0) { $0 + $1.count })
        let squeeze = nextMs.map { lineMs + msPerCodePoint * total > $0 } ?? false
        var before: Int64 = 0
        return pieces.map { piece in
            let ms = squeeze
                ? lineMs + before * (nextMs! - lineMs) / total // both >= 0: division is floor
                : lineMs + msPerCodePoint * before
            before += Int64(piece.count)
            return LyricWord(ms: ms, text: String(String.UnicodeScalarView(piece)))
        }
    }

    /// `[m:ss]` or `[m:ss.f]` at `i`: its ms and the index after it.
    /// (Numbers beyond 9 digits are not a stamp, rather than overflowing.)
    private static func stamp(_ s: [Unicode.Scalar], at start: Int) -> (Int64, Int)? {
        var i = start
        guard i < s.count, s[i] == "[" else { return nil }
        i += 1
        guard case let (minutes, afterMinutes)? = digits(s, at: i), afterMinutes < s.count, s[afterMinutes] == ":" else {
            return nil
        }
        guard case let (seconds, afterSeconds)? = digits(s, at: afterMinutes + 1) else { return nil }
        i = afterSeconds
        var fraction: Int64 = 0
        if i < s.count, s[i] == "." {
            var afterFraction = i + 1
            while afterFraction < s.count, isDigit(s[afterFraction]) { afterFraction += 1 }
            guard afterFraction > i + 1 else { return nil }
            // The fraction's digits padded with zeros or cut to three.
            var three = Array(s[(i + 1)..<min(afterFraction, i + 4)])
            while three.count < 3 { three.append("0") }
            fraction = three.reduce(0) { $0 * 10 + Int64($1.value - 48) }
            i = afterFraction
        }
        guard i < s.count, s[i] == "]" else { return nil }
        return (minutes * 60_000 + seconds * 1_000 + fraction, i + 1)
    }

    /// A run of ASCII digits at `i` (at least one), its value and the index after it.
    private static func digits(_ s: [Unicode.Scalar], at start: Int) -> (Int64, Int)? {
        var i = start
        var value: Int64 = 0
        while i < s.count, isDigit(s[i]) {
            guard i - start < 9 else { return nil }
            value = value * 10 + Int64(s[i].value - 48)
            i += 1
        }
        guard i > start else { return nil }
        return (value, i)
    }

    private static func isDigit(_ c: Unicode.Scalar) -> Bool { ("0"..."9").contains(c) }

    private static func isBlank(_ c: Unicode.Scalar) -> Bool { c == " " || c == "\t" }

    private static func trimmed(_ s: ArraySlice<Unicode.Scalar>) -> String {
        var view = String.UnicodeScalarView()
        guard let first = s.firstIndex(where: { !isBlank($0) }), let last = s.lastIndex(where: { !isBlank($0) }) else {
            return ""
        }
        view.append(contentsOf: s[first...last])
        return String(view)
    }
}
