#if os(iOS)
import Foundation
import MotopartyCore
import Network

struct HostCandidate {
    enum Source: String { case bonjour, sweep }

    /// Where the probe actually reached the host (resolved address when known).
    let endpoint: NWEndpoint
    /// The name from the host's `hello`.
    let name: String
    let txt: ServiceTXT?
    let source: Source
}

/// Finds the host (PROTOCOL.md "Discovery"). Every Bonjour `_motoparty._tcp`
/// result is only a candidate: each is probed in parallel as it appears (3 s
/// connect, resolution included, then 1 s for the host's `hello`; the probe
/// sends nothing). 3 s after start the own /24 is swept too (64 parallel,
/// 400 ms connect, same 1 s `hello` rule), alongside the browse. A failed
/// candidate is skipped for 10 s; Bonjour candidates are re-probed every 2 s
/// while nothing has won. Only a validated host `hello` ends discovery; the
/// last host's name wins over another answer within 300 ms (`HostSelection`).
final class Discovery {
    /// Called on the main queue, once per start().
    var onFound: ((HostCandidate) -> Void)?
    /// A host answered with another protocol version (name, its proto). Main queue.
    var onWrongProto: ((String, Int) -> Void)?

    private enum ProbeOutcome {
        case host(Hello, NWEndpoint?)
        case timeout, refused, closed
        case notHost
        case wrongProto(Int)
        case failed(String)

        var label: String {
            switch self {
            case .host: return "ok"
            case .timeout: return "timeout"
            case .refused: return "refused"
            case .closed: return "closed"
            case .notHost: return "not a host"
            case .wrongProto(let p): return "not a host (proto \(p))"
            case .failed(let why): return "error \(why)"
            }
        }
    }

    private let queue = DispatchQueue(label: "motoparty.discovery")
    private var browser: NWBrowser?
    private var results: Set<NWBrowser.Result> = []
    private var reprobeTimer: DispatchSourceTimer?
    private var sweepWork: DispatchWorkItem?
    private var probes: [ObjectIdentifier: NWConnection] = [:]
    private var selection = HostSelection<HostCandidate>()
    private var pending: [String] = []
    private var sweepInFlight = 0
    private var sweepMisses = 0
    private var generation = 0
    private var active = false

    /// `preferredName`: the last host this client linked to.
    func start(preferredName: String?) {
        queue.async { [self] in
            stopLocked()
            active = true
            generation += 1
            selection.restart(preferredName: preferredName)
            startBrowser()
            startReprobeTimer()
            scheduleSweep(afterMs: LinkDefaults.bonjourGraceMs)
        }
    }

    func stop() {
        queue.async { [self] in stopLocked() }
    }

    // MARK: Bonjour

