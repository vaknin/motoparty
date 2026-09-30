#if os(iOS)
import AVFoundation
import MotopartyCore

/// Short synthesized cues ("live", "end", "ok", "error") played through the
/// current audio session route, at the app volume (`volume`, 0...1:
/// AVAudioPlayer cannot boost, so the top app level plays at 1).
final class EarconPlayer {
    private var players: [String: AVAudioPlayer] = [:]
    /// `AppVolume.Gains.cueVolume`, applied to the next `play`.
    var volume: Float = 1

    func play(_ name: String) {
        let player: AVAudioPlayer
        if let cached = players[name] {
            player = cached
        } else {
            do {
                player = try AVAudioPlayer(data: EarconSynth.wav(for: name))
            } catch {
                Log.audio.error("earcon \(name, privacy: .public): \(error.localizedDescription, privacy: .public)")
                return
            }
            players[name] = player
        }
        player.currentTime = 0
        player.volume = volume
        player.prepareToPlay()
        player.play()
    }

    /// After a media-services reset the cached players are dead: make new
    /// ones on the next `play`.
    func reset() {
        players.removeAll()
    }

    func play(_ earcon: Earcon) { play(earcon.rawValue) }
}
#endif
