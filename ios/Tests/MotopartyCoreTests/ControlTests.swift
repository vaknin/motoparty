import Foundation
import XCTest
@testable import MotopartyCore

final class MessageFixtureTests: XCTestCase {
    func testEveryFixtureMessageRoundTrips() throws {
        let fixture = try Fixtures.json("control/messages.json")
        let messages = try XCTUnwrap(fixture["messages"] as? [[String: Any]])
        XCTAssertEqual(messages.count, 52)

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
            "music.error", "music.play", "music.pause", "music.next", "music.stop", "music.control",
            "command.text", "music.search", "music.browse", "music.results", "music.enqueue",
            "music.edit", "music.download", "music.downloads", "announce", "state", "bye",
        ]
        XCTAssertEqual(seenTypes, allTypes, "fixtures should cover every message type")
    }

    func testDecodedValues() throws {
        let hello = try ControlCodec.decode(Data(#"{"t":"hello","proto":1,"role":"host","name":"Pixel 8","voicePort":47801,"httpPort":47802}"#.utf8))
        XCTAssertEqual(hello, .hello(Hello(role: .host, name: "Pixel 8", voicePort: 47801, httpPort: 47802)))
        let smart = try ControlCodec.decode(Data(#"{"t":"hello","proto":1,"role":"host","name":"Pixel 8","interpret":true}"#.utf8))
        XCTAssertEqual(smart, .hello(Hello(role: .host, name: "Pixel 8", interpret: true)))
        XCTAssertFalse(String(decoding: try ControlCodec.encode(hello), as: UTF8.self).contains("interpret"))

        let state = try ControlCodec.decode(Data(#"{"t":"state","talk":true,"queue":[]}"#.utf8))
        XCTAssertEqual(state, .state(HostState(talk: true)))

        let next = try ControlCodec.decode(Data(#"{"t":"music.next","id":"a","atHostTimeMs":5}"#.utf8))
        XCTAssertEqual(next, .musicNext(MusicNext(id: "a", atHostTimeMs: 5)))

        let queue = try ControlCodec.decode(Data(#"{"t":"state","talk":false,"queue":[{"id":"a","title":"T","artist":"A","durationMs":1000,"art":"https://x/a.jpg"},{"id":"b","title":"T","artist":"A","durationMs":2000},{"id":"c","title":"T","artist":"A"}]}"#.utf8))
        XCTAssertEqual(queue, .state(HostState(talk: false, queue: [
            .init(id: "a", title: "T", artist: "A", durationMs: 1000, art: "https://x/a.jpg"),
            .init(id: "b", title: "T", artist: "A", durationMs: 2000),
            .init(id: "c", title: "T", artist: "A"),
        ])))
    }

    /// PROTOCOL.md "Repeat by touch": `repeat` carries the mode to set, `off` included, and needs it.
    func testRepeatControlCarriesItsMode() throws {
        for mode in RepeatSetting.allCases {
            let message = ControlMessage.musicControl(.setRepeat(mode))
            let encoded = try ControlCodec.encode(message)
            XCTAssertEqual(try Fixtures.canonical(jsonData: encoded),
                           try Fixtures.canonical(["t": "music.control", "action": "repeat", "mode": mode.rawValue]))
            XCTAssertEqual(try ControlCodec.decode(encoded), message)
        }
        // The other actions send no mode.
        let next = String(decoding: try ControlCodec.encode(.musicControl(MusicControl(action: .next))), as: UTF8.self)
        XCTAssertFalse(next.contains("mode"))
        for json in [#"{"t":"music.control","action":"repeat"}"#,
                     #"{"t":"music.control","action":"repeat","mode":"all"}"#,
                     #"{"t":"music.control","action":"repeat","mode":"Track"}"#,
                     #"{"t":"music.control","action":"repeat","mode":null}"#,
                     #"{"t":"music.control","action":"repeat","mode":1}"#,
                     #"{"t":"music.control","action":"pause","mode":"all"}"#] {
            XCTAssertThrowsError(try ControlCodec.decode(Data(json.utf8)), json) { error in
                XCTAssertFalse((error as? ControlCodecError)?.closesConnection ?? true, json)
            }
        }
    }

    /// PROTOCOL.md "Browsing" step 6: `start` carries the songs' refs and needs
    /// them; `stop` sends none. `state.busy` is optional.
    func testDownloadMessages() throws {
        let start = ControlMessage.musicDownload(.start("PL1", ids: ["a1", "a2"]))
        XCTAssertEqual(try Fixtures.canonical(jsonData: try ControlCodec.encode(start)),
                       try Fixtures.canonical(["t": "music.download", "op": "start", "ref": "PL1", "ids": ["a1", "a2"]]))
        XCTAssertEqual(try ControlCodec.decode(try ControlCodec.encode(start)), start)
        let stop = String(decoding: try ControlCodec.encode(.musicDownload(.stop("PL1"))), as: UTF8.self)
        XCTAssertFalse(stop.contains("ids"))
        XCTAssertEqual(DownloadOp.allCases.map(\.rawValue), ["start", "stop"])

        let downloads = try ControlCodec.decode(Data(#"{"t":"music.downloads","cached":["a1"],"downloads":[{"ref":"PL1","done":1,"total":2,"failed":0,"running":true}]}"#.utf8))
        XCTAssertEqual(downloads, .musicDownloads(MusicDownloads(cached: ["a1"], downloads: [
            DownloadItem(ref: "PL1", done: 1, total: 2, failed: 0, running: true),
        ])))
        let busy = try ControlCodec.decode(Data(#"{"t":"state","talk":false,"queue":[],"busy":"Searching song \"x\""}"#.utf8))
        XCTAssertEqual(busy, .state(HostState(talk: false, busy: #"Searching song "x""#)))
        XCTAssertFalse(String(decoding: try ControlCodec.encode(.state(HostState(talk: false))), as: UTF8.self).contains("busy"))

        for json in [#"{"t":"music.download","op":"start","ref":"PL1"}"#,
                     #"{"t":"music.download","op":"start","ref":"PL1","ids":null}"#,
                     #"{"t":"music.download","op":"start","ref":"PL1","ids":[1]}"#,
                     #"{"t":"music.download","op":"Stop","ref":"PL1"}"#,
                     #"{"t":"music.download","op":"stop"}"#,
                     #"{"t":"music.downloads","cached":[]}"#,
                     #"{"t":"music.downloads","cached":[],"downloads":[{"ref":"PL1","done":0,"total":1,"failed":0}]}"#,
                     #"{"t":"state","talk":false,"queue":[],"busy":1}"#] {
            XCTAssertThrowsError(try ControlCodec.decode(Data(json.utf8)), json) { error in
                XCTAssertFalse((error as? ControlCodecError)?.closesConnection ?? true, json)
            }
        }
    }

    /// The Download button reads as on the Pixel and the marks follow `cached`.
    func testCollectionDownloadButton() {
        var downloads = HostDownloads()
        XCTAssertEqual(downloads.button(ref: "PL1", songs: ["a", "b"]).label, "Download")
        XCTAssertEqual(downloads.button(ref: "PL1", songs: []).phase, .idle)
        downloads = HostDownloads(MusicDownloads(cached: ["a"], downloads: [
            DownloadItem(ref: "PL1", done: 1, total: 2, failed: 0, running: true),
            DownloadItem(ref: "PL2", done: 3, total: 3, failed: 1, running: false),
            DownloadItem(ref: "PL3", done: 2, total: 2, failed: 0, running: false),
        ]))
        XCTAssertTrue(downloads.isCached("a"))
        XCTAssertFalse(downloads.isCached("b"))
        let running = downloads.button(ref: "PL1", songs: ["a", "b"])
        XCTAssertEqual(running.label, "Downloading 1/2 · Stop")
        XCTAssertTrue(running.isRunning)
        XCTAssertEqual(running.fraction, 0.5)
        XCTAssertEqual(downloads.button(ref: "PL2", songs: ["a", "b", "c"]).label, "Retry · 2/3 saved")
        XCTAssertTrue(downloads.button(ref: "PL3", songs: ["x"]).isDone, "finished without failures")
        // Every song already cached: done without any download of this one.
        let cached = downloads.button(ref: "PL9", songs: ["a"])
        XCTAssertEqual(cached.label, "Downloaded")
        XCTAssertNil(cached.fraction)
    }

    /// The button cycles like the Pixel's, from the mode in the host's last `state`.
    func testRepeatButtonCycle() {
        XCTAssertEqual(RepeatSetting(nil), .off)
        XCTAssertEqual(RepeatSetting(.queue), .queue)
        XCTAssertEqual(RepeatSetting(.track), .track)
        XCTAssertEqual(RepeatSetting.off.next, .queue)
        XCTAssertEqual(RepeatSetting.queue.next, .track)
        XCTAssertEqual(RepeatSetting.track.next, .off)
        XCTAssertEqual(RepeatSetting.allCases.map(\.label), ["Repeat off", "Repeat this song", "Repeat the queue"])
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

    func testDecodedBrowsingValues() throws {
        let search = try ControlCodec.decode(Data(#"{"t":"music.search","id":7,"kind":"songs","query":"money"}"#.utf8))
        XCTAssertEqual(search, .musicSearch(MusicSearch(id: 7, kind: .songs, query: "money")))

        // Artists (2026-10-02): the search kind, the browse kind and the page's albums.
        let artists = try ControlCodec.decode(Data(#"{"t":"music.search","id":7,"kind":"artists","query":"pink floyd"}"#.utf8))
        XCTAssertEqual(artists, .musicSearch(MusicSearch(id: 7, kind: .artists, query: "pink floyd")))
        let page = try ControlCodec.decode(Data(#"{"t":"music.browse","id":8,"ref":"UCY2qt3dw2TQJxvBrDiYGHdQ","kind":"artist"}"#.utf8))
        XCTAssertEqual(page, .musicBrowse(MusicBrowse(id: 8, ref: "UCY2qt3dw2TQJxvBrDiYGHdQ", kind: .artist)))
        let album = try ControlCodec.decode(Data(#"{"t":"music.browse","id":9,"ref":"OLAK5uy_x"}"#.utf8))
        XCTAssertEqual(album, .musicBrowse(MusicBrowse(id: 9, ref: "OLAK5uy_x")))
        let artistPage = try ControlCodec.decode(Data(#"{"t":"music.results","id":8,"items":[{"ref":"_FrOQC-zEog","title":"Comfortably Numb","artist":"Pink Floyd","durationMs":382000}],"albums":[{"ref":"OLAK5uy_x","title":"The Dark Side of the Moon","artist":"Pink Floyd","count":10}]}"#.utf8))
        guard case .musicResults(let r) = artistPage else { return XCTFail("\(artistPage)") }
        XCTAssertEqual(r.items.first?.durationMs, 382000)
        XCTAssertEqual(r.albums?.first?.title, "The Dark Side of the Moon")
        XCTAssertEqual(r.albums?.first?.count, 10)
        let plainResults = try ControlCodec.decode(Data(#"{"t":"music.results","id":7,"items":[]}"#.utf8))
        guard case .musicResults(let pr) = plainResults else { return XCTFail("\(plainResults)") }
        XCTAssertNil(pr.albums)
        // An absent kind is omitted on the wire, not null.
        let plainBrowse = try ControlCodec.encode(.musicBrowse(MusicBrowse(id: 9, ref: "x")))
        XCTAssertEqual(try Fixtures.canonical(jsonData: plainBrowse), #"{"id":9,"ref":"x","t":"music.browse"}"#)

        let edit = try ControlCodec.decode(Data(#"{"t":"music.edit","op":"remove","index":0,"id":"a"}"#.utf8))
        XCTAssertEqual(edit, .musicEdit(MusicEdit(op: .remove, index: 0, id: "a")))

        let move = try ControlCodec.decode(Data(#"{"t":"music.edit","op":"move","index":4,"id":"x1","to":0}"#.utf8))
        XCTAssertEqual(move, .musicEdit(MusicEdit(op: .move, index: 4, id: "x1", to: 0)))

        let repeating = try ControlCodec.decode(Data(#"{"t":"state","talk":false,"music":{"id":"x1","title":"A","artist":"B","playing":true,"positionMs":0,"atHostTimeMs":5,"durationMs":1000,"repeat":"track"},"queue":[]}"#.utf8))
        guard case .state(let s) = repeating else { return XCTFail("\(repeating)") }
        XCTAssertEqual(s.music?.repeat, .track)
        let plain = try ControlCodec.decode(Data(#"{"t":"state","talk":false,"music":{"id":"x1","title":"A","artist":"B","playing":true,"positionMs":0,"atHostTimeMs":5,"durationMs":1000},"queue":[]}"#.utf8))
        guard case .state(let p) = plain else { return XCTFail("\(plain)") }
        XCTAssertNil(p.music?.repeat)

        // `clear` needs neither field, and they are omitted, not null.
        let clear = try ControlCodec.encode(.musicEdit(MusicEdit(op: .clear)))
        XCTAssertEqual(try Fixtures.canonical(jsonData: clear), #"{"op":"clear","t":"music.edit"}"#)
    }

    func testEnqueueFitsOneFrame() throws {
        func size(_ m: MusicEnqueue) throws -> Int { try ControlCodec.encode(.musicEnqueue(m)).count }
        let art = "https://i.ytimg.com/vi/" + String(repeating: "x", count: 200) + "/mqdefault.jpg"
        let tracks = (0..<300).map {
            EnqueueTrack(id: "id\($0)", title: String(repeating: "t", count: 100), artist: "A", durationMs: 1000, art: art)
        }

        // Capped at 200 first.
        let small = MusicEnqueue(mode: .end, tracks: Array(tracks.prefix(3))).fitted()
        XCTAssertEqual(small.tracks.count, 3)
        XCTAssertNotNil(small.tracks[0].art, "art is only dropped when needed")

        // 200 tracks with art are over 64 KiB; without art they fit.
        let capped = MusicEnqueue(mode: .now, tracks: tracks, art: "https://a").fitted()
        XCTAssertEqual(capped.tracks.count, MusicEnqueue.maxTracks)
        XCTAssertTrue(capped.tracks.allSatisfy { $0.art == nil })
        XCTAssertEqual(capped.art, "https://a")
        XCTAssertLessThan(try size(capped), Framing.maxFrameLength)

        // Still too big without art: trailing tracks go, the order stays.
        let long = tracks.map { t in
            var t = t; t.title = String(repeating: "t", count: 1000); return t
        }
        let cut = MusicEnqueue(mode: .now, tracks: long).fitted()
        XCTAssertLessThan(cut.tracks.count, MusicEnqueue.maxTracks)
        XCTAssertGreaterThan(cut.tracks.count, 50)
        XCTAssertEqual(cut.tracks.map(\.id), long.prefix(cut.tracks.count).map(\.id))
        XCTAssertLessThan(try size(cut), Framing.maxFrameLength)
        var one = cut; one.tracks.append(long[cut.tracks.count]); one.tracks = one.tracks.map { var t = $0; t.art = nil; return t }
        XCTAssertGreaterThanOrEqual(try size(one), Framing.maxFrameLength, "the largest prefix that fits")
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
                     #"{"t":"music.next","id":"a"}"#, #"{"t":"music.next","id":"a","atHostTimeMs":"5"}"#,
                     #"{"t":"state","talk":false,"queue":[{"id":"a","title":"T","artist":"A","durationMs":"1"}]}"#,
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
        XCTAssertEqual(malformed.count, 28, "every malformed vector must be exercised")
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
        XCTAssertEqual(fatal.count, 6, "every fatal vector must be exercised")
        for f in fatal {
            let hex = try XCTUnwrap(f["hex"] as? String)
            var d = FrameDecoder()
            let frames = try d.append(Data(hex: hex))
            XCTAssertEqual(frames.count, 1, hex)
            if (f["_doc"] as? String)?.contains("invalid UTF-8") == true {
                XCTAssertFalse(ControlCodec.isValidUTF8(frames[0]), hex)
            }
            XCTAssertThrowsError(try ControlCodec.decode(frames[0]), hex) { error in
                XCTAssertEqual(error as? ControlCodecError, .invalidJSON)
                XCTAssertEqual((error as? ControlCodecError)?.closesConnection, true)
            }
        }
    }

    func testFatalVectorsIncludeTheFourInvalidUTF8Frames() throws {
        let fixture = try Fixtures.json("control/framing.json")
        let fatal = try XCTUnwrap(fixture["fatal"] as? [[String: Any]])
        XCTAssertEqual(fatal.filter { ($0["_doc"] as? String)?.contains("invalid UTF-8") == true }.count, 4)
    }

    func testStrictUTF8() {
        XCTAssertTrue(ControlCodec.isValidUTF8(Data()))
        XCTAssertTrue(ControlCodec.isValidUTF8(Data("{\"t\":\"announce\",\"text\":\"שלום 🎵\"}".utf8)))
        XCTAssertFalse(ControlCodec.isValidUTF8(Data([0x22, 0xFF, 0x22])))
        XCTAssertFalse(ControlCodec.isValidUTF8(Data([0xC3, 0x28])))
        XCTAssertFalse(ControlCodec.isValidUTF8(Data([0xED, 0xA0, 0x80])))
        XCTAssertFalse(ControlCodec.isValidUTF8(Data([0xC0, 0xAF])))
        // Truncated at the end of the frame.
        XCTAssertFalse(ControlCodec.isValidUTF8(Data([0x61, 0xE2, 0x82])))
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

    /// `stepReset`: a fresh estimator; a slow pong is kept, a real step
    /// resets, and the `500 + rtt / 2` boundary is pinned on both sides.
    func testStepReset() throws {
        let fixture = try Fixtures.json("clock.json")
        let block = try XCTUnwrap(fixture["stepReset"] as? [String: Any])
        let steps = try XCTUnwrap(block["steps"] as? [[String: Any]])
        XCTAssertFalse(steps.isEmpty)

        var clock = ClockSync()
        for (i, step) in steps.enumerated() {
            let s = try XCTUnwrap(step["sample"] as? [NSNumber]).map { $0.int64Value }
            let expect = try XCTUnwrap(step["expectOffset"] as? NSNumber).doubleValue
            clock.add(t0: s[0], t1: s[1], t2: s[2], t3: s[3])
            XCTAssertEqual(clock.offset, expect, "stepReset step \(i)")
        }
    }

    func testKeepaliveDue() {
        XCTAssertFalse(LinkDefaults.keepaliveDue(nowMs: 1_999, lastSentMs: 1_000))
        XCTAssertTrue(LinkDefaults.keepaliveDue(nowMs: 2_000, lastSentMs: 1_000))
        // Ticks at 250 ms: the gap after the last packet stays under 1.25 s.
        XCTAssertLessThanOrEqual(LinkDefaults.keepaliveIntervalMs + LinkDefaults.keepaliveTickMs, 1_250)
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
