#if os(iOS)
import CoreMedia
import Foundation
import MotopartyCore

/// Thread-safe wrapper around ClockSync plus conversions to CMClock host time
/// (what AVPlayer.setRate(_:time:atHostTime:) takes).
final class HostClock {
    private let lock = NSLock()
    private var sync = ClockSync() // window 8, 500 ms step reset (PROTOCOL.md)

    func localNowMs() -> Double { MonotonicClock.nowMs() }

    func add(_ pong: Pong) {
        let t3 = MonotonicClock.nowMsInt()
        lock.withLock { _ = sync.add(pong, receivedAt: t3) }
    }

    func reset() { lock.withLock { sync.reset() } }

    var offsetMs: Double? { lock.withLock { sync.offset } }
    var rttMs: Double? { lock.withLock { sync.rtt } }

    /// Current host-clock time, or nil before the first pong.
    func hostNowMs() -> Double? { offsetMs.map { localNowMs() + $0 } }

    /// The CMClock host time at which the host clock reads `hostMs`. Computed
    /// relative to "now" on both clocks, so it does not depend on the two
    /// sharing an epoch.
    func cmHostTime(forHostMs hostMs: Double) -> CMTime? {
        guard let offset = offsetMs else { return nil }
        let localTarget = hostMs - offset
        let deltaSeconds = (localTarget - localNowMs()) / 1000
        let now = CMClockGetTime(CMClockGetHostTimeClock())
        return CMTimeAdd(now, CMTime(seconds: deltaSeconds, preferredTimescale: 1_000_000_000))
    }
}
#endif
