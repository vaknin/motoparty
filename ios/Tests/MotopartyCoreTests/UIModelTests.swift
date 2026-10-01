import XCTest
@testable import MotopartyCore

/// The decisions behind the iPhone's screens (audit round 3).
final class UIModelTests: XCTestCase {
    // MARK: Times

    func testClock() {
        XCTAssertEqual(TrackTime.clock(0), "0:00")
        XCTAssertEqual(TrackTime.clock(999), "0:00")
        XCTAssertEqual(TrackTime.clock(61_000), "1:01")
        XCTAssertEqual(TrackTime.clock(599_999), "9:59")
        XCTAssertEqual(TrackTime.clock(3_600_000), "1:00:00")
        XCTAssertEqual(TrackTime.clock(3_725_000), "1:02:05")
        XCTAssertEqual(TrackTime.clock(-5_000), "0:00")
        XCTAssertEqual(TrackTime.clock(.nan), "0:00")
        XCTAssertEqual(TrackTime.clock(.infinity), "0:00")
    }

    func testJoinedLeavesOutWhatIsMissing() {
        XCTAssertEqual(TrackTime.joined(["Dire Straits", "3:33"]), "Dire Straits · 3:33")
        XCTAssertEqual(TrackTime.joined(["", "3:33"]), "3:33")
        XCTAssertEqual(TrackTime.joined([nil, "Dire Straits", nil]), "Dire Straits")
        XCTAssertEqual(TrackTime.joined([nil, ""]), "")
    }

    func testPositionFollowsTheHostAnchorWhilePlaying() {
        let anchor = MusicAnchor(positionMs: 10_000, atHostTimeMs: 1_000_000)
        XCTAssertEqual(PlaybackPosition.ms(anchor: anchor, hostNowMs: 1_005_000, parkedMs: 0, durationMs: 200_000), 15_000)
        // The anchor is still ahead (the resume lead): not below zero.
        let early = MusicAnchor(positionMs: 0, atHostTimeMs: 1_001_500)
        XCTAssertEqual(PlaybackPosition.ms(anchor: early, hostNowMs: 1_000_000, parkedMs: 0, durationMs: 200_000), 0)
        // Past the end: the end.
        XCTAssertEqual(PlaybackPosition.ms(anchor: anchor, hostNowMs: 2_000_000, parkedMs: 0, durationMs: 200_000), 200_000)
    }

    func testPositionIsWhereTheTrackIsParkedOtherwise() {
        XCTAssertEqual(PlaybackPosition.ms(anchor: nil, hostNowMs: 1_005_000, parkedMs: 42_000, durationMs: 200_000), 42_000)
        // No clock yet: the anchor cannot be followed.
        let anchor = MusicAnchor(positionMs: 10_000, atHostTimeMs: 1_000_000)
        XCTAssertEqual(PlaybackPosition.ms(anchor: anchor, hostNowMs: nil, parkedMs: 42_000, durationMs: 200_000), 42_000)
        XCTAssertEqual(PlaybackPosition.ms(anchor: nil, hostNowMs: nil, parkedMs: .nan, durationMs: nil), 0)
        // An unknown duration (0) does not clamp.
        XCTAssertEqual(PlaybackPosition.ms(anchor: nil, hostNowMs: nil, parkedMs: 42_000, durationMs: 0), 42_000)
    }

    // MARK: Talk

    func testTalkPhase() {
        XCTAssertEqual(TalkPhase(requested: false, open: false, live: false, mode: .ownMic), .idle)
        XCTAssertEqual(TalkPhase(requested: true, open: false, live: false, mode: .ownMic), .connecting)
        // Open, the headset still switching: not live yet.
        XCTAssertEqual(TalkPhase(requested: false, open: true, live: false, mode: .ownMic), .connecting)
        XCTAssertEqual(TalkPhase(requested: false, open: true, live: true, mode: .ownMic), .live(hostMic: false))
        XCTAssertEqual(TalkPhase(requested: false, open: true, live: true, mode: .hostMic), .live(hostMic: true))
        // A stale "live" without a talk is nothing.
        XCTAssertEqual(TalkPhase(requested: false, open: false, live: true, mode: .hostMic), .idle)
    }

