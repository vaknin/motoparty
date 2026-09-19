#if os(iOS)
import Foundation
import MotopartyCore
import Network

/// UDP voice (PROTOCOL.md, Voice). Sends from an ephemeral port to the host's
/// voice port; sends a keepalive once per second while no audio is going out,
/// so the host always knows our address and the radios never idle. `ts` is a
/// running 16 kHz clock from a random start that keeps running between talks
/// (VoiceSequencer).
final class VoiceSocket {
    /// Called on the socket's queue for every valid packet from the host.
    var onPacket: ((VoicePacket) -> Void)?

    private let host: String
    private let port: Int
    private let queue = DispatchQueue(label: "motoparty.voice", qos: .userInteractive)
    private var connection: NWConnection?
    private var sequencer = VoiceSequencer(seq: UInt16.random(in: 0...UInt16.max),
                                           startTs: UInt32.random(in: 0...UInt32.max),
                                           startMs: MonotonicClock.nowMs())
    private var lastAudioSentMs: Double = 0
    private var timer: DispatchSourceTimer?
    private var stopped = false

    init(host: String, port: Int) {
        self.host = host
        self.port = port
    }

    func start() {
        queue.async { [self] in
            connect()
            let t = DispatchSource.makeTimerSource(queue: queue)
            t.schedule(deadline: .now(), repeating: .milliseconds(LinkDefaults.keepaliveIntervalMs))
            t.setEventHandler { [weak self] in self?.keepaliveTick() }
            t.resume()
            timer = t
        }
    }

    func stop() {
        queue.async { [self] in
            stopped = true
            timer?.cancel()
            timer = nil
            connection?.cancel()
            connection = nil
        }
    }

    /// Talk (mic capture) starts: the next frame's ts aligns to the clock.
    func beginCapture() {
        queue.async { [self] in sequencer.beginCapture() }
    }

    /// One encoded 20 ms Opus frame (voice activity).
    func sendAudio(_ opus: Data) {
        let now = MonotonicClock.nowMs()
        queue.async { [self] in
            let packet = sequencer.audio(opus, nowMs: now)
            lastAudioSentMs = now
            connection?.send(content: packet.encoded(), completion: .idempotent)
        }
    }

    /// A frame the encoder flagged as DTX (OPUS_GET_IN_DTX): not sent, ts advances.
    func skipFrame() {
        let now = MonotonicClock.nowMs()
        queue.async { [self] in sequencer.skipFrame(nowMs: now) }
    }

    // MARK: - Private (on queue)

    private func connect() {
        guard !stopped else { return }
        let params = NetworkInterfaces.lanParameters(NWParameters.udp)
        params.serviceClass = .interactiveVoice
        let conn = NWConnection(host: NWEndpoint.Host(host),
                                port: NWEndpoint.Port(rawValue: UInt16(port))!,
                                using: params)
        conn.stateUpdateHandler = { [weak self, weak conn] state in
            guard let self else { return }
            switch state {
            case .ready:
                if let conn { self.receive(on: conn) }
            case .failed(let error):
                Log.voice.error("voice socket failed: \(error.localizedDescription, privacy: .public); reconnecting")
                self.connection?.cancel()
                self.connection = nil
                self.queue.asyncAfter(deadline: .now() + 1) { [weak self] in self?.connect() }
            default:
                break
            }
        }
        connection = conn
        conn.start(queue: queue)
    }

    private func receive(on conn: NWConnection) {
        conn.receiveMessage { [weak self] data, _, _, error in
            guard let self, !self.stopped else { return }
            if let data, let packet = VoicePacket.decode(data) {
                self.onPacket?(packet)
            }
            if error == nil { self.receive(on: conn) }
        }
    }

    private func keepaliveTick() {
        let now = MonotonicClock.nowMs()
        guard now - lastAudioSentMs >= Double(LinkDefaults.keepaliveIntervalMs) else { return }
        let packet = sequencer.keepalive(nowMs: now)
        connection?.send(content: packet.encoded(), completion: .idempotent)
    }
}
#endif
