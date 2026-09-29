"""`motoparty-peer host`: a minimal fake Pixel, for testing the iOS client without the Pixel.

Advertises over Bonjour, serves control/voice/HTTP, is the talk authority (talk ends on a
trigger or a talk-ending command, never on silence), echoes voice back to the client, serves its
--track files, parses command.text (only the first of a talk the client opened, with its effect
on the talk) and stdin `hear` phrases (the host's own first phrase, or solo talk), and answers the
Browsing messages (search, browse, enqueue, edit) from those files.

With --host-mic (or stdin `hostmic on`) it decides talks as host-mic talks (PROTOCOL.md "Talk flow",
Host-mic talk): its talk.open carries mic:"host", it drops and counts any client audio in such a
talk, and its `hear` stands for the ASR on the opener's channel of the two-channel receiver.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import random
import re
import socket
import threading
from dataclasses import dataclass, field, replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import discovery
from .commands import TALK_ENDING, UNKNOWN_ANNOUNCE, VOLUME_ACTIONS, FirstPhraseGate, parse_command
from .music import VALID_ID, TrackInfo, browse, load_library, search
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
          hostmic on|off (next talks are host-mic talks: talk.open mic:"host") |
          hear <phrase> (recognised in the talk: first phrase rule, or every phrase solo) |
          announce <text> | state | stats | raw <json> (send unvalidated) | quit"""

C2H_ONLY = {"ping", "music.ready", "music.error", "music.control", "command.text",
            "music.search", "music.browse", "music.enqueue", "music.edit"}
MAX_QUEUE = 200  # PROTOCOL.md "Browsing" 3 and 5


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
    dropped: int = 0  # client audio in a host-mic talk (PROTOCOL.md "Voice"): dropped, never echoed


