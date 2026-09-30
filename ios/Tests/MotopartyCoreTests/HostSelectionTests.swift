import Foundation
import XCTest
@testable import MotopartyCore

final class HostSelectionTests: XCTestCase {
    typealias Sel = HostSelection<String>

    private func accepted(_ d: Sel.Decision) -> String? {
        if case .accept(let c) = d { return c }
        return nil
    }

    func testProbeOnceWhileInFlight() {
        var s = Sel()
        XCTAssertTrue(s.beginProbe("peer-test-1", nowMs: 0))
        XCTAssertFalse(s.beginProbe("peer-test-1", nowMs: 100))
        XCTAssertTrue(s.beginProbe("Pixel 8", nowMs: 100))
    }

    func testFailedCandidateBacksOffTenSeconds() {
        var s = Sel()
        XCTAssertTrue(s.beginProbe("stale", nowMs: 0))
        s.probeFailed("stale", nowMs: 3_000)
        XCTAssertTrue(s.isInBackoff("stale", nowMs: 12_999))
        XCTAssertFalse(s.beginProbe("stale", nowMs: 12_999))
        XCTAssertTrue(s.beginProbe("stale", nowMs: 13_000))
    }

    func testCancelledProbeIsNotBackedOff() {
        var s = Sel()
        XCTAssertTrue(s.beginProbe("a", nowMs: 0))
        s.probeCancelled("a")
        XCTAssertTrue(s.beginProbe("a", nowMs: 1))
    }

    func testFirstAnswerWinsWithoutPreference() {
        var s = Sel()
        _ = s.beginProbe("a", nowMs: 0)
        _ = s.beginProbe("b", nowMs: 0)
        XCTAssertEqual(accepted(s.probeSucceeded("a", name: "Pixel 8", candidate: "A", nowMs: 50)), "A")
        XCTAssertTrue(s.decided)
        guard case .none = s.probeSucceeded("b", name: "Other", candidate: "B", nowMs: 60) else {
            return XCTFail("decided selection must ignore later answers")
        }
        XCTAssertFalse(s.beginProbe("c", nowMs: 70))
    }

    func testPreferredAnswerWinsImmediately() {
        var s = Sel(preferredName: "Pixel 8")
        _ = s.beginProbe("x", nowMs: 0)
        XCTAssertEqual(accepted(s.probeSucceeded("x", name: "Pixel 8", candidate: "X", nowMs: 10)), "X")
    }

    func testPreferredAnswerWithinWindowBeatsEarlierOne() {
        var s = Sel(preferredName: "Pixel 8")
        guard case .wait(let deadline) = s.probeSucceeded("a", name: "Other", candidate: "A", nowMs: 100) else {
            return XCTFail("non-preferred first answer should wait")
        }
        XCTAssertEqual(deadline, 400)
        XCTAssertNil(s.windowExpired(nowMs: 399))
        XCTAssertEqual(accepted(s.probeSucceeded("b", name: "Pixel 8", candidate: "B", nowMs: 250)), "B")
        XCTAssertNil(s.windowExpired(nowMs: 400))
    }

    func testFirstAnswerWinsAfterWindow() {
        var s = Sel(preferredName: "Pixel 8")
        _ = s.probeSucceeded("a", name: "Other", candidate: "A", nowMs: 100)
        guard case .none = s.probeSucceeded("b", name: "Third", candidate: "B", nowMs: 200) else {
            return XCTFail("second non-preferred answer changes nothing")
        }
        XCTAssertEqual(s.windowExpired(nowMs: 400), "A")
        XCTAssertTrue(s.decided)
    }

    func testRestartKeepsBackoffClearsDecision() {
        var s = Sel()
        _ = s.beginProbe("stale", nowMs: 0)
        s.probeFailed("stale", nowMs: 0)
        _ = s.beginProbe("host", nowMs: 0)
        _ = s.probeSucceeded("host", name: "Pixel 8", candidate: "H", nowMs: 5)
        s.restart(preferredName: "Pixel 8")
        XCTAssertFalse(s.decided)
        XCTAssertFalse(s.beginProbe("stale", nowMs: 1_000))
        XCTAssertTrue(s.beginProbe("host", nowMs: 1_000))
    }

