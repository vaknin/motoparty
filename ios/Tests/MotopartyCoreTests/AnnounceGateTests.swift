import XCTest
@testable import MotopartyCore

/// `AnnounceGate`: an announcement is never started into the switch from the
/// talk session back to media (Android's `MediaCue`, F9b).
final class AnnounceGateTests: XCTestCase {
    private let a2dp = "BluetoothA2DPOutput"

    func testNothingHeldStartsAtOnce() {
        var gate = AnnounceGate()
        let release = gate.request(id: 1, atMs: 100)
        XCTAssertEqual(release?.id, 1)
        XCTAssertEqual(release?.spokeMs, 0)
        XCTAssertEqual(release?.line, "announce gate: released none, route n/a, spoke +0 ms (now)")
        XCTAssertFalse(gate.isHolding)
        XCTAssertNil(gate.nextDeadlineMs)
    }

    /// The bug: talk.close, then the reply during the end-earcon hold.
    func testReplyAfterAnHFPTalkWaitsForTheRouteAndTheSettle() {
        var gate = AnnounceGate()
        gate.talkSessionHeld(atMs: 0)
        // talk.close at 5000; the announce arrives 30 ms later, in the hold.
        XCTAssertNil(gate.request(id: 7, atMs: 5_030))
        XCTAssertNil(gate.nextDeadlineMs, "no fallback into a held talk session")
        // The hold ends (0.22 s): activate(.media).
        XCTAssertEqual(gate.mediaRestored(wasBluetoothHFP: true, atMs: 5_220), [])
        XCTAssertEqual(gate.nextDeadlineMs, 5_220 + AnnounceGate.timeoutMs)
        // The category change still reports HFP: not yet.
        XCTAssertEqual(gate.routeReported(outputIsBluetoothHFP: true, output: "BluetoothHFP", atMs: 5_260), [])
        // A2DP reported: settle.
        XCTAssertEqual(gate.routeReported(outputIsBluetoothHFP: false, output: a2dp, atMs: 6_100), [])
        XCTAssertEqual(gate.nextDeadlineMs, 6_100 + AnnounceGate.settleMs)
        XCTAssertEqual(gate.tick(atMs: 6_200), [])
        let released = gate.tick(atMs: 6_350)
        XCTAssertEqual(released.map(\.id), [7])
        XCTAssertEqual(released.first?.fallback, false)
        XCTAssertEqual(released.first?.line,
                       "announce gate: released +190 ms, route BluetoothA2DPOutput +1070 ms, spoke +1320 ms (route)")
        XCTAssertFalse(gate.isHolding)
        // The next one is instant again.
        XCTAssertNotNil(gate.request(id: 8, atMs: 7_000))
    }

    func testOffHFPTheReleaseIsEnough() {
        // Speaker, wired or USB: no profile switch.
        var gate = AnnounceGate()
        gate.talkSessionHeld(atMs: 0)
        XCTAssertNil(gate.request(id: 1, atMs: 1_000))
        XCTAssertNil(gate.request(id: 2, atMs: 1_010))
        let released = gate.mediaRestored(wasBluetoothHFP: false, atMs: 1_220)
        XCTAssertEqual(released.map(\.id), [1, 2])
        XCTAssertEqual(released.first?.line, "announce gate: released +220 ms, route n/a, spoke +220 ms (released)")
        XCTAssertFalse(gate.isHolding)
    }

    func testFallbackWhenNoRouteIsReported() {
        var gate = AnnounceGate()
        gate.talkSessionHeld(atMs: 0)
        XCTAssertNil(gate.request(id: 1, atMs: 1_000))
        _ = gate.mediaRestored(wasBluetoothHFP: true, atMs: 1_200)
        // Counted from the release, which came after the request.
        XCTAssertEqual(gate.tick(atMs: 3_000), [])
        let released = gate.tick(atMs: 3_200)
        XCTAssertEqual(released.map(\.id), [1])
        XCTAssertEqual(released.first?.fallback, true)
        XCTAssertEqual(released.first?.line, "announce gate: released +200 ms, route none, spoke +2200 ms (fallback)")
        // The wait is over: later ones do not wait as well.
        XCTAssertFalse(gate.isHolding)
        XCTAssertNotNil(gate.request(id: 2, atMs: 3_300))
    }

