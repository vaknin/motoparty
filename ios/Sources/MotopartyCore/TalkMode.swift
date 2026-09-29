import Foundation

/// How this phone takes part in one talk (PROTOCOL.md "Talk flow" and
/// "Host-mic talk"). Decided once, from the host's `talk.open`, and fixed
/// until that talk closes.
public enum TalkMode: Equatable, Sendable {
    /// Every phone uses its own headset microphone: call mode (HFP), capture,
    /// send, and, in a talk this phone opened, recognise its first phrase.
    case ownMic
    /// The host captures both riders (`talk.open{mic:"host"}`): this phone
    /// opens no microphone, stays in media mode, only plays what it receives
    /// and sends keepalives. The host recognises the passenger's commands.
    case hostMic

    /// The mode for a talk the host opened with `open`. `nil` (no
    /// `talk.open` to go on) is an own-mic talk.
    public init(open: TalkOpen?) {
        self.init(mic: open?.mic)
    }

    /// The mode for a talk learnt only from `state{talk:true}` (a join
    /// mid-talk, a missed `talk.open`): the host repeats `mic` there while a
    /// host-mic talk is open, so it opens exactly as from `talk.open`.
    public init(state: HostState) {
        self.init(mic: state.talk ? state.mic : nil)
    }

    public init(mic: TalkMic?) {
        self = mic == .host ? .hostMic : .ownMic
    }

    /// Opens a microphone, so it needs the record permission; a refused
    /// permission (or a call holding the mic) is a "cannot".
    public var needsMicrophone: Bool { self == .ownMic }

    /// Sends audio packets. A host-mic client sends keepalives only.
    public var sendsAudio: Bool { self == .ownMic }

    /// Switches the headset to call mode (HFP). A host-mic talk keeps the
    /// media session as it is: no profile switch, no route change.
    public var usesCallMode: Bool { self == .ownMic }

    /// Whether this phone runs speech recognition for commands in this talk:
    /// only the opener's phone, and only when the phone captures itself. In a
    /// host-mic talk the host recognises the passenger's first phrase, and
    /// this phone never sends `command.text`.
    public func recognisesCommands(opener: Role?, me: Role = .client) -> Bool {
        self == .ownMic && opener == me
    }

    /// What the "live" earcon waits for: the mic delivering, or (with no mic
    /// of our own) the talk's playback running.
    public var liveSignal: String {
        switch self {
        case .ownMic: "capture up"
        case .hostMic: "playback up"
        }
    }

    /// The `talk mode:` log line's value.
    public var logLabel: String {
        switch self {
        case .ownMic: "own mic"
        case .hostMic: "host-mic (receive only)"
        }
    }
}
