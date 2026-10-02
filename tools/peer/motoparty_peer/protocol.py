"""Motoparty wire protocol v1: control codec, framing and the voice header.

This module is the executable form of PROTOCOL.md (repo root). It is deliberately strict:
anything PROTOCOL.md does not allow is rejected with a ProtocolError that names the rule,
so the peer can point at the exact bug when it talks to the Android or iOS app.

Strictness decisions the spec leaves open (see README "Protocol notes"):
- A known message with a missing required field, a wrong JSON type, an unknown enum value
  or an explicit ``null`` fails to decode. The peer logs and drops such a frame; it does not
  close the connection (only an oversize length does, per spec).
- Unknown fields are dropped on decode (they are "ignored"), so decode(encode(x)) only ever
  contains fields this version knows.
- Integers must be JSON integers (``1``, not ``1.0``); booleans are not integers.
"""

from __future__ import annotations

import asyncio
import json
import struct
import time
from dataclasses import dataclass
from typing import Any

PROTO_VERSION = 1

CONTROL_PORT = 47800
VOICE_PORT = 47801
HTTP_PORT = 47802

SERVICE_TYPE = "_motoparty._tcp.local."

MAX_FRAME = 64 * 1024  # bytes of JSON; a larger length prefix is a protocol error

PING_INTERVAL_MS = 2000
LIVENESS_TIMEOUT_MS = 6000
RESUME_LEAD_MS = 1500
PLAY_LEAD_MS = 300
READY_TIMEOUT_MS = 8000

# Voice
SAMPLE_RATE = 16_000
FRAME_SAMPLES = 320  # 20 ms at 16 kHz
FRAME_MS = 20
VOICE_MAGIC = 0x4D
KIND_AUDIO = 1
KIND_KEEPALIVE = 2
VOICE_HEADER = struct.Struct(">BBHI")
KEEPALIVE_INTERVAL_MS = 1000


class ProtocolError(Exception):
    """A frame or message violates PROTOCOL.md."""


class FatalFrame(ProtocolError):
    """The frame cannot be a message at all (oversize, not UTF-8 JSON, not an object):
    the connection must be closed. Plain ProtocolError = drop the message, keep the link."""


class FrameTooLarge(FatalFrame):
    """Length prefix above MAX_FRAME: the connection must be closed."""


def now_ms() -> int:
    """This process's monotonic millisecond clock (the host clock when acting as host)."""
    return time.monotonic_ns() // 1_000_000


# --------------------------------------------------------------------------- control codec

# Field spec: (kind, required). kind is "int", "str", "bool", "strlist" (array of strings), a tuple of allowed strings
# (enum), ("obj", schema) or ("list", schema).
_ROLE = ("host", "client")
_BY = ("host", "client")
_REASON = ("trigger", "link", "unavailable")
_MIC = ("host",)
# Volume is local (PROTOCOL.md "Commands"): volumeUp/volumeDown are not wire actions.
_ACTION = ("pause", "resume", "next", "previous", "repeat")
# music.control mode (2026-10-01): the repeat mode to set; required with "repeat" (see
# _check_control). Unlike state.music.repeat, "off" is sent.
_SET_REPEAT = ("off", "track", "queue")
_EARCON = ("ok", "error")
# Browsing (PROTOCOL.md "Browsing")
_KIND = ("songs", "albums", "playlists", "artists")
# music.browse kind (2026-10-02, PROTOCOL.md "Browsing" 2a): absent = an album or playlist.
_BROWSE_KIND = ("artist",)
_MODE = ("now", "next", "end")
_OP = ("jump", "remove", "clear", "move")
# state.music.repeat (2026-10-01): absent = off, and the host never sends "off".
_REPEAT = ("track", "queue")
# music.download (2026-10-01, PROTOCOL.md "Browsing" 6): ids is required with "start".
_DOWNLOAD_OP = ("start", "stop")

STATE_MUSIC_SCHEMA: dict[str, tuple[Any, bool]] = {
    "id": ("str", True),
    "title": ("str", True),
    "artist": ("str", True),
    "playing": ("bool", True),
    "positionMs": ("int", True),
    "atHostTimeMs": ("int", True),
    "durationMs": ("int", True),
    "art": ("str", False),
    "repeat": (_REPEAT, False),
}

QUEUE_ITEM_SCHEMA: dict[str, tuple[Any, bool]] = {
    "id": ("str", True),
    "title": ("str", True),
    "artist": ("str", True),
    "durationMs": ("int", False),
    "art": ("str", False),
}

