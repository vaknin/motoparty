import Foundation
import XCTest
@testable import MotopartyCore

final class CommandFixtureTests: XCTestCase {
    /// `{"action": …}` plus `kind`/`query` for play, the shape of
    /// `fixtures/commands.json`.
    private func dictionary(_ command: Command) -> [String: String] {
        switch command {
        case .play(let kind, let query): ["action": "play", "kind": kind.rawValue, "query": query]
        case .queue(let place, let count, let kind, let query):
            ["action": "queue", "where": place.rawValue, "kind": kind?.rawValue ?? CommandParser.similar]
                .merging(kind == nil ? [:] : ["query": query]) { $1 }
                .merging(count.map { ["count": String($0)] } ?? [:]) { $1 }
        default: ["action": command.action]
        }
    }

    func testEveryFixtureCase() throws {
        let fixture = try Fixtures.json("commands.json")
        let cases = try XCTUnwrap(fixture["cases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 65)
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
        XCTAssertEqual(fixture["answerMs"] as? Double, FirstPhraseGate.answerMs)
        XCTAssertEqual(cases.count, 24)
        for c in cases {
            let name = try XCTUnwrap(c["name"] as? String)
            let role = try XCTUnwrap((c["role"] as? String).flatMap(FirstPhraseGate.Role.init(rawValue:)), name)
            let phrases = try XCTUnwrap(c["phrases"] as? [[String: Any]])
            let expect = try XCTUnwrap(c["expect"] as? [Any])
            XCTAssertEqual(phrases.count, expect.count, name)
            // Any live-earcon time: only the difference counts.
            let liveAtMs = 123_456.0
            var gate = FirstPhraseGate(role: role, liveAtMs: liveAtMs, interpret: c["interpret"] as? Bool ?? false)
            // The host's question arrives before any phrase at or after askAtMs;
            // the opener's next phrase is then the reply.
            var askAtMs = c["askAtMs"] as? Double
            var replying = false
            for (phrase, want) in zip(phrases, expect) {
                let text = try XCTUnwrap(phrase["text"] as? String)
                let atMs = try XCTUnwrap(phrase["atMs"] as? Double)
                if let at = askAtMs, at <= atMs {
                    gate.ask(atMs: liveAtMs + at)
                    askAtMs = nil
                    replying = role == .opener
                }
                // JSON null arrives as NSNull: conversation.
                let expected: HeardPhrase = (want as? String).map { replying ? .reply($0) : .command($0) } ?? .conversation
                XCTAssertEqual(gate.classify(text, nowMs: liveAtMs + atMs), expected, "\(name): \(text.debugDescription)")
                // An empty phrase spends nothing: the reply is still to come.
                if expected != .conversation || !CommandParser.normalize(text).isEmpty { replying = false }
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

    func testAQuestionOpensTheGateForOneReply() {
        var gate = FirstPhraseGate(role: .opener, liveAtMs: 0, interpret: true)
        XCTAssertEqual(gate.classify("play an album by Moby", nowMs: 2_000), .command("play an album by moby"))
        XCTAssertTrue(gate.isSpent(atMs: 2_000))
        gate.ask(atMs: 3_000)
        XCTAssertFalse(gate.isSpent(atMs: 13_000))
        XCTAssertTrue(gate.isSpent(atMs: 13_001))
        XCTAssertEqual(gate.classify("…", nowMs: 4_000), .conversation)
        XCTAssertEqual(gate.classify("Louder!", nowMs: 6_000), .reply("louder"))
        XCTAssertTrue(gate.isSpent(atMs: 6_000))
        XCTAssertEqual(gate.classify("next", nowMs: 7_000), .conversation)
    }

    func testCommandTextFeedsTheParser() {
        var gate = FirstPhraseGate(role: .opener, liveAtMs: 0)
        XCTAssertEqual(gate.classify("Hey, turn it LOUDER please!", nowMs: 500), .conversation)
        gate = FirstPhraseGate(role: .opener, liveAtMs: 0)
        let heard = gate.classify("Hey, louder please!", nowMs: 500)
        XCTAssertEqual(heard, .command("hey louder please"))
        if case .command(let text) = heard { XCTAssertEqual(CommandParser.parse(text), .volumeUp) }
    }

    /// Volume never reaches the host, so the client closes that talk itself;
    /// everything else is sent as heard and the host closes it.
    /// With a host that interprets, an unparsed first phrase is sent as it is
    /// and volume still stays on this phone.
    func testClientCommandRouteOfCandidate() {
        var gate = FirstPhraseGate(role: .opener, liveAtMs: 0, interpret: true)
        XCTAssertEqual(gate.classify("Put on something by Moby", nowMs: 1_000), .command("put on something by moby"))
        XCTAssertEqual(ClientCommand.route("put on something by moby", volumeArmed: false), .send("put on something by moby"))
        XCTAssertEqual(ClientCommand.route("louder", volumeArmed: true), .volume(up: true, .inTalk))
    }

    func testClientCommandRoute() {
        XCTAssertEqual(ClientCommand.route("hey louder please", volumeArmed: true), .volume(up: true, .inTalk))
        XCTAssertEqual(ClientCommand.route("volume down", volumeArmed: true), .volume(up: false, .inTalk))
        // Disarmed, the system volume in a talk is the call volume: the media
        // volume can only be stepped after the close.
        XCTAssertEqual(ClientCommand.route("volume up", volumeArmed: false), .volume(up: true, .afterMediaRoute))
        XCTAssertEqual(ClientCommand.route("quieter", volumeArmed: false), .volume(up: false, .afterMediaRoute))
        for text in ["play daft punk", "pause", "resume", "next", "previous", "over", "what's playing", "shuffle"] {
            for armed in [true, false] {
                XCTAssertEqual(ClientCommand.route(text, volumeArmed: armed), .send(text), text)
            }
        }
    }
}
