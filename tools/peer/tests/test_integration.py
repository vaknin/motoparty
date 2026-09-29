"""Host and client CLIs against each other on localhost, headless (--no-audio)."""

from __future__ import annotations

import json
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path

import pytest

from conftest import load_fixture

PEER_DIR = Path(__file__).resolve().parents[1]


def free_port(kind=socket.SOCK_STREAM) -> int:
    with socket.socket(socket.AF_INET, kind) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Proc:
    def __init__(self, *args: str) -> None:
        self.lines: list[str] = []
        self._cv = threading.Condition()
        self.p = subprocess.Popen(
            [sys.executable, "-u", "-m", "motoparty_peer", *args],
            cwd=PEER_DIR, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, bufsize=1,
        )
        threading.Thread(target=self._read, daemon=True).start()
        self._pos = 0

    def _read(self) -> None:
        for line in self.p.stdout:
            with self._cv:
                self.lines.append(line.rstrip("\n"))
                self._cv.notify_all()

    def send(self, line: str) -> None:
        self.p.stdin.write(line + "\n")
        self.p.stdin.flush()

    def expect(self, pattern: str, timeout: float = 5.0) -> re.Match:
        """Wait for a line after the previous match that matches the regex."""
        rx = re.compile(pattern)
        deadline = time.monotonic() + timeout
        with self._cv:
            while True:
                for i in range(self._pos, len(self.lines)):
                    m = rx.search(self.lines[i])
                    if m:
                        self._pos = i + 1
                        return m
                left = deadline - time.monotonic()
                if left <= 0 or self.p.poll() is not None and self._pos >= len(self.lines):
                    raise AssertionError(f"no line matching {pattern!r}; output:\n" + "\n".join(self.lines[-60:]))
                self._cv.wait(min(left, 0.1))

    def msg(self, direction: str, t: str, timeout: float = 5.0) -> dict:
        m = self.expect(rf"{re.escape(direction)} (\{{\"t\":\"{re.escape(t)}\".*)$", timeout)
        return json.loads(m.group(1))

    def stop(self) -> None:
        if self.p.poll() is None:
            try:
                self.send("quit")
                self.p.wait(3)
            except (OSError, subprocess.TimeoutExpired):
                self.p.kill()
                self.p.wait()

    def output(self) -> str:
        return "\n".join(self.lines)


@pytest.fixture(scope="module")
def track(tmp_path_factory):
    if not shutil.which("ffmpeg"):
        return None
    out = tmp_path_factory.mktemp("track") / "tone.m4a"
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=330:duration=2",
         "-c:a", "aac", "-b:a", "48k", "-metadata", "title=Bench Tone", "-metadata", "artist=Peer", str(out)],
        check=True, timeout=30,
    )
    return out


def start_pair(tmp_path, track, *client_args, host_extra=()):
    cp, vp, hp = free_port(), free_port(socket.SOCK_DGRAM), free_port()
    host_args = ["host", "--no-mdns", "--bind", "127.0.0.1", "--port", str(cp), "--voice-port", str(vp),
                 "--http-port", str(hp), "--name", "Test Host", *host_extra]
    if track:
        host_args += ["--track", str(track)]
    host = Proc(*host_args)
    host.expect(r"control tcp/\d+ voice udp/\d+ http tcp/\d+")
    client = Proc("client", "--host", "127.0.0.1", "--port", str(cp), "--no-audio", "--name", "Test Client",
                  "--cache-dir", str(tmp_path / "cache"), *client_args)
    return host, client, (cp, vp, hp)


def _open_talk(client) -> None:
    client.send("talk")
    client.msg("<<", "talk.open")
    assert client.msg("<<", "state")["talk"] is True


