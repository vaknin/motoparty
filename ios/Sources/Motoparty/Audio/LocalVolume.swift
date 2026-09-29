#if os(iOS)
import AVFoundation
import MediaPlayer
import UIKit

/// Sets **this** phone's system output volume (PROTOCOL.md "Commands": volume
/// is local and is never sent to the host): `VolumeKey` parks it at 15/16
/// while the link is up and hands it back on disarm, and the spoken volume
/// commands step it while the link is down (while it is up they step the app
/// gain instead).
///
/// iOS has no public setter for the system volume, so this drives the hidden
/// `UISlider` inside an `MPVolumeView`: writing its `value` is what actually
/// moves the system / AirPods volume, the same thing the hardware buttons do.
/// The view has to be in the window hierarchy for that to work, so it is
/// parked off-screen. Everything about the trick is kept in this one type.
///
/// Main queue only: UIKit, and the slider posts its change synchronously.
final class LocalVolume {
    /// One press of a hardware volume button.
    static let step: Float = 1.0 / 16.0

    private var volumeView: MPVolumeView?
    private weak var cachedSlider: UISlider?
    /// The latest value asked for while the slider did not exist yet; the
    /// retry sets it (a newer `set` replaces it).
    private var pending: Float?
    private var retryScheduled = false
    /// Retries before giving up on a pending value: 8 × 100 ms.
    private static let maxRetries = 8
    private static let retryDelay: TimeInterval = 0.1

    /// Both return false if the slider could not be reached, so the caller can
    /// play the error earcon instead of pretending the volume moved.
    func up() -> Bool { adjust(by: LocalVolume.step) }
    func down() -> Bool { adjust(by: -LocalVolume.step) }

    /// Puts the MPVolumeView in the window now, so its slider exists (UIKit
    /// makes it only after a layout pass) before the first park. Call once
    /// the window is up (app start); harmless to call again.
    func prepare() {
        _ = slider()
    }

    /// Sets the volume to `value` (0...1). If the slider is not there yet
    /// (fresh view, no layout pass), the value is kept and set on the next
    /// run loop turns, a few times 100 ms apart; true then too. False only if
    /// there is no window to put the view in.
    func set(_ value: Float) -> Bool {
        let value = min(1, max(0, value))
        if let slider = slider() {
            pending = nil
            slider.value = value
            return true
        }
        guard volumeView != nil else {
            Log.audio.error("local volume: no MPVolumeView slider (no window)")
            return false
        }
        pending = value
        scheduleRetry(attempt: 1)
        return true
    }

    private func scheduleRetry(attempt: Int) {
        guard !retryScheduled else { return }
        retryScheduled = true
        DispatchQueue.main.asyncAfter(deadline: .now() + (attempt == 1 ? 0 : Self.retryDelay)) { [weak self] in
            guard let self else { return }
            self.retryScheduled = false
            guard let value = self.pending else { return }
            if let slider = self.slider() {
                self.pending = nil
                slider.value = value
                Log.audio.info("local volume: set \(value, privacy: .public) on retry \(attempt)")
            } else if attempt < Self.maxRetries {
                self.scheduleRetry(attempt: attempt + 1)
            } else {
                self.pending = nil
                Log.audio.error("local volume: no MPVolumeView slider after \(attempt) tries")
            }
        }
    }

    private func adjust(by delta: Float) -> Bool {
        // The session's outputVolume is the reading that is always right; the
        // slider may not have caught up with a hardware button press yet.
        let current = AVAudioSession.sharedInstance().outputVolume
        let target = min(1, max(0, current + delta))
        guard set(target) else { return false }
        Log.audio.info("local volume \(current, privacy: .public) → \(target, privacy: .public)")
        return true
    }

    private func slider() -> UISlider? {
        if let cachedSlider, cachedSlider.window != nil { return cachedSlider }
        if volumeView == nil, let window = Self.window() {
            // Off-screen, a real size, and nearly (not fully) transparent:
            // a hidden or zero-sized MPVolumeView may never build its slider.
            let view = MPVolumeView(frame: CGRect(x: -1_000, y: -1_000, width: 120, height: 40))
            view.alpha = 0.0001
            view.isUserInteractionEnabled = false
            window.addSubview(view)
            volumeView = view
        }
        guard let view = volumeView else { return nil }
        if view.window == nil, let window = Self.window() { window.addSubview(view) }
        view.setNeedsLayout()
        view.layoutIfNeeded()
        // The slider sits deeper than the view's direct subviews on current iOS.
        guard let slider = Self.findSlider(in: view) else { return nil }
        if cachedSlider == nil { Log.audio.info("local volume: slider ready") }
        cachedSlider = slider
        return slider
    }

    private static func findSlider(in view: UIView) -> UISlider? {
        for sub in view.subviews {
            if let slider = sub as? UISlider { return slider }
            if let slider = findSlider(in: sub) { return slider }
        }
        return nil
    }

    private static func window() -> UIWindow? {
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
        return windows.first(where: \.isKeyWindow) ?? windows.first
    }
}
#endif
