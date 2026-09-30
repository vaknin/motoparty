import Foundation

/// Fixed ports and timings from PROTOCOL.md.
public enum LinkDefaults {
    public static let serviceType = "_motoparty._tcp"
    public static let controlPort = 47800
    public static let voicePort = 47801
    public static let httpPort = 47802
    public static let pingIntervalMs = 2_000
    public static let livenessTimeoutMs = 6_000
    /// The /24 sweep starts this long after discovery starts, whatever the browse shows.
    public static let bonjourGraceMs = 3_000
    /// Bonjour probe: connect timeout, service resolution included.
    public static let bonjourProbeTimeoutMs = 3_000
    /// Bonjour candidates still unanswered are re-probed on this tick (backoff permitting).
    public static let bonjourReprobeMs = 2_000
    public static let sweepParallelism = 64
    /// Sweep: TCP connect timeout per address…
    public static let sweepTimeoutMs = 400
    /// …then (sweep and Bonjour probes) how long to wait for the host's `hello`.
    public static let probeHelloTimeoutMs = 1_000
    /// A voice keepalive goes out once nothing was sent for this long…
    public static let keepaliveIntervalMs = 1_000
    /// …checked on this tick (the host's cadence), so a gap never nears 2 s.
    public static let keepaliveTickMs = 250

    /// Whether a keepalive is due on a tick: nothing (audio or keepalive)
    /// was sent for `keepaliveIntervalMs`.
    public static func keepaliveDue(nowMs: Double, lastSentMs: Double) -> Bool {
        nowMs - lastSentMs >= Double(keepaliveIntervalMs)
    }
}

/// Fallback discovery: every other address of the client's own IPv4 /24.
public enum SubnetSweep {
    /// Candidates for "a.b.c.d", excluding itself, .0 and .255. Ordered by
    /// likelihood first (.1 is the usual gateway/hotspot address), then the
    /// rest ascending.
    public static func candidates(ownIPv4: String) -> [String] {
        let parts = ownIPv4.split(separator: ".").compactMap { UInt8($0) }
        guard parts.count == 4 else { return [] }
        let prefix = "\(parts[0]).\(parts[1]).\(parts[2])."
        var hosts: [UInt8] = [1]
        hosts += (2...254).map { UInt8($0) }
        return hosts.filter { $0 != parts[3] }.map { prefix + String($0) }
    }
}

/// "6 s without any received control frame" = link loss.
public struct Liveness: Sendable {
    public var timeoutMs: Double
    public private(set) var lastReceivedMs: Double

    public init(nowMs: Double, timeoutMs: Double = Double(LinkDefaults.livenessTimeoutMs)) {
        self.timeoutMs = timeoutMs
        self.lastReceivedMs = nowMs
    }

    public mutating func received(nowMs: Double) { lastReceivedMs = nowMs }

    public func isLost(nowMs: Double) -> Bool { nowMs - lastReceivedMs >= timeoutMs }
}

/// Bonjour TXT: proto=1, voice=47801, http=47802.
public struct ServiceTXT: Equatable, Sendable {
    public var proto: Int?
    public var voicePort: Int
    public var httpPort: Int

    public init(_ txt: [String: String]) {
        proto = txt["proto"].flatMap(Int.init)
        voicePort = txt["voice"].flatMap(Int.init) ?? LinkDefaults.voicePort
        httpPort = txt["http"].flatMap(Int.init) ?? LinkDefaults.httpPort
    }
}
