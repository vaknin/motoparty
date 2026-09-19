#if os(iOS)
import AVFoundation
import Foundation

/// Downloads tracks from the host (`GET http://<host>:<httpPort>/track/<id>.m4a`)
/// into Caches/tracks, fully, before `music.ready`. LRU-pruned to `maxBytes`.
/// Main-queue API.
final class TrackCache {
    enum Outcome {
        case ready(URL)
        case failed(String)
    }

    let maxBytes: Int64 = 1_000_000_000
    private let directory: URL
    private let session: URLSession
    private var inflight: [String: [(Outcome) -> Void]] = [:]

    init() {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        directory = caches.appendingPathComponent("tracks", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 300
        config.allowsCellularAccess = false // the host is on Wi-Fi; never pay for LAN data
        config.waitsForConnectivity = false
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: config)
    }

    func localURL(for id: String) -> URL {
        let safe = id.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(CharacterSet(charactersIn: "-_"))) ?? id
        return directory.appendingPathComponent(safe).appendingPathExtension("m4a")
    }

    func isCached(_ id: String) -> Bool {
        FileManager.default.fileExists(atPath: localURL(for: id).path)
    }

    /// `path` is the music.load path (e.g. "/track/<id>.m4a").
    func fetch(id: String, host: String, port: Int, path: String, completion: @escaping (Outcome) -> Void) {
        let dest = localURL(for: id)
        if FileManager.default.fileExists(atPath: dest.path) {
            touch(dest)
            completion(.ready(dest))
            return
        }
        if inflight[id] != nil {
            inflight[id]?.append(completion)
            return
        }
        var components = URLComponents()
        components.scheme = "http"
        components.host = host
        components.port = port
        components.path = path
        guard let url = components.url else {
            completion(.failed("bad URL for \(path)"))
            return
        }
        inflight[id] = [completion]
        Log.music.info("download \(url.absoluteString, privacy: .public)")

        let task = session.downloadTask(with: url) { [weak self] tmp, response, error in
            // The temp file is deleted when this handler returns: move it now.
            var outcome: Outcome
            if let error {
                outcome = .failed(error.localizedDescription)
            } else if let http = response as? HTTPURLResponse, http.statusCode != 200 {
                outcome = .failed("HTTP \(http.statusCode)")
            } else if let tmp {
                do {
                    try? FileManager.default.removeItem(at: dest)
                    try FileManager.default.moveItem(at: tmp, to: dest)
                    outcome = .ready(dest)
                } catch {
                    outcome = .failed("store: \(error.localizedDescription)")
                }
            } else {
                outcome = .failed("no data")
            }
            DispatchQueue.main.async { self?.verify(id: id, outcome: outcome) }
        }
        task.resume()
    }

    // MARK: - Private

    private func verify(id: String, outcome: Outcome) {
        guard case .ready(let url) = outcome else { return finish(id, outcome) }
        Task { @MainActor in
            let asset = AVURLAsset(url: url)
            let playable = (try? await asset.load(.isPlayable)) ?? false
            let duration = (try? await asset.load(.duration))?.seconds ?? 0
            if playable, duration > 0 {
                self.finish(id, outcome)
                self.prune()
            } else {
                try? FileManager.default.removeItem(at: url)
                self.finish(id, .failed("not decodable"))
            }
        }
    }

    private func finish(_ id: String, _ outcome: Outcome) {
        let waiters = inflight.removeValue(forKey: id) ?? []
        if case .failed(let why) = outcome { Log.music.error("track \(id, privacy: .public): \(why, privacy: .public)") }
        for w in waiters { w(outcome) }
    }

    private func touch(_ url: URL) {
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: url.path)
    }

    private func prune() {
        let keys: [URLResourceKey] = [.contentModificationDateKey, .fileSizeKey]
        guard let files = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: keys) else { return }
        var entries = files.compactMap { url -> (URL, Date, Int64)? in
            guard let v = try? url.resourceValues(forKeys: Set(keys)) else { return nil }
            return (url, v.contentModificationDate ?? .distantPast, Int64(v.fileSize ?? 0))
        }
        var total = entries.reduce(Int64(0)) { $0 + $1.2 }
        entries.sort { $0.1 < $1.1 } // oldest first
        for (url, _, size) in entries where total > maxBytes {
            try? FileManager.default.removeItem(at: url)
            total -= size
        }
    }
}
#endif
