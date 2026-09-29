#if os(iOS)
import Foundation

/// What a headset / lock-screen button does.
enum RemoteAction: String, CaseIterable, Identifiable {
    case talk, command, playPause, next, previous, none

    var id: String { rawValue }

    var label: String {
        switch self {
        case .talk: "Talk on/off"
        case .command: "Voice command"
        case .playPause: "Music play/pause"
        case .next: "Next track"
        case .previous: "Previous track"
        case .none: "Nothing"
        }
    }
}

final class AppSettings: ObservableObject {
    private let defaults = UserDefaults.standard

    @Published var deviceName: String { didSet { defaults.set(deviceName, forKey: "deviceName") } }
    /// Output-latency trim (ms). Positive = this phone's audio comes out late
    /// (Bluetooth), so its player runs ahead by this much.
    @Published var latencyTrimMs: Double { didSet { defaults.set(latencyTrimMs, forKey: "latencyTrimMs") } }
    /// BCP-47 tag for on-device ASR and TTS.
    @Published var speechLanguage: String { didSet { defaults.set(speechLanguage, forKey: "speechLanguage") } }
    /// Headset play/pause (AirPods single press) / lock-screen play-pause.
    @Published var playPauseAction: RemoteAction { didSet { defaults.set(playPauseAction.rawValue, forKey: "playPauseAction") } }
    /// Headset next track (AirPods double press).
    @Published var nextTrackAction: RemoteAction { didSet { defaults.set(nextTrackAction.rawValue, forKey: "nextTrackAction") } }
    /// Headset previous track (AirPods triple press).
    @Published var previousTrackAction: RemoteAction { didSet { defaults.set(previousTrackAction.rawValue, forKey: "previousTrackAction") } }
    /// iOS also sends "pause" when an AirPod is taken out of the ear, so the
    /// explicit pause command is ignored unless this is on.
    @Published var pauseCommandTriggers: Bool { didSet { defaults.set(pauseCommandTriggers, forKey: "pauseCommandTriggers") } }
    /// Longest voice command recording, seconds.
    @Published var commandMaxSeconds: Double { didSet { defaults.set(commandMaxSeconds, forKey: "commandMaxSeconds") } }

    init() {
        deviceName = defaults.string(forKey: "deviceName") ?? "iPhone"
        latencyTrimMs = defaults.object(forKey: "latencyTrimMs") as? Double ?? 0
        speechLanguage = defaults.string(forKey: "speechLanguage") ?? "en-US"
        playPauseAction = RemoteAction(rawValue: defaults.string(forKey: "playPauseAction") ?? "") ?? .talk
        nextTrackAction = RemoteAction(rawValue: defaults.string(forKey: "nextTrackAction") ?? "") ?? .command
        previousTrackAction = RemoteAction(rawValue: defaults.string(forKey: "previousTrackAction") ?? "") ?? .previous
        pauseCommandTriggers = defaults.object(forKey: "pauseCommandTriggers") as? Bool ?? false
        commandMaxSeconds = defaults.object(forKey: "commandMaxSeconds") as? Double ?? 6
    }

    static let languages = ["en-US", "en-GB", "he-IL", "de-DE", "fr-FR", "es-ES", "it-IT", "ru-RU"]
}
#endif
