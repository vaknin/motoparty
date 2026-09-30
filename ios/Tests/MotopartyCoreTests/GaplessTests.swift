import Foundation
import XCTest
@testable import MotopartyCore

/// PROTOCOL.md "Music flow" step 6.
final class GaplessTrackerTests: XCTestCase {
    private let current = MusicAnchor(positionMs: 0, atHostTimeMs: 100_000)
    /// The current track is 200 s long: it ends, and "b" starts, at 300 000.
    private let next = MusicNext(id: "b", atHostTimeMs: 300_000)

    private func queued() -> GaplessTracker {
        var t = GaplessTracker()
        XCTAssertEqual(t.next(next, playing: true, ready: true), .queue)
        return t
    }

    func testNextIsQueuedBehindAPlayingTrack() {
        let t = queued()
        XCTAssertEqual(t.pending, GaplessTracker.Next(id: "b", atHostTimeMs: 300_000))
        XCTAssertEqual(t.pending?.anchor, MusicAnchor(positionMs: 0, atHostTimeMs: 300_000))
    }

    func testNextIsIgnoredWhenNotPlayingOrNotReady() {
        var t = GaplessTracker()
        XCTAssertEqual(t.next(next, playing: false, ready: true), .ignore)
        XCTAssertEqual(t.next(next, playing: true, ready: false), .ignore)
        XCTAssertNil(t.pending)
        // A track queued before does not survive a next that cannot be queued.
        t = queued()
        XCTAssertEqual(t.next(MusicNext(id: "c", atHostTimeMs: 300_000), playing: true, ready: false), .ignore)
        XCTAssertNil(t.pending)
    }

    func testSameNextAgainKeepsTheQueuedItemAndTakesTheNewTime() {
        var t = queued()
        XCTAssertEqual(t.next(next, playing: true, ready: true), .keep)
        XCTAssertEqual(t.next(MusicNext(id: "b", atHostTimeMs: 290_000), playing: true, ready: true), .keep)
        XCTAssertEqual(t.pending?.atHostTimeMs, 290_000)
    }

    func testNextNamingAnotherTrackReplacesThePendingOne() {
        var t = queued()
        XCTAssertEqual(t.next(MusicNext(id: "c", atHostTimeMs: 300_000), playing: true, ready: true), .replace)
        XCTAssertEqual(t.pending?.id, "c")
    }

    func testPlayOfTheQueuedTrackOnItsAnchorDoesNotRestart() {
        var t = queued()
        // The host's music.play at the change arrives before the local change.
        let play = MusicAnchor(positionMs: 0, atHostTimeMs: 300_000)
        XCTAssertEqual(t.play(id: "b", anchor: play, currentId: "a", currentAnchor: current, playing: true, source: .message), .awaitChange)
        XCTAssertNotNil(t.pending)
        // The player changes over: that anchor is the current one.
        XCTAssertEqual(t.advanced(), GaplessTracker.Next(id: "b", atHostTimeMs: 300_000))
        XCTAssertNil(t.pending)
        // The same music.play (and every state after it) after the change.
        XCTAssertEqual(t.play(id: "b", anchor: play, currentId: "b", currentAnchor: play, playing: true, source: .message), .keep)
        // state re-anchors along the same timeline.
        let later = MusicAnchor(positionMs: 5_000, atHostTimeMs: 305_000)
        XCTAssertEqual(t.play(id: "b", anchor: later, currentId: "b", currentAnchor: play, playing: true, source: .message), .keep)
    }

    func testPlayOfTheQueuedTrackOnAnotherAnchorStartsIt() {
        var t = queued()
        // The host changed with a gap after all (it lost its own queued item).
        let late = MusicAnchor(positionMs: 0, atHostTimeMs: 300_700)
        XCTAssertEqual(t.play(id: "b", anchor: late, currentId: "a", currentAnchor: current, playing: true, source: .message), .start)
        XCTAssertNil(t.pending)
    }