def test_full_session(tmp_path, track):
    host, client, (cp, vp, hp) = start_pair(tmp_path, track, "--tone")
    try:
        # hello both ways; the client learns the ports from the host hello
        hello = client.msg("<<", "hello")
        assert hello == {"t": "hello", "proto": 1, "role": "host", "name": "Test Host", "voicePort": vp, "httpPort": hp}
        assert client.msg("<<", "state") == {"t": "state", "talk": False, "queue": []}
        assert host.msg("<<", "hello")["name"] == "Test Client"

        # ping/pong: same machine, same CLOCK_MONOTONIC -> offset ~ 0
        m = client.expect(r"pong id=\d+ rtt=(-?\d+)ms offset=(-?[\d.]+)ms", timeout=5)
        assert abs(float(m.group(2))) <= 5 and 0 <= int(m.group(1)) < 50

        # talk open (client asks, host decides and broadcasts), audio flows both ways
        client.send("talk")
        assert client.msg(">>", "talk.open") == {"t": "talk.open", "by": "client"}
        assert client.msg("<<", "talk.open") == {"t": "talk.open", "by": "client"}
        client.expect("TALK OPEN - sending 440 Hz tone")
        assert client.msg("<<", "state")["talk"] is True
        m = client.expect(r"voice rx: rx=(\d+) .*played=(\d+) .*underruns=(\d+).*\| tx: audio=(\d+)", timeout=5)
        rx, played, tx = int(m.group(1)), int(m.group(2)), int(m.group(4))
        assert tx >= 20 and rx >= 20 and played >= 15, m.group(0)
        hs = host.expect(r"voice: audio=(\d+) active=(\d+) keepalive=\d+ echoed=(\d+)", timeout=5)
        assert int(hs.group(1)) >= 20 and int(hs.group(3)) >= 20 and int(hs.group(2)) >= 10

        client.send("talk")
        assert client.msg(">>", "talk.close") == {"t": "talk.close", "by": "client", "reason": "trigger"}
        assert client.msg("<<", "talk.close") == {"t": "talk.close", "by": "client", "reason": "trigger"}
        assert client.msg("<<", "state")["talk"] is False

        # command.text outside a talk: ignored and logged, no announce
        client.send("say pause")
        host.expect(r"command.text ignored: no talk is open")
        # in a talk the client opened: the first command.text -> parser -> announce
        client.send("talk")
        client.msg("<<", "talk.open")
        client.send("say what's the weather")
        assert client.msg("<<", "announce") == {"t": "announce", "text": "Didn't catch that", "earcon": "error"}
        # volume is local: the client parses first and sends nothing
        client.send("say Louder!")
        client.expect(r"local: volumeUp handled here \[earcon ok\]")
        client.send("vol+")
        client.expect(r"local: volumeUp handled here \[earcon ok\]")
        assert sum('"command.text"' in line for line in client.lines) == 2  # pause and the weather
        client.send("talk")
        client.msg("<<", "talk.close")
        assert client.msg("<<", "state")["talk"] is False
        assert sum('"announce"' in line for line in client.lines) == 1  # the ignored pause got none

        if track is None:
            pytest.skip("ffmpeg not available: music part skipped")

        # music: load -> client downloads over HTTP -> ready -> play 300 ms ahead
        host.send("load")
        load = client.msg("<<", "music.load")
        assert load["path"] == f"/track/{load['id']}.m4a"
        assert (load["title"], load["artist"]) == ("Bench Tone", "Peer")
        assert 1900 <= load["durationMs"] <= 2100
        assert client.msg(">>", "music.ready") == {"t": "music.ready", "id": load["id"]}
        play = client.msg("<<", "music.play")
        assert play["id"] == load["id"] and play["positionMs"] == 0
        m = client.expect(r"music: play \S+ from 0 ms at host (\d+) = local ([\d.]+) \(in (\d+) ms\)")
        assert abs(float(m.group(2)) - int(m.group(1))) <= 5  # offset ~ 0 on localhost
        assert 150 <= int(m.group(3)) <= 300
        host.expect(r'http: 127\.0\.0\.1 "GET /track/\S+ HTTP/1.1" 200')
        host.expect(r"music: client ready after \d+ ms")
        state = client.msg("<<", "state")
        assert state["music"]["playing"] is True and state["music"]["id"] == load["id"]
        cached = tmp_path / "cache" / f"{load['id']}.m4a"
        assert cached.read_bytes() == track.read_bytes()

        # the track server: Range -> 206, unknown path -> 404
        import urllib.error
        import urllib.request

        url = f"http://127.0.0.1:{hp}{load['path']}"
        req = urllib.request.Request(url, headers={"Range": "bytes=0-99"})
        with urllib.request.urlopen(req, timeout=3) as r:
            assert r.status == 206 and r.headers["Content-Type"] == "audio/mp4"
            assert r.headers["Content-Range"] == f"bytes 0-99/{track.stat().st_size}"
            assert r.read() == track.read_bytes()[:100]
        with pytest.raises(urllib.error.HTTPError) as e:
            urllib.request.urlopen(f"http://127.0.0.1:{hp}/track/nope.m4a", timeout=3)
        assert e.value.code == 404

        # talk pauses music; closing it resumes with the 1500 ms lead
        client.send("talk")
        client.msg("<<", "talk.open")
        st = client.msg("<<", "state")
        assert st["talk"] is True and st["music"]["playing"] is False
        client.send("talk")
        client.msg("<<", "talk.close")
        resume = client.msg("<<", "music.play")
        assert resume["positionMs"] == st["music"]["positionMs"]
        m = client.expect(r"music: play \S+ from \d+ ms at host \d+ = local [\d.]+ \(in (\d+) ms\)")
        assert 1300 <= int(m.group(1)) <= 1500

        # music.control from the client
        client.send("pause")
        pause = client.msg("<<", "music.pause")
        assert pause["id"] == load["id"]

        client.send("quit")
        assert client.msg(">>", "bye") == {"t": "bye", "reason": "user"}
        host.msg("<<", "bye")
        client.p.wait(5)
    finally:
        client.stop()
        host.stop()


