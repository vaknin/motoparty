import Foundation

/// Pure bookkeeping for discovery (PROTOCOL.md "Discovery"): which candidates
/// may be probed now, the failure backoff (10 s; 1 s after "connection
/// refused", a host that is restarting), the probe of the last linked address
/// (exempt from the backoff) and which answering host wins.
/// A Bonjour result or sweep address is only a candidate; a host is a
/// candidate whose first frame was a valid host `hello`.
///
/// Keys identify candidates: the service name for Bonjour, the IP for the
/// sweep. `Candidate` is whatever the caller needs to connect to a winner.
/// Not thread-safe: the owner confines it to one queue.
public struct HostSelection<Candidate> {
    public enum Decision {
        /// This host wins now.
        case accept(Candidate)
        /// A host answered but isn't the preferred one: call `windowExpired`
        /// at `deadlineMs` (unless a preferred host answers first).
        case wait(deadlineMs: Double)
        /// Nothing to do (already decided, or an earlier answer is waiting).
        case none
    }

    public static var backoffMs: Double { 10_000 }
    /// "Connection refused": something is at that address but not listening
    /// yet, which is what a restarting host looks like.
    public static var refusedBackoffMs: Double { 1_000 }
    /// The address of the last link is probed this often after a link loss.
    public static var lastAddressProbeMs: Double { 1_000 }
    public static var preferWindowMs: Double { 300 }

    /// The name of the last host this client linked to.
    public var preferredName: String?
    public private(set) var decided = false

    /// When a failed candidate may be probed again.
    private var retryAtMs: [String: Double] = [:]
    private var inFlight: Set<String> = []
    private var firstAnswer: (candidate: Candidate, atMs: Double)?
    /// Hosts that speak another protocol version (PROTOCOL.md "Control
    /// channel"): not probed and not accepted until the user asks again.
    private var blockedKeys: Set<String> = []
    private var blockedNames: Set<String> = []

    public init(preferredName: String? = nil) {
        self.preferredName = preferredName
    }

    /// Starts a new discovery run. Failure backoff is kept: a stale service
    /// that just failed stays skipped across a quick restart.
    public mutating func restart(preferredName: String?) {
        self.preferredName = preferredName
        decided = false
        inFlight.removeAll()
        firstAnswer = nil
    }

    /// True (and marks `key` in flight) when `key` may be probed now: no
    /// decision yet, no probe running for it, not failed within the backoff.
    public mutating func beginProbe(_ key: String, nowMs: Double) -> Bool {
        guard !decided, !inFlight.contains(key), !blockedKeys.contains(key) else { return false }
        if let retryAt = retryAtMs[key] {
            if nowMs < retryAt { return false }
            retryAtMs[key] = nil
        }
        inFlight.insert(key)
        return true
    }

    /// The probe of the address this client was last linked to (PROTOCOL.md
    /// "Discovery" step 5): like `beginProbe`, but exempt from the backoff.
    /// The caller asks every `lastAddressProbeMs`; one probe runs at a time.
    public mutating func beginLastAddressProbe(_ key: String) -> Bool {
        guard !decided, !inFlight.contains(key), !blockedKeys.contains(key) else { return false }
        retryAtMs[key] = nil
        inFlight.insert(key)
        return true
    }

    public func isInBackoff(_ key: String, nowMs: Double) -> Bool {
        if blockedKeys.contains(key) { return true }
        guard let retryAt = retryAtMs[key] else { return false }
        return nowMs < retryAt
    }

    /// `refused`: the connection was refused (nothing listening there yet).
    public mutating func probeFailed(_ key: String, nowMs: Double, refused: Bool = false) {
        inFlight.remove(key)
        retryAtMs[key] = nowMs + (refused ? Self.refusedBackoffMs : Self.backoffMs)
    }

    /// A probe was abandoned without a verdict (discovery stopped).
    public mutating func probeCancelled(_ key: String) {
        inFlight.remove(key)
    }

    /// `key` answered with a valid host `hello` named `name`.
    public mutating func probeSucceeded(_ key: String, name: String, candidate: Candidate, nowMs: Double) -> Decision {
        inFlight.remove(key)
        retryAtMs[key] = nil
        // A blocked host reached another way (Bonjour name, sweep address).
        if blockedNames.contains(name) {
            blockedKeys.insert(key)
            return .none
        }
        guard !decided else { return .none }
        if let preferred = preferredName, name == preferred {
            decided = true
            return .accept(candidate)
        }
        if firstAnswer != nil { return .none }
        guard preferredName != nil else {
            decided = true
            return .accept(candidate)
        }
        firstAnswer = (candidate, nowMs)
        return .wait(deadlineMs: nowMs + Self.preferWindowMs)
    }

    /// The probe of `key` read a host `hello` with another `proto`: that
    /// candidate is left alone until `unblock`. Other candidates go on.
    public mutating func probeWrongProto(_ key: String) {
        inFlight.remove(key)
        blockedKeys.insert(key)
    }

    /// The host named `name` refused this client's protocol version after
    /// the probe (`bye{reason:"proto"}`, or another `proto` in its `hello`):
    /// it is not accepted again, under any key, until `unblock`. `keys`: the
    /// candidate keys known to lead to it.
    public mutating func block(name: String, keys: [String] = []) {
        blockedNames.insert(name)
        blockedKeys.formUnion(keys)
    }

    /// The user asked to connect again.
    public mutating func unblock() {
        blockedKeys.removeAll()
        blockedNames.removeAll()
    }

    public var hasBlockedHosts: Bool { !blockedKeys.isEmpty || !blockedNames.isEmpty }

    /// The preference window is over: the first answer wins, if still undecided.
    public mutating func windowExpired(nowMs: Double) -> Candidate? {
        guard !decided, let first = firstAnswer, nowMs >= first.atMs + Self.preferWindowMs else { return nil }
        decided = true
        return first.candidate
    }
}

/// Probe verdict on a candidate's first frame (PROTOCOL.md "Discovery").
public enum HostProbe {
    public enum Verdict: Equatable {
        case host(Hello)
        /// A host hello with another protocol version.
        case wrongProto(Int)
        case notHost
    }

    public static func judge(firstFrame: Data) -> Verdict {
        guard case .hello(let h)? = try? ControlCodec.decode(firstFrame), h.role == .host else { return .notHost }
        return h.proto == Hello.currentProto ? .host(h) : .wrongProto(h.proto)
    }
}

/// PROTOCOL.md "Control channel": a host that speaks another protocol
/// version says so with another `proto` in its `hello`, or answers this
/// client's `hello` with `bye{reason:"proto"}`. The client shows it and does
/// not connect to that host again until the user asks.
public enum ProtoMismatch {
    public static let byeReason = "proto"

    /// True for a message on the control connection that means the host and
    /// this client speak different protocol versions.
    public static func isMismatch(_ message: ControlMessage) -> Bool {
        switch message {
        case .hello(let hello): return hello.role == .host && hello.proto != Hello.currentProto
        case .bye(let bye): return bye.reason == byeReason
        default: return false
        }
    }
}