    func testTalkWording() {
        XCTAssertEqual(TalkPhase.idle.buttonTitle, "TALK")
        XCTAssertEqual(TalkPhase.connecting.buttonTitle, "Connecting…")
        XCTAssertEqual(TalkPhase.live(hostMic: false).buttonTitle, "END TALK")
        XCTAssertNil(TalkPhase.idle.caption)
        XCTAssertNil(TalkPhase.connecting.caption)
        XCTAssertEqual(TalkPhase.live(hostMic: false).caption, "Talking")
        XCTAssertEqual(TalkPhase.live(hostMic: true).caption, "Talking · rider's mic")
        XCTAssertTrue(TalkPhase.live(hostMic: true).isLive)
        XCTAssertTrue(TalkPhase.connecting.isConnecting)
        XCTAssertFalse(TalkPhase.idle.isLive || TalkPhase.idle.isConnecting)
        XCTAssertEqual(TalkPhase.live(hostMic: false).accessibilityLabel, "End talk")
    }

    // MARK: Link

    func testLinkWordingNamesThePixelNotAHost() {
        XCTAssertEqual(LinkWording.searching(lastHost: "Pixel 8"), "Looking for Pixel 8…")
        XCTAssertEqual(LinkWording.searching(lastHost: nil), "Looking for the Pixel…")
        XCTAssertEqual(LinkWording.searching(lastHost: "  "), "Looking for the Pixel…")
        XCTAssertEqual(LinkWording.connecting("Pixel 8"), "Connecting to Pixel 8…")
        XCTAssertEqual(LinkWording.connected("Pixel 8"), "Connected to Pixel 8")
    }

    // MARK: Notices

    func testNoticeIsClearedByTheNextSuccessOfItsKind() {
        let talk = Notice(.talk, "Could not open the mic")
        XCTAssertTrue(talk.isCleared(by: .talkOpened))
        XCTAssertFalse(talk.isCleared(by: .connected))
        XCTAssertFalse(talk.isCleared(by: .reconnectAsked))

        let link = Notice(.link, "Pixel speaks protocol 2")
        XCTAssertTrue(link.isCleared(by: .connected))
        XCTAssertTrue(link.isCleared(by: .reconnectAsked))
        XCTAssertFalse(link.isCleared(by: .talkOpened))

        let audio = Notice(.audio, "Audio session: busy")
        XCTAssertTrue(audio.isCleared(by: .audioSessionActivated))
        XCTAssertTrue(audio.isCleared(by: .talkOpened))
        XCTAssertFalse(audio.isCleared(by: .connected))
    }

    // MARK: Queue

    private func item(_ id: String) -> HostState.QueueItem {
        HostState.QueueItem(id: id, title: "T\(id)", artist: "A")
    }

    func testQueueText() {
        XCTAssertNil(QueueText.badge(0))
        XCTAssertNil(QueueText.badge(-1))
        XCTAssertEqual(QueueText.badge(1), "1")
        XCTAssertEqual(QueueText.badge(99), "99")
        XCTAssertEqual(QueueText.badge(100), "99+")
        XCTAssertEqual(QueueText.upNext(0), "Up next")
        XCTAssertEqual(QueueText.upNext(12), "Up next · 12")
        XCTAssertEqual(QueueText.songs(1), "1 song")
        XCTAssertEqual(QueueText.songs(14), "14 songs")
        XCTAssertEqual(QueueText.clearMessage(1), "The upcoming song is removed. The current song keeps playing.")
        XCTAssertEqual(QueueText.clearMessage(3), "The 3 upcoming songs are removed. The current song keeps playing.")
    }

    func testRowKeysAreUniqueAndSurviveARemovalAbove() {
        let queue = [item("a"), item("b"), item("a"), item("c")]
        let rows = QueueRow.rows(queue)
        XCTAssertEqual(rows.map(\.key), ["a#0", "b#0", "a#1", "c#0"])
        XCTAssertEqual(rows.map(\.index), [0, 1, 2, 3])
        XCTAssertEqual(Set(rows.map(\.key)).count, rows.count)
        // "b" removed: "c" keeps its key (a row number would have changed).
        let after = QueueRow.rows([item("a"), item("a"), item("c")])
        XCTAssertEqual(after.last?.key, "c#0")
        XCTAssertEqual(after.last?.index, 2)
    }

    private func keys(_ edits: QueueEdits, _ queue: [HostState.QueueItem]) -> [String] {
        edits.visible(queue).map(\.key)
    }

