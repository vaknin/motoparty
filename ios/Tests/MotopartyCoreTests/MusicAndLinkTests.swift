import Foundation
import XCTest
@testable import MotopartyCore

final class MusicAnchorTests: XCTestCase {
    func testExpectedPosition() {
        let a = MusicAnchor(positionMs: 61_234, atHostTimeMs: 1_000_000)
        XCTAssertEqual(a.expectedPositionMs(hostNowMs: 1_000_000), 61_234)
        XCTAssertEqual(a.expectedPositionMs(hostNowMs: 1_010_000), 71_234)
        XCTAssertEqual(a.expectedPositionMs(hostNowMs: 999_700), 60_934)
        XCTAssertEqual(a.targetPlayerPositionMs(hostNowMs: 1_010_000, trimMs: 150), 71_384)
    }

    func testStartPlanFutureAnchorStartsExactlyAtIt() {
        let a = MusicAnchor(positionMs: 0, atHostTimeMs: 1_300)
        XCTAssertEqual(a.startPlan(hostNowMs: 1_000, trimMs: 0), StartPlan(positionMs: 0, atHostTimeMs: 1_300))
        XCTAssertEqual(a.startPlan(hostNowMs: 1_000, trimMs: 120), StartPlan(positionMs: 120, atHostTimeMs: 1_300))
    }

    func testStartPlanLateJoinComputesPositionFromAnchor() {
        let a = MusicAnchor(positionMs: 1_000, atHostTimeMs: 500)
        let plan = a.startPlan(hostNowMs: 2_000, trimMs: 0, minLeadMs: 150)
        XCTAssertEqual(plan, StartPlan(positionMs: 2_650, atHostTimeMs: 2_150))
    }

    func testStartPlanPastEndIsNil() {
        let a = MusicAnchor(positionMs: 212_000, atHostTimeMs: 0)
        XCTAssertNil(a.startPlan(hostNowMs: 5_000, trimMs: 0, durationMs: 213_000))
    }

    func testDriftMeasuredAgainstAnchor() {
        let a = MusicAnchor(positionMs: 10_000, atHostTimeMs: 0)
        var c = DriftController()
        XCTAssertEqual(c.decide(anchor: a, playerPositionMs: 20_050, hostNowMs: 10_000, trimMs: 0),
                       DriftDecision(errorMs: 50, action: .hold, nextCheckMs: DriftCheck.intervalMs))
        XCTAssertEqual(c.decide(anchor: a, playerPositionMs: 20_200, hostNowMs: 10_000, trimMs: 0),
                       DriftDecision(errorMs: 200, action: .rate(0.98), nextCheckMs: DriftCheck.correctingIntervalMs))
        // Trim moves the target: 100 ms ahead is exactly right with a 100 ms trim.
        c.reset()
        XCTAssertEqual(c.decide(anchor: a, playerPositionMs: 20_100, hostNowMs: 10_000, trimMs: 100),
                       DriftDecision(errorMs: 0, action: .hold, nextCheckMs: DriftCheck.intervalMs))
    }

    func testSameTimeline() {
        let a = MusicAnchor(positionMs: 1_000, atHostTimeMs: 50_000)
        XCTAssertTrue(a.isSameTimeline(as: MusicAnchor(positionMs: 11_000, atHostTimeMs: 60_000)))
        XCTAssertTrue(a.isSameTimeline(as: MusicAnchor(positionMs: 11_015, atHostTimeMs: 60_000)))
        XCTAssertFalse(a.isSameTimeline(as: MusicAnchor(positionMs: 11_100, atHostTimeMs: 60_000)))
        XCTAssertFalse(a.isSameTimeline(as: MusicAnchor(positionMs: 0, atHostTimeMs: 60_000)))
    }

    func testStateMusicAnchor() {
        let m = HostState.Music(id: "x", title: "t", artist: "a", playing: true,
                                positionMs: 5, atHostTimeMs: 9, durationMs: 100)
        XCTAssertEqual(m.anchor, MusicAnchor(positionMs: 5, atHostTimeMs: 9))
        XCTAssertEqual(MusicAnchor(MusicPlay(id: "x", positionMs: 5, atHostTimeMs: 9)), m.anchor)
    }
}

