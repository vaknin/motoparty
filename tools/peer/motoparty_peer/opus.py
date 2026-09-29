"""Minimal ctypes binding to the system libopus (no Python dependency).

Encoder settings are the ones PROTOCOL.md fixes: 16 kHz mono, 20 ms frames, application
VOIP, 24 kbps, in-band FEC with 10 % expected loss, DTX on.
"""

from __future__ import annotations

import ctypes
import ctypes.util
import sys

from .protocol import FRAME_SAMPLES, SAMPLE_RATE

OPUS_OK = 0
OPUS_APPLICATION_VOIP = 2048
OPUS_SET_BITRATE_REQUEST = 4002
OPUS_SET_INBAND_FEC_REQUEST = 4012
OPUS_SET_PACKET_LOSS_PERC_REQUEST = 4014
OPUS_SET_DTX_REQUEST = 4016
OPUS_GET_IN_DTX_REQUEST = 4049

BITRATE = 24_000
LOSS_PERC = 10
MAX_PACKET = 1500


class OpusError(RuntimeError):
    pass


def _load() -> ctypes.CDLL:
    names = ["libopus.so.0", "libopus.so", "libopus.0.dylib", "opus.dll"]
    found = ctypes.util.find_library("opus")
    if found:
        names.insert(0, found)
    errors = []
    for name in names:
        try:
            return ctypes.CDLL(name)
        except OSError as e:
            errors.append(f"{name}: {e}")
    raise OpusError("libopus not found (" + "; ".join(errors) + ")")


_lib: ctypes.CDLL | None = None


def lib() -> ctypes.CDLL:
    global _lib
    if _lib is None:
        L = _load()
        L.opus_get_version_string.restype = ctypes.c_char_p
        L.opus_strerror.restype = ctypes.c_char_p
        L.opus_strerror.argtypes = [ctypes.c_int]
        L.opus_encoder_create.restype = ctypes.c_void_p
        L.opus_encoder_create.argtypes = [ctypes.c_int32, ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
        L.opus_encoder_destroy.argtypes = [ctypes.c_void_p]
        L.opus_encode.restype = ctypes.c_int32
        L.opus_encode.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_int32]
        L.opus_decoder_create.restype = ctypes.c_void_p
        L.opus_decoder_create.argtypes = [ctypes.c_int32, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
        L.opus_decoder_destroy.argtypes = [ctypes.c_void_p]
        L.opus_decode.restype = ctypes.c_int
        L.opus_decode.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int32, ctypes.c_char_p, ctypes.c_int, ctypes.c_int]
        # opus_encoder_ctl is variadic: leave argtypes unset and pass ctypes objects explicitly.
        _lib = L
    return _lib


def version() -> str:
    return lib().opus_get_version_string().decode()


def _check(code: int, what: str) -> int:
    if code < 0:
        raise OpusError(f"{what}: {lib().opus_strerror(code).decode()}")
    return code


