import Foundation

/// Every control message in PROTOCOL.md. Encodes to / decodes from one JSON
/// object with a string field `t`. Optional fields are omitted when nil.
public enum ControlMessage: Equatable, Sendable {
    case hello(Hello)
    case ping(Ping)
    case pong(Pong)
    case talkOpen(TalkOpen)
    case talkClose(TalkClose)
    case musicLoad(MusicLoad)
    case musicReady(MusicReady)
    case musicError(MusicError)
    case musicPlay(MusicPlay)
    case musicPause(MusicPause)
    case musicStop
    case musicControl(MusicControl)
    case commandText(CommandText)
    case announce(Announce)
    case state(HostState)
    case bye(Bye)
    /// A message whose `t` this build does not know. Callers ignore it.
    case unknown(type: String)

    public var type: String {
        switch self {
        case .hello: "hello"
        case .ping: "ping"
        case .pong: "pong"
        case .talkOpen: "talk.open"
        case .talkClose: "talk.close"
        case .musicLoad: "music.load"
        case .musicReady: "music.ready"
        case .musicError: "music.error"
        case .musicPlay: "music.play"
        case .musicPause: "music.pause"
        case .musicStop: "music.stop"
        case .musicControl: "music.control"
        case .commandText: "command.text"
        case .announce: "announce"
        case .state: "state"
        case .bye: "bye"
        case .unknown(let t): t
        }
    }
}

// MARK: - Field enums

public enum Role: String, Codable, Sendable { case host, client }

public enum TalkCloseReason: String, Codable, Sendable {
    case trigger, silence, link
    /// The sender cannot open its microphone (PROTOCOL.md "Talk flow" step 1).
    case unavailable
}

/// Button presses only. Volume is local (PROTOCOL.md "Commands"), so it is
/// never a `music.control` action; a `volumeUp`/`volumeDown` on the wire is a
/// malformed message and is dropped.
public enum MusicAction: String, Codable, Sendable, CaseIterable {
    case pause, resume, next, previous
}

public enum Earcon: String, Codable, Sendable { case ok, error }

// MARK: - Payloads

public struct Hello: Codable, Equatable, Sendable {
    public var proto: Int
    public var role: Role
    public var name: String
    public var voicePort: Int?
    public var httpPort: Int?

    public init(proto: Int = 1, role: Role, name: String, voicePort: Int? = nil, httpPort: Int? = nil) {
        self.proto = proto
        self.role = role
        self.name = name
        self.voicePort = voicePort
        self.httpPort = httpPort
    }
}

public struct Ping: Codable, Equatable, Sendable {
    public var id: Int
    public var t0: Int64
    public init(id: Int, t0: Int64) { self.id = id; self.t0 = t0 }
}

public struct Pong: Codable, Equatable, Sendable {
    public var id: Int
    public var t0: Int64
    public var t1: Int64
    public var t2: Int64
    public init(id: Int, t0: Int64, t1: Int64, t2: Int64) {
        self.id = id; self.t0 = t0; self.t1 = t1; self.t2 = t2
    }
}

public struct TalkOpen: Codable, Equatable, Sendable {
    public var by: Role
    public init(by: Role) { self.by = by }
}

public struct TalkClose: Codable, Equatable, Sendable {
    public var by: Role
    public var reason: TalkCloseReason
    public init(by: Role, reason: TalkCloseReason) { self.by = by; self.reason = reason }
}

public struct MusicLoad: Codable, Equatable, Sendable {
    public var id: String
    public var path: String
    public var title: String
    public var artist: String
    public var album: String?
    public var durationMs: Int64

    public init(id: String, path: String, title: String, artist: String, album: String? = nil, durationMs: Int64) {
        self.id = id; self.path = path; self.title = title
        self.artist = artist; self.album = album; self.durationMs = durationMs
    }
}

public struct MusicReady: Codable, Equatable, Sendable {
    public var id: String
    public init(id: String) { self.id = id }
}

public struct MusicError: Codable, Equatable, Sendable {
    public var id: String
    public var message: String
    public init(id: String, message: String) { self.id = id; self.message = message }
}

public struct MusicPlay: Codable, Equatable, Sendable {
    public var id: String
    public var positionMs: Int64
    public var atHostTimeMs: Int64
    public init(id: String, positionMs: Int64, atHostTimeMs: Int64) {
        self.id = id; self.positionMs = positionMs; self.atHostTimeMs = atHostTimeMs
    }
}

public struct MusicPause: Codable, Equatable, Sendable {
    public var id: String
    public var positionMs: Int64
    public init(id: String, positionMs: Int64) { self.id = id; self.positionMs = positionMs }
}

public struct MusicControl: Codable, Equatable, Sendable {
    public var action: MusicAction
    public init(action: MusicAction) { self.action = action }
}

public struct CommandText: Codable, Equatable, Sendable {
    public var text: String
    public var lang: String
    public init(text: String, lang: String) { self.text = text; self.lang = lang }
}

public struct Announce: Codable, Equatable, Sendable {
    public var text: String
    public var earcon: Earcon?
    public init(text: String, earcon: Earcon? = nil) { self.text = text; self.earcon = earcon }
}

public struct HostState: Codable, Equatable, Sendable {
    public struct Music: Codable, Equatable, Sendable {
        public var id: String
        public var title: String
        public var artist: String
        public var playing: Bool
        public var positionMs: Int64
        public var atHostTimeMs: Int64
        public var durationMs: Int64

