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


def start_pair(tmp_path, track, *client_args, silence_ms=10_000):
    cp, vp, hp = free_port(), free_port(socket.SOCK_DGRAM), free_port()
    host_args = ["host", "--no-mdns", "--bind", "127.0.0.1", "--port", str(cp), "--voice-port", str(vp),
                 "--http-port", str(hp), "--name", "Test Host", "--silence-ms", str(silence_ms)]
    if track:
        host_args += ["--track", str(track)]
    host = Proc(*host_args)
    host.expect(r"control tcp/\d+ voice udp/\d+ http tcp/\d+")
    client = Proc("client", "--host", "127.0.0.1", "--port", str(cp), "--no-audio", "--name", "Test Client",
                  "--cache-dir", str(tmp_path / "cache"), *client_args)
    return host, client, (cp, vp, hp)


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

        # command.text -> parser -> announce
        client.send("say what's the weather")
        assert client.msg("<<", "announce") == {"t": "announce", "text": "Didn't catch that", "earcon": "error"}
        # volume is local: the client parses first and sends nothing
        client.send("say Louder!")
        client.expect(r"local: volumeUp handled here \[earcon ok\]")
        client.send("vol+")
        client.expect(r"local: volumeUp handled here \[earcon ok\]")
        assert sum('"command.text"' in line for line in client.lines) == 1  # only the weather one

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


def test_silence_closes_talk(tmp_path):
    # no --tone and no audio: the client sends only keepalives, so the host must close talk
    host, client, _ = start_pair(tmp_path, None, silence_ms=1500)
    try:
        client.msg("<<", "hello")
        client.send("talk")
        client.msg("<<", "talk.open")
        close = client.msg("<<", "talk.close", timeout=4)
        assert close == {"t": "talk.close", "by": "host", "reason": "silence"}
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
        client.send("say pause")  # the connection is still up and still answers
        assert client.msg("<<", "announce")["earcon"] == "error"  # nothing is playing
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
        client.send("say pause")  # the real client is unaffected
        client.msg("<<", "announce")
    finally:
        client.stop()
        host.stop()
