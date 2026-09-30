#if os(iOS)
import Foundation
import MotopartyCore
import Network

protocol ControlClientDelegate: AnyObject {
    /// `hostAddress` is the host's IPv4 (for UDP voice and HTTP tracks).
    func controlDidConnect(_ client: ControlClient, hostAddress: String?)
    func control(_ client: ControlClient, didReceive message: ControlMessage)
    func controlDidDisconnect(_ client: ControlClient, reason: String)
}

/// TCP control channel (PROTOCOL.md): u32 framing, `hello` first, `ping`
/// every 2 s, link loss after 6 s without any frame. One instance per
/// connection attempt; after `controlDidDisconnect`, make a new one.
/// Delegate callbacks arrive on the main queue.
final class ControlClient {
    weak var delegate: ControlClientDelegate?

    private let endpoint: NWEndpoint
    private let name: String
    private let clock: HostClock
    private let queue = DispatchQueue(label: "motoparty.control")
    private var connection: NWConnection?
    private var decoder = FrameDecoder()
    private var liveness = Liveness(nowMs: 0)
    private var timer: DispatchSourceTimer?
    private var pingId = 0
    private var lastPingMs: Double = 0
    private var closed = false

    init(endpoint: NWEndpoint, name: String, clock: HostClock) {
        self.endpoint = endpoint
        self.name = name
        self.clock = clock
    }

    func start() {
        queue.async { [self] in
            let tcp = NWProtocolTCP.Options()
            tcp.noDelay = true
            tcp.connectionTimeout = 3
            let params = NetworkInterfaces.lanParameters(NWParameters(tls: nil, tcp: tcp))
            // talk.open / state / pong must not queue behind a track download
            // on the hotspot (audit P3; the host marks its end CS5).
            params.serviceClass = .signaling
            let conn = NWConnection(to: endpoint, using: params)
            connection = conn
            conn.stateUpdateHandler = { [weak self] state in self?.handle(state) }
            conn.start(queue: queue)
            // Discovery only hands over a host that just sent a valid hello
            // (usually its resolved address), so this is a safety net for a
            // host that left in between: give up fast and rediscover.
            queue.asyncAfter(deadline: .now() + 4) { [weak self] in
                guard let self, !self.closed, self.timer == nil else { return }
                self.close("connect timeout")
            }
        }
    }

    /// Sends `bye` (optional) and closes without a disconnect callback.
    func stop(sendBye: Bool = true) {
        queue.async { [self] in
            guard !closed else { return }
            if sendBye, let conn = connection, let data = try? Framing.frame(.bye(Bye(reason: "user"))) {
                conn.send(content: data, completion: .contentProcessed { _ in })
            }
            close("stopped", notify: false)
        }
    }

    func send(_ message: ControlMessage) {
        queue.async { [self] in sendNow(message) }
    }

    // MARK: - Private (on queue)

    private func sendNow(_ message: ControlMessage) {
        guard !closed, let conn = connection else { return }
        do {
            let data = try Framing.frame(message)
            conn.send(content: data, completion: .contentProcessed { error in
                if let error { Log.link.error("send failed: \(error.localizedDescription, privacy: .public)") }
            })
        } catch {
            Log.link.error("encode failed for \(message.type, privacy: .public)")
        }
    }

    private func handle(_ state: NWConnection.State) {
        switch state {
        case .ready:
            Log.link.info("control connected")
            liveness = Liveness(nowMs: MonotonicClock.nowMs())
            sendNow(.hello(Hello(role: .client, name: name)))
            sendPing()
            startTimer()
            receive()
            let host = NetworkInterfaces.hostString(connection?.currentPath?.remoteEndpoint)
            DispatchQueue.main.async { self.delegate?.controlDidConnect(self, hostAddress: host) }
        case .waiting(let error):
            close("waiting: \(error.localizedDescription)")
        case .failed(let error):
            close("failed: \(error.localizedDescription)")
        case .cancelled:
            close("cancelled")
        default:
            break
        }
    }

    private func receive() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, isComplete, error in
            guard let self, !self.closed else { return }
            if let data, !data.isEmpty {
                self.liveness.received(nowMs: MonotonicClock.nowMs())
                do {
                    for frame in try self.decoder.append(data) { self.dispatch(frame) }
                } catch {
                    self.close("protocol error: oversize frame")
                    return
                }
            }
            if isComplete { self.close("closed by host"); return }
            if let error { self.close("receive: \(error.localizedDescription)"); return }
            self.receive()
        }
    }

    private func dispatch(_ frame: Data) {
        guard !closed else { return }
        let message: ControlMessage
        do {
            message = try ControlCodec.decode(frame)
        } catch let error as ControlCodecError where error.closesConnection {
            close("protocol error: invalid JSON")
            return
        } catch {
            // Missing / mistyped required field: drop, keep the connection.
            Log.link.error("dropped message: \(String(describing: error), privacy: .public)")
            return
        }
        switch message {
        case .unknown:
            return
        case .pong(let pong):
            clock.add(pong)
        case .bye:
            DispatchQueue.main.async { self.delegate?.control(self, didReceive: message) }
            close("bye from host")
            return
        default:
            break
        }
        DispatchQueue.main.async { self.delegate?.control(self, didReceive: message) }
    }

    private func startTimer() {
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + .milliseconds(500), repeating: .milliseconds(500))
        t.setEventHandler { [weak self] in self?.tick() }
        t.resume()
        timer = t
    }

    private func tick() {
        let now = MonotonicClock.nowMs()
        if liveness.isLost(nowMs: now) {
            close("link timeout (6 s)")
            return
        }
        if now - lastPingMs >= Double(LinkDefaults.pingIntervalMs) { sendPing() }
    }

    private func sendPing() {
        pingId += 1
        lastPingMs = MonotonicClock.nowMs()
        sendNow(.ping(Ping(id: pingId, t0: MonotonicClock.nowMsInt())))
    }

    private func close(_ reason: String, notify: Bool = true) {
        guard !closed else { return }
        closed = true
        Log.link.info("control closed: \(reason, privacy: .public)")
        timer?.cancel()
        timer = nil
        connection?.stateUpdateHandler = nil
        connection?.cancel()
        connection = nil
        if notify {
            DispatchQueue.main.async { self.delegate?.controlDidDisconnect(self, reason: reason) }
        }
    }
}
#endif
