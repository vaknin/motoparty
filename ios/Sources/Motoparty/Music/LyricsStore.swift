#if os(iOS)
import Foundation
import MotopartyCore

/// Synced lyrics from the host (`GET http://<host>:<httpPort>/lyrics/<id>.json`,
/// PROTOCOL.md "Tracks", "Lyrics"), only while this phone's lyrics toggle is
/// on: for the current track and the one the last `music.load` prefetched.
/// Kept in memory per id; a 503 is asked again by `LyricsFetch`'s rule.
/// Nothing is fetched while the toggle is off, and turning it off drops what
/// is still pending. Main-queue API.
final class LyricsStore: ObservableObject {
    /// What the lyrics pane shows for a track.
    enum Shown: Equatable {
        case loading
        case found(Lyrics)
        case none
    }

    /// Every id asked for (found ones are kept for a while after).
    @Published private(set) var fetches: [String: LyricsFetch] = [:]

    /// The host's track server; nil while unlinked.
    var endpoint: () -> (host: String, port: Int)? = { nil }

    private(set) var enabled = false
    private var currentId: String?
    private var nextId: String?
    /// Voids the answers and retries of requests made before a reset of their id.
    private var generation: [String: Int] = [:]
    private var retryTimers: [String: Timer] = [:]
    private var tasks: [String: URLSessionDataTask] = [:]
    /// Found ids, oldest first; past `keepFound` the oldest go.
    private var foundOrder: [String] = []
    private let keepFound = 20
    private let session: URLSession

    init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 10
        config.timeoutIntervalForResource = 20
        config.allowsCellularAccess = false // the host is on Wi-Fi
        config.waitsForConnectivity = false
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: config)
    }

    func shown(for id: String) -> Shown {
        guard let fetch = fetches[id] else { return .loading }
        if let lyrics = fetch.lyrics { return .found(lyrics) }
        return fetch.isPending ? .loading : .none
    }

    /// The lyrics toggle.
    func setEnabled(_ on: Bool) {
        guard on != enabled else { return }
        enabled = on
        if on {
            fetchWanted()
        } else {
            // Pending ones start over the next time it is on.
            for (id, fetch) in fetches where fetch.isPending { reset(id) }
        }
    }

    /// The track now playing changed (nil: none).
    func setCurrent(_ id: String?) {
        guard id != currentId else { return }
        currentId = id
        if nextId == id { nextId = nil }
        fetchWanted()
    }

    /// A `music.load` for `id`: the current track or the next one. A 404 or
    /// a given-up lookup is final only until the id is loaded again.
    func loaded(_ id: String) {
        if id != currentId { nextId = id }
        if let fetch = fetches[id], !fetch.isPending, fetch.lyrics == nil { reset(id) }
        fetchWanted()
    }

    /// The link (and with it the host's address) came up: ask for what is
    /// still missing.
    func linked() {
        fetchWanted()
    }

    // MARK: - Private

    private func fetchWanted() {
        guard enabled else { return }
        for id in [currentId, nextId].compactMap({ $0 }) where fetches[id] == nil {
            start(id)
        }
        prune()
    }

    private func start(_ id: String) {
        guard let endpoint = endpoint() else { return } // asked again by `linked()`
        fetches[id] = LyricsFetch()
        request(id, endpoint)
    }

    private func request(_ id: String, _ endpoint: (host: String, port: Int)) {
        var components = URLComponents()
        components.scheme = "http"
        components.host = endpoint.host
        components.port = endpoint.port
        components.path = "/lyrics/\(id).json"
        guard let url = components.url else {
            var fetch = fetches[id] ?? LyricsFetch()
            _ = fetch.answered(status: 404)
            fetches[id] = fetch
            return
        }
        let gen = generation[id, default: 0]
        let task = session.dataTask(with: url) { [weak self] data, response, _ in
            let status = (response as? HTTPURLResponse)?.statusCode
            DispatchQueue.main.async { self?.answer(id, gen: gen, status: status, body: data) }
        }
        tasks[id] = task
        task.resume()
    }

    private func answer(_ id: String, gen: Int, status: Int?, body: Data?) {
        guard gen == generation[id, default: 0], var fetch = fetches[id] else { return }
        tasks[id] = nil
        let step = fetch.answered(status: status, body: body)
        fetches[id] = fetch
        switch fetch.phase {
        case .found(let lyrics):
            Log.music.info("lyrics \(id, privacy: .public): \(lyrics.lines.count) lines")
            foundOrder.removeAll { $0 == id }
            foundOrder.append(id)
            prune()
        case .missing, .gaveUp:
            Log.music.info("lyrics \(id, privacy: .public): none (\(status.map(String.init) ?? "no answer", privacy: .public))")
        case .fetching, .waiting:
            break
        }
        guard case .retry(let afterMs) = step else { return }
        retryTimers[id] = Timer.scheduledTimer(withTimeInterval: Double(afterMs) / 1000, repeats: false) { [weak self] _ in
            guard let self, gen == self.generation[id, default: 0] else { return }
            self.retryTimers[id] = nil
            guard self.enabled, var fetch = self.fetches[id], fetch.retryStarted() else { return }
            self.fetches[id] = fetch
            if let endpoint = self.endpoint() {
                self.request(id, endpoint)
            } else {
                self.answer(id, gen: gen, status: nil, body: nil) // unlinked: no answer, as a 503
            }
        }
    }

    /// Forgets `id` and voids whatever of it is still under way.
    private func reset(_ id: String) {
        generation[id, default: 0] += 1
        retryTimers.removeValue(forKey: id)?.invalidate()
        tasks.removeValue(forKey: id)?.cancel()
        fetches[id] = nil
        foundOrder.removeAll { $0 == id }
    }

    /// Keeps the wanted ids and the last `keepFound` found ones.
    private func prune() {
        let wanted = Set([currentId, nextId].compactMap { $0 })
        for id in fetches.keys where !wanted.contains(id) && fetches[id]?.lyrics == nil {
            reset(id)
        }
        while foundOrder.count > keepFound, let oldest = foundOrder.first(where: { !wanted.contains($0) }) {
            reset(oldest)
        }
    }
}
#endif