    func testRemovedRowIsHiddenAtOnce() {
        let queue = [item("a"), item("b"), item("c")]
        var edits = QueueEdits()
        XCTAssertEqual(keys(edits, queue), ["a#0", "b#0", "c#0"])
        let removed = edits.remove([QueueRow.rows(queue)[1]], in: queue, nowMs: 1_000)
        XCTAssertEqual(removed, [QueueUndo.Removed(item: item("b"), at: 1)])
        XCTAssertEqual(removed.map(\.command), [.edit(.remove, index: 1, id: "b")])
        XCTAssertFalse(edits.isEmpty)
        XCTAssertEqual(keys(edits, queue), ["a#0", "c#0"])
        // The same row twice is one removal.
        XCTAssertEqual(edits.remove([QueueRow.rows(queue)[1]], in: queue, nowMs: 1_500), [])
        XCTAssertEqual(edits.nextExpiryMs, 1_000 + QueueEdits.timeoutMs)
    }

    func testWireIndexCountsRemovalsInFlightAbove() {
        let queue = [item("a"), item("b"), item("c"), item("d")]
        let rows = QueueRow.rows(queue)
        var edits = QueueEdits()
        XCTAssertEqual(edits.wireIndex(of: rows[2], in: queue), 2)
        edits.remove([rows[1]], in: queue, nowMs: 0)
        // The host will have dropped "b" by the time it reads the next edit.
        XCTAssertEqual(edits.wireIndex(of: rows[0], in: queue), 0)
        XCTAssertEqual(edits.wireIndex(of: rows[2], in: queue), 1)
        XCTAssertEqual(edits.wireIndex(of: rows[3], in: queue), 2)
        let removed = edits.remove([rows[2]], in: queue, nowMs: 0)
        XCTAssertEqual(removed.map(\.at), [1])
        XCTAssertEqual(edits.wireIndex(of: rows[3], in: queue), 1)
    }

    func testSeveralRowsAreRemovedFromTheBottomUp() {
        let queue = [item("a"), item("b"), item("c"), item("d")]
        var edits = QueueEdits()
        let rows = edits.visible(queue)
        let removed = edits.remove([rows[1], rows[3]], in: queue, nowMs: 0)
        // "d" first, so "b" is still at 1 when the host reads its remove.
        XCTAssertEqual(removed.map(\.command), [.edit(.remove, index: 3, id: "d"), .edit(.remove, index: 1, id: "b")])
        XCTAssertEqual(keys(edits, queue), ["a#0", "c#0"])
    }

    func testTheHostsStateConfirmsARemoval() {
        let old = [item("a"), item("b"), item("c")]
        var edits = QueueEdits()
        edits.remove([QueueRow.rows(old)[1]], in: old, nowMs: 0)
        let new = [item("a"), item("c")]
        edits.queueChanged(from: old, to: new)
        XCTAssertTrue(edits.isEmpty)
        XCTAssertEqual(keys(edits, new), ["a#0", "c#0"])
    }

    func testAnotherChangeKeepsTheRowHidden() {
        // The head of the queue started playing before the host read the
        // remove: the removed row must not come back for a moment.
        let old = [item("a"), item("b"), item("c")]
        var edits = QueueEdits()
        edits.remove([QueueRow.rows(old)[1]], in: old, nowMs: 0)
        let headGone = [item("b"), item("c")]
        edits.queueChanged(from: old, to: headGone)
        XCTAssertFalse(edits.isEmpty)
        XCTAssertEqual(keys(edits, headGone), ["c#0"])
        XCTAssertEqual(edits.wireIndex(of: QueueRow.rows(headGone)[1], in: headGone), 0)
        edits.queueChanged(from: headGone, to: [item("c")])
        XCTAssertTrue(edits.isEmpty)
    }

    func testRemovingOneOfTwoCopiesShowsTheOther() {
        let old = [item("a"), item("x"), item("a")]
        var edits = QueueEdits()
        edits.remove([QueueRow.rows(old)[2]], in: old, nowMs: 0)
        XCTAssertEqual(keys(edits, old), ["a#0", "x#0"])
        let new = [item("a"), item("x")]
        edits.queueChanged(from: old, to: new)
        XCTAssertTrue(edits.isEmpty)
        XCTAssertEqual(keys(edits, new), ["a#0", "x#0"])
    }

