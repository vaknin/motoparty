import Foundation
import XCTest
@testable import MotopartyCore

/// Event-driven simulation: frame i has seq i, ts i*320, payload [i] unless
/// overridden. The receiver pulls every 20 ms at t = k*20 + 10; arrivals are
/// delivered in time order between pulls.
final class JitterBufferTests: XCTestCase {
    struct Arrival {
        var index: Int
        var at: Double
        var seq: Int?
        var ts: Int?
        var keepalive = false
    }

    private func payload(_ i: Int) -> Data { Data([UInt8(truncatingIfNeeded: i)]) }

    private func a(_ i: Int, _ t: Double, seq: Int? = nil, ts: Int? = nil) -> Arrival {
        Arrival(index: i, at: t, seq: seq, ts: ts)
    }

    /// Frames 0..<n sent every 20 ms from t0, no network delay.
    private func steady(_ range: Range<Int>, from t0: Double = 0) -> [Arrival] {
        range.map { a($0, t0 + Double(($0 - range.lowerBound) * 20)) }
    }

    @discardableResult
    private func run(_ jb: inout JitterBuffer, _ arrivals: [Arrival], pullFrom: Double = 10,
                     to end: Double) -> [PlayoutAction] {
        var pending = arrivals.sorted { $0.at < $1.at }
        var actions: [PlayoutAction] = []
        var t = pullFrom
        while t < end {
            while let next = pending.first, next.at <= t {
                pending.removeFirst()
                let seq = UInt16(truncatingIfNeeded: next.seq ?? next.index)
                let ts = UInt32(truncatingIfNeeded: next.ts ?? next.index * 320)
                if next.keepalive {
                    jb.insert(VoicePacket(kind: .keepalive, seq: seq, ts: ts), nowMs: next.at)
                } else {
                    jb.insert(seq: seq, ts: ts, payload: payload(next.index), nowMs: next.at)
                }
            }
            actions.append(jb.pull(nowMs: t))
            t += 20
        }
        return actions
    }

    private func decoded(_ actions: [PlayoutAction]) -> [Int] {
        actions.compactMap { if case .decode(let d) = $0 { Int(d[0]) } else { nil } }
    }

    /// Actions from the first decode on, trimmed to `count`.
    private func fromFirstDecode(_ actions: [PlayoutAction], _ count: Int) -> [PlayoutAction] {
        guard let i = actions.firstIndex(where: { if case .decode = $0 { true } else { false } }) else { return [] }
        return Array(actions[i..<min(actions.count, i + count)])
    }

    func testStartsAfterTargetAndPlaysInOrder() {
        var jb = JitterBuffer()
        XCTAssertEqual(jb.pull(nowMs: 0), .silence)
        let actions = run(&jb, steady(0..<20), to: 400)
        // Buffering until t=40: pulls at 10 and 30 are silent, then 0, 1, 2… in order.
        XCTAssertEqual(Array(actions.prefix(3)), [.silence, .silence, .decode(payload(0))])
        XCTAssertEqual(decoded(actions), Array(0..<18))
        XCTAssertEqual(jb.targetMs, 40)
        XCTAssertEqual(jb.stats.underruns, 0)
        XCTAssertEqual(jb.stats.dropped, 0)
    }

    func testReorderingWithinDepth() {
        var jb = JitterBuffer()
        let actions = run(&jb, [a(0, 0), a(2, 20), a(1, 25), a(3, 60), a(4, 80)], to: 150)
        XCTAssertEqual(decoded(actions), [0, 1, 2, 3, 4])
        XCTAssertEqual(jb.stats.lost, 0)
    }

    func testSingleLossUsesFECFromSuccessor() {
        var jb = JitterBuffer()
        let arrivals = steady(0..<8).filter { $0.index != 2 }
        let actions = run(&jb, arrivals, to: 200)
        XCTAssertEqual(fromFirstDecode(actions, 5),
                       [.decode(payload(0)), .decode(payload(1)), .decodeFEC(payload(3)),
                        .decode(payload(3)), .decode(payload(4))])
        XCTAssertEqual(jb.stats.fec, 1)
        XCTAssertEqual(jb.stats.lost, 1)
    }

