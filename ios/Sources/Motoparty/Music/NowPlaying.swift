#if os(iOS)
import Foundation
import MediaPlayer

/// Lock screen / Control Center info and headset buttons. Because the app owns
/// the audio session and the player, headset presses arrive here as remote
/// commands: play/pause, next, previous (AirPods: single, double, triple
/// press; other buds: whatever their own app maps to those). AppModel maps them to actions (Settings).
final class NowPlaying {
    enum Button { case playPause, pause, next, previous }

    /// Main queue.
    var onButton: ((Button) -> Void)?

    init() {
        let center = MPRemoteCommandCenter.shared()
        _ = center.togglePlayPauseCommand.addTarget { [weak self] _ in self?.fire(.playPause) ?? .commandFailed }
        _ = center.playCommand.addTarget { [weak self] _ in self?.fire(.playPause) ?? .commandFailed }
        // Also sent when an AirPod is taken out of the ear; AppModel decides.
        _ = center.pauseCommand.addTarget { [weak self] _ in self?.fire(.pause) ?? .commandFailed }
        _ = center.nextTrackCommand.addTarget { [weak self] _ in self?.fire(.next) ?? .commandFailed }
        _ = center.previousTrackCommand.addTarget { [weak self] _ in self?.fire(.previous) ?? .commandFailed }
        for command in [center.togglePlayPauseCommand, center.playCommand, center.pauseCommand,
                        center.nextTrackCommand, center.previousTrackCommand] {
            command.isEnabled = true
        }
        // Position is owned by the host.
        center.changePlaybackPositionCommand.isEnabled = false
        center.skipForwardCommand.isEnabled = false
        center.skipBackwardCommand.isEnabled = false
        center.seekForwardCommand.isEnabled = false
        center.seekBackwardCommand.isEnabled = false
    }

    private func fire(_ button: Button) -> MPRemoteCommandHandlerStatus {
        DispatchQueue.main.async { self.onButton?(button) }
        return .success
    }

    func update(title: String?, artist: String?, album: String?, durationMs: Int64?,
                positionMs: Double, playing: Bool, talking: Bool) {
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: talking ? "Talking…" : (title ?? "Motoparty"),
            MPMediaItemPropertyArtist: artist ?? "",
            MPNowPlayingInfoPropertyElapsedPlaybackTime: positionMs / 1000,
            MPNowPlayingInfoPropertyPlaybackRate: playing ? 1.0 : 0.0,
        ]
        if let album { info[MPMediaItemPropertyAlbumTitle] = album }
        if let durationMs { info[MPMediaItemPropertyPlaybackDuration] = Double(durationMs) / 1000 }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
}
#endif