class Host:
    def __init__(self, args) -> None:
        self.args = args
        self.name = args.name or f"{socket.gethostname()} peer"
        self.conns: set[Conn] = set()
        self.current: Conn | None = None
        self.talk = False
        self.mic_available = True  # `mic off`: refuse talk.open with reason "unavailable"
        # --host-mic / `hostmic on`: the next talks open with mic:"host" (PROTOCOL.md "Host-mic
        # talk"). talk_mic is this talk's mode, fixed at the open until it closes.
        self.host_mic = bool(getattr(args, "host_mic", False))
        self.talk_mic: str | None = None
        self.talk_dropped = 0  # client audio packets dropped in this host-mic talk
        self.talk_opened = 0
        # PROTOCOL.md "Commands", The first phrase decides: who opened this talk, whether the
        # client's one command.text of it has arrived, and the host's own first-phrase gate.
        self.talk_by: str | None = None
        self.client_command_seen = False
        self.gate: FirstPhraseGate | None = None
        self.last_activity = 0
        self.resume_after_talk = False
        # A spoken `pause` in the talk cancels the resume after it, including one that a
        # `next`/`previous` still loading would set (PROTOCOL.md "Commands", Effect on the talk).
        self.no_resume = False
        # A track that a spoken `play` loads does not start before the headsets are back in
        # media mode after the talk that command ended (PROTOCOL.md "Commands").
        self.media_at = 0
        self.library: list[TrackInfo] = load_library(args.track or [])
        self.by_id = {t.id: t for t in self.library}
        # The current track (`load` plays the first library track), the upcoming queue
        # (state.queue) and the tracks before the current one (for `previous`).
        self.track: TrackInfo | None = self.library[0] if self.library else None
        self.queue: list[TrackInfo] = []
        self.history: list[TrackInfo] = []
        self.music: dict | None = None
        self.pending_ready: dict[str, asyncio.Future] = {}
        self.load_task: asyncio.Task | None = None
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
        for t in self.library:
            log(f"track: {t.path} {t.title!r} by {t.artist!r} ({t.duration_ms} ms) <- {t.file}")
        adv = None
        if not a.no_mdns:
            everywhere = a.bind in ("0.0.0.0", "")
            addrs = discovery.local_ipv4s() if everywhere else [a.bind]
            try:
                # Bound to one address: announce only on that interface (e.g. 127.0.0.1 stays
                # off the Wi-Fi).
                adv = discovery.Advertiser(self.name, self.port, self.voice_port, self.http_port, addrs,
                                           interfaces=None if everywhere else [a.bind])
                await adv.start()
                log(f"bonjour: advertising {adv.info.name!r} on {addrs}")
            except Exception as e:
                log(f"bonjour: advertisement failed: {type(e).__name__}: {e}")
                adv = None
        stdin_task = asyncio.create_task(self._stdin())
        stats_task = asyncio.create_task(self._stats())
        try:
            async with server:
                await server.serve_forever()
        except asyncio.CancelledError:
            pass
        finally:
            for t in (stdin_task, stats_task):
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
        if self.talk and self.talk_mic:
            s["mic"] = self.talk_mic  # PROTOCOL.md `state`: a mid-talk joiner opens it the same way
        if self.music:
            s["music"] = dict(self.music)
        s["queue"] = [{"id": t.id, "title": t.title, "artist": t.artist} for t in self.queue]
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
        except OSError as e:  # e.g. a discovery probe: reads our hello, then closes (RST)
            log(f"{ip}: connection closed ({type(e).__name__})")
            return
        except asyncio.CancelledError:
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
            if "mic" in msg:
                log("   (mic on a client request ignored: only the host's decision carries it)")
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
            self._client_command(msg["text"])
        elif t in ("music.search", "music.browse"):
            self._browse(msg)
        elif t == "music.enqueue":
            self._enqueue(msg)
        elif t == "music.edit":
            self._edit(msg)
        elif t not in C2H_ONLY:
            log(f"   ({t!r} is host-to-client; ignored)")
        return None

    # ------------------------------------------------------------------ talk

    def _open_talk(self, by: str) -> None:
        now = now_ms()
        self.talk = True
        self.talk_opened = now
        self.talk_by = by
        self.client_command_seen = False
        self.talk_mic = "host" if self.host_mic else None
        self.talk_dropped = 0
        # The host's live earcon plays now; a talk with no client is solo. In a host-mic talk
        # the host runs ASR on the opener's channel whoever opened it (PROTOCOL.md "Commands").
        role = "solo" if self.current is None else "opener" if by == "host" or self.talk_mic else "other"
        self.gate = FirstPhraseGate(role, now)
        self.no_resume = False
        if self.music and self.music["playing"]:
            self._freeze_music(now)
            self.resume_after_talk = True
        msg = {"t": "talk.open", "by": by}
        if self.talk_mic:
            msg["mic"] = self.talk_mic
        log(f"TALK OPEN (by {by}{', host-mic' if self.talk_mic else ''})")
        self.send(msg)
        self.send_state()

    def _close_talk(self, by: str, reason: str) -> None:
        self.talk = False
        self.talk_by = None
        self.gate = None
        log(f"TALK CLOSED (by {by}, {reason})")
        if self.talk_mic:
            log(f"host-mic talk: dropped {self.talk_dropped} client audio packets")
        self.talk_mic = None
        self.send({"t": "talk.close", "by": by, "reason": reason}, quiet=self.current is None)
        # A track still loading (a `next` in the talk) starts by itself once ready, so the old
        # one does not resume in between; it keeps `no_resume` until then.
        if self.resume_after_talk and self.music and not self.no_resume and not self._loading():
            self._play_from(self.music["positionMs"], now_ms() + RESUME_LEAD_MS)
        self.resume_after_talk = False
        if self._loading():
            self.media_at = now_ms() + RESUME_LEAD_MS
        else:
            self.no_resume = False
        self.send(self.state(), quiet=self.current is None)

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
        if self.talk and self.talk_mic == "host":
            # PROTOCOL.md "Voice": in a host-mic talk only the host sends audio; drop the client's.
            self.vstats.dropped += 1
            self.talk_dropped += 1
            if self.talk_dropped == 1:
                log("voice: client audio in a host-mic talk; dropping it")
            return
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
                    f"dropped={v.dropped} foreign={v.foreign} silent_for={now_ms() - max(self.talk_opened, self.last_activity)}ms")

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
        if t.art:
            self.music["art"] = t.art
        self.send({"t": "music.play", "id": t.id, "positionMs": pos, "atHostTimeMs": at})

    async def _load_and_play(self) -> None:
        t = self.track
        assert t is not None
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
            if self.talk or self.no_resume:
                self.music = {"id": t.id, "title": t.title, "artist": t.artist, "playing": False,
                              "positionMs": 0, "atHostTimeMs": now, "durationMs": t.duration_ms}
                if t.art:
                    self.music["art"] = t.art
                if self.talk:
                    self.resume_after_talk = not self.no_resume
                    log("music: talk is open; " + ("resume cancelled, stays paused" if self.no_resume
                                                   else "will start when it closes"))
                else:
                    self.no_resume = False
                    log("music: `pause` was said in the talk; stays paused")
            else:
                # after a talk, not before the headset is back in media mode (PROTOCOL.md "Talk flow" 4)
                self._play_from(0, max(now + PLAY_LEAD_MS, self.media_at))
            self.send_state()
        finally:
            if self.pending_ready.get(t.id) is fut:
                del self.pending_ready[t.id]

    def _start(self, track: TrackInfo) -> None:
        """Make `track` current and load/play it, replacing any load still in flight."""
        if self.load_task and not self.load_task.done():
            self.load_task.cancel()
        self.track = track
        self.load_task = asyncio.create_task(self._load_and_play())

    def _loading(self) -> bool:
        return self.load_task is not None and not self.load_task.done()

    def _has_current(self) -> bool:
        """A track is loaded or being loaded (state.music, or about to be)."""
        return self.track is not None and (self.music is not None or self._loading())

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
            self.no_resume = False
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
        elif action == "next" and self.queue:
            if self._has_current():
                self.history.append(self.track)
            self._start(self.queue.pop(0))
            self.send_state()
        elif action == "previous" and self.history:
            if self._has_current():
                self.queue.insert(0, self.track)
            self._start(self.history.pop())
            self.send_state()
        else:
            log(f"   ({action}: nothing {'queued' if action == 'next' else 'before this track'})")

    # ------------------------------------------------------------------ browsing

    def _browse(self, msg: dict) -> None:
        res: dict = {"t": "music.results", "id": msg["id"], "items": []}
        if msg["t"] == "music.search":
            res["items"] = search(self.library, msg["kind"], msg["query"])
        elif not VALID_ID.fullmatch(msg["ref"]):
            res["error"] = "Invalid ref"
        elif (items := browse(self.library, msg["ref"])) is None:
            res["error"] = "Not found"
        else:
            res["items"] = items
        self.send(res)

    def _enqueue(self, msg: dict) -> None:
        # PROTOCOL.md "Browsing" 3. The fake host can only play its own files, so an id
        # that is valid but not one of them is skipped like an invalid one.
        tracks = []
        for tr in msg["tracks"]:
            lib = self.by_id.get(tr["id"]) if VALID_ID.fullmatch(tr["id"]) else None
            if lib is None:
                log(f"   (skipping {tr['id']!r}: not a track this host serves)")
                continue
            tracks.append(replace(lib, art=tr.get("art") or msg.get("art")))
        tracks = tracks[:MAX_QUEUE]
        if not tracks:
            log("   (no playable tracks; enqueue ignored)")
            return
        mode = msg["mode"]
        if mode == "now":
            self._touch_play_ends_talk("client")
        if not self._has_current():
            mode = "now"  # nothing playing: next/end act like now
        if mode == "now":
            if self._has_current():
                self.history.append(self.track)
            self.queue = tracks[1:]
            self._start(tracks[0])
        elif mode == "next":
            self.queue[0:0] = tracks
        else:
            self.queue += tracks
        del self.queue[MAX_QUEUE:]
        self.send_state()

    def _edit(self, msg: dict) -> None:
        op = msg["op"]
        if op == "clear":
            self.queue.clear()
            self.send_state()
            return
        i, id_ = msg.get("index"), msg.get("id")
        if i is None or id_ is None or not 0 <= i < len(self.queue) or self.queue[i].id != id_:
            log("   (stale or incomplete edit: the queue changed under the client; ignored)")
            return
        if op == "remove":
            del self.queue[i]
        else:  # jump: the skipped tracks go behind the current one, so `previous` reaches them
            self._touch_play_ends_talk("client")
            if self._has_current():
                self.history.append(self.track)
            self.history += self.queue[:i]
            track, self.queue = self.queue[i], self.queue[i + 1 :]
            self._start(track)
        self.send_state()

    def _touch_play_ends_talk(self, by: str) -> None:
        """PROTOCOL.md "Browsing" 3, Play by touch ends a talk: a `now` enqueue or a `jump` edit
        during a talk closes it like a spoken `play` (by the side that touched); the new track
        starts after the headset is back in media mode, instead of the old one resuming."""
        if not self.talk:
            return
        log(f"   play by touch ends the talk (by {by})")
        self.resume_after_talk = False
        self._close_talk(by, "trigger")
        self.no_resume = False  # an earlier spoken `pause` does not hold back what was just touched
        self.media_at = now_ms() + RESUME_LEAD_MS

    def _client_command(self, text: str) -> None:
        """command.text from the client. The host enforces the first-phrase rule (PROTOCOL.md
        "Commands"): it acts only while a talk is open, the client opened it, and this is the
        first command.text of that talk. Any other is ignored and logged, with no announce."""
        if not self.talk:
            why = "no talk is open"
        elif self.talk_mic == "host":
            why = "host-mic talk: the host recognises the opener's channel, the client never does"
        elif self.talk_by != "client":
            why = "the client did not open this talk"
        elif self.client_command_seen:
            why = "not the first command.text of this talk"
        else:
            self.client_command_seen = True
            self._command(text, "client")
            return
        log(f"   command.text ignored: {why}")

    def _hear(self, phrase: str) -> None:
        """A phrase the host's own ASR recognised on its talk microphone (stdin `hear`): its
        first phrase in a talk it opened, or every non-empty phrase in a solo talk. In a
        host-mic talk it is the opener's channel: the passenger's (right) when the client
        opened the talk, acted on as if it were the client's command.text."""
        if not self.talk or self.gate is None:
            log("hear: no talk open; commands are spoken inside a talk")
            return
        text = self.gate.phrase(phrase, now_ms())
        passenger = self.talk_mic == "host" and self.talk_by == "client" and self.current is not None
        if text is None:
            log(f"hear: {phrase!r} is conversation ({self.gate.why}), not acted on")
        elif passenger:
            if parse_command(text)["action"] in VOLUME_ACTIONS:
                log(f"hear: {parse_command(text)['action']} on the passenger's channel ignored, "
                    f"no announce (their volume keys do it)")
            else:
                log(f"hear: command {text!r} (passenger's channel, as the client's command)")
                self.client_command_seen = True
                self._command(text, "client")
        elif parse_command(text)["action"] in VOLUME_ACTIONS:
            log(f"local: {parse_command(text)['action']} handled here [earcon ok] "
                f"(the peer has no real volume)")
        else:
            log(f"hear: command {text!r}")
            self._command(text, "host")

    def _command(self, text: str, by: str) -> None:
        """A command spoken in a talk by `by` (the client's command.text, or the host's own
        phrase). It has an effect on the talk (PROTOCOL.md "Commands", Effect on the talk):
        play/resume/end close it (by the side that spoke) as soon as the command parses, and
        their announce goes after that close; pause/next/previous leave it open and choose
        what happens after it. The announce goes to the client and the host speaks it too."""
        cmd = parse_command(text)
        log(f"   parsed: {cmd}")
        a = cmd["action"]
        if self.talk and a in TALK_ENDING:
            ok = a == "resume" and self._resume()
            if a == "play" and self.track is not None:
                self.resume_after_talk = False  # the new track starts instead of the old one
            log(f"   {a!r} ends the talk")
            self._close_talk(by, "trigger")
            self.media_at = now_ms() + RESUME_LEAD_MS
            if a == "resume":
                self.send({"t": "announce", "text": "Resuming" if ok else "Nothing to resume",
                           "earcon": "ok" if ok else "error"})
                return
        if a == "unknown":
            self.send(dict(UNKNOWN_ANNOUNCE))
            return
        if a == "end":
            if not self.talk:  # it closed above if it was open
                log("   (no talk to end)")
            return
        if a == "play":
            if self.track is None:
                self.send({"t": "announce", "text": f"No results for {cmd['query']}", "earcon": "error"})
                return
            self.send({"t": "announce", "text": f"Playing {self.track.title} by {self.track.artist}", "earcon": "ok"})
            self._start(self.track)
        elif a == "pause":
            if self.talk:
                # music is already paused for the talk: cancel the resume after it
                ok = self.resume_after_talk and not self.no_resume
                self.no_resume = True
                self.resume_after_talk = False
            else:
                ok = self._pause()
            self.send({"t": "announce", "text": "Paused" if ok else "Nothing is playing", "earcon": "ok" if ok else "error"})
        elif a == "resume":
            ok = self._resume()
            self.send({"t": "announce", "text": "Resuming" if ok else "Nothing to resume", "earcon": "ok" if ok else "error"})
        elif a in ("next", "previous"):
            # Inside a talk the track loads but stays paused until the talk closes (see
            # _load_and_play): it chooses what resumes after the talk.
            pool = self.queue if a == "next" else self.history
            if not pool:
                self.send({"t": "announce", "text": "Nothing queued" if a == "next" else "Nothing before this",
                           "earcon": "error"})
                return
            title = pool[0 if a == "next" else -1].title
            self._music_control(a)
            self.send({"t": "announce", "text": f"{'Next' if a == 'next' else 'Back to'}: {title}", "earcon": "ok"})
        elif a == "nowplaying":
            t = self.track if self._has_current() else None
            if t is None:
                self.send({"t": "announce", "text": "Nothing playing", "earcon": "error"})
            else:
                text = f"{t.title} by {t.artist}" if t.artist else t.title
                self.send({"t": "announce", "text": text, "earcon": "ok"})
        elif a == "shuffle":
            # the upcoming queue only; the current track stays (PROTOCOL.md "Commands")
            if len(self.queue) < 2:
                self.send({"t": "announce", "text": "Nothing to shuffle", "earcon": "error"})
                return
            random.shuffle(self.queue)
            self.send_state()
            self.send({"t": "announce", "text": "Shuffled", "earcon": "ok"})
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
                if self.track is None:
                    log("no --track given")
                elif self._loading():
                    log("already loading")
                else:
                    self._start(self.track)
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
            elif cmd == "hostmic":
                arg = rest.strip().lower()
                if arg == "on":
                    self.host_mic = True
                elif arg == "off":
                    self.host_mic = False
                elif arg == "":
                    self.host_mic = not self.host_mic
                else:
                    log("usage: hostmic on|off")
                    continue
                log(("host-mic ON: the next talk.open carries mic:'host'" if self.host_mic
                     else "host-mic OFF: the next talk.open has no mic")
                    + (" (the open talk keeps its mode)" if self.talk else ""))
            elif cmd == "hear":
                if rest.strip():
                    self._hear(rest)
                else:
                    log("usage: hear <phrase>")
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
            path = self.path.split("?", 1)[0]
            m = re.fullmatch(r"/track/([A-Za-z0-9_-]+)\.m4a", path)
            t = host.by_id.get(m[1]) if m else None
            if t is None:
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