    func testOneCopyGoneConfirmsOneRemovalOfIt() {
        let old = [item("a"), item("x"), item("a")]
        var edits = QueueEdits()
        edits.remove(edits.visible(old).filter { $0.item.id == "a" }, in: old, nowMs: 0)
        XCTAssertEqual(keys(edits, old), ["x#0"])
        let half = [item("x"), item("a")]
        edits.queueChanged(from: old, to: half)
        XCTAssertFalse(edits.isEmpty)
        XCTAssertEqual(keys(edits, half), ["x#0"])
        edits.queueChanged(from: half, to: [item("x")])
        XCTAssertTrue(edits.isEmpty)
    }

    func testAnUnconfirmedRemovalComesBack() {
        let queue = [item("a"), item("b")]
        var edits = QueueEdits()
        edits.remove([QueueRow.rows(queue)[0]], in: queue, nowMs: 1_000)
        XCTAssertFalse(edits.expire(nowMs: 1_000 + QueueEdits.timeoutMs - 1))
        XCTAssertEqual(edits.visible(queue).count, 1)
        XCTAssertTrue(edits.expire(nowMs: 1_000 + QueueEdits.timeoutMs))
        XCTAssertEqual(edits.visible(queue).count, 2)
        XCTAssertNil(edits.nextExpiryMs)
    }

    func testAClearedQueueDropsEveryRemoval() {
        let old = [item("a"), item("b")]
        var edits = QueueEdits()
        edits.remove([QueueRow.rows(old)[0]], in: old, nowMs: 0)
        edits.queueChanged(from: old, to: [])
        XCTAssertTrue(edits.isEmpty)
    }

    // MARK: Queue: drag to reorder

    func testADraggedRowStaysWhereItWasDropped() {
        let queue = [item("a"), item("b"), item("c"), item("d")]
        var edits = QueueEdits()
        // SwiftUI's offset 3 is the gap above "d": "a" ends up at 2.
        XCTAssertEqual(edits.move(from: 0, insertBefore: 3, in: queue, nowMs: 0), .edit(.move, index: 0, id: "a", to: 2))
        XCTAssertEqual(keys(edits, queue), ["b#0", "c#0", "a#0", "d#0"])
        // Up: "d" to the top.
        XCTAssertEqual(edits.move(from: 3, insertBefore: 0, in: queue, nowMs: 0), .edit(.move, index: 3, id: "d", to: 0))
        XCTAssertEqual(keys(edits, queue), ["d#0", "b#0", "c#0", "a#0"])
        // Past the end is the end.
        XCTAssertEqual(edits.move(from: 0, insertBefore: 9, in: queue, nowMs: 0), .edit(.move, index: 0, id: "d", to: 3))
    }

    func testADropInPlaceSendsNothing() {
        let queue = [item("a"), item("b"), item("a")]
        var edits = QueueEdits()
        XCTAssertNil(edits.move(from: 1, insertBefore: 1, in: queue, nowMs: 0))
        XCTAssertNil(edits.move(from: 1, insertBefore: 2, in: queue, nowMs: 0))
        XCTAssertNil(edits.move(from: 5, insertBefore: 0, in: queue, nowMs: 0))
        XCTAssertTrue(edits.isEmpty)
        // Past a copy of itself: the same queue.
        let twice = [item("a"), item("a")]
        XCTAssertNil(edits.move(from: 0, insertBefore: 2, in: twice, nowMs: 0))
        XCTAssertTrue(edits.isEmpty)
    }

    func testTheHostsStateConfirmsAMove() {
        let old = [item("a"), item("b"), item("c")]
        var edits = QueueEdits()
        edits.move(from: 2, insertBefore: 0, in: old, nowMs: 0)
        let new = [item("c"), item("a"), item("b")]
        XCTAssertEqual(keys(edits, old), QueueRow.rows(new).map(\.key))
        edits.queueChanged(from: old, to: new)
        XCTAssertTrue(edits.isEmpty)
        XCTAssertEqual(keys(edits, new), ["c#0", "a#0", "b#0"])
    }