def test_silence_does_not_close_talk(tmp_path):
    # no --tone and no audio: the client sends only keepalives. Talk ends on a trigger only.
    host, client, _ = start_pair(tmp_path, None)
    try:
        client.msg("<<", "hello")
        client.send("talk")
        client.msg("<<", "talk.open")
        with pytest.raises(AssertionError):
            client.msg("<<", "talk.close", timeout=3)
        client.send("talk")
        close = client.msg("<<", "talk.close")
        assert close == {"t": "talk.close", "by": "client", "reason": "trigger"}
    finally:
        client.stop()
        host.stop()


def test_second_client_replaces_first(tmp_path):
    host, client, (cp, _, _) = start_pair(tmp_path, None)
    try:
        client.msg("<<", "hello")
        other = Proc("client", "--host", "127.0.0.1", "--port", str(cp), "--no-audio", "--name", "Second",
                     "--cache-dir", str(tmp_path / "c2"))
        try:
            other.msg("<<", "hello")
            assert client.msg("<<", "bye") == {"t": "bye", "reason": "replaced"}
            host.expect("client 'Second' at 127.0.0.1 is now the client")
        finally:
            other.p.kill()
            other.p.wait()
    finally:
        client.p.kill()
        client.p.wait()
        host.stop()


def test_volume_utterance_reaching_the_host_is_not_understood(tmp_path):
    """A client that still sends a volume utterance gets "Didn't catch that" (PROTOCOL.md)."""
    host, client, _ = start_pair(tmp_path, None)
    try:
        client.msg("<<", "hello")
        _open_talk(client)
        client.send('raw {"t":"command.text","text":"volume up","lang":"en-US"}')
        assert client.msg("<<", "announce") == {"t": "announce", "text": "Didn't catch that", "earcon": "error"}
        host.expect("volume is local; the client should not have sent this")
    finally:
        client.stop()
        host.stop()


