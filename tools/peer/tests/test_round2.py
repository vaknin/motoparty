"""Round 2 (2026-09-30): hello.proto / bye proto, gapless (music.next), parking at the end of
the queue, the second music.load after a music.error, Talk flow 1 and Discovery 5."""

from __future__ import annotations

import asyncio
import json
import shutil
import socket
import subprocess
import threading
from pathlib import Path
from types import SimpleNamespace

import pytest

from motoparty_peer import client as client_mod
from motoparty_peer import discovery
from motoparty_peer import host as host_mod
from motoparty_peer.cli import build_parser
from motoparty_peer.client import Client
from motoparty_peer.host import Host
from motoparty_peer.music import TrackInfo
from motoparty_peer.protocol import encode_frame, now_ms
from test_integration import Proc, free_port, start_pair

# ---------------------------------------------------------------- fake host (unit, no sockets)


def _track(id_: str, dur: int) -> TrackInfo:
    return TrackInfo(id_, Path(f"/nonexistent/{id_}.m4a"), id_.upper(), "Band", None, dur)


def _host(monkeypatch, *tracks: TrackInfo) -> Host:
    monkeypatch.setattr(host_mod, "GAPLESS_MIN_NOTICE_MS", 0)
    h = Host(SimpleNamespace(name="t", track=None, verbose=False))
    h.library = list(tracks)
    h.by_id = {t.id: t for t in tracks}
    h.track = tracks[0]
    h.loop = asyncio.get_running_loop()
    h.current = SimpleNamespace(name="c", ip="127.0.0.1")
    h.sent = []
    h.send = lambda msg, conn=None, **kw: h.sent.append(msg)
    return h


def _types(h: Host) -> list[str]:
    return [m["t"] for m in h.sent]


async def _msg(h: Host, msg: dict) -> None:
    await h._on_message(h.current, msg, now_ms())


def _playing(h: Host, lead: int = 20) -> int:
    """Start the current track at `lead` ms from now, as _load_and_play ends; returns the anchor time."""
    at = now_ms() + lead
    h._play_from(0, at)
    h.send_state()
    return at


def test_host_announces_the_next_track_and_changes_without_a_gap(monkeypatch):
    async def go():
        a, b, c = _track("a", 300), _track("b", 5000), _track("c", 5000)
        h = _host(monkeypatch, a, b, c)
        h.queue = [b, c]
        at = _playing(h)
        # Music flow 5: the next queue item is prefetched as soon as the current one starts
        assert _types(h) == ["music.play", "state", "music.load"] and h.sent[-1]["id"] == "b"
        await _msg(h, {"t": "music.ready", "id": "b"})
        assert h.sent[-1] == {"t": "music.next", "id": "b", "atHostTimeMs": at + 300}
        h.send_state()  # e.g. a queue edit: not announced twice
        assert _types(h).count("music.next") == 1
        del h.sent[:]
        await asyncio.sleep(0.45)
        # at the change: state, then music.play on the announced anchor, then the following load
        assert _types(h) == ["state", "music.play", "music.load"]
        assert h.sent[0]["music"]["id"] == "b" and h.sent[0]["music"]["positionMs"] == 0
        assert h.sent[0]["music"]["atHostTimeMs"] == at + 300 and h.sent[0]["music"]["playing"] is True
        assert h.sent[0]["queue"] == [{"id": "c", "title": "C", "artist": "Band"}]
        assert h.sent[1] == {"t": "music.play", "id": "b", "positionMs": 0, "atHostTimeMs": at + 300}
        assert h.sent[2]["id"] == "c"
        assert h.track is b and h.history == [a] and h.next_sent is None
        h.end_timer.cancel()

    asyncio.run(go())