    func testAMoveAfterARemovalNamesTheHostsIndexes() {
        let queue = [item("a"), item("b"), item("c"), item("d")]
        var edits = QueueEdits()
        edits.remove([QueueRow.rows(queue)[1]], in: queue, nowMs: 0)
        // The host will have [a, c, d]: "d" is at 2 there.
        XCTAssertEqual(edits.move(from: 2, insertBefore: 0, in: queue, nowMs: 0), .edit(.move, index: 2, id: "d", to: 0))
        XCTAssertEqual(keys(edits, queue), ["d#0", "a#0", "c#0"])
        // The removal's state first: the move still shows.
        let removed = [item("a"), item("c"), item("d")]
        edits.queueChanged(from: queue, to: removed)
        XCTAssertFalse(edits.isEmpty)
        XCTAssertEqual(keys(edits, removed), ["d#0", "a#0", "c#0"])
        let moved = [item("d"), item("a"), item("c")]
        edits.queueChanged(from: removed, to: moved)
        XCTAssertTrue(edits.isEmpty)
    }

    func testARemovalAfterAMoveNamesTheHostsIndexes() {
        let queue = [item("a"), item("b"), item("c")]
        var edits = QueueEdits()
        edits.move(from: 2, insertBefore: 0, in: queue, nowMs: 0)
        let rows = edits.visible(queue)
        XCTAssertEqual(edits.wireIndex(of: rows[1], in: queue), 1)
        let removed = edits.remove([rows[2]], in: queue, nowMs: 0)
        XCTAssertEqual(removed.map(\.command), [.edit(.remove, index: 2, id: "b")])
        XCTAssertEqual(keys(edits, queue), ["c#0", "a#0"])
        // Both in one state.
        edits.queueChanged(from: queue, to: [item("c"), item("a")])
        XCTAssertTrue(edits.isEmpty)
    }

    func testAnUnconfirmedMoveRollsBack() {
        let queue = [item("a"), item("b"), item("c")]
        var edits = QueueEdits()
        edits.move(from: 0, insertBefore: 3, in: queue, nowMs: 1_000)
        // A state that does not show it (another song added) keeps it.
        let more = queue + [item("z")]
        edits.queueChanged(from: queue, to: more)
        XCTAssertEqual(keys(edits, more), ["b#0", "c#0", "a#0", "z#0"])
        XCTAssertEqual(edits.nextExpiryMs, 1_000 + QueueEdits.timeoutMs)
        XCTAssertTrue(edits.expire(nowMs: 1_000 + QueueEdits.timeoutMs))
        XCTAssertEqual(keys(edits, more), ["a#0", "b#0", "c#0", "z#0"])
    }

    func testAMovedRowThatIsGoneEndsTheMove() {
        let queue = [item("a"), item("b"), item("c")]
        var edits = QueueEdits()
        edits.move(from: 0, insertBefore: 3, in: queue, nowMs: 0)
        edits.queueChanged(from: queue, to: [item("b"), item("c")])
        XCTAssertTrue(edits.isEmpty)
    }

    func testAnExpiredEditTakesTheLaterOnesWithIt() {
        let queue = [item("a"), item("b"), item("c")]
        var edits = QueueEdits()
        edits.remove([QueueRow.rows(queue)[0]], in: queue, nowMs: 0)
        edits.move(from: 1, insertBefore: 0, in: queue, nowMs: 3_000)
        XCTAssertEqual(keys(edits, queue), ["c#0", "b#0"])
        XCTAssertTrue(edits.expire(nowMs: QueueEdits.timeoutMs))
        XCTAssertTrue(edits.isEmpty)
        XCTAssertEqual(keys(edits, queue), ["a#0", "b#0", "c#0"])
    }

    // MARK: Queue: Undo

    func testUndoBannerText() {
        let one = QueueUndo.removed([QueueUndo.Removed(item: item("b"), at: 1)], nowMs: 2_000)
        XCTAssertEqual(one?.text, "Removed: Tb")
        XCTAssertEqual(one?.untilMs, 2_000 + QueueUndo.durationMs)
        XCTAssertEqual(QueueUndo.durationMs, 10_000)
        let two = QueueUndo.removed([QueueUndo.Removed(item: item("b"), at: 1), QueueUndo.Removed(item: item("c"), at: 2)], nowMs: 0)
        XCTAssertEqual(two?.text, "Removed 2 songs")
        XCTAssertNil(QueueUndo.removed([], nowMs: 0))
        XCTAssertEqual(QueueUndo.cleared([item("a")], nowMs: 0)?.text, "Queue cleared")
        XCTAssertNil(QueueUndo.cleared([], nowMs: 0))
    }

