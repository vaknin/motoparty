import Foundation

/// The host's downloads as the last `music.downloads` gave them (PROTOCOL.md
/// "Browsing" step 6): which tracks are cached there, and each collection's
/// progress by its `ref`.
public struct HostDownloads: Equatable, Sendable {
    public private(set) var cached: Set<String> = []
    public private(set) var progress: [String: DownloadItem] = [:]

    public init() {}

    public init(_ message: MusicDownloads) {
        cached = Set(message.cached)
        progress = Dictionary(message.downloads.map { ($0.ref, $0) }, uniquingKeysWith: { _, last in last })
    }

    /// The song plays on the host without coverage: the row's mark.
    public func isCached(_ id: String) -> Bool { cached.contains(id) }

    /// The collection page's Download button for `ref` with these songs.
    public func button(ref: String, songs: [String]) -> CollectionDownloadButton {
        CollectionDownloadButton(progress: progress[ref], allCached: !songs.isEmpty && songs.allSatisfy(cached.contains))
    }
}

/// The Download button of an album or playlist, worded as on the Pixel's
/// Search tab: "Download", "Downloading 5/14 · Stop", "Retry · 12/14 saved",
/// "Downloaded".
public struct CollectionDownloadButton: Equatable, Sendable {
    public enum Phase: Equatable, Sendable {
        /// Nothing started (or it was stopped): a tap starts it.
        case idle
        /// A tap sends `stop`.
        case running(done: Int, total: Int)
        /// Finished with failures: a tap starts it again.
        case retry(saved: Int, total: Int)
        /// Every song is on the host: nothing to tap.
        case done
    }

    public let phase: Phase

    public init(progress: DownloadItem?, allCached: Bool) {
        if let p = progress, p.running {
            phase = .running(done: p.done, total: p.total)
        } else if allCached || (progress.map { $0.failed == 0 } ?? false) {
            phase = .done
        } else if let p = progress {
            phase = .retry(saved: p.done - p.failed, total: p.total)
        } else {
            phase = .idle
        }
    }

    public var label: String {
        switch phase {
        case .idle: "Download"
        case .running(let done, let total): "Downloading \(done)/\(total) · Stop"
        case .retry(let saved, let total): "Retry · \(saved)/\(total) saved"
        case .done: "Downloaded"
        }
    }

    public var isRunning: Bool { if case .running = phase { true } else { false } }
    public var isDone: Bool { phase == .done }

    /// For the progress ring while running.
    public var fraction: Double? {
        guard case .running(let done, let total) = phase, total > 0 else { return nil }
        return Double(done) / Double(total)
    }
}