    func testFallbackReleasesTheRestToo() {
        var gate = AnnounceGate()
        gate.talkSessionHeld(atMs: 0)
        _ = gate.mediaRestored(wasBluetoothHFP: true, atMs: 100)
        XCTAssertNil(gate.request(id: 1, atMs: 200))
        XCTAssertNil(gate.request(id: 2, atMs: 1_500))
        let released = gate.tick(atMs: 2_200)
        XCTAssertEqual(released.map(\.id), [1, 2])
        XCTAssertEqual(released.map(\.fallback), [true, false])
    }

    func testRequestDuringTheDrainWaitsToo() {
        var gate = AnnounceGate()
        gate.talkSessionHeld(atMs: 0)
        _ = gate.mediaRestored(wasBluetoothHFP: true, atMs: 100)
        XCTAssertNil(gate.request(id: 1, atMs: 300))
        _ = gate.routeReported(outputIsBluetoothHFP: false, output: a2dp, atMs: 900)
        XCTAssertNil(gate.request(id: 2, atMs: 1_000), "still settling")
        let released = gate.tick(atMs: 900 + AnnounceGate.settleMs)
        XCTAssertEqual(released.map(\.id), [1, 2])
        XCTAssertEqual(released.last?.releasedMs, 0, "released before it was asked for")
    }

    func testATalkReopenedKeepsThemForTheNextRelease() {
        var gate = AnnounceGate()
        gate.talkSessionHeld(atMs: 0)
        XCTAssertNil(gate.request(id: 1, atMs: 100))
        _ = gate.mediaRestored(wasBluetoothHFP: true, atMs: 300)
        gate.talkSessionHeld(atMs: 500)
        XCTAssertNil(gate.nextDeadlineMs)
        XCTAssertEqual(gate.tick(atMs: 10_000), [], "never into the talk")
        XCTAssertEqual(gate.routeReported(outputIsBluetoothHFP: false, output: a2dp, atMs: 10_100), [],
                       "a route change during the talk is not the release")
        let released = gate.mediaRestored(wasBluetoothHFP: false, atMs: 12_000)
        XCTAssertEqual(released.map(\.id), [1])
    }

    func testReportsOutsideADrainAreIgnored() {
        var gate = AnnounceGate()
        XCTAssertEqual(gate.routeReported(outputIsBluetoothHFP: false, output: a2dp, atMs: 0), [])
        XCTAssertEqual(gate.mediaRestored(wasBluetoothHFP: true, atMs: 0), [], "no talk session was held")
        XCTAssertFalse(gate.isHolding)
    }

    func testZeroSettleReleasesOnTheReport() {
        var gate = AnnounceGate(settleMs: 0)
        gate.talkSessionHeld(atMs: 0)
        XCTAssertNil(gate.request(id: 1, atMs: 10))
        _ = gate.mediaRestored(wasBluetoothHFP: true, atMs: 20)
        XCTAssertEqual(gate.routeReported(outputIsBluetoothHFP: false, output: "Speaker", atMs: 700).map(\.id), [1])
    }

    func testResetDropsWhatWaits() {
        var gate = AnnounceGate()
        gate.talkSessionHeld(atMs: 0)
        XCTAssertNil(gate.request(id: 1, atMs: 10))
        XCTAssertNil(gate.request(id: 2, atMs: 20))
        XCTAssertEqual(gate.reset(), [1, 2])
        XCTAssertFalse(gate.isHolding)
        XCTAssertEqual(gate.pendingCount, 0)
        XCTAssertNotNil(gate.request(id: 3, atMs: 30))
    }

    func testTimingsAreTheNamedConstants() {
        XCTAssertEqual(AnnounceGate.timeoutMs, 2_000, "Android's MediaCue.TIMEOUT_MS")
        XCTAssertEqual(AnnounceGate.settleMs, 250)
        // The settle fits the host's 1.5 s resume lead with room for the
        // ~1 s profile switch.
        XCTAssertLessThan(AnnounceGate.settleMs, 500)
    }
}
