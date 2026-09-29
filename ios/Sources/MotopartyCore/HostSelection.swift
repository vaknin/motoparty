import Foundation

/// Pure bookkeeping for discovery (PROTOCOL.md "Discovery"): which candidates
/// may be probed now, the 10 s failure backoff, and which answering host wins.
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
    public static var preferWindowMs: Double { 300 }

    /// The name of the last host this client linked to.
    public var preferredName: String?
    public private(set) var decided = false

    private var failedAtMs: [String: Double] = [:]
    private var inFlight: Set<String> = []
    private var firstAnswer: (candidate: Candidate, atMs: Double)?

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
        guard !decided, !inFlight.contains(key) else { return false }
        if let failed = failedAtMs[key] {
            if nowMs - failed < Self.backoffMs { return false }
            failedAtMs[key] = nil
        }
        inFlight.insert(key)
        return true
    }

    public func isInBackoff(_ key: String, nowMs: Double) -> Bool {
        guard let failed = failedAtMs[key] else { return false }
        return nowMs - failed < Self.backoffMs
    }

    public mutating func probeFailed(_ key: String, nowMs: Double) {
        inFlight.remove(key)
        failedAtMs[key] = nowMs
    }

    /// A probe was abandoned without a verdict (discovery stopped).
    public mutating func probeCancelled(_ key: String) {
        inFlight.remove(key)
    }

    /// `key` answered with a valid host `hello` named `name`.
    public mutating func probeSucceeded(_ key: String, name: String, candidate: Candidate, nowMs: Double) -> Decision {
        inFlight.remove(key)
        failedAtMs[key] = nil
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
