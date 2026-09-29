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