RESULT_ITEM_SCHEMA: dict[str, tuple[Any, bool]] = {
    "ref": ("str", True),
    "title": ("str", True),
    "artist": ("str", True),
    "durationMs": ("int", False),
    "count": ("int", False),
    "art": ("str", False),
}

DOWNLOAD_ITEM_SCHEMA: dict[str, tuple[Any, bool]] = {
    "ref": ("str", True),
    "done": ("int", True),
    "total": ("int", True),
    "failed": ("int", True),
    "running": ("bool", True),
}

ENQUEUE_TRACK_SCHEMA: dict[str, tuple[Any, bool]] = {
    "id": ("str", True),
    "title": ("str", True),
    "artist": ("str", True),
    "durationMs": ("int", True),
    "album": ("str", False),
    "art": ("str", False),
}

SCHEMAS: dict[str, dict[str, tuple[Any, bool]]] = {
    "hello": {
        "proto": ("int", True),
        "role": (_ROLE, True),
        "name": ("str", True),
        # Required when role == "host", dropped when role == "client" (see _check_hello).
        "voicePort": ("int", False),
        "httpPort": ("int", False),
        # Host only, absent = false: it interprets unparsed first phrases (PROTOCOL.md "Commands").
        "interpret": ("bool", False),
    },
    "ping": {"id": ("int", True), "t0": ("int", True)},
    "pong": {"id": ("int", True), "t0": ("int", True), "t1": ("int", True), "t2": ("int", True)},
    # mic: H->C only (PROTOCOL.md "Host-mic talk"); its only value is "host".
    "talk.open": {"by": (_BY, True), "mic": (_MIC, False)},
    "talk.close": {"by": (_BY, True), "reason": (_REASON, True)},
    "music.load": {
        "id": ("str", True),
        "path": ("str", True),
        "title": ("str", True),
        "artist": ("str", True),
        "album": ("str", False),
        "durationMs": ("int", True),
    },
    "music.ready": {"id": ("str", True)},
    "music.error": {"id": ("str", True), "message": ("str", True)},
    "music.play": {"id": ("str", True), "positionMs": ("int", True), "atHostTimeMs": ("int", True)},
    "music.pause": {"id": ("str", True), "positionMs": ("int", True)},
    # H->C: track id starts at position 0 at atHostTimeMs, gapless (PROTOCOL.md "Music flow" 6).
    "music.next": {"id": ("str", True), "atHostTimeMs": ("int", True)},
    "music.stop": {},
    "music.control": {"action": (_ACTION, True), "mode": (_SET_REPEAT, False)},
    "command.text": {"text": ("str", True), "lang": ("str", True)},
    "music.search": {"id": ("int", True), "kind": (_KIND, True), "query": ("str", True)},
    "music.browse": {"id": ("int", True), "ref": ("str", True), "kind": (_BROWSE_KIND, False)},
    "music.results": {
        "id": ("int", True),
        "items": (("list", RESULT_ITEM_SCHEMA), True),
        "error": ("str", False),
        # An artist page's albums and singles (2026-10-02), next to its top songs in items.
        "albums": (("list", RESULT_ITEM_SCHEMA), False),
    },
    "music.enqueue": {
        "mode": (_MODE, True),
        "tracks": (("list", ENQUEUE_TRACK_SCHEMA), True),
        "art": ("str", False),
    },
    # index/id are required for jump/remove, but a stale or missing pair is the host's
    # "ignore the edit" case, not a malformed frame (see host.py). `to` is required (an int >= 0)
    # for "move" (see _check_edit).
    "music.edit": {"op": (_OP, True), "index": ("int", False), "id": ("str", False), "to": ("int", False)},
    # PROTOCOL.md "Browsing" 6 (2026-10-01): a collection into the host's cache, and the marks.
    "music.download": {"op": (_DOWNLOAD_OP, True), "ref": ("str", True), "ids": ("strlist", False)},
    "music.downloads": {
        "cached": ("strlist", True),
        "downloads": (("list", DOWNLOAD_ITEM_SCHEMA), True),
    },
    # ask (only true): the text is a clarifying question (PROTOCOL.md "Commands").
    "announce": {"text": ("str", True), "earcon": (_EARCON, False), "ask": ("bool", False)},
    "state": {
        "talk": ("bool", True),
        "music": (("obj", STATE_MUSIC_SCHEMA), False),
        "queue": (("list", QUEUE_ITEM_SCHEMA), True),
        # Only while talk is true and the talk is host-mic (see _check_state).
        "mic": (_MIC, False),
        # 2026-10-01: only while a voice command's search runs ("Searching song ...").
        "busy": ("str", False),
    },
    "bye": {"reason": ("str", False)},
}

