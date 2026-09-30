#if os(iOS)
import Foundation
import MotopartyCore

final class AppSettings: ObservableObject {
    private let defaults = UserDefaults.standard

    @Published var deviceName: String { didSet { defaults.set(deviceName, forKey: "deviceName") } }
    /// Output-latency trim (ms). Positive = this phone's audio comes out late
    /// (Bluetooth), so its player runs ahead by this much.
    @Published var latencyTrimMs: Double { didSet { defaults.set(latencyTrimMs, forKey: "latencyTrimMs") } }
    /// Whether the player also runs ahead by `AVAudioSession.outputLatency`
    /// (audit M2: AVPlayer may already account for it, which would count it
    /// twice). On = the behaviour so far, until a click-track session decides.
    @Published var compensateOutputLatency: Bool {
        didSet { defaults.set(compensateOutputLatency, forKey: "compensateOutputLatency") }
    }
    /// BCP-47 tag for on-device ASR and TTS.
    @Published var speechLanguage: String { didSet { defaults.set(speechLanguage, forKey: "speechLanguage") } }
    /// The app volume level (`AppVolume`, 0...16) the keys and spoken
    /// commands last set; the next link starts there. Not a published
    /// setting: `AppModel.volumeLevel` is what the UI shows.
    var appVolumeLevel: Int {
        get { AppVolume.clamp(defaults.object(forKey: "appVolumeLevel") as? Int ?? AppVolume.defaultLevel) }
        set { defaults.set(AppVolume.clamp(newValue), forKey: "appVolumeLevel") }
    }
    /// The last host this client linked to; discovery prefers it when several answer.
    var lastHostName: String? {
        get { defaults.string(forKey: "lastHostName") }
        set { defaults.set(newValue, forKey: "lastHostName") }
    }
    /// The Search tab's recent searches and recently played tracks.
    var browseHistory: BrowseHistory {
        get { BrowseHistory.decoded(defaults.data(forKey: "browseHistory")) }
        set { defaults.set(newValue.encoded(), forKey: "browseHistory") }
    }

    init() {
        deviceName = defaults.string(forKey: "deviceName") ?? "iPhone"
        latencyTrimMs = defaults.object(forKey: "latencyTrimMs") as? Double ?? 0
        compensateOutputLatency = defaults.object(forKey: "compensateOutputLatency") as? Bool ?? true
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