        public init(id: String, title: String, artist: String, playing: Bool,
                    positionMs: Int64, atHostTimeMs: Int64, durationMs: Int64) {
            self.id = id; self.title = title; self.artist = artist; self.playing = playing
            self.positionMs = positionMs; self.atHostTimeMs = atHostTimeMs; self.durationMs = durationMs
        }

        public var anchor: MusicAnchor {
            MusicAnchor(positionMs: positionMs, atHostTimeMs: atHostTimeMs)
        }
    }

    public struct QueueItem: Codable, Equatable, Sendable {
        public var id: String
        public var title: String
        public var artist: String
        public init(id: String, title: String, artist: String) {
            self.id = id; self.title = title; self.artist = artist
        }
    }

    public var talk: Bool
    public var music: Music?
    public var queue: [QueueItem]

    public init(talk: Bool, music: Music? = nil, queue: [QueueItem] = []) {
        self.talk = talk; self.music = music; self.queue = queue
    }

    // `queue` is required (possibly empty); `music` is omitted when nothing is loaded.
}

public struct Bye: Codable, Equatable, Sendable {
    public var reason: String?
    public init(reason: String? = nil) { self.reason = reason }
}

// MARK: - Codec

public enum ControlCodecError: Error, Equatable {
    /// Not JSON, or not a JSON object. The connection must be closed.
    case invalidJSON
    /// A JSON object without a string `t`. Drop and log.
    case missingType
    /// A known type with a missing or mistyped required field. Drop and log;
    /// the connection stays up.
    case invalidFields(type: String, detail: String)

    /// Whether PROTOCOL.md requires closing the connection.
    public var closesConnection: Bool { self == .invalidJSON }
}

public enum ControlCodec {
    private struct Empty: Codable {}

    /// Decodes one JSON object. Unknown `t` → `.unknown`; unknown fields are
    /// ignored. Throws `ControlCodecError` (see `closesConnection`).
    public static func decode(_ data: Data) throws -> ControlMessage {
        let object: Any
        do {
            object = try JSONSerialization.jsonObject(with: data)
        } catch {
            throw ControlCodecError.invalidJSON
        }
        guard let dict = object as? [String: Any] else { throw ControlCodecError.invalidJSON }
        guard let t = dict["t"] as? String else { throw ControlCodecError.missingType }

        let decoder = JSONDecoder()
        func d<T: Decodable>(_: T.Type) throws -> T {
            do {
                return try decoder.decode(T.self, from: data)
            } catch {
                throw ControlCodecError.invalidFields(type: t, detail: String(describing: error))
            }
        }
        switch t {
        case "hello": return .hello(try d(Hello.self))
        case "ping": return .ping(try d(Ping.self))
        case "pong": return .pong(try d(Pong.self))
        case "talk.open": return .talkOpen(try d(TalkOpen.self))
        case "talk.close": return .talkClose(try d(TalkClose.self))
        case "music.load": return .musicLoad(try d(MusicLoad.self))
        case "music.ready": return .musicReady(try d(MusicReady.self))
        case "music.error": return .musicError(try d(MusicError.self))
        case "music.play": return .musicPlay(try d(MusicPlay.self))
        case "music.pause": return .musicPause(try d(MusicPause.self))
        case "music.stop": return .musicStop
        case "music.control": return .musicControl(try d(MusicControl.self))
        case "command.text": return .commandText(try d(CommandText.self))
        case "announce": return .announce(try d(Announce.self))
        case "state": return .state(try d(HostState.self))
        case "bye": return .bye(try d(Bye.self))
        default: return .unknown(type: t)
        }
    }

    /// Encodes to compact UTF-8 JSON. Encoding `.unknown` yields `{"t":…}`.
    public static func encode(_ message: ControlMessage) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.withoutEscapingSlashes]
        let t = message.type
        switch message {
        case .hello(let p): return try encoder.encode(Tagged(t: t, payload: p))
        case .ping(let p): return try encoder.encode(Tagged(t: t, payload: p))
        case .pong(let p): return try encoder.encode(Tagged(t: t, payload: p))
        case .talkOpen(let p): return try encoder.encode(Tagged(t: t, payload: p))
        case .talkClose(let p): return try encoder.encode(Tagged(t: t, payload: p))
        case .musicLoad(let p): return try encoder.encode(Tagged(t: t, payload: p))
        case .musicReady(let p): return try encoder.encode(Tagged(t: t, payload: p))
        case .musicError(let p): return try encoder.encode(Tagged(t: t, payload: p))
        case .musicPlay(let p): return try encoder.encode(Tagged(t: t, payload: p))
        case .musicPause(let p): return try encoder.encode(Tagged(t: t, payload: p))
        case .musicStop: return try encoder.encode(Tagged(t: t, payload: Empty()))
        case .musicControl(let p): return try encoder.encode(Tagged(t: t, payload: p))
        case .commandText(let p): return try encoder.encode(Tagged(t: t, payload: p))
        case .announce(let p): return try encoder.encode(Tagged(t: t, payload: p))
        case .state(let p): return try encoder.encode(Tagged(t: t, payload: p))
        case .bye(let p): return try encoder.encode(Tagged(t: t, payload: p))
        case .unknown: return try encoder.encode(Tagged(t: t, payload: Empty()))
        }
    }

    /// Payload fields plus `t`, flattened into one JSON object.
    private struct Tagged<P: Encodable>: Encodable {
        let t: String
        let payload: P
        private enum Key: String, CodingKey { case t }

        func encode(to encoder: any Encoder) throws {
            var c = encoder.container(keyedBy: Key.self)
            try c.encode(t, forKey: .t)
            try payload.encode(to: encoder)
        }
    }
}