KNOWN_TYPES = frozenset(SCHEMAS)


def _check_value(where: str, kind: Any, value: Any) -> Any:
    if value is None:
        raise ProtocolError(f"{where}: null is not allowed (omit absent optional fields)")
    if kind == "int":
        if not isinstance(value, int) or isinstance(value, bool):
            raise ProtocolError(f"{where}: expected integer, got {value!r}")
        return value
    if kind == "str":
        if not isinstance(value, str):
            raise ProtocolError(f"{where}: expected string, got {value!r}")
        return value
    if kind == "strlist":
        if not isinstance(value, list):
            raise ProtocolError(f"{where}: expected array, got {value!r}")
        return [_check_value(f"{where}[{i}]", "str", v) for i, v in enumerate(value)]
    if kind == "bool":
        if not isinstance(value, bool):
            raise ProtocolError(f"{where}: expected boolean, got {value!r}")
        return value
    if isinstance(kind, tuple) and kind and kind[0] == "obj":
        return _check_object(where, kind[1], value)
    if isinstance(kind, tuple) and kind and kind[0] == "list":
        if not isinstance(value, list):
            raise ProtocolError(f"{where}: expected array, got {value!r}")
        return [_check_object(f"{where}[{i}]", kind[1], v) for i, v in enumerate(value)]
    if isinstance(kind, tuple):  # enum of strings
        if not isinstance(value, str) or value not in kind:
            raise ProtocolError(f"{where}: expected one of {list(kind)}, got {value!r}")
        return value
    raise AssertionError(kind)


def _check_object(where: str, schema: dict[str, tuple[Any, bool]], obj: Any) -> dict[str, Any]:
    if not isinstance(obj, dict):
        raise ProtocolError(f"{where}: expected object, got {obj!r}")
    out: dict[str, Any] = {}
    for name, (kind, required) in schema.items():
        if name not in obj:
            if required:
                raise ProtocolError(f"{where}: missing required field {name!r}")
            continue
        out[name] = _check_value(f"{where}.{name}", kind, obj[name])
    return out


def _check_hello(msg: dict[str, Any]) -> None:
    if msg["role"] == "host":
        for f in ("voicePort", "httpPort"):
            if f not in msg:
                raise ProtocolError(f"hello: role host requires {f!r}")
    else:
        msg.pop("voicePort", None)
        msg.pop("httpPort", None)
        msg.pop("interpret", None)


def _check_state(msg: dict[str, Any]) -> None:
    # PROTOCOL.md `state`: mic is present only while talk is true. A stray one on a closed
    # talk describes no talk, so it is dropped (like a client hello's ports), not fatal.
    if not msg["talk"]:
        msg.pop("mic", None)


def _check_edit(msg: dict[str, Any]) -> None:
    # PROTOCOL.md "Browsing" 4: a move without `to`, or with a negative one, is malformed.
    if msg["op"] == "move" and msg.get("to", -1) < 0:
        raise ProtocolError(f"music.edit: op 'move' requires an integer 'to' >= 0, got {msg.get('to')!r}")


def _check_control(msg: dict[str, Any]) -> None:
    # PROTOCOL.md "Repeat by touch": a repeat without a mode is malformed.
    if msg["action"] == "repeat" and "mode" not in msg:
        raise ProtocolError("music.control: action 'repeat' requires a 'mode'")


def _check_download(msg: dict[str, Any]) -> None:
    # PROTOCOL.md "Browsing" 6: a start without ids is malformed; a stop ignores them.
    if msg["op"] == "start" and "ids" not in msg:
        raise ProtocolError("music.download: op 'start' requires 'ids'")


