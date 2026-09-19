#if os(iOS)
import Foundation
import MotopartyCore
import Network

struct HostCandidate {
    let endpoint: NWEndpoint
    let name: String
    let txt: ServiceTXT?
}

/// Finds the host: Bonjour `_motoparty._tcp` first; after 3 s without a result
/// also TCP-sweep the own /24 on port 47800 (64 parallel, 400 ms connect
/// timeout, then 1 s for the host's `hello`) and take the first address that
/// sends a valid host `hello` (PROTOCOL.md, Discovery).
/// Keeps looking until found or stopped.
final class Discovery {
    /// Called on the main queue, once per start().
    var onFound: ((HostCandidate) -> Void)?

    private let queue = DispatchQueue(label: "motoparty.discovery")
    private var browser: NWBrowser?
    private var sweepWork: DispatchWorkItem?
    private var probes: [ObjectIdentifier: NWConnection] = [:]
    private var pending: [String] = []
    private var active = false

    func start() {
        queue.async { [self] in
            stopLocked()
            active = true
            startBrowser()
            let work = DispatchWorkItem { [weak self] in self?.startSweep() }
            sweepWork = work
            queue.asyncAfter(deadline: .now() + .milliseconds(LinkDefaults.bonjourGraceMs), execute: work)
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
            self?.handle(results)
        }
        b.start(queue: queue)
        browser = b
    }

    private func handle(_ results: Set<NWBrowser.Result>) {
        guard active, let result = results.first else { return }
        var txt: ServiceTXT?
        if case .bonjour(let record) = result.metadata {
            txt = ServiceTXT(record.dictionary)
        }
        var name = "Host"
        if case .service(let serviceName, _, _, _) = result.endpoint { name = serviceName }
        Log.link.info("bonjour found \(name, privacy: .public)")
        finish(HostCandidate(endpoint: result.endpoint, name: name, txt: txt))
    }

    // MARK: Sweep

    private func startSweep() {
        guard active else { return }
        guard let ownIP = NetworkInterfaces.wifiIPv4() else {
            Log.link.info("sweep: no Wi-Fi IPv4 yet, retrying")
            scheduleSweep(afterMs: 2_000)
            return
        }
        pending = SubnetSweep.candidates(ownIPv4: ownIP)
        Log.link.info("sweep: \(self.pending.count) addresses around \(ownIP, privacy: .public)")
        while probes.count < LinkDefaults.sweepParallelism, !pending.isEmpty { launchProbe() }
    }

    private func scheduleSweep(afterMs ms: Int) {
        let work = DispatchWorkItem { [weak self] in self?.startSweep() }
        sweepWork = work
        queue.asyncAfter(deadline: .now() + .milliseconds(ms), execute: work)
    }

    private func launchProbe() {
        guard active, !pending.isEmpty else { return }
        let ip = pending.removeFirst()
        let tcp = NWProtocolTCP.Options()
        tcp.connectionTimeout = 1
        let params = NetworkInterfaces.lanParameters(NWParameters(tls: nil, tcp: tcp))
        let port = NWEndpoint.Port(rawValue: UInt16(LinkDefaults.controlPort))!
        let conn = NWConnection(host: NWEndpoint.Host(ip), port: port, using: params)
        let key = ObjectIdentifier(conn)
        probes[key] = conn
        var decoder = FrameDecoder()
        var connected = false

        func done(_ found: Bool) {
            guard probes.removeValue(forKey: key) != nil else { return }
            conn.cancel()
            if found {
                finish(HostCandidate(endpoint: .hostPort(host: NWEndpoint.Host(ip), port: port), name: ip, txt: nil))
                return
            }
            if active {
                if !pending.isEmpty {
                    launchProbe()
                } else if probes.isEmpty {
                    scheduleSweep(afterMs: 2_000) // nothing answered; go again
                }
            }
        }

        func readHello() {
            conn.receive(minimumIncompleteLength: 1, maximumLength: 4096) { data, _, isComplete, error in
                if let data, !data.isEmpty {
                    guard let frames = try? decoder.append(data) else { return done(false) }
                    if let first = frames.first {
                        if case .hello(let h)? = try? ControlCodec.decode(first), h.role == .host {
                            return done(true)
                        }
                        return done(false)
                    }
                }
                if isComplete || error != nil { return done(false) }
                readHello()
            }
        }

        conn.stateUpdateHandler = { state in
            switch state {
            case .ready:
                connected = true
                readHello()
                // The host sends hello right after accepting.
                self.queue.asyncAfter(deadline: .now() + .milliseconds(LinkDefaults.sweepHelloTimeoutMs)) { done(false) }
            case .failed, .waiting:
                done(false)
            default:
                break
            }
        }
        conn.start(queue: queue)
        queue.asyncAfter(deadline: .now() + .milliseconds(LinkDefaults.sweepTimeoutMs)) {
            if !connected { done(false) }
        }
    }

    // MARK: Common

    private func finish(_ candidate: HostCandidate) {
        guard active else { return }
        stopLocked()
        DispatchQueue.main.async { self.onFound?(candidate) }
    }

    private func stopLocked() {
        active = false
        sweepWork?.cancel()
        sweepWork = nil
        browser?.cancel()
        browser = nil
        for conn in probes.values { conn.cancel() }
        probes.removeAll()
        pending.removeAll()
    }
}
#endif
