"""Adaptive jitter buffer for the voice stream (PROTOCOL.md "Voice").

Pure logic, no audio and no clock of its own: callers pass ``now_ms`` in, so it is
deterministic under test. It hands back *decisions* (play this packet / FEC-recover from
that one / conceal / silence); ``VoiceReceiver`` in voice.py turns them into PCM.

Model (the spec fixes the numbers, this fixes the meaning):

* **Depth** = audio buffered ahead of the playout point, including the frame about to be
  played: ``newest_ts + 320 - play_ts`` in ms. **Target depth** starts at 40 ms.
* **Anchoring.** When idle, playout (re)starts at the oldest buffered frame as soon as the
  buffered span reaches the target *or* the oldest packet has waited ``target`` ms (so a
  lone packet still plays). This happens at the start of every talk spurt.
* **Per pull (every 20 ms):** the frame at ``play_ts`` if present. Otherwise, if a later
  packet is buffered: count the lost packets between the last consumed seq and that packet's
  seq (keepalive seqs are not losses). The ``lost`` frames right before the later packet are
  concealed - FEC from that packet for the frame immediately before it, PLC for the others.
  Any other missing frame is a DTX gap (seq contiguous, ts jumped) and plays as silence,
  not loss. If nothing later is buffered: PLC for up to 3 frames, then back to idle.
* **Underrun** = a packet arrives after its playout slot has passed (it was needed but not
  there). Target +20 ms (max 200). If it is the direct successor of the last frame played
  (we have only concealed since), playout re-anchors on it with the new target; otherwise it
  is dropped and 20 ms is inserted at the next silence (DTX gap or empty buffer).
* **Decrease** = every 10 s without an underrun, target -20 ms (min 40).
* **Trim**: every 50 pulls (1 s), if the minimum depth seen in that second was
  >= target + 20 ms, one frame is dropped. That applies a lowered target and absorbs a
  sender clock that runs fast.
"""

from __future__ import annotations

from dataclasses import dataclass

from .protocol import FRAME_SAMPLES

SAMPLES_PER_MS = 16

FRAME = "frame"  # play payload
FEC = "fec"  # decode payload (the *next* packet) with FEC to recover the missing frame
PLC = "plc"  # packet-loss concealment
SILENCE = "silence"  # DTX gap / idle


@dataclass(slots=True)
class Decision:
    kind: str
    payload: bytes | None = None
    ts: int | None = None  # unwrapped ts of the frame this decision stands for


@dataclass(slots=True)
class JitterStats:
    received: int = 0
    keepalives: int = 0
    duplicates: int = 0
    late: int = 0
    underruns: int = 0
    played: int = 0
    fec: int = 0
    plc: int = 0  # concealed lost packets
    starved: int = 0  # PLC because nothing was buffered
    dtx_silence: int = 0
    trimmed: int = 0
    stretched: int = 0
    resets: int = 0


def _unwrap(raw: int, ref: int | None, bits: int) -> int:
    if ref is None:
        return raw
    mod = 1 << bits
    diff = (raw - ref) % mod
    if diff >= mod >> 1:
        diff -= mod
    return ref + diff


@dataclass(slots=True)
class _Pkt:
    seq: int
    payload: bytes
    arrival: int


