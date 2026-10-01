#if os(iOS)
import Foundation
import MotopartyCore

final class AppSettings: ObservableObject {
    private let defaults = UserDefaults.standard

    @Published var deviceName: String { didSet { defaults.set(deviceName, forKey: "deviceName") } }
    /// Output-latency trims (ms), one per output route (`LatencyTrims`, as on
    /// Android). Positive = this phone's audio comes out late on that route
    /// (Bluetooth), so its player runs ahead by this much.
    @Published var trims: LatencyTrims { didSet { defaults.set(trims.encoded(), forKey: "latencyTrims") } }
    /// The "live" beep when a talk's microphone is on. Off by default, as on
    /// Android (`Settings.liveBeep`).
    @Published var liveBeep: Bool { didSet { defaults.set(liveBeep, forKey: "liveBeep") } }
    /// The screen stays on while the Ride tab is showing (Android's
    /// `UiPrefs.keepScreenOn`). Off by default: there is no charger on the bike.
    @Published var keepScreenOn: Bool { didSet { defaults.set(keepScreenOn, forKey: "keepScreenOn") } }
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
        // Before 2026-10-01: one trim for every route. It was set for a
        // Bluetooth headset, so it becomes the wireless default.
        let legacyTrim = defaults.object(forKey: "latencyTrimMs") as? Double
        let loadedTrims = LatencyTrims.load(defaults.data(forKey: "latencyTrims"), legacy: legacyTrim)
        trims = loadedTrims
        if legacyTrim != nil {
            defaults.set(loadedTrims.encoded(), forKey: "latencyTrims")
            defaults.removeObject(forKey: "latencyTrimMs")
        }
        liveBeep = defaults.object(forKey: "liveBeep") as? Bool ?? false
        keepScreenOn = defaults.object(forKey: "keepScreenOn") as? Bool ?? false
        compensateOutputLatency = defaults.object(forKey: "compensateOutputLatency") as? Bool ?? true
        speechLanguage = defaults.string(forKey: "speechLanguage") ?? "en-US"
        // Removed 2026-09-29: the command mode, and headset buttons as talk
        // triggers (the earbuds sit inside the helmet).
        for key in ["commandMaxSeconds", "playPauseAction", "nextTrackAction", "previousTrackAction", "pauseCommandTriggers"] {
            defaults.removeObject(forKey: key)
        }
    }

    /// The speech languages Settings offers: Android's 14, and the one set.
    var languages: [String] { SpeechLanguages.offered(current: speechLanguage) }
}
#endif
