import Foundation

/// The Search tab's history, local to this phone (not in PROTOCOL.md): the
/// last searches, and the tracks the host last had as its current track.
/// Pure and `Codable`; the app keeps it as JSON in UserDefaults.
public struct BrowseHistory: Codable, Equatable, Sendable {
    public struct Search: Codable, Equatable, Hashable, Sendable {
        public var kind: SearchKind
        public var query: String
        public init(kind: SearchKind, query: String) { self.kind = kind; self.query = query }
    }

    /// A track as `state.music` named it: enough to send it back in a
    /// `music.enqueue`.
    public struct Track: Codable, Equatable, Sendable {
        public var id: String
        public var title: String
        public var artist: String
        public var durationMs: Int64
        public var art: String?

        public init(id: String, title: String, artist: String, durationMs: Int64, art: String? = nil) {
            self.id = id; self.title = title; self.artist = artist
            self.durationMs = durationMs; self.art = art
        }

        public init(_ music: HostState.Music) {
            self.init(id: music.id, title: music.title, artist: music.artist,
                      durationMs: music.durationMs, art: music.art)
        }

        public var enqueueTrack: EnqueueTrack {
            EnqueueTrack(id: id, title: title, artist: artist, durationMs: durationMs, art: art)
        }
    }

    public static let maxSearches = 10
    public static let maxPlayed = 20

    /// Newest first; one entry per query (ignoring case), with its latest kind.
    public private(set) var searches: [Search] = []
    /// Newest first; one entry per track id.
    public private(set) var played: [Track] = []

    public init() {}

    private enum CodingKeys: String, CodingKey { case searches, played }

    /// A search whose kind this build does not know (one a newer build saved
    /// before a downgrade, 2026-10-02) is dropped on its own; the rest of the
    /// history stays.
    private struct StoredSearch: Decodable {
        let search: Search?
        init(from decoder: any Decoder) throws { search = try? Search(from: decoder) }
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        searches = try c.decode([StoredSearch].self, forKey: .searches).compactMap(\.search)
        played = try c.decode([Track].self, forKey: .played)
    }

    /// A search was sent. Returns whether anything changed.
    @discardableResult
    public mutating func searched(_ kind: SearchKind, query: String) -> Bool {
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return false }
        let entry = Search(kind: kind, query: query)
        if searches.first == entry { return false }
        searches.removeAll { $0.query.lowercased() == query.lowercased() }
        searches.insert(entry, at: 0)
        if searches.count > Self.maxSearches { searches.removeLast(searches.count - Self.maxSearches) }
        return true
    }

    public mutating func clearSearches() {
        searches = []
    }

    /// `state.music` named this track as current (every `state` does, so an
    /// unchanged newest entry is a no-op). Returns whether anything changed.
    @discardableResult
    public mutating func sawCurrent(_ track: Track) -> Bool {
        guard !track.id.isEmpty else { return false }
        if played.first == track { return false }
        played.removeAll { $0.id == track.id }
        played.insert(track, at: 0)
        if played.count > Self.maxPlayed { played.removeLast(played.count - Self.maxPlayed) }
        return true
    }

    // MARK: - Persistence

    public func encoded() -> Data? {
        try? JSONEncoder().encode(self)
    }

    /// Empty history for missing or unreadable data (a newer or older format).
    public static func decoded(_ data: Data?) -> BrowseHistory {
        guard let data, var history = try? JSONDecoder().decode(BrowseHistory.self, from: data) else {
            return BrowseHistory()
        }
        // Stored by another build with other limits: keep within these.
        history.searches = Array(history.searches.prefix(maxSearches))
        history.played = Array(history.played.prefix(maxPlayed))
        return history
    }
}
