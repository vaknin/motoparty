#if os(iOS)
import AVFoundation
import MediaPlayer
import UIKit

/// Changes **this** phone's output volume (PROTOCOL.md "Commands": volume is
/// local and is never sent to the host).
///
/// iOS has no public setter for the system volume, so this drives the hidden
/// `UISlider` inside an `MPVolumeView`: writing its `value` is what actually
/// moves the system / AirPods volume, the same thing the hardware buttons do.
/// The view has to be in the window hierarchy for that to work, so it is
/// parked off-screen. Everything about the trick is kept in this one type so
/// it can be swapped for `AVPlayer.volume` (which would only duck our own
/// music) after testing on a real phone.
///
/// Main queue only: UIKit, and the slider posts its change synchronously.
final class LocalVolume {
    /// One press of a hardware volume button.
    static let step: Float = 1.0 / 16.0

    private var volumeView: MPVolumeView?

    /// Both return false if the slider could not be reached, so the caller can
    /// play the error earcon instead of pretending the volume moved.
    func up() -> Bool { adjust(by: LocalVolume.step) }
    func down() -> Bool { adjust(by: -LocalVolume.step) }

    private func adjust(by delta: Float) -> Bool {
        guard let slider = slider() else {
            Log.audio.error("local volume: no MPVolumeView slider")
            return false
        }
        // The session's outputVolume is the reading that is always right; the
        // slider may not have caught up with a hardware button press yet.
        let current = AVAudioSession.sharedInstance().outputVolume
        let target = min(1, max(0, current + delta))
        slider.value = target
        Log.audio.info("local volume \(current, privacy: .public) → \(target, privacy: .public)")
        return true
    }

    private func slider() -> UISlider? {
        if volumeView == nil, let window = Self.window() {
            let view = MPVolumeView(frame: CGRect(x: -1_000, y: -1_000, width: 120, height: 40))
            window.addSubview(view)
            view.layoutIfNeeded()
            volumeView = view
        }
        return volumeView?.subviews.compactMap { $0 as? UISlider }.first
    }

    private static func window() -> UIWindow? {
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
        return windows.first(where: \.isKeyWindow) ?? windows.first
    }
}
#endif
