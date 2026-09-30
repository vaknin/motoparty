"""Adaptive jitter buffer for the voice stream (PROTOCOL.md "Voice", "Shedding a backlog").

A port of the reference implementation, ``android/.../core/JitterBuffer.kt``; the shared
vectors in ``fixtures/jitter.json`` pin all three (Kotlin, Swift, this one).

Pure logic, no audio and no clock of its own: callers pass ``now`` (ms) in, so it is
deterministic under test. It hands back *decisions* (play this packet / FEC-recover from
that one / conceal / silence); ``VoiceReceiver`` in voice.py turns them into PCM.

* **Talk spurts.** The first frame of a spurt plays ``target_ms`` after it arrived. A spurt
  starts at the first packet, and after every silence gap (a ``ts`` jump with contiguous
  ``seq``), also when the packet after the gap comes later than the old spurt's playout clock.
* **Shedding.** Oldest frames are dropped at spurt start until the queue spans the target, and
  during a spurt when the smallest depth over ``SHED_WINDOW`` frames due stays above the
  target (> target + 120 ms: the whole excess; > target + 40 ms: one frame). A 400 ms hard cap
  applies on every pull. Shed frames are neither loss nor underruns (``stats.shed``).
* **Underrun** = a packet arrives after its playout slot (on time up to half a frame after
  it): target +20 ms (max 200, at most once per spurt). 10 s without one: target -20 ms
  (min 40). Either change takes effect at the next spurt. The late packet is dropped.
* **Re-anchor.** Packets that keep arriving late for ``REANCHOR_AFTER_MS`` with nothing played
  in between mean the playout clock is ahead of the stream for good; the next late packet
  starts a new spurt instead of being dropped.
* **Restart.** A ``ts`` more than 3 s from the playout clock drops the queue and starts over
  at the current target.
* **Loss vs. silence.** Keepalives share ``seq``; they are never loss or activity. A real
  ``seq`` gap is loss: FEC from the successor if it is the very next frame, else PLC. With
  nothing queued: at most 3 PLC frames, then silence.

``seq``/``ts`` wrap-around is handled by unwrapping. Not thread-safe; callers lock.
"""

from __future__ import annotations

from dataclasses import dataclass

from .protocol import FRAME_MS, FRAME_SAMPLES

SAMPLES_PER_MS = 16

FRAME = "frame"  # play payload
FEC = "fec"  # decode payload (the *next* packet) with FEC to recover the missing frame
PLC = "plc"  # packet-loss concealment
SILENCE = "silence"  # between spurts, or buffering the next one


@dataclass(slots=True)
class Decision:
    kind: str
    payload: bytes | None = None
    ts: int | None = None  # unwrapped ts of the packet in `payload` (FRAME and FEC)
    seq: int | None = None  # its seq as on the wire (u16); for FEC that is the successor's


@dataclass(slots=True)
class JitterStats:
    received: int = 0  # audio packets pushed, late ones included
    keepalives: int = 0
    underruns: int = 0  # packets that arrived after their playout slot (and were dropped)
    played: int = 0
    fec: int = 0
    plc: int = 0  # concealed frames: lost, or not (yet) arrived mid-spurt
    reanchors: int = 0  # late runs that started a new spurt instead of being dropped
    shed: int = 0  # queued frames dropped to get rid of a backlog
    restarts: int = 0  # ts more than 3 s from the playout clock
    max_depth_ms: int = 0  # largest queue depth seen when a frame was due


def unwrap(raw: int, ref: int | None, modulus: int) -> int:
    """Unwraps a modular counter to the value nearest the previous one."""
    if ref is None:
        return raw
    best = ref - ref % modulus + raw
    for candidate in (best - modulus, best + modulus):
        if abs(candidate - ref) < abs(best - ref):
            best = candidate
    return best


@dataclass(slots=True)
class _Pkt:
    seq: int
    arrival: int
    payload: bytes