def test_malformed_messages_are_dropped_and_the_link_survives(tmp_path):
    """Every fixtures/control/framing.json "malformed" vector: drop, keep the connection.

    `raw` is the way to send a now-invalid message (e.g. music.control volumeUp) on purpose.
    """
    host, client, _ = start_pair(tmp_path, None)
    try:
        client.msg("<<", "hello")
        for case in load_fixture("control/framing.json")["malformed"]:
            client.send("raw " + case["json"])
            host.expect(r"dropped invalid frame ")
        client.send("talk")  # the connection is still up and still answers
        assert client.msg("<<", "talk.open") == {"t": "talk.open", "by": "client"}
        assert "closing" not in host.output()
    finally:
        client.stop()
        host.stop()


def test_host_refuses_talk_when_its_mic_is_unavailable(tmp_path):
    host, client, _ = start_pair(tmp_path, None)
    try:
        client.msg("<<", "hello")
        host.send("mic off")
        host.expect("mic UNAVAILABLE")
        client.send("talk")
        assert client.msg("<<", "talk.close") == {"t": "talk.close", "by": "host", "reason": "unavailable"}
        client.expect(r"TALK REFUSED by host: microphone unavailable")
        assert not any("TALK OPEN" in line for line in client.lines)
        # talk never opened: state.talk stays false
        host.send("state")
        assert client.msg("<<", "state")["talk"] is False
        # mic back on: the same request now opens talk
        host.send("mic on")
        host.expect("mic available again")
        client.send("talk")
        assert client.msg("<<", "talk.open") == {"t": "talk.open", "by": "client"}
        assert client.msg("<<", "state")["talk"] is True
    finally:
        client.stop()
        host.stop()


def test_client_refuses_talk_when_its_mic_is_unavailable(tmp_path):
    host, client, _ = start_pair(tmp_path, None, "--mic-unavailable")
    try:
        client.msg("<<", "hello")
        host.send("talk")  # host-triggered talk; the client cannot open its mic
        assert client.msg("<<", "talk.open") == {"t": "talk.open", "by": "host"}
        client.expect("MIC UNAVAILABLE: refusing talk")
        assert client.msg(">>", "talk.close") == {"t": "talk.close", "by": "client", "reason": "unavailable"}
        # the host treats it as a close request and broadcasts the close
        assert client.msg("<<", "talk.close") == {"t": "talk.close", "by": "client", "reason": "unavailable"}
        assert client.msg("<<", "state")["talk"] is False
        assert not any("TALK OPEN" in line for line in client.lines)
        # the stdin toggle puts the mic back
        client.send("unavailable")
        client.expect("mic available again")
        host.send("talk")
        client.msg("<<", "talk.open")
        client.expect("TALK OPEN")
    finally:
        client.stop()
        host.stop()


def test_host_rejects_oversize_frame_and_survives(tmp_path):
    host, client, (cp, _, _) = start_pair(tmp_path, None)
    try:
        client.msg("<<", "hello")
        with socket.create_connection(("127.0.0.1", cp), timeout=2) as s:
            s.sendall(bytes.fromhex("00010001"))  # fixtures/control/framing.json invalid vector
            s.settimeout(2)
            got = b""
            while True:  # host sends hello + state, then must close on the bad length
                chunk = s.recv(4096)
                if not chunk:
                    break
                got += chunk
        host.expect(r"protocol error: frame length 65537 exceeds 65536; closing")
        client.send("talk")  # the real client is unaffected
        client.msg("<<", "talk.open")
    finally:
        client.stop()
        host.stop()


