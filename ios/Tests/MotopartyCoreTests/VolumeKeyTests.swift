import XCTest
@testable import MotopartyCore

final class AppVolumeTests: XCTestCase {
    func testLevelsAreDbSteps() {
        XCTAssertEqual(AppVolume.db(AppVolume.unityLevel), 0)
        XCTAssertEqual(AppVolume.db(16), 3)
        XCTAssertEqual(AppVolume.db(14), -3)
        XCTAssertEqual(AppVolume.db(1), -42)
        XCTAssertNil(AppVolume.db(0))
        XCTAssertEqual(AppVolume.linear(0), 0)
        XCTAssertEqual(AppVolume.linear(15), 1)
        XCTAssertEqual(AppVolume.linear(9), powf(10, -18 / 20), accuracy: 1e-6)
        // Every step down is quieter.
        for level in 1...AppVolume.maxLevel {
            XCTAssertLessThan(AppVolume.linear(level - 1), AppVolume.linear(level))
        }
    }

    func testGainsBoostOnlyTalkAboveUnity() {
        let top = AppVolume.gains(level: 16)
        XCTAssertEqual(top.talkVolume, 1)
        XCTAssertEqual(top.talkBoostDb, 3)
        XCTAssertEqual(top.musicVolume, 1)
        XCTAssertEqual(top.cueVolume, 1)
        XCTAssertEqual(AppVolume.gains(level: 15), AppVolume.unity)
        let low = AppVolume.gains(level: 11)
        XCTAssertEqual(low.talkBoostDb, 0)
        XCTAssertEqual(low.talkVolume, powf(10, -12 / 20), accuracy: 1e-6)
        XCTAssertEqual(low.musicVolume, low.talkVolume)
        XCTAssertEqual(low.cueVolume, low.talkVolume)
        let silent = AppVolume.gains(level: 0)
        XCTAssertEqual(silent, AppVolume.Gains(talkVolume: 0, talkBoostDb: 0, musicVolume: 0, cueVolume: 0))
    }

    func testDefaultLevelAndReleaseVolume() {
        XCTAssertEqual(AppVolume.defaultLevel, 12)
        XCTAssertEqual(AppVolume.db(AppVolume.defaultLevel), -9)
        XCTAssertEqual(AppVolume.systemVolume(forLevel: 8), 0.5)
        XCTAssertEqual(AppVolume.systemVolume(forLevel: 16), 1)
        XCTAssertEqual(AppVolume.systemVolume(forLevel: 40), 1)
        XCTAssertEqual(AppVolume.systemVolume(forLevel: -3), 0)
    }
}

final class VolumeKeyGateTests: XCTestCase {
    private let step = VolumeKeyGate.step
    private let park = VolumeKeyGate.parkVolume
    private var above: Float { park + step }
    private var below: Float { park - step }

    /// Armed at app `level` (system volume 0.5) and parked: the park's own
    /// reading has arrived.
    private func armed(level: Int = 8, nowMs: Double = 0) -> VolumeKeyGate {
        var gate = VolumeKeyGate()
        XCTAssertEqual(gate.arm(level: level, volume: 0.5, nowMs: nowMs), .park)
        XCTAssertEqual(gate.observe(park, nowMs: nowMs + 20), .ownReset)
        return gate
    }

    /// One up press at `t` and the reading of its reset.
    private func up(_ gate: inout VolumeKeyGate, at t: Double) -> VolumeKeyGate.Action {
        let action = gate.observe(above, nowMs: t)
        XCTAssertEqual(gate.observe(park, nowMs: t + 10), .ownReset)
        return action
    }

    private func down(_ gate: inout VolumeKeyGate, at t: Double) -> VolumeKeyGate.Action {
        let action = gate.observe(below, nowMs: t)
        XCTAssertEqual(gate.observe(park, nowMs: t + 10), .ownReset)
        return action
    }

    // MARK: Arming

    func testDisarmedIsAPlainVolumeKey() {
        var gate = VolumeKeyGate()
        for (i, volume) in [0.5, 0.5 + step, 0.5 + 2 * step, 1, 0.2].enumerated() {
            XCTAssertEqual(gate.observe(Float(volume), nowMs: Double(i) * 100), .none)
        }
        XCTAssertEqual(gate.settle(nowMs: 1_000), .none)
        XCTAssertEqual(gate.endSettle(nowMs: 3_000), .none)
        XCTAssertNil(gate.nudge(by: 1))
    }

