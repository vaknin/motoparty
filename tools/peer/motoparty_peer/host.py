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
from .commands import (
    UNKNOWN_ANNOUNCE,
    VOLUME_ACTIONS,
    QUEUE_SIMILAR,
    SIMILAR,
    FirstPhraseGate,
    ANSWER_GRACE_MS,
    ANSWER_MS,
    INTERPRET_PLAYED,
    INTERPRET_UP_NEXT,
    UNDO_MS,
    UNDOABLE,
    command_text,
    interpretation_actions,
    parse_command,
    to_actions,
)
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
          hear <phrase> (recognised in the talk: first phrase rule, or every phrase solo;
          a command ends the talk) |
          next | previous | queue (every other --track after the current one) |
          repeat off|track|queue (state.music.repeat) |
          announce <text> | state | stats | raw <json> (send unvalidated) | quit"""

C2H_ONLY = {"ping", "music.ready", "music.error", "music.control", "command.text",
            "music.search", "music.browse", "music.enqueue", "music.edit"}
MAX_QUEUE = 200  # PROTOCOL.md "Browsing" 3 and 5
ERROR_RELOAD_MS = 2000  # PROTOCOL.md "Music flow" 3: music.load once more after a first music.error
# Less than this before the current track ends, the next one is not announced any more (as on
# the Android host): it then starts on its music.play, with a gap.
GAPLESS_MIN_NOTICE_MS = 1500


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
        # The stub interpreter (--interpret-table, or set by a test); None = not interpreting.
        self.interpret_table: dict[str, str] | None = None
        if getattr(args, "interpret_table", None):
            with open(args.interpret_table, encoding="utf-8") as f:
                self.interpret_table = json.load(f)
        self.interpret_delay_ms = 0  # tests: how long the stub takes to answer
        self.question: dict | None = None  # this talk's clarifying question, asked or answered
        self.answer_wait_ms = ANSWER_MS + ANSWER_GRACE_MS  # tests shorten it
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
        # state.music.repeat: None (off), "track" or "queue" (PROTOCOL.md `state`).
        self.repeat: str | None = None
        # Voice undo (PROTOCOL.md "Commands", Voice actions): (when, the upcoming queue, the
        # repeat mode) as they were before the last voice list that changed them.
        self.undo: tuple[int, list[TrackInfo], str | None] | None = None
        self.music: dict | None = None
        self.pending_ready: dict[str, asyncio.Future] = {}
        self.load_task: asyncio.Task | None = None
        self.loop: asyncio.AbstractEventLoop | None = None
        # PROTOCOL.md "Music flow" 5 and 6: ids this client answered music.ready for, the queue
        # item whose music.load was sent ahead, the music.next the client holds as pending
        # (id, atHostTimeMs), and the timer for the end of the current track.
        self.client_ready: set[str] = set()
        self.prefetched: str | None = None
        self.next_sent: tuple[str, int] | None = None
        self.end_timer: asyncio.TimerHandle | None = None
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
                "voicePort": self.voice_port, "httpPort": self.http_port,
                **({"interpret": True} if self.interpret_table is not None else {})}

    def _awaits_reply(self, by: str) -> bool:
        """Is the next phrase of `by` the reply to the host's question of this talk?"""
        q = self.question
        return bool(q and self.talk and not q["answered"] and q["by"] == by and q["session"] == self.talk_opened)

    def _interpret(self, text: str, by: str, solo: bool, reply: dict | None) -> None:
        """An unparsed candidate while interpreting (PROTOCOL.md "Commands", Interpretation), or
        the `reply` to a question. The stub interpreter is a table, normalised phrase -> the
        answer a model would give; a phrase not in it is conversation, and the value null is a
        failure (a timeout)."""
        assert self.interpret_table is not None
        answer = self.interpret_table.get(text, '{"actions":[]}')
        failed = not isinstance(answer, str)
        # The snapshot of the context window: answer positions resolve against these tracks.
        snap = {"up": self.queue[:INTERPRET_UP_NEXT], "played": self.history[::-1][:INTERPRET_PLAYED]}
        outcome = None if failed else interpretation_actions(answer, len(snap["up"]), len(snap["played"]))
        talk_was, session = self.talk, self.talk_opened
        if reply is not None:
            reply["answered"] = True

        def done() -> None:
            asks = isinstance(outcome, dict)
            if self.talk != talk_was or self.talk_opened != session:
                log(f"interpret: {text!r} -> {outcome or 'conversation'} (dropped: the talk changed)")
                return
            if asks and talk_was and self.question is None:
                log(f"interpret: {text!r} -> ask {outcome['ask']!r} (fallback {outcome['fallback']})")
                self._ask({"session": session, "by": by, "phrase": text, "text": outcome["ask"],
                           "fallback": outcome["fallback"], "answered": False})
                return
            # A reply that settles nothing falls back on the first answer; "never mind" does not.
            fallback = reply["fallback"] if reply is not None and (failed or asks) else None
            actions = (outcome["fallback"] or fallback) if asks else outcome if outcome is not None else fallback
            # A `play …` of the grammar that the interpreter did not settle is played as spoken.
            if not actions and reply is None and parse_command(text)["action"] in ("play", "queue"):
                log(f"interpret: {text!r} not settled; executed as spoken")
                self._command(text, by)
                return
            if not actions:
                log(f"interpret: {text!r} -> {'failed' if failed else 'conversation'}")
                if solo:
                    self.send(dict(UNKNOWN_ANNOUNCE))
                return
            if by == "client" and any(a["type"] in VOLUME_ACTIONS for a in actions):
                log(f"interpret: {text!r}: volume ignored (it is the passenger's own)")
                actions = [a for a in actions if a["type"] not in VOLUME_ACTIONS]
                if not actions:
                    return
            log(f"interpret: {text!r} -> {js(actions)}")
            self._run(actions, by, snap)

        if self.interpret_delay_ms:
            asyncio.get_running_loop().call_later(self.interpret_delay_ms / 1000, done)
        else:
            done()

    def _ask(self, q: dict) -> None:
        """The host's one question of the talk (PROTOCOL.md "Commands", The clarifying question):
        said in the talk, which stays open; the next phrase of the same side is the reply, and
        with none in time the fallback is executed."""
        self.question = q
        if self.gate is not None and (q["by"] == "host" or self.talk_mic == "host"):
            self.gate.ask(now_ms())
        self.send({"t": "announce", "text": q["text"], "ask": True})

        def no_reply() -> None:
            if self.question is not q or q["answered"] or not self.talk or self.talk_opened != q["session"]:
                return
            q["answered"] = True
            log(f"ask: no reply to {q['text']!r}; fallback {q['fallback']}")
            if q["fallback"]:
                self._run(q["fallback"], q["by"])

        asyncio.get_running_loop().call_later(self.answer_wait_ms / 1000, no_reply)

    def _submit(self, text: str, by: str, solo: bool = False) -> None:
        """A command candidate: the grammar first, then (if on) the interpreter, which also gets
        every `play …` (to repair names, or ask). The reply to a question is always interpreted."""
        reply = self.question if self._awaits_reply(by) else None
        if self.interpret_table is not None and (reply is not None or parse_command(text)["action"] in ("unknown", "play", "queue")):
            self._interpret(text, by, solo, reply)
        else:
            self._command(text, by)

    def state(self) -> dict:
        s: dict = {"t": "state", "talk": self.talk}
        if self.talk and self.talk_mic:
            s["mic"] = self.talk_mic  # PROTOCOL.md `state`: a mid-talk joiner opens it the same way
        if self.music:
            s["music"] = dict(self.music)
            if self.repeat:
                s["music"]["repeat"] = self.repeat
        s["queue"] = [{"id": t.id, "title": t.title, "artist": t.artist} for t in self.queue]
        return s

    def send_state(self) -> None:
        self.send(self.state())
        self._music_changed()

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
        self.client_ready.clear()
        self.prefetched = self.next_sent = None
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
                # PROTOCOL.md "Liveness": the same client back on a new socket keeps an open
                # talk (it acts on the state{talk:true} this connection got on connect, voice
                # follows its next packet); anyone else ends it.
                if self.talk:
                    if old.name == c.name:
                        log(f"talk kept: {c.name!r} reconnected")
                    else:
                        self._close_talk("host", "link")
            log(f"client {c.name!r} at {c.ip} is now the client")
            # a new connection has prefetched nothing and holds no music.next
            self.client_ready.clear()
            self.prefetched = self.next_sent = None
            self._music_changed()
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
            if t == "music.ready":
                self.client_ready.add(msg["id"])
            else:
                self.client_ready.discard(msg["id"])
            fut = self.pending_ready.get(msg["id"])
            if fut and not fut.done():
                fut.set_result(msg)
            elif msg["id"] == self.prefetched:
                log(f"   ({t} for the prefetched next track)")
            else:
                log(f"   ({t} for {msg['id']!r}, which is not being loaded)")
            self._music_changed()  # the next track just became ready: music.next
        elif t == "music.control":
            self._music_control(msg["action"], msg.get("mode"))
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
        self.question = None
        self.talk_mic = "host" if self.host_mic else None
        self.talk_dropped = 0
        # The host's live earcon plays now; a talk with no client is solo. In a host-mic talk
        # the host runs ASR on the opener's channel whoever opened it (PROTOCOL.md "Commands").
        role = "solo" if self.current is None else "opener" if by == "host" or self.talk_mic else "other"
        self.gate = FirstPhraseGate(role, now, interpret=self.interpret_table is not None)
        self.no_resume = False
        self.next_sent = None  # a talk cancels the client's pending music.next
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
        self._music_changed()  # music.next again after the resume's music.play

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
        self.next_sent = None  # any music.play but the one of the change cancels a pending music.next

    @staticmethod
    def _load_msg(t: TrackInfo) -> dict:
        load = {"t": "music.load", "id": t.id, "path": t.path, "title": t.title, "artist": t.artist}
        if t.album:
            load["album"] = t.album
        load["durationMs"] = t.duration_ms
        return load

    def _music_changed(self) -> None:
        """PROTOCOL.md "Music flow" 5 and 6, after anything that may have changed the music, the
        queue or what the client has ready: prefetch the next queue item, announce it with
        music.next (or take an announcement back), and arm the end of the current track."""
        m = self.music
        if m and self.queue and self.current is not None and self.queue[0].id != self.prefetched:
            self.prefetched = self.queue[0].id
            self.send(self._load_msg(self.queue[0]))
        # The track's end by its anchor. durationMs is the file's own (mvhd) duration.
        playing = bool(m and m["playing"] and not self.talk and not self._loading() and m["durationMs"] > 0)
        end = m["atHostTimeMs"] + m["durationMs"] - m["positionMs"] if playing else 0
        want = None
        if (playing and self.current is not None and self.repeat != "track" and self.queue
                and self.queue[0].id in self.client_ready):
            want = (self.queue[0].id, end)
            if want != self.next_sent and end - now_ms() < GAPLESS_MIN_NOTICE_MS:
                want = None  # too late to announce: it starts on its music.play, with a gap
        if want != self.next_sent:
            if want is not None:
                self.send({"t": "music.next", "id": want[0], "atHostTimeMs": want[1]})
            elif m and m["playing"] and not self.talk and self.current is not None:
                # Take it back: the current track's music.play, anchor unchanged (no seek).
                log(f"music: taking music.next {self.next_sent[0]} back")
                self.send({"t": "music.play", "id": m["id"], "positionMs": m["positionMs"],
                           "atHostTimeMs": m["atHostTimeMs"]})
            self.next_sent = want
        if self.end_timer is not None:
            self.end_timer.cancel()
            self.end_timer = None
        if playing and self.loop is not None:
            self.end_timer = self.loop.call_later(max(0, end - now_ms()) / 1000, self._track_ended)

    def _track_ended(self) -> None:
        """The current track ran out by its anchor: change to the next queue item (without a gap
        when it was announced), or park the last one at its start."""
        self.end_timer = None
        m, t = self.music, self.track
        if not m or not m["playing"] or t is None or self._loading():
            return
        end = m["atHostTimeMs"] + m["durationMs"] - m["positionMs"]
        if self.repeat == "track":
            log(f"music: {t.id} ended; repeat track: again from 0 at {end}")
            self._play_from(0, end)
            self.send_state()
            return
        if not self.queue and self.repeat == "queue":
            # the queue starts again from the first track it still holds (PROTOCOL.md `state`)
            log("music: end of the queue; repeat queue: from the first track again")
            self.queue, self.history = [*self.history, t][:MAX_QUEUE], []
        if not self.queue:
            # PROTOCOL.md "Music flow" 3 (2026-09-30): the last track is parked, not stopped.
            log(f"music: end of the queue; {t.id} parked at 0")
            m.update(playing=False, positionMs=0, atHostTimeMs=now_ms())
            self.next_sent = None
            self.send({"t": "music.pause", "id": t.id, "positionMs": 0})
            self.send_state()
            return
        self.history.append(t)
        nxt = self.queue.pop(0)
        if self.next_sent != (nxt.id, end):
            log(f"music: {t.id} ended; {nxt.id} was not announced, loading it (gap)")
            self._start(nxt)
            self.send_state()
            return
        log(f"music: {t.id} ended; gapless change to {nxt.id} at {end}")
        self.track = nxt
        self.music = {"id": nxt.id, "title": nxt.title, "artist": nxt.artist, "playing": True,
                      "positionMs": 0, "atHostTimeMs": end, "durationMs": nxt.duration_ms}
        if nxt.art:
            self.music["art"] = nxt.art
        self.next_sent = None  # the client changed over: nothing pending any more
        self.send(self.state())
        self.send({"t": "music.play", "id": nxt.id, "positionMs": 0, "atHostTimeMs": end})
        self._music_changed()  # music.load of the following track, and its music.next if ready

    async def _load_and_play(self) -> None:
        t = self.track
        assert t is not None
        fut = self.loop.create_future()
        self.pending_ready[t.id] = fut
        try:
            self.send(self._load_msg(t))
            t0 = now_ms()
            try:
                reply = await asyncio.wait_for(fut, READY_TIMEOUT_MS / 1000)
                if reply["t"] == "music.error" and not reply["message"].startswith("not decodable"):
                    # PROTOCOL.md "Music flow" 3: once more after 2 s; a second error, or 8 s
                    # since the first load without music.ready, and the host plays alone.
                    log(f"music: client error {reply['message']!r}; sending music.load again in "
                        f"{ERROR_RELOAD_MS / 1000:g} s")
                    await asyncio.sleep(ERROR_RELOAD_MS / 1000)
                    fut = self.loop.create_future()
                    self.pending_ready[t.id] = fut
                    self.send(self._load_msg(t))
                    left = max(0.0, (t0 + READY_TIMEOUT_MS - now_ms()) / 1000)
                    reply = await asyncio.wait_for(fut, left)
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
            if self.load_task is asyncio.current_task():
                self.load_task = None  # loaded: the state below may announce the next track
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
        self.next_sent = None
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
        self.next_sent = None
        self.send({"t": "music.stop"})
        self.send_state()

    def _set_repeat(self, mode: str) -> None:
        """Touch repeat (ours or the client's): like the Pixel's button, it leaves the voice undo alone."""
        repeat = None if mode == "off" else mode
        if repeat == self.repeat:
            return
        self.repeat = repeat
        log(f"   repeat {mode}")
        self.send_state()

    def _music_control(self, action: str, mode: str | None = None) -> None:
        if action == "repeat":
            # The codec drops a repeat without a mode, so there is always one here.
            self._set_repeat(mode or "off")
        elif action == "pause":
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
        elif op == "move":  # it is state.queue[to] afterwards; past the end = the end
            track = self.queue.pop(i)
            self.queue.insert(min(msg["to"], len(self.queue)), track)
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
        elif self.client_command_seen and not self._awaits_reply("client"):
            why = "not the first command.text of this talk"
        else:
            self.client_command_seen = True
            self._submit(text, "client")
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
        elif self._awaits_reply("client" if passenger else "host"):
            log(f"hear: reply {text!r} to the question")
            self._submit(text, "client" if passenger else "host", solo=self.gate.role == "solo")
        elif passenger:
            if parse_command(text)["action"] in VOLUME_ACTIONS:
                log(f"hear: {parse_command(text)['action']} on the passenger's channel ignored, "
                    f"no announce (their volume keys do it)")
            else:
                log(f"hear: command {text!r} (passenger's channel, as the client's command)")
                self.client_command_seen = True
                self._submit(text, "client")
        elif parse_command(text)["action"] in VOLUME_ACTIONS:
            log(f"local: {parse_command(text)['action']} handled here [earcon ok] "
                f"(the peer has no real volume); it ends the talk")
            self._close_talk("host", "trigger")
        else:
            log(f"hear: command {text!r}")
            self._submit(text, "host", solo=self.gate.role == "solo")

    def _command(self, text: str, by: str) -> None:
        """A command spoken by `by` (the client's command.text, or the host's own phrase), parsed
        by the grammar and run as voice actions (PROTOCOL.md "Commands", Voice actions). An
        unparsed phrase ends nothing and gets "Didn't catch that"."""
        cmd = parse_command(text)
        log(f"   parsed: {cmd}")
        a = cmd["action"]
        if a in VOLUME_ACTIONS:
            # Volume is local: a client that sends a volume utterance is misbehaving
            # (PROTOCOL.md "Commands": "A host that still receives a volume utterance in
            # command.text answers 'Didn't catch that'"). Like an unparsed phrase, it ends nothing.
            log("   volume is local; the client should not have sent this")
        if a == "unknown" or a in VOLUME_ACTIONS:
            self.send(dict(UNKNOWN_ANNOUNCE))
            return
        self._run(to_actions(cmd), by)

    def _run(self, actions: list[dict], by: str, snap: dict | None = None) -> None:
        """Run a voice action list from `by` in order (PROTOCOL.md "Commands", Voice actions,
        Running a list). Positions resolve against `snap`, the context window's tracks. PROTOCOL.md
        "Commands", Effect on the talk: a list ends the talk it was spoken in (closed by the side
        that spoke), even if an action fails. Each action works on the music the talk paused
        before the close (pause cancels the resume, next/previous choose what resumes, play and
        jump replace it), so the close itself starts the right thing. Then one announce for the
        whole list, made of the parts that need one, to the client (the host speaks it too)."""
        prior = self.undo
        if any(a["type"] in UNDOABLE for a in actions):
            self.undo = (now_ms(), list(self.queue), self.repeat)
        parts = [p for a in actions if (p := self._act(a, snap or {"up": [], "played": []}, prior))]
        if self.talk:
            log(f"   {[a['type'] for a in actions]} ends the talk")
            self._close_talk(by, "trigger")
            # whatever starts next waits for the headset to be back in media mode
            self.media_at = now_ms() + RESUME_LEAD_MS
        elif [a["type"] for a in actions] == ["end"]:
            log("   (no talk to end)")
        if parts:
            text = ". ".join(t for t, _ in parts)
            earcon = "error" if all(e == "error" for _, e in parts) else "ok"
            self.send({"t": "announce", "text": text, "earcon": earcon})

    @staticmethod
    def _name(t: TrackInfo) -> str:
        return f"{t.title} by {t.artist}" if t.artist else t.title

    def _upcoming(self, positions: list[int], snap: dict) -> list[TrackInfo]:
        """Snapshot positions -> those tracks, if they are still upcoming (by identity: the
        queue may have changed while the model was thinking)."""
        out = []
        for p in positions:
            t = snap["up"][p - 1] if p - 1 < len(snap["up"]) else None
            if t is not None and any(q is t for q in self.queue):
                out.append(t)
            else:
                log(f"   (position {p} is no longer upcoming; skipped)")
        return out

    def _act(self, a: dict, snap: dict, prior: tuple | None) -> tuple[str, str] | None:
        """Carry out one voice action; in a talk, on the music the talk paused. Returns its part
        of the announce as (text, earcon), or None: an action whose result is the music itself
        has none (PROTOCOL.md "Commands", Running a list). The fake host has no catalog: a
        search "finds" its library."""
        t = a["type"]
        if t == "play":
            if self.track is None:
                return f"Couldn't find {a.get('query', 'anything similar')}", "error"
            if a["kind"] == SIMILAR and not self._has_current():
                return "Nothing playing", "error"
            # the new track starts instead of the one the talk paused, whatever was said before
            self.resume_after_talk = False
            self.no_resume = False
            self.undo = None  # a new queue forgets the undo
            self._start(self.track)
        elif t == "pause":
            if self.talk:
                # music is already paused for the talk: cancel the resume after it
                ok = self.resume_after_talk and not self.no_resume
                self.no_resume = True
                self.resume_after_talk = False
            else:
                ok = self._pause()
            if not ok:
                return "Nothing playing", "error"
        elif t == "resume":
            if not self._resume():
                return "Nothing to resume", "error"
        elif t in ("next", "previous"):
            # Inside a talk the track loads paused (see _load_and_play); the close resumes it if
            # music was playing before the talk.
            if not (self.queue if t == "next" else self.history):
                return ("End of queue" if t == "next" else "Nothing before this"), "error"
            if self.talk and not (self.resume_after_talk or self._loading()):
                self.no_resume = True  # paused before the talk: stays paused on the new track
            self._music_control(t)
        elif t == "add":
            # Queueing by voice: whatever is asked for, the library, in order (no query is read).
            current = self.track if self._has_current() else None
            if a["kind"] == SIMILAR and current is None:
                return "Nothing playing", "error"
            have = {current.id} if current else set()
            if a["where"] != "instead":
                have |= {q.id for q in self.queue}
            limit = a.get("count") or (QUEUE_SIMILAR if a["kind"] == SIMILAR else MAX_QUEUE)
            added = [q for q in self.library if q.id not in have][:limit]
            if not added:
                return "Nothing to add", "error"
            if current is None:
                # nothing loaded: they just start playing, and the music says so
                self.queue = added[1:]
                self._start(added[0])
                self.send_state()
                return None
            if a["where"] == "next":
                self.queue[0:0] = added
            else:
                self.queue = (self.queue if a["where"] == "end" else []) + added
            del self.queue[MAX_QUEUE:]
            self.send_state()
            what = self._name(added[0]) if len(added) == 1 else f"{len(added)} songs"
            return (f"Next: {what}" if a["where"] == "next" else f"Added {what}"), "ok"
        elif t == "remove":
            if "at" in a:
                gone = self._upcoming(a["at"], snap)
            else:
                gone = [q for q in self.queue if command_text(q.artist) == a["artist"]]
            if not gone:
                return "Nothing to remove", "error"
            self.queue = [q for q in self.queue if not any(q is g for g in gone)]
            self.send_state()
            return f"Removed {self._name(gone[0]) if len(gone) == 1 else f'{len(gone)} songs'}", "ok"
        elif t == "move":
            moved = self._upcoming(a["at"], snap)
            if not moved:
                return "Nothing to move", "error"
            rest = [q for q in self.queue if not any(q is m for m in moved)]
            at = min(a["to"] - 1, len(rest))
            self.queue = rest[:at] + moved + rest[at:]
            self.send_state()
            where = "next" if at == 0 else "the end" if at == len(rest) else str(a["to"])
            return f"Moved {self._name(moved[0]) if len(moved) == 1 else f'{len(moved)} songs'} to {where}", "ok"
        elif t == "clear":
            if not self.queue:
                return "Nothing to clear", "error"
            self.queue.clear()
            self.send_state()
            return "Cleared the queue", "ok"
        elif t == "jump":
            if a["at"] > 0:
                found = self._upcoming([a["at"]], snap)
                if not found:
                    return "That song is gone", "error"
                i = next(i for i, q in enumerate(self.queue) if q is found[0])
                skipped, self.queue = self.queue[:i], self.queue[i + 1 :]
            else:
                # a played track is put back right after the current one and played; the queue stays
                found, skipped = [snap["played"][-a["at"] - 1]], []
            self.resume_after_talk = False
            self.no_resume = False
            self.undo = None  # a new current track forgets the undo
            if self._has_current():
                self.history.append(self.track)
            self.history += skipped
            self._start(found[0])
            self.send_state()
        elif t in ("restart", "seek"):
            m = self.music
            if not m:
                return "Nothing playing", "error"
            now = now_ms()
            pos = 0 if t == "restart" else self._position(now) + a["by"] * 1000 if "by" in a else a["to"] * 1000
            pos = max(0, min(pos, m["durationMs"]) if m["durationMs"] > 0 else pos)
            if m["playing"]:
                self._play_from(pos, now + PLAY_LEAD_MS)
            else:  # paused, or by the talk: it resumes from here
                m.update(positionMs=pos, atHostTimeMs=now)
            self.send_state()
        elif t == "repeat":
            self.repeat = None if a["mode"] == "off" else a["mode"]
            self.send_state()
        elif t in VOLUME_ACTIONS:
            log(f"   local: {t} handled here (the peer has no real volume)")
        elif t == "tell":
            return self._tell(a["about"])
        elif t == "undo":
            if prior is None or now_ms() - prior[0] > UNDO_MS:
                return "Nothing to undo", "error"
            self.undo = None  # one level: an undo cannot be undone
            current = self.track.id if self._has_current() else None
            back = [q for q in prior[1] if q.id != current]
            n = sum(1 for q in back if not any(q is u for u in self.queue))
            self.queue, self.repeat = back, prior[2]
            self.send_state()
            return (f"Put back {n} song{'s' if n != 1 else ''}" if n else "Undone"), "ok"
        elif t == "shuffle":
            # the upcoming queue only; the current track stays (PROTOCOL.md "Commands")
            if len(self.queue) < 2:
                return "Nothing to shuffle", "error"
            random.shuffle(self.queue)
            self.send_state()
            return "Shuffled", "ok"
        return None  # `end` does nothing but end the talk

    def _tell(self, about: str) -> tuple[str, str]:
        """`tell`: the host says the fact in its own words from its own data."""
        t = self.track if self._has_current() else None
        if about in ("track", "album") and t is None:
            return "Nothing playing", "error"
        if about == "track":
            return self._name(t), "ok"
        if about == "album":
            return (f"From {t.album}" if t.album else "Album unknown"), "ok"
        if about == "next":
            return (f"Next: {self._name(self.queue[0])}" if self.queue else "Nothing after this"), "ok"
        if about == "previous":
            return (f"Before this: {self._name(self.history[-1])}" if self.history else "Nothing before this"), "ok"
        minutes = round(sum(q.duration_ms for q in self.queue) / 60_000)
        return (f"{len(self.queue)} songs left, about {minutes} minutes" if self.queue else "Nothing after this"), "ok"

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
            elif cmd in ("next", "previous"):
                self._music_control(cmd)
            elif cmd == "queue":
                self.queue = [t for t in self.library if t is not self.track][:MAX_QUEUE]
                log(f"queue: {[t.title for t in self.queue]}")
                self.send_state()
            elif cmd == "repeat":
                arg = rest.strip().lower()
                if arg not in ("off", "track", "queue"):
                    log("usage: repeat off|track|queue")
                    continue
                self._set_repeat(arg)
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