    func testDoubleLossConcealsThenFEC() {
        var jb = JitterBuffer()
        let arrivals = steady(0..<8).filter { $0.index != 1 && $0.index != 2 }
        let actions = run(&jb, arrivals, to: 200)
        XCTAssertEqual(fromFirstDecode(actions, 4),
                       [.decode(payload(0)), .conceal, .decodeFEC(payload(3)), .decode(payload(3))])
        XCTAssertEqual(jb.stats.lost, 2)
    }

    func testDTXGapIsSilenceNotLoss() {
        var jb = JitterBuffer()
        // Five frames, then the sender goes DTX; resumes at frame slot 20 with the next seq.
        let arrivals = steady(0..<5) + [a(5, 400, seq: 5, ts: 20 * 320), a(6, 420, seq: 6, ts: 21 * 320)]
        let actions = run(&jb, arrivals, to: 520)
        XCTAssertEqual(decoded(actions), [0, 1, 2, 3, 4, 5, 6])
        XCTAssertEqual(jb.stats.underruns, 0)
        XCTAssertEqual(jb.stats.lost, 0)
        XCTAssertEqual(jb.targetMs, 40)
    }

    func testDTXGapWithBothSidesBufferedIsSilence() {
        var jb = JitterBuffer()
        // Frame 0, then (next seq) frame slot 3: slots 1 and 2 were skipped by DTX.
        let arrivals = [a(0, 0), a(1, 60, seq: 1, ts: 3 * 320), a(2, 80, seq: 2, ts: 4 * 320)]
        let actions = run(&jb, arrivals, to: 160)
        XCTAssertEqual(fromFirstDecode(actions, 5),
                       [.decode(payload(0)), .silence, .silence, .decode(payload(1)), .decode(payload(2))])
        XCTAssertEqual(jb.stats.lost, 0)
    }

    func testStallMakesLatePacketsUnderrunOnceAndKeepsSlots() {
        var jb = JitterBuffer()
        // Frames 5..9 get stuck and arrive together at t=300, after their
        // slots (150..230). Frames 15.. arrive on time and keep their slots.
        let arrivals = steady(0..<5) + (5..<10).map { a($0, 300) } + steady(15..<20, from: 300)
        let actions = run(&jb, arrivals, to: 500)
        XCTAssertEqual(jb.stats.late, 5)
        XCTAssertEqual(jb.stats.underruns, 1, "one raise per spurt")
        XCTAssertEqual(jb.targetMs, 60)
        XCTAssertEqual(decoded(actions), [0, 1, 2, 3, 4, 15, 16, 17, 18, 19])
        // Latency did not creep: frame 15 plays at its original slot, 50 + 15*20.
        let i15 = actions.firstIndex(of: .decode(payload(15)))!
        XCTAssertEqual(Double(i15) * 20 + 10, 350)
    }

    func testRaisedTargetAppliesAtNextSpurt() {
        var jb = JitterBuffer()
        // Spurt 1: frame 1 is late → target 60. Spurt 2 (contiguous seq, ts
        // jump) starts at arrival + 60.
        let arrivals = [a(0, 0), a(1, 200), a(2, 1_000, seq: 2, ts: 60 * 320), a(3, 1_020, seq: 3, ts: 61 * 320)]
        let actions = run(&jb, arrivals, to: 1_200)
        XCTAssertEqual(jb.targetMs, 60)
        XCTAssertEqual(decoded(actions), [0, 2, 3])
        let i2 = actions.firstIndex(of: .decode(payload(2)))!
        XCTAssertEqual(Double(i2) * 20 + 10, 1_070) // first pull ≥ 1000 + 60
        XCTAssertEqual(jb.stats.spurts, 2)
    }

    func testTargetDecreasesAfterTenQuietSeconds() {
        var jb = JitterBuffer()
        // Frame 5 is 200 ms late → 60; then a clean stream for > 10 s.
        let arrivals = steady(0..<5) + [a(5, 300)] + steady(6..<520, from: 120)
        run(&jb, arrivals, to: 320)
        XCTAssertEqual(jb.targetMs, 60)
        run(&jb, arrivals.filter { $0.at > 300 }, pullFrom: 330, to: 10_400)
        XCTAssertEqual(jb.targetMs, 40)
        XCTAssertEqual(jb.stats.underruns, 1)
    }