    func testRefusedCandidateBacksOffOneSecond() {
        var s = Sel()
        XCTAssertTrue(s.beginProbe("bonjour:Pixel 8", nowMs: 0))
        s.probeFailed("bonjour:Pixel 8", nowMs: 200, refused: true)
        XCTAssertTrue(s.isInBackoff("bonjour:Pixel 8", nowMs: 1_199))
        XCTAssertFalse(s.beginProbe("bonjour:Pixel 8", nowMs: 1_199))
        XCTAssertFalse(s.isInBackoff("bonjour:Pixel 8", nowMs: 1_200))
        XCTAssertTrue(s.beginProbe("bonjour:Pixel 8", nowMs: 1_200))
        // A timeout after it is the long backoff again.
        s.probeFailed("bonjour:Pixel 8", nowMs: 1_300)
        XCTAssertFalse(s.beginProbe("bonjour:Pixel 8", nowMs: 11_299))
        XCTAssertTrue(s.beginProbe("bonjour:Pixel 8", nowMs: 11_300))
    }

    func testLastAddressProbeIsExemptFromBackoff() {
        var s = Sel(preferredName: "Pixel 8")
        XCTAssertTrue(s.beginLastAddressProbe("last:10.0.0.1"))
        // One at a time.
        XCTAssertFalse(s.beginLastAddressProbe("last:10.0.0.1"))
        s.probeFailed("last:10.0.0.1", nowMs: 400)
        XCTAssertTrue(s.beginLastAddressProbe("last:10.0.0.1"))
        s.probeFailed("last:10.0.0.1", nowMs: 1_400, refused: true)
        XCTAssertTrue(s.beginLastAddressProbe("last:10.0.0.1"))
        // Its failures don't touch the sweep's candidate for the same address.
        XCTAssertTrue(s.beginProbe("ip:10.0.0.1", nowMs: 1_500))
        // The restarted host answers: it wins like any other candidate.
        XCTAssertEqual(accepted(s.probeSucceeded("last:10.0.0.1", name: "Pixel 8", candidate: "L", nowMs: 2_400)), "L")
        XCTAssertFalse(s.beginLastAddressProbe("last:10.0.0.1"), "decided")
    }

    func testRejoinAfterHostRestartTakesAboutASecond() {
        // The host app restarts: refused until it listens again at 2.5 s.
        var s = Sel(preferredName: "Pixel 8")
        var now = 0.0
        var found: Double?
        while found == nil, now < 20_000 {
            if s.beginLastAddressProbe("last") {
                if now >= 2_500 {
                    _ = s.probeSucceeded("last", name: "Pixel 8", candidate: "L", nowMs: now)
                    found = now
                } else {
                    s.probeFailed("last", nowMs: now, refused: true)
                }
            }
            now += Sel.lastAddressProbeMs
        }
        XCTAssertEqual(found, 3_000)
    }

    func testJudgeFirstFrame() throws {
        let host = Hello(role: .host, name: "Pixel 8", voicePort: 47801, httpPort: 47802)
        XCTAssertEqual(HostProbe.judge(firstFrame: try ControlCodec.encode(.hello(host))), .host(host))
        let old = Hello(proto: Hello.currentProto + 1, role: .host, name: "Pixel 8")
        XCTAssertEqual(HostProbe.judge(firstFrame: try ControlCodec.encode(.hello(old))), .wrongProto(Hello.currentProto + 1))
        let client = Hello(role: .client, name: "iPhone")
        XCTAssertEqual(HostProbe.judge(firstFrame: try ControlCodec.encode(.hello(client))), .notHost)
        XCTAssertEqual(HostProbe.judge(firstFrame: try ControlCodec.encode(.ping(Ping(id: 1, t0: 0)))), .notHost)
        XCTAssertEqual(HostProbe.judge(firstFrame: Data("junk".utf8)), .notHost)
    }
}