def test_host_cancels_and_re_sends_music_next(monkeypatch):
    async def go():
        a, b, c = _track("a", 60_000), _track("b", 5000), _track("c", 5000)
        h = _host(monkeypatch, a, b, c)
        h.queue = [b, c]
        _playing(h, 0)
        await _msg(h, {"t": "music.ready", "id": "b"})
        await _msg(h, {"t": "music.ready", "id": "c"})
        assert _types(h).count("music.next") == 1

        # pause cancels (the client drops it on music.pause); resume announces it again
        del h.sent[:]
        await asyncio.sleep(0.03)
        assert h._pause() and _types(h) == ["music.pause", "state"] and h.next_sent is None
        del h.sent[:]
        assert h._resume()
        play, state, nxt = h.sent
        assert (play["t"], state["t"], nxt["t"]) == ("music.play", "state", "music.next")
        assert nxt["atHostTimeMs"] == play["atHostTimeMs"] + 60_000 - play["positionMs"]

        # a talk cancels; after it closes: music.play, then music.next again
        del h.sent[:]
        h._open_talk("host")
        assert "music.next" not in _types(h) and h.next_sent is None and h.end_timer is None
        del h.sent[:]
        h._close_talk("host", "trigger")
        assert _types(h) == ["talk.close", "music.play", "state", "music.next"]
        assert h.sent[-1]["atHostTimeMs"] == h.sent[1]["atHostTimeMs"] + 60_000 - h.sent[1]["positionMs"]

        # a queue edit that changes the next track: a music.next naming the other one
        del h.sent[:]
        h._edit({"t": "music.edit", "op": "remove", "index": 0, "id": "b"})
        assert _types(h) == ["state", "music.load", "music.next"]
        assert h.sent[1]["id"] == "c" and h.sent[2]["id"] == "c"

        # nothing left to follow: taken back with the current track's music.play, anchor unchanged
        del h.sent[:]
        anchor = (h.music["positionMs"], h.music["atHostTimeMs"])
        h._edit({"t": "music.edit", "op": "clear"})
        assert _types(h) == ["state", "music.play"]
        assert (h.sent[1]["id"], h.sent[1]["positionMs"], h.sent[1]["atHostTimeMs"]) == ("a", *anchor)
        assert h.next_sent is None
        h.end_timer.cancel()

    asyncio.run(go())


def test_host_skip_takes_the_announcement_back(monkeypatch):
    async def go():
        a, b, c = _track("a", 60_000), _track("b", 5000), _track("c", 5000)
        h = _host(monkeypatch, a, b, c)
        h.queue = [b, c]
        _playing(h, 0)
        await _msg(h, {"t": "music.ready", "id": "b"})
        del h.sent[:]
        h._music_control("next")  # b is loaded the usual way; a keeps playing until its music.play
        assert _types(h)[:3] == ["state", "music.load", "music.play"]
        assert h.sent[2]["id"] == "a" and h.next_sent is None and h.end_timer is None
        await asyncio.sleep(0)
        h.load_task.cancel()

    asyncio.run(go())


def test_host_without_a_ready_next_track_changes_with_a_gap(monkeypatch):
    async def go():
        a, b = _track("a", 100), _track("b", 5000)
        h = _host(monkeypatch, a, b)
        h.queue = [b]
        _playing(h, 0)
        await asyncio.sleep(0.2)  # b never became ready: no music.next, the usual load
        assert "music.next" not in _types(h)
        assert h.track is b and h._loading() and h.sent[-1]["t"] == "music.load" and h.sent[-1]["id"] == "b"
        h.load_task.cancel()

    asyncio.run(go())


def test_host_parks_the_last_track_instead_of_stopping(monkeypatch):
    async def go():
        a = _track("a", 100)
        h = _host(monkeypatch, a)
        _playing(h, 0)
        del h.sent[:]
        await asyncio.sleep(0.2)
        assert h.sent[0] == {"t": "music.pause", "id": "a", "positionMs": 0}
        m = h.sent[1]["music"]
        assert (m["id"], m["playing"], m["positionMs"]) == ("a", False, 0)
        assert "music.stop" not in _types(h) and h.end_timer is None
        del h.sent[:]
        assert h._resume() and h.sent[0]["positionMs"] == 0  # play again starts it from 0
        h.end_timer.cancel()

    asyncio.run(go())


def test_host_sends_music_load_again_after_a_first_error(monkeypatch):
    async def go(second: str):
        monkeypatch.setattr(host_mod, "ERROR_RELOAD_MS", 60)
        h = _host(monkeypatch, _track("a", 60_000))
        h._start(h.track)
        await asyncio.sleep(0.01)
        assert _types(h) == ["music.load"]
        await _msg(h, {"t": "music.error", "id": "a", "message": "HTTP 404"})
        await asyncio.sleep(0.03)
        assert _types(h) == ["music.load"]  # not before 2 s (60 ms here)
        await asyncio.sleep(0.06)
        assert _types(h) == ["music.load", "music.load"] and h.sent[0] == h.sent[1]
        await _msg(h, {"t": second, "id": "a", **({"message": "HTTP 404"} if second == "music.error" else {})})
        await asyncio.sleep(0.01)
        # ready: plays together; a second error: the host plays alone. music.play either way.
        assert _types(h) == ["music.load", "music.load", "music.play", "state"]
        h.end_timer.cancel()

    asyncio.run(go("music.ready"))
    asyncio.run(go("music.error"))


