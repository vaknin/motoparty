"""Host clock offset estimator (PROTOCOL.md "Clock")."""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class ClockSample:
    rtt: int | float
    offset: float  # hostClock - clientClock


class ClockEstimator:
    """Keep the last 8 accepted samples; estimate = offset of the smallest-rtt one (ties: newest).

    Samples with rtt < 0 are discarded and do not enter the window. A sample whose offset is
    more than ``500 + rtt / 2`` ms (its own rtt) from the current estimate means a clock
    stepped (the iOS monotonic clock stops in sleep): the window is cleared and starts over
    from that sample. A slow pong alone is off by at most half its round trip and is kept.
    """

    WINDOW = 8
    STEP_MS = 500

    def __init__(self) -> None:
        self._samples: deque[ClockSample] = deque(maxlen=self.WINDOW)
        self.resets = 0  # step resets so far

    @staticmethod
    def sample(t0: int, t1: int, t2: int, t3: int) -> ClockSample:
        rtt = (t3 - t0) - (t2 - t1)
        offset = ((t1 - t0) + (t2 - t3)) / 2
        return ClockSample(rtt, offset)

    def add(self, t0: int, t1: int, t2: int, t3: int) -> ClockSample | None:
        """Add a ping/pong exchange; returns the sample, or None if it was discarded."""
        s = self.sample(t0, t1, t2, t3)
        if s.rtt < 0:
            return None
        est = self.offset
        if est is not None and abs(s.offset - est) > self.STEP_MS + s.rtt / 2:
            self._samples.clear()
            self.resets += 1
        self._samples.append(s)
        return s

    def clear(self) -> None:
        """Forget everything: for a different host. A reconnect to the same host keeps the window."""
        self._samples.clear()

    @property
    def ready(self) -> bool:
        return bool(self._samples)

    @property
    def best(self) -> ClockSample | None:
        best = None
        for s in self._samples:  # oldest -> newest, so "<=" makes ties go to the newest
            if best is None or s.rtt <= best.rtt:
                best = s
        return best

    @property
    def offset(self) -> float | None:
        b = self.best
        return None if b is None else b.offset

    def host_to_local(self, host_ms: float) -> float:
        off = self.offset
        if off is None:
            raise RuntimeError("no clock samples yet")
        return host_ms - off

    def local_to_host(self, local_ms: float) -> float:
        off = self.offset
        if off is None:
            raise RuntimeError("no clock samples yet")
        return local_ms + off
