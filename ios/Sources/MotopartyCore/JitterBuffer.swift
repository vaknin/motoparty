import Foundation

/// What the audio output should do for the next 20 ms frame.
public enum PlayoutAction: Equatable, Sendable {
    /// Nothing to play: idle, waiting for a spurt's first slot, or silence
    /// between talk spurts (a `ts` jump with contiguous `seq`).
    case silence
    /// A normal frame.
    case decode(Data)
    /// The frame is missing but its successor has arrived: decode the
    /// successor's in-band FEC (opus_decode with decode_fec = 1).
    case decodeFEC(Data)
    /// The frame is missing: packet-loss concealment (opus_decode with NULL).
    case conceal
}

/// Adaptive receive buffer for 20 ms Opus frames (PROTOCOL.md, Voice). The
/// same rules as Android's `core/JitterBuffer.kt`; `fixtures/jitter.json`
/// pins both.
///
/// - A *talk spurt* starts at the first packet and again after every silence
///   gap (a `ts` jump with contiguous `seq`; keepalives and packets that came
///   too late to play count as seen), also when the packet after the gap
///   comes later than the old spurt's playout clock. Its first frame plays
///   `targetMs` after it arrived; later frames play at fixed 20 ms slots.
/// - *Shedding*: a backlog (playout stalled, or the output clock is slower
///   than the sender's) would otherwise stay as delay until the talk ends,
///   since an earbud mic never goes silent. Oldest frames are dropped at
///   spurt start until the queue spans the target, and during a spurt when
///   the smallest depth over `shedWindow` frames due stays above the target.
///   Shed frames are neither loss nor underruns (`stats.shed`).
/// - *Underrun* = a packet arrives after its playout slot has passed. It is
///   dropped; the target rises by 20 ms (max 200), at most once per spurt.
///   An empty buffer during silence is not an underrun.
/// - After 10 s without an underrun the target drops by 20 ms (min 40).
///   Target changes apply at the start of the next spurt.
/// - Re-anchor: packets that keep arriving late for `reanchorAfterMs` with
///   nothing played in between mean the playout clock is ahead of the stream
///   and stays there; the next late packet starts a new spurt instead.
/// - Within a spurt, a `seq` gap is loss: FEC from the successor if it is the
///   very next frame, otherwise PLC.
/// - Hard cap: `pull` never holds more than `maxTargetMs + backlogSlackMs`
///   (400 ms = 20 frames); anything older is shed.
///
/// Not thread-safe; call from one queue (or under a lock).
public struct JitterBuffer: Sendable {
    public struct Config: Sendable {
        public var initialTargetMs = 40
        public var stepMs = 20
        public var minTargetMs = 40
        public var maxTargetMs = 200
        public var decreaseAfterMs: Double = 10_000
        /// PLC frames emitted when the buffer runs dry before going silent.
        public var maxConcealFrames = 3
        /// Maximum packets held.
        public var capacity = 100
        /// How far the backlog may exceed `maxTargetMs` before `pull` sheds its
        /// oldest frames (Android's `BACKLOG_SLACK_MS`): the hard cap is
        /// `maxTargetMs + backlogSlackMs` = 400 ms = 20 packets.
        public var backlogSlackMs = 200
        /// Frames due per shed window (1 s).
        public var shedWindow = 50
        /// Smallest depth of a window this far above the target: shed one frame…
        public var shedOneAboveMs = 40
        /// …and this far above: a stall, shed the whole excess at once.
        public var shedAllAboveMs = 120
        /// How long packets may keep arriving late, nothing played, before
        /// `insert` re-anchors.
        public var reanchorAfterMs: Double = 100

        public init() {}
    }

    public struct Stats: Equatable, Sendable {
        public var received = 0
        public var played = 0
        public var fec = 0
        public var concealed = 0
        public var lost = 0
        /// Packets that arrived after their slot (each one is an underrun in
        /// the spec's sense; Android's `underruns`).
        public var late = 0
        public var duplicates = 0
        /// Packets pushed out by `capacity`.
        public var dropped = 0
        /// Target raises: at most one per spurt, however many were late.
        public var underruns = 0
        public var spurts = 0
        /// Queued frames dropped to get rid of a backlog (spurt start, 1 s
        /// window, hard cap).
        public var shed = 0
        /// Late runs that started a new spurt instead of being dropped.
        public var reanchors = 0
        /// Largest queue depth seen when a frame was due, ms.
        public var maxDepthMs = 0
        var depthSumMs = 0
        var depthPulls = 0
        /// Mean queue depth over the frames due, ms; 0 before the first.
        public var meanDepthMs: Int { depthPulls == 0 ? 0 : depthSumMs / depthPulls }
    }

    private struct Entry: Sendable {
        var ts: Int64
        var seq: Int64
        var payload: Data
        var arrivalMs: Double
    }

    public static let frameSamples: Int64 = 320
    public static let frameMs = 20
    private static let samplesPerMs: Int64 = 16
    /// A `ts` this far from the playout clock: the sender restarted.
    private static let resync: Int64 = 3 * 16_000
    private static let maxKeepalives = 16

