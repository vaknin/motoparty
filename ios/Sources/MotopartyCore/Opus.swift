import COpus
import Foundation

public struct OpusError: Error, Equatable, CustomStringConvertible {
    public let code: Int32
    public init(_ code: Int32) { self.code = code }
    public var description: String {
        guard let s = opus_strerror(code) else { return "opus error \(code)" }
        return "opus: \(String(cString: s)) (\(code))"
    }
}

/// Voice settings from PROTOCOL.md: 16 kHz mono, 20 ms, VOIP, 24 kbps,
/// in-band FEC with 10 % expected loss, DTX.
public enum VoiceFormat {
    public static let sampleRate: Int32 = 16_000
    public static let channels: Int32 = 1
    public static let frameSamples = 320
    public static let bitrate: Int32 = 24_000
    public static let expectedLossPercent: Int32 = 10
    /// Largest packet we accept to produce (24 kbps × 20 ms is ~60 bytes).
    public static let maxPacketBytes = 1_275
}

/// Not thread-safe: use from a single queue.
public final class OpusVoiceEncoder: @unchecked Sendable {
    private let enc: OpaquePointer

    public struct Frame: Equatable, Sendable {
        public var data: Data
        /// OPUS_GET_IN_DTX was 1 after encoding: this is a DTX / comfort-noise
        /// update and must NOT be sent (PROTOCOL.md, Voice → Sending), so every
        /// kind-1 packet on the wire is voice activity.
        public var isDTX: Bool
    }

    public init(complexity: Int32 = 5) throws {
        var err: Int32 = 0
        guard let e = opus_encoder_create(VoiceFormat.sampleRate, VoiceFormat.channels,
                                          OPUS_APPLICATION_VOIP, &err), err == OPUS_OK else {
            throw OpusError(err)
        }
        enc = e
        try check(mp_opus_encoder_set_bitrate(enc, VoiceFormat.bitrate))
        try check(mp_opus_encoder_set_inband_fec(enc, 1))
        try check(mp_opus_encoder_set_packet_loss_perc(enc, VoiceFormat.expectedLossPercent))
        try check(mp_opus_encoder_set_dtx(enc, 1))
        try check(mp_opus_encoder_set_signal_voice(enc))
        try check(mp_opus_encoder_set_complexity(enc, complexity))
    }

    deinit { opus_encoder_destroy(enc) }

    public var bitrate: Int32 { get32(mp_opus_encoder_get_bitrate) }
    public var fecEnabled: Bool { get32(mp_opus_encoder_get_inband_fec) == 1 }
    public var dtxEnabled: Bool { get32(mp_opus_encoder_get_dtx) == 1 }

    /// Encodes exactly 320 float samples in [-1, 1].
    public func encode(_ pcm: [Float]) throws -> Frame {
        precondition(pcm.count == VoiceFormat.frameSamples, "Opus frame must be 320 samples")
        var out = [UInt8](repeating: 0, count: VoiceFormat.maxPacketBytes)
        let n = pcm.withUnsafeBufferPointer { src in
            out.withUnsafeMutableBufferPointer { dst in
                opus_encode_float(enc, src.baseAddress!, Int32(VoiceFormat.frameSamples),
                                  dst.baseAddress!, Int32(dst.count))
            }
        }
        guard n >= 0 else { throw OpusError(n) }
        var inDTX: opus_int32 = 0
        let rc = mp_opus_encoder_get_in_dtx(enc, &inDTX)
        // Fallback if the ctl ever fails: libopus says a 1-2 byte packet
        // "does not need to be transmitted (DTX)".
        let dtx = rc == OPUS_OK ? inDTX == 1 : n <= 2
        return Frame(data: Data(out[0..<Int(n)]), isDTX: dtx)
    }

    private func check(_ code: Int32) throws { if code != OPUS_OK { throw OpusError(code) } }

    private func get32(_ f: (OpaquePointer?, UnsafeMutablePointer<opus_int32>?) -> Int32) -> Int32 {
        var v: opus_int32 = 0
        _ = f(enc, &v)
        return v
    }
}

/// Not thread-safe: use from a single queue.
public final class OpusVoiceDecoder: @unchecked Sendable {
    private let dec: OpaquePointer

    public init() throws {
        var err: Int32 = 0
        guard let d = opus_decoder_create(VoiceFormat.sampleRate, VoiceFormat.channels, &err),
              err == OPUS_OK else {
            throw OpusError(err)
        }
        dec = d
    }

    deinit { opus_decoder_destroy(dec) }

    /// Decodes a packet into 320 samples.
    public func decode(_ packet: Data) throws -> [Float] { try run(packet, fec: false) }

    /// Recovers the frame *before* `successor` from its in-band FEC.
    public func decodeFEC(from successor: Data) throws -> [Float] { try run(successor, fec: true) }

    /// Packet-loss concealment for one missing frame.
    public func conceal() throws -> [Float] { try run(nil, fec: false) }

    /// Maps a jitter-buffer action to 320 samples of output.
    public func render(_ action: PlayoutAction) -> [Float] {
        do {
            switch action {
            case .silence: return [Float](repeating: 0, count: VoiceFormat.frameSamples)
            case .decode(let p): return try decode(p)
            case .decodeFEC(let p): return try decodeFEC(from: p)
            case .conceal: return try conceal()
            }
        } catch {
            return [Float](repeating: 0, count: VoiceFormat.frameSamples)
        }
    }

    public func reset() { _ = mp_opus_decoder_reset(dec) }

    private func run(_ packet: Data?, fec: Bool) throws -> [Float] {
        var out = [Float](repeating: 0, count: VoiceFormat.frameSamples)
        let n: Int32 = out.withUnsafeMutableBufferPointer { dst in
            if let packet, !packet.isEmpty {
                let bytes = [UInt8](packet)
                return bytes.withUnsafeBufferPointer { src in
                    opus_decode_float(dec, src.baseAddress, Int32(src.count), dst.baseAddress!,
                                      Int32(VoiceFormat.frameSamples), fec ? 1 : 0)
                }
            }
            return opus_decode_float(dec, nil, 0, dst.baseAddress!, Int32(VoiceFormat.frameSamples), 0)
        }
        guard n >= 0 else { throw OpusError(n) }
        return out
    }
}
