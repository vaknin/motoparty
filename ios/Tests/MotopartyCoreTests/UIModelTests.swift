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

    func testRemovedRowIsHiddenAtOnce() {
        let queue = [item("a"), item("b"), item("c")]
        var removals = QueueRemovals()
        XCTAssertEqual(removals.visible(queue).map(\.key), ["a#0", "b#0", "c#0"])
        removals.remove(QueueRow.rows(queue)[1], nowMs: 1_000)
        XCTAssertFalse(removals.isEmpty)
        XCTAssertEqual(removals.visible(queue).map(\.key), ["a#0", "c#0"])
        // The same row twice is one removal.
        removals.remove(QueueRow.rows(queue)[1], nowMs: 1_500)
        XCTAssertEqual(removals.nextExpiryMs, 1_000 + QueueRemovals.timeoutMs)
    }

    func testWireIndexCountsRemovalsInFlightAbove() {
        let queue = [item("a"), item("b"), item("c"), item("d")]
        let rows = QueueRow.rows(queue)
        var removals = QueueRemovals()
        XCTAssertEqual(removals.wireIndex(of: rows[2], in: queue), 2)
        removals.remove(rows[1], nowMs: 0)
        // The host will have dropped "b" by the time it reads the next edit.
        XCTAssertEqual(removals.wireIndex(of: rows[0], in: queue), 0)
        XCTAssertEqual(removals.wireIndex(of: rows[2], in: queue), 1)
        XCTAssertEqual(removals.wireIndex(of: rows[3], in: queue), 2)
        removals.remove(rows[2], nowMs: 0)
        XCTAssertEqual(removals.wireIndex(of: rows[3], in: queue), 1)
    }

    func testTheHostsStateConfirmsARemoval() {
        let old = [item("a"), item("b"), item("c")]
        var removals = QueueRemovals()
        removals.remove(QueueRow.rows(old)[1], nowMs: 0)
        let new = [item("a"), item("c")]
        removals.queueChanged(from: old, to: new)
        XCTAssertTrue(removals.isEmpty)
        XCTAssertEqual(removals.visible(new).map(\.key), ["a#0", "c#0"])
    }

    func testAnotherChangeKeepsTheRowHidden() {
        // The head of the queue started playing before the host read the
        // remove: the removed row must not come back for a moment.
        let old = [item("a"), item("b"), item("c")]
        var removals = QueueRemovals()
        removals.remove(QueueRow.rows(old)[1], nowMs: 0)
        let headGone = [item("b"), item("c")]
        removals.queueChanged(from: old, to: headGone)
        XCTAssertFalse(removals.isEmpty)
        XCTAssertEqual(removals.visible(headGone).map(\.key), ["c#0"])
        XCTAssertEqual(removals.wireIndex(of: QueueRow.rows(headGone)[1], in: headGone), 0)
        removals.queueChanged(from: headGone, to: [item("c")])
        XCTAssertTrue(removals.isEmpty)
    }

    func testRemovingOneOfTwoCopiesShowsTheOther() {
        let old = [item("a"), item("x"), item("a")]
        var removals = QueueRemovals()
        removals.remove(QueueRow.rows(old)[2], nowMs: 0)
        XCTAssertEqual(removals.visible(old).map(\.key), ["a#0", "x#0"])
        let new = [item("a"), item("x")]
        removals.queueChanged(from: old, to: new)
        XCTAssertTrue(removals.isEmpty)
        XCTAssertEqual(removals.visible(new).map(\.key), ["a#0", "x#0"])
    }

    func testAnUnconfirmedRemovalComesBack() {
        let queue = [item("a"), item("b")]
        var removals = QueueRemovals()
        removals.remove(QueueRow.rows(queue)[0], nowMs: 1_000)
        XCTAssertFalse(removals.expire(nowMs: 1_000 + QueueRemovals.timeoutMs - 1))
        XCTAssertEqual(removals.visible(queue).count, 1)
        XCTAssertTrue(removals.expire(nowMs: 1_000 + QueueRemovals.timeoutMs))
        XCTAssertEqual(removals.visible(queue).count, 2)
        XCTAssertNil(removals.nextExpiryMs)
    }

    func testAClearedQueueDropsEveryRemoval() {
        let old = [item("a"), item("b")]
        var removals = QueueRemovals()
        removals.remove(QueueRow.rows(old)[0], nowMs: 0)
        removals.queueChanged(from: old, to: [])
        XCTAssertTrue(removals.isEmpty)
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
                                 "nowplaying", "shuffle", "end"])
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
