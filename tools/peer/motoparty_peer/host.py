"""`motoparty-peer host`: a minimal fake Pixel, for testing the iOS client without the Pixel.

Advertises over Bonjour, serves control/voice/HTTP, is the talk authority (incl. the 10 s
silence close), echoes voice back to the client, serves one track and parses command.text.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import random
import re
import socket
import threading
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from . import discovery
from .commands import UNKNOWN_ANNOUNCE, VOLUME_ACTIONS, parse_command
from .music import TrackInfo, load_track
from .opus import is_voice_activity
from .protocol import (
    CONTROL_PORT,
    HTTP_PORT,
    KIND_AUDIO,
    LIVENESS_TIMEOUT_MS,
    PLAY_LEAD_MS,
    PROTO_VERSION,
    READY_TIMEOUT_MS,
    RESUME_LEAD_MS,
    VOICE_PORT,
    FatalFrame,
    ProtocolError,
    VoicePacket,
    decode_message,
    encode_frame,
    frame,
    is_known,
    now_ms,
    read_frame,
)
from .util import js, log, stdin_lines
from .voice import VoiceProtocol

HELP = """commands: load | play | pause | stop | talk | mic on|off (refuse talk as unavailable) |
          announce <text> | state | stats | raw <json> (send unvalidated) | quit"""

C2H_ONLY = {"ping", "music.ready", "music.error", "music.control", "command.text"}


@dataclass(eq=False)
class Conn:
    reader: asyncio.StreamReader
    writer: asyncio.StreamWriter
    ip: str
    last_rx: int
    name: str | None = None
    tasks: list = field(default_factory=list)

    def close(self) -> None:
        self.writer.close()


@dataclass(slots=True)
class VoiceStats:
    audio: int = 0
    keepalive: int = 0
    active: int = 0
    echoed: int = 0
    foreign: int = 0


class Host:
    def __init__(self, args) -> None:
        self.args = args
        self.name = args.name or f"{socket.gethostname()} peer"
        self.conns: set[Conn] = set()
        self.current: Conn | None = None
        self.talk = False
        self.mic_available = True  # `mic off`: refuse talk.open with reason "unavailable"
        self.talk_opened = 0
        self.last_activity = 0
        self.resume_after_talk = False
        self.track: TrackInfo | None = load_track(Path(args.track)) if args.track else None
        self.music: dict | None = None
        self.pending_ready: dict[str, asyncio.Future] = {}
        self.loading = False
        self.voice_transport: asyncio.DatagramTransport | None = None
        self.voice_addr = None
        self.voice_seq = random.randrange(1 << 16)
        self.vstats = VoiceStats()
        self.last_voice_rx = 0

    # ------------------------------------------------------------------ lifecycle

    async def run(self) -> None:
        a = self.args
        self.loop = asyncio.get_running_loop()
        self.main = asyncio.current_task()
        server = await asyncio.start_server(self._on_connect, a.bind, a.port)
        self.port = server.sockets[0].getsockname()[1]
        self.voice_transport, _ = await self.loop.create_datagram_endpoint(
            lambda: VoiceProtocol(self._on_voice), local_addr=(a.bind, a.voice_port)
        )
        self.voice_port = self.voice_transport.get_extra_info("sockname")[1]
        httpd = ThreadingHTTPServer((a.bind, a.http_port), _make_handler(self))
        httpd.daemon_threads = True
        self.http_port = httpd.server_address[1]
        threading.Thread(target=httpd.serve_forever, name="http", daemon=True).start()
        log(f"host {self.name!r}: control tcp/{self.port} voice udp/{self.voice_port} http tcp/{self.http_port}")
        if self.track:
            t = self.track
            log(f"track: {t.path} {t.title!r} by {t.artist!r} ({t.duration_ms} ms) <- {t.file}")
        adv = None
        if not a.no_mdns:
            addrs = discovery.local_ipv4s() if a.bind in ("0.0.0.0", "") else [a.bind]
            try:
                adv = discovery.Advertiser(self.name, self.port, self.voice_port, self.http_port, addrs)
                await adv.start()
                log(f"bonjour: advertising {adv.info.name!r} on {addrs}")
            except Exception as e:
                log(f"bonjour: advertisement failed: {type(e).__name__}: {e}")
                adv = None
        stdin_task = asyncio.create_task(self._stdin())
        silence_task = asyncio.create_task(self._silence_watch())
        stats_task = asyncio.create_task(self._stats())
        try:
            async with server:
                await server.serve_forever()
        except asyncio.CancelledError:
            pass
        finally:
            for t in (stdin_task, silence_task, stats_task):
                t.cancel()
            for c in list(self.conns):
                c.close()
            httpd.shutdown()
            self.voice_transport.close()
            if adv:
                with contextlib.suppress(Exception):
                    await asyncio.wait_for(adv.close(), 3)
            log("host stopped")

    # ------------------------------------------------------------------ control

    def send(self, msg: dict, conn: Conn | None = None, *, quiet: bool = False) -> None:
        c = conn or self.current
        if c is None:
            if not quiet:
                log("(no client) would send", js(msg))
            return
        c.writer.write(encode_frame(msg))
        if not quiet or self.args.verbose:
            log(">>", js(msg))

    def hello(self) -> dict:
        return {"t": "hello", "proto": PROTO_VERSION, "role": "host", "name": self.name,
                "voicePort": self.voice_port, "httpPort": self.http_port}

    def state(self) -> dict:
        s: dict = {"t": "state", "talk": self.talk}
        if self.music:
            s["music"] = dict(self.music)
        s["queue"] = []
        return s

    def send_state(self) -> None:
        self.send(self.state())

    async def _on_connect(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        ip = writer.get_extra_info("peername")[0]
        sock = writer.get_extra_info("socket")
        if sock is not None:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        c = Conn(reader, writer, ip, now_ms())
        self.conns.add(c)
        log(f"connection from {ip}")
        self.send(self.hello(), c)
        self.send(self.state(), c)
        watchdog = asyncio.create_task(self._watchdog(c))
        try:
            while True:
                try:
                    payload = await read_frame(reader)
                except asyncio.IncompleteReadError:
                    log(f"{ip}: connection closed")
                    return
                except FatalFrame as e:
                    log(f"!! {ip}: protocol error: {e}; closing")
                    return
                t1 = now_ms()
                c.last_rx = t1
                try:
                    msg = decode_message(payload)
                except FatalFrame as e:
                    log(f"!! {ip}: protocol error: {e}; closing")
                    return
                except ProtocolError as e:
                    log(f"!! {ip}: dropped invalid frame ({e}): {payload[:300]!r}")
                    continue
                if await self._on_message(c, msg, t1) == "close":
                    return
        except (OSError, asyncio.CancelledError):
            return
        finally:
            watchdog.cancel()
            self.conns.discard(c)
            c.close()
            if self.current is c:
                self.current = None
                self._link_lost()

    async def _watchdog(self, c: Conn) -> None:
        while True:
            await asyncio.sleep(0.25)
            if now_ms() - c.last_rx > LIVENESS_TIMEOUT_MS:
                log(f"{c.ip}: link lost, nothing received for {LIVENESS_TIMEOUT_MS} ms")
                c.writer.transport.abort()
                return

    def _link_lost(self) -> None:
        log("client gone")
        if self.talk:
            self._close_talk("host", "link")

    async def _on_message(self, c: Conn, msg: dict, t1: int) -> str | None:
        t = msg["t"]
        if t == "ping":
            self.send({"t": "pong", "id": msg["id"], "t0": msg["t0"], "t1": t1, "t2": now_ms()}, c, quiet=True)
            if self.args.verbose:
                log("<<", js(msg))
            return None
        log("<<", js(msg))
        if not is_known(msg):
            log(f"   (unknown type {t!r} ignored)")
            return None
        if t == "hello":
            if msg["role"] != "client":
                log(f"   peer claims role {msg['role']!r}; closing")
                self.send({"t": "bye", "reason": "role"}, c)
                return "close"
            if msg["proto"] != PROTO_VERSION:
                log(f"   peer speaks proto {msg['proto']}; closing")
                self.send({"t": "bye", "reason": "proto"}, c)
                return "close"
            old = self.current
            c.name = msg["name"]
            self.current = c
            if old is not None and old is not c:
                log(f"   replaces the previous client at {old.ip}")
                self.send({"t": "bye", "reason": "replaced"}, old)
                old.close()
            log(f"client {c.name!r} at {c.ip} is now the client")
            return None
        if c is not self.current:
            log("   (from a connection that has not sent hello; ignored)")
            return None
        if t == "bye":
            return "close"
        if t == "talk.open":
            if self.talk:
                log("   talk already open")
                self.send_state()
            elif not self.mic_available:
                # PROTOCOL.md "Talk flow" 1: the only refusal. Talk never opens, so there is
                # no talk.close broadcast afterwards and state.talk stays false.
                log("   MIC UNAVAILABLE: refusing talk (state.talk stays false)")
                self.send({"t": "talk.close", "by": "host", "reason": "unavailable"}, c)
            else:
                self._open_talk("client")
        elif t == "talk.close":
            if self.talk:
                self._close_talk("client", msg["reason"])
            else:
                log("   talk already closed")
                self.send_state()
        elif t in ("music.ready", "music.error"):
            fut = self.pending_ready.get(msg["id"])
            if fut and not fut.done():
                fut.set_result(msg)
            else:
                log(f"   ({t} for {msg['id']!r}, which is not being loaded)")
        elif t == "music.control":
            self._music_control(msg["action"])
        elif t == "command.text":
            self._command(msg["text"])
        elif t not in C2H_ONLY:
            log(f"   ({t!r} is host-to-client; ignored)")
        return None

    # ------------------------------------------------------------------ talk

    def _open_talk(self, by: str) -> None:
        now = now_ms()
        self.talk = True
        self.talk_opened = now
        if self.music and self.music["playing"]:
            self._freeze_music(now)
            self.resume_after_talk = True
        log(f"TALK OPEN (by {by})")
        self.send({"t": "talk.open", "by": by})
        self.send_state()

    def _close_talk(self, by: str, reason: str) -> None:
        self.talk = False
        log(f"TALK CLOSED (by {by}, {reason})")
        self.send({"t": "talk.close", "by": by, "reason": reason}, quiet=self.current is None)
        if self.resume_after_talk and self.music:
            self.resume_after_talk = False
            self._play_from(self.music["positionMs"], now_ms() + RESUME_LEAD_MS)
        self.send(self.state(), quiet=self.current is None)

    async def _silence_watch(self) -> None:
        while True:
            await asyncio.sleep(0.1)
            if self.talk and now_ms() - max(self.talk_opened, self.last_activity) >= self.args.silence_ms:
                self._close_talk("host", "silence")

    # ------------------------------------------------------------------ voice

    def _on_voice(self, pkt: VoicePacket, addr) -> None:
        if self.current is None or addr[0] != self.current.ip:
            self.vstats.foreign += 1
            return
        if addr != self.voice_addr:
            log(f"voice: client voice address is now {addr[0]}:{addr[1]}")
        self.voice_addr = addr  # reply to the most recent valid packet's source
        self.last_voice_rx = now_ms()
        if pkt.kind != KIND_AUDIO:
            self.vstats.keepalive += 1
            return
        self.vstats.audio += 1
        if is_voice_activity(pkt.payload):
            self.vstats.active += 1
            self.last_activity = now_ms()
        if self.talk and self.voice_transport is not None:
            echo = VoicePacket(KIND_AUDIO, self.voice_seq, pkt.ts, pkt.payload)
            self.voice_seq = (self.voice_seq + 1) & 0xFFFF
            self.voice_transport.sendto(echo.encode(), addr)
            self.vstats.echoed += 1

    async def _stats(self) -> None:
        while True:
            await asyncio.sleep(2)
            if self.talk or now_ms() - self.last_voice_rx < 2500 and self.vstats.audio:
                v = self.vstats
                log(f"voice: audio={v.audio} active={v.active} keepalive={v.keepalive} echoed={v.echoed} "
                    f"foreign={v.foreign} silent_for={now_ms() - max(self.talk_opened, self.last_activity)}ms")

    # ------------------------------------------------------------------ music

    def _position(self, now: int) -> int:
        m = self.music
        assert m is not None
        pos = m["positionMs"] + max(0, now - m["atHostTimeMs"]) if m["playing"] else m["positionMs"]
        return min(pos, m["durationMs"]) if m["durationMs"] > 0 else pos

    def _freeze_music(self, now: int) -> int:
        pos = self._position(now)
        self.music.update(playing=False, positionMs=pos, atHostTimeMs=now)
        return pos

    def _play_from(self, pos: int, at: int) -> None:
        t = self.track
        assert t is not None
        self.music = {"id": t.id, "title": t.title, "artist": t.artist, "playing": True,
                      "positionMs": pos, "atHostTimeMs": at, "durationMs": t.duration_ms}
        self.send({"t": "music.play", "id": t.id, "positionMs": pos, "atHostTimeMs": at})

    async def _load_and_play(self) -> None:
        t = self.track
        if t is None:
            log("no --track given")
            return
        if self.loading:
            log("already loading")
            return
        self.loading = True
        fut = self.loop.create_future()
        self.pending_ready[t.id] = fut
        try:
            load = {"t": "music.load", "id": t.id, "path": t.path, "title": t.title, "artist": t.artist}
            if t.album:
                load["album"] = t.album
            load["durationMs"] = t.duration_ms
            self.send(load)
            t0 = now_ms()
            try:
                reply = await asyncio.wait_for(fut, READY_TIMEOUT_MS / 1000)
                if reply["t"] == "music.ready":
                    log(f"music: client ready after {now_ms() - t0} ms")
                else:
                    log(f"music: client error {reply['message']!r}; playing alone")
            except asyncio.TimeoutError:
                log(f"music: no music.ready within {READY_TIMEOUT_MS} ms; playing alone")
            now = now_ms()
            if self.talk:
                self.music = {"id": t.id, "title": t.title, "artist": t.artist, "playing": False,
                              "positionMs": 0, "atHostTimeMs": now, "durationMs": t.duration_ms}
                self.resume_after_talk = True
                log("music: talk is open; will start when it closes")
            else:
                self._play_from(0, now + PLAY_LEAD_MS)
            self.send_state()
        finally:
            self.pending_ready.pop(t.id, None)
            self.loading = False

    def _pause(self) -> bool:
        if not self.music or not self.music["playing"]:
            return False
        pos = self._freeze_music(now_ms())
        self.send({"t": "music.pause", "id": self.music["id"], "positionMs": pos})
        self.send_state()
        return True

    def _resume(self) -> bool:
        if not self.music or self.music["playing"]:
            return False
        if self.talk:
            self.resume_after_talk = True
            return True
        self._play_from(self.music["positionMs"], now_ms() + PLAY_LEAD_MS)
        self.send_state()
        return True

    def _stop(self) -> None:
        self.music = None
        self.resume_after_talk = False
        self.send({"t": "music.stop"})
        self.send_state()

    def _music_control(self, action: str) -> None:
        if action == "pause":
            self._pause() or log("   (nothing playing)")
        elif action == "resume":
            self._resume() or log("   (nothing paused)")
        else:
            log(f"   ({action}: no queue in the fake host)")

    def _command(self, text: str) -> None:
        cmd = parse_command(text)
        log(f"   parsed: {cmd}")
        a = cmd["action"]
        if a == "unknown":
            self.send(dict(UNKNOWN_ANNOUNCE))
            return
        if a == "play":
            if self.track is None:
                self.send({"t": "announce", "text": f"No results for {cmd['query']}", "earcon": "error"})
                return
            self.send({"t": "announce", "text": f"Playing {self.track.title} by {self.track.artist}", "earcon": "ok"})
            asyncio.create_task(self._load_and_play())
        elif a == "pause":
            ok = self._pause()
            self.send({"t": "announce", "text": "Paused" if ok else "Nothing is playing", "earcon": "ok" if ok else "error"})
        elif a == "resume":
            ok = self._resume()
            self.send({"t": "announce", "text": "Resuming" if ok else "Nothing to resume", "earcon": "ok" if ok else "error"})
        elif a in ("next", "previous"):
            self.send({"t": "announce", "text": "Nothing queued", "earcon": "error"})
        elif a in VOLUME_ACTIONS:
            # Volume is local: a client that sends a volume utterance is misbehaving
            # (PROTOCOL.md "Commands": "A host that still receives a volume utterance in
            # command.text answers 'Didn't catch that'").
            log("   volume is local; the client should not have sent this")
            self.send(dict(UNKNOWN_ANNOUNCE))

    # ------------------------------------------------------------------ stdin

    async def _stdin(self) -> None:
        q = stdin_lines(self.loop)
        log(HELP)
        while True:
            line = await q.get()
            if line is None:
                return
            cmd, _, rest = line.strip().partition(" ")
            if not cmd:
                continue
            if cmd == "load":
                asyncio.create_task(self._load_and_play())
            elif cmd in ("play", "resume"):
                self._resume() or log("nothing paused (use load)")
            elif cmd == "pause":
                self._pause() or log("nothing playing")
            elif cmd == "stop":
                self._stop()
            elif cmd == "talk":
                if self.talk:
                    self._close_talk("host", "trigger")
                elif not self.mic_available:
                    log("mic is off; not opening talk ('mic on' to re-enable)")
                else:
                    self._open_talk("host")
            elif cmd == "mic":
                arg = rest.strip().lower()
                if arg in ("off", "unavailable"):
                    self.mic_available = False
                elif arg in ("on", "available"):
                    self.mic_available = True
                elif arg == "":
                    self.mic_available = not self.mic_available
                else:
                    log("usage: mic on|off")
                    continue
                log("mic UNAVAILABLE: a client talk.open is answered with "
                    "talk.close{by:'host',reason:'unavailable'}" if not self.mic_available
                    else "mic available again: a client talk.open opens talk")
            elif cmd == "announce":
                self.send({"t": "announce", "text": rest.strip() or "Test announcement", "earcon": "ok"})
            elif cmd == "state":
                self.send_state()
            elif cmd == "stats":
                log(f"voice: {self.vstats}")
            elif cmd == "raw":
                try:
                    body = json.dumps(json.loads(rest), ensure_ascii=False, separators=(",", ":")).encode()
                except ValueError as e:
                    log(f"raw: invalid JSON: {e}")
                    continue
                if self.current:
                    self.current.writer.write(frame(body))
                    log(">> (raw)", body.decode())
            elif cmd == "quit":
                if self.current:
                    self.send({"t": "bye", "reason": "shutdown"})
                    with contextlib.suppress(Exception):
                        await asyncio.wait_for(self.current.writer.drain(), 1)
                self.main.cancel()
                return
            else:
                log(HELP)


# ---------------------------------------------------------------------- HTTP track server

_RANGE = re.compile(r"bytes=(\d*)-(\d*)")


def parse_range(header: str, size: int) -> tuple[int, int] | None | str:
    """-> (start, end_inclusive), None to ignore the header (serve 200), or "unsatisfiable"."""
    m = _RANGE.fullmatch(header.strip())
    if not m or (m[1] == "" and m[2] == ""):
        return None  # multi-range or malformed: RFC 9110 allows ignoring Range
    if m[1] == "":
        n = int(m[2])
        if n == 0 or size == 0:
            return "unsatisfiable"
        return max(0, size - n), size - 1
    start = int(m[1])
    end = min(int(m[2]), size - 1) if m[2] else size - 1
    if start >= size or start > end:
        return "unsatisfiable"
    return start, end


def _make_handler(host: Host):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "motoparty-peer"

        def log_message(self, fmt, *args):  # route through our logger
            log(f"http: {self.client_address[0]} {fmt % args}")

        def _serve(self, body: bool) -> None:
            t = host.track
            path = self.path.split("?", 1)[0]
            if t is None or path != t.path:
                self.send_response(404)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            data = t.file.read_bytes()
            size = len(data)
            rng = self.headers.get("Range")
            parsed = parse_range(rng, size) if rng else None
            if parsed == "unsatisfiable":
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if parsed is None:
                self.send_response(200)
                chunk = data
            else:
                start, end = parsed
                self.send_response(206)
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
                chunk = data[start : end + 1]
            self.send_header("Content-Type", "audio/mp4")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(len(chunk)))
            self.end_headers()
            if body:
                self.wfile.write(chunk)

        def do_GET(self):
            self._serve(True)

        def do_HEAD(self):
            self._serve(False)

    return Handler


def run(args) -> None:
    args.port = CONTROL_PORT if args.port is None else args.port
    args.voice_port = VOICE_PORT if args.voice_port is None else args.voice_port
    args.http_port = HTTP_PORT if args.http_port is None else args.http_port
    try:
        asyncio.run(Host(args).run())
    except KeyboardInterrupt:
        pass
