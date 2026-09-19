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
