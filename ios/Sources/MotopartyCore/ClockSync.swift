import Foundation

/// NTP-style estimate of `offset = hostClock - clientClock` (PROTOCOL.md, Clock).
///
/// Keeps the last `window` valid samples; the estimate is the offset of the
/// sample with the smallest RTT (ties: most recent). Samples with RTT < 0 are
/// discarded and do not take a window slot. A sample whose offset differs
/// from the current estimate by more than `stepResetMs` (500) plus half its
/// own RTT clears the window first: the iOS monotonic clock stops while the
/// device sleeps, but a slow pong alone can be off by half its round trip and
/// must not throw a good window away.
public struct ClockSync: Sendable {
    public struct Sample: Equatable, Sendable {
        public var rtt: Double
        public var offset: Double
    }

    public let window: Int
    /// nil disables the step reset (tests only).
    public var stepResetMs: Double?
    public private(set) var samples: [Sample] = []

    public init(window: Int = 8, stepResetMs: Double? = 500) {
        self.window = window
        self.stepResetMs = stepResetMs
    }

    /// Adds a ping/pong round trip. `t3` = client clock at pong receipt.
    /// Returns false if the sample was discarded.
    @discardableResult
    public mutating func add(t0: Int64, t1: Int64, t2: Int64, t3: Int64) -> Bool {
        let rtt = Double(t3 - t0) - Double(t2 - t1)
        guard rtt >= 0 else { return false }
        let offset = (Double(t1 - t0) + Double(t2 - t3)) / 2
        if let limit = stepResetMs, let current = self.offset, abs(offset - current) > limit + rtt / 2 {
            samples.removeAll()
        }
        samples.append(Sample(rtt: rtt, offset: offset))
        if samples.count > window { samples.removeFirst(samples.count - window) }
        return true
    }

    public mutating func add(_ pong: Pong, receivedAt t3: Int64) -> Bool {
        add(t0: pong.t0, t1: pong.t1, t2: pong.t2, t3: t3)
    }

    public mutating func reset() { samples.removeAll() }

    private var best: Sample? {
        var best: Sample?
        for s in samples where best == nil || s.rtt <= best!.rtt { best = s }
        return best
    }

    /// hostClock - clientClock in ms, or nil before the first valid sample.
    public var offset: Double? { best?.offset }

    /// RTT of the sample the estimate is based on.
    public var rtt: Double? { best?.rtt }

    public var isSynced: Bool { !samples.isEmpty }

    public func hostToLocal(_ host: Double) -> Double? { offset.map { host - $0 } }
    public func localToHost(_ local: Double) -> Double? { offset.map { local + $0 } }
}

/// The client's monotonic millisecond clock. On Darwin this is
/// mach_absolute_time (the same base as CMClockGetHostTimeClock); on Linux
/// CLOCK_MONOTONIC.
public enum MonotonicClock {
    public static func nowMs() -> Double {
        Double(DispatchTime.now().uptimeNanoseconds) / 1_000_000
    }

    public static func nowMsInt() -> Int64 { Int64(nowMs().rounded()) }
}
