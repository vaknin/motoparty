import Foundation

/// The one-line status under now playing, for the stretches where the clock
/// sits at 0:00 (or stands still) and nothing else says why.
public enum MusicStatus: Equatable, Sendable {
    /// Playing, plainly paused, or nothing loaded: no line.
    case none
    /// The current track is downloading, or it was loaded (`music.load`) and
    /// the host has not sent its `music.play` yet (PROTOCOL.md "Music flow").
    case loading
    /// A talk is open and holds the music: the host resumes it with
    /// `music.play` once the talk closes (PROTOCOL.md "Talk flow" step 4).
    case pausedForTalk
    /// The host is searching for a voice command (`state.busy`, 2026-10-01),
    /// e.g. `Searching song "moby"`; shown with a spinner, also with nothing
    /// loaded. The two above come first, as on the Pixel.
    case searching(String)

    public var text: String? {
        switch self {
        case .none: nil
        case .loading: "Downloading song…"
        case .pausedForTalk: "Paused for talk — plays when the talk ends"
        case .searching(let what): "\(what)…"
        }
    }

    /// The line turns with a spinner (it is waiting for something).
    public var spins: Bool {
        switch self {
        case .loading, .searching: true
        case .none, .pausedForTalk: false
        }
    }
}

/// Follows the control messages and downloads that decide `MusicStatus`.
/// Pure bookkeeping; the app feeds it events and reads `status`.
public struct MusicStatusTracker: Equatable, Sendable {
    /// The track now playing (`state.music.id` / the last `music.play`).
    public private(set) var currentId: String?
    /// The host's music is playing (last `music.play`, or `state.music.playing`).
    public private(set) var playing = false
    public private(set) var talkOpen = false
    /// Music was playing when this talk opened, so the host holds its resume.
    private var heldAtTalkOpen = false
    /// Loaded (`music.load`, or first named by `state`) and not yet played.
    private var awaitingPlay: Set<String> = []
    /// Being fetched from the host (download + decode check).
    private var downloading: Set<String> = []
    /// `state.busy`: the host's voice search, nil when none runs.
    public private(set) var busy: String?

    public init() {}

    public var status: MusicStatus {
        let track = trackStatus
        if track == .none, let busy, !busy.isEmpty { return .searching(busy) }
        return track
    }

    private var trackStatus: MusicStatus {
        guard let id = currentId else { return .none }
        if downloading.contains(id) { return .loading }
        let pending = awaitingPlay.contains(id)
        if talkOpen, heldAtTalkOpen || pending { return .pausedForTalk }
        if pending { return .loading }
        return .none
    }

    // MARK: - Events

    /// Every `state`: its `busy` (absent ends the search line).
    public mutating func hostBusy(_ text: String?) {
        busy = text
    }

    /// The track now playing changed (or was confirmed); nil: nothing loaded.
    public mutating func setCurrent(_ id: String?) {
        guard id != currentId else { return }
        currentId = id
        if id == nil {
            playing = false
            heldAtTalkOpen = false
        }
    }

    /// `music.load` (the current track, or the next one being prefetched),
    /// or `state.music` naming a track with no load yet: it waits for its
    /// `music.play`.
    public mutating func load(_ id: String) {
        awaitingPlay.insert(id)
    }

    /// `music.play` for `id`: the wait is over.
    public mutating func play(_ id: String) {
        awaitingPlay.remove(id)
        playing = true
    }

    /// `state.music`: the host's play state for the current track. Playing
    /// means the host already has an anchor to follow, so nothing is awaited.
    public mutating func hostState(id: String, playing: Bool) {
        self.playing = playing
        if playing { awaitingPlay.remove(id) }
    }

    /// `music.pause`: nothing resumes on its own, not even after a talk.
    public mutating func pause(_ id: String) {
        awaitingPlay.remove(id)
        playing = false
        heldAtTalkOpen = false
    }

    /// `music.stop`, or `state` without music.
    public mutating func stop() {
        awaitingPlay.removeAll()
        playing = false
        heldAtTalkOpen = false
    }

    /// The host opened talk (`talk.open`, or `state{talk:true}`), whether or
    /// not this phone's own side of it is up yet: the host's music is held
    /// from its decision on. Call before applying that state's music.
    public mutating func talkOpened() {
        guard !talkOpen else { return }
        talkOpen = true
        heldAtTalkOpen = playing
    }

    /// The host closed talk, or the link dropped.
    public mutating func talkClosed() {
        talkOpen = false
        heldAtTalkOpen = false
    }

    public mutating func downloadStarted(_ id: String) {
        downloading.insert(id)
    }

    /// The download ended. A failed one will not become ready, so it waits
    /// for nothing (the host plays alone after `music.error`).
    public mutating func downloadFinished(_ id: String, ok: Bool) {
        downloading.remove(id)
        if !ok { awaitingPlay.remove(id) }
    }

    /// The link dropped: the host re-sends `music.load` after the next join.
    public mutating func linkLost() {
        awaitingPlay.removeAll()
        busy = nil
    }
}
