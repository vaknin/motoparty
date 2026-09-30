"""`motoparty-peer client`: stands in for the iPhone against a host (the Pixel or `peer host`)."""

from __future__ import annotations

import asyncio
import contextlib
import json
import socket
from pathlib import Path

from . import discovery
from .audio import Mic, Speaker, Tone
from .clock import ClockEstimator
from .commands import VOLUME_ACTIONS, FirstPhraseGate, parse_command
from .music import LocalPlayer, cache_path, check_decodable, download
from .protocol import (
    CONTROL_PORT,
    LIVENESS_TIMEOUT_MS,
    PING_INTERVAL_MS,
    FatalFrame,
    ProtocolError,
    decode_message,
    encode_frame,
    frame,
    is_known,
    now_ms,
    read_frame,
)
from .util import js, log, stdin_lines
from .voice import Pacer, VoiceProtocol, VoiceReceiver, VoiceSender

HELP = """commands: talk | hear <phrase> (recognised in the talk: first phrase rule; a command
          ends the talk, a volume phrase is local and closes it from here) |
          say <text> (command.text as is) | pause | resume | next | previous | vol+ | vol- (local) |
          unavailable (toggle "my mic is dead") | search songs|albums|playlists <query> |
          browse <n> | enqueue now|next|end <n>|all | edit jump|remove <i> | edit clear |
          stats | raw <json> (send unvalidated) | quit"""

MUSIC_CONTROL = {"pause": "pause", "resume": "resume", "next": "next", "previous": "previous"}

# PROTOCOL.md "Talk flow" 1: a talk.open request the host has not answered is dropped after 5 s.
TALK_REQUEST_TIMEOUT_MS = 5000
# How long after its own gapless change the peer waits for the host's music.play before warning.
CHANGE_CONFIRM_MS = 2000

# Volume is local (PROTOCOL.md "Commands"): these buttons never reach the wire.
LOCAL_VOLUME = {"vol+": "volumeUp", "vol-": "volumeDown"}