    func testUndoPutsARemovedRowBackAtOnce() {
        let queue = [item("a"), item("b"), item("c")]
        var edits = QueueEdits()
        let removed = edits.remove([QueueRow.rows(queue)[1]], in: queue, nowMs: 0)
        let undo = QueueUndo.removed(removed, nowMs: 0)!
        // Before the host's state: the host's queue will be [a, c], then
        // [a, c, b] after the enqueue, and the move takes "b" from 2 to 1.
        let sent = undo.undo(&edits, queue: queue, nothingLoaded: false, nowMs: 100)
        XCTAssertEqual(sent, [.enqueueEnd([EnqueueTrack(item("b"))]), .edit(.move, index: 2, id: "b", to: 1)])
        XCTAssertEqual(keys(edits, queue), ["a#0", "b#0", "c#0"])
        // One state per message, as the host sends them: the row never moves.
        let removedState = [item("a"), item("c")]
        edits.queueChanged(from: queue, to: removedState)
        XCTAssertEqual(keys(edits, removedState), ["a#0", "b#0", "c#0"])
        let enqueued = [item("a"), item("c"), item("b")]
        edits.queueChanged(from: removedState, to: enqueued)
        XCTAssertEqual(keys(edits, enqueued), ["a#0", "b#0", "c#0"])
        XCTAssertFalse(edits.isEmpty)
        edits.queueChanged(from: enqueued, to: queue)
        XCTAssertTrue(edits.isEmpty)
    }

    func testUndoAfterTheRemovalWasConfirmed() {
        let queue = [item("a"), item("b"), item("c")]
        var edits = QueueEdits()
        let removed = edits.remove([QueueRow.rows(queue)[1]], in: queue, nowMs: 0)
        let after = [item("a"), item("c")]
        edits.queueChanged(from: queue, to: after)
        XCTAssertTrue(edits.isEmpty)
        let sent = QueueUndo.removed(removed, nowMs: 0)!.undo(&edits, queue: after, nothingLoaded: false, nowMs: 0)
        XCTAssertEqual(sent, [.enqueueEnd([EnqueueTrack(item("b"))]), .edit(.move, index: 2, id: "b", to: 1)])
        XCTAssertEqual(keys(edits, after), ["a#0", "b#0", "c#0"])
        // Enqueue and move in one state.
        edits.queueChanged(from: after, to: queue)
        XCTAssertTrue(edits.isEmpty)
    }

    func testUndoOfTheLastRowNeedsNoMove() {
        let queue = [item("a"), item("b")]
        var edits = QueueEdits()
        let removed = edits.remove([QueueRow.rows(queue)[1]], in: queue, nowMs: 0)
        let sent = QueueUndo.removed(removed, nowMs: 0)!.undo(&edits, queue: queue, nothingLoaded: false, nowMs: 0)
        XCTAssertEqual(sent, [.enqueueEnd([EnqueueTrack(item("b"))])])
        XCTAssertEqual(keys(edits, queue), ["a#0", "b#0"])
        edits.queueChanged(from: queue, to: [item("a")])
        edits.queueChanged(from: [item("a")], to: queue)
        XCTAssertTrue(edits.isEmpty)
    }

    func testUndoOfSeveralPutsEachBackInItsPlace() {
        let queue = [item("a"), item("b"), item("c"), item("d")]
        var edits = QueueEdits()
        let rows = edits.visible(queue)
        let removed = edits.remove([rows[1], rows[3]], in: queue, nowMs: 0)
        let sent = QueueUndo.removed(removed, nowMs: 0)!.undo(&edits, queue: queue, nothingLoaded: false, nowMs: 0)
        XCTAssertEqual(sent, [
            .enqueueEnd([EnqueueTrack(item("b"))]), .edit(.move, index: 2, id: "b", to: 1),
            .enqueueEnd([EnqueueTrack(item("d"))]),
        ])
        XCTAssertEqual(keys(edits, queue), ["a#0", "b#0", "c#0", "d#0"])
    }

    func testAnUnconfirmedUndoGoesWithItsRemoval() {
        let queue = [item("a"), item("b")]
        var edits = QueueEdits()
        let removed = edits.remove([QueueRow.rows(queue)[0]], in: queue, nowMs: 0)
        _ = QueueUndo.removed(removed, nowMs: 0)!.undo(&edits, queue: queue, nothingLoaded: false, nowMs: 4_000)
        // Nothing confirmed: both go together, and the queue is as it was.
        XCTAssertTrue(edits.expire(nowMs: QueueEdits.timeoutMs))
        XCTAssertEqual(keys(edits, queue), ["a#0", "b#0"])
    }

