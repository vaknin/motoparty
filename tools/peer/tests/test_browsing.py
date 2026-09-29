"""Fake-host browsing: search/browse over local tracks and the enqueue/edit queue semantics
(PROTOCOL.md "Browsing"). No sockets: Host.send and Host._start are stubbed."""

from pathlib import Path
from types import SimpleNamespace

import pytest

from motoparty_peer.host import MAX_QUEUE, Host
from motoparty_peer.music import TrackInfo, album_ref


def track(id_, title, album=None, artist="Band"):
    return TrackInfo(id_, Path(f"/nonexistent/{id_}.m4a"), title, artist, album, 1000)


LIB = [track("a1", "Alpha", "First"), track("a2", "Beta", "First"), track("b1", "Gamma", "Second"),
       track("c1", "Delta"), track("c2", "Epsilon")]


@pytest.fixture
def host():
    h = Host(SimpleNamespace(name="t", track=None))
    h.library = list(LIB)
    h.by_id = {t.id: t for t in LIB}
    h.sent = []
    h.started = []
    h.send = lambda msg, conn=None, **kw: h.sent.append(msg)

    def start(t):  # what _load_and_play ends with: the track is current and playing
        h.track = t
        h.started.append(t.id)
        h.music = {"id": t.id, "title": t.title, "artist": t.artist, "playing": True,
                   "positionMs": 0, "atHostTimeMs": 0, "durationMs": t.duration_ms}

    h._start = start
    return h


def ids(ts):
    return [t.id for t in ts]


def enq(h, mode, *track_ids, **extra):
    tracks = [{"id": i, "title": "x", "artist": "y", "durationMs": 1} for i in track_ids]
    h._enqueue({"t": "music.enqueue", "mode": mode, "tracks": tracks, **extra})


def last_state(h):
    return [m for m in h.sent if m["t"] == "state"][-1]


def test_search_songs_albums_playlists(host):
    host._browse({"t": "music.search", "id": 1, "kind": "songs", "query": "FIRST"})
    assert [i["ref"] for i in host.sent[-1]["items"]] == ["a1", "a2"]
    host._browse({"t": "music.search", "id": 2, "kind": "songs", "query": ""})
    assert len(host.sent[-1]["items"]) == 5
    host._browse({"t": "music.search", "id": 3, "kind": "albums", "query": "a"})
    res = host.sent[-1]
    assert res["id"] == 3 and [(i["title"], i["count"]) for i in res["items"]] == [("First", 2), ("Second", 1)]
    host._browse({"t": "music.search", "id": 4, "kind": "playlists", "query": ""})
    assert host.sent[-1] == {"t": "music.results", "id": 4, "items": []}


def test_browse(host):
    host._browse({"t": "music.browse", "id": 5, "ref": album_ref("First")})
    assert [i["ref"] for i in host.sent[-1]["items"]] == ["a1", "a2"]
    host._browse({"t": "music.browse", "id": 6, "ref": "nope"})
    assert host.sent[-1]["error"] and host.sent[-1]["items"] == []
    host._browse({"t": "music.browse", "id": 7, "ref": "bad ref!"})
    assert host.sent[-1]["error"] and host.sent[-1]["items"] == []


@pytest.mark.parametrize("mode", ["now", "next", "end"])
def test_enqueue_with_nothing_playing_acts_like_now(host, mode):
    enq(host, mode, "a1", "a2", "b1")
    assert host.started == ["a1"] and ids(host.queue) == ["a2", "b1"]
    assert [q["id"] for q in last_state(host)["queue"]] == ["a2", "b1"]


def test_enqueue_modes_with_a_current_track(host):
    enq(host, "now", "a1", "a2")
    enq(host, "end", "b1")
    assert ids(host.queue) == ["a2", "b1"]
    enq(host, "next", "c1", "c2")
    assert ids(host.queue) == ["c1", "c2", "a2", "b1"] and host.track.id == "a1"
    enq(host, "now", "b1", "c1")  # replaces the queue, starts the first
    assert host.started == ["a1", "b1"] and ids(host.queue) == ["c1"]
    assert ids(host.history) == ["a1"]


def test_enqueue_skips_invalid_ignores_empty_and_caps(host):
    enq(host, "now", "bad id", "zz_unknown", "a2", art="https://x/cover.jpg")
    assert host.started == ["a2"] and host.music is not None
    assert host.track.art == "https://x/cover.jpg"
    n = len(host.sent)
    enq(host, "end", "bad id")
    assert len(host.sent) == n  # nothing valid left: ignored, no state
    enq(host, "end", *["c1"] * 300)
    assert len(host.queue) == MAX_QUEUE


def test_jump_keeps_earlier_tracks_for_previous(host):
    enq(host, "now", "a1", "a2", "b1", "c1")
    host._edit({"t": "music.edit", "op": "jump", "index": 1, "id": "b1"})
    assert host.track.id == "b1" and ids(host.queue) == ["c1"] and ids(host.history) == ["a1", "a2"]
    host._music_control("previous")
    assert host.track.id == "a2" and ids(host.queue) == ["b1", "c1"]


def test_remove_and_stale_edit_ignored(host):
    enq(host, "now", "a1", "a2", "b1", "c1")
    n = len(host.sent)
    host._edit({"t": "music.edit", "op": "remove", "index": 0, "id": "b1"})  # queue[0] is a2
    host._edit({"t": "music.edit", "op": "jump", "index": 7, "id": "a2"})
    host._edit({"t": "music.edit", "op": "remove", "index": 0})
    assert len(host.sent) == n and ids(host.queue) == ["a2", "b1", "c1"]
    host._edit({"t": "music.edit", "op": "remove", "index": 1, "id": "b1"})
    assert ids(host.queue) == ["a2", "c1"] and last_state(host)["queue"][1]["id"] == "c1"


def test_clear(host):
    enq(host, "now", "a1", "a2", "b1")
    host._edit({"t": "music.edit", "op": "clear"})
    assert host.queue == [] and host.track.id == "a1" and last_state(host)["queue"] == []
