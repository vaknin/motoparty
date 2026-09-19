import Foundation
import XCTest
@testable import MotopartyCore

final class MessageFixtureTests: XCTestCase {
    func testEveryFixtureMessageRoundTrips() throws {
        let fixture = try Fixtures.json("control/messages.json")
        let messages = try XCTUnwrap(fixture["messages"] as? [[String: Any]])
        XCTAssertEqual(messages.count, 23)

        var seenTypes = Set<String>()
        for original in messages {
            let t = try XCTUnwrap(original["t"] as? String)
            let input = try JSONSerialization.data(withJSONObject: original)

            let decoded = try ControlCodec.decode(input)
            XCTAssertEqual(decoded.type, t)
            if case .unknown = decoded { XCTFail("\(t) decoded as unknown") }

            let encoded = try ControlCodec.encode(decoded)
            XCTAssertEqual(try Fixtures.canonical(jsonData: encoded), try Fixtures.canonical(original),
                           "re-encoded \(t) differs")

            let again = try ControlCodec.decode(encoded)
            XCTAssertEqual(again, decoded, "\(t) second decode differs")
            seenTypes.insert(t)
        }

        let allTypes: Set<String> = [
            "hello", "ping", "pong", "talk.open", "talk.close", "music.load", "music.ready",
            "music.error", "music.play", "music.pause", "music.stop", "music.control",
            "command.text", "announce", "state", "bye",
        ]
        XCTAssertEqual(seenTypes, allTypes, "fixtures should cover every message type")
    }

