import Foundation

/// Which microphone (and with it which output) an own-mic talk uses. Pure:
/// the app maps `AVAudioSession`'s ports to `TalkPort`s and applies the
/// `TalkRoutePlan` this returns (`SessionController`).
///
/// The order is Android's `AudioRouter.choose`: a Bluetooth headset first,
/// then a wired headset, then a USB one; with none of them, the phone's own
/// microphone and the loudspeaker (`.defaultToSpeaker`, never the receiver).
/// On iOS the output follows the chosen input, since a headset's microphone
/// and its earpieces are one route: a wired headset's mic takes the talk to
/// its headphones, a USB headset's to its USB output.
public enum TalkRoute {
    /// The kind of an available input port, as far as the talk cares.
    public enum Kind: Int, Comparable, Sendable, CaseIterable {
        /// `.bluetoothHFP`: earbuds in call mode (AirPods, Redmi Buds).
        case bluetoothHFP = 0
        /// `.headsetMic`: a wired headset (Lightning or a USB-C adapter's
        /// analog jack). Its output is `.headphones`.
        case headsetMic = 1
        /// `.usbAudio`: a USB audio interface or USB-C headset.
        case usbAudio = 2
        /// Anything else (`.builtInMic`, `.lineIn`, `.carAudio`, …): never
        /// chosen; the phone picks its own default.
        case other = 3

        public static func < (a: Kind, b: Kind) -> Bool { a.rawValue < b.rawValue }

        /// A headset the rider wears: its microphone wins, and the output
        /// goes with it.
        public var isHeadset: Bool { self != .other }

        /// The word in the `talk input:` log line.
        public var word: String {
            switch self {
            case .bluetoothHFP: "bluetooth"
            case .headsetMic: "wired"
            case .usbAudio: "usb"
            case .other: "phone"
            }
        }
    }

    /// One entry of `AVAudioSession.availableInputs`.
    public struct Port: Equatable, Sendable {
        public var kind: Kind
        /// `AVAudioSessionPortDescription.uid`: how the app finds the port again.
        public var uid: String

        public init(kind: Kind, uid: String) {
            self.kind = kind
            self.uid = uid
        }
    }

    /// What the session should do.
    public struct Plan: Equatable, Sendable {
        /// The preferred input; nil clears the preference (no headset: the
        /// phone's microphone, and a gone headset's stale preference goes).
        public var input: Port?
        /// Drop an earlier `overrideOutputAudioPort(.speaker)`, so the output
        /// follows the headset. iOS clears the override on a route change
        /// already; this makes it independent of that.
        public var clearSpeakerOverride: Bool

        public init(input: Port?, clearSpeakerOverride: Bool) {
            self.input = input
            self.clearSpeakerOverride = clearSpeakerOverride
        }
    }

    /// The talk's input among `available`: the best headset kind, the first
    /// port of that kind (iOS lists them in its own order), or nil.
    public static func choose(_ available: [Port]) -> Port? {
        available.filter { $0.kind.isHeadset }.min { $0.kind < $1.kind }
    }

    /// The whole decision. `outputOnSpeaker`: the current output is the
    /// built-in speaker (the talk's no-headset route, or an override from a
    /// headset that left).
    public static func plan(available: [Port], outputOnSpeaker: Bool) -> Plan {
        let input = choose(available)
        return Plan(input: input, clearSpeakerOverride: input != nil && outputOnSpeaker)
    }

    /// `AVAudioSession.RouteChangeReason` raw values that mean a device came
    /// or went: then a talk picks its input again (a wired headset plugged in
    /// mid-talk takes over from the phone's mic; unplugged, the talk falls
    /// back to the earbuds, else the phone and the speaker). The other reasons
    /// (category change, override, our own preferred-input change) are not
    /// re-planned, so applying a plan never loops.
    public static func replans(reason: UInt) -> Bool {
        reason == newDeviceAvailable || reason == oldDeviceUnavailable
    }

    /// `AVAudioSession.RouteChangeReason.newDeviceAvailable.rawValue`.
    public static let newDeviceAvailable: UInt = 1
    /// `AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue`.
    public static let oldDeviceUnavailable: UInt = 2
}