    func testStateOnTheUnchangedAnchorKeepsThePendingNext() {
        var t = queued()
        // The host sends state on every queue edit.
        let same = MusicAnchor(positionMs: 50_000, atHostTimeMs: 150_000)
        XCTAssertEqual(t.play(id: "a", anchor: same, currentId: "a", currentAnchor: current, playing: true, source: .state), .keep)
        XCTAssertFalse(t.state(musicId: "a", playing: true, currentId: "a"))
        XCTAssertNotNil(t.pending)
    }

    func testPlayMessageOnTheUnchangedAnchorTakesTheNextBackWithoutASeek() {
        var t = queued()
        let same = MusicAnchor(positionMs: 50_000, atHostTimeMs: 150_000)
        // Not .start: the player neither seeks nor restarts.
        XCTAssertEqual(t.play(id: "a", anchor: same, currentId: "a", currentAnchor: current, playing: true, source: .message), .takeBack)
        XCTAssertNil(t.pending)
        // Nothing queued: the same message is only an anchor refresh.
        XCTAssertEqual(t.play(id: "a", anchor: same, currentId: "a", currentAnchor: current, playing: true, source: .message), .keep)
        // The host announces the next track again when it still applies.
        XCTAssertEqual(t.next(next, playing: true, ready: true), .queue)
        XCTAssertNotNil(t.pending)
    }

    func testStateWithoutMusicOrNotPlayingOrOnAnotherTrackCancels() {
        // After a skip: music absent while the new track downloads.
        var t = queued()
        XCTAssertTrue(t.state(musicId: nil, playing: false, currentId: "a"))
        XCTAssertNil(t.pending)
        // A track that is neither current nor pending.
        t = queued()
        XCTAssertTrue(t.state(musicId: "z", playing: true, currentId: "a"))
        XCTAssertNil(t.pending)
        // The current track, not playing.
        t = queued()
        XCTAssertTrue(t.state(musicId: "a", playing: false, currentId: "a"))
        XCTAssertNil(t.pending)
        // The current or the pending track, playing: kept.
        t = queued()
        XCTAssertFalse(t.state(musicId: "a", playing: true, currentId: "a"))
        XCTAssertFalse(t.state(musicId: "b", playing: true, currentId: "a"))
        XCTAssertNotNil(t.pending)
        // Nothing queued: nothing to take out.
        var empty = GaplessTracker()
        XCTAssertFalse(empty.state(musicId: nil, playing: false, currentId: "a"))
    }

    /// Host order at a change: state, music.play{b,0,at}, music.load c, music.next{c,at2}.
    func testNextAfterTheChangePlayWaitsForTheLocalChange() {
        var t = queued()
        let play = MusicAnchor(positionMs: 0, atHostTimeMs: 300_000)
        XCTAssertFalse(t.state(musicId: "b", playing: true, currentId: "a"))
        XCTAssertEqual(t.play(id: "b", anchor: play, currentId: "a", currentAnchor: current, playing: true, source: .state), .awaitChange)
        XCTAssertEqual(t.play(id: "b", anchor: play, currentId: "a", currentAnchor: current, playing: true, source: .message), .awaitChange)
        let after = MusicNext(id: "c", atHostTimeMs: 480_000)
        XCTAssertEqual(t.next(after, playing: true, ready: true), .later)
        // The pending track is not replaced.
        XCTAssertEqual(t.pending, GaplessTracker.Next(id: "b", atHostTimeMs: 300_000))
        // The pending one named again is nothing new.
        XCTAssertEqual(t.next(next, playing: true, ready: true), .keep)
        XCTAssertEqual(t.deferred, after)
        // The local change: b is current, c is applied now.
        XCTAssertEqual(t.advanced(fromId: "a", fromAnchor: current)?.id, "b")
        XCTAssertEqual(t.takeDeferred(), after)
        XCTAssertNil(t.takeDeferred())
        XCTAssertEqual(t.next(after, playing: true, ready: true), .queue)
        XCTAssertEqual(t.pending?.id, "c")
    }

    func testNextAfterTheChangePlayThatIsNotReadyLeavesThePendingTrack() {
        var t = queued()
        let play = MusicAnchor(positionMs: 0, atHostTimeMs: 300_000)
        _ = t.play(id: "b", anchor: play, currentId: "a", currentAnchor: current, playing: true, source: .message)
        XCTAssertEqual(t.next(MusicNext(id: "c", atHostTimeMs: 480_000), playing: true, ready: false), .later)
        XCTAssertEqual(t.pending?.id, "b")
        XCTAssertNil(t.deferred)
    }

