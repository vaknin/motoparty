/// The voice-command grammar of PROTOCOL.md "Commands". The host parses every
/// `command.text`; the client runs the same parser on its own utterance first,
/// because volume is local and is never sent.
///
/// Pure Swift: `Unicode.Scalar.Properties.generalCategory` is stdlib, so the
/// normalisation is identical on Linux and on iOS (Foundation's category
/// tables are not).
public enum Command: Equatable, Sendable {
    case play(kind: Kind, query: String)
    case pause
    case resume
    case next
    case previous
    case volumeUp
    case volumeDown
    /// Close the talk and change nothing else (PROTOCOL.md "Commands").
    case end
    /// The host announces the current track, after the talk it ends.
    case nowPlaying
    /// The host shuffles the upcoming queue and announces "Shuffled".
    case shuffle
    case unknown

    public enum Kind: String, Equatable, Sendable, CaseIterable {
        case song, album, artist, playlist
    }

    /// The `action` name used in `fixtures/commands.json` and by the host.
    public var action: String {
        switch self {
        case .play: "play"
        case .pause: "pause"
        case .resume: "resume"
        case .next: "next"
        case .previous: "previous"
        case .volumeUp: "volumeUp"
        case .volumeDown: "volumeDown"
        case .end: "end"
        case .nowPlaying: "nowplaying"
        case .shuffle: "shuffle"
        case .unknown: "unknown"
        }
    }

    /// Volume is local: the phone that heard it changes its own media volume
    /// (earcon `ok`), sends no `command.text`, and ends the talk itself.
    public var isVolume: Bool { self == .volumeUp || self == .volumeDown }
}

public enum CommandParser {
    private static let phrases: [String: Command] = [
        "pause": .pause, "stop": .pause,
        "resume": .resume, "continue": .resume,
        "next": .next, "skip": .next,
        "previous": .previous, "back": .previous,
        "volume up": .volumeUp, "louder": .volumeUp,
        "volume down": .volumeDown, "quieter": .volumeDown,
        "over": .end, "end talk": .end, "hang up": .end,
        "what's playing": .nowPlaying, "whats playing": .nowPlaying,
        "what is playing": .nowPlaying, "what song is this": .nowPlaying,
        "shuffle": .shuffle,
    ]

    private static let kinds: [String: Command.Kind] =
        Dictionary(uniqueKeysWithValues: Command.Kind.allCases.map { ($0.rawValue, $0) })

    /// Lowercase; `’` (U+2019) → `'`; every code point that is not a letter
    /// (L*), mark (M*) or number (N*), not `'` and not whitespace → a space;
    /// collapse whitespace; trim.
    public static func normalize(_ text: String) -> [String] {
        var cleaned = String.UnicodeScalarView()
        for scalar in text.lowercased().unicodeScalars {
            if scalar == "\u{2019}" || scalar == "'" {
                cleaned.append("'")
            } else if keep(scalar) {
                cleaned.append(scalar)
            } else {
                cleaned.append(" ")
            }
        }
        return String(cleaned).split(separator: " ").map(String.init)
    }

    private static func keep(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter,
             .nonspacingMark, .spacingMark, .enclosingMark,
             .decimalNumber, .letterNumber, .otherNumber:
            true
        default:
            false
        }
    }

    /// Normalises, drops leading "hey"/"please" words and one trailing
    /// "please", then matches the grammar. Anything else is `.unknown`
    /// (the host answers "Didn't catch that").
    public static func parse(_ text: String) -> Command {
        var words = normalize(text)
        while let first = words.first, first == "hey" || first == "please" { words.removeFirst() }
        if words.last == "please" { words.removeLast() }
        guard !words.isEmpty else { return .unknown }

        if words[0] == "play" {
            var rest = words.dropFirst()
            var kind = Command.Kind.song
            if rest.count >= 2, rest[rest.startIndex] == "the", let k = kinds[rest[rest.startIndex + 1]] {
                kind = k
                rest = rest.dropFirst(2)
            } else if let first = rest.first, let k = kinds[first] {
                kind = k
                rest = rest.dropFirst()
            }
            // An empty query is not a command.
            return rest.isEmpty ? .unknown : .play(kind: kind, query: rest.joined(separator: " "))
        }
        return phrases[words.joined(separator: " ")] ?? .unknown
    }
}