def test_host_does_not_reload_a_file_that_is_not_decodable(monkeypatch):
    async def go():
        h = _host(monkeypatch, _track("a", 60_000))
        h._start(h.track)
        await asyncio.sleep(0.01)
        await _msg(h, {"t": "music.error", "id": "a", "message": "not decodable: x"})
        await asyncio.sleep(0.01)
        assert _types(h) == ["music.load", "music.play", "state"]
        h.end_timer.cancel()

    asyncio.run(go())


# ---------------------------------------------------------------- peer client (unit)


class _Writer:
    def __init__(self) -> None:
        self.frames: list[dict] = []

    def write(self, data: bytes) -> None:
        self.frames.append(json.loads(data[4:]))

    def close(self) -> None:
        pass


def _client(tmp_path, monkeypatch, *argv) -> tuple[Client, _Writer, list[str]]:
    args = build_parser().parse_args(["client", "--no-audio", "--cache-dir", str(tmp_path), *argv])
    c = Client(args)
    logs: list[str] = []
    monkeypatch.setattr(client_mod, "log", lambda *a: logs.append(" ".join(map(str, a))))
    w = _Writer()
    c.loop = asyncio.get_running_loop()
    c.conn = SimpleNamespace(writer=w, ip="127.0.0.1", port=47800)
    c.cached = {"a": tmp_path / "a.m4a", "b": tmp_path / "b.m4a", "c": tmp_path / "c.m4a"}
    return c, w, logs


def _has(logs: list[str], text: str) -> bool:
    return any(text in line for line in logs)


def test_client_opens_an_artist_page_and_its_albums(tmp_path, monkeypatch):
    """PROTOCOL.md "Browsing" 2a (2026-10-02): `browse <n>` of an artist sends kind artist; the
    reply's songs are song results and its albums open with `browse a<n>`."""
    async def go():
        c, w, logs = _client(tmp_path, monkeypatch)
        c._browse_cmd("search", "artists floyd")
        assert w.frames[-1] == {"t": "music.search", "id": c.req_id, "kind": "artists", "query": "floyd"}
        await c._handle({"t": "music.results", "id": c.req_id, "items": [
            {"ref": "UCpf", "title": "Pink Floyd", "artist": ""}]})
        c._browse_cmd("browse", "1")
        assert w.frames[-1] == {"t": "music.browse", "id": c.req_id, "ref": "UCpf", "kind": "artist"}
        await c._handle({"t": "music.results", "id": c.req_id,
                         "items": [{"ref": "s1", "title": "Time", "artist": "Pink Floyd", "durationMs": 1000}],
                         "albums": [{"ref": "PL1", "title": "Meddle", "artist": "Pink Floyd", "count": 6}]})
        assert _has(logs, "a1. Meddle - Pink Floyd (6 tracks)")
        c._download_cmd("")  # an artist page is not a collection
        assert _has(logs, "usage: download")
        c._browse_cmd("enqueue", "now 1")
        assert w.frames[-1]["t"] == "music.enqueue" and w.frames[-1]["tracks"][0]["id"] == "s1"
        assert "album" not in w.frames[-1]["tracks"][0]
        c._browse_cmd("browse", "a2")
        assert _has(logs, "no album 'a2'")
        c._browse_cmd("browse", "a1")
        assert w.frames[-1] == {"t": "music.browse", "id": c.req_id, "ref": "PL1"}
        assert c.req_album == "Meddle" and c.browsed_ref == "PL1"

    asyncio.run(go())