    func testUndoWithNothingLoadedDoesNothing() {
        let queue = [item("a"), item("b")]
        var edits = QueueEdits()
        let removed = edits.remove([QueueRow.rows(queue)[0]], in: queue, nowMs: 0)
        edits.queueChanged(from: queue, to: [item("b")])
        let sent = QueueUndo.removed(removed, nowMs: 0)!.undo(&edits, queue: [item("b")], nothingLoaded: true, nowMs: 0)
        XCTAssertEqual(sent, [])
        XCTAssertTrue(edits.isEmpty)
    }

    func testUndoOfAClearQueuesTheSongsAgain() {
        var edits = QueueEdits()
        let songs = [item("a"), HostState.QueueItem(id: "b", title: "Tb", artist: "A", durationMs: 200_000, art: "https://x/b.jpg")]
        let sent = QueueUndo.cleared(songs, nowMs: 0)!.undo(&edits, queue: [], nothingLoaded: true, nowMs: 0)
        XCTAssertEqual(sent, [.enqueueEnd([
            EnqueueTrack(id: "a", title: "Ta", artist: "A", durationMs: 0),
            EnqueueTrack(id: "b", title: "Tb", artist: "A", durationMs: 200_000, art: "https://x/b.jpg"),
        ])])
        XCTAssertTrue(edits.isEmpty)
    }

    // MARK: Search

    func testSearchWording() {
        XCTAssertEqual(SearchWording.nothingFound("money for nothing"), "Nothing found for “money for nothing”")
        XCTAssertEqual(SearchWording.nothingFound("  "), "No results")
        XCTAssertEqual(SearchWording.prompt, "Songs, albums, artists")
        XCTAssertEqual(SearchWording.retryHint, "Try again when there is signal.")
    }

    // MARK: Voice commands

    func testEveryChipIsACommandTheParserKnows() {
        for chip in VoiceCommandChip.all {
            let said = chip.argument == nil ? chip.words : "\(chip.words) brothers in arms"
            XCTAssertNotEqual(CommandParser.parse(said), .unknown, said)
        }
        XCTAssertEqual(Set(VoiceCommandChip.all.map(\.words)).count, VoiceCommandChip.all.count)
    }

    func testChipsCoverEveryAction() {
        var actions: Set<String> = []
        for chip in VoiceCommandChip.all {
            actions.insert(CommandParser.parse(chip.argument == nil ? chip.words : "\(chip.words) x").action)
        }
        XCTAssertEqual(actions, ["play", "pause", "resume", "next", "previous", "volumeUp", "volumeDown",
                                 "nowplaying", "shuffle", "queue", "end"])
    }

    func testPlayChipsNameTheirKind() {
        XCTAssertEqual(CommandParser.parse("play album brothers in arms"), .play(kind: .album, query: "brothers in arms"))
        XCTAssertEqual(CommandParser.parse("play playlist road"), .play(kind: .playlist, query: "road"))
        XCTAssertEqual(CommandParser.parse("play artist dire straits"), .play(kind: .artist, query: "dire straits"))
    }

    func testChipAccessibilityText() {
        XCTAssertEqual(VoiceCommandChip("play", argument: "song").accessibilityText, "play song")
        XCTAssertEqual(VoiceCommandChip("over", note: "ends the talk").accessibilityText, "over, ends the talk")
        XCTAssertEqual(VoiceCommandChip("pause").accessibilityText, "pause")
    }

    // MARK: Images

    func testArtIsAskedForInTheSizeItIsShownAt() {
        XCTAssertEqual(ArtURL.sized("https://lh3.googleusercontent.com/abc=w544-h544", pixels: 144),
                       "https://lh3.googleusercontent.com/abc=w144-h144")
        XCTAssertEqual(ArtURL.sized("https://yt3.googleusercontent.com/abc=w544-h544-l90-rj", pixels: 144),
                       "https://yt3.googleusercontent.com/abc=w144-h144-l90-rj")
        XCTAssertEqual(ArtURL.sized("https://lh3.googleusercontent.com/abc=w60-h60-l90-rj", pixels: 288),
                       "https://lh3.googleusercontent.com/abc=w288-h288-l90-rj")
        XCTAssertEqual(ArtURL.sized("https://yt3.ggpht.com/abc=s88-c-k", pixels: 144),
                       "https://yt3.ggpht.com/abc=s144-c-k")
    }

