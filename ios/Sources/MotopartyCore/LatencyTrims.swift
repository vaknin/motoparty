import Foundation

/// The output the music plays on, as far as the latency trim cares: one
/// Bluetooth (or AirPlay, car) device, keyed by its address so its A2DP and
/// HFP faces are the same route, or this phone's own outputs (speaker,
/// receiver, wired, USB), which all share `localKey`. Android's
/// `OutputRoute` (`music/LatencyTrims.kt`). `name` is for Settings.
public struct OutputRoute: Equatable, Hashable, Sendable {
    public static let localKey = "local"
    public static let speaker = OutputRoute(key: localKey, name: "iPhone speaker", wireless: false)

    public let key: String
    public let name: String
    /// A device of its own (Bluetooth, AirPlay, CarPlay), not this phone's.
    public let wireless: Bool

    public init(key: String, name: String, wireless: Bool) {
        self.key = key
        self.name = name
        self.wireless = wireless
    }

    /// The route for `AVAudioSession.currentRoute.outputs.first`, from its
    /// `portType.rawValue`, `uid` and `portName`; nil (no output) is the speaker.
    public static func of(portType: String?, uid: String, name: String) -> OutputRoute {
        guard let portType else { return .speaker }
        switch portType {
        case "BluetoothA2DPOutput", "BluetoothHFP", "BluetoothLE", "AirPlay", "CarAudio":
            return OutputRoute(key: deviceKey(uid: uid, name: name), name: name.isEmpty ? "Bluetooth" : name,
                               wireless: true)
        case "Headphones", "LineOut":
            return OutputRoute(key: localKey, name: "Wired headphones", wireless: false)
        case "USBAudio":
            return OutputRoute(key: localKey, name: "USB audio", wireless: false)
        default:
            return .speaker
        }
    }

    /// A Bluetooth port's `uid` is its address plus the profile
    /// (`AA:BB:CC:DD:EE:FF-tacl` for A2DP, `…-tsco` for HFP): the address
    /// alone, so a talk does not change the trim. Without one, the name.
    static func deviceKey(uid: String, name: String) -> String {
        let address = uid.prefix(17)
        let isAddress = address.count == 17 && address.enumerated().allSatisfy { i, c in
            i % 3 == 2 ? c == ":" : c.isHexDigit
        }
        if isAddress { return address.uppercased() }
        if !uid.isEmpty { return uid }
        return "name:\(name)"
    }
}

/// The per-route latency trim (PROTOCOL.md "Music flow" step 4: each phone's
/// own output-latency trim), as on Android since 2026-09-29: one number set
/// for the AirPods and applied on the speaker too put the music ~240 ms off.
///
/// `local` is this phone's own outputs; `byDevice` a value per device; a
/// device without its own value gets `wirelessDefault`, where the old single
/// setting goes. Persisted as JSON by `AppSettings`.
public struct LatencyTrims: Equatable, Codable, Sendable {
    /// The range the Settings stepper allows (the old single trim's).
    public static let minMs = -500.0
    public static let maxMs = 1_000.0
    public static let stepMs = 10.0

    public var local: Double
    public var wirelessDefault: Double
    public var byDevice: [String: Double]

    public init(local: Double = 0, wirelessDefault: Double = 0, byDevice: [String: Double] = [:]) {
        self.local = Self.clamp(local)
        self.wirelessDefault = Self.clamp(wirelessDefault)
        self.byDevice = byDevice.mapValues(Self.clamp)
    }

    public func of(_ route: OutputRoute) -> Double {
        route.wireless ? byDevice[route.key] ?? wirelessDefault : local
    }

    /// `ms` (clamped) as the trim of `route` from now on.
    public func with(_ route: OutputRoute, _ ms: Double) -> LatencyTrims {
        var trims = self
        if route.wireless { trims.byDevice[route.key] = Self.clamp(ms) } else { trims.local = Self.clamp(ms) }
        return trims
    }

    public static func clamp(_ ms: Double) -> Double {
        ms.isFinite ? min(maxMs, max(minMs, ms)) : 0
    }

    public func encoded() -> Data? { try? JSONEncoder().encode(self) }

    /// The stored trims; without them, the single `latencyTrimMs` of before
    /// (`legacy`): it was set for a Bluetooth headset, so it becomes the
    /// wireless default and this phone's own outputs start at 0.
    public static func load(_ data: Data?, legacy: Double?) -> LatencyTrims {
        if let data, let trims = try? JSONDecoder().decode(LatencyTrims.self, from: data) {
            return LatencyTrims(local: trims.local, wirelessDefault: trims.wirelessDefault, byDevice: trims.byDevice)
        }
        return LatencyTrims(wirelessDefault: legacy ?? 0)
    }
}