    public let config: Config
    public private(set) var targetMs: Int
    public private(set) var stats = Stats()

    /// Queued packets, oldest `ts` first.
    private var packets: [Entry] = []
    /// True while inside a spurt: frames are due at `nextTs`.
    private var playing = false
    /// Unwrapped ts of the frame for the next playout slot.
    private var nextTs: Int64 = 0
    /// Unwrapped seq of the last frame played or shed.
    private var lastSeq: Int64?
    private var tsRef: Int64?
    private var seqRef: Int64?
    private var emptyPulls = 0
    private var lastUnderrunMs: Double?
    private var raisedThisSpurt = false
    /// Arrival of the first late packet since the last frame played.
    private var lateSinceMs: Double?
    /// `ts` of the last frame returned as `.decode`.
    private var lastPlayedTs: Int64?
    /// Frames due in the current shed window, and the smallest queue among them.
    private var windowPulls = 0
    private var windowMin = Int.max
    private var keepaliveSeqs: [Int64] = []
    /// Audio seqs that arrived after their slot: seen, so not a gap.
    private var lateSeqs: [Int64] = []

    public init(config: Config = Config()) {
        self.config = config
        self.targetMs = config.initialTargetMs
    }

    /// Waiting for a spurt with nothing queued (a spurt that ran dry stays
    /// a spurt until the next packet says what the gap was).
    public var isIdle: Bool { !playing && packets.isEmpty }
    public var isPlaying: Bool { playing }
    public var bufferedPackets: Int { packets.count }

    /// Back to idle with the initial target (call at the start of a talk).
    /// The counters in `stats` keep counting.
    public mutating func reset() {
        packets.removeAll()
        keepaliveSeqs.removeAll()
        lateSeqs.removeAll()
        playing = false
        nextTs = 0
        emptyPulls = 0
        raisedThisSpurt = false
        lateSinceMs = nil
        lastPlayedTs = nil
        windowPulls = 0
        windowMin = .max
        lastSeq = nil
        tsRef = nil
        seqRef = nil
        targetMs = config.initialTargetMs
        lastUnderrunMs = nil
    }

    public mutating func noteKeepalive(seq: UInt16) {
        let s = Self.unwrap(Int64(seq), seqRef, 1 << 16)
        seqRef = s
        keepaliveSeqs.append(s)
        if keepaliveSeqs.count > Self.maxKeepalives { keepaliveSeqs.removeFirst() }
    }

    public mutating func insert(_ packet: VoicePacket, nowMs: Double) {
        switch packet.kind {
        case .keepalive: noteKeepalive(seq: packet.seq)
        case .audio: insert(seq: packet.seq, ts: packet.ts, payload: packet.payload, nowMs: nowMs)
        }
    }

    public mutating func insert(seq: UInt16, ts: UInt32, payload: Data, nowMs: Double) {
        if lastUnderrunMs == nil { lastUnderrunMs = nowMs }
        let u = Self.unwrap(Int64(ts), tsRef, 1 << 32)
        tsRef = u
        let s = Self.unwrap(Int64(seq), seqRef, 1 << 16)
        seqRef = s
        stats.received += 1
        let entry = Entry(ts: u, seq: s, payload: payload, arrivalMs: nowMs)

        if playing, abs(u - nextTs) > Self.resync {
            // The sender restarted or we fell hopelessly behind: start over,
            // keep the target.
            packets.removeAll()
            playing = false
            lastSeq = nil
            lastPlayedTs = nil
        }
        if playing, u < nextTs - Self.frameSamples / 2 {
            if packets.isEmpty, let played = lastPlayedTs, u - played >= 2 * Self.frameSamples,
               onlyKeepalivesBefore(s) {
                // Not late: the sender was silent since the last played frame
                // (`ts` jumped, `seq` did not) and this first packet of the
                // next spurt merely took longer than the old spurt's timeline
                // allowed. End that spurt; `pull` starts the new one.
                playing = false
                packets.append(entry)
                return
            }
            let since = lateSinceMs ?? nowMs
            lateSinceMs = since
            if nowMs - since >= config.reanchorAfterMs {
                // Late for a while and nothing played: the playout clock is
                // ahead of this stream and stays there (both advance at 50
                // frames/s). Start a new spurt at this packet; the target was
                // already raised by the first late packet of this run.
                stats.reanchors += 1
                playing = false
                lastSeq = nil
                lateSinceMs = nil
                packets.removeAll { $0.ts < u }
                queue(entry)
                return
            }
            // Its playout slot has passed.
            stats.late += 1
            lastUnderrunMs = nowMs
            if !raisedThisSpurt {
                raisedThisSpurt = true
                stats.underruns += 1
                targetMs = min(config.maxTargetMs, targetMs + config.stepMs)
            }
            // Not loss either: if it was the spurt's last packet, the silence
            // gap after it must still start a new spurt.
            lateSeqs.append(s)
            if lateSeqs.count > Self.maxKeepalives { lateSeqs.removeFirst() }
            return
        }
        queue(entry)
        while packets.count > config.capacity {
            packets.removeFirst()
            stats.dropped += 1
        }
    }

