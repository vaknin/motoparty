import XCTest
@testable import MotopartyCore

/// What the Live Activity shows and when ActivityKit is called.
final class LiveActivityTests: XCTestCase {
    private let track = MusicLoad(id: "a", path: "/track/a.m4a", title: "Sultans of Swing", artist: "Dire Straits",
                                  durationMs: 348_000)
    private let since = Date(timeIntervalSince1970: 1_790_000_000)

    private func state(track: MusicLoad? = nil, playing: Bool = false, talkOpen: Bool = false, talkLive: Bool = false,
                       since: Date? = nil, rider: String = "Pixel 8",
                       link: LiveActivityState.Link = .linked) -> LiveActivityState? {
        LiveActivityState(track: track, playing: playing, talkOpen: talkOpen, talkLive: talkLive, talkLiveSince: since,
                          riderName: rider, link: link)
    }

    // MARK: State

    func testNothingBeforeTheFirstLink() {
        XCTAssertNil(state(link: .none))
        // A track or a talk is shown whatever the link.
        XCTAssertEqual(state(track: track, link: .none)?.mode, .paused)
        XCTAssertEqual(state(talkOpen: true, link: .none)?.mode, .connecting)
    }

    func testTrackPlayingAndPaused() {
        XCTAssertEqual(state(track: track, playing: true),
                       LiveActivityState(title: "Sultans of Swing", detail: "Dire Straits", mode: .playing))
        XCTAssertEqual(state(track: track, playing: false),
                       LiveActivityState(title: "Sultans of Swing", detail: "Dire Straits", mode: .paused))
        // The music goes on along its anchor while the link is looked for.
        XCTAssertEqual(state(track: track, playing: true, link: .lost)?.detail, "Dire Straits")
    }

    func testTalkConnectingThenLive() {
        XCTAssertEqual(state(track: track, playing: true, talkOpen: true),
                       LiveActivityState(title: "Sultans of Swing", detail: "Connecting…", mode: .connecting))
        XCTAssertEqual(state(track: track, talkOpen: true, talkLive: true, since: since),
                       LiveActivityState(title: "Sultans of Swing", detail: "Talking with Pixel 8", mode: .talking,
                                         talkSince: since))
        XCTAssertEqual(state(talkOpen: true, talkLive: true, since: since, rider: "  "),
                       LiveActivityState(title: "Motoparty", detail: "Talking", mode: .talking, talkSince: since))
        // A stale "live" without a talk is nothing, and carries no clock.
        XCTAssertEqual(state(track: track, playing: true, talkLive: true, since: since),
                       LiveActivityState(title: "Sultans of Swing", detail: "Dire Straits", mode: .playing))
    }

    func testIdleSaysHowTheLinkIs() {
        XCTAssertEqual(state(link: .linked),
                       LiveActivityState(title: "Motoparty", detail: "Connected to Pixel 8", mode: .idle))
        XCTAssertEqual(state(link: .lost),
                       LiveActivityState(title: "Motoparty", detail: "Looking for Pixel 8…", mode: .idle))
        XCTAssertEqual(state(rider: "", link: .linked)?.detail, "Connected to the Pixel")
        XCTAssertEqual(state(rider: "", link: .lost)?.detail, "Looking for the Pixel…")
    }

    func testStateSurvivesTheTripToTheExtension() throws {
        let sent = try XCTUnwrap(state(track: track, talkOpen: true, talkLive: true, since: since))
        let received = try JSONDecoder().decode(LiveActivityState.self, from: JSONEncoder().encode(sent))
        XCTAssertEqual(received, sent)
    }

    // MARK: Tracker

