#if os(iOS)
import Foundation
import MotopartyCore

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

    init() {
        deviceName = defaults.string(forKey: "deviceName") ?? "iPhone"
        latencyTrimMs = defaults.object(forKey: "latencyTrimMs") as? Double ?? 0
        speechLanguage = defaults.string(forKey: "speechLanguage") ?? "en-US"
        playPauseAction = RemoteAction.stored(defaults.string(forKey: "playPauseAction"), fallback: .defaultPlayPause)
        nextTrackAction = RemoteAction.stored(defaults.string(forKey: "nextTrackAction"), fallback: .defaultNext)
        previousTrackAction = RemoteAction.stored(defaults.string(forKey: "previousTrackAction"), fallback: .defaultPrevious)
        pauseCommandTriggers = defaults.object(forKey: "pauseCommandTriggers") as? Bool ?? false
        // The removed command mode's setting (2026-09-29).
        defaults.removeObject(forKey: "commandMaxSeconds")
    }

    static let languages = ["en-US", "en-GB", "he-IL", "de-DE", "fr-FR", "es-ES", "it-IT", "ru-RU"]
}
#endif
