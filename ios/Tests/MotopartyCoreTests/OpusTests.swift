import Foundation
import XCTest
@testable import MotopartyCore

final class OpusTests: XCTestCase {
    /// Speech-like test signal: gliding pitch (120-220 Hz) with harmonics and a
    /// 4 Hz syllable envelope, so SILK's VAD sees speech activity (it only
    /// codes LBRR/FEC for active frames; a steady tone is treated as noise).
    private func frame(_ index: Int, amplitude: Float = 0.5) -> [Float] {
        (0..<320).map { n in
            let t = Float(index * 320 + n) / 16_000
            // Integral of f0(t) = 170 + 50 sin(2π·0.7t).
            let phase = 2 * .pi * (170 * t - 50 / (2 * .pi * 0.7) * cos(2 * .pi * 0.7 * t))
            let env = 0.05 + 0.95 * pow(max(0, sin(2 * .pi * 4 * t)), 2)
            var v: Float = 0
            for h in 1...6 { v += sin(Float(h) * phase) / Float(h) }
            return amplitude * env * v / 2.45
        }
    }

    private func energy(_ x: [Float]) -> Float { x.reduce(0) { $0 + $1 * $1 } / Float(max(1, x.count)) }

    private func error(_ a: [Float], _ b: [Float]) -> Float { energy(zip(a, b).map { $0 - $1 }) }

    func testEncoderSettings() throws {
        let enc = try OpusVoiceEncoder()
        XCTAssertEqual(enc.bitrate, 24_000)
        XCTAssertTrue(enc.fecEnabled)
        XCTAssertTrue(enc.dtxEnabled)
    }

    func testRoundTripPreservesEnergy() throws {
        let enc = try OpusVoiceEncoder()
        let dec = try OpusVoiceDecoder()
        var input: [Float] = []
        var output: [Float] = []
        var bytes = 0
        for i in 0..<100 {
            let pcm = frame(i)
            let packet = try enc.encode(pcm)
            if i >= 5 { XCTAssertFalse(packet.isDTX, "speech-like input must not be DTX (frame \(i))") }
            bytes += packet.data.count
            let out = try dec.decode(packet.data)
            XCTAssertEqual(out.count, 320)
            if i >= 10 { input += pcm; output += out } // skip codec warm-up
        }
        let ratio = energy(output) / energy(input)
        XCTAssertGreaterThan(ratio, 0.5, "decoded energy ratio \(ratio)")
        XCTAssertLessThan(ratio, 2.0, "decoded energy ratio \(ratio)")
        // ~24 kbps → ~60 bytes per 20 ms frame.
        let avg = Double(bytes) / 100
        XCTAssertGreaterThan(avg, 30)
        XCTAssertLessThan(avg, 90)
    }

    func testFECRecoversDroppedFrameBetterThanPLC() throws {
        let enc = try OpusVoiceEncoder()
        let packets = try (0..<40).map { try enc.encode(frame($0)).data }
        let dropped = 28 // near a syllable peak

        let reference = try OpusVoiceDecoder()
        var refFrames: [[Float]] = []
        for p in packets { refFrames.append(try reference.decode(p)) }

        let withFEC = try OpusVoiceDecoder()
        let withPLC = try OpusVoiceDecoder()
        var fecFrame: [Float] = []
        var plcFrame: [Float] = []
        for (i, p) in packets.enumerated() {
            if i == dropped {
                fecFrame = try withFEC.decodeFEC(from: packets[i + 1])
                plcFrame = try withPLC.conceal()
            } else {
                _ = try withFEC.decode(p)
                _ = try withPLC.decode(p)
            }
        }
        let ref = refFrames[dropped]
        XCTAssertGreaterThan(energy(fecFrame), 0.2 * energy(ref), "FEC produced (near) silence")
        let fecErr = error(fecFrame, ref)
        let plcErr = error(plcFrame, ref)
        XCTAssertLessThan(fecErr, plcErr, "FEC error \(fecErr) should beat PLC error \(plcErr)")
        XCTAssertLessThan(fecErr, 0.5 * energy(ref))
    }

    func testJitterBufferFECActionThroughDecoder() throws {
        let enc = try OpusVoiceEncoder()
        let packets = try (0..<12).map { try enc.encode(frame($0)).data }
        var jb = JitterBuffer()
        let dec = try OpusVoiceDecoder()
        var actions: [PlayoutAction] = []
        jb.insert(seq: 0, ts: 0, payload: packets[0], nowMs: 0)
        for i in 1..<12 {
            // Frame i arrives at i*20; frame 6 is lost. Pull 10 ms later.
            if i != 6 { jb.insert(seq: UInt16(i), ts: UInt32(i * 320), payload: packets[i], nowMs: Double(i * 20)) }
            let a = jb.pull(nowMs: Double(i * 20 + 10))
            actions.append(a)
            XCTAssertEqual(dec.render(a).count, 320)
        }
        XCTAssertTrue(actions.contains(.decodeFEC(packets[7])), "\(actions)")
        XCTAssertEqual(jb.stats.fec, 1)
    }

    func testSilenceGoesDTX() throws {
        let enc = try OpusVoiceEncoder()
        let silence = [Float](repeating: 0, count: 320)
        let frames = try (0..<100).map { _ in try enc.encode(silence) }
        let dtx = frames.filter(\.isDTX).count
        XCTAssertGreaterThan(dtx, 50, "only \(dtx)/100 silent frames were DTX")
    }

    func testDecoderRendersSilenceAndPLCWithoutInput() throws {
        let dec = try OpusVoiceDecoder()
        XCTAssertEqual(dec.render(.silence), [Float](repeating: 0, count: 320))
        XCTAssertEqual(dec.render(.conceal).count, 320)
        XCTAssertThrowsError(try dec.decode(Data([0xFF, 0xFF, 0xFF])))
    }
}