    /// Call once per 20 ms of output.
    public mutating func pull(nowMs: Double) -> PlayoutAction {
        if let last = lastUnderrunMs, nowMs - last >= config.decreaseAfterMs {
            lastUnderrunMs = nowMs
            if targetMs > config.minTargetMs { targetMs = max(config.minTargetMs, targetMs - config.stepMs) }
        }
        // Hard cap only; the shedding below is what normally keeps the depth.
        while packets.count * Self.frameMs > config.maxTargetMs + config.backlogSlackMs { shedOldest() }

        if !playing {
            guard let oldest = packets.first, nowMs - oldest.arrivalMs >= Double(targetMs) else { return .silence }
            // A burst queued before the spurt could start: keep no more than
            // the target of it, and start at the oldest that is left.
            while let first = packets.first, let last = packets.last,
                  last.ts - first.ts > Int64(targetMs) * Self.samplesPerMs {
                shedOldest()
            }
            playing = true
            raisedThisSpurt = false
            nextTs = packets[0].ts
            windowPulls = 0
            windowMin = .max
            stats.spurts += 1
        }

        // A frame is due: note the depth, and once per window shed what never
        // drained.
        let depth = packets.count
        let depthMs = depth * Self.frameMs
        stats.depthSumMs += depthMs
        stats.depthPulls += 1
        stats.maxDepthMs = max(stats.maxDepthMs, depthMs)
        windowMin = min(windowMin, depth)
        windowPulls += 1
        if windowPulls >= config.shedWindow {
            let minMs = windowMin * Self.frameMs
            var drop = 0
            if minMs > targetMs + config.shedAllAboveMs {
                drop = (minMs - targetMs) / Self.frameMs
            } else if minMs > targetMs + config.shedOneAboveMs {
                drop = 1
            }
            for _ in 0..<min(drop, packets.count) { shedOldest() }
            windowPulls = 0
            windowMin = .max
        }

        guard let first = packets.first else {
            // Nothing queued: loss or the sender went quiet. Conceal briefly,
            // then fall silent rather than let PLC stretch the last phoneme.
            nextTs += Self.frameSamples
            emptyPulls += 1
            if emptyPulls <= config.maxConcealFrames {
                stats.concealed += 1
                return .conceal
            }
            return .silence
        }
        if first.ts < nextTs + Self.frameSamples / 2 {
            packets.removeFirst()
            lastSeq = first.seq
            lastPlayedTs = first.ts
            nextTs = first.ts + Self.frameSamples
            emptyPulls = 0
            lateSinceMs = nil
            stats.played += 1
            return .decode(first.payload)
        }
        if onlyKeepalivesBefore(first.seq) {
            // Silence gap: the next packet starts a new spurt with the
            // current target.
            playing = false
            return pull(nowMs: nowMs)
        }
        // Loss: the frame for this slot is missing and a later one is queued.
        nextTs += Self.frameSamples
        emptyPulls = 0
        stats.lost += 1
        if first.ts < nextTs + Self.frameSamples / 2 {
            stats.fec += 1
            lateSinceMs = nil
            return .decodeFEC(first.payload)
        }
        stats.concealed += 1
        return .conceal
    }

    // MARK: - Helpers

    /// Inserts in `ts` order; a second packet for the same `ts` is dropped.
    private mutating func queue(_ entry: Entry) {
        var i = packets.count
        while i > 0, packets[i - 1].ts > entry.ts { i -= 1 }
        if i > 0, packets[i - 1].ts == entry.ts {
            stats.duplicates += 1
            return
        }
        packets.insert(entry, at: i)
    }

    /// Drops the oldest queued frame as if it had played: its successor is
    /// next, not a gap.
    private mutating func shedOldest() {
        guard !packets.isEmpty else { return }
        let dropped = packets.removeFirst()
        lastSeq = dropped.seq
        nextTs = dropped.ts + Self.frameSamples
        stats.shed += 1
    }

    /// True if every seq strictly between the last played packet and `seq`
    /// was a keepalive (or an audio packet that came too late to play: it
    /// arrived, it just could not be used).
    private func onlyKeepalivesBefore(_ seq: Int64) -> Bool {
        guard let last = lastSeq else { return true }
        if seq <= last || seq - last - 1 > Int64(Self.maxKeepalives) { return false }
        var s = last + 1
        while s < seq {
            if !keepaliveSeqs.contains(s), !lateSeqs.contains(s) { return false }
            s += 1
        }
        return true
    }

    /// Unwraps a modular counter to the value nearest the previous one.
    static func unwrap(_ raw: Int64, _ ref: Int64?, _ modulus: Int64) -> Int64 {
        guard let ref else { return raw }
        let floorMod = ((ref % modulus) + modulus) % modulus
        var best = ref - floorMod + raw
        for candidate in [best - modulus, best + modulus] where abs(candidate - ref) < abs(best - ref) {
            best = candidate
        }
        return best
    }
}
