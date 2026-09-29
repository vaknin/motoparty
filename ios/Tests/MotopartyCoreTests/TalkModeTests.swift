import Foundation
import XCTest
@testable import MotopartyCore

/// PROTOCOL.md "Host-mic talk": the `mic` field on the wire and the mode the
/// client derives from it.
final class TalkModeTests: XCTestCase {
    private func decode(_ json: String) throws -> ControlMessage {
        try ControlCodec.decode(Data(json.utf8))
    }

    func testMicHostDecodes() throws {
        XCTAssertEqual(try decode(#"{"t":"talk.open","by":"client","mic":"host"}"#),
                       .talkOpen(TalkOpen(by: .client, mic: .host)))
        XCTAssertEqual(try decode(#"{"t":"talk.open","by":"host","mic":"host"}"#),
                       .talkOpen(TalkOpen(by: .host, mic: .host)))
    }

    func testMicIsOptionalAndOmittedWhenNil() throws {
        XCTAssertEqual(try decode(#"{"t":"talk.open","by":"host"}"#), .talkOpen(TalkOpen(by: .host)))
        let request = String(decoding: try ControlCodec.encode(.talkOpen(TalkOpen(by: .client))), as: UTF8.self)
        XCTAssertFalse(request.contains("mic"), request)
        XCTAssertFalse(request.contains("null"), request)
    }

    func testMicOutsideItsSetDropsTheMessage() {
        for bad in [#""both""#, #""client""#, #""""#, "1", "true"] {
            let json = #"{"t":"talk.open","by":"host","mic":"# + bad + "}"
            XCTAssertThrowsError(try decode(json), json) { error in
                XCTAssertEqual((error as? ControlCodecError)?.closesConnection, false, json)
            }
        }
    }

    func testModeFromOpen() {
        XCTAssertEqual(TalkMode(open: TalkOpen(by: .host, mic: .host)), .hostMic)
        XCTAssertEqual(TalkMode(open: TalkOpen(by: .client, mic: .host)), .hostMic)
        XCTAssertEqual(TalkMode(open: TalkOpen(by: .host)), .ownMic)
        XCTAssertEqual(TalkMode(open: TalkOpen(by: .client)), .ownMic)
        // A talk learnt only from state: no mic to go on, so our own.
        XCTAssertEqual(TalkMode(open: nil), .ownMic)
    }

    func testStateCarriesMic() throws {
        let json = #"{"t":"state","talk":true,"queue":[],"mic":"host"}"#
        XCTAssertEqual(try decode(json), .state(HostState(talk: true, mic: .host)))
        XCTAssertEqual(try decode(#"{"t":"state","talk":true,"queue":[]}"#), .state(HostState(talk: true)))
        let plain = String(decoding: try ControlCodec.encode(.state(HostState(talk: false))), as: UTF8.self)
        XCTAssertFalse(plain.contains("mic"), plain)
        XCTAssertThrowsError(try decode(#"{"t":"state","talk":true,"queue":[],"mic":"both"}"#)) { error in
            XCTAssertEqual((error as? ControlCodecError)?.closesConnection, false)
        }
    }

    func testModeFromState() {
        // A join mid-talk opens a host-mic talk exactly as talk.open{mic:"host"} would.
        XCTAssertEqual(TalkMode(state: HostState(talk: true, mic: .host)), .hostMic)
        XCTAssertEqual(TalkMode(state: HostState(talk: true)), .ownMic)
        // mic means nothing without an open talk.
        XCTAssertEqual(TalkMode(state: HostState(talk: false, mic: .host)), .ownMic)
    }

    func testHostMicOpensNothingAndSendsNothing() {
        XCTAssertFalse(TalkMode.hostMic.needsMicrophone)
        XCTAssertFalse(TalkMode.hostMic.sendsAudio)
        XCTAssertFalse(TalkMode.hostMic.usesCallMode)
        XCTAssertTrue(TalkMode.ownMic.needsMicrophone)
        XCTAssertTrue(TalkMode.ownMic.sendsAudio)
        XCTAssertTrue(TalkMode.ownMic.usesCallMode)
    }

    func testOnlyAnOwnMicTalkWeOpenedRecognises() {
        XCTAssertTrue(TalkMode.ownMic.recognisesCommands(opener: .client))
        XCTAssertFalse(TalkMode.ownMic.recognisesCommands(opener: .host))
        XCTAssertFalse(TalkMode.ownMic.recognisesCommands(opener: nil))
        // In a host-mic talk the host recognises the passenger's first phrase.
        XCTAssertFalse(TalkMode.hostMic.recognisesCommands(opener: .client))
        XCTAssertFalse(TalkMode.hostMic.recognisesCommands(opener: .host))
        XCTAssertFalse(TalkMode.hostMic.recognisesCommands(opener: nil))
    }

    func testLogLabels() {
        XCTAssertEqual(TalkMode.hostMic.logLabel, "host-mic (receive only)")
        XCTAssertEqual(TalkMode.ownMic.logLabel, "own mic")
        XCTAssertEqual(TalkMode.hostMic.liveSignal, "playback up")
        XCTAssertEqual(TalkMode.ownMic.liveSignal, "capture up")
    }
}