class OpusEncoder:
    def __init__(self, bitrate: int = BITRATE, fec: bool = True, loss_perc: int = LOSS_PERC, dtx: bool = True) -> None:
        err = ctypes.c_int()
        self._enc = lib().opus_encoder_create(SAMPLE_RATE, 1, OPUS_APPLICATION_VOIP, ctypes.byref(err))
        _check(err.value, "opus_encoder_create")
        self._ctl(OPUS_SET_BITRATE_REQUEST, bitrate)
        self._ctl(OPUS_SET_INBAND_FEC_REQUEST, 1 if fec else 0)
        self._ctl(OPUS_SET_PACKET_LOSS_PERC_REQUEST, loss_perc)
        self._ctl(OPUS_SET_DTX_REQUEST, 1 if dtx else 0)
        self._out = ctypes.create_string_buffer(MAX_PACKET)

    def _ctl(self, request: int, value: int) -> None:
        _check(lib().opus_encoder_ctl(ctypes.c_void_p(self._enc), ctypes.c_int(request), ctypes.c_int(value)), f"ctl {request}")

    @property
    def in_dtx(self) -> bool:
        """True if the last frame was a DTX comfort-noise update or was not coded at all."""
        v = ctypes.c_int()
        _check(lib().opus_encoder_ctl(ctypes.c_void_p(self._enc), ctypes.c_int(OPUS_GET_IN_DTX_REQUEST), ctypes.byref(v)), "get in_dtx")
        return bool(v.value)

    def encode(self, pcm: bytes) -> bytes:
        """320 int16 little-endian mono samples -> Opus packet (<= 2 bytes means "don't send")."""
        if len(pcm) != FRAME_SAMPLES * 2:
            raise ValueError(f"expected {FRAME_SAMPLES * 2} bytes of PCM, got {len(pcm)}")
        n = _check(lib().opus_encode(self._enc, pcm, FRAME_SAMPLES, self._out, MAX_PACKET), "opus_encode")
        return self._out.raw[:n]

    def close(self) -> None:
        if self._enc:
            lib().opus_encoder_destroy(self._enc)
            self._enc = None

    def __del__(self) -> None:  # pragma: no cover - best effort
        try:
            self.close()
        except Exception:
            pass


class OpusDecoder:
    def __init__(self) -> None:
        err = ctypes.c_int()
        self._dec = lib().opus_decoder_create(SAMPLE_RATE, 1, ctypes.byref(err))
        _check(err.value, "opus_decoder_create")
        self._pcm = ctypes.create_string_buffer(FRAME_SAMPLES * 2)

    def _decode(self, data: bytes | None, fec: bool) -> bytes:
        if data is None:
            n = lib().opus_decode(self._dec, None, 0, self._pcm, FRAME_SAMPLES, 0)
        else:
            n = lib().opus_decode(self._dec, data, len(data), self._pcm, FRAME_SAMPLES, 1 if fec else 0)
        _check(n, "opus_decode")
        return self._pcm.raw[: n * 2]

    def decode(self, packet: bytes) -> bytes:
        return self._decode(packet, False)

    def decode_fec(self, next_packet: bytes) -> bytes:
        """Recover the frame *before* next_packet from its in-band FEC data."""
        return self._decode(next_packet, True)

    def conceal(self) -> bytes:
        """Packet-loss concealment for one missing 20 ms frame."""
        return self._decode(None, False)

    def close(self) -> None:
        if self._dec:
            lib().opus_decoder_destroy(self._dec)
            self._dec = None

    def __del__(self) -> None:  # pragma: no cover
        try:
            self.close()
        except Exception:
            pass


def is_dtx_packet(packet: bytes) -> bool:
    """libopus signals "no need to transmit" with a packet of 2 bytes or less."""
    return len(packet) <= 2


def is_voice_activity(packet: bytes) -> bool:
    """Does this received packet carry voice activity (a "non-DTX frame")? Host stats only.

    With DTX on, libopus still emits a comfort-noise update every ~400 ms of silence, and
    with any background noise those are full-size (40-55 bytes at these settings), so size
    cannot tell them from speech. The SILK layer can: for SILK and hybrid packets (TOC
    config < 16) with one frame or two equal frames (code 0/1), RFC 6716 4.2.3 puts the
    first frame's VAD flag in the top bit of the byte after the TOC, and comfort-noise
    updates always have it clear. CELT-only and code 2/3 packets count as activity.

    Caveat: SILK's VAD also clears the flag for DTX hangover frames and for a *steady*
    tone after ~0.8 s (it adapts to it as background) - hence the beeping --tone.
    """
    if len(packet) <= 2:
        return False
    toc = packet[0]
    config, code = toc >> 3, toc & 0x3
    if config < 16 and code in (0, 1):
        return bool(packet[1] & 0x80)
    return True


if __name__ == "__main__":  # pragma: no cover
    print(version(), file=sys.stderr)