    private func startBrowser() {
        let params = NWParameters()
        params.includePeerToPeer = false
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: LinkDefaults.serviceType, domain: nil), using: params)
        b.stateUpdateHandler = { state in
            Log.link.info("browser state: \(String(describing: state), privacy: .public)")
        }
        b.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self, self.active else { return }
            self.results = results
            self.probeBonjour()
        }
        b.start(queue: queue)
        browser = b
    }

    private func startReprobeTimer() {
        let t = DispatchSource.makeTimerSource(queue: queue)
        let ms = LinkDefaults.bonjourReprobeMs
        t.schedule(deadline: .now() + .milliseconds(ms), repeating: .milliseconds(ms))
        t.setEventHandler { [weak self] in self?.probeBonjour() }
        t.resume()
        reprobeTimer = t
    }

    /// Probes every current result that isn't in flight or backing off.
    private func probeBonjour() {
        guard active else { return }
        let now = MonotonicClock.nowMs()
        for result in results {
            guard case .service(let name, _, _, _) = result.endpoint else { continue }
            let key = "bonjour:" + name
            guard selection.beginProbe(key, nowMs: now) else { continue }
            var txt: ServiceTXT?
            if case .bonjour(let record) = result.metadata { txt = ServiceTXT(record.dictionary) }
            probe(result.endpoint, connectMs: LinkDefaults.bonjourProbeTimeoutMs) { [weak self] outcome in
                guard let self else { return }
                Log.link.info("probe \(name, privacy: .public): \(outcome.label, privacy: .public)")
                self.settle(key, label: name, outcome: outcome, fallback: result.endpoint, txt: txt, source: .bonjour)
            }
        }
    }

    // MARK: Sweep

    private func startSweep() {
        guard active else { return }
        guard let ownIP = NetworkInterfaces.wifiIPv4() else {
            Log.link.info("sweep: no Wi-Fi IPv4 yet, retrying")
            scheduleSweep(afterMs: 2_000)
            return
        }
        let now = MonotonicClock.nowMs()
        pending = SubnetSweep.candidates(ownIPv4: ownIP).filter { !selection.isInBackoff("ip:" + $0, nowMs: now) }
        sweepMisses = 0
        if pending.isEmpty {
            scheduleSweep(afterMs: 2_000) // everything failed recently; wait out the backoff
            return
        }
        Log.link.info("sweep: \(self.pending.count) addresses around \(ownIP, privacy: .public)")
        while sweepInFlight < LinkDefaults.sweepParallelism, !pending.isEmpty { launchSweepProbe() }
    }

    private func scheduleSweep(afterMs ms: Int) {
        sweepWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.startSweep() }
        sweepWork = work
        queue.asyncAfter(deadline: .now() + .milliseconds(ms), execute: work)
    }

    private func launchSweepProbe() {
        guard active else { return }
        let now = MonotonicClock.nowMs()
        while let ip = pending.first {
            pending.removeFirst()
            let key = "ip:" + ip
            guard selection.beginProbe(key, nowMs: now) else { continue }
            let port = NWEndpoint.Port(rawValue: UInt16(LinkDefaults.controlPort))!
            let endpoint = NWEndpoint.hostPort(host: NWEndpoint.Host(ip), port: port)
            sweepInFlight += 1
            probe(endpoint, connectMs: LinkDefaults.sweepTimeoutMs) { [weak self] outcome in
                guard let self else { return }
                self.sweepInFlight -= 1
                // Silent addresses are the norm on a /24: log only what answered.
                switch outcome {
                case .timeout, .refused, .closed, .failed: self.sweepMisses += 1
                default: Log.link.info("probe \(ip, privacy: .public): \(outcome.label, privacy: .public)")
                }
                self.settle(key, label: ip, outcome: outcome, fallback: endpoint, txt: nil, source: .sweep)
                guard self.active else { return }
                if !self.pending.isEmpty {
                    self.launchSweepProbe()
                } else if self.sweepInFlight == 0 {
                    Log.link.info("sweep: no host (\(self.sweepMisses) silent)")
                    self.scheduleSweep(afterMs: 2_000) // go again
                }
            }
            return
        }
    }

    // MARK: Probe

    /// Connects to `endpoint` (connect timeout `connectMs`, resolution
    /// included), sends nothing, and judges the first frame received within
    /// 1 s. `completion` runs on the queue, unless discovery stopped first.
    private func probe(_ endpoint: NWEndpoint, connectMs: Int, completion: @escaping (ProbeOutcome) -> Void) {
        let tcp = NWProtocolTCP.Options()
        tcp.connectionTimeout = max(1, (connectMs + 999) / 1000)
        let params = NetworkInterfaces.lanParameters(NWParameters(tls: nil, tcp: tcp))
        let conn = NWConnection(to: endpoint, using: params)
        let id = ObjectIdentifier(conn)
        probes[id] = conn
        var decoder = FrameDecoder()
        var connected = false

        func done(_ outcome: ProbeOutcome) {
            guard probes.removeValue(forKey: id) != nil else { return }
            conn.stateUpdateHandler = nil
            conn.cancel()
            completion(outcome)
        }

        func readHello() {
            conn.receive(minimumIncompleteLength: 1, maximumLength: 4096) { data, _, isComplete, error in
                if let data, !data.isEmpty {
                    guard let frames = try? decoder.append(data) else { return done(.notHost) }
                    if let first = frames.first {
                        switch HostProbe.judge(firstFrame: first) {
                        case .host(let hello): return done(.host(hello, conn.currentPath?.remoteEndpoint))
                        case .wrongProto(let p): return done(.wrongProto(p))
                        case .notHost: return done(.notHost)
                        }
                    }
                }
                if isComplete { return done(.closed) }
                if let error { return done(Self.outcome(error)) }
                readHello()
            }
        }

        conn.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                connected = true
                readHello()
                // The host sends hello right after accepting.
                self?.queue.asyncAfter(deadline: .now() + .milliseconds(LinkDefaults.probeHelloTimeoutMs)) { done(.timeout) }
            case .failed(let error), .waiting(let error):
                done(Self.outcome(error))
            default:
                break
            }
        }
        conn.start(queue: queue)
        queue.asyncAfter(deadline: .now() + .milliseconds(connectMs)) {
            if !connected { done(.timeout) }
        }
    }

    private static func outcome(_ error: NWError) -> ProbeOutcome {
        switch error {
        case .posix(.ECONNREFUSED): return .refused
        case .posix(.ETIMEDOUT): return .timeout
        default: return .failed(error.localizedDescription)
        }
    }

    // MARK: Selection

    private func settle(_ key: String, label: String, outcome: ProbeOutcome, fallback: NWEndpoint, txt: ServiceTXT?, source: HostCandidate.Source) {
        let now = MonotonicClock.nowMs()
        guard case .host(let hello, let remote) = outcome else {
            selection.probeFailed(key, nowMs: now)
            if case .wrongProto(let p) = outcome, active {
                DispatchQueue.main.async { self.onWrongProto?(label, p) }
            }
            return
        }
        let candidate = HostCandidate(endpoint: remote ?? fallback, name: hello.name, txt: txt, source: source)
        switch selection.probeSucceeded(key, name: hello.name, candidate: candidate, nowMs: now) {
        case .accept(let winner):
            finish(winner)
        case .wait(let deadlineMs):
            let gen = generation
            queue.asyncAfter(deadline: .now() + .milliseconds(Int(deadlineMs - now) + 1)) { [weak self] in
                guard let self, self.generation == gen, self.active else { return }
                if let winner = self.selection.windowExpired(nowMs: max(MonotonicClock.nowMs(), deadlineMs)) {
                    self.finish(winner)
                }
            }
        case .none:
            break
        }
    }

    private func finish(_ candidate: HostCandidate) {
        guard active else { return }
        Log.link.info("discovery: host \(candidate.name, privacy: .public) via \(candidate.source.rawValue, privacy: .public)")
        stopLocked()
        DispatchQueue.main.async { self.onFound?(candidate) }
    }

    private func stopLocked() {
        active = false
        generation += 1
        sweepWork?.cancel()
        sweepWork = nil
        reprobeTimer?.cancel()
        reprobeTimer = nil
        browser?.cancel()
        browser = nil
        results = []
        for conn in probes.values {
            conn.stateUpdateHandler = nil
            conn.cancel()
        }
        probes.removeAll()
        pending.removeAll()
        sweepInFlight = 0
    }
}
#endif