def validate_message(obj: Any) -> dict[str, Any]:
    """Validate a parsed JSON value; return the normalised message.

    Known types come back with only their known fields. Unknown types come back as
    ``{"t": <type>}`` so the dispatcher can ignore them (see ``is_known``).
    """
    if not isinstance(obj, dict):
        raise FatalFrame(f"message must be a JSON object, got {type(obj).__name__}")
    t = obj.get("t")
    if not isinstance(t, str):
        raise ProtocolError(f"message needs a string field 't', got {t!r}")
    schema = SCHEMAS.get(t)
    if schema is None:
        return {"t": t}
    out = {"t": t}
    out.update(_check_object(t, schema, obj))
    if t == "hello":
        _check_hello(out)
    elif t == "state":
        _check_state(out)
    elif t == "music.edit":
        _check_edit(out)
    elif t == "music.control":
        _check_control(out)
    elif t == "music.download":
        _check_download(out)
    return out


def is_known(msg: dict[str, Any]) -> bool:
    return msg.get("t") in KNOWN_TYPES


def decode_message(payload: bytes) -> dict[str, Any]:
    """Frame body (UTF-8 JSON) -> validated message."""
    try:
        text = payload.decode("utf-8")
    except UnicodeDecodeError as e:
        raise FatalFrame(f"frame is not valid UTF-8: {e}") from None
    try:
        obj = json.loads(text)
    except json.JSONDecodeError as e:
        raise FatalFrame(f"frame is not valid JSON: {e}") from None
    return validate_message(obj)


def encode_message(msg: dict[str, Any]) -> bytes:
    """Validated message -> compact UTF-8 JSON (no length prefix). Rejects unknown types."""
    norm = validate_message(msg)
    if not is_known(norm):
        raise ProtocolError(f"refusing to encode unknown message type {msg.get('t')!r}")
    return json.dumps(norm, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def frame(payload: bytes) -> bytes:
    """Prefix a frame body with its u32 big-endian length."""
    if len(payload) > MAX_FRAME:
        raise FrameTooLarge(f"frame of {len(payload)} bytes exceeds {MAX_FRAME}")
    return struct.pack(">I", len(payload)) + payload


def encode_frame(msg: dict[str, Any]) -> bytes:
    return frame(encode_message(msg))


def check_length(header: bytes) -> int:
    """Parse a 4-byte length prefix; raise FrameTooLarge before any body is read."""
    if len(header) != 4:
        raise ProtocolError("length prefix must be 4 bytes")
    (n,) = struct.unpack(">I", header)
    if n > MAX_FRAME:
        raise FrameTooLarge(f"frame length {n} exceeds {MAX_FRAME}")
    return n


class FrameDecoder:
    """Incremental decoder: feed() bytes, get complete frame bodies back."""

    def __init__(self) -> None:
        self._buf = bytearray()

    def feed(self, data: bytes) -> list[bytes]:
        self._buf += data
        out = []
        while len(self._buf) >= 4:
            n = check_length(bytes(self._buf[:4]))
            if len(self._buf) < 4 + n:
                break
            out.append(bytes(self._buf[4 : 4 + n]))
            del self._buf[: 4 + n]
        return out


async def read_frame(reader: asyncio.StreamReader) -> bytes:
    """Read one frame body. Raises asyncio.IncompleteReadError on EOF, FrameTooLarge."""
    n = check_length(await reader.readexactly(4))
    return await reader.readexactly(n)


# --------------------------------------------------------------------------- voice header


@dataclass(frozen=True, slots=True)
class VoicePacket:
    kind: int
    seq: int  # u16
    ts: int  # u32, sample index at 16 kHz of the first sample
    payload: bytes = b""

    def encode(self) -> bytes:
        if self.kind not in (KIND_AUDIO, KIND_KEEPALIVE):
            raise ProtocolError(f"unknown voice kind {self.kind}")
        if self.kind == KIND_KEEPALIVE and self.payload:
            raise ProtocolError("keepalive has no payload")
        return VOICE_HEADER.pack(VOICE_MAGIC, self.kind, self.seq & 0xFFFF, self.ts & 0xFFFFFFFF) + self.payload


def decode_voice(data: bytes) -> VoicePacket | None:
    """Parse a UDP datagram; None for anything PROTOCOL.md says to drop."""
    if len(data) < VOICE_HEADER.size:
        return None
    magic, kind, seq, ts = VOICE_HEADER.unpack_from(data)
    if magic != VOICE_MAGIC or kind not in (KIND_AUDIO, KIND_KEEPALIVE):
        return None
    payload = data[VOICE_HEADER.size :]
    if kind == KIND_KEEPALIVE:
        payload = b""  # "no payload"; tolerate and ignore trailing bytes
    return VoicePacket(kind, seq, ts, payload)