    func testNextBeforeTheChangePlayStillReplaces() {
        var t = queued()
        XCTAssertEqual(t.next(MusicNext(id: "c", atHostTimeMs: 300_000), playing: true, ready: true), .replace)
        XCTAssertNil(t.deferred)
    }

    func testOnRepeatTheNextAfterTheChangeHasTheSameIdAndALaterStart() {
        var t = GaplessTracker()
        XCTAssertEqual(t.next(MusicNext(id: "a", atHostTimeMs: 300_000), playing: true, ready: true), .queue)
        let again = MusicAnchor(positionMs: 0, atHostTimeMs: 300_000)
        XCTAssertEqual(t.play(id: "a", anchor: again, currentId: "a", currentAnchor: current, playing: true, source: .message), .awaitChange)
        XCTAssertEqual(t.next(MusicNext(id: "a", atHostTimeMs: 500_000), playing: true, ready: true), .later)
        XCTAssertEqual(t.pending?.atHostTimeMs, 300_000)
        XCTAssertEqual(t.deferred?.atHostTimeMs, 500_000)
    }

    func testCancelDropsTheDeferredNextToo() {
        var t = queued()
        let play = MusicAnchor(positionMs: 0, atHostTimeMs: 300_000)
        _ = t.play(id: "b", anchor: play, currentId: "a", currentAnchor: current, playing: true, source: .message)
        _ = t.next(MusicNext(id: "c", atHostTimeMs: 480_000), playing: true, ready: true)
        XCTAssertTrue(t.cancel())
        XCTAssertNil(t.takeDeferred())
        XCTAssertFalse(t.hostChanged)
        // Back to normal: a next is queued, not held back.
        XCTAssertEqual(t.next(next, playing: true, ready: true), .queue)
    }

    /// The iPhone changes over early by its output delay: the host still
    /// names the old track for a moment.
    func testOldTrackOnItsOldAnchorAfterTheLocalChangeIsNotReloaded() {
        var t = queued()
        let nowAnchor = MusicAnchor(positionMs: 0, atHostTimeMs: 300_000)
        XCTAssertNotNil(t.advanced(fromId: "a", fromAnchor: MusicAnchor(positionMs: 40_000, atHostTimeMs: 140_000)))
        let old = MusicAnchor(positionMs: 199_900, atHostTimeMs: 299_900)
        XCTAssertEqual(t.play(id: "a", anchor: old, currentId: "b", currentAnchor: nowAnchor, playing: true, source: .state), .stale)
        XCTAssertEqual(t.play(id: "a", anchor: old, currentId: "b", currentAnchor: nowAnchor, playing: true, source: .message), .stale)
        // The next one queued meanwhile survives that state.
        XCTAssertEqual(t.next(MusicNext(id: "c", atHostTimeMs: 480_000), playing: true, ready: true), .queue)
        XCTAssertFalse(t.state(musicId: "a", playing: true, currentId: "b"))
        XCTAssertNotNil(t.pending)
        // Then the host's change: the usual keep.
        XCTAssertEqual(t.play(id: "b", anchor: nowAnchor, currentId: "b", currentAnchor: nowAnchor, playing: true, source: .state), .keep)
        // The old track on another anchor is a real play (previous, seek).
        let again = MusicAnchor(positionMs: 0, atHostTimeMs: 310_000)
        XCTAssertEqual(t.play(id: "a", anchor: again, currentId: "b", currentAnchor: nowAnchor, playing: true, source: .message), .start)
        XCTAssertNil(t.pending)
        // …and after a start the old anchor is no longer special.
        XCTAssertEqual(t.play(id: "a", anchor: old, currentId: "b", currentAnchor: nowAnchor, playing: true, source: .state), .start)
    }