/// PROTOCOL.md "Music flow" step 4: in sync up to 80 ms, rate nudge of up to
/// ±2 % from there to 1 s, re-seek above 1 s.
final class DriftControllerTests: XCTestCase {
    func testInSyncBandBoundaries() {
        var c = DriftController()
        for error in [0.0, 79, 80, -79, -80] {
            let d = c.decide(errorMs: error)
            XCTAssertEqual(d, DriftDecision(errorMs: error, action: .hold, nextCheckMs: DriftCheck.intervalMs),
                           "error \(error)")
            XCTAssertFalse(c.isCorrecting)
        }
    }

    func testJustOutsideTheBandNudgesTheRate() {
        var c = DriftController()
        // Player ahead → slow down; 81 ms quantises to 0.9925 (1 - 81/10000 = 0.9919).
        XCTAssertEqual(c.decide(errorMs: 81),
                       DriftDecision(errorMs: 81, action: .rate(0.9925), nextCheckMs: DriftCheck.correctingIntervalMs))
        XCTAssertEqual(c.rate, 0.9925)

        // Player behind → speed up, same size.
        var back = DriftController()
        XCTAssertEqual(back.decide(errorMs: -81),
                       DriftDecision(errorMs: -81, action: .rate(1.0075), nextCheckMs: DriftCheck.correctingIntervalMs))
        XCTAssertEqual(back.rate, 1.0075)
    }

    func testRateIsProportionalAndClampedToTwoPercent() {
        XCTAssertEqual(DriftCheck.rate(forErrorMs: 100), 0.99)
        XCTAssertEqual(DriftCheck.rate(forErrorMs: -100), 1.01)
        XCTAssertEqual(DriftCheck.rate(forErrorMs: 200), 0.98)
        XCTAssertEqual(DriftCheck.rate(forErrorMs: 999), 0.98)
        XCTAssertEqual(DriftCheck.rate(forErrorMs: -999), 1.02)
        // Way out of range still clamps (the caller re-seeks there instead).
        XCTAssertEqual(DriftCheck.rate(forErrorMs: 60_000), DriftCheck.minRate)
        XCTAssertEqual(DriftCheck.rate(forErrorMs: -60_000), DriftCheck.maxRate)
    }

    func testSeekBoundary() {
        var c = DriftController()
        XCTAssertEqual(c.decide(errorMs: 999),
                       DriftDecision(errorMs: 999, action: .rate(0.98), nextCheckMs: DriftCheck.correctingIntervalMs))
        XCTAssertEqual(c.decide(errorMs: 1_000),
                       DriftDecision(errorMs: 1_000, action: .hold, nextCheckMs: DriftCheck.correctingIntervalMs))
        XCTAssertEqual(c.rate, 0.98)
        // Above 1 s: re-seek, and the correction is dropped (the seek restarts
        // playback at rate 1.0).
        XCTAssertEqual(c.decide(errorMs: 1_001),
                       DriftDecision(errorMs: 1_001, action: .reseek, nextCheckMs: DriftCheck.correctingIntervalMs))
        XCTAssertEqual(c.rate, 1)
        XCTAssertFalse(c.isCorrecting)

        var back = DriftController()
        XCTAssertEqual(back.decide(errorMs: -1_000).action, .rate(1.02))
        XCTAssertEqual(back.decide(errorMs: -1_001).action, .reseek)
        XCTAssertEqual(back.rate, 1)
    }

    func testCorrectionHoldsPastEightyAndReleasesAtOne() {
        var c = DriftController()
        // 300 ms asks for more than 2 %, so it clamps to the floor.
        XCTAssertEqual(c.decide(errorMs: 300).action, .rate(0.98))
        // Still correcting: no new rate while the quantised rate is unchanged.
        XCTAssertEqual(c.decide(errorMs: 299).action, .hold)
        // 160 ms: 1 - 160/10000 = 0.984, quantised up to the nearest 0.25 % step.
        XCTAssertEqual(c.decide(errorMs: 160).action, .rate(0.985))
        // Back inside 80 ms but not yet settled: hysteresis keeps the nudge on.
        XCTAssertEqual(c.decide(errorMs: 79),
                       DriftDecision(errorMs: 79, action: .hold, nextCheckMs: DriftCheck.correctingIntervalMs))
        XCTAssertEqual(c.decide(errorMs: 41).action, .hold)
        XCTAssertTrue(c.isCorrecting)
        // Settled: back to 1.0 and to the slow check interval.
        XCTAssertEqual(c.decide(errorMs: 40),
                       DriftDecision(errorMs: 40, action: .rate(1), nextCheckMs: DriftCheck.intervalMs))
        XCTAssertFalse(c.isCorrecting)
        // And it stays there: no flapping at the 80 ms boundary.
        XCTAssertEqual(c.decide(errorMs: 80).action, .hold)
        XCTAssertEqual(c.decide(errorMs: -80).action, .hold)
        XCTAssertEqual(c.rate, 1)
    }