    func testTargetCappedAt200() {
        var jb = JitterBuffer()
        // 20 spurts, 1 s apart; in each the second frame is 300 ms late.
        var arrivals: [Arrival] = []
        for k in 0..<20 {
            let t = Double(k) * 1_000
            let ts = k * 50 * 320
            arrivals.append(a(2 * k, t, seq: 2 * k, ts: ts))
            arrivals.append(a(2 * k + 1, t + 300, seq: 2 * k + 1, ts: ts + 320))
        }
        run(&jb, arrivals, to: 20_500)
        XCTAssertEqual(jb.targetMs, 200)
        XCTAssertEqual(jb.stats.underruns, 20)
        XCTAssertEqual(jb.stats.spurts, 20)
    }

    func testLatePacketIsDroppedAndCountsAsUnderrun() {
        var jb = JitterBuffer()
        // Frame 2 arrives after its slot was recovered via FEC from frame 3.
        let arrivals = steady(0..<6).filter { $0.index != 2 } + [a(2, 115)]
        let actions = run(&jb, arrivals, to: 200)
        XCTAssertEqual(jb.stats.late, 1)
        XCTAssertEqual(jb.targetMs, 60)
        XCTAssertEqual(decoded(actions), [0, 1, 3, 4, 5])
        XCTAssertEqual(jb.stats.fec, 1)
    }

    func testKeepaliveSeqIsNotLoss() {
        var jb = JitterBuffer()
        // DTX pause long enough for the sender to send a keepalive (seq 3).
        var keepalive = a(99, 600, seq: 3, ts: 30 * 320)
        keepalive.keepalive = true
        let arrivals = steady(0..<3) + [keepalive, a(4, 1_200, seq: 4, ts: 60 * 320), a(5, 1_220, seq: 5, ts: 61 * 320)]
        let actions = run(&jb, arrivals, to: 1_400)
        XCTAssertEqual(decoded(actions), [0, 1, 2, 4, 5])
        XCTAssertEqual(jb.stats.lost, 0)
        XCTAssertEqual(jb.stats.underruns, 0)
    }

    func testKeepaliveSeqWithBothSidesBuffered() {
        var jb = JitterBuffer()
        var keepalive = a(99, 30, seq: 1, ts: 2 * 320)
        keepalive.keepalive = true
        // seq 0 audio, seq 1 keepalive, seq 2 audio at slot 3.
        let arrivals = [a(0, 0), keepalive, a(2, 60, seq: 2, ts: 3 * 320), a(3, 80, seq: 3, ts: 4 * 320)]
        let actions = run(&jb, arrivals, to: 200)
        XCTAssertEqual(decoded(actions), [0, 2, 3])
        XCTAssertEqual(jb.stats.lost, 0)
    }

    func testSequenceAndTimestampWrap() {
        var jb = JitterBuffer()
        let baseTs = Int(UInt32.max) - 639 // wraps after two frames
        let arrivals = (0..<6).map { a($0, Double($0 * 20), seq: 65_533 + $0, ts: baseTs + $0 * 320) }
        let actions = run(&jb, arrivals, to: 200)
        XCTAssertEqual(decoded(actions), [0, 1, 2, 3, 4, 5])
        XCTAssertEqual(jb.stats.lost, 0)
    }

    func testDuplicatesIgnored() {
        var jb = JitterBuffer()
        let actions = run(&jb, [a(0, 0), a(1, 20), a(1, 21), a(2, 40)], to: 120)
        XCTAssertEqual(jb.stats.duplicates, 1)
        XCTAssertEqual(decoded(actions), [0, 1, 2])
    }

    func testGoesIdleAfterLongSilenceAndReanchors() {
        var jb = JitterBuffer()
        run(&jb, [a(0, 0)], to: 2_200)
        XCTAssertTrue(jb.isIdle)
        // A new talkspurt from a restarted sender (seq/ts reset) plays fine.
        let actions = run(&jb, [a(7, 3_000, seq: 0, ts: 0)], pullFrom: 3_010, to: 3_070)
        XCTAssertEqual(decoded(actions), [7])
    }