    func testArmStartsAtTheGivenLevelNotTheSystemVolume() {
        var gate = VolumeKeyGate()
        // Device log 2026-09-29: system 0.2 at connect used to mean level 3
        // (-36 dB) behind a system volume reading 94%.
        XCTAssertEqual(gate.arm(level: AppVolume.defaultLevel, volume: 0.2, nowMs: 0), .park)
        XCTAssertTrue(gate.armed)
        XCTAssertEqual(gate.level, 12)
        XCTAssertEqual(gate.observe(park, nowMs: 30), .ownReset)
        // Arming again (already armed) changes nothing.
        XCTAssertEqual(gate.arm(level: 3, volume: 0.2, nowMs: 50), .none)
        XCTAssertEqual(gate.level, 12)

        var loud = VolumeKeyGate()
        XCTAssertEqual(loud.arm(level: 40, volume: 0.1, nowMs: 0), .park)
        XCTAssertEqual(loud.level, 16)
        var silent = VolumeKeyGate()
        XCTAssertEqual(silent.arm(level: -2, volume: 1, nowMs: 0), .park)
        XCTAssertEqual(silent.level, 0)
    }

    func testParkInTransitIsNotAKey() {
        // Device log 2026-09-29: armed at 0.2 (level 3), and a reading on the
        // way to the park 18 ms later stepped the level to 4.
        var gate = VolumeKeyGate()
        XCTAssertEqual(gate.arm(level: 3, volume: 0.2, nowMs: 0), .park)
        XCTAssertEqual(gate.level, 3)
        XCTAssertEqual(gate.observe(0.25, nowMs: 18), .none)
        XCTAssertEqual(gate.observe(0.6, nowMs: 30), .none)
        XCTAssertEqual(gate.observe(park, nowMs: 40), .ownReset)
        XCTAssertEqual(gate.level, 3)
        // Parked, keys count again.
        XCTAssertEqual(up(&gate, at: 1_000), .step(level: 4))
        XCTAssertEqual(down(&gate, at: 2_000), .step(level: 3))

        // Once the park is no longer expected, a reading there is a key.
        var late = VolumeKeyGate()
        XCTAssertEqual(late.arm(level: 3, volume: 0.2, nowMs: 0), .park)
        XCTAssertEqual(late.observe(0.25, nowMs: VolumeKeyGate.ownChangeMs + 1), .step(level: 4))
    }

    func testHfpParkReadingIsTheParkNotAKey() {
        // Device log 2026-09-29: in an HFP talk the park reads back as 0.95.
        var gate = armed(level: 8)
        XCTAssertEqual(gate.settle(nowMs: 1_000), .park)
        XCTAssertEqual(gate.observe(0.95, nowMs: 1_100), .ownReset)
        XCTAssertEqual(gate.endSettle(nowMs: 2_600), .park)
        XCTAssertEqual(gate.observe(0.95, nowMs: 2_620), .ownReset)
        // A key moves it off (up to 1, down a 1/20 step), and the 0.95 reset
        // that follows is the park, not the opposite key.
        XCTAssertEqual(gate.observe(1, nowMs: 4_000), .step(level: 9))
        XCTAssertEqual(gate.observe(0.95, nowMs: 4_010), .ownReset)
        XCTAssertEqual(gate.observe(0.9, nowMs: 6_000), .step(level: 8))
        XCTAssertEqual(gate.observe(0.95, nowMs: 6_010), .ownReset)
        XCTAssertEqual(gate.level, 8)
    }

    func testDisarmHandsTheLevelBackToTheSystemVolume() {
        var gate = armed(level: 8)
        XCTAssertEqual(up(&gate, at: 1_000), .step(level: 9))
        XCTAssertEqual(gate.disarm(volume: park, nowMs: 2_000), .release(volume: 9.0 / 16))
        XCTAssertFalse(gate.armed)
        // Disarmed: readings are the system's again.
        XCTAssertEqual(gate.observe(park, nowMs: 2_050), .none)
        XCTAssertEqual(gate.observe(9.0 / 16, nowMs: 2_100), .none)
        XCTAssertEqual(gate.disarm(volume: 9.0 / 16, nowMs: 2_200), .none)

        var top = armed(level: 16)
        XCTAssertEqual(up(&top, at: 1_000), .step(level: 16))
        XCTAssertEqual(top.disarm(volume: park, nowMs: 2_000), .release(volume: 1))
    }

    // MARK: Park and own resets

