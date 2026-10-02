import Foundation

/// Why this phone keeps the host's music silent while the host plays on
/// (2026-10-02). The host is never told: the rider keeps listening.
/// - `route`: the headset went away (no music from the pocket loudspeaker).
/// - `call`: an audio-session interruption (a phone call) is on. Set when it
///   begins, cleared when it ends, whatever iOS says about resuming; the
///   player then rejoins the host's live position. A `state` or `music.play`
///   meanwhile must not restart the player.
public struct MusicHold: Equatable, Sendable {
    public var route: Bool
    public var call: Bool

    public init(route: Bool = false, call: Bool = false) {
        self.route = route; self.call = call
    }

    /// May the local player run the host's music now? Talk holds it as well
    /// (the host pauses for a talk and resumes with `music.play`).
    public func mayPlay(talkOpen: Bool) -> Bool {
        !talkOpen && !route && !call
    }

    /// The host plays and this phone would too: the play/pause button, the
    /// lock screen and the Live Activity show "playing" only then.
    public func playingHere(hostPlaying: Bool) -> Bool {
        hostPlaying && !route && !call
    }

    /// The Ride screen's line for the hold; the call first (it ends by itself).
    public var line: String? {
        if call { return Self.callLine }
        if route { return Self.routeLine }
        return nil
    }

    public static let callLine = "Music held during your call"
    public static let routeLine = "Headset disconnected: music is silent on this phone. Press Play to use the speaker."
}