def test_client_downloads_the_browsed_collection_and_shows_the_marks(tmp_path, monkeypatch):
    """PROTOCOL.md "Browsing" 6: `download` after `browse <n>` sends the songs' refs; `music.downloads`
    and state.busy are shown."""
    async def go():
        c, w, logs = _client(tmp_path, monkeypatch)
        c._download_cmd("")
        assert w.frames == [] and _has(logs, "usage: download")
        c.req_kind = "albums"
        c.results = [{"ref": "PL1", "title": "Album", "artist": "Band", "count": 2}]
        c._browse_cmd("browse", "1")
        await c._handle({"t": "music.results", "id": c.req_id, "items": [
            {"ref": "a1", "title": "One", "artist": "Band"}, {"ref": "a2", "title": "Two", "artist": "Band"}]})
        c._download_cmd("")
        c._download_cmd("stop")
        assert w.frames[1:] == [{"t": "music.download", "op": "start", "ref": "PL1", "ids": ["a1", "a2"]},
                                {"t": "music.download", "op": "stop", "ref": "PL1"}]
        await c._handle({"t": "music.downloads", "cached": ["a2", "zz"],
                         "downloads": [{"ref": "PL1", "done": 1, "total": 2, "failed": 0, "running": True}]})
        assert c.host_cached == {"a2", "zz"}
        assert _has(logs, "PL1: downloading 1/2, 0 failed") and _has(logs, "downloaded here: ['Two']")
        await c._handle({"t": "state", "talk": False, "queue": [], "busy": "Searching song \"x\""})
        assert _has(logs, 'busy: Searching song "x"…')
        await c._handle({"t": "state", "talk": False, "queue": []})
        assert _has(logs, "busy: done")

    asyncio.run(go())


def test_second_trigger_before_the_decision_takes_the_request_back(tmp_path, monkeypatch):
    async def go():
        c, w, logs = _client(tmp_path, monkeypatch)
        c._talk_trigger()
        c._talk_trigger()
        assert w.frames == [{"t": "talk.open", "by": "client"},
                            {"t": "talk.close", "by": "client", "reason": "trigger"}]
        assert c.talk_request is None
        # the host had opened it meanwhile and now closes it: the client follows both
        await c._handle({"t": "talk.open", "by": "client"})
        await c._handle({"t": "talk.close", "by": "client", "reason": "trigger"})
        assert not c.talk and c.talk_request is None

    asyncio.run(go())


def test_unanswered_talk_request_is_dropped(tmp_path, monkeypatch):
    async def go():
        monkeypatch.setattr(client_mod, "TALK_REQUEST_TIMEOUT_MS", 50)
        c, w, logs = _client(tmp_path, monkeypatch)
        c._talk_trigger()
        assert c.talk_request is not None
        await asyncio.sleep(0.1)
        assert c.talk_request is None and _has(logs, "request unanswered for 50 ms, dropped")
        c._talk_trigger()  # a trigger after the drop is a new request, not a close
        assert [f["t"] for f in w.frames] == ["talk.open", "talk.open"]
        await c._handle({"t": "talk.open", "by": "client"})  # the decision ends the wait
        assert c.talk and c.talk_request is None
        c._talk_trigger()
        assert w.frames[-1] == {"t": "talk.close", "by": "client", "reason": "trigger"}

    asyncio.run(go())


def _state(id_: str, pos: int, at: int, playing: bool = True) -> dict:
    return {"t": "state", "talk": False, "queue": [],
            "music": {"id": id_, "title": "t", "artist": "a", "playing": playing, "positionMs": pos,
                      "atHostTimeMs": at, "durationMs": 1000}}


async def _pending(tmp_path, monkeypatch, wait: int = 60_000):
    """A client playing `a` with `b` accepted behind it, changing `wait` ms from now."""
    c, w, logs = _client(tmp_path, monkeypatch)
    at = now_ms()
    await c._handle({"t": "music.play", "id": "a", "positionMs": 0, "atHostTimeMs": at})
    assert c.anchor == ("a", 0, at)
    await c._handle({"t": "music.next", "id": "b", "atHostTimeMs": at + wait})
    assert c.pending_next == ("b", at + wait) and _has(logs, "music.next: ACCEPTED b behind a")
    return c, logs, at, at + wait


def test_client_changes_on_its_clock_and_the_play_of_the_change_is_no_seek(tmp_path, monkeypatch):
    async def go():
        c, logs, at, change = await _pending(tmp_path, monkeypatch, 80)
        await c._handle(_state("a", 0, at))  # the current track on its unchanged anchor: kept
        assert c.pending_next == ("b", change)
        await asyncio.sleep(0.15)
        assert c.pending_next is None and c.anchor == ("b", 0, change) and c.cur_id == "b"
        assert _has(logs, "music.next: CHANGE to b") and _has(logs, "awaiting the host's state and music.play")
        await c._handle(_state("b", 0, change))
        await c._handle({"t": "music.play", "id": "b", "positionMs": 0, "atHostTimeMs": change})
        assert _has(logs, "carries the announced anchor; no seek, no restart")
        assert not _has(logs, "WARNING") and c.changed is None
        # a music.next after the change names the track after it
        await c._handle({"t": "music.next", "id": "c", "atHostTimeMs": change + 60_000})
        assert c.pending_next == ("c", change + 60_000)
        c._cancel_next("test end")

    asyncio.run(go())


