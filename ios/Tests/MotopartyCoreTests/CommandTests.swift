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
        XCTAssertEqual(cases.count, 35)
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
}

final class WakeWordTests: XCTestCase {
    func testEveryFixtureCase() throws {
        let fixture = try Fixtures.json("wake.json")
        let cases = try XCTUnwrap(fixture["cases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 16)
        for c in cases {
            let text = try XCTUnwrap(c["text"] as? String)
            // JSON null arrives as NSNull: "not a command".
            let expect = c["expect"] as? String
            XCTAssertEqual(CommandParser.wakeCommand(text), expect, "wake rule on \(text.debugDescription)")
        }
    }

    func testCommandTextFeedsTheParser() {
        let text = CommandParser.wakeCommand("Moto party, over")
        XCTAssertEqual(text, "over")
        XCTAssertEqual(text.map(CommandParser.parse), .end)
        XCTAssertEqual(CommandParser.wakeCommand("okay okay motor party louder").map(CommandParser.parse), .volumeUp)
        // The wake word must come first, as whole words.
        XCTAssertNil(CommandParser.wakeCommand("next moto party"))
        XCTAssertNil(CommandParser.wakeCommand("motor parts next"))
    }

    func testGateNeedsTheWakeWord() {
        var gate = WakeGate()
        XCTAssertEqual(gate.classify("play pink floyd", nowMs: 0), .conversation)
        XCTAssertEqual(gate.classify("Moto party, next", nowMs: 100), .command("next"))
        XCTAssertEqual(gate.classify("next", nowMs: 200), .conversation)
    }

    func testBareWakeWordArmsTheNextPhraseOnly() {
        var gate = WakeGate()
        XCTAssertEqual(gate.classify("Moto party.", nowMs: 1_000), .armed)
        XCTAssertTrue(gate.isArmed)
        XCTAssertEqual(gate.classify("Play Pink Floyd!", nowMs: 5_900), .command("play pink floyd"))
        XCTAssertFalse(gate.isArmed)
        XCTAssertEqual(gate.classify("next", nowMs: 6_000), .conversation)
    }

    func testArmingExpiresAfterFiveSeconds() {
        var gate = WakeGate()
        XCTAssertEqual(gate.classify("motoparty", nowMs: 0), .armed)
        XCTAssertEqual(gate.classify("next", nowMs: 5_001), .conversation)
        // Exactly at the window's end still counts.
        XCTAssertEqual(gate.classify("motoparty", nowMs: 10_000), .armed)
        XCTAssertEqual(gate.classify("next", nowMs: 15_000), .command("next"))
    }

    func testArmedPhraseMayRepeatTheWakeWord() {
        var gate = WakeGate()
        XCTAssertEqual(gate.classify("motoparty", nowMs: 0), .armed)
        XCTAssertEqual(gate.classify("motoparty", nowMs: 1_000), .armed)
        XCTAssertEqual(gate.classify("hey moto party skip", nowMs: 2_000), .command("skip"))
        gate.reset()
        XCTAssertFalse(gate.isArmed)
        XCTAssertEqual(gate.classify("motoparty", nowMs: 3_000), .armed)
        gate.reset()
        XCTAssertEqual(gate.classify("skip", nowMs: 3_100), .conversation)
    }
}
