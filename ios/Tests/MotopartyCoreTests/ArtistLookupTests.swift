import Foundation
import XCTest
@testable import MotopartyCore

final class ArtistLookupTests: XCTestCase {
    private func artist(_ name: String, _ ref: String = "UC1") -> ResultItem {
        ResultItem(ref: ref, title: name, artist: "")
    }

    private func album(_ title: String, _ artist: String, _ ref: String) -> ResultItem {
        ResultItem(ref: ref, title: title, artist: artist, count: 10)
    }

    func testFirstArtistOfACredit() {
        XCTAssertEqual(ArtistNames.first("Queen & David Bowie"), "Queen")
        XCTAssertEqual(ArtistNames.first("Calvin Harris feat. Rihanna"), "Calvin Harris")
        XCTAssertEqual(ArtistNames.first("Calvin Harris Feat Rihanna"), "Calvin Harris")
        XCTAssertEqual(ArtistNames.first("Eminem ft. Dido"), "Eminem")
        XCTAssertEqual(ArtistNames.first("A, B & C"), "A")
        XCTAssertEqual(ArtistNames.first("Simon & Garfunkel, X"), "Simon")
        XCTAssertEqual(ArtistNames.first("  Pink Floyd "), "Pink Floyd")
        XCTAssertEqual(ArtistNames.first(""), "")
        // Not a separator inside a word.
        XCTAssertEqual(ArtistNames.first("Daft Punk"), "Daft Punk")
        XCTAssertEqual(ArtistNames.first("Lefty Ftwo"), "Lefty Ftwo")
    }

    func testNormalizedNames() {
        XCTAssertEqual(ArtistNames.normalized("AC/DC"), "acdc")
        XCTAssertEqual(ArtistNames.normalized("Beyoncé"), "beyonce")
        XCTAssertEqual(ArtistNames.normalized("The  Beatles!"), "thebeatles")
        XCTAssertEqual(ArtistNames.normalized("עומר אדם"), "עומראדם")
    }

    func testArtistQueryAndPick() {
        let lookup = RideLookup.artist(credit: "Queen & David Bowie")
        XCTAssertEqual(lookup.kind, .artists)
        XCTAssertEqual(lookup.query, "Queen")
        // The exact name wins over a higher hit.
        let hits = [artist("Queen Naija", "UCa"), artist("QUEEN", "UCb"), artist("Queen", "UCc")]
        XCTAssertEqual(lookup.pick(hits), .artist(hits[1]))
        // No exact name: the top hit.
        XCTAssertEqual(lookup.pick([artist("Queens of the Stone Age", "UCq")]), .artist(artist("Queens of the Stone Age", "UCq")))
        XCTAssertNil(lookup.pick([]))
    }

    func testAlbumQueryAndPick() {
        let lookup = RideLookup.album(title: "Greatest Hits", credit: "Queen feat. Someone")
        XCTAssertEqual(lookup.kind, .albums)
        XCTAssertEqual(lookup.query, "Queen Greatest Hits")
        XCTAssertEqual(RideLookup.album(title: "Greatest Hits", credit: "").query, "Greatest Hits")

        let other = album("Greatest Hits", "ABBA", "OL1")
        let queen = album("Greatest Hits", "Queen", "OL2")
        let similar = album("Greatest Hits II", "Queen", "OL3")
        // Title and artist both match.
        XCTAssertEqual(lookup.pick([similar, other, queen]), .collection(queen))
        // Only the title matches: the first such.
        XCTAssertEqual(lookup.pick([similar, other]), .collection(other))
        // Nothing matches: the top hit.
        XCTAssertEqual(lookup.pick([similar]), .collection(similar))
        XCTAssertNil(lookup.pick([]))
    }
}

final class MusicHoldTests: XCTestCase {
    func testAnyHoldOrTalkKeepsThePlayerSilent() {
        XCTAssertTrue(MusicHold().mayPlay(talkOpen: false))
        XCTAssertFalse(MusicHold().mayPlay(talkOpen: true))
        XCTAssertFalse(MusicHold(route: true).mayPlay(talkOpen: false))
        XCTAssertFalse(MusicHold(call: true).mayPlay(talkOpen: false))
        XCTAssertFalse(MusicHold(route: true, call: true).mayPlay(talkOpen: false))
    }

    func testPlayingHereOnlyWithoutAHold() {
        XCTAssertTrue(MusicHold().playingHere(hostPlaying: true))
        XCTAssertFalse(MusicHold().playingHere(hostPlaying: false))
        XCTAssertFalse(MusicHold(call: true).playingHere(hostPlaying: true))
        XCTAssertFalse(MusicHold(route: true).playingHere(hostPlaying: true))
    }

    func testRideLine() {
        XCTAssertNil(MusicHold().line)
        XCTAssertEqual(MusicHold(call: true).line, "Music held during your call")
        XCTAssertEqual(MusicHold(route: true).line, MusicHold.routeLine)
        XCTAssertEqual(MusicHold(route: true, call: true).line, MusicHold.callLine)
    }

    /// The sequence `AppModel` runs: the call begins, a `state` (or
    /// `music.play`) arrives and asks to play, the call ends during a talk
    /// (the early-return branch), then the talk closes.
    func testCallSequence() {
        var hold = MusicHold()
        hold.call = true                                  // interruptionBegan
        XCTAssertFalse(hold.mayPlay(talkOpen: false))     // a state mid-call: no restart
        // interruptionEnded clears it in every branch, the talk one too…
        hold.call = false
        XCTAssertFalse(hold.mayPlay(talkOpen: true))      // …but the open talk still holds it
        XCTAssertTrue(hold.mayPlay(talkOpen: false))      // talk closed: rejoin live
        // A headset lost during the call stays held after it.
        hold = MusicHold(route: true, call: true)
        hold.call = false
        XCTAssertFalse(hold.mayPlay(talkOpen: false))
    }
}