    func testEarlyBurstPlaysAtSlotsWithoutDrops() {
        var jb = JitterBuffer()
        // 10 frames arrive at once (e.g. after Wi-Fi power save): early
        // packets just wait for their slots.
        let actions = run(&jb, (0..<10).map { a($0, 0) }, to: 300)
        XCTAssertEqual(decoded(actions), Array(0..<10))
        XCTAssertEqual(jb.stats.dropped, 0)
        XCTAssertEqual(jb.stats.late, 0)
    }

    func testBacklogOverCapIsTrimmedAndCursorFollows() {
        var jb = JitterBuffer()
        // 30 frames (600 ms) land in one burst: over the 400 ms hard cap
        // (maxTargetMs + backlogSlackMs), so the oldest 10 go and the playout
        // cursor jumps behind them instead of the delay being carried along.
        let actions = run(&jb, (0..<30).map { a($0, 0) }, to: 440)
        XCTAssertEqual(jb.stats.dropped, 10)
        XCTAssertEqual(decoded(actions), Array(10..<30))
        // nextTs/lastSeq moved with the drop: the next frame is a plain decode
        // in the same spurt, and nothing dropped is counted as loss.
        XCTAssertEqual(jb.stats.lost, 0)
        XCTAssertEqual(jb.stats.concealed, 0)
        XCTAssertEqual(jb.stats.spurts, 1)
        XCTAssertEqual(jb.bufferedPackets, 0)
    }

    func testTrimmedBacklogKeepsSilenceGapASilenceGap() {
        var jb = JitterBuffer()
        // 10 frames, then (contiguous seq) a spurt 90 frames of silence later,
        // all arriving at once. The cap drops exactly the first 10, so `lastSeq`
        // must end on frame 9: otherwise the gap looks like 10 lost packets.
        let arrivals = (0..<10).map { a($0, 0) } + (10..<30).map { a($0, 0, ts: (90 + $0) * 320) }
        let actions = run(&jb, arrivals, to: 440)
        XCTAssertEqual(jb.stats.dropped, 10)
        XCTAssertEqual(decoded(actions), Array(10..<30))
        XCTAssertEqual(jb.stats.lost, 0)
        XCTAssertEqual(jb.stats.concealed, 0)
        XCTAssertEqual(jb.stats.spurts, 2, "the frame after the gap starts a new spurt")
    }

    func testBacklogAtCapIsNotTrimmed() {
        var jb = JitterBuffer()
        // Exactly 400 ms buffered: at the cap, not over it — nothing is dropped.
        let actions = run(&jb, (0..<20).map { a($0, 0) }, to: 440)
        XCTAssertEqual(jb.stats.dropped, 0)
        XCTAssertEqual(decoded(actions), Array(0..<20))
        XCTAssertEqual(jb.stats.lost, 0)
        XCTAssertEqual(jb.stats.spurts, 1)
    }

    func testEmptyBufferDuringSilenceIsNotUnderrun() {
        var jb = JitterBuffer()
        // Long pause (1.5 s, < idle) between spurts: no underrun, no loss.
        let arrivals = steady(0..<3) + [a(3, 1_500, seq: 3, ts: 80 * 320)]
        let actions = run(&jb, arrivals, to: 1_700)
        XCTAssertEqual(decoded(actions), [0, 1, 2, 3])
        XCTAssertEqual(jb.stats.underruns, 0)
        XCTAssertEqual(jb.stats.lost, 0)
        XCTAssertEqual(jb.stats.spurts, 2)
    }

    func testResetRestoresInitialTarget() {
        var jb = JitterBuffer()
        run(&jb, [a(0, 0), a(1, 300)], to: 320)
        XCTAssertEqual(jb.targetMs, 60)
        jb.reset()
        XCTAssertEqual(jb.targetMs, 40)
        XCTAssertTrue(jb.isIdle)
        XCTAssertEqual(jb.pull(nowMs: 400), .silence)
    }
}
