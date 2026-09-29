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
        case .unknown: "unknown"
        }
    }

    /// Volume is local: the phone that heard it changes its own volume
    /// (earcon `ok`) and sends nothing.
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

    /// The wake-word rule (PROTOCOL.md "Commands", Wake word): normalise, drop
    /// leading "hey"/"ok"/"okay" words, then the phrase must start with
    /// "motoparty", "moto party" or "motor party" as whole words. Returns nil
    /// when it does not (conversation), "" for the bare wake word (arms the
    /// next phrase), else the normalised command text after it.
    public static func wakeCommand(_ text: String) -> String? {
        var words = normalize(text)[...]
        while let first = words.first, wakePrefixes.contains(first) { words.removeFirst() }
        for wake in wakeWords where words.starts(with: wake) {
            return words.dropFirst(wake.count).joined(separator: " ")
        }
        return nil
    }

    private static let wakePrefixes: Set<String> = ["hey", "ok", "okay"]
    private static let wakeWords: [[String]] = [["motoparty"], ["moto", "party"], ["motor", "party"]]
}

/// What one recognised phrase of a talk is (PROTOCOL.md "Commands", Wake word).
public enum HeardPhrase: Equatable, Sendable {
    /// Talk between the riders: never sent, never acted on.
    case conversation
    /// The bare wake word: the next phrase within the window is a command.
    case armed
    /// Command text (normalised, without the wake word), for `CommandParser`
    /// and `command.text`.
    case command(String)

    public var label: String {
        switch self {
        case .conversation: "conversation"
        case .armed: "armed"
        case .command: "command"
        }
    }
}

/// Classifies the phrases of one talk: the wake-word rule plus the arming
/// window. The client is never solo, so the wake word is always required.
public struct WakeGate: Sendable {
    /// How long a bare wake word keeps the next phrase a command.
    public static let armWindowMs: Double = 5_000

    private var armedAtMs: Double?

    public init() {}

    public var isArmed: Bool { armedAtMs != nil }

    /// A new talk (or its end) starts unarmed.
    public mutating func reset() { armedAtMs = nil }

    public mutating func classify(_ phrase: String, nowMs: Double) -> HeardPhrase {
        let armed = armedAtMs.map { nowMs - $0 <= Self.armWindowMs } ?? false
        armedAtMs = nil
        if let text = CommandParser.wakeCommand(phrase) {
            // A second bare wake word re-arms; "moto party next" after one is
            // simply the command.
            if text.isEmpty {
                armedAtMs = nowMs
                return .armed
            }
            return .command(text)
        }
        guard armed else { return .conversation }
        let text = CommandParser.normalize(phrase).joined(separator: " ")
        return text.isEmpty ? .conversation : .command(text)
    }
}