class JitterBuffer:
    MIN_MS = 40
    MAX_MS = 200
    STEP_MS = 20
    DECREASE_AFTER_MS = 10_000
    EMPTY_PLC_FRAMES = 3
    TRIM_WINDOW = 50
    RESET_TS_JUMP = 60 * 1000 * SAMPLES_PER_MS  # > 60 s jump: treat as a new stream
    RESET_SEQ_JUMP = 3000

    def __init__(self, now_ms: int = 0) -> None:
        self.target_ms = self.MIN_MS
        self._last_adjust = now_ms
        self.stats = JitterStats()
        self._reset_stream()

    # -- state -------------------------------------------------------------------------

    def _reset_stream(self) -> None:
        self._buf: dict[int, _Pkt] = {}
        self._seen: set[int] = set()
        self._keepalive_seqs: set[int] = set()
        self._seq_ref: int | None = None
        self._ts_ref: int | None = None
        self.playing = False
        self._play_ts: int | None = None
        self._last_seq: int | None = None
        self._empty = 0
        self._pending_stretch = 0
        self._pending_trim = False
        self._win_n = 0
        self._win_min: float | None = None

    @property
    def depth_ms(self) -> float:
        if not self._buf:
            return 0.0
        start = self._play_ts if self.playing and self._play_ts is not None else min(self._buf)
        return (max(self._buf) + FRAME_SAMPLES - start) / SAMPLES_PER_MS

    def _underrun(self, now: int) -> None:
        self.stats.underruns += 1
        self.target_ms = min(self.target_ms + self.STEP_MS, self.MAX_MS)
        self._last_adjust = now

    def _maybe_decrease(self, now: int) -> None:
        if now - self._last_adjust >= self.DECREASE_AFTER_MS:
            self._last_adjust = now
            if self.target_ms > self.MIN_MS:
                self.target_ms = max(self.target_ms - self.STEP_MS, self.MIN_MS)

    def _unwrap_seq(self, seq: int) -> int:
        s = _unwrap(seq, self._seq_ref, 16)
        if self._seq_ref is None or s > self._seq_ref:
            self._seq_ref = s
        return s

    def _prune(self) -> None:
        if self._last_seq is None:
            return
        floor = self._last_seq - 512
        if len(self._seen) > 1024:
            self._seen = {s for s in self._seen if s > floor}
        if len(self._keepalive_seqs) > 1024:
            self._keepalive_seqs = {s for s in self._keepalive_seqs if s > floor}

    def _jumped(self, seq: int, ts: int | None) -> bool:
        if self._seq_ref is not None:
            d = (seq - self._seq_ref) % 65536
            if min(d, 65536 - d) > self.RESET_SEQ_JUMP:
                return True
        if ts is not None and self._ts_ref is not None:
            d = (ts - self._ts_ref) % (1 << 32)
            if min(d, (1 << 32) - d) > self.RESET_TS_JUMP:
                return True
        return False

    # -- input -------------------------------------------------------------------------

    def push_keepalive(self, seq: int, now: int) -> None:
        if self._jumped(seq, None):
            self.stats.resets += 1
            self._reset_stream()
        self.stats.keepalives += 1
        self._keepalive_seqs.add(self._unwrap_seq(seq))

    def push(self, seq: int, ts: int, payload: bytes, now: int) -> str:
        """Add an audio packet (raw u16 seq, raw u32 ts). Returns "ok", "dup" or "late"."""
        if self._jumped(seq, ts):
            self.stats.resets += 1
            self._reset_stream()
        s = self._unwrap_seq(seq)
        t = _unwrap(ts, self._ts_ref, 32)
        if self._ts_ref is None or t > self._ts_ref:
            self._ts_ref = t
        self.stats.received += 1
        if s in self._seen or t in self._buf:
            self.stats.duplicates += 1
            return "dup"
        self._seen.add(s)
        self._prune()
        if self._play_ts is not None and t < self._play_ts:
            self.stats.late += 1
            self._underrun(now)
            if self._last_seq is None or s == self._last_seq + 1:
                # Only concealment since the last real frame: replay from here, anchored anew.
                self.playing = False
                self._play_ts = None
                self._empty = 0
                self._buf = {k: v for k, v in self._buf.items() if k > t}
                self._buf[t] = _Pkt(s, payload, now)
            else:
                self._pending_stretch += 1
            return "late"
        self._buf[t] = _Pkt(s, payload, now)
        return "ok"

    # -- output ------------------------------------------------------------------------

    def pull(self, now: int) -> Decision:
        """Called once per 20 ms of output."""
        self._maybe_decrease(now)
        if not self.playing:
            if not self._buf:
                return Decision(SILENCE)
            oldest = min(self._buf)
            span_ms = (max(self._buf) + FRAME_SAMPLES - oldest) / SAMPLES_PER_MS
            waited = now - min(p.arrival for p in self._buf.values())
            if span_ms < self.target_ms and waited < self.target_ms:
                return Decision(SILENCE)
            self.playing = True
            self._play_ts = oldest
            self._empty = 0
            self._win_n, self._win_min = 0, None

        assert self._play_ts is not None
        self._track_depth()

        if self._pending_trim and self._play_ts in self._buf and self._play_ts + FRAME_SAMPLES in self._buf:
            dropped = self._buf.pop(self._play_ts)
            self._last_seq = dropped.seq
            self._play_ts += FRAME_SAMPLES
            self._pending_trim = False
            self.stats.trimmed += 1

        ts = self._play_ts
        pkt = self._buf.pop(ts, None)
        if pkt is not None:
            self._last_seq = pkt.seq
            self._play_ts += FRAME_SAMPLES
            self._empty = 0
            self.stats.played += 1
            return Decision(FRAME, pkt.payload, ts)

        if self._buf:
            self._empty = 0
            nts = min(self._buf)
            nxt = self._buf[nts]
            lost = 0
            if self._last_seq is not None:
                lo, hi = self._last_seq, nxt.seq
                lost = (hi - lo - 1) - sum(1 for k in self._keepalive_seqs if lo < k < hi)
            frames_gap = (nts - ts) // FRAME_SAMPLES
            if lost > 0 and frames_gap <= lost:
                self._play_ts += FRAME_SAMPLES
                if frames_gap == 1:
                    self.stats.fec += 1
                    return Decision(FEC, nxt.payload, ts)
                self.stats.plc += 1
                return Decision(PLC, None, ts)
            if self._pending_stretch:
                self._pending_stretch -= 1
                self.stats.stretched += 1
                return Decision(SILENCE, None, None)
            self._play_ts += FRAME_SAMPLES
            self.stats.dtx_silence += 1
            return Decision(SILENCE, None, ts)

        # Nothing buffered: the talker paused (DTX / talk over) or packets are late.
        self._empty += 1
        if self._pending_stretch:
            self._pending_stretch -= 1
            self.stats.stretched += 1
        else:
            self._play_ts += FRAME_SAMPLES
        if self._empty >= self.EMPTY_PLC_FRAMES:
            self.playing = False
        self.stats.starved += 1
        return Decision(PLC, None, ts)

    def _track_depth(self) -> None:
        d = self.depth_ms
        self._win_min = d if self._win_min is None else min(self._win_min, d)
        self._win_n += 1
        if self._win_n >= self.TRIM_WINDOW:
            if self._win_min >= self.target_ms + self.STEP_MS:
                self._pending_trim = True
            self._win_n, self._win_min = 0, None