    func testParkReadingOnlyCountsWhileExpected() {
        var gate = VolumeKeyGate()
        XCTAssertEqual(gate.arm(level: 15, volume: park, nowMs: 0), .park)
        // Already at the park: the reset makes no reading, and the expectation
        // just expires; a later park-level reading is nothing.
        XCTAssertEqual(gate.observe(park, nowMs: 2_000), .none)
        // A key still moves it off the park.
        XCTAssertEqual(gate.observe(above, nowMs: 3_000), .step(level: 16))
        XCTAssertEqual(gate.observe(park, nowMs: 3_010), .ownReset)
        // Only once: a second reading at the park is not waited for.
        XCTAssertEqual(gate.observe(park, nowMs: 3_020), .none)
    }

    func testResetThatNeverArrivesExpires() {
        var gate = armed()
        XCTAssertEqual(gate.observe(above, nowMs: 1_000), .step(level: 9))
        // The slider was unreachable; much later the volume comes back to the
        // park by some other way: not ours, and not a key.
        XCTAssertEqual(gate.observe(park, nowMs: 1_000 + VolumeKeyGate.ownChangeMs + 1), .none)
        XCTAssertEqual(gate.level, 9)
    }

    func testRepeatBeforeTheResetIsStillAKey() {
        var gate = armed()
        XCTAssertEqual(gate.observe(below, nowMs: 1_000), .step(level: 7))
        // The next down lands before the reset: compared with the latest reading.
        XCTAssertEqual(gate.observe(below - step, nowMs: 1_500), .step(level: 6))
        XCTAssertEqual(gate.observe(park, nowMs: 1_510), .ownReset)
    }

    // MARK: Single presses

    func testSinglePressesStepTheAppLevel() {
        var gate = armed(level: 8)
        XCTAssertEqual(up(&gate, at: 1_000), .step(level: 9))
        XCTAssertEqual(gate.lastKey, .up)
        XCTAssertNil(gate.lastGapMs)
        XCTAssertEqual(up(&gate, at: 3_000), .step(level: 10))
        XCTAssertEqual(gate.lastGapMs, 2_000)
        XCTAssertEqual(gate.burstCount, 1)
        XCTAssertEqual(down(&gate, at: 5_000), .step(level: 9))
        XCTAssertEqual(gate.lastKey, .down)
        XCTAssertEqual(down(&gate, at: 7_000), .step(level: 8))
        XCTAssertEqual(gate.level, 8)
    }

    func testAppLevelIsClamped() {
        var gate = armed(level: 16)
        XCTAssertEqual(up(&gate, at: 1_000), .step(level: 16))
        XCTAssertEqual(up(&gate, at: 3_000), .step(level: 16))

        var quiet = armed(level: 1)
        XCTAssertEqual(quiet.level, 1)
        XCTAssertEqual(down(&quiet, at: 1_000), .step(level: 0))
        XCTAssertEqual(down(&quiet, at: 3_000), .step(level: 0))
        XCTAssertEqual(quiet.nudge(by: -1), 0)
        XCTAssertEqual(quiet.nudge(by: 40), 16)
    }

    func testSpokenCommandsNudgeTheLevel() {
        var gate = armed(level: 8)
        XCTAssertEqual(gate.nudge(by: 1), 9)
        XCTAssertEqual(gate.nudge(by: -1), 8)
        XCTAssertEqual(gate.nudge(by: -1), 7)
        // The disarm hands the nudged level back.
        XCTAssertEqual(gate.disarm(volume: park, nowMs: 1_000), .release(volume: 7.0 / 16))
    }

    func testThreeQuickTapsAreThreeSteps() {
        var gate = armed(level: 8)
        XCTAssertEqual(up(&gate, at: 1_000), .step(level: 9))
        XCTAssertEqual(up(&gate, at: 1_180), .step(level: 10))
        XCTAssertEqual(up(&gate, at: 1_360), .step(level: 11))
        XCTAssertEqual(gate.burstCount, 3)
        // A fourth only after a human pause: a new burst, not a hold.
        XCTAssertEqual(up(&gate, at: 1_700), .step(level: 12))
        XCTAssertEqual(gate.burstCount, 1)
    }

    func testIrregularTapsNeverToggle() {
        var gate = armed(level: 8)
        // Gaps a finger makes: the later ones too slow for key repeat.
        var t = 1_000.0
        var level = 8
        for gap in [0.0, 300, 250, 350, 280, 260, 400] {
            t += gap
            level += 1
            XCTAssertEqual(up(&gate, at: t), .step(level: level))
        }
    }

    func testDownBreaksABurst() {
        var gate = armed(level: 8)
        XCTAssertEqual(up(&gate, at: 1_000), .step(level: 9))
        XCTAssertEqual(up(&gate, at: 1_500), .step(level: 10))
        XCTAssertEqual(down(&gate, at: 1_600), .step(level: 9))
        XCTAssertEqual(up(&gate, at: 1_700), .step(level: 10))
        XCTAssertEqual(up(&gate, at: 1_800), .step(level: 11))
        XCTAssertEqual(gate.burstCount, 2)
    }