def test_spoken_phrases_in_a_talk(tmp_path):
    """PROTOCOL.md "Commands": the first phrase rule on the client, `end` and `pause` on the host."""
    host, client, _ = start_pair(tmp_path, None)
    try:
        client.msg("<<", "hello")
        client.send("hear pause")  # outside a talk: nothing is sent
        client.expect("hear: no talk open")
        _open_talk(client)

        # empty phrases do not spend the first phrase; the first real one parses: a command
        client.send("hear ...")
        client.expect(r"hear: '\.\.\.' is conversation \(empty\), not sent")
        client.send("hear Pause!")
        assert client.msg(">>", "command.text") == {"t": "command.text", "text": "pause", "lang": "en-US"}
        assert client.msg("<<", "announce")["text"] == "Nothing is playing"
        # every later phrase is conversation, even one that parses
        client.send("hear over")
        client.expect(r"hear: 'over' is conversation \(the first phrase is spent\), not sent")
        # a second command.text in the same talk (a misbehaving client) is ignored, no announce
        client.send("say over")
        host.expect(r"command.text ignored: not the first command.text of this talk")
        with pytest.raises(AssertionError):
            client.expect(r'<< \{"t":"(announce|talk\.close)"', timeout=1)
        client.send("talk")
        client.msg("<<", "talk.close")

        # an unparsed first phrase is conversation (no "Didn't catch that") and spends it
        _open_talk(client)
        client.send("hear what a view")
        client.expect(r"hear: 'what a view' is conversation \(the first phrase does not parse\), not sent")
        client.send("hear pause")
        client.expect(r"hear: 'pause' is conversation \(the first phrase is spent\), not sent")
        client.send("talk")
        client.msg("<<", "talk.close")

        # the first phrase may be a local volume command: handled here, nothing sent
        _open_talk(client)
        client.send("hear louder")
        client.expect(r"local: volumeUp handled here \[earcon ok\]")
        client.send("talk")
        client.msg("<<", "talk.close")

        # `over` closes the talk, by the client that spoke it, and nothing else happens
        _open_talk(client)
        client.send("hear Hey, over")
        assert client.msg(">>", "command.text")["text"] == "hey over"
        assert client.msg("<<", "talk.close") == {"t": "talk.close", "by": "client", "reason": "trigger"}
        assert client.msg("<<", "state")["talk"] is False
        with pytest.raises(AssertionError):
            client.expect(r'<< \{"t":"(music\.play|announce)"', timeout=1)
        client.send("hear over")
        client.expect("hear: no talk open")
        assert sum('"command.text"' in line for line in client.lines if ">>" in line) == 3
    finally:
        client.stop()
        host.stop()


def test_host_opened_talk(tmp_path):
    """A talk the host opened: the client never commands, the host's own first phrase does."""
    host, client, _ = start_pair(tmp_path, None)
    try:
        client.msg("<<", "hello")
        host.send("talk")
        assert client.msg("<<", "talk.open") == {"t": "talk.open", "by": "host"}
        client.send("hear pause")
        client.expect(r"hear: 'pause' is conversation \(this phone did not open the talk\), not sent")
        client.send("say pause")  # the host enforces it too
        host.expect(r"command.text ignored: the client did not open this talk")

        host.send("hear")
        host.expect("usage: hear <phrase>")
        host.send("hear Pause.")
        host.expect(r"hear: command 'pause'")
        assert client.msg("<<", "announce") == {"t": "announce", "text": "Nothing is playing", "earcon": "error"}
        host.send("hear over")
        host.expect(r"hear: 'over' is conversation \(the first phrase is spent\), not acted on")
        host.send("talk")
        assert client.msg("<<", "talk.close") == {"t": "talk.close", "by": "host", "reason": "trigger"}

        # the host's first phrase `over` ends the talk by the host
        host.send("talk")
        client.msg("<<", "talk.open")
        host.send("hear over")
        assert client.msg("<<", "talk.close") == {"t": "talk.close", "by": "host", "reason": "trigger"}
        with pytest.raises(AssertionError):  # `end` has no announce
            client.msg("<<", "announce", timeout=1)
        host.send("hear over")
        host.expect("hear: no talk open")
    finally:
        client.stop()
        host.stop()


