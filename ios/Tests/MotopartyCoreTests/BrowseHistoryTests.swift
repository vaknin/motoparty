import Foundation
import XCTest
@testable import MotopartyCore

final class BrowseHistoryTests: XCTestCase {
    private func track(_ id: String, _ title: String = "T") -> BrowseHistory.Track {
        BrowseHistory.Track(id: id, title: title, artist: "A", durationMs: 1_000)
    }

    func testSearchesNewestFirstDeduplicatedWithKind() {
        var h = BrowseHistory()
        XCTAssertTrue(h.searched(.songs, query: "Queen"))
        XCTAssertTrue(h.searched(.albums, query: " abbey road "))
        XCTAssertEqual(h.searches, [.init(kind: .albums, query: "abbey road"), .init(kind: .songs, query: "Queen")])
        // The same query again (any case) moves up and takes the newest kind.
        XCTAssertTrue(h.searched(.playlists, query: "queen"))
        XCTAssertEqual(h.searches, [.init(kind: .playlists, query: "queen"), .init(kind: .albums, query: "abbey road")])
        // Unchanged newest entry, and empty queries: nothing to save.
        XCTAssertFalse(h.searched(.playlists, query: "queen"))
        XCTAssertFalse(h.searched(.songs, query: "   "))
        h.clearSearches()
        XCTAssertEqual(h.searches, [])
    }

    func testSearchesKeepTen() {
        var h = BrowseHistory()
        for i in 0..<15 { h.searched(.songs, query: "q\(i)") }
        XCTAssertEqual(h.searches.count, BrowseHistory.maxSearches)
        XCTAssertEqual(h.searches.first?.query, "q14")
        XCTAssertEqual(h.searches.last?.query, "q5")
    }

    func testPlayedDistinctNewestFirstKeepsTwenty() {
        var h = BrowseHistory()
        XCTAssertTrue(h.sawCurrent(track("a")))
        // Every state repeats the current track: no change.
        XCTAssertFalse(h.sawCurrent(track("a")))
        h.sawCurrent(track("b"))
        h.sawCurrent(track("a", "T2"))
        XCTAssertEqual(h.played.map(\.id), ["a", "b"])
        XCTAssertEqual(h.played.first?.title, "T2")
        XCTAssertFalse(h.sawCurrent(track("")))
        for i in 0..<30 { h.sawCurrent(track("t\(i)")) }
        XCTAssertEqual(h.played.count, BrowseHistory.maxPlayed)
        XCTAssertEqual(h.played.first?.id, "t29")
        XCTAssertEqual(h.played.last?.id, "t10")
    }

    func testTrackFromStateAndBackToEnqueue() {
        let music = HostState.Music(id: "dQw4w9WgXcQ", title: "Song", artist: "Band", playing: true,
                                    positionMs: 0, atHostTimeMs: 0, durationMs: 212_000, art: "http://x/a.jpg")
        let t = BrowseHistory.Track(music)
        XCTAssertEqual(t.enqueueTrack, EnqueueTrack(id: "dQw4w9WgXcQ", title: "Song", artist: "Band",
                                                    durationMs: 212_000, art: "http://x/a.jpg"))
    }

    func testPersistenceRoundTrip() throws {
        var h = BrowseHistory()
        h.searched(.albums, query: "Thriller")
        h.sawCurrent(track("a"))
        XCTAssertEqual(BrowseHistory.decoded(h.encoded()), h)
        XCTAssertEqual(BrowseHistory.decoded(nil), BrowseHistory())
        XCTAssertEqual(BrowseHistory.decoded(Data("not json".utf8)), BrowseHistory())

        // UserDefaults, as the app stores it.
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "BrowseHistoryTests"))
        defer { defaults.removePersistentDomain(forName: "BrowseHistoryTests") }
        defaults.set(h.encoded(), forKey: "browseHistory")
        XCTAssertEqual(BrowseHistory.decoded(defaults.data(forKey: "browseHistory")), h)
    }
}