class JitterBuffer:
    FRAME = FRAME_SAMPLES
    MIN_MS = 40
    MAX_MS = 200
    STEP_MS = 20
    LOWER_AFTER_MS = 10_000
    BACKLOG_SLACK_MS = 200  # hard cap = MAX_MS + this
    SHED_WINDOW = 50  # frames due per shed window (1 s)
    SHED_ONE_ABOVE_MS = 40
    SHED_ALL_ABOVE_MS = 120
    RESYNC = 3 * 16_000
    REANCHOR_AFTER_MS = 100
    MAX_PACKETS = 100
    MAX_KEEPALIVES = 16
    CONCEAL_EMPTY_FRAMES = 3

    def __init__(self) -> None:
        self.target_ms = self.MIN_MS
        self.stats = JitterStats()
        self._packets: dict[int, _Pkt] = {}  # unwrapped ts -> packet
        self._keepalive_seqs: set[int] = set()
        self._late_seqs: set[int] = set()  # audio that came after its slot: seen, so not a gap
        self.playing = False  # inside a spurt: frames are due at _next_ts
        self._next_ts = 0
        self._last_seq: int | None = None
        self._last_underrun: int | None = None
        self._ts_ref: int | None = None
        self._seq_ref: int | None = None
        self._empty_pulls = 0
        self._raised_this_spurt = False
        self._late_since: int | None = None  # first late arrival since the last frame played
        self._last_played_ts: int | None = None
        self._window_pulls = 0
        self._window_min: int | None = None

    @property
    def depth_ms(self) -> int:
        return len(self._packets) * FRAME_MS

    # -- input -------------------------------------------------------------------------

    def push_keepalive(self, seq: int, now: int = 0) -> None:
        s = self._seq_ref = unwrap(seq, self._seq_ref, 1 << 16)
        self.stats.keepalives += 1
        self._keepalive_seqs.add(s)
        while len(self._keepalive_seqs) > self.MAX_KEEPALIVES:
            self._keepalive_seqs.remove(min(self._keepalive_seqs))

    def push(self, seq: int, ts: int, payload: bytes, now: int) -> str:
        """Add an audio packet (raw u16 seq, raw u32 ts).

        Returns "ok" (queued), "late" (underrun, dropped), "spurt" (behind the old spurt's
        clock but the first packet of a new one) or "reanchor".
        """
        if self._last_underrun is None:
            self._last_underrun = now
        u = self._ts_ref = unwrap(ts, self._ts_ref, 1 << 32)
        s = self._seq_ref = unwrap(seq, self._seq_ref, 1 << 16)
        self.stats.received += 1
        if self.playing and abs(u - self._next_ts) > self.RESYNC:
            # The sender restarted or we fell hopelessly behind: start over, keep the target.
            self.stats.restarts += 1
            self._packets.clear()
            self.playing = False
            self._last_seq = None
            self._last_played_ts = None
        if self.playing and u < self._next_ts - self.FRAME // 2:
            played = self._last_played_ts
            if (not self._packets and played is not None and u - played >= 2 * self.FRAME
                    and self._only_keepalives_before(s)):
                # Not late: the sender was silent since the last played frame and this first
                # packet of the next spurt merely took longer than the old spurt's timeline
                # allowed. End that spurt; pull() starts the new one.
                self.playing = False
                self._packets[u] = _Pkt(s, now, payload)
                return "spurt"
            if self._late_since is None:
                self._late_since = now
            if now - self._late_since >= self.REANCHOR_AFTER_MS:
                # Late for a while and nothing played: the playout clock is ahead of this
                # stream and stays there. Start a new spurt at this packet, keep the target
                # (already raised by the first late packet of this run).
                self.stats.reanchors += 1
                self.playing = False
                self._last_seq = None
                self._late_since = None
                for k in [k for k in self._packets if k < u]:
                    del self._packets[k]
                self._packets.setdefault(u, _Pkt(s, now, payload))
                return "reanchor"
            self.stats.underruns += 1
            self._last_underrun = now
            if not self._raised_this_spurt:  # one late burst is one underrun event
                self.target_ms = min(self.MAX_MS, self.target_ms + self.STEP_MS)
            self._raised_this_spurt = True
            # Not loss either: if it was the spurt's last packet, the silence gap after it
            # must still start a new spurt instead of being concealed.
            self._late_seqs.add(s)
            while len(self._late_seqs) > self.MAX_KEEPALIVES:
                self._late_seqs.remove(min(self._late_seqs))
            return "late"
        self._packets.setdefault(u, _Pkt(s, now, payload))
        while len(self._packets) > self.MAX_PACKETS:
            del self._packets[min(self._packets)]
        return "ok"

    # -- output ------------------------------------------------------------------------

    def pull(self, now: int) -> Decision:
        """Called once per 20 ms of output."""
        while True:
            d = self._pull(now)
            if d is not None:
                return d

    def _pull(self, now: int) -> Decision | None:
        pk = self._packets
        if self._last_underrun is not None and now - self._last_underrun >= self.LOWER_AFTER_MS:
            self._last_underrun = now
            if self.target_ms > self.MIN_MS:
                self.target_ms -= self.STEP_MS
        # Hard cap only; normal changes wait for a spurt start or the shed window.
        while len(pk) * FRAME_MS > self.MAX_MS + self.BACKLOG_SLACK_MS:
            self._shed_oldest()

        if not self.playing:
            if not pk:
                return Decision(SILENCE)
            oldest = min(pk)
            if now - pk[oldest].arrival < self.target_ms:
                return Decision(SILENCE)
            # A burst queued before the spurt could start: keep no more than the target of it.
            newest = max(pk)
            while newest - min(pk) > self.target_ms * SAMPLES_PER_MS:
                self._shed_oldest()
            self.playing = True
            self._raised_this_spurt = False
            self._next_ts = min(pk)
            self._window_pulls = 0
            self._window_min = None

        # A frame is due: note the depth, and once per window shed what never drained.
        depth = len(pk)
        self.stats.max_depth_ms = max(self.stats.max_depth_ms, depth * FRAME_MS)
        if self._window_min is None or depth < self._window_min:
            self._window_min = depth
        self._window_pulls += 1
        if self._window_pulls >= self.SHED_WINDOW:
            min_ms = self._window_min * FRAME_MS
            if min_ms > self.target_ms + self.SHED_ALL_ABOVE_MS:
                drop = (min_ms - self.target_ms) // FRAME_MS
            elif min_ms > self.target_ms + self.SHED_ONE_ABOVE_MS:
                drop = 1
            else:
                drop = 0
            for _ in range(min(drop, len(pk))):
                self._shed_oldest()
            self._window_pulls = 0
            self._window_min = None

        half = self.FRAME // 2
        first_ts = min(pk) if pk else None
        if first_ts is not None and first_ts < self._next_ts + half:
            p = pk.pop(first_ts)
            self._last_seq = p.seq
            self._last_played_ts = first_ts
            self._next_ts = first_ts + self.FRAME
            self._empty_pulls = 0
            self._late_since = None
            self.stats.played += 1
            return Decision(FRAME, p.payload, first_ts, p.seq & 0xFFFF)
        if first_ts is not None and self._only_keepalives_before(pk[first_ts].seq):
            # Silence gap: the next packet starts a new spurt with the current target.
            self.playing = False
            return None  # pull again, not playing
        self._next_ts += self.FRAME
        if first_ts is None:
            # Nothing queued: loss or the sender went quiet. Conceal briefly, then fall silent
            # rather than let PLC stretch the last phoneme.
            self._empty_pulls += 1
            if self._empty_pulls <= self.CONCEAL_EMPTY_FRAMES:
                self.stats.plc += 1
                return Decision(PLC)
            return Decision(SILENCE)
        self._empty_pulls = 0
        if first_ts < self._next_ts + half:
            self.stats.fec += 1
            self._late_since = None
            p = pk[first_ts]
            return Decision(FEC, p.payload, first_ts, p.seq & 0xFFFF)
        self.stats.plc += 1
        return Decision(PLC)

    def _shed_oldest(self) -> None:
        """Drops the oldest queued frame as if it had played: its successor is next, not a gap."""
        if not self._packets:
            return
        ts = min(self._packets)
        self._last_seq = self._packets.pop(ts).seq
        self._next_ts = ts + self.FRAME
        self.stats.shed += 1

    def _only_keepalives_before(self, seq: int) -> bool:
        """True if every seq strictly between the last played packet and ``seq`` was a
        keepalive (or an audio packet that came too late to play)."""
        last = self._last_seq
        if last is None:
            return True
        if seq <= last or seq - last - 1 > self.MAX_KEEPALIVES:
            return False
        return all(s in self._keepalive_seqs or s in self._late_seqs for s in range(last + 1, seq))
