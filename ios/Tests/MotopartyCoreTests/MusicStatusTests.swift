import XCTest
@testable import MotopartyCore

/// The line under now playing: "Loading…" through PROTOCOL.md "Music flow"
/// steps 1–3, "Paused for talk" while a talk holds the music.
final class MusicStatusTests: XCTestCase {
    func testNothingLoadedShowsNothing() {
        var t = MusicStatusTracker()
        XCTAssertEqual(t.status, .none)
        t.load("a") // a prefetch of a track that is not current
        XCTAssertEqual(t.status, .none)
        t.talkOpened()
        XCTAssertEqual(t.status, .none)
    }

    func testTrackStartIsLoadingUntilPlay() {
        var t = MusicStatusTracker()
        // state names the new track (paused at 0), then music.load, download, ready, play.
        t.load("a")
        t.setCurrent("a")
        t.hostState(id: "a", playing: false)
        XCTAssertEqual(t.status, .loading)
        t.downloadStarted("a")
        XCTAssertEqual(t.status, .loading)
        t.downloadFinished("a", ok: true)
        XCTAssertEqual(t.status, .loading, "ready sent, music.play not yet here")
        t.play("a")
        XCTAssertEqual(t.status, .none)
        t.hostState(id: "a", playing: true)
        XCTAssertEqual(t.status, .none)
    }

    func testLoadAfterStateAlsoLoads() {
        var t = MusicStatusTracker()
        t.setCurrent("a")
        t.hostState(id: "a", playing: false)
        XCTAssertEqual(t.status, .none, "plainly paused")
        t.load("a")
        XCTAssertEqual(t.status, .loading)
    }

    func testPrefetchedNextTrackOnlyCountsOnceCurrent() {
        var t = MusicStatusTracker()
        t.setCurrent("a")
        t.play("a")
        t.load("b")
        t.downloadStarted("b")
        XCTAssertEqual(t.status, .none, "the next track downloading is not the current one")
        t.downloadFinished("b", ok: true)
        t.setCurrent("b")
        t.hostState(id: "b", playing: false)
        XCTAssertEqual(t.status, .loading)
        t.play("b")
        XCTAssertEqual(t.status, .none)
    }

    func testJoinMidTrackIsLoadingWhileDownloading() {
        var t = MusicStatusTracker()
        // state says playing: the anchor is known, only the file is missing.
        t.load("a")
        t.setCurrent("a")
        t.hostState(id: "a", playing: true)
        XCTAssertEqual(t.status, .none)
        t.downloadStarted("a")
        XCTAssertEqual(t.status, .loading)
        t.downloadFinished("a", ok: true)
        XCTAssertEqual(t.status, .none)
    }

    func testFailedDownloadStopsLoading() {
        var t = MusicStatusTracker()
        t.load("a")
        t.setCurrent("a")
        t.downloadStarted("a")
        t.downloadFinished("a", ok: false)
        XCTAssertEqual(t.status, .none)
    }

    func testTalkHoldsPlayingMusic() {
        var t = MusicStatusTracker()
        t.setCurrent("a")
        t.play("a")
        t.talkOpened()
        // The host's state for the talk says paused; still held.
        t.hostState(id: "a", playing: false)
        XCTAssertEqual(t.status, .pausedForTalk)
        t.talkClosed()
        XCTAssertEqual(t.status, .none)
        t.play("a")
        XCTAssertEqual(t.status, .none)
    }

    func testTalkOverPlainlyPausedMusicShowsNothing() {
        var t = MusicStatusTracker()
        t.setCurrent("a")
        t.play("a")
        t.pause("a")
        t.talkOpened()
        XCTAssertEqual(t.status, .none)
    }

    func testSpokenPauseInTalkReleasesTheHold() {
        var t = MusicStatusTracker()
        t.setCurrent("a")
        t.play("a")
        t.talkOpened()
        t.pause("a")
        XCTAssertEqual(t.status, .none)
    }

    func testTrackLoadedDuringTalkIsParked() {
        var t = MusicStatusTracker()
        t.talkOpened()
        t.load("a")
        t.setCurrent("a")
        t.hostState(id: "a", playing: false)
        XCTAssertEqual(t.status, .pausedForTalk)
        t.downloadStarted("a")
        XCTAssertEqual(t.status, .loading, "the download is the longer wait")
        t.downloadFinished("a", ok: true)
        XCTAssertEqual(t.status, .pausedForTalk)
        t.talkClosed()
        XCTAssertEqual(t.status, .loading)
        t.play("a")
        XCTAssertEqual(t.status, .none)
    }

    func testTalkOpenedTwiceKeepsTheHold() {
        var t = MusicStatusTracker()
        t.setCurrent("a")
        t.play("a")
        t.talkOpened()
        t.hostState(id: "a", playing: false)
        t.talkOpened() // state{talk:true} after talk.open
        XCTAssertEqual(t.status, .pausedForTalk)
    }

    func testStopAndLinkLossClear() {
        var t = MusicStatusTracker()
        t.load("a")
        t.setCurrent("a")
        t.stop()
        XCTAssertEqual(t.status, .none)
        t.load("a")
        t.linkLost()
        XCTAssertEqual(t.status, .none)
        t.setCurrent(nil)
        XCTAssertEqual(t.status, .none)
    }

    func testText() {
        XCTAssertNil(MusicStatus.none.text)
        XCTAssertEqual(MusicStatus.loading.text, "Downloading song…")
        XCTAssertEqual(MusicStatus.pausedForTalk.text, "Paused for talk — plays when the talk ends")
        XCTAssertEqual(MusicStatus.searching(#"Searching song "moby""#).text, #"Searching song "moby"…"#)
        XCTAssertEqual([MusicStatus.none, .loading, .pausedForTalk, .searching("x")].map(\.spins), [false, true, false, true])
    }

    /// `state.busy`: shown with nothing loaded too, after the track's own
    /// reasons (as on the Pixel), and gone with a state without it or a link loss.
    func testBusyShowsTheHostSearch() {
        var t = MusicStatusTracker()
        t.hostBusy("Searching song \"moby\"")
        XCTAssertEqual(t.status, .searching("Searching song \"moby\""))
        t.load("a")
        t.setCurrent("a")
        XCTAssertEqual(t.status, .loading, "the track's own status first")
        t.play("a")
        XCTAssertEqual(t.status, .searching("Searching song \"moby\""))
        t.hostBusy(nil)
        XCTAssertEqual(t.status, .none)
        t.hostBusy("")
        XCTAssertEqual(t.status, .none)
        t.hostBusy("Searching album \"x\"")
        t.linkLost()
        XCTAssertEqual(t.status, .none)
    }
}
