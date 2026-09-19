"""Voice over UDP: sender (Opus + header + keepalive), receiver (jitter buffer + decoder)."""

from __future__ import annotations

import asyncio
import random
import threading
from collections.abc import Callable
from dataclasses import dataclass

from . import jitter
from .jitter import JitterBuffer
from .opus import OpusDecoder, OpusEncoder, is_dtx_packet
from .protocol import (
    FRAME_MS,
    FRAME_SAMPLES,
    KEEPALIVE_INTERVAL_MS,
    KIND_AUDIO,
    KIND_KEEPALIVE,
    VoicePacket,
    decode_voice,
    now_ms,
)

SILENT_FRAME = bytes(FRAME_SAMPLES * 2)


@dataclass(slots=True)
class SenderStats:
    audio: int = 0
    keepalive: int = 0
    dtx_skipped: int = 0


class VoiceSender:
    """Numbers frames and packets. ``send`` is the raw datagram sink.

    seq: +1 per packet sent (audio and keepalive). ts: +320 per 20 ms frame, including frames
    DTX suppressed; at the start of a new talk session ts also jumps by the wall-clock time
    since the last frame, so the closed period looks like one long DTX gap to the receiver.
    Both start at random values so the other side's wrap handling gets exercised.
    """

    def __init__(self, send: Callable[[bytes], None], encoder: OpusEncoder | None = None) -> None:
        self._send = send
        self.encoder = encoder or OpusEncoder()
        self.seq = random.randrange(1 << 16)
        self.ts = random.randrange(1 << 32)
        self.last_sent_ms: int | None = None
        self._last_frame_ms: int | None = None
        self.stats = SenderStats()
        self._lock = threading.Lock()

    def _emit(self, pkt: VoicePacket) -> None:
        self._send(pkt.encode())
        self.seq = (self.seq + 1) & 0xFFFF
        self.last_sent_ms = now_ms()

    def begin_session(self) -> None:
        with self._lock:
            if self._last_frame_ms is not None:
                gap = max(0, now_ms() - self._last_frame_ms) // FRAME_MS
                self.ts = (self.ts + gap * FRAME_SAMPLES) & 0xFFFFFFFF

    def send_pcm(self, pcm: bytes) -> bool:
        """Encode one 20 ms frame and send it unless DTX suppresses it. Returns True if sent."""
        with self._lock:
            packet = self.encoder.encode(pcm)
            ts = self.ts
            self.ts = (self.ts + FRAME_SAMPLES) & 0xFFFFFFFF
            self._last_frame_ms = now_ms()
            # Skip what libopus says need not be sent (<= 2 bytes) and also the comfort-noise
            # updates it emits every ~400 ms while in DTX: then every audio packet on the wire
            # is a "non-DTX frame" for the peer's talk silence timer (see README).
            if is_dtx_packet(packet) or self.encoder.in_dtx:
                self.stats.dtx_skipped += 1
                return False
            self._emit(VoicePacket(KIND_AUDIO, self.seq, ts, packet))
            self.stats.audio += 1
            return True

    def keepalive_due(self) -> bool:
        return self.last_sent_ms is None or now_ms() - self.last_sent_ms >= KEEPALIVE_INTERVAL_MS

    def send_keepalive(self) -> None:
        with self._lock:
            self._emit(VoicePacket(KIND_KEEPALIVE, self.seq, self.ts))
            self.stats.keepalive += 1


class VoiceReceiver:
    """Jitter buffer + Opus decoder. push() from the network, pull_pcm() from the output clock."""

    def __init__(self) -> None:
        self.jb = JitterBuffer(now_ms())
        self.decoder = OpusDecoder()
        self._lock = threading.Lock()
        self.last_rx_ms: int | None = None
        self.last_audio_ms: int | None = None

    def push(self, pkt: VoicePacket) -> None:
        now = now_ms()
        with self._lock:
            self.last_rx_ms = now
            if pkt.kind == KIND_KEEPALIVE:
                self.jb.push_keepalive(pkt.seq, now)
            else:
                self.last_audio_ms = now
                self.jb.push(pkt.seq, pkt.ts, pkt.payload, now)

    def pull_pcm(self) -> bytes:
        with self._lock:
            d = self.jb.pull(now_ms())
            try:
                if d.kind == jitter.FRAME:
                    return self.decoder.decode(d.payload)
                if d.kind == jitter.FEC:
                    return self.decoder.decode_fec(d.payload)
                if d.kind == jitter.PLC:
                    return self.decoder.conceal()
            except Exception:
                return SILENT_FRAME  # a corrupt packet must not kill the audio thread
            return SILENT_FRAME

    def summary(self) -> str:
        with self._lock:
            s, jb = self.jb.stats, self.jb
            return (
                f"rx={s.received} ka={s.keepalives} played={s.played} fec={s.fec} plc={s.plc} "
                f"late={s.late} dup={s.duplicates} dtx={s.dtx_silence} starved={s.starved} "
                f"underruns={s.underruns} trimmed={s.trimmed} depth={jb.depth_ms:.0f}ms "
                f"target={jb.target_ms}ms"
            )


class VoiceProtocol(asyncio.DatagramProtocol):
    """UDP socket glue: hands valid packets and their source address to a callback."""

    def __init__(self, on_packet: Callable[[VoicePacket, tuple], None]) -> None:
        self.on_packet = on_packet
        self.transport: asyncio.DatagramTransport | None = None
        self.dropped = 0

    def connection_made(self, transport) -> None:  # type: ignore[override]
        self.transport = transport

    def datagram_received(self, data: bytes, addr) -> None:  # type: ignore[override]
        pkt = decode_voice(data)
        if pkt is None:
            self.dropped += 1
            return
        self.on_packet(pkt, addr)

    def error_received(self, exc) -> None:  # type: ignore[override]
        pass  # ICMP port unreachable etc.; UDP is fire-and-forget


class Pacer:
    """Drift-free 20 ms tick on the asyncio loop."""

    def __init__(self, period_ms: int = FRAME_MS) -> None:
        self.period = period_ms / 1000

    async def ticks(self):
        loop = asyncio.get_running_loop()
        nxt = loop.time()
        while True:
            nxt += self.period
            delay = nxt - loop.time()
            if delay < -0.2:  # fell far behind (suspend, debugger): resync
                nxt = loop.time()
                delay = 0
            await asyncio.sleep(max(0.0, delay))
            yield
