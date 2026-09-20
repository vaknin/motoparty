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

/// Adaptive receive buffer for 20 ms Opus frames (PROTOCOL.md, Voice).
///
/// - A *talk spurt* starts with the first packet after idle, or with a packet
///   whose `seq` is contiguous with what was played (keepalives count) but
///   whose `ts` jumps: the gap was silence. Its playout slot is its arrival
///   time + `targetMs`; later frames of the spurt play at fixed 20 ms slots
///   after it, so latency never creeps up within a spurt.
/// - *Underrun* = a packet arrives after its playout slot has passed. It is
///   dropped; the target rises by 20 ms (max 200), at most once per spurt.
///   An empty buffer during silence is not an underrun.
/// - After 10 s without an underrun the target drops by 20 ms (min 40).
///   Target changes apply at the start of the next spurt.
/// - Within a spurt, a `seq` gap is loss: FEC from the successor if it has
///   arrived, otherwise PLC.
/// - Backlog cap: `pull` never holds more than
///   `maxTargetMs + backlogSlackMs` (400 ms = 20 frames) of audio. Anything
///   older is dropped and the playout cursor jumps behind it, so a burst that
///   the output never drained costs those frames instead of that much delay.
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
        /// Nothing received for this long → idle (the next packet anchors).
        public var idleAfterMs = 2_000
        /// Maximum packets held.
        public var capacity = 100
        /// How far the backlog may exceed `maxTargetMs` before `pull` drops its
        /// oldest frames (Android's `BACKLOG_SLACK_MS`): the hard cap is
        /// `maxTargetMs + backlogSlackMs` = 400 ms = 20 packets.
        public var backlogSlackMs = 200

        public init() {}
    }

    public struct Stats: Equatable, Sendable {
        public var received = 0
        public var played = 0
        public var fec = 0
        public var concealed = 0
        public var lost = 0
        public var late = 0
        public var duplicates = 0
        public var dropped = 0
        public var underruns = 0
        public var spurts = 0
    }

    private enum Phase: Equatable {
        case idle
        /// First frame of the first spurt plays at `untilMs`.
        case buffering(untilMs: Double)
        case playing
    }

    private struct Entry: Sendable {
        var seq: UInt16
        var payload: Data
        var arrivalMs: Double
    }

    public static let frameSamples: Int64 = 320
    public static let frameMs = 20

    public let config: Config
    public private(set) var targetMs: Int
    public private(set) var stats = Stats()

    private var phase: Phase = .idle
    private var packets: [Int64: Entry] = [:]
    /// Unwrapped ts of the frame for the next playout slot.
    private var nextTs: Int64 = 0
    /// seq of the last frame played or accounted as lost.
    private var lastSeq: UInt16 = 0
    private var tsRef: Int64?
    private var emptyPulls = 0
    private var lastUnderrunMs: Double = 0
    private var underrunThisSpurt = false
    private var keepaliveSeqs: [UInt16] = []

    public init(config: Config = Config()) {
        self.config = config
        self.targetMs = config.initialTargetMs
    }

    public var isIdle: Bool { phase == .idle }
    public var isPlaying: Bool { phase == .playing }
    public var bufferedPackets: Int { packets.count }

    /// Back to idle with the initial target (call at the start of a talk).
    public mutating func reset() {
        phase = .idle
        packets.removeAll()
        targetMs = config.initialTargetMs
        emptyPulls = 0
        underrunThisSpurt = false
        keepaliveSeqs.removeAll()
        tsRef = nil
    }

    public mutating func noteKeepalive(seq: UInt16) {
        keepaliveSeqs.append(seq)
        if keepaliveSeqs.count > 32 { keepaliveSeqs.removeFirst() }
    }

    public mutating func insert(_ packet: VoicePacket, nowMs: Double) {
        switch packet.kind {
        case .keepalive: noteKeepalive(seq: packet.seq)
        case .audio: insert(seq: packet.seq, ts: packet.ts, payload: packet.payload, nowMs: nowMs)
        }
    }

    public mutating func insert(seq: UInt16, ts: UInt32, payload: Data, nowMs: Double) {
        stats.received += 1
        let uts = unwrap(ts)

        if phase == .idle {
            packets.removeAll()
            nextTs = uts
            lastSeq = seq &- 1
            phase = .buffering(untilMs: nowMs + Double(targetMs))
            lastUnderrunMs = nowMs
            emptyPulls = 0
            beginSpurt()
            packets[uts] = Entry(seq: seq, payload: payload, arrivalMs: nowMs)
            return
        }

        if uts < nextTs {
            // Its playout slot has passed. Its seq is accounted for (not loss).
            stats.late += 1
            let ahead = Int(seq &- lastSeq)
            if ahead > 0, ahead < 0x8000 { lastSeq = seq }
            if !underrunThisSpurt {
                underrunThisSpurt = true
                stats.underruns += 1
                targetMs = min(config.maxTargetMs, targetMs + config.stepMs)
            }
            lastUnderrunMs = nowMs
            return
        }
        if packets[uts] != nil {
            stats.duplicates += 1
            return
        }
        packets[uts] = Entry(seq: seq, payload: payload, arrivalMs: nowMs)
        emptyPulls = 0

        while packets.count > config.capacity, let oldest = packets.keys.min() {
            packets.removeValue(forKey: oldest)
            stats.dropped += 1
        }
    }

    /// Call once per 20 ms of output.
    public mutating func pull(nowMs: Double) -> PlayoutAction {
        if nowMs - lastUnderrunMs >= config.decreaseAfterMs {
            if targetMs > config.minTargetMs { targetMs = max(config.minTargetMs, targetMs - config.stepMs) }
            lastUnderrunMs = nowMs
        }

        // Hard cap on the backlog, the same rule as Android's
        // `MAX_MS + BACKLOG_SLACK_MS`: a burst the output never drained (or
        // clock drift over a very long spurt) would otherwise sit in the buffer
        // as delay until the next pause. Drop the oldest frames and put the
        // cursor right behind the last one dropped, so they are not counted as
        // loss and the next pull plays what is left. Normal target changes still
        // wait for the next spurt.
        while packets.count * Self.frameMs > config.maxTargetMs + config.backlogSlackMs,
              let oldest = packets.keys.min(), let entry = packets.removeValue(forKey: oldest) {
            lastSeq = entry.seq
            nextTs = oldest + Self.frameSamples
            stats.dropped += 1
        }

        switch phase {
        case .idle:
            return .silence
        case .buffering(let until):
            if nowMs < until { return .silence }
            phase = .playing
        case .playing:
            break
        }

        guard let firstKey = packets.keys.min(), let first = packets[firstKey] else {
            emptyPulls += 1
            nextTs += Self.frameSamples
            if emptyPulls * Self.frameMs >= config.idleAfterMs {
                phase = .idle
                return .silence
            }
            if emptyPulls <= config.maxConcealFrames {
                stats.concealed += 1
                return .conceal
            }
            return .silence
        }

        if firstKey == nextTs {
            packets.removeValue(forKey: firstKey)
            lastSeq = first.seq
            nextTs += Self.frameSamples
            stats.played += 1
            return .decode(first.payload)
        }

        // The frame for this slot is missing; a later one is buffered.
        let missing = missingBetween(lastSeq, first.seq)
        if missing == 0 {
            // Contiguous seq, ts jump: silence, then a new talk spurt whose
            // first frame plays at arrival + target.
            if nowMs >= first.arrivalMs + Double(targetMs) {
                packets.removeValue(forKey: firstKey)
                lastSeq = first.seq
                nextTs = firstKey + Self.frameSamples
                beginSpurt()
                stats.played += 1
                return .decode(first.payload)
            }
            // Hold the cursor below the spurt so it only starts via this branch.
            return .silence
        }

        // Loss: account for one missing packet (skipping keepalive seqs).
        let slot = nextTs
        nextTs += Self.frameSamples
        repeat { lastSeq &+= 1 } while keepaliveSeqs.contains(lastSeq) && lastSeq != first.seq
        stats.lost += 1
        if firstKey == slot + Self.frameSamples {
            stats.fec += 1
            return .decodeFEC(first.payload)
        }
        stats.concealed += 1
        return .conceal
    }

    // MARK: - Helpers

    private mutating func beginSpurt() {
        stats.spurts += 1
        underrunThisSpurt = false
    }

    /// Audio packets missing strictly between seq `a` and `b` (keepalives excluded).
    private func missingBetween(_ a: UInt16, _ b: UInt16) -> Int {
        let distance = Int(b &- a)
        guard distance > 1 else { return 0 }
        let keepalives = keepaliveSeqs.filter { k in
            let d = Int(k &- a)
            return d > 0 && d < distance
        }.count
        return max(0, distance - 1 - keepalives)
    }

    private mutating func unwrap(_ ts: UInt32) -> Int64 {
        guard let ref = tsRef else {
            tsRef = Int64(ts)
            return Int64(ts)
        }
        let delta = Int64(Int32(bitPattern: ts &- UInt32(truncatingIfNeeded: ref)))
        let u = ref + delta
        if u > ref { tsRef = u }
        return u
    }
}