class Client:
    def __init__(self, args) -> None:
        self.args = args
        self.name = args.name or socket.gethostname()
        self.audio = not args.no_audio
        self.clock = ClockEstimator()
        self.clock_host: str | None = None  # name of the host the clock window belongs to
        self.conn: discovery.Connection | None = None
        no_mdns = bool(getattr(args, "no_mdns", False))
        # PROTOCOL.md "Discovery"; kept across reconnects (backoff table, last host linked).
        # --no-mdns: no browse, so the sweep starts at once instead of after 3 s.
        self.discovery = discovery.Discovery(
            port=args.port or CONTROL_PORT, mdns=not no_mdns, sweep_delay=0.0 if no_mdns else discovery.SWEEP_DELAY, log=log
        )
        self.talk = False
        self.gate: FirstPhraseGate | None = None  # this talk's first-phrase gate
        # This talk's mic mode from the host's talk.open: "host" = host-mic talk (PROTOCOL.md
        # "Host-mic talk"): no mic, no audio sent, keepalives only, no command.text.
        self.talk_mic: str | None = None
        self.ping_id = 0
        self.last_rx = 0
        self.sender: VoiceSender | None = None
        self.receiver: VoiceReceiver | None = None
        self.voice_transport = None
        self.source_task: asyncio.Task | None = None
        self.mic: Mic | None = None
        self.speaker: Speaker | None = None
        self.player = LocalPlayer(args.play)
        self.cache_dir = Path(args.cache_dir).expanduser()
        self.downloads: dict[str, asyncio.Task] = {}
        self.cached: dict[str, Path] = {}
        self.quitting = False
        self.http_port: int | None = None
        # "this phone cannot open its microphone": refuse talk with reason "unavailable"
        self.mic_unavailable = bool(getattr(args, "mic_unavailable", False))
        # Browsing: newest request id, its kind, the numbered results and the host's queue
        self.req_id = 0
        self.req_kind = "songs"
        self.req_album: str | None = None  # collection title when browsing one
        self.results: list[dict] = []
        self.queue: list[dict] = []
        # Talk flow 1: the timer of a talk.open request the host has not decided yet
        self.talk_request: asyncio.TimerHandle | None = None
        self.got_bye = False
        # Music flow 6 bookkeeping (the peer has no gapless player: it logs what a client does).
        # anchor: the music.play this client is playing on; pending_next: the accepted
        # music.next (id, atHostTimeMs); changed: the change made on our own clock that the
        # host's music.play has not confirmed yet.
        self.cur_id: str | None = None
        self.anchor: tuple[str, int, int] | None = None
        self.pending_next: tuple[str, int] | None = None
        self.next_timer: asyncio.TimerHandle | None = None
        self.changed: tuple[str, int] | None = None
        self.changed_timer: asyncio.TimerHandle | None = None
        self.durations: dict[str, int] = {}

    # ------------------------------------------------------------------ lifecycle

    async def run(self) -> None:
        self.loop = asyncio.get_running_loop()
        self.main = asyncio.current_task()
        stdin_task = asyncio.create_task(self._stdin())
        if self.audio:
            from .audio import describe_devices  # noqa: PLC0415

            log("audio:", describe_devices())
            self.speaker = Speaker(self._pull_pcm, self.args.output_device)
            self.speaker.start()
        try:
            while not self.quitting:
                conn = await self._connect()
                if conn is None:
                    continue
                await self._session(conn)
                if self.quitting:
                    break
                if self.args.host or self.got_bye:
                    log("link down; reconnecting in 1 s")
                    await asyncio.sleep(1)
                else:
                    # PROTOCOL.md "Discovery" 5: the last host address is probed at once
                    log("link down; restarting discovery")
        except asyncio.CancelledError:
            if not self.quitting:
                raise
        finally:
            stdin_task.cancel()
            self.player.stop()
            if self.speaker:
                self.speaker.stop()
            log("bye")

    async def _connect(self) -> discovery.Connection | None:
        a = self.args
        try:
            if a.host:
                log(f"connecting to {a.host}:{a.port}")
                return await discovery.handshake(a.host, a.port, self.name, 3.0, 3.0)
            found = await self.discovery.find()
            if found is None:
                return None
            log(f"discovery: host {found.hello['name']!r} at {found.ip}:{found.port} (via {found.via})")
            try:
                conn = await discovery.handshake(found.ip, found.port, self.name, 3.0, 3.0)
            except BaseException:
                self.discovery.mark_failed(found.ip, found.port)
                raise
            if found.bonjour is not None:
                txt = found.bonjour.txt
                for key, fld in (("voice", "voicePort"), ("http", "httpPort")):
                    if txt.get(key) != str(conn.hello[fld]):
                        log(f"warning: TXT {key}={txt.get(key)!r} but hello {fld}={conn.hello[fld]}")
                if txt.get("proto") != "1":
                    log(f"warning: TXT proto={txt.get('proto')!r}, expected '1'")
            self.discovery.prefer = conn.hello["name"]  # the last host linked wins a tie next time
            self.discovery.last = (conn.ip, conn.port)  # Discovery 5: probed first after a link loss
            return conn
        except discovery.ProtoMismatch as e:
            self._proto_refused(a.host or found.ip, a.port if a.host else found.port, str(e))
            return None
        except (OSError, asyncio.TimeoutError, asyncio.IncompleteReadError, ProtocolError) as e:
            log(f"connect failed: {type(e).__name__}: {e}")
            await asyncio.sleep(2)
            return None

    def _proto_refused(self, ip: str, port: int, why: str) -> None:
        """PROTOCOL.md "Control channel": a host with another proto is shown and not connected
        to again until the user asks: no reconnect loop. With --host that is the only host, so
        the client stops; with discovery the address is never probed again."""
        log(f"!! PROTOCOL VERSION MISMATCH with {ip}:{port}: {why}; not connecting to this host again")
        self.discovery.blocked.add((ip, port))
        if self.discovery.last == (ip, port):
            self.discovery.last = None
        if self.args.host:
            log("stopping (restart the peer to try again)")
            self.quitting = True

    async def _session(self, conn: discovery.Connection) -> None:
        self.conn = conn
        self.got_bye = False
        self.last_rx = now_ms()
        self.http_port = conn.hello["httpPort"]
        # PROTOCOL.md "Clock": a reconnect to the same host keeps the window.
        if self.clock_host not in (None, conn.hello["name"]):
            log(f"clock: different host ({conn.hello['name']!r}), window cleared")
            self.clock.clear()
        self.clock_host = conn.hello["name"]
        log(f"connected to {conn.ip}:{conn.port}")
        log("<<", js(conn.hello))
        loop = self.loop
        transport, _ = await loop.create_datagram_endpoint(
            lambda: VoiceProtocol(self._on_voice), remote_addr=(conn.ip, conn.hello["voicePort"])
        )
        self.voice_transport = transport
        self.sender = VoiceSender(transport.sendto)
        self.receiver = VoiceReceiver()
        tasks = [asyncio.create_task(c) for c in (self._pinger(), self._keepalive(), self._stats())]
        if not self.audio:
            tasks.append(asyncio.create_task(self._headless_playout()))
        recv = asyncio.create_task(self._recv_loop(conn))
        tasks.append(asyncio.create_task(self._watchdog(recv)))
        try:
            await recv
        except asyncio.CancelledError:
            if asyncio.current_task().cancelling():
                raise
        finally:
            for t in tasks:
                t.cancel()
            self._set_talk(False, quiet=True)
            self._drop_request()
            self._cancel_next("the link going down")
            self._forget_change()
            self.anchor = self.cur_id = None
            self.conn = None
            conn.writer.close()
            transport.close()
            self.sender = None
            if self.receiver:
                log("voice (final):", self.receiver.summary())

    async def _recv_loop(self, conn: discovery.Connection) -> None:
        while True:
            try:
                payload = await read_frame(conn.reader)
            except asyncio.IncompleteReadError:
                log("host closed the connection")
                return
            except FatalFrame as e:
                log(f"!! protocol error: {e}; closing")
                return
            except OSError as e:
                log(f"connection error: {e}")
                return
            self.last_rx = now_ms()
            try:
                msg = decode_message(payload)
            except FatalFrame as e:
                log(f"!! protocol error: {e}; closing")
                return
            except ProtocolError as e:
                log(f"!! dropped invalid frame ({e}): {payload[:300]!r}")
                continue
            if await self._handle(msg) == "close":
                return

    async def _watchdog(self, recv: asyncio.Task) -> None:
        while True:
            await asyncio.sleep(0.25)
            if now_ms() - self.last_rx > LIVENESS_TIMEOUT_MS:
                log(f"link lost: nothing received for {LIVENESS_TIMEOUT_MS} ms")
                recv.cancel()
                return

    # ------------------------------------------------------------------ control

    def send(self, msg: dict, *, quiet: bool = False) -> bool:
        if self.conn is None:
            log("not connected")
            return False
        try:
            self.conn.writer.write(encode_frame(msg))
        except ProtocolError as e:
            log(f"refusing to send invalid message: {e}")
            return False
        if not quiet or self.args.verbose:
            log(">>", js(msg))
        return True

    async def _pinger(self) -> None:
        while True:
            self.ping_id += 1
            self.send({"t": "ping", "id": self.ping_id, "t0": now_ms()}, quiet=True)
            await asyncio.sleep(PING_INTERVAL_MS / 1000)

    async def _handle(self, msg: dict) -> str | None:
        t = msg["t"]
        if t == "pong":
            t3 = now_ms()
            s = self.clock.add(msg["t0"], msg["t1"], msg["t2"], t3)
            best = self.clock.best
            if self.args.verbose:
                log("<<", js(msg))
            if s is None:
                log(f"<< pong id={msg['id']} DISCARDED (negative rtt)")
            else:
                log(f"<< pong id={msg['id']} rtt={s.rtt}ms offset={s.offset:.1f}ms "
                    f"| estimate offset={best.offset:.1f}ms (rtt {best.rtt}ms)")
            return None
        log("<<", js(msg))
        if not is_known(msg):
            log(f"   (unknown type {t!r} ignored)")
        elif t == "hello":
            log("   (unexpected second hello ignored)")
        elif t == "state":
            # A talk learnt only from state (joined mid-talk) opens in the mode state names.
            self._set_talk(msg["talk"], mic=msg.get("mic"))
            self.queue = msg["queue"]
            self._state_vs_next(msg.get("music"))
        elif t == "music.results":
            self._results(msg)
        elif t == "talk.open":
            if msg.get("mic") == "host":
                # PROTOCOL.md "Host-mic talk": we open no microphone, so a dead or unpermitted
                # mic is no reason to refuse; only a failed playback route would be.
                if self.mic_unavailable:
                    log("   (mic marked unavailable, but a host-mic talk needs none: accepting)")
                self._set_talk(True, by=msg["by"], mic="host")
            elif self.mic_unavailable:
                # PROTOCOL.md "Talk flow" 1: the failure is on our side, so we answer the
                # host's talk.open with a close request; the host treats it as one.
                log("   MIC UNAVAILABLE: refusing talk (the host asked, so it plays the error earcon)")
                self.send({"t": "talk.close", "by": "client", "reason": "unavailable"})
            else:
                self._set_talk(True, by=msg["by"])
        elif t == "talk.close":
            if msg["reason"] == "unavailable" and not self.talk:
                log(f"   TALK REFUSED by {msg['by']}: microphone unavailable "
                    f"(talk never opened, state.talk stays false) [earcon error]")
            self._drop_request()
            self._set_talk(False)
        elif t == "announce":
            earcon = f" [earcon {msg['earcon']}]" if "earcon" in msg else ""
            log(f"ANNOUNCE (would speak): {msg['text']!r}{earcon}")
        elif t == "music.load":
            self.durations[msg["id"]] = msg["durationMs"]
            self._start_download(msg)
        elif t == "music.play":
            await self._music_play(msg)
        elif t == "music.next":
            self._music_next(msg)
        elif t in ("music.pause", "music.stop"):
            self._cancel_next(t)
            self._forget_change()
            self.anchor = None
            self.cur_id = msg.get("id")
            self.player.stop()
            log(f"music: {t.split('.')[1]} (local player stopped)")
        elif t == "bye":
            log(f"host said bye ({msg.get('reason', 'no reason')})")
            self.got_bye = True
            if msg.get("reason") == "proto" and self.conn is not None:
                self._proto_refused(self.conn.ip, self.conn.port, "the host said bye{reason:'proto'}")
            return "close"
        else:
            log(f"   (unexpected {t!r} from host, ignored)")
        return None

    # ------------------------------------------------------------------ talk / voice

    def _set_talk(self, open_: bool, quiet: bool = False, by: str | None = None,
                  mic: str | None = None) -> None:
        if open_ == self.talk:
            return
        if open_ and self.mic_unavailable and mic != "host":
            return  # we refused; never open the mic, whatever state the host broadcasts
        self.talk = open_
        if open_:
            self._drop_request()  # the host decided
            self._cancel_next("a talk")
            self._forget_change()
            self.anchor = None  # music is paused for the talk; the host sends music.play after it
        if open_ and mic == "host":
            # Receive only: music paused, voice played, media mode kept; the keepalive task
            # goes on sending keepalives, and nothing is recognised or sent as a command.
            self.talk_mic = "host"
            self.gate = None
            self.player.stop()
            log("TALK OPEN - talk mode: host-mic (receive only)")
        elif open_:
            # The live earcon plays now (the peer has none). A talk seen only through `state`
            # (joined mid-talk) has no known opener, so this phone is not it.
            self.gate = FirstPhraseGate("opener" if by == "client" else "other", now_ms())
            self.player.stop()  # both sides pause music locally during talk
            if self.sender:
                self.sender.begin_session()
            what = self._start_source()
            log(f"TALK OPEN - sending {what}")
        else:
            self._stop_source()
            self.gate = None
            self.talk_mic = None
            if not quiet:
                log("TALK CLOSED")

    def _drop_request(self) -> None:
        if self.talk_request is not None:
            self.talk_request.cancel()
            self.talk_request = None

    def _request_timeout(self) -> None:
        self.talk_request = None
        log(f"talk: request unanswered for {TALK_REQUEST_TIMEOUT_MS} ms, dropped [earcon error]")

    def _talk_trigger(self) -> None:
        """The talk trigger (stdin `talk`), PROTOCOL.md "Talk flow" 1 and 3."""
        if self.talk:
            self.send({"t": "talk.close", "by": "client", "reason": "trigger"})
        elif self.talk_request is not None:
            # A second trigger before the host's decision: the host, having opened the talk
            # meanwhile, closes it.
            log("talk: second trigger before the host's decision; taking the request back")
            self._drop_request()
            self.send({"t": "talk.close", "by": "client", "reason": "trigger"})
        elif self.mic_unavailable:
            log("mic is marked unavailable; not asking for talk ('unavailable' to toggle)")
        elif self.send({"t": "talk.open", "by": "client"}):
            self.talk_request = self.loop.call_later(TALK_REQUEST_TIMEOUT_MS / 1000, self._request_timeout)

    def _hear(self, phrase: str) -> None:
        """A phrase the on-device ASR recognised on the talk microphone (PROTOCOL.md "Commands"):
        only the first non-empty one in a talk this client opened, within 8 s of the live
        earcon, is a command, and only if it parses."""
        if self.talk and self.talk_mic == "host":
            log(f"hear: {phrase!r} skipped: host-mic talk, the host recognises commands; nothing sent")
            return
        if not self.talk or self.gate is None:
            log("hear: no talk open; commands are spoken inside a talk, nothing sent")
            return
        text = self.gate.phrase(phrase, now_ms())
        if text is None:
            log(f"hear: {phrase!r} is conversation ({self.gate.why}), not sent")
        elif parse_command(text)["action"] in VOLUME_ACTIONS:
            # Volume is local and, like every command, ends the talk it was spoken in: the phone
            # that heard it closes the talk itself (PROTOCOL.md "Commands", Effect on the talk).
            log(f"local: {parse_command(text)['action']} handled here [earcon ok]; "
                f"no command.text sent (the peer has no real volume); it ends the talk")
            self._talk_trigger()
        else:
            self.send({"t": "command.text", "text": text, "lang": self.args.lang})

    def _start_source(self) -> str:
        if self.args.tone:
            self.source_task = asyncio.create_task(self._tone())
            return "440 Hz tone"
        if not self.audio:
            return "nothing (--no-audio without --tone)"
        loop = self.loop

        def on_frame(pcm: bytes) -> None:
            loop.call_soon_threadsafe(self._send_pcm, pcm)

        try:
            self.mic = Mic(on_frame, self.args.input_device)
            self.mic.start()
        except Exception as e:  # PortAudio errors are not a common base class
            log(f"!! microphone failed: {e}")
            self.mic = None
            return "nothing (mic failed)"
        return "microphone"

    def _stop_source(self) -> None:
        if self.source_task:
            self.source_task.cancel()
            self.source_task = None
        if self.mic:
            self.mic.stop()
            self.mic = None

    def _send_pcm(self, pcm: bytes) -> None:
        if self.talk and self.talk_mic != "host" and self.sender:
            self.sender.send_pcm(pcm)

    async def _tone(self) -> None:
        tone = Tone()
        async for _ in Pacer().ticks():
            self._send_pcm(tone.frame())

    def _on_voice(self, pkt, addr) -> None:
        if self.receiver:
            self.receiver.push(pkt)

    def _pull_pcm(self) -> bytes:
        r = self.receiver
        return r.pull_pcm() if r else bytes(640)

    async def _headless_playout(self) -> None:
        async for _ in Pacer().ticks():
            self._pull_pcm()

    async def _keepalive(self) -> None:
        while True:
            await asyncio.sleep(0.1)
            if self.sender and self.sender.keepalive_due():
                self.sender.send_keepalive()

    async def _stats(self) -> None:
        while True:
            await asyncio.sleep(2)
            r, s = self.receiver, self.sender
            if r is None or s is None:
                continue
            recent = r.last_audio_ms is not None and now_ms() - r.last_audio_ms < 2500
            if self.talk or recent:
                log(f"voice rx: {r.summary()} | tx: audio={s.stats.audio} dtx_skipped={s.stats.dtx_skipped} "
                    f"keepalive={s.stats.keepalive}")

    # ------------------------------------------------------------------ music

    def _start_download(self, msg: dict) -> None:
        id_ = msg["id"]
        if not msg["path"].startswith("/track/"):
            log(f"warning: music.load path {msg['path']!r} is outside /track/")
        old = self.downloads.get(id_)
        if old and not old.done():
            return
        self.downloads[id_] = asyncio.create_task(self._download(msg))

    async def _download(self, msg: dict) -> None:
        id_ = msg["id"]
        conn = self.conn
        if conn is None:
            return
        url = f"http://{conn.ip}:{self.http_port}{msg['path']}"
        dest = cache_path(self.cache_dir, id_)
        t0 = now_ms()
        try:
            size = await asyncio.to_thread(download, url, dest)
            await asyncio.to_thread(check_decodable, dest)
        except RuntimeError as e:
            log(f"music: {id_} failed: {e}")
            if self.conn is conn:
                self.send({"t": "music.error", "id": id_, "message": str(e)})
            return
        self.cached[id_] = dest
        log(f"music: cached {id_} ({size} bytes in {now_ms() - t0} ms) -> {dest}")
        if self.conn is conn:
            self.send({"t": "music.ready", "id": id_})

    # Music flow 6 (gapless). The peer's player (mpv/ffplay) cannot queue a track behind another,
    # so this is the bookkeeping of a real client, logged: what is accepted, what cancels it,
    # when the change happens on our clock, and whether the host's messages agree.

    def _local(self, host_ms: int) -> float:
        return self.clock.host_to_local(host_ms) if self.clock.ready else float(host_ms)

    def _cancel_next(self, why: str) -> None:
        if self.next_timer is not None:
            self.next_timer.cancel()
            self.next_timer = None
        if self.pending_next is not None:
            log(f"music.next: pending {self.pending_next[0]} CANCELLED by {why}")
            self.pending_next = None

    def _forget_change(self) -> None:
        if self.changed_timer is not None:
            self.changed_timer.cancel()
            self.changed_timer = None
        self.changed = None

    def _music_next(self, msg: dict) -> None:
        id_, at = msg["id"], msg["atHostTimeMs"]
        if self.pending_next == (id_, at):
            log(f"music.next: {id_} repeated unchanged, still pending")
            return
        if self.changed is not None:
            # Sent after the music.play of a change we made first: it names the track after.
            log("music.next: arrived before the music.play of our own change; it names the track after it")
        if self.pending_next is not None and self.pending_next[0] != id_:
            self._cancel_next("a music.next naming another track")
        if self.anchor is None or self.talk:
            log(f"music.next: WARNING {id_} announced while nothing is playing here "
                f"({'a talk is open' if self.talk else 'no music.play anchor'}); not accepted, "
                f"it starts on its music.play")
            return
        notes = []
        if id_ not in self.cached:
            notes.append("WARNING: we never answered music.ready for it")
        dur = self.durations.get(self.anchor[0])
        if dur:
            off = at - (self.anchor[2] + dur - self.anchor[1])
            notes.append(f"{off:+d} ms from the end by music.load durationMs")
        wait = self._local(at) - now_ms()
        if wait < 0:
            notes.append(f"WARNING: that time is {-wait:.0f} ms in the past")
        if self.next_timer is not None:
            self.next_timer.cancel()
        self.pending_next = (id_, at)
        self.next_timer = self.loop.call_later(max(0.0, wait) / 1000, self._change_over, "own clock")
        log(f"music.next: ACCEPTED {id_} behind {self.anchor[0]}, change at host {at} "
            f"(in {wait:.0f} ms){'; ' if notes else ''}{'; '.join(notes)}")

    def _change_over(self, via: str) -> None:
        if self.pending_next is None:
            return
        id_, at = self.pending_next
        if self.next_timer is not None:
            self.next_timer.cancel()
            self.next_timer = None
        self.pending_next = None
        self.cur_id, self.anchor = id_, (id_, 0, at)
        log(f"music.next: CHANGE to {id_} at host {at} (by {via}); anchor is now {{{id_}, 0, {at}}}")
        file = self.cached.get(id_)
        if file is not None:
            self.player.schedule(file, 0, self._local(at))
        if via == "own clock":
            self._forget_change()
            self.changed = (id_, at)
            self.changed_timer = self.loop.call_later(CHANGE_CONFIRM_MS / 1000, self._change_unconfirmed)
            log(f"music.next: awaiting the host's state and music.play{{{id_}, 0, {at}}}")

    def _change_unconfirmed(self) -> None:
        self.changed_timer = None
        if self.changed is not None:
            log(f"music.next: WARNING no music.play for the change to {self.changed[0]} within "
                f"{CHANGE_CONFIRM_MS} ms of it")
            self.changed = None

    def _play_vs_next(self, msg: dict) -> bool:
        """A music.play against the gapless bookkeeping. True: nothing to seek or restart."""
        key = (msg["id"], msg["positionMs"], msg["atHostTimeMs"])
        if self.pending_next is not None and key == (self.pending_next[0], 0, self.pending_next[1]):
            log("music.next: the music.play of the change arrived before our own clock got there")
            self._change_over("the host's music.play")
            return True
        if self.changed is not None:
            announced = (self.changed[0], 0, self.changed[1])
            self._forget_change()
            if key == announced:
                log("music.next: music.play of the change carries the announced anchor; no seek, no restart")
                return True
            log(f"music.next: WARNING the music.play after the change does not carry the announced "
                f"anchor: announced {announced}, got {key}")
        if self.pending_next is not None:
            if msg["id"] == self.pending_next[0]:
                log(f"music.next: WARNING music.play for the pending track is not on the announced "
                    f"anchor: announced {(self.pending_next[0], 0, self.pending_next[1])}, got {key}")
            if key == self.anchor:
                self._cancel_next("a music.play repeating the current anchor (taken back; no seek)")
                return True
            self._cancel_next(f"a music.play for {msg['id']} that is not the one of the change")
        elif key == self.anchor:
            log("music: play repeats the current anchor; no seek")
            return True
        return False

    def _state_vs_next(self, music: dict | None) -> None:
        if self.pending_next is None:
            return
        pend = self.pending_next
        if music is None:
            self._cancel_next("a state without music")
        elif not music["playing"]:
            self._cancel_next("a state whose music is not playing")
        elif music["id"] == pend[0]:
            on = (music["positionMs"], music["atHostTimeMs"]) == (0, pend[1])
            log("music.next: state names the pending track: the host changed over first; awaiting its music.play"
                + ("" if on else f"; WARNING its anchor {(music['positionMs'], music['atHostTimeMs'])} "
                                 f"is not the announced (0, {pend[1]})"))
        elif music["id"] != self.cur_id:
            self._cancel_next(f"a state naming {music['id']}, neither the current nor the pending track")

    async def _music_play(self, msg: dict) -> None:
        id_ = msg["id"]
        if self._play_vs_next(msg):
            return
        pending = self.downloads.get(id_)
        if pending and not pending.done():
            await asyncio.shield(pending)
        if self.clock.ready:
            local = self.clock.host_to_local(msg["atHostTimeMs"])
        else:
            log("warning: no clock estimate yet, assuming offset 0")
            local = float(msg["atHostTimeMs"])
        delta = local - now_ms()
        log(f"music: play {id_} from {msg['positionMs']} ms at host {msg['atHostTimeMs']} "
            f"= local {local:.1f} ({'in' if delta >= 0 else 'LATE by'} {abs(delta):.0f} ms)")
        if self.talk:
            log("music: talk is open, not starting local playback")
            return
        file = self.cached.get(id_)
        if file is None:
            log(f"music: {id_} is not cached, cannot play")
            return
        self.cur_id, self.anchor = id_, (id_, msg["positionMs"], msg["atHostTimeMs"])
        self.player.schedule(file, msg["positionMs"], local)

    # ------------------------------------------------------------------ browsing

    def _request(self, msg: dict, kind: str, album: str | None = None) -> None:
        self.req_id += 1
        self.req_kind, self.req_album = kind, album
        self.send({**msg, "id": self.req_id})

    def _results(self, msg: dict) -> None:
        if msg["id"] != self.req_id:  # PROTOCOL.md "Browsing" 1: only the newest request
            log(f"   (results for old request {msg['id']} ignored)")
            return
        self.results = msg["items"]
        if "error" in msg:
            log(f"   results: {msg['error']}")
        for n, it in enumerate(self.results, 1):
            extra = f" ({it['count']} tracks)" if "count" in it else ""
            extra += f" {it['durationMs'] // 1000}s" if "durationMs" in it else ""
            log(f"   {n:3}. {it['title']} - {it['artist'] or '?'}{extra}")

    def _pick(self, arg: str) -> list[dict] | None:
        """`all` or a 1-based result number -> result items, else None."""
        if arg == "all":
            return self.results
        if arg.isdigit() and 1 <= int(arg) <= len(self.results):
            return [self.results[int(arg) - 1]]
        log(f"no result {arg!r} (have {len(self.results)})")
        return None

    def _browse_cmd(self, cmd: str, rest: str) -> None:
        args = rest.split()
        if cmd == "search":
            kind, _, query = rest.strip().partition(" ")
            if kind not in ("songs", "albums", "playlists"):
                log("usage: search songs|albums|playlists <query>")
                return
            self._request({"t": "music.search", "kind": kind, "query": query.strip()}, kind)
        elif cmd == "browse":
            items = self._pick(args[0]) if len(args) == 1 and args[0] != "all" else None
            if items is None or self.req_kind == "songs":
                log("usage: browse <n> (an album or playlist result)")
                return
            self._request({"t": "music.browse", "ref": items[0]["ref"]}, "songs",
                          items[0]["title"] if self.req_kind == "albums" else None)
        elif cmd == "enqueue":
            items = self._pick(args[1]) if len(args) == 2 and args[0] in ("now", "next", "end") else None
            if items is None or self.req_kind != "songs":
                log("usage: enqueue now|next|end <n>|all (song results)")
                return
            tracks = []
            for it in items:
                tr = {"id": it["ref"], "title": it["title"], "artist": it["artist"], "durationMs": it.get("durationMs", 0)}
                if self.req_album:
                    tr["album"] = self.req_album
                if "art" in it:
                    tr["art"] = it["art"]
                tracks.append(tr)
            self.send({"t": "music.enqueue", "mode": args[0], "tracks": tracks})
        elif cmd == "edit":
            if args == ["clear"]:
                self.send({"t": "music.edit", "op": "clear"})
            elif len(args) == 2 and args[0] in ("jump", "remove") and args[1].isdigit() and int(args[1]) < len(self.queue):
                i = int(args[1])
                self.send({"t": "music.edit", "op": args[0], "index": i, "id": self.queue[i]["id"]})
            else:
                log(f"usage: edit jump|remove <i> (0-based, queue has {len(self.queue)}) | edit clear")

    # ------------------------------------------------------------------ stdin

    async def _stdin(self) -> None:
        q = stdin_lines(self.loop)
        log(HELP)
        while True:
            line = await q.get()
            if line is None:
                return  # EOF: keep running without commands
            cmd, _, rest = line.strip().partition(" ")
            if not cmd:
                continue
            if cmd == "talk":
                self._talk_trigger()
            elif cmd == "unavailable":
                self.mic_unavailable = not self.mic_unavailable
                log("mic UNAVAILABLE: the next talk.open is answered with "
                    "talk.close{by:'client',reason:'unavailable'}" if self.mic_unavailable
                    else "mic available again: talk.open is accepted")
            elif cmd == "say":
                text = rest.strip()
                if not text:
                    log("usage: say <text>")
                    continue
                parsed = parse_command(text)  # volume is local: parse before sending
                if parsed["action"] in VOLUME_ACTIONS:
                    log(f"local: {parsed['action']} handled here [earcon ok]; "
                        f"no command.text sent (the peer has no real volume)")
                else:
                    self.send({"t": "command.text", "text": text, "lang": self.args.lang})
            elif cmd == "hear":
                if rest.strip():
                    self._hear(rest)
                else:
                    log("usage: hear <phrase>")
            elif cmd in LOCAL_VOLUME:
                log(f"local: {LOCAL_VOLUME[cmd]} handled here [earcon ok]; "
                    f"nothing sent (the peer has no real volume)")
            elif cmd in MUSIC_CONTROL:
                self.send({"t": "music.control", "action": MUSIC_CONTROL[cmd]})
            elif cmd in ("search", "browse", "enqueue", "edit"):
                self._browse_cmd(cmd, rest)
            elif cmd == "stats":
                log("voice:", self.receiver.summary() if self.receiver else "(not connected)")
                best = self.clock.best
                log("clock:", f"offset={best.offset:.1f}ms rtt={best.rtt}ms" if best else "(no samples)")
            elif cmd == "raw":
                try:
                    body = json.dumps(json.loads(rest), ensure_ascii=False, separators=(",", ":")).encode()
                except ValueError as e:
                    log(f"raw: invalid JSON: {e}")
                    continue
                if self.conn:
                    self.conn.writer.write(frame(body))
                    log(">> (raw)", body.decode())
            elif cmd == "quit":
                self.quitting = True
                if self.conn:
                    self.send({"t": "bye", "reason": "user"})
                    with contextlib.suppress(Exception):
                        await asyncio.wait_for(self.conn.writer.drain(), 1)
                self.main.cancel()
                return
            else:
                log(HELP)


def run(args) -> None:
    args.port = args.port or CONTROL_PORT
    try:
        asyncio.run(Client(args).run())
    except KeyboardInterrupt:
        pass
