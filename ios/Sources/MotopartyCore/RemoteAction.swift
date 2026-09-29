/// What a headset / lock-screen button does. In core (not the app target) so
/// the decoding of stored settings is tested on Linux.
///
/// There is no voice-command action: commands are spoken inside a talk, after
/// the wake word (PROTOCOL.md "Commands"), so one press is always talk.
public enum RemoteAction: String, CaseIterable, Identifiable, Sendable {
    case talk, playPause, next, previous, none

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .talk: "Talk on/off"
        case .playPause: "Music play/pause"
        case .next: "Next track"
        case .previous: "Previous track"
        case .none: "Nothing"
        }
    }

    /// A stored setting, or `fallback` when it is missing or names an action
    /// that no longer exists (a "command" saved before 2026-09-29).
    public static func stored(_ raw: String?, fallback: RemoteAction) -> RemoteAction {
        raw.flatMap(RemoteAction.init(rawValue:)) ?? fallback
    }

    /// Defaults per button: play/pause talks, next/previous skip tracks.
    public static let defaultPlayPause = RemoteAction.talk
    public static let defaultNext = RemoteAction.next
    public static let defaultPrevious = RemoteAction.previous
}
