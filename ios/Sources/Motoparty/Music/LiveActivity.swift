#if os(iOS)
import ActivityKit
import MotopartyActivity
import MotopartyCore
import UIKit

/// The Live Activity on the lock screen and in the Dynamic Island: what
/// plays and the talk, display only (the views are the `MotopartyWidgets`
/// extension). `LiveActivityTracker` (MotopartyCore) decides each call, so
/// `show` can be called on every model change.
///
/// ActivityKit starts an activity only while the app is in front; updates and
/// the end also go through in the background, where the audio mode keeps the
/// app running. A start that has to wait is made when the app next becomes
/// active. With Live Activities switched off in Settings nothing happens.
/// Main queue.
final class LiveActivityController {
    private var tracker = LiveActivityTracker()
    private var activity: Activity<MotopartyActivityAttributes>?
    private var wanted: LiveActivityState?
    /// The calls to ActivityKit, one after the other in the order they were made.
    private var queue: Task<Void, Never>?
    private var observers: [NSObjectProtocol] = []

    init() {
        // An activity of an earlier run (the app was killed) would stay on the
        // lock screen with its last state for hours.
        let leftovers = Activity<MotopartyActivityAttributes>.activities
        if !leftovers.isEmpty {
            Log.app.info("live activity: ending \(leftovers.count) left over")
            enqueue { for old in leftovers { await old.end(nil, dismissalPolicy: .immediate) } }
        }
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil,
                                            queue: .main) { [weak self] _ in
            self?.tracker.foregrounded()
            self?.apply()
        })
        observers.append(center.addObserver(forName: UIApplication.willTerminateNotification, object: nil,
                                            queue: .main) { [weak self] _ in
            self?.endBeforeExit()
        })
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
    }

    /// nil: no activity.
    func show(_ state: LiveActivityState?) {
        wanted = state
        apply()
    }

    private func apply() {
        if let activity, activity.activityState == .dismissed || activity.activityState == .ended {
            // Swiped away (dismissed), or ended by the system.
            Log.app.info("live activity: gone (\(String(describing: activity.activityState), privacy: .public))")
            tracker.lost(byUser: activity.activityState == .dismissed)
            self.activity = nil
        }
        let step = tracker.step(wanted: wanted, canStart: UIApplication.shared.applicationState == .active
            && ActivityAuthorizationInfo().areActivitiesEnabled)
        switch step {
        case .none:
            break
        case .start(let state):
            do {
                activity = try Activity.request(attributes: MotopartyActivityAttributes(),
                                                content: ActivityContent(state: state, staleDate: nil), pushType: nil)
                Log.app.info("live activity: started")
            } catch {
                Log.app.error("live activity: not started: \(error.localizedDescription, privacy: .public)")
                tracker.startFailed()
            }
        case .update(let state):
            guard let activity else { return }
            enqueue { await activity.update(ActivityContent(state: state, staleDate: nil)) }
        case .end:
            guard let activity else { return }
            self.activity = nil
            enqueue { await activity.end(nil, dismissalPolicy: .immediate) }
        }
    }

    private func enqueue(_ call: @escaping () async -> Void) {
        let before = queue
        queue = Task {
            await before?.value
            await call()
        }
    }

    /// The app is being terminated: take the activity down with it, waiting
    /// briefly, because nothing runs after this returns.
    private func endBeforeExit() {
        guard let activity else { return }
        self.activity = nil
        let done = DispatchSemaphore(value: 0)
        Task.detached {
            await activity.end(nil, dismissalPolicy: .immediate)
            done.signal()
        }
        _ = done.wait(timeout: .now() + 1)
    }
}
#endif