    // MARK: Holds

    /// A hold: the press, the initial repeat delay, then fast repeats.
    private func hold(_ gate: inout VolumeKeyGate, from start: Double, startLevel: Int) {
        XCTAssertEqual(up(&gate, at: start), .step(level: min(16, startLevel + 1)))
        XCTAssertEqual(up(&gate, at: start + 500), .step(level: min(16, startLevel + 2)))
        XCTAssertEqual(gate.lastGapMs, 500)
        XCTAssertEqual(up(&gate, at: start + 600), .step(level: min(16, startLevel + 3)))
        XCTAssertEqual(up(&gate, at: start + 700), .toggle(level: startLevel))
        XCTAssertEqual(gate.burstCount, VolumeKeyGate.holdSteps)
    }

    func testHoldTogglesOnceRevertsAndAbsorbs() {
        var gate = armed(level: 8)
        hold(&gate, from: 1_000, startLevel: 8)
        XCTAssertEqual(gate.level, 8)
        for i in 1...20 {
            XCTAssertEqual(up(&gate, at: 1_700 + Double(i) * 100), .absorb)
        }
        XCTAssertEqual(gate.level, 8)
        // Released: quiet longer than the repeat gap, and the next press is a
        // single step again.
        XCTAssertEqual(up(&gate, at: 5_000), .step(level: 9))
        XCTAssertEqual(gate.burstCount, 1)
    }

    func testHoldAtTheTopStillToggles() {
        var gate = armed(level: 16)
        hold(&gate, from: 1_000, startLevel: 16)
        XCTAssertEqual(gate.level, 16)
    }

    func testFirstGapBound() {
        var gate = armed(level: 8)
        XCTAssertEqual(up(&gate, at: 1_000), .step(level: 9))
        // Inclusive.
        XCTAssertEqual(up(&gate, at: 1_000 + VolumeKeyGate.firstRepeatGapMs), .step(level: 10))
        XCTAssertEqual(gate.burstCount, 2)
        var late = armed(level: 8)
        XCTAssertEqual(up(&late, at: 1_000), .step(level: 9))
        XCTAssertEqual(up(&late, at: 1_001 + VolumeKeyGate.firstRepeatGapMs), .step(level: 10))
        XCTAssertEqual(late.burstCount, 1)
    }

    func testRepeatGapBoundAfterTheFirst() {
        var gate = armed(level: 8)
        XCTAssertEqual(up(&gate, at: 1_000), .step(level: 9))
        XCTAssertEqual(up(&gate, at: 1_500), .step(level: 10))
        // Inclusive; one slower gap and the burst starts over at this press.
        XCTAssertEqual(up(&gate, at: 1_500 + VolumeKeyGate.repeatGapMs), .step(level: 11))
        XCTAssertEqual(gate.burstCount, 3)
        XCTAssertEqual(up(&gate, at: 1_701 + VolumeKeyGate.repeatGapMs), .step(level: 12))
        XCTAssertEqual(gate.burstCount, 1)
        // A hold from here reverts only its own steps.
        XCTAssertEqual(up(&gate, at: 2_400), .step(level: 13))
        XCTAssertEqual(up(&gate, at: 2_500), .step(level: 14))
        XCTAssertEqual(up(&gate, at: 2_600), .toggle(level: 11))
    }

    func testHoldAfterSteppingRevertsToTheNewLevel() {
        var gate = armed(level: 8)
        XCTAssertEqual(up(&gate, at: 1_000), .step(level: 9))
        XCTAssertEqual(down(&gate, at: 3_000), .step(level: 8))
        XCTAssertEqual(down(&gate, at: 5_000), .step(level: 7))
        hold(&gate, from: 8_000, startLevel: 7)
    }

    func testSlowResetsDoNotBreakAHold() {
        // Each reset's reading arrives late, just before the next repeat:
        // every up is still one step from the latest reading.
        var gate = armed(level: 8)
        XCTAssertEqual(gate.observe(above, nowMs: 1_000), .step(level: 9))
        XCTAssertEqual(gate.observe(park, nowMs: 1_400), .ownReset)
        XCTAssertEqual(gate.observe(above, nowMs: 1_500), .step(level: 10))
        XCTAssertEqual(gate.observe(park, nowMs: 1_580), .ownReset)
        XCTAssertEqual(gate.observe(above, nowMs: 1_600), .step(level: 11))
        XCTAssertEqual(gate.observe(park, nowMs: 1_690), .ownReset)
        XCTAssertEqual(gate.observe(above, nowMs: 1_700), .toggle(level: 8))
    }