def test_client_warns_when_the_play_of_the_change_is_on_another_anchor(tmp_path, monkeypatch):
    async def go():
        c, logs, at, change = await _pending(tmp_path, monkeypatch, 30)
        await asyncio.sleep(0.08)
        await c._handle({"t": "music.play", "id": "b", "positionMs": 0, "atHostTimeMs": change + 250})
        assert _has(logs, "WARNING the music.play after the change does not carry the announced anchor")
        assert c.anchor == ("b", 0, change + 250)

    asyncio.run(go())


def test_client_warns_when_no_play_follows_its_change(tmp_path, monkeypatch):
    async def go():
        monkeypatch.setattr(client_mod, "CHANGE_CONFIRM_MS", 40)
        c, logs, at, change = await _pending(tmp_path, monkeypatch, 20)
        await asyncio.sleep(0.12)
        assert _has(logs, "WARNING no music.play for the change to b")

    asyncio.run(go())


def test_play_of_the_change_before_the_clients_own_clock(tmp_path, monkeypatch):
    async def go():
        c, logs, at, change = await _pending(tmp_path, monkeypatch)
        await c._handle(_state("b", 0, change))
        assert c.pending_next == ("b", change) and _has(logs, "state names the pending track")
        await c._handle({"t": "music.play", "id": "b", "positionMs": 0, "atHostTimeMs": change})
        assert c.pending_next is None and c.anchor == ("b", 0, change) and c.changed is None
        assert _has(logs, "CHANGE to b") and not _has(logs, "WARNING")

    asyncio.run(go())


@pytest.mark.parametrize("what", ["repeat", "seek", "pending-off-anchor", "other", "pause", "stop", "talk",
                                  "state-none", "state-paused", "state-other", "next-other"])
def test_what_cancels_a_pending_music_next(tmp_path, monkeypatch, what):
    async def go():
        c, logs, at, change = await _pending(tmp_path, monkeypatch)
        msg = {
            "repeat": {"t": "music.play", "id": "a", "positionMs": 0, "atHostTimeMs": at},
            "seek": {"t": "music.play", "id": "a", "positionMs": 500, "atHostTimeMs": at + 300},
            "pending-off-anchor": {"t": "music.play", "id": "b", "positionMs": 0, "atHostTimeMs": change + 5},
            "other": {"t": "music.play", "id": "c", "positionMs": 0, "atHostTimeMs": at + 300},
            "pause": {"t": "music.pause", "id": "a", "positionMs": 10},
            "stop": {"t": "music.stop"},
            "talk": {"t": "talk.open", "by": "host"},
            "state-none": {"t": "state", "talk": False, "queue": []},
            "state-paused": _state("a", 10, at, playing=False),
            "state-other": _state("c", 0, at),
            "next-other": {"t": "music.next", "id": "c", "atHostTimeMs": change},
        }[what]
        await c._handle(msg)
        assert _has(logs, "music.next: pending b CANCELLED by"), logs
        assert c.next_timer is None or what == "next-other"
        if what == "next-other":
            assert c.pending_next == ("c", change)  # replaced, not merely dropped
            c._cancel_next("test end")
        else:
            assert c.pending_next is None
        if what == "repeat":
            assert _has(logs, "taken back; no seek") and c.anchor == ("a", 0, at)
        if what == "pending-off-anchor":
            assert _has(logs, "WARNING music.play for the pending track is not on the announced anchor")
        c._set_talk(False, quiet=True)

    asyncio.run(go())


def test_music_next_is_not_accepted_while_nothing_plays(tmp_path, monkeypatch):
    async def go():
        c, w, logs = _client(tmp_path, monkeypatch)
        await c._handle({"t": "music.next", "id": "b", "atHostTimeMs": now_ms() + 1000})
        assert c.pending_next is None and _has(logs, "announced while nothing is playing here")

    asyncio.run(go())


