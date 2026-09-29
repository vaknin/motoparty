import Foundation
import XCTest
@testable import MotopartyCore

final class CommandFixtureTests: XCTestCase {
    /// `{"action": …}` plus `kind`/`query` for play, the shape of
    /// `fixtures/commands.json`.
    private func dictionary(_ command: Command) -> [String: String] {
        switch command {
        case .play(let kind, let query): ["action": "play", "kind": kind.rawValue, "query": query]
        default: ["action": command.action]
        }
    }

    func testEveryFixtureCase() throws {
        let fixture = try Fixtures.json("commands.json")
        let cases = try XCTUnwrap(fixture["cases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 45)
        for c in cases {
            let text = try XCTUnwrap(c["text"] as? String)
            let expect = try XCTUnwrap(c["expect"] as? [String: String])
            XCTAssertEqual(dictionary(CommandParser.parse(text)), expect, "parsing \(text.debugDescription)")
        }
    }

    func testNormalisationPerCodePoint() {
        XCTAssertEqual(CommandParser.normalize("What's 4U?"), ["what's", "4u"])
        // U+2019 becomes an apostrophe; é (L*) and its decomposed form (L* + M*) survive.
        XCTAssertEqual(CommandParser.normalize("Guns N\u{2019} Roses"), ["guns", "n'", "roses"])
        XCTAssertEqual(CommandParser.normalize("Beyonc\u{e9} / Beyonce\u{301}"), ["beyoncé", "beyoncé"])
        // Punctuation, symbols and control characters are separators, not removals.
        XCTAssertEqual(CommandParser.normalize("volume-up"), ["volume", "up"])
        XCTAssertEqual(CommandParser.normalize("\u{5}play\tme\u{a0}now!"), ["play", "me", "now"])
        XCTAssertEqual(CommandParser.normalize("   "), [])
        XCTAssertEqual(CommandParser.normalize("שלום, עולם"), ["שלום", "עולם"])
    }

    func testGrammarEdges() {
        XCTAssertEqual(CommandParser.parse("Hey, please play the Beatles"), .play(kind: .song, query: "the beatles"))
        XCTAssertEqual(CommandParser.parse("play the song"), .unknown)
        XCTAssertEqual(CommandParser.parse("PLAY   ALBUM   שלום עולם!"), .play(kind: .album, query: "שלום עולם"))
        XCTAssertEqual(CommandParser.parse("please please pause"), .pause)
        // Only one trailing "please" is dropped.
        XCTAssertEqual(CommandParser.parse("pause please please"), .unknown)
        XCTAssertEqual(CommandParser.parse("volume-up"), .volumeUp)
        XCTAssertEqual(CommandParser.parse("play don't stop me now"), .play(kind: .song, query: "don't stop me now"))
        // The non-play phrases must match exactly.
        XCTAssertEqual(CommandParser.parse("next song"), .unknown)
        XCTAssertEqual(CommandParser.parse("turn it up"), .unknown)
    }

    func testVolumeIsLocal() {
        XCTAssertTrue(CommandParser.parse("louder").isVolume)
        XCTAssertTrue(CommandParser.parse("quieter please").isVolume)
        XCTAssertFalse(CommandParser.parse("next").isVolume)
        XCTAssertFalse(CommandParser.parse("play louder").isVolume)
        // …and is not a wire action any more.
        XCTAssertNil(MusicAction(rawValue: "volumeUp"))
        XCTAssertEqual(MusicAction.allCases.map(\.rawValue), ["pause", "resume", "next", "previous"])
    }

    func testEnd() {
        XCTAssertEqual(CommandParser.parse("Over."), .end)
        XCTAssertEqual(CommandParser.parse("hey end talk please"), .end)
        XCTAssertEqual(CommandParser.parse("hang up"), .end)
        XCTAssertEqual(CommandParser.parse("over and out"), .unknown)
        XCTAssertEqual(CommandParser.parse("end"), .unknown)
        XCTAssertFalse(Command.end.isVolume)
    }

    func testNowPlayingAndShuffle() {
        XCTAssertEqual(CommandParser.parse("What\u{2019}s playing?"), .nowPlaying)
        XCTAssertEqual(CommandParser.parse("what song is this"), .nowPlaying)
        XCTAssertEqual(CommandParser.parse("Shuffle!"), .shuffle)
        XCTAssertEqual(CommandParser.parse("shuffle the queue"), .unknown)
        XCTAssertEqual(Command.nowPlaying.action, "nowplaying")
        // Sent as command.text like every non-volume command.
        XCTAssertFalse(Command.nowPlaying.isVolume)
        XCTAssertFalse(Command.shuffle.isVolume)
    }
}

final class FirstPhraseTests: XCTestCase {
    func testEveryFixtureCase() throws {
        let fixture = try Fixtures.json("first_phrase.json")
        XCTAssertEqual(fixture["firstPhraseMs"] as? Double, FirstPhraseGate.firstPhraseMs)
        let cases = try XCTUnwrap(fixture["cases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 12)
        for c in cases {
            let name = try XCTUnwrap(c["name"] as? String)
            let role = try XCTUnwrap((c["role"] as? String).flatMap(FirstPhraseGate.Role.init(rawValue:)), name)
            let phrases = try XCTUnwrap(c["phrases"] as? [[String: Any]])
            let expect = try XCTUnwrap(c["expect"] as? [Any])
            XCTAssertEqual(phrases.count, expect.count, name)
            // Any live-earcon time: only the difference counts.
            let liveAtMs = 123_456.0
            var gate = FirstPhraseGate(role: role, liveAtMs: liveAtMs)
            for (phrase, want) in zip(phrases, expect) {
                let text = try XCTUnwrap(phrase["text"] as? String)
                let atMs = try XCTUnwrap(phrase["atMs"] as? Double)
                // JSON null arrives as NSNull: conversation.
                let expected: HeardPhrase = (want as? String).map(HeardPhrase.command) ?? .conversation
                XCTAssertEqual(gate.classify(text, nowMs: liveAtMs + atMs), expected, "\(name): \(text.debugDescription)")
            }
        }
    }

    func testSpent() {
        var gate = FirstPhraseGate(role: .opener, liveAtMs: 1_000)
        XCTAssertFalse(gate.isSpent(atMs: 1_000))
        XCTAssertFalse(gate.isSpent(atMs: 9_000))
        XCTAssertTrue(gate.isSpent(atMs: 9_001))
        // An empty phrase spends nothing; the first real one spends it, parsed or not.
        XCTAssertEqual(gate.classify("…", nowMs: 2_000), .conversation)
        XCTAssertFalse(gate.isSpent(atMs: 2_000))
        XCTAssertEqual(gate.classify("hello there", nowMs: 3_000), .conversation)
        XCTAssertTrue(gate.isSpent(atMs: 3_000))

        XCTAssertTrue(FirstPhraseGate(role: .other, liveAtMs: 0).isSpent(atMs: 0))
        XCTAssertFalse(FirstPhraseGate(role: .solo, liveAtMs: 0).isSpent(atMs: 60_000))
    }

    func testCommandTextFeedsTheParser() {
        var gate = FirstPhraseGate(role: .opener, liveAtMs: 0)
        XCTAssertEqual(gate.classify("Hey, turn it LOUDER please!", nowMs: 500), .conversation)
        gate = FirstPhraseGate(role: .opener, liveAtMs: 0)
        let heard = gate.classify("Hey, louder please!", nowMs: 500)
        XCTAssertEqual(heard, .command("hey louder please"))
        if case .command(let text) = heard { XCTAssertEqual(CommandParser.parse(text), .volumeUp) }
    }
}