    func testOnRepeatTheOldAnchorAfterTheChangeIsStaleToo() {
        var t = GaplessTracker()
        _ = t.next(MusicNext(id: "a", atHostTimeMs: 300_000), playing: true, ready: true)
        let nowAnchor = MusicAnchor(positionMs: 0, atHostTimeMs: 300_000)
        XCTAssertNotNil(t.advanced(fromId: "a", fromAnchor: current))
        XCTAssertEqual(t.play(id: "a", anchor: current, currentId: "a", currentAnchor: nowAnchor, playing: true, source: .state), .stale)
        XCTAssertEqual(t.play(id: "a", anchor: nowAnchor, currentId: "a", currentAnchor: nowAnchor, playing: true, source: .message), .keep)
    }

    /// A mid-track join or a talk end: music.play, then music.next at once,
    /// while the start is still prerolling (`playing` covers a pending start).
    func testNextRightAfterAPlayIsQueued() {
        var t = GaplessTracker()
        XCTAssertEqual(t.play(id: "a", anchor: current, currentId: nil, currentAnchor: nil, playing: false, source: .message), .start)
        XCTAssertEqual(t.next(next, playing: true, ready: true), .queue)
        XCTAssertEqual(t.pending?.id, "b")
    }

    func testSeekInTheCurrentTrackCancelsThePendingNext() {
        var t = queued()
        let seek = MusicAnchor(positionMs: 120_000, atHostTimeMs: 150_000)
        XCTAssertEqual(t.play(id: "a", anchor: seek, currentId: "a", currentAnchor: current, playing: true, source: .message), .start)
        XCTAssertNil(t.pending)
    }

    func testPlayOfAnotherTrackCancelsThePendingNext() {
        var t = queued()
        let other = MusicAnchor(positionMs: 0, atHostTimeMs: 150_000)
        XCTAssertEqual(t.play(id: "z", anchor: other, currentId: "a", currentAnchor: current, playing: true, source: .message), .start)
        XCTAssertNil(t.pending)
    }

    func testPlayWhileNotPlayingAlwaysStarts() {
        var t = GaplessTracker()
        XCTAssertEqual(t.play(id: "a", anchor: current, currentId: "a", currentAnchor: current, playing: false, source: .message), .start)
        XCTAssertEqual(t.play(id: "a", anchor: current, currentId: nil, currentAnchor: nil, playing: false, source: .message), .start)
    }

    func testRepeatOfTheSameTrackIsTheQueuedOneNotTheCurrent() {
        var t = GaplessTracker()
        XCTAssertEqual(t.next(MusicNext(id: "a", atHostTimeMs: 300_000), playing: true, ready: true), .queue)
        let again = MusicAnchor(positionMs: 0, atHostTimeMs: 300_000)
        XCTAssertEqual(t.play(id: "a", anchor: again, currentId: "a", currentAnchor: current, playing: true, source: .message), .awaitChange)
    }

    func testPauseStopAndTalkCancel() {
        var t = queued()
        XCTAssertTrue(t.cancel())
        XCTAssertNil(t.pending)
        XCTAssertFalse(t.cancel())
        XCTAssertNil(t.advanced())
    }
}

/// Audit M1, the client's half: a start further ahead than before.
final class FutureStartTests: XCTestCase {
    func testAnchorOneAndAHalfSecondsAheadStartsExactlyAtIt() {
        let a = MusicAnchor(positionMs: 0, atHostTimeMs: 11_500)
        XCTAssertEqual(a.startPlan(hostNowMs: 10_000, trimMs: 0, minLeadMs: 150),
                       StartPlan(positionMs: 0, atHostTimeMs: 11_500))
        // Re-planned after seek + preroll: still the anchor, nothing skipped.
        XCTAssertEqual(a.startPlan(hostNowMs: 10_400, trimMs: 0, minLeadMs: 60),
                       StartPlan(positionMs: 0, atHostTimeMs: 11_500))
        let resume = MusicAnchor(positionMs: 61_000, atHostTimeMs: 11_500)
        XCTAssertEqual(resume.startPlan(hostNowMs: 10_000, trimMs: 180, durationMs: 200_000),
                       StartPlan(positionMs: 61_180, atHostTimeMs: 11_500))
    }
}