    func testDecodedValues() throws {
        let hello = try ControlCodec.decode(Data(#"{"t":"hello","proto":1,"role":"host","name":"Pixel 8","voicePort":47801,"httpPort":47802}"#.utf8))
        XCTAssertEqual(hello, .hello(Hello(role: .host, name: "Pixel 8", voicePort: 47801, httpPort: 47802)))

        let state = try ControlCodec.decode(Data(#"{"t":"state","talk":true,"queue":[]}"#.utf8))
        XCTAssertEqual(state, .state(HostState(talk: true)))
    }

    func testOptionalFieldsAreOmittedNotNull() throws {
        let hello = try ControlCodec.encode(.hello(Hello(role: .client, name: "iPhone")))
        let text = String(decoding: hello, as: UTF8.self)
        XCTAssertFalse(text.contains("null"))
        XCTAssertFalse(text.contains("voicePort"))

        let bye = String(decoding: try ControlCodec.encode(.bye(Bye())), as: UTF8.self)
        XCTAssertEqual(bye, #"{"t":"bye"}"#)

        let stop = String(decoding: try ControlCodec.encode(.musicStop), as: UTF8.self)
        XCTAssertEqual(stop, #"{"t":"music.stop"}"#)
    }

    func testPathSlashesAreNotEscaped() throws {
        let load = MusicLoad(id: "a", path: "/track/a.m4a", title: "T", artist: "A", durationMs: 1)
        let text = String(decoding: try ControlCodec.encode(.musicLoad(load)), as: UTF8.self)
        XCTAssertTrue(text.contains(#""/track/a.m4a""#), text)
    }

    func testMalformedInputClassification() {
        func error(_ json: String) -> ControlCodecError? {
            do { _ = try ControlCodec.decode(Data(json.utf8)); return nil } catch { return error as? ControlCodecError }
        }
        // Invalid JSON / not an object: close the connection.
        XCTAssertEqual(error("not json"), .invalidJSON)
        XCTAssertEqual(error("[1,2]"), .invalidJSON)
        XCTAssertTrue(error("{")!.closesConnection)
        // No `t`: dropped.
        XCTAssertEqual(error(#"{"id":1}"#), .missingType)
        XCTAssertFalse(error(#"{"id":1}"#)!.closesConnection)
        // Known type, missing or mistyped required field: dropped, connection stays up.
        // Volume is local, so volumeUp/Down are no longer music.control actions.
        for json in [#"{"t":"ping","id":1}"#, #"{"t":"ping","id":"1","t0":2}"#,
                     #"{"t":"state","talk":false}"#, #"{"t":"talk.close","by":"host","reason":"bored"}"#,
                     #"{"t":"music.control","action":"louder"}"#,
                     #"{"t":"music.control","action":"volumeDown"}"#] {
            guard case .invalidFields? = error(json) else { XCTFail("\(json) should be invalidFields"); continue }
            XCTAssertFalse(error(json)!.closesConnection)
        }
    }
}

final class FramingFixtureTests: XCTestCase {
    func testValidFrames() throws {
        let fixture = try Fixtures.json("control/framing.json")
        let valid = try XCTUnwrap(fixture["valid"] as? [[String: Any]])
        XCTAssertFalse(valid.isEmpty)
        for v in valid {
            let json = try XCTUnwrap(v["json"] as? String)
            let hex = try XCTUnwrap(v["hex"] as? String)

            // Encode: exact bytes.
            XCTAssertEqual(try Framing.frame(Data(json.utf8)).hex, hex)

            // Decode in one go and byte by byte.
            var whole = FrameDecoder()
            let frames = try whole.append(Data(hex: hex))
            XCTAssertEqual(frames.count, 1)
            XCTAssertEqual(try Fixtures.canonical(jsonData: frames[0]), try Fixtures.canonical(jsonData: Data(json.utf8)))

            var trickle = FrameDecoder()
            var got: [Data] = []
            for byte in Data(hex: hex) { got += try trickle.append(Data([byte])) }
            XCTAssertEqual(got, frames)
            XCTAssertEqual(trickle.pendingByteCount, 0)

            // And through the message codec.
            _ = try ControlCodec.decode(frames[0])
        }
    }

    func testTwoFramesInOneRead() throws {
        let a = try Framing.frame(.bye(Bye()))
        let b = try Framing.frame(.ping(Ping(id: 1, t0: 2)))
        var d = FrameDecoder()
        let frames = try d.append(a + b.prefix(3))
        XCTAssertEqual(frames.count, 1)
        let rest = try d.append(b.dropFirst(3))
        XCTAssertEqual(try rest.map(ControlCodec.decode), [.ping(Ping(id: 1, t0: 2))])
    }

    func testInvalidHeadersRejectedBeforeBody() throws {
        let fixture = try Fixtures.json("control/framing.json")
        let invalid = try XCTUnwrap(fixture["invalid"] as? [[String: Any]])
        XCTAssertFalse(invalid.isEmpty)
        for v in invalid {
            let hex = try XCTUnwrap(v["hex"] as? String)
            var d = FrameDecoder()
            // Only the header is supplied: rejection must not wait for the body.
            XCTAssertThrowsError(try d.append(Data(hex: hex)), v["why"] as? String ?? "") { error in
                guard case FramingError.oversize = error else { return XCTFail("wrong error \(error)") }
            }
        }
    }

    func testSizeLimitBoundary() throws {
        XCTAssertNoThrow(try Framing.frame(Data(count: 65_536)))
        XCTAssertThrowsError(try Framing.frame(Data(count: 65_537)))
        var d = FrameDecoder()
        XCTAssertNoThrow(try d.append(Data(hex: "00010000")))
    }

    func testMalformedMessagesAreDroppedNotFatal() throws {
        let fixture = try Fixtures.json("control/framing.json")
        let malformed = try XCTUnwrap(fixture["malformed"] as? [[String: Any]])
        XCTAssertEqual(malformed.count, 6, "every malformed vector must be exercised")
        for m in malformed {
            let json = try XCTUnwrap(m["json"] as? String)
            // Framed and received like any other frame…
            var d = FrameDecoder()
            let frames = try d.append(try Framing.frame(Data(json.utf8)))
            XCTAssertEqual(frames.count, 1)
            // …then rejected by the codec with a non-closing error.
            XCTAssertThrowsError(try ControlCodec.decode(frames[0]), json) { error in
                guard let e = error as? ControlCodecError else { return XCTFail("\(error)") }
                XCTAssertFalse(e.closesConnection, "\(json) must not close the connection")
            }
        }
    }

    func testFatalFramesCloseTheConnection() throws {
        let fixture = try Fixtures.json("control/framing.json")
        let fatal = try XCTUnwrap(fixture["fatal"] as? [[String: Any]])
        XCTAssertFalse(fatal.isEmpty)
        for f in fatal {
            let hex = try XCTUnwrap(f["hex"] as? String)
            var d = FrameDecoder()
            let frames = try d.append(Data(hex: hex))
            XCTAssertEqual(frames.count, 1, hex)
            XCTAssertThrowsError(try ControlCodec.decode(frames[0]), hex) { error in
                XCTAssertEqual(error as? ControlCodecError, .invalidJSON)
                XCTAssertEqual((error as? ControlCodecError)?.closesConnection, true)
            }
        }
    }

    func testUnknownTypesAndFields() throws {
        let fixture = try Fixtures.json("control/framing.json")
        let unknown = try XCTUnwrap(fixture["unknown"] as? [[String: Any]])
        XCTAssertEqual(unknown.count, 2)

        let future = try ControlCodec.decode(Data(try XCTUnwrap(unknown[0]["json"] as? String).utf8))
        XCTAssertEqual(future, .unknown(type: "future.thing"))

        let ping = try ControlCodec.decode(Data(try XCTUnwrap(unknown[1]["json"] as? String).utf8))
        XCTAssertEqual(ping, .ping(Ping(id: 1, t0: 2)))
    }
}

final class ClockFixtureTests: XCTestCase {
    func testEstimatorSteps() throws {
        let fixture = try Fixtures.json("clock.json")
        let steps = try XCTUnwrap(fixture["steps"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(steps.count, 13)

        var clock = ClockSync()
        XCTAssertNil(clock.offset)
        for (i, step) in steps.enumerated() {
            let s = try XCTUnwrap(step["sample"] as? [NSNumber]).map { $0.int64Value }
            let expect = try XCTUnwrap(step["expectOffset"] as? NSNumber).doubleValue
            clock.add(t0: s[0], t1: s[1], t2: s[2], t3: s[3])
            XCTAssertEqual(clock.offset, expect, "step \(i)")
        }

        let conversions = try XCTUnwrap(fixture["conversions"] as? [[String: Any]])
        for c in conversions {
            let host = try XCTUnwrap(c["host"] as? NSNumber).doubleValue
            let local = try XCTUnwrap(c["local"] as? NSNumber).doubleValue
            XCTAssertEqual(clock.hostToLocal(host), local)
            XCTAssertEqual(clock.localToHost(local), host)
        }
    }

    func testNegativeRttDiscarded() {
        var clock = ClockSync()
        XCTAssertFalse(clock.add(t0: 4000, t1: 7990, t2: 7991, t3: 3985))
        XCTAssertNil(clock.offset)
        XCTAssertTrue(clock.samples.isEmpty)
    }

    func testStepResetFlushesWindow() {
        var clock = ClockSync()
        clock.add(t0: 0, t1: 1000, t2: 1000, t3: 2)      // offset 999, rtt 2
        clock.add(t0: 10, t1: 5000, t2: 5000, t3: 30)    // offset 4980, rtt 20 → step
        XCTAssertEqual(clock.offset, 4980)
        XCTAssertEqual(clock.samples.count, 1)
    }
}

final class VoiceHeaderFixtureTests: XCTestCase {
    func testValidPackets() throws {
        let fixture = try Fixtures.json("voice/header.json")
        let valid = try XCTUnwrap(fixture["valid"] as? [[String: Any]])
        XCTAssertEqual(valid.count, 3)
        for v in valid {
            let kind = try XCTUnwrap(VoicePacket.Kind(rawValue: UInt8(try XCTUnwrap(v["kind"] as? NSNumber).intValue)))
            let seq = UInt16(try XCTUnwrap(v["seq"] as? NSNumber).intValue)
            let ts = UInt32(try XCTUnwrap(v["ts"] as? NSNumber).uint32Value)
            let payload = Data(hex: try XCTUnwrap(v["payloadHex"] as? String))
            let hex = try XCTUnwrap(v["hex"] as? String)

            let packet = VoicePacket(kind: kind, seq: seq, ts: ts, payload: payload)
            XCTAssertEqual(packet.encoded().hex, hex)
            XCTAssertEqual(VoicePacket.decode(Data(hex: hex)), packet)
        }
    }

    func testInvalidPackets() throws {
        let fixture = try Fixtures.json("voice/header.json")
        let invalid = try XCTUnwrap(fixture["invalid"] as? [[String: Any]])
        XCTAssertEqual(invalid.count, 3)
        for v in invalid {
            let hex = try XCTUnwrap(v["hex"] as? String)
            XCTAssertNil(VoicePacket.decode(Data(hex: hex)), v["why"] as? String ?? hex)
        }
    }

    func testSequencerRunningClock() {
        var s = VoiceSequencer(seq: 65_535, startTs: UInt32.max - 159, startMs: 1_000)
        // ts is a running 16 kHz clock: 16 samples per ms, wrapping.
        XCTAssertEqual(s.clockTs(nowMs: 1_000), UInt32.max - 159)
        XCTAssertEqual(s.clockTs(nowMs: 1_010), 0)
        XCTAssertEqual(s.clockTs(nowMs: 1_020), 160)

        // Keepalive while talk is closed carries the current clock.
        let k = s.keepalive(nowMs: 1_020)
        XCTAssertEqual(k.kind, .keepalive)
        XCTAssertEqual(k.seq, 65_535)
        XCTAssertEqual(k.ts, 160)

        // Capture starts: first frame aligns to the clock, then +320 per frame,
        // DTX-skipped frames advance ts but not seq.
        s.beginCapture()
        let a0 = s.audio(Data([1]), nowMs: 2_000)
        XCTAssertEqual(a0.seq, 0)
        XCTAssertEqual(a0.ts, s.clockTs(nowMs: 2_000))
        s.skipFrame(nowMs: 2_021)
        let a2 = s.audio(Data([2]), nowMs: 2_039) // capture jitter does not move ts
        XCTAssertEqual(a2.seq, 1)
        XCTAssertEqual(a2.ts, a0.ts &+ 640)

        // After a long stall the frame clock re-aligns to the running clock.
        let a3 = s.audio(Data([3]), nowMs: 5_000)
        XCTAssertEqual(a3.ts, s.clockTs(nowMs: 5_000))
        XCTAssertEqual(a3.seq, 2)
    }
}