/// PROTOCOL.md "Control channel": a host with another protocol version.
final class ProtoMismatchTests: XCTestCase {
    func testByeProtoAndAnotherHelloProtoAreMismatches() {
        XCTAssertTrue(ProtoMismatch.isMismatch(.bye(Bye(reason: "proto"))))
        XCTAssertFalse(ProtoMismatch.isMismatch(.bye(Bye(reason: "user"))))
        XCTAssertFalse(ProtoMismatch.isMismatch(.bye(Bye())))
        var hello = Hello(role: .host, name: "Pixel 8")
        XCTAssertFalse(ProtoMismatch.isMismatch(.hello(hello)))
        hello.proto = Hello.currentProto + 1
        XCTAssertTrue(ProtoMismatch.isMismatch(.hello(hello)))
        XCTAssertFalse(ProtoMismatch.isMismatch(.musicStop))
    }

    func testWrongProtoCandidateIsNotProbedAgainUntilTheUserAsks() {
        var s = HostSelection<String>()
        XCTAssertTrue(s.beginProbe("bonjour:Old", nowMs: 0))
        s.probeWrongProto("bonjour:Old")
        // Not after the backoff, not across a restart, not as the last address.
        XCTAssertFalse(s.beginProbe("bonjour:Old", nowMs: 60_000))
        s.restart(preferredName: nil)
        XCTAssertFalse(s.beginProbe("bonjour:Old", nowMs: 120_000))
        XCTAssertTrue(s.isInBackoff("bonjour:Old", nowMs: 120_000))
        XCTAssertTrue(s.hasBlockedHosts)
        // Other hosts are unaffected.
        XCTAssertTrue(s.beginProbe("bonjour:Pixel 8", nowMs: 120_000))
        if case .accept(let c) = s.probeSucceeded("bonjour:Pixel 8", name: "Pixel 8", candidate: "pixel", nowMs: 120_100) {
            XCTAssertEqual(c, "pixel")
        } else { XCTFail("the other host must win") }
        // The user asks: probed again.
        s.restart(preferredName: nil)
        s.unblock()
        XCTAssertFalse(s.hasBlockedHosts)
        XCTAssertTrue(s.beginProbe("bonjour:Old", nowMs: 130_000))
    }

    func testHostThatSaidByeProtoIsNotAcceptedAgainUnderAnyKey() {
        var s = HostSelection<String>(preferredName: "Pixel 8")
        s.block(name: "Pixel 8", keys: ["bonjour:Pixel 8", "ip:10.0.0.1", "last:10.0.0.1"])
        XCTAssertFalse(s.beginProbe("bonjour:Pixel 8", nowMs: 0))
        XCTAssertFalse(s.beginProbe("ip:10.0.0.1", nowMs: 0))
        XCTAssertFalse(s.beginLastAddressProbe("last:10.0.0.1"))
        // Reached under a key nobody knew (another address): still not accepted,
        // and that key is not probed again either.
        XCTAssertTrue(s.beginProbe("ip:10.0.0.7", nowMs: 0))
        if case .none = s.probeSucceeded("ip:10.0.0.7", name: "Pixel 8", candidate: "x", nowMs: 100) {} else { XCTFail() }
        XCTAssertFalse(s.decided)
        XCTAssertFalse(s.beginProbe("ip:10.0.0.7", nowMs: 60_000))
        // Another host still wins (after the preference window).
        XCTAssertTrue(s.beginProbe("ip:10.0.0.9", nowMs: 200))
        if case .wait = s.probeSucceeded("ip:10.0.0.9", name: "Other", candidate: "other", nowMs: 300) {} else { XCTFail() }
        XCTAssertEqual(s.windowExpired(nowMs: 600), "other")
        s.restart(preferredName: "Pixel 8")
        s.unblock()
        XCTAssertTrue(s.beginProbe("bonjour:Pixel 8", nowMs: 1_000))
        if case .accept = s.probeSucceeded("bonjour:Pixel 8", name: "Pixel 8", candidate: "pixel", nowMs: 1_100) {} else { XCTFail() }
    }
}