/// What the client does with its one command of a talk (PROTOCOL.md
/// "Commands", "Effect on the talk: every command ends it"). The host ends
/// the talk for every command it is sent; volume is never sent, so the client
/// ends that talk itself, with the `talk.close` a Talk press sends.
public enum ClientCommand: Equatable, Sendable {
    /// Change this phone's media volume, then `talk.close{by:"client",
    /// reason:"trigger"}`.
    case volume(up: Bool, VolumeTiming)
    /// `command.text`: the host acts and closes the talk.
    case send(String)

    public enum VolumeTiming: Equatable, Sendable {
        /// The app owns the volume (keys armed): its level is the loudness of
        /// music, talk and cues alike, so it changes at once, the `ok` earcon
        /// plays on the talk route, which is up, and the close follows it.
        case inTalk
        /// The system volume is the volume, and during a talk that is the
        /// call volume (HFP), not the media one: close first, and step (with
        /// the earcon) once the session is back on the media route.
        case afterMediaRoute
    }

    /// `text`: a command text from `FirstPhraseGate` (so it parses).
    /// `volumeArmed`: the app level is the volume (`VolumeKeyGate.armed`).
    public static func route(_ text: String, volumeArmed: Bool) -> ClientCommand {
        switch CommandParser.parse(text) {
        case .volumeUp: .volume(up: true, volumeArmed ? .inTalk : .afterMediaRoute)
        case .volumeDown: .volume(up: false, volumeArmed ? .inTalk : .afterMediaRoute)
        default: .send(text)
        }
    }
}

/// What one recognised phrase of a talk is (PROTOCOL.md "Commands").
public enum HeardPhrase: Equatable, Sendable {
    /// Talk between the riders: never sent, never acted on.
    case conversation
    /// Command text (normalised up to and including trim, fillers kept: the
    /// parser drops them), for `CommandParser` and `command.text`.
    case command(String)

    public var label: String {
        switch self {
        case .conversation: "conversation"
        case .command: "command"
        }
    }
}

/// One phone's view of one talk (PROTOCOL.md "Commands", The first phrase
/// decides). Pure: every time is passed in, in ms on one monotonic clock.
public struct FirstPhraseGate: Sendable {
    public enum Role: String, Sendable {
        /// This phone opened the talk (the side in the host's `talk.open{by}`).
        case opener
        /// The other side opened it: never commands.
        case other
        /// The host's talk has no client: every non-empty phrase, no window.
        /// The iPhone is always the client, so it is never solo.
        case solo
    }

    /// `FIRST_PHRASE_MS`: how long after the live earcon the opener's first
    /// phrase may arrive (inclusive).
    public static let firstPhraseMs: Double = 8_000

    public let role: Role
    /// When this phone played its live earcon.
    public let liveAtMs: Double
    private var used = false

    public init(role: Role, liveAtMs: Double) {
        self.role = role
        self.liveAtMs = liveAtMs
    }

    /// Nothing this phone hears from `nowMs` on can be a command, so it may
    /// stop recognising for the rest of the talk.
    public func isSpent(atMs nowMs: Double) -> Bool {
        switch role {
        case .other: true
        case .solo: false
        case .opener: used || nowMs - liveAtMs > Self.firstPhraseMs
        }
    }

    /// Classifies one phrase whose result arrived at `nowMs`. A phrase that
    /// is empty after normalisation is conversation and spends nothing.
    public mutating func classify(_ phrase: String, nowMs: Double) -> HeardPhrase {
        let text = CommandParser.normalize(phrase).joined(separator: " ")
        guard !text.isEmpty else { return .conversation }
        switch role {
        case .other:
            return .conversation
        case .solo:
            return .command(text)
        case .opener:
            guard !isSpent(atMs: nowMs) else { return .conversation }
            used = true
            // An unparsed first phrase is conversation: no "Didn't catch that".
            return CommandParser.parse(text) == .unknown ? .conversation : .command(text)
        }
    }
}
