import XCTest
@testable import MotopartyCore

/// PROTOCOL.md "Tracks", "Lyrics": fixtures/lyrics.json (shared with the
/// Kotlin and Python sides), the fetch policy and the per-track offset.
final class LyricsTests: XCTestCase {
    private func vectors() throws -> [String: Any] { try Fixtures.json("lyrics.json") }

    private func expectedLines(_ raw: Any?) throws -> [LyricLine] {
        let data = try JSONSerialization.data(withJSONObject: raw ?? [])
        return try JSONDecoder().decode([LyricLine].self, from: data)
    }

    func testParseVectors() throws {
        let cases = try XCTUnwrap(vectors()["parse"] as? [[String: Any]])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let lrc = try XCTUnwrap(c["lrc"] as? String, name)
            XCTAssertEqual(LRC.parse(lrc), try expectedLines(c["lines"]), name)
        }
    }

    func testTimelineVectors() throws {
        let v = try vectors()
        let timeline = try XCTUnwrap(v["timeline"] as? [String: Any])
        let cases = try XCTUnwrap(v["parse"] as? [[String: Any]])
        let index = try XCTUnwrap(timeline["case"] as? Int)
        let lyrics = Lyrics(id: "t", lines: try expectedLines(cases[index]["lines"]))
        let steps = try XCTUnwrap(timeline["steps"] as? [[String: Any]])
        XCTAssertFalse(steps.isEmpty)
        for step in steps {
            let t = try XCTUnwrap(step["t"] as? Int)
            let line = try XCTUnwrap(step["line"] as? Int)
            let sung = try XCTUnwrap(step["sung"] as? Int)
            XCTAssertEqual(lyrics.position(atMs: Int64(t)),
                           LyricsPosition(line: line < 0 ? nil : line, sung: sung), "t=\(t)")
        }
    }

    func testDecodeServed() throws {
        let served = try XCTUnwrap(vectors()["served"])
        let data = try JSONSerialization.data(withJSONObject: served)
        let lyrics = try XCTUnwrap(Lyrics.decode(data), "unknown fields are ignored")
        XCTAssertEqual(lyrics.id, "abcDEF_-123")
        XCTAssertEqual(lyrics.source, "lrclib")
        XCTAssertEqual(lyrics.lines.count, 5)
        XCTAssertTrue(lyrics.lines[2].isBreak)
        XCTAssertEqual(lyrics.lines[3].text, "Late \t short")
        XCTAssertEqual(lyrics.lines[3].words.map(\.ms), [9500, 9800])
        // The host's lines are what the parser makes of the same LRC.
        let parsed = try XCTUnwrap(vectors()["parse"] as? [[String: Any]])[0]
        XCTAssertEqual(lyrics.lines, try expectedLines(parsed["lines"]))
    }

    func testBreakWithoutTextOrWordsDecodes() throws {
        let data = Data(#"{"id":"a","lines":[{"ms":5}]}"#.utf8)
        let lyrics = try XCTUnwrap(Lyrics.decode(data))
        XCTAssertEqual(lyrics.lines, [LyricLine(ms: 5, text: "", words: [])])
        XCTAssertNil(Lyrics.decode(Data("not json".utf8)))
    }

    func testNeighbours() {
        let lines = (0..<5).map { LyricLine(ms: Int64($0) * 1_000, text: "l\($0)", words: []) }
        let lyrics = Lyrics(id: "a", lines: lines)
        XCTAssertNil(lyrics.previous(nil))
        XCTAssertNil(lyrics.previous(0))
        XCTAssertEqual(lyrics.previous(3)?.text, "l2")
        XCTAssertEqual(lyrics.upcoming(nil, count: 2).map(\.text), ["l0", "l1"])
        XCTAssertEqual(lyrics.upcoming(2, count: 2).map(\.text), ["l3", "l4"])
        XCTAssertEqual(lyrics.upcoming(3, count: 2).map(\.text), ["l4"])
        XCTAssertEqual(lyrics.upcoming(4, count: 2), [])
        XCTAssertEqual(Lyrics(id: "e", lines: []).position(atMs: 10), LyricsPosition(line: nil, sung: 0))
    }

    func testStampEdgeCases() {
        // A stamp not at the start, a broken one, and an over-long fraction (cut to three).
        XCTAssertEqual(LRC.parse(" [00:01]x\n[00:01x\n[0:02.98765]y").map(\.ms), [2987])
        // A lone \r is not a line break.
        XCTAssertEqual(LRC.parse("[00:01]a\rb").map(\.text), ["a\rb"])
    }

    // MARK: Fetch policy

    func testFoundAndMissingAreFinal() {
        var f = LyricsFetch()
        XCTAssertTrue(f.isPending)
        let body = Data(#"{"id":"a","lines":[]}"#.utf8)
        XCTAssertEqual(f.answered(status: 200, body: body), .done)
        XCTAssertEqual(f.lyrics, Lyrics(id: "a", lines: []))
        XCTAssertFalse(f.isPending)
        XCTAssertFalse(f.retryStarted())

        var g = LyricsFetch()
        XCTAssertEqual(g.answered(status: 404), .done)
        XCTAssertEqual(g.phase, .missing)
        var h = LyricsFetch()
        XCTAssertEqual(h.answered(status: 200, body: Data("<html>".utf8)), .done)
        XCTAssertEqual(h.phase, .missing, "a body that is not lyrics")
        var k = LyricsFetch()
        XCTAssertEqual(k.answered(status: 500), .done)
        XCTAssertEqual(k.phase, .missing)
    }

    func testBusyRetriesSixTimesThenGivesUp() {
        var f = LyricsFetch()
        for n in 0..<LyricsFetch.maxRetries {
            XCTAssertEqual(f.answered(status: n % 2 == 0 ? 503 : nil), .retry(afterMs: 5_000))
            XCTAssertEqual(f.phase, .waiting)
            XCTAssertTrue(f.isPending)
            XCTAssertEqual(f.answered(status: 404), .done, "no request is out while waiting")
            XCTAssertTrue(f.retryStarted())
        }
        XCTAssertEqual(f.retries, 6)
        XCTAssertEqual(f.answered(status: 503), .done)
        XCTAssertEqual(f.phase, .gaveUp)
        XCTAssertFalse(f.isPending)
    }

    func testRetryCanFindThem() {
        var f = LyricsFetch()
        _ = f.answered(status: 503)
        _ = f.retryStarted()
        XCTAssertEqual(f.answered(status: 200, body: Data(#"{"id":"a","lines":[{"ms":0,"text":"","words":[]}]}"#.utf8)), .done)
        XCTAssertEqual(f.lyrics?.lines.count, 1)
    }

    // MARK: Offset

    func testOffsetSteps() {
        var o = LyricsOffsets()
        XCTAssertEqual(o.of("a"), 0)
        o = o.stepped("a", by: -1)
        XCTAssertEqual(o.of("a"), -200)
        XCTAssertEqual(o.of("b"), 0)
        o = o.stepped("a", by: 1)
        XCTAssertNil(o.byTrack["a"], "back at 0: no entry")
        o = o.stepped("a", by: 1_000)
        XCTAssertEqual(o.of("a"), LyricsOffsets.limitMs)
        XCTAssertEqual(LyricsOffsets.load(o.encoded()), o)
        XCTAssertEqual(LyricsOffsets.load(nil), LyricsOffsets())
        XCTAssertEqual(LyricsOffsets.load(Data("x".utf8)), LyricsOffsets())
    }

    func testOffsetApplies() {
        // A negative offset shows the lyrics sooner: the position runs ahead.
        XCTAssertEqual(LyricsOffsets.lyricsMs(positionMs: 1_000.7, offsetMs: -200), 1_200)
        XCTAssertEqual(LyricsOffsets.lyricsMs(positionMs: 1_000, offsetMs: 400), 600)
        XCTAssertEqual(LyricsOffsets.lyricsMs(positionMs: .nan, offsetMs: 0), 0)
        let lyrics = Lyrics(id: "a", lines: [LyricLine(ms: 1_000, text: "x", words: [LyricWord(ms: 1_000, text: "x")])])
        XCTAssertNil(lyrics.position(atMs: LyricsOffsets.lyricsMs(positionMs: 900, offsetMs: 0)).line)
        XCTAssertEqual(lyrics.position(atMs: LyricsOffsets.lyricsMs(positionMs: 900, offsetMs: -200)).line, 0)
    }
}