def test_solo_talk_on_the_host(tmp_path):
    """No client: every non-empty host phrase is a command, unparsed -> "Didn't catch that"."""
    cp, vp, hp = free_port(), free_port(socket.SOCK_DGRAM), free_port()
    host = Proc("host", "--no-mdns", "--bind", "127.0.0.1", "--port", str(cp), "--voice-port", str(vp),
                "--http-port", str(hp))
    try:
        host.expect(r"control tcp/\d+ voice udp/\d+ http tcp/\d+")
        host.send("talk")
        host.expect(r"TALK OPEN \(by host\)")
        host.send("hear what's the weather")
        host.expect(r'\(no client\) would send \{"t":"announce","text":"Didn\'t catch that","earcon":"error"\}')
        host.send("hear ")
        host.expect("usage: hear <phrase>")
        host.send("hear next")
        host.expect(r'would send \{"t":"announce","text":"Nothing queued","earcon":"error"\}')
        host.send("hear quieter")
        host.expect(r"local: volumeDown handled here \[earcon ok\]")
        host.send("hear Over.")
        host.expect(r"TALK CLOSED \(by host, trigger\)")
    finally:
        host.stop()


def _load(host, client) -> dict:
    host.send("load")
    load = client.msg("<<", "music.load")
    client.msg(">>", "music.ready")
    client.msg("<<", "music.play")
    return load


def test_spoken_resume_ends_the_talk_and_music_follows(tmp_path, track):
    if track is None:
        pytest.skip("ffmpeg not available")
    host, client, _ = start_pair(tmp_path, track)
    try:
        client.msg("<<", "hello")
        load = _load(host, client)
        _open_talk(client)
        client.send("hear resume")
        assert client.msg(">>", "command.text")["text"] == "resume"
        # the close comes first, then the resume after the usual lead, then the announce
        assert client.msg("<<", "talk.close") == {"t": "talk.close", "by": "client", "reason": "trigger"}
        play = client.msg("<<", "music.play")
        assert play["id"] == load["id"]
        m = client.expect(r"music: play \S+ from \d+ ms at host \d+ = local [\d.]+ \(in (\d+) ms\)")
        assert 1300 <= int(m.group(1)) <= 1510  # the clock estimate may be a millisecond off
        assert client.msg("<<", "state")["talk"] is False
        assert client.msg("<<", "announce") == {"t": "announce", "text": "Resuming", "earcon": "ok"}
    finally:
        client.stop()
        host.stop()


def test_spoken_play_and_pause_in_a_talk(tmp_path, track):
    if track is None:
        pytest.skip("ffmpeg not available")
    host, client, _ = start_pair(tmp_path, track)
    try:
        client.msg("<<", "hello")
        _load(host, client)

        # `pause` in the talk cancels the resume after it; the talk stays open
        _open_talk(client)
        client.send("hear pause")
        assert client.msg("<<", "announce") == {"t": "announce", "text": "Paused", "earcon": "ok"}
        client.send("talk")
        client.msg("<<", "talk.close")
        assert client.msg("<<", "state")["music"]["playing"] is False
        with pytest.raises(AssertionError):
            client.msg("<<", "music.play", timeout=1)

        # `play …` ends the talk; the track starts once the headset is back in media mode
        _open_talk(client)
        client.send("hear Play Bench Tone")
        assert client.msg(">>", "command.text")["text"] == "play bench tone"
        assert client.msg("<<", "talk.close") == {"t": "talk.close", "by": "client", "reason": "trigger"}
        assert client.msg("<<", "state")["talk"] is False
        assert client.msg("<<", "announce")["text"] == "Playing Bench Tone by Peer"
        client.msg("<<", "music.load")
        client.msg("<<", "music.play")
        m = client.expect(r"music: play \S+ from 0 ms at host \d+ = local [\d.]+ \(in (\d+) ms\)")
        assert int(m.group(1)) >= 1000  # the resume lead, not the 300 ms play lead
    finally:
        client.stop()
        host.stop()


# ---------------------------------------------------------------------- host-mic talk


