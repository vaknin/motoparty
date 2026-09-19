import Foundation

public enum FramingError: Error, Equatable {
    /// Declared or actual length above `Framing.maxFrameLength`. The
    /// connection must be closed.
    case oversize(Int)
}

/// Control-channel framing: u32 big-endian length, then that many bytes of
/// UTF-8 JSON. Maximum 64 KiB.
public enum Framing {
    public static let maxFrameLength = 64 * 1024

    public static func frame(_ payload: Data) throws -> Data {
        guard payload.count <= maxFrameLength else { throw FramingError.oversize(payload.count) }
        var out = Data(capacity: 4 + payload.count)
        let n = UInt32(payload.count)
        out.append(contentsOf: [UInt8(n >> 24), UInt8((n >> 16) & 0xFF), UInt8((n >> 8) & 0xFF), UInt8(n & 0xFF)])
        out.append(payload)
        return out
    }

    public static func frame(_ message: ControlMessage) throws -> Data {
        try frame(ControlCodec.encode(message))
    }
}

/// Incremental decoder for a TCP byte stream. Feed it whatever arrived; it
/// returns every complete frame body. Throws as soon as a header declares an
/// oversize length, before any of that body is read.
public struct FrameDecoder: Sendable {
    private var buffer = Data()

    public init() {}

    /// Bytes received but not yet part of a complete frame.
    public var pendingByteCount: Int { buffer.count }

    public mutating func append(_ bytes: Data) throws -> [Data] {
        buffer.append(bytes)
        var frames: [Data] = []
        while buffer.count >= 4 {
            let start = buffer.startIndex
            let length = Int(buffer[start]) << 24 | Int(buffer[start + 1]) << 16
                | Int(buffer[start + 2]) << 8 | Int(buffer[start + 3])
            guard length <= Framing.maxFrameLength else { throw FramingError.oversize(length) }
            guard buffer.count >= 4 + length else { break }
            frames.append(Data(buffer[(start + 4)..<(start + 4 + length)]))
            buffer = Data(buffer[(start + 4 + length)...])
        }
        return frames
    }
}
