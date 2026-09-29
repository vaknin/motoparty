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

    init() {
        deviceName = defaults.string(forKey: "deviceName") ?? "iPhone"
        latencyTrimMs = defaults.object(forKey: "latencyTrimMs") as? Double ?? 0
        speechLanguage = defaults.string(forKey: "speechLanguage") ?? "en-US"
        // Removed 2026-09-29: the command mode, and headset buttons as talk
        // triggers (the earbuds sit inside the helmet).
        for key in ["commandMaxSeconds", "playPauseAction", "nextTrackAction", "previousTrackAction", "pauseCommandTriggers"] {
            defaults.removeObject(forKey: key)
        }
    }

    static let languages = ["en-US", "en-GB", "he-IL", "de-DE", "fr-FR", "es-ES", "it-IT", "ru-RU"]
}
#endif
