import Foundation

/// A page the Search tab pushes (2026-10-02): an album or playlist's songs,
/// or an artist's page (PROTOCOL.md "Browsing" step 2a). The navigation
/// path holds these, so the Ride screen can push one too.
public enum BrowseTarget: Hashable, Sendable {
    case collection(ResultItem)
    case artist(ResultItem)
}

/// A tap on the playing song's artist or album on the Ride screen
/// (2026-10-02): which search to run, and which hit to open. The Pixel's
/// `RideTab` taps follow the same artist rule.
public enum RideLookup: Equatable, Sendable {
    /// The artist credit as `music.load` gives it ("A, B & C feat. D").
    case artist(credit: String)
    /// The album's title and the track's artist credit.
    case album(title: String, credit: String)

    public var kind: SearchKind {
        switch self {
        case .artist: .artists
        case .album: .albums
        }
    }

    /// The words searched: the whole credit, so a duo such as "Simon &
    /// Garfunkel" is found as itself (the host's `Catalog.findArtist` also
    /// starts with the whole credit, 2026-10-02); for an album, the first
    /// artist's name and the album's title (the name narrows a common title
    /// such as "Greatest Hits").
    public var query: String {
        switch self {
        case .artist(let credit):
            return credit.trimmingCharacters(in: .whitespacesAndNewlines)
        case .album(let title, let credit):
            let name = ArtistNames.first(credit)
            return name.isEmpty ? title : "\(name) \(title)"
        }
    }

    /// The hit to open, nil with no hits.
    /// - Artist: the first hit whose name equals the whole credit, else the
    ///   first whose name equals the first artist's (`ArtistNames.normalized`),
    ///   else the top hit.
    /// - Album: the first hit whose title equals the album's and whose
    ///   (first) artist equals the track's, else the first whose title
    ///   equals, else the top hit.
    public func pick(_ hits: [ResultItem]) -> BrowseTarget? {
        guard let top = hits.first else { return nil }
        switch self {
        case .artist(let credit):
            let whole = ArtistNames.normalized(credit)
            let name = ArtistNames.normalized(ArtistNames.first(credit))
            let hit = hits.first { ArtistNames.normalized($0.title) == whole }
                ?? hits.first { ArtistNames.normalized($0.title) == name } ?? top
            return .artist(hit)
        case .album(let title, let credit):
            let album = ArtistNames.normalized(title)
            let artist = ArtistNames.normalized(ArtistNames.first(credit))
            let titled = hits.filter { ArtistNames.normalized($0.title) == album }
            let hit = titled.first { ArtistNames.normalized(ArtistNames.first($0.artist)) == artist }
                ?? titled.first ?? top
            return .collection(hit)
        }
    }
}

/// Artist credits as YouTube Music writes them.
public enum ArtistNames {
    /// What separates artists in a credit. " feat." and " ft." also without
    /// the dot, any case.
    private static let separators = [", ", " & ", " feat. ", " feat ", " ft. ", " ft ", " featuring "]

    /// The first artist of a credit: "Queen & David Bowie" → "Queen",
    /// "Calvin Harris feat. Rihanna" → "Calvin Harris". Trimmed.
    public static func first(_ credit: String) -> String {
        var name = credit
        for separator in separators {
            if let range = name.range(of: separator, options: .caseInsensitive) {
                name = String(name[..<range.lowerBound])
            }
        }
        return name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// For comparing names: case and accents folded, everything but letters
    /// and digits dropped ("AC/DC" = "acdc", "Beyoncé" = "beyonce"). Letters
    /// of any script stay, so Hebrew names compare too.
    public static func normalized(_ text: String) -> String {
        let folded = text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
        return String(folded.unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) }.map(Character.init))
    }
}
