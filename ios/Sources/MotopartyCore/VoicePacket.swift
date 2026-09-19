import Foundation

/// UDP voice packet: 8-byte big-endian header, then payload (PROTOCOL.md, Voice).
public struct VoicePacket: Equatable, Sendable {
    public enum Kind: UInt8, Sendable {
        case audio = 1
        case keepalive = 2
    }

    public static let magic: UInt8 = 0x4D
    public static let headerLength = 8
    /// 16 kHz, 20 ms.
    public static let samplesPerFrame: UInt32 = 320
    public static let sampleRate = 16_000

    public var kind: Kind
    public var seq: UInt16
    public var ts: UInt32
    public var payload: Data

    public init(kind: Kind, seq: UInt16, ts: UInt32, payload: Data = Data()) {
        self.kind = kind
        self.seq = seq
        self.ts = ts
        self.payload = payload
    }

    public func encoded() -> Data {
        var out = Data(capacity: Self.headerLength + payload.count)
        out.append(Self.magic)
        out.append(kind.rawValue)
        out.append(UInt8(seq >> 8))
        out.append(UInt8(seq & 0xFF))
        out.append(UInt8(ts >> 24))
        out.append(UInt8((ts >> 16) & 0xFF))
        out.append(UInt8((ts >> 8) & 0xFF))
        out.append(UInt8(ts & 0xFF))
        out.append(payload)
        return out
    }

    /// nil for wrong magic, unknown kind, or fewer than 8 bytes.
    public static func decode(_ data: Data) -> VoicePacket? {
        guard data.count >= headerLength else { return nil }
        let b = [UInt8](data.prefix(headerLength))
        guard b[0] == magic, let kind = Kind(rawValue: b[1]) else { return nil }
        let seq = UInt16(b[2]) << 8 | UInt16(b[3])
        let ts = UInt32(b[4]) << 24 | UInt32(b[5]) << 16 | UInt32(b[6]) << 8 | UInt32(b[7])
        return VoicePacket(kind: kind, seq: seq, ts: ts, payload: Data(data.dropFirst(headerLength)))
    }
}

/// Sender-side counters (PROTOCOL.md, Voice → Sending).
///
/// - `seq`: +1 per packet sent; audio and keepalive share it.
/// - `ts`: the sender's running 16 kHz sample clock. It starts at a random
///   value when the voice socket opens and keeps running while talk is
///   closed; keepalives carry its current value. Audio frames count +320
///   each (sent or DTX-skipped) from the clock value at the first frame of a
///   capture, and re-align to the clock if they drift by more than
///   `realignSamples` (capture restarts, stalls).
public struct VoiceSequencer: Sendable {
    public private(set) var seq: UInt16
    public let startTs: UInt32
    public let startMs: Double
    public var realignSamples: Int64 = 3_200 // 200 ms
    private var nextAudioTs: UInt32?

    public init(seq: UInt16, startTs: UInt32, startMs: Double) {
        self.seq = seq
        self.startTs = startTs
        self.startMs = startMs
    }

    /// The running clock at local monotonic time `nowMs`.
    public func clockTs(nowMs: Double) -> UInt32 {
        let samples = Int64(((nowMs - startMs) * 16).rounded(.down))
        return startTs &+ UInt32(truncatingIfNeeded: samples)
    }

    /// Call when capture (re)starts: the next frame aligns to the clock.
    public mutating func beginCapture() { nextAudioTs = nil }

    /// Packet for the next 20 ms frame.
    public mutating func audio(_ payload: Data, nowMs: Double) -> VoicePacket {
        let ts = frameTs(nowMs: nowMs)
        defer { seq &+= 1 }
        return VoicePacket(kind: .audio, seq: seq, ts: ts, payload: payload)
    }

    /// A frame DTX decided not to send: ts advances, seq does not.
    public mutating func skipFrame(nowMs: Double) { _ = frameTs(nowMs: nowMs) }

    public mutating func keepalive(nowMs: Double) -> VoicePacket {
        defer { seq &+= 1 }
        return VoicePacket(kind: .keepalive, seq: seq, ts: clockTs(nowMs: nowMs))
    }

    private mutating func frameTs(nowMs: Double) -> UInt32 {
        let clock = clockTs(nowMs: nowMs)
        var ts = nextAudioTs ?? clock
        if abs(Int64(Int32(bitPattern: ts &- clock))) > realignSamples { ts = clock }
        nextAudioTs = ts &+ VoicePacket.samplesPerFrame
        return ts
    }
}