    // MARK: Settling

    func testRouteJumpIsNotAKeyAndIsParked() {
        var gate = armed(level: 8)
        XCTAssertEqual(up(&gate, at: 9_800), .step(level: 9))
        // Talk opens: HFP has its own level; the burst is forgotten.
        XCTAssertEqual(gate.settle(nowMs: 10_000), .park)
        XCTAssertEqual(gate.observe(0.3, nowMs: 10_300), .park)
        XCTAssertEqual(gate.observe(park, nowMs: 10_320), .ownReset)
        XCTAssertEqual(gate.observe(0.8, nowMs: 10_600), .park)
        XCTAssertEqual(gate.level, 9)
        XCTAssertEqual(gate.burstCount, 0)
        // The window is over: parked once more, and keys count again.
        XCTAssertEqual(gate.endSettle(nowMs: 10_000 + VolumeKeyGate.settleMs), .park)
        XCTAssertEqual(gate.observe(park, nowMs: 11_520), .ownReset)
        XCTAssertEqual(gate.endSettle(nowMs: 11_900), .none)
        XCTAssertEqual(up(&gate, at: 12_000), .step(level: 10))
        XCTAssertNil(gate.lastGapMs)
    }

    func testSettleCallsExtendTheWindow() {
        var gate = armed()
        XCTAssertEqual(gate.settle(nowMs: 1_000), .park)
        XCTAssertEqual(gate.settle(nowMs: 2_000), .park)
        // The first call's timer: the window is still open.
        XCTAssertEqual(gate.endSettle(nowMs: 1_000 + VolumeKeyGate.settleMs), .none)
        XCTAssertEqual(gate.observe(0.2, nowMs: 2_700), .park)
        XCTAssertEqual(gate.endSettle(nowMs: 2_000 + VolumeKeyGate.settleMs), .park)
    }

    func testReadingAfterTheWindowWithoutItsTimerIsAKey() {
        var gate = armed(level: 8)
        XCTAssertEqual(gate.settle(nowMs: 1_000), .park)
        XCTAssertEqual(gate.observe(park, nowMs: 1_010), .ownReset)
        XCTAssertEqual(gate.observe(above, nowMs: 1_000 + VolumeKeyGate.settleMs + 1), .step(level: 9))
    }

    func testHoldThatOpensTalkStaysAbsorbedAcrossTheSettle() {
        var gate = armed(level: 8)
        hold(&gate, from: 1_000, startLevel: 8)
        // The toggle opens talk: the session switches while the key is still held.
        XCTAssertEqual(gate.settle(nowMs: 1_750), .park)
        var t = 1_800.0
        while t < 1_750 + VolumeKeyGate.settleMs {
            XCTAssertEqual(gate.observe(above, nowMs: t), .park)
            XCTAssertEqual(gate.observe(park, nowMs: t + 10), .ownReset)
            t += 100
        }
        XCTAssertEqual(gate.endSettle(nowMs: 1_750 + VolumeKeyGate.settleMs), .park)
        // Still held after the window: absorbed, no second toggle, no steps.
        for _ in 0..<10 {
            XCTAssertEqual(up(&gate, at: t), .absorb)
            t += 100
        }
        XCTAssertEqual(gate.level, 8)
        // Released and pressed again much later: a step.
        XCTAssertEqual(up(&gate, at: t + 2_000), .step(level: 9))
    }

    func testDisarmForgetsEverything() {
        var gate = armed(level: 8)
        XCTAssertEqual(up(&gate, at: 1_000), .step(level: 9))
        XCTAssertEqual(up(&gate, at: 1_500), .step(level: 10))
        XCTAssertEqual(gate.settle(nowMs: 1_600), .park)
        XCTAssertEqual(gate.disarm(volume: park, nowMs: 1_650), .release(volume: 10.0 / 16))
        XCTAssertEqual(gate.observe(0.3, nowMs: 1_700), .none)
        // Re-armed at the level it is given (the app remembers 10), whatever
        // the system volume is now.
        XCTAssertEqual(gate.arm(level: 10, volume: 0.25, nowMs: 1_750), .park)
        XCTAssertEqual(gate.level, 10)
        XCTAssertEqual(gate.observe(park, nowMs: 1_760), .ownReset)
        // Not settling any more, and a new burst.
        XCTAssertEqual(up(&gate, at: 1_800), .step(level: 11))
        XCTAssertEqual(gate.burstCount, 1)
    }
}
