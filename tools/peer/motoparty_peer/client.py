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
from .commands import VOLUME_ACTIONS, parse_command
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

HELP = """commands: talk | say <text> | pause | resume | next | previous | vol+ | vol- (local) |
          unavailable (toggle "my mic is dead") | stats | raw <json> (send unvalidated) | quit"""

MUSIC_CONTROL = {"pause": "pause", "resume": "resume", "next": "next", "previous": "previous"}

# Volume is local (PROTOCOL.md "Commands"): these buttons never reach the wire.
LOCAL_VOLUME = {"vol+": "volumeUp", "vol-": "volumeDown"}


class Client:
    def __init__(self, args) -> None:
        self.args = args
        self.name = args.name or socket.gethostname()
        self.audio = not args.no_audio
        self.clock = ClockEstimator()
        self.conn: discovery.Connection | None = None
        self.talk = False
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
                if not self.quitting:
                    log("link down; restarting discovery in 1 s")
                    await asyncio.sleep(1)
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
            found = None
            if not getattr(a, "no_mdns", False):
                log("discovery: browsing _motoparty._tcp for 3 s")
                found = await asyncio.to_thread(discovery.browse, discovery.BROWSE_SECONDS)
            if found:
                log(f"discovery: bonjour found {found.name!r} at {found.address}:{found.port} txt={found.txt}")
                conn = await discovery.handshake(found.address, found.port, self.name, 3.0, 3.0)
                for key, field in (("voice", "voicePort"), ("http", "httpPort")):
                    if found.txt.get(key) != str(conn.hello[field]):
                        log(f"warning: TXT {key}={found.txt.get(key)!r} but hello {field}={conn.hello[field]}")
                if found.txt.get("proto") != "1":
                    log(f"warning: TXT proto={found.txt.get('proto')!r}, expected '1'")
                return conn
            cands = discovery.sweep_candidates()
            why = "--no-mdns" if getattr(a, "no_mdns", False) else "no bonjour result"
            log(f"discovery: {why}, sweeping {len(cands)} addresses on port {a.port}")
            conn = await discovery.sweep(cands, a.port, self.name)
            if conn is None:
                log("discovery: no host found")
                await asyncio.sleep(1)
            return conn
        except (OSError, asyncio.TimeoutError, asyncio.IncompleteReadError, ProtocolError) as e:
            log(f"connect failed: {type(e).__name__}: {e}")
            await asyncio.sleep(2)
            return None

    async def _session(self, conn: discovery.Connection) -> None:
        self.conn = conn
        self.last_rx = now_ms()
        self.http_port = conn.hello["httpPort"]
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
            self._set_talk(msg["talk"])
        elif t == "talk.open":
            if self.mic_unavailable:
                # PROTOCOL.md "Talk flow" 1: the failure is on our side, so we answer the
                # host's talk.open with a close request; the host treats it as one.
                log("   MIC UNAVAILABLE: refusing talk (the host asked, so it plays the error earcon)")
                self.send({"t": "talk.close", "by": "client", "reason": "unavailable"})
            else:
                self._set_talk(True)
        elif t == "talk.close":
            if msg["reason"] == "unavailable" and not self.talk:
                log(f"   TALK REFUSED by {msg['by']}: microphone unavailable "
                    f"(talk never opened, state.talk stays false) [earcon error]")
            self._set_talk(False)
        elif t == "announce":
            earcon = f" [earcon {msg['earcon']}]" if "earcon" in msg else ""
            log(f"ANNOUNCE (would speak): {msg['text']!r}{earcon}")
        elif t == "music.load":
            self._start_download(msg)
        elif t == "music.play":
            await self._music_play(msg)
        elif t in ("music.pause", "music.stop"):
            self.player.stop()
            log(f"music: {t.split('.')[1]} (local player stopped)")
        elif t == "bye":
            log(f"host said bye ({msg.get('reason', 'no reason')})")
            return "close"
        else:
            log(f"   (unexpected {t!r} from host, ignored)")
        return None

    # ------------------------------------------------------------------ talk / voice

    def _set_talk(self, open_: bool, quiet: bool = False) -> None:
        if open_ and self.mic_unavailable:
            return  # we refused; never open the mic, whatever state the host broadcasts
        if open_ == self.talk:
            return
        self.talk = open_
        if open_:
            self.player.stop()  # both sides pause music locally during talk
            if self.sender:
                self.sender.begin_session()
            what = self._start_source()
            log(f"TALK OPEN - sending {what}")
        else:
            self._stop_source()
            if not quiet:
                log("TALK CLOSED")

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
        if self.talk and self.sender:
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

    async def _music_play(self, msg: dict) -> None:
        id_ = msg["id"]
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
        self.player.schedule(file, msg["positionMs"], local)

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
                if self.talk:
                    self.send({"t": "talk.close", "by": "client", "reason": "trigger"})
                elif self.mic_unavailable:
                    log("mic is marked unavailable; not asking for talk ('unavailable' to toggle)")
                else:
                    self.send({"t": "talk.open", "by": "client"})
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
            elif cmd in LOCAL_VOLUME:
                log(f"local: {LOCAL_VOLUME[cmd]} handled here [earcon ok]; "
                    f"nothing sent (the peer has no real volume)")
            elif cmd in MUSIC_CONTROL:
                self.send({"t": "music.control", "action": MUSIC_CONTROL[cmd]})
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