def test_host_mic_talk_client_is_receive_only(tmp_path):
    """PROTOCOL.md "Host-mic talk": the host's decision carries mic:"host"; the client opens no
    mic, sends keepalives only and no command.text; the host recognises the passenger's channel."""
    host, client, _ = start_pair(tmp_path, None, "--tone", host_extra=("--host-mic",))
    try:
        client.msg("<<", "hello")
        client.send("talk")
        assert client.msg(">>", "talk.open") == {"t": "talk.open", "by": "client"}  # a request never has mic
        assert client.msg("<<", "talk.open") == {"t": "talk.open", "by": "client", "mic": "host"}
        client.expect(r"talk mode: host-mic \(receive only\)")
        host.expect(r"TALK OPEN \(by client, host-mic\)")
        assert client.msg("<<", "state") == {"t": "state", "talk": True, "queue": [], "mic": "host"}
        # --tone would send audio in a normal talk: here only keepalives go out
        m = client.expect(r"\| tx: audio=(\d+) dtx_skipped=\d+ keepalive=(\d+)", timeout=5)
        m = client.expect(r"\| tx: audio=(\d+) dtx_skipped=\d+ keepalive=(\d+)", timeout=5)
        assert int(m.group(1)) == 0 and int(m.group(2)) >= 1, m.group(0)
        hs = host.expect(r"voice: audio=(\d+) .*keepalive=(\d+) echoed=(\d+) dropped=(\d+)", timeout=5)
        assert int(hs.group(1)) == 0 and int(hs.group(2)) >= 1 and int(hs.group(3)) == 0
        # the client never recognises in a host-mic talk: hear sends nothing
        client.send("hear pause")
        client.expect(r"hear: 'pause' skipped: host-mic talk")
        # a misbehaving client's command.text is ignored by the host
        client.send("say pause")
        host.expect(r"command.text ignored: host-mic talk")
        with pytest.raises(AssertionError):
            client.expect(r'<< \{"t":"announce"', timeout=1)
        # the host's ASR on the passenger's channel: first phrase acts as the client's command
        host.send("hear louder")
        host.expect(r"hear: volumeUp on the passenger's channel ignored, no announce")
        host.send("hear pause")  # the first phrase was spent by `louder`
        host.expect(r"hear: 'pause' is conversation \(the first phrase is spent\)")
        client.send("talk")
        assert client.msg("<<", "talk.close") == {"t": "talk.close", "by": "client", "reason": "trigger"}
        host.expect(r"host-mic talk: dropped 0 client audio packets")
        client.expect("TALK CLOSED")
        assert client.msg("<<", "state") == {"t": "state", "talk": False, "queue": []}  # no mic

        # a passenger `over` ends the talk by the client, as its command.text would
        client.send("talk")
        client.msg("<<", "talk.open")
        host.send("hear Over.")
        host.expect(r"hear: command 'over' \(passenger's channel, as the client's command\)")
        assert client.msg("<<", "talk.close") == {"t": "talk.close", "by": "client", "reason": "trigger"}
        assert sum('"command.text"' in line for line in client.lines if ">>" in line) == 1  # the `say`
    finally:
        client.stop()
        host.stop()


def test_host_drops_client_audio_in_a_host_mic_talk(tmp_path):
    """PROTOCOL.md "Voice": a host that still receives client audio in a host-mic talk drops it."""
    from motoparty_peer.protocol import KIND_AUDIO, VoicePacket

    host, client, (_, vp, _) = start_pair(tmp_path, None, host_extra=("--host-mic",))
    try:
        client.msg("<<", "hello")
        host.expect("is now the client")
        _open_talk(client)
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            for i in range(5):
                s.sendto(VoicePacket(KIND_AUDIO, i, i * 320, b"\xf8\xff\xfe").encode(), ("127.0.0.1", vp))
        host.expect("voice: client audio in a host-mic talk; dropping it")
        hs = host.expect(r"voice: audio=5 .*echoed=0 dropped=5", timeout=5)
        assert hs
        client.send("talk")
        client.msg("<<", "talk.close")
        host.expect(r"host-mic talk: dropped 5 client audio packets")
    finally:
        client.stop()
        host.stop()


