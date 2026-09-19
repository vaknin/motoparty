#if os(iOS)
import AVFoundation
import MotopartyCore

/// Short synthesized cues ("live", "end", "ok", "error") played through the
/// current audio session route.
final class EarconPlayer {
    private var players: [String: AVAudioPlayer] = [:]

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
        player.prepareToPlay()
        player.play()
    }

    func play(_ earcon: Earcon) { play(earcon.rawValue) }
}
#endif