def test_bye_proto_stops_a_client_given_a_host(tmp_path, monkeypatch):
    async def go():
        c, w, logs = _client(tmp_path, monkeypatch, "--host", "127.0.0.1")
        assert await c._handle({"t": "bye", "reason": "proto"}) == "close"
        assert c.quitting and ("127.0.0.1", 47800) in c.discovery.blocked
        assert _has(logs, "PROTOCOL VERSION MISMATCH")

    asyncio.run(go())


def test_bye_proto_blocks_the_host_for_discovery(tmp_path, monkeypatch):
    async def go():
        c, w, logs = _client(tmp_path, monkeypatch)
        c.discovery.last = ("127.0.0.1", 47800)
        await c._handle({"t": "bye", "reason": "proto"})
        d = c.discovery
        assert not c.quitting and d.last is None and d.backed_off("127.0.0.1", 47800)

    asyncio.run(go())


# ---------------------------------------------------------------- discovery


class _HelloHost:
    """A loopback server that sends a host hello with `proto` and counts connections."""

    def __init__(self, proto: int = 1, name: str = "H") -> None:
        self.proto, self.name, self.accepted = proto, name, 0

    async def start(self) -> "_HelloHost":
        self.server = await asyncio.start_server(self._on, "127.0.0.1", 0)
        self.port = self.server.sockets[0].getsockname()[1]
        return self

    async def _on(self, reader, writer) -> None:
        self.accepted += 1
        writer.write(encode_frame({"t": "hello", "proto": self.proto, "role": "host", "name": self.name,
                                   "voicePort": 1, "httpPort": 2}))
        with _quiet():
            await asyncio.wait_for(reader.read(), 1)
        writer.close()

    async def close(self) -> None:
        self.server.close()
        await self.server.wait_closed()


class _quiet:
    def __enter__(self):
        return self

    def __exit__(self, et, e, tb):
        return et is not None and issubclass(et, (asyncio.TimeoutError, ConnectionError))


def test_last_host_address_is_probed_at_once_and_exempt_from_the_backoff():
    async def go():
        h = await _HelloHost().start()
        d = discovery.Discovery(port=1, mdns=False, sweep_delay=1000.0, candidates=list)
        d.last = ("127.0.0.1", h.port)
        d.mark_failed(*d.last)  # a failed handshake a moment ago does not hold it back
        t0 = asyncio.get_running_loop().time()
        found = await d.find(timeout=3)
        took = asyncio.get_running_loop().time() - t0
        await h.close()
        return found, took

    found, took = asyncio.run(go())
    assert found is not None and found.via == "last" and took < 0.5


def test_last_host_address_is_probed_every_second_until_it_is_back():
    async def go():
        port = free_port()
        d = discovery.Discovery(port=1, mdns=False, sweep_delay=1000.0, candidates=list)
        d.last = ("127.0.0.1", port)
        task = asyncio.create_task(d.find(timeout=5))
        await asyncio.sleep(0.3)  # the first probe was refused: the host is restarting
        server = await asyncio.start_server(
            lambda r, w: w.write(encode_frame({"t": "hello", "proto": 1, "role": "host", "name": "H",
                                               "voicePort": 1, "httpPort": 2})), "127.0.0.1", port)
        found = await task
        server.close()
        return found

    found = asyncio.run(go())
    assert found is not None and found.via == "last"


def test_a_host_with_another_proto_is_probed_once_and_never_again():
    async def go():
        h = await _HelloHost(proto=2).start()
        logs: list[str] = []
        d = discovery.Discovery(port=h.port, mdns=False, sweep_delay=0.0, backoff=0.05,
                                candidates=lambda: ["127.0.0.1"], log=logs.append)
        found = await d.find(timeout=2.5)  # the sweep comes round again every second
        await h.close()
        return found, h.accepted, d, logs

    found, accepted, d, logs = asyncio.run(go())
    assert found is None and accepted == 1 and d.blocked
    assert any("peer speaks proto 2" in line for line in logs)


# ---------------------------------------------------------------- the CLIs


def test_client_cli_stops_on_a_host_with_another_proto(tmp_path):
    srv = socket.socket()
    srv.bind(("127.0.0.1", 0))
    srv.listen()
    srv.settimeout(0.2)
    accepted = []
    done = threading.Event()

    def serve():
        while not done.is_set():
            try:
                s, _ = srv.accept()
            except OSError:
                continue
            accepted.append(s)
            s.sendall(encode_frame({"t": "hello", "proto": 2, "role": "host", "name": "Future",
                                    "voicePort": 1, "httpPort": 2}))

    threading.Thread(target=serve, daemon=True).start()
    client = Proc("client", "--host", "127.0.0.1", "--port", str(srv.getsockname()[1]), "--no-audio",
                  "--cache-dir", str(tmp_path))
    try:
        client.expect(r"PROTOCOL VERSION MISMATCH .*peer speaks proto 2.*not connecting to this host again")
        assert client.p.wait(5) == 0  # it stops instead of reconnecting in a loop
        assert len(accepted) == 1
    finally:
        done.set()
        client.stop()
        srv.close()