def test_hostmic_toggle_and_a_client_without_a_mic(tmp_path):
    """`hostmic on|off` decides per talk; a client whose mic is unavailable still joins a
    host-mic talk (it needs none) and refuses a normal one."""
    host, client, _ = start_pair(tmp_path, None, "--mic-unavailable")
    try:
        client.msg("<<", "hello")
        host.expect("is now the client")
        host.send("hostmic on")
        host.expect("host-mic ON")
        host.send("talk")
        assert client.msg("<<", "talk.open") == {"t": "talk.open", "by": "host", "mic": "host"}
        client.expect(r"a host-mic talk needs none: accepting")
        client.expect(r"talk mode: host-mic \(receive only\)")
        assert client.msg("<<", "state")["talk"] is True
        host.send("hostmic off")
        host.expect(r"host-mic OFF: .*\(the open talk keeps its mode\)")
        host.send("hear pause")  # host opened it: its own first phrase, as in any talk
        host.expect(r"hear: command 'pause'$")
        host.send("talk")
        assert client.msg("<<", "talk.close") == {"t": "talk.close", "by": "host", "reason": "trigger"}
        host.expect(r"host-mic talk: dropped 0 client audio packets")
        # the next talk opens without mic: the client refuses it as before
        host.send("talk")
        assert client.msg("<<", "talk.open") == {"t": "talk.open", "by": "host"}
        assert client.msg(">>", "talk.close") == {"t": "talk.close", "by": "client", "reason": "unavailable"}
        host.send("hostmic maybe")
        host.expect("usage: hostmic on|off")
    finally:
        client.stop()
        host.stop()


def test_client_joining_a_host_mic_talk_learns_it_from_state(tmp_path):
    """PROTOCOL.md `state`: a client that joins mid-talk sees only state; its mic:"host" makes
    it open the talk receive-only, as the talk.open would have."""
    cp, vp, hp = free_port(), free_port(socket.SOCK_DGRAM), free_port()
    host = Proc("host", "--no-mdns", "--bind", "127.0.0.1", "--port", str(cp), "--voice-port", str(vp),
                "--http-port", str(hp), "--name", "Test Host", "--host-mic")
    client = None
    try:
        host.expect(r"control tcp/\d+ voice udp/\d+ http tcp/\d+")
        host.send("talk")  # solo host-mic talk, before any client
        host.expect(r"TALK OPEN \(by host, host-mic\)")
        client = Proc("client", "--host", "127.0.0.1", "--port", str(cp), "--no-audio", "--name", "Late",
                      "--cache-dir", str(tmp_path / "cache"), "--tone")
        client.msg("<<", "hello")
        assert client.msg("<<", "state") == {"t": "state", "talk": True, "queue": [], "mic": "host"}
        client.expect(r"talk mode: host-mic \(receive only\)")
        m = client.expect(r"\| tx: audio=(\d+) dtx_skipped=\d+ keepalive=(\d+)", timeout=5)
        assert int(m.group(1)) == 0, m.group(0)
        client.send("hear pause")
        client.expect(r"hear: 'pause' skipped: host-mic talk")
        host.send("talk")
        assert client.msg("<<", "talk.close") == {"t": "talk.close", "by": "host", "reason": "trigger"}
        client.expect("TALK CLOSED")

        # without mic, a late joiner opens a normal talk from state (sends its tone), as before
        host.send("hostmic off")
        host.expect("host-mic OFF")
        client.stop()
        host.send("talk")  # solo normal talk
        host.expect(r"TALK OPEN \(by host\)")
        client = Proc("client", "--host", "127.0.0.1", "--port", str(cp), "--no-audio", "--name", "Late 2",
                      "--cache-dir", str(tmp_path / "cache"), "--tone")
        client.msg("<<", "hello")
        assert client.msg("<<", "state") == {"t": "state", "talk": True, "queue": []}
        client.expect("TALK OPEN - sending 440 Hz tone")
    finally:
        if client:
            client.stop()
        host.stop()
