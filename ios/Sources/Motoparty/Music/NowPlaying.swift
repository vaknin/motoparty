#if os(iOS)
import Foundation
import MediaPlayer
import MotopartyCore
import UIKit

/// Lock screen / Control Center info and the remote commands. Because the app
/// owns the audio session and the player, lock-screen, Control Center and
/// headset presses arrive here as play, pause, next and previous. They control
/// the music only: no button starts or ends a talk (the earbuds sit inside the
/// helmet, 2026-09-29).
final class NowPlaying {
    enum Button { case playPause, play, pause, next, previous }

    /// Main queue.
    var onButton: ((Button) -> Void)?

    /// What is on the lock screen now and when it was put there, to write it
    /// again (moved on by the time since) once its cover has arrived.
    private var shown: (info: LockScreenInfo, at: Date)?
    /// The cover on the lock screen and the URL it came from.
    private var artwork: (url: String, item: MPMediaItemArtwork)?
    /// The cover being fetched; a newer track's request makes an older answer stale.
    private var wantedArt: String?

    init() {
        let center = MPRemoteCommandCenter.shared()
        _ = center.togglePlayPauseCommand.addTarget { [weak self] _ in self?.fire(.playPause) ?? .commandFailed }
        // Play only resumes, never toggles: buds send it when put back in.
        _ = center.playCommand.addTarget { [weak self] _ in self?.fire(.play) ?? .commandFailed }
        // Pauses the ride's music (user decision U-D1, 2026-09-30), also when
        // it is a bud taken out of the ear that sends it.
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

    /// Main queue. The position is the host's (its play anchor), so the lock
    /// screen's clock runs like the rider's, also while a track is still
    /// downloading here.
    func update(_ info: LockScreenInfo) {
        shown = (info, Date())
        write(info)
        loadArtwork(info.art)
    }

    private func write(_ info: LockScreenInfo) {
        var values: [String: Any] = [
            MPMediaItemPropertyTitle: info.title,
            MPMediaItemPropertyArtist: info.artist,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: info.positionMs / 1000,
            MPNowPlayingInfoPropertyPlaybackRate: info.playing ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
        ]
        if let album = info.album { values[MPMediaItemPropertyAlbumTitle] = album }
        if let durationMs = info.durationMs { values[MPMediaItemPropertyPlaybackDuration] = Double(durationMs) / 1000 }
        if let artwork, artwork.url == info.art { values[MPMediaItemPropertyArtwork] = artwork.item }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = values
    }

    /// Fetches the cover once per track (the shared loader caches it, and the
    /// Ride card has usually asked for the same image already).
    private func loadArtwork(_ url: String?) {
        guard let url, !url.isEmpty else {
            wantedArt = nil
            artwork = nil
            return
        }
        guard artwork?.url != url, wantedArt != url else { return }
        wantedArt = url
        ArtLoader.shared.load(url, pixels: ArtURL.buckets[ArtURL.buckets.count - 1]) { [weak self] image in
            guard let self, self.wantedArt == url else { return }
            self.wantedArt = nil
            guard let image else { return }
            self.artwork = (url, Self.artworkItem(image))
            guard let shown = self.shown, shown.info.art == url else { return }
            var info = shown.info
            if info.playing { info.positionMs += Date().timeIntervalSince(shown.at) * 1000 }
            self.write(info)
        }
    }

    /// The system asks for the image on a queue of its own: the handler must
    /// not be formed in (and so inherit) any actor's isolation.
    private static func artworkItem(_ image: UIImage) -> MPMediaItemArtwork {
        MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }
}
#endif