/// Audit M2.
final class OutputDelayTests: XCTestCase {
    func testCompensationIsSwitchable() {
        XCTAssertEqual(OutputDelay(outputLatencyMs: 160, trimMs: 30, compensate: true).ms, 190)
        XCTAssertEqual(OutputDelay(outputLatencyMs: 160, trimMs: 30, compensate: false).ms, 30)
        XCTAssertEqual(OutputDelay(outputLatencyMs: 160, trimMs: -40, compensate: false).ms, -40)
    }

    func testRereadBeforeTheStartReplans() {
        XCTAssertEqual(OutputDelay.reread(usedMs: 20, nowMs: 170, started: false), .replan)
        XCTAssertEqual(OutputDelay.reread(usedMs: 170, nowMs: 175, started: false), .keep)
    }

    func testRereadWhilePlayingRestartsOnlyOutsideTheSyncBand() {
        XCTAssertEqual(OutputDelay.reread(usedMs: 20, nowMs: 170, started: true), .restart)
        XCTAssertEqual(OutputDelay.reread(usedMs: 170, nowMs: 20, started: true), .restart)
        XCTAssertEqual(OutputDelay.reread(usedMs: 100, nowMs: 180, started: true), .keep)
    }
}

/// PROTOCOL.md "Talk flow" step 1.
final class TalkPressTests: XCTestCase {
    func testSecondPressTakesTheRequestBack() {
        XCTAssertEqual(TalkPress.action(talkOpen: false, requested: false), .request)
        XCTAssertEqual(TalkPress.action(talkOpen: false, requested: true), .cancelRequest)
        XCTAssertEqual(TalkPress.action(talkOpen: true, requested: false), .close)
        XCTAssertEqual(TalkPress.action(talkOpen: true, requested: true), .close)
    }

    func testRequestIsDroppedAfterFiveSeconds() {
        XCTAssertFalse(TalkPress.isExpired(requestedAtMs: 1_000, nowMs: 5_999))
        XCTAssertTrue(TalkPress.isExpired(requestedAtMs: 1_000, nowMs: 6_000))
    }
}

/// Audit H9.
final class VolumeKeyArmingTests: XCTestCase {
    func testArmedOnlyOnceConnected() {
        var a = VolumeKeyArming()
        XCTAssertNil(a.link(connected: false, searching: true, nowMs: 0))
        XCTAssertFalse(a.armed)
        XCTAssertNil(a.link(connected: true, searching: false, nowMs: 100))
        XCTAssertTrue(a.armed)
    }

    func testLinkFlapKeepsTheKeysArmed() {
        var a = VolumeKeyArming()
        _ = a.link(connected: true, searching: false, nowMs: 0)
        XCTAssertEqual(a.link(connected: false, searching: true, nowMs: 1_000), 11_000)
        XCTAssertTrue(a.armed)
        // searching → connecting: the same grace, not a new one.
        XCTAssertEqual(a.link(connected: false, searching: true, nowMs: 4_000), 11_000)
        XCTAssertNil(a.link(connected: true, searching: false, nowMs: 5_000))
        XCTAssertTrue(a.armed)
        // The old timer fires after the reconnect: nothing.
        XCTAssertFalse(a.expire(nowMs: 11_000))
        XCTAssertTrue(a.armed)
    }

    func testDisarmedTenSecondsAfterTheLoss() {
        var a = VolumeKeyArming()
        _ = a.link(connected: true, searching: false, nowMs: 0)
        _ = a.link(connected: false, searching: true, nowMs: 1_000)
        XCTAssertFalse(a.expire(nowMs: 10_999))
        XCTAssertTrue(a.expire(nowMs: 11_000))
        XCTAssertFalse(a.armed)
        // Still searching afterwards: stays disarmed until connected.
        XCTAssertNil(a.link(connected: false, searching: true, nowMs: 12_000))
        XCTAssertFalse(a.armed)
    }

    func testIdleDisarmsAtOnce() {
        var a = VolumeKeyArming()
        _ = a.link(connected: true, searching: false, nowMs: 0)
        XCTAssertNil(a.link(connected: false, searching: false, nowMs: 1_000))
        XCTAssertFalse(a.armed)
    }
}