    func testCorrectionSwitchesSignWithoutSeeking() {
        var c = DriftController()
        XCTAssertEqual(c.decide(errorMs: 400).action, .rate(0.98))
        XCTAssertEqual(c.decide(errorMs: -120).action, .rate(1.0125))
        XCTAssertEqual(c.decide(errorMs: -20).action, .rate(1))
    }

    func testResetDropsTheCorrection() {
        var c = DriftController()
        XCTAssertEqual(c.decide(errorMs: -200).action, .rate(1.02))
        c.reset()
        XCTAssertEqual(c.rate, 1)
        XCTAssertFalse(c.isCorrecting)
        // After a reset a small error needs no rate change at all.
        XCTAssertEqual(c.decide(errorMs: 50).action, .hold)
    }

    func testEveryDecisionStaysWithinTwoPercent() {
        var c = DriftController()
        for error in stride(from: -1_200.0, through: 1_200.0, by: 7) {
            let d = c.decide(errorMs: error)
            if case .rate(let r) = d.action {
                XCTAssertGreaterThanOrEqual(r, DriftCheck.minRate)
                XCTAssertLessThanOrEqual(r, DriftCheck.maxRate)
            }
            XCTAssertGreaterThanOrEqual(c.rate, DriftCheck.minRate)
            XCTAssertLessThanOrEqual(c.rate, DriftCheck.maxRate)
        }
    }
}

final class LinkMathTests: XCTestCase {
    func testSweepCandidates() {
        let c = SubnetSweep.candidates(ownIPv4: "10.42.7.17")
        XCTAssertEqual(c.count, 253)
        XCTAssertEqual(c.first, "10.42.7.1")
        XCTAssertFalse(c.contains("10.42.7.17"))
        XCTAssertFalse(c.contains("10.42.7.0"))
        XCTAssertFalse(c.contains("10.42.7.255"))
        XCTAssertEqual(Set(c).count, 253)

        let gw = SubnetSweep.candidates(ownIPv4: "192.168.1.1")
        XCTAssertEqual(gw.count, 253)
        XCTAssertEqual(gw.first, "192.168.1.2")

        XCTAssertEqual(SubnetSweep.candidates(ownIPv4: "fe80::1"), [])
        XCTAssertEqual(SubnetSweep.candidates(ownIPv4: "300.1.1.1"), [])
    }

    func testLiveness() {
        var l = Liveness(nowMs: 0)
        XCTAssertFalse(l.isLost(nowMs: 5_999))
        XCTAssertTrue(l.isLost(nowMs: 6_000))
        l.received(nowMs: 5_000)
        XCTAssertFalse(l.isLost(nowMs: 10_999))
    }

    func testServiceTXT() {
        XCTAssertEqual(ServiceTXT(["proto": "1", "voice": "5000", "http": "5001"]).voicePort, 5000)
        let defaults = ServiceTXT([:])
        XCTAssertNil(defaults.proto)
        XCTAssertEqual(defaults.voicePort, 47801)
        XCTAssertEqual(defaults.httpPort, 47802)
    }

    func testEarconWav() {
        let wav = EarconSynth.wav(tones: [(440, 0.1)])
        XCTAssertEqual(String(decoding: wav.prefix(4), as: UTF8.self), "RIFF")
        XCTAssertEqual(wav.count, 44 + 2 * 2_205)
        for name in ["live", "ok", "error", "end"] { XCTAssertGreaterThan(EarconSynth.wav(for: name).count, 44) }
    }
}