def _frame(sock: socket.socket) -> dict | None:
    def exactly(n: int) -> bytes | None:
        buf = b""
        while len(buf) < n:
            chunk = sock.recv(n - len(buf))
            if not chunk:
                return None
            buf += chunk
        return buf

    head = exactly(4)
    return None if head is None else json.loads(exactly(int.from_bytes(head, "big")))


def test_host_answers_another_proto_with_bye_and_keeps_its_client(tmp_path):
    host, client, (cp, _, _) = start_pair(tmp_path, None)
    try:
        client.msg("<<", "hello")
        host.expect("client 'Test Client' at 127.0.0.1 is now the client")
        with socket.create_connection(("127.0.0.1", cp), timeout=5) as s:
            assert _frame(s)["t"] == "hello" and _frame(s)["t"] == "state"
            body = b'{"t":"hello","proto":2,"role":"client","name":"Old phone"}'
            s.sendall(len(body).to_bytes(4, "big") + body)
            assert _frame(s) == {"t": "bye", "reason": "proto"}
            assert _frame(s) is None  # closed
        host.expect("peer speaks proto 2; closing")
        # no session started and the connected client was not replaced: it still gets answers
        client.send("talk")
        assert client.msg("<<", "talk.open") == {"t": "talk.open", "by": "client"}
        out = host.output()
        assert "replaces the previous client" not in out and "Old phone' at" not in out
        assert "client gone" not in out and '"replaced"' not in client.output()
    finally:
        client.stop()
        host.stop()


@pytest.fixture(scope="module")
def two_tracks(tmp_path_factory):
    if not shutil.which("ffmpeg"):
        pytest.skip("ffmpeg not available")
    d = tmp_path_factory.mktemp("tracks")
    for name, freq in (("1-first", 330), ("2-second", 440)):
        subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"sine=frequency={freq}:duration=4",
             "-c:a", "aac", "-b:a", "48k", "-metadata", f"title={name}", "-metadata", "artist=Peer",
             str(d / f"{name}.m4a")], check=True, timeout=30)
    return d


def test_gapless_between_the_two_peers(tmp_path, two_tracks):
    host, client, _ = start_pair(tmp_path, two_tracks)
    try:
        client.msg("<<", "hello")
        client.msg("<<", "state")
        host.expect("is now the client")
        host.send("queue")
        assert [q["title"] for q in client.msg("<<", "state")["queue"]] == ["2-second"]
        host.send("load")
        first = client.msg("<<", "music.load")
        play = client.msg("<<", "music.play")
        second = client.msg("<<", "music.load")  # prefetch of the next queue item
        assert second["title"] == "2-second"
        nxt = client.msg("<<", "music.next")
        assert nxt == {"t": "music.next", "id": second["id"],
                       "atHostTimeMs": play["atHostTimeMs"] + first["durationMs"]}
        client.expect(rf"music\.next: ACCEPTED {second['id']} behind {first['id']}")
        client.expect(rf"music\.next: CHANGE to {second['id']} at host {nxt['atHostTimeMs']}", timeout=8)
        host.expect(rf"gapless change to {second['id']}", timeout=8)
        # the queue is empty after the change: the last track is parked at 0, not stopped
        assert client.msg("<<", "music.pause", timeout=8) == {"t": "music.pause", "id": second["id"], "positionMs": 0}
        st = client.msg("<<", "state")["music"]
        assert (st["id"], st["playing"], st["positionMs"]) == (second["id"], False, 0)
        out = client.output()
        change = {"t": "music.play", "id": second["id"], "positionMs": 0, "atHostTimeMs": nxt["atHostTimeMs"]}
        assert json.dumps(change, separators=(",", ":")) in out
        assert "WARNING" not in out and "CANCELLED" not in out and "music.stop" not in out
        assert "carries the announced anchor" in out or "arrived before our own clock" in out
    finally:
        client.stop()
        host.stop()