    func testOtherArtIsLeftAlone() {
        for url in ["https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg",
                    "https://i.ytimg.com/vi/abc-_123/hqdefault.jpg?x=w544-h544",
                    "https://lh3.googleusercontent.com/abc",
                    "https://lh3.googleusercontent.com/abc=rj",
                    "https://lh3.googleusercontent.com/a=b/w544-h544",
                    "https://evil.example/googleusercontent.com=w544-h544",
                    "http://192.168.43.1:8766/art/1.jpg",
                    "not a url",
                    ""] {
            XCTAssertEqual(ArtURL.sized(url, pixels: 144), url)
        }
        XCTAssertEqual(ArtURL.sized("https://lh3.googleusercontent.com/abc=w544-h544", pixels: 0),
                       "https://lh3.googleusercontent.com/abc=w544-h544")
    }

    func testArtPixelsPickTheSmallestBucketThatCovers() {
        XCTAssertEqual(ArtURL.pixels(points: 48, scale: 3), 144)
        XCTAssertEqual(ArtURL.pixels(points: 44, scale: 2), 144)
        XCTAssertEqual(ArtURL.pixels(points: 56, scale: 3), 288)
        XCTAssertEqual(ArtURL.pixels(points: 88, scale: 3), 288)
        XCTAssertEqual(ArtURL.pixels(points: 200, scale: 3), 544)
        XCTAssertEqual(ArtURL.pixels(points: 400, scale: 3), 544)
        XCTAssertEqual(ArtURL.pixels(points: 48, scale: 0), 144)
    }

    // MARK: Lock screen

    private let track = MusicLoad(id: "a", path: "/track/a.m4a", title: "Money for Nothing",
                                  artist: "Dire Straits", album: "Brothers in Arms", durationMs: 506_000)

    func testLockScreenShowsTheTrackOnTheHostsTimeline() {
        let info = LockScreenInfo(track: track, art: "https://x/a=w544-h544", talking: false, riderName: "Pixel 8",
                                  positionMs: 12_000, playing: true)
        XCTAssertEqual(info.title, "Money for Nothing")
        XCTAssertEqual(info.artist, "Dire Straits")
        XCTAssertEqual(info.album, "Brothers in Arms")
        XCTAssertEqual(info.durationMs, 506_000)
        XCTAssertEqual(info.positionMs, 12_000)
        XCTAssertTrue(info.playing)
        XCTAssertEqual(info.art, "https://x/a=w544-h544")
    }

    func testLockScreenDuringATalk() {
        let info = LockScreenInfo(track: track, art: nil, talking: true, riderName: "Pixel 8",
                                  positionMs: 12_000, playing: true)
        XCTAssertEqual(info.title, "Money for Nothing")
        XCTAssertEqual(info.artist, "Talking with Pixel 8")
        XCTAssertFalse(info.playing, "the music is held while a talk is open")
        XCTAssertEqual(LockScreenInfo(track: nil, art: nil, talking: true, riderName: " ",
                                      positionMs: 0, playing: false).artist, "Talking")
    }

    func testLockScreenWithNoTrack() {
        let info = LockScreenInfo(track: nil, art: "https://x/old", talking: false, riderName: "Pixel 8",
                                  positionMs: 99_000, playing: true)
        XCTAssertEqual(info.title, "Motoparty")
        XCTAssertEqual(info.artist, "")
        XCTAssertNil(info.durationMs)
        XCTAssertEqual(info.positionMs, 0)
        XCTAssertFalse(info.playing)
        XCTAssertNil(info.art)
    }

    func testLockScreenLeavesOutAnUnknownDuration() {
        var unknown = track
        unknown.durationMs = 0
        XCTAssertNil(LockScreenInfo(track: unknown, art: nil, talking: false, riderName: "", positionMs: -5,
                                    playing: false).durationMs)
        XCTAssertEqual(LockScreenInfo(track: unknown, art: nil, talking: false, riderName: "", positionMs: -5,
                                      playing: false).positionMs, 0)
    }
}