    func testStartsOnceThenOnlyUpdatesOnAChange() throws {
        var tracker = LiveActivityTracker()
        let playing = try XCTUnwrap(state(track: track, playing: true))
        let paused = try XCTUnwrap(state(track: track, playing: false))
        XCTAssertEqual(tracker.step(wanted: nil, canStart: true), .none)
        XCTAssertEqual(tracker.step(wanted: playing, canStart: true), .start(playing))
        // The model asks on every state message: nothing for the same state.
        XCTAssertEqual(tracker.step(wanted: playing, canStart: true), .none)
        XCTAssertEqual(tracker.step(wanted: playing, canStart: false), .none)
        XCTAssertEqual(tracker.step(wanted: paused, canStart: true), .update(paused))
        XCTAssertEqual(tracker.step(wanted: nil, canStart: true), .end)
        XCTAssertEqual(tracker.step(wanted: nil, canStart: true), .none)
    }

    func testStartsOnlyInFrontButUpdatesAnywhere() throws {
        var tracker = LiveActivityTracker()
        let playing = try XCTUnwrap(state(track: track, playing: true))
        let talking = try XCTUnwrap(state(track: track, talkOpen: true, talkLive: true, since: since))
        XCTAssertEqual(tracker.step(wanted: playing, canStart: false), .none)
        XCTAssertNil(tracker.shown)
        // In front again: the state wanted by then is what starts.
        XCTAssertEqual(tracker.step(wanted: talking, canStart: true), .start(talking))
        XCTAssertEqual(tracker.step(wanted: playing, canStart: false), .update(playing))
    }

    func testCanStartIsOnlyAskedForAStart() throws {
        var tracker = LiveActivityTracker()
        let playing = try XCTUnwrap(state(track: track, playing: true))
        let paused = try XCTUnwrap(state(track: track, playing: false))
        var asked = 0
        func canStart() -> Bool { asked += 1; return true }
        XCTAssertEqual(tracker.step(wanted: nil, canStart: canStart()), .none)
        XCTAssertEqual(asked, 0)
        XCTAssertEqual(tracker.step(wanted: playing, canStart: canStart()), .start(playing))
        XCTAssertEqual(asked, 1)
        XCTAssertEqual(tracker.step(wanted: paused, canStart: canStart()), .update(paused))
        XCTAssertEqual(tracker.step(wanted: nil, canStart: canStart()), .end)
        XCTAssertEqual(asked, 1)
    }

    func testARefusedStartIsAskedAgain() throws {
        var tracker = LiveActivityTracker()
        let playing = try XCTUnwrap(state(track: track, playing: true))
        XCTAssertEqual(tracker.step(wanted: playing, canStart: true), .start(playing))
        tracker.startFailed()
        XCTAssertEqual(tracker.step(wanted: playing, canStart: true), .start(playing))
    }

    func testSwipedAwayStaysAwayUntilTheAppIsOpened() throws {
        var tracker = LiveActivityTracker()
        let playing = try XCTUnwrap(state(track: track, playing: true))
        let paused = try XCTUnwrap(state(track: track, playing: false))
        XCTAssertEqual(tracker.step(wanted: playing, canStart: true), .start(playing))
        tracker.lost(byUser: true)
        XCTAssertEqual(tracker.step(wanted: paused, canStart: true), .none)
        tracker.foregrounded()
        XCTAssertEqual(tracker.step(wanted: paused, canStart: true), .start(paused))
        // Nothing to show also forgets the swipe.
        tracker.lost(byUser: true)
        XCTAssertEqual(tracker.step(wanted: nil, canStart: true), .none)
        XCTAssertEqual(tracker.step(wanted: playing, canStart: true), .start(playing))
    }

    func testEndedByTheSystemComesBackOnTheNextChange() throws {
        var tracker = LiveActivityTracker()
        let playing = try XCTUnwrap(state(track: track, playing: true))
        XCTAssertEqual(tracker.step(wanted: playing, canStart: true), .start(playing))
        tracker.lost(byUser: false)
        XCTAssertEqual(tracker.step(wanted: playing, canStart: false), .none)
        XCTAssertEqual(tracker.step(wanted: playing, canStart: true), .start(playing))
    }
}
