"""Sound devices (sounddevice/PortAudio, imported lazily) and the test tone."""

from __future__ import annotations

import math
import struct
from collections.abc import Callable

from .protocol import FRAME_SAMPLES, SAMPLE_RATE


class Tone:
    """440 Hz test signal in 20 ms int16 frames, phase-continuous.

    By default it beeps (400 ms on, 100 ms off, 5 ms ramps). A steady tone is stationary, so
    the SILK voice-activity detector reclassifies it as background after ~0.8 s; a host that
    closes talk on "no voice activity" would then hang up on it. The beeps keep VAD on.
    ``on_ms=None`` gives the steady tone.
    """

    def __init__(self, freq: float = 440.0, amplitude: float = 0.3, on_ms: int | None = 400, off_ms: int = 100) -> None:
        self.step = 2 * math.pi * freq / SAMPLE_RATE
        self.amp = 32767 * amplitude
        self.on = None if on_ms is None else on_ms * SAMPLE_RATE // 1000
        self.period = None if on_ms is None else (on_ms + off_ms) * SAMPLE_RATE // 1000
        self.ramp = 5 * SAMPLE_RATE // 1000
        self.n = 0

    def _gain(self, k: int) -> float:
        if self.on is None:
            return 1.0
        ph = k % self.period
        if ph >= self.on:
            return 0.0
        edge = min(ph, self.on - 1 - ph)
        return 1.0 if edge >= self.ramp else 0.5 - 0.5 * math.cos(math.pi * edge / self.ramp)

    def frame(self) -> bytes:
        start = self.n
        self.n += FRAME_SAMPLES
        return struct.pack(
            f"<{FRAME_SAMPLES}h",
            *(int(self.amp * self._gain(k) * math.sin(self.step * k)) for k in range(start, start + FRAME_SAMPLES)),
        )


def _sd():
    import sounddevice  # noqa: PLC0415 - PortAudio only needed with real audio

    return sounddevice


def describe_devices() -> str:
    sd = _sd()
    try:
        return f"in: {sd.query_devices(kind='input')['name']}, out: {sd.query_devices(kind='output')['name']}"
    except Exception as e:  # pragma: no cover
        return f"(no default device: {e})"


class Mic:
    """16 kHz mono capture in 320-sample blocks; on_frame runs on the PortAudio thread."""

    def __init__(self, on_frame: Callable[[bytes], None], device=None) -> None:
        self._on_frame = on_frame
        self._device = device
        self._stream = None
        self._pending = bytearray()

    def _cb(self, indata, frames, time_info, status) -> None:
        self._pending += bytes(indata)
        n = FRAME_SAMPLES * 2
        while len(self._pending) >= n:
            chunk = bytes(self._pending[:n])
            del self._pending[:n]
            self._on_frame(chunk)

    def start(self) -> None:
        if self._stream is not None:
            return
        sd = _sd()
        self._pending.clear()
        self._stream = sd.RawInputStream(
            samplerate=SAMPLE_RATE, channels=1, dtype="int16", blocksize=FRAME_SAMPLES,
            latency="low", device=self._device, callback=self._cb,
        )
        self._stream.start()

    def stop(self) -> None:
        if self._stream is not None:
            self._stream.stop()
            self._stream.close()
            self._stream = None


class Speaker:
    """16 kHz mono playback; pulls one 20 ms frame at a time from pull()."""

    def __init__(self, pull: Callable[[], bytes], device=None) -> None:
        self._pull = pull
        self._device = device
        self._stream = None
        self._fifo = bytearray()

    def _cb(self, outdata, frames, time_info, status) -> None:
        need = frames * 2
        while len(self._fifo) < need:
            self._fifo += self._pull()
        outdata[:] = bytes(self._fifo[:need])
        del self._fifo[:need]

    def start(self) -> None:
        if self._stream is not None:
            return
        sd = _sd()
        self._stream = sd.RawOutputStream(
            samplerate=SAMPLE_RATE, channels=1, dtype="int16", blocksize=FRAME_SAMPLES,
            latency="low", device=self._device, callback=self._cb,
        )
        self._stream.start()

    def stop(self) -> None:
        if self._stream is not None:
            self._stream.stop()
            self._stream.close()
            self._stream = None
