"""Fake-host browsing: search/browse over local tracks and the enqueue/edit queue semantics
(PROTOCOL.md "Browsing"). No sockets: Host.send and Host._start are stubbed."""

import asyncio
from pathlib import Path
from types import SimpleNamespace

import pytest

from motoparty_peer.host import MAX_QUEUE, Host
from motoparty_peer.music import (ARTIST_ALBUMS, ARTIST_SONGS, VALID_ID, TrackInfo, album_ref, artist_ref,
                                  browse_artist)
from motoparty_peer.protocol import ProtocolError, decode_message, encode_message


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


# ------------------------------------------------------------------ play by touch ends a talk


def open_talk(h):
    h.talk, h.talk_by, h.resume_after_talk = True, "client", True


def talk_closes(h):
    return [m for m in h.sent if m["t"] == "talk.close"]


def test_enqueue_now_during_a_talk_closes_it_before_playing(host):
    enq(host, "now", "a1")
    open_talk(host)
    host.sent.clear()
    enq(host, "now", "b1", "c1")
    assert talk_closes(host) == [{"t": "talk.close", "by": "client", "reason": "trigger"}]
    assert not host.talk and not host.resume_after_talk and host.media_at > 0
    assert host.started == ["a1", "b1"] and ids(host.queue) == ["c1"]


def test_jump_during_a_talk_closes_it(host):
    enq(host, "now", "a1", "a2", "b1")
    open_talk(host)
    host.sent.clear()
    host._edit({"t": "music.edit", "op": "jump", "index": 1, "id": "b1"})
    assert talk_closes(host) == [{"t": "talk.close", "by": "client", "reason": "trigger"}]
    assert not host.talk and host.started[-1] == "b1"


@pytest.mark.parametrize("mode", ["next", "end"])
def test_enqueue_next_or_end_keeps_the_talk_open(host, mode):
    enq(host, "now", "a1")
    open_talk(host)
    enq(host, mode, "b1")
    assert host.talk and not talk_closes(host)


def test_remove_clear_stale_jump_and_empty_enqueue_keep_the_talk_open(host):
    enq(host, "now", "a1", "a2", "b1")
    open_talk(host)
    host._edit({"t": "music.edit", "op": "remove", "index": 0, "id": "a2"})
    host._edit({"t": "music.edit", "op": "jump", "index": 0, "id": "stale"})
    enq(host, "now", "nope")
    host._edit({"t": "music.edit", "op": "clear"})
    assert host.talk and not talk_closes(host)


# ------------------------------------------------------------------ nowplaying / shuffle


def announces(h):
    return [(m["text"], m["earcon"]) for m in h.sent if m["t"] == "announce"]


def test_nowplaying(host):
    host._command("what's playing", "client")
    enq(host, "now", "a1")
    host._command("what song is this", "client")
    host._start(track("x", "Solo", artist=""))
    host._command("what is playing", "client")
    assert announces(host) == [("Nothing playing", "error"), ("Alpha by Band", "ok"), ("Solo", "ok")]


def test_shuffle_keeps_the_current_track(host):
    enq(host, "now", "a1", "a2")
    host._command("shuffle", "client")
    assert announces(host)[-1] == ("Nothing to shuffle", "error")
    enq(host, "end", *[t.id for t in LIB[2:]])
    before = ids(host.queue)
    host.sent.clear()
    for _ in range(20):  # with 4 upcoming tracks, 20 shuffles all equal to the start is ~1e-28
        host._command("shuffle please", "client")
        if ids(host.queue) != before:
            break
    assert sorted(ids(host.queue)) == sorted(before) and ids(host.queue) != before
    assert host.track.id == "a1" and host.started == ["a1"]
    assert announces(host)[-1] == ("Shuffled", "ok")
    assert [q["id"] for q in last_state(host)["queue"]] == ids(host.queue)


CLIENT_CLOSE = {"t": "talk.close", "by": "client", "reason": "trigger"}


def after_close(h):
    """What was sent after the talk.close, without the states."""
    i = h.sent.index(CLIENT_CLOSE)
    return [m for m in h.sent[i + 1:] if m["t"] != "state"]


@pytest.mark.parametrize("phrase, reply", [("what's playing", "Alpha by Band"), ("shuffle", "Shuffled")])
def test_nowplaying_and_shuffle_end_the_talk_then_announce(host, phrase, reply):
    enq(host, "now", "a1", "a2", "b1")
    host.music["playing"] = False  # as the talk froze it
    open_talk(host)
    host.sent.clear()
    host._command(phrase, "client")
    assert not host.talk and talk_closes(host) == [CLIENT_CLOSE]
    # the music that was playing resumes, and the reply is spoken after the switch
    assert [m["t"] for m in after_close(host)] == ["music.play", "announce"]
    assert after_close(host)[0]["id"] == "a1"
    assert after_close(host)[1] == {"t": "announce", "text": reply, "earcon": "ok"}
    assert announces(host) == [(reply, "ok")]  # nothing before the close either


@pytest.mark.parametrize("phrase", ["next", "previous", "pause", "resume", "play anything", "over"])
def test_a_command_whose_result_is_the_music_ends_the_talk_without_an_announce(host, phrase):
    enq(host, "now", "a1", "a2", "b1")
    host._music_control("next")  # a2 current, a1 behind it, b1 ahead
    host.music["playing"] = False  # as the talk froze it
    open_talk(host)
    host.sent.clear()
    host._command(phrase, "client")
    assert not host.talk and talk_closes(host) == [CLIENT_CLOSE]
    assert announces(host) == []
    assert host.media_at > 0


@pytest.mark.parametrize("phrase", ["next", "previous", "pause", "resume", "play anything"])
def test_a_successful_command_outside_a_talk_has_no_announce(host, phrase):
    enq(host, "now", "a1", "a2", "b1")
    host._music_control("next")
    if phrase == "resume":
        host.music["playing"] = False
    host.sent.clear()
    host._command(phrase, "client")
    assert announces(host) == [] and not talk_closes(host)


@pytest.mark.parametrize("phrase, reply", [
    ("next", "End of queue"), ("previous", "Nothing before this"), ("pause", "Nothing playing"),
    ("resume", "Nothing to resume"), ("shuffle", "Nothing to shuffle"), ("what's playing", "Nothing playing"),
    ("play anything", "Couldn't find anything"),
])
def test_a_failing_command_ends_the_talk_and_the_error_follows_the_close(host, phrase, reply):
    host.talk, host.talk_by = True, "client"  # nothing loaded, nothing queued
    host._command(phrase, "client")
    assert not host.talk and talk_closes(host) == [CLIENT_CLOSE]
    assert after_close(host) == [{"t": "announce", "text": reply, "earcon": "error"}]
    assert announces(host) == [(reply, "error")]


@pytest.mark.parametrize("phrase", ["what a view", "volume up"])
def test_an_unparsed_phrase_or_a_stray_volume_utterance_ends_nothing(host, phrase):
    enq(host, "now", "a1")
    open_talk(host)
    host._command(phrase, "client")
    assert host.talk and not talk_closes(host)
    assert announces(host) == [("Didn't catch that", "error")]


@pytest.mark.parametrize("playing_before, held", [(True, False), (False, True)])
def test_next_in_a_talk_resumes_only_music_that_was_playing_before_it(host, playing_before, held):
    """The new track is still loading when the talk closes: it starts by itself once ready,
    unless the music was paused before the talk (then it stays paused on the new track)."""
    enq(host, "now", "a1", "a2")
    host.music["playing"] = False
    host.talk, host.talk_by, host.resume_after_talk = True, "client", playing_before
    real_start = host._start

    def start(t):
        real_start(t)
        host._loading = lambda: True

    host._start = start
    host.sent.clear()
    host._command("next", "client")
    assert not host.talk and host.started[-1] == "a2"
    assert host.no_resume is held
    assert after_close(host) == []  # the old track does not resume in between; no announce


# Queueing by voice (PROTOCOL.md "Commands"). The fake host "finds" its library for any query.

def test_queue_adds_without_interrupting_and_says_what(host):
    enq(host, "now", "a1", "a2")
    host._command("queue 1 artist band", "client")
    assert host.track.id == "a1" and host.started == ["a1"]
    added = [t for t in LIB if t.id not in ("a1", "a2")][0]
    assert ids(host.queue) == ["a2", added.id]
    assert announces(host)[-1] == (f"Added {added.title} by {added.artist}", "ok")
    host._command("queue next 2 similar", "client")
    assert ids(host.queue)[2:] == ["a2", added.id] and len(host.queue) == 4
    assert announces(host)[-1] == ("Next: 2 songs", "ok")
    assert [q["id"] for q in last_state(host)["queue"]] == ids(host.queue)


def test_queue_instead_replaces_the_upcoming_tracks(host):
    enq(host, "now", "a1", "a2")
    host._command("queue instead anything", "client")
    assert host.track.id == "a1" and ids(host.queue) == [t.id for t in LIB if t.id != "a1"]
    host._command("queue anything", "client")
    assert announces(host)[-1] == ("Nothing to add", "error")


def test_queue_with_nothing_loaded_just_plays(host):
    host._command("queue similar", "client")
    assert announces(host) == [("Nothing playing", "error")]
    host._command("queue 2 artist band", "client")
    assert host.started == [LIB[0].id] and ids(host.queue) == [LIB[1].id]
    assert announces(host) == [("Nothing playing", "error")]


def test_queue_ends_the_talk_then_announces(host):
    enq(host, "now", "a1")
    host.music["playing"] = False  # as the talk froze it
    open_talk(host)
    host.sent.clear()
    host._command("queue next 1 song whatever", "client")
    assert not host.talk and talk_closes(host) == [CLIENT_CLOSE]
    assert [m["t"] for m in after_close(host)] == ["music.play", "announce"]
    assert after_close(host)[0]["id"] == "a1" and after_close(host)[1]["text"].startswith("Next: ")


# ---------------------------------------------------------------- downloads (Browsing 6)


def downloads_sent(h):
    return [m for m in h.sent if m["t"] == "music.downloads"]


def test_download_a_collection_reports_progress_and_marks(host):
    """A start downloads each id in turn; library tracks become cached, other ids fail."""
    host.download_step_ms = 1
    ref = album_ref("First")

    async def run(ids):
        host._download({"t": "music.download", "op": "start", "ref": ref, "ids": ids})
        await host.download_tasks[ref]

    asyncio.run(run(["a1", "a2", "zz9", "a1", "bad id!"]))
    sent = downloads_sent(host)
    # Invalid and repeated ids are left out: three tracks.
    assert sent[0]["downloads"] == [{"ref": ref, "done": 0, "total": 3, "failed": 0, "running": True}]
    assert [m["downloads"][0]["done"] for m in sent] == [0, 1, 2, 3]
    assert sent[-1] == {"t": "music.downloads", "cached": ["a1", "a2"],
                        "downloads": [{"ref": ref, "done": 3, "total": 3, "failed": 1, "running": False}]}
    assert ref not in host.download_tasks
    # Everything cached already: done at once.
    asyncio.run(run(["a1", "a2"]))
    assert downloads_sent(host)[-1]["downloads"] == [{"ref": ref, "done": 2, "total": 2, "failed": 0, "running": False}]


def test_download_stop_forgets_the_progress_and_keeps_what_was_cached(host):
    host.download_step_ms = 50
    ref = album_ref("First")

    async def run():
        host._download({"t": "music.download", "op": "start", "ref": ref, "ids": ["a1", "a2"]})
        # A second start of the same collection is ignored while it runs.
        host._download({"t": "music.download", "op": "start", "ref": ref, "ids": ["b1"]})
        await asyncio.sleep(0.08)  # a1 done, a2 in flight
        host._download({"t": "music.download", "op": "stop", "ref": ref})
        await asyncio.sleep(0.08)

    asyncio.run(run())
    assert host.cached == {"a1"}
    assert downloads_sent(host)[-1] == {"t": "music.downloads", "cached": ["a1"], "downloads": []}
    assert host.download_tasks == {} and host.downloads == {}
    n = len(host.sent)
    host._download({"t": "music.download", "op": "stop", "ref": ref})  # nothing running: ignored
    host._download({"t": "music.download", "op": "start", "ref": "bad ref!", "ids": ["a1"]})
    host._download({"t": "music.download", "op": "start", "ref": ref, "ids": ["bad id!"]})
    assert len(host.sent) == n


def test_busy_rides_on_state_until_cleared(host):
    host.set_busy('Searching song "moby"')
    assert last_state(host)["busy"] == 'Searching song "moby"'
    n = len(host.sent)
    host.set_busy('Searching song "moby"')  # no change, no state
    assert len(host.sent) == n
    host.set_busy(None)
    assert "busy" not in last_state(host)


# ------------------------------------------------------------------ artists (2026-10-02)

ART_LIB = [track("a1", "Alpha", "First"), track("a2", "Beta", "First"), track("o1", "Omega", "Other", artist="Solo"),
           track("o2", "Psi", artist="Solo"), track("b1", "Gamma", "Second")]


def test_search_artists_by_name(host):
    """PROTOCOL.md "Browsing" 2a: artists are the library's artist names, ref a valid id, artist empty."""
    host.library = list(ART_LIB)
    host._browse({"t": "music.search", "id": 1, "kind": "artists", "query": ""})
    items = host.sent[-1]["items"]
    assert [(i["title"], i["artist"]) for i in items] == [("Band", ""), ("Solo", "")]
    assert all(VALID_ID.fullmatch(i["ref"]) and i["ref"] == artist_ref(i["title"]) for i in items)
    host._browse({"t": "music.search", "id": 2, "kind": "artists", "query": "SOL"})
    assert [i["title"] for i in host.sent[-1]["items"]] == ["Solo"]
    host._browse({"t": "music.search", "id": 3, "kind": "artists", "query": "omega"})  # a title is not a name
    assert host.sent[-1]["items"] == []


def test_browse_an_artist_page(host):
    host.library = list(ART_LIB)
    host._browse({"t": "music.browse", "id": 4, "ref": artist_ref("Solo"), "kind": "artist"})
    res = host.sent[-1]
    assert res["id"] == 4 and "error" not in res
    assert [i["ref"] for i in res["items"]] == ["o1", "o2"]
    assert res["albums"] == [{"ref": album_ref("Other"), "title": "Other", "artist": "Solo", "count": 1}]
    assert encode_message(res)  # a valid music.results
    host._browse({"t": "music.browse", "id": 5, "ref": artist_ref("Band"), "kind": "artist"})
    assert [a["title"] for a in host.sent[-1]["albums"]] == ["First", "Second"]
    # An artist ref is not a collection, and an album ref is not an artist.
    host._browse({"t": "music.browse", "id": 6, "ref": artist_ref("Solo")})
    assert host.sent[-1]["error"] and host.sent[-1]["items"] == []
    host._browse({"t": "music.browse", "id": 7, "ref": album_ref("First"), "kind": "artist"})
    assert host.sent[-1]["error"] and "albums" not in host.sent[-1]
    host._browse({"t": "music.browse", "id": 8, "ref": "bad ref!", "kind": "artist"})
    assert host.sent[-1]["error"] == "Invalid ref"


def test_artist_page_is_capped():
    lib = [track(f"s{i:02}", f"Song {i}", f"Album {i}", artist="Many") for i in range(60)]
    songs, albums = browse_artist(lib, artist_ref("Many"))
    assert len(songs) == ARTIST_SONGS and len(albums) == ARTIST_ALBUMS
    assert songs[0]["ref"] == "s00" and albums[-1]["title"] == "Album 49"


def test_browse_kind_and_albums_on_the_wire():
    assert decode_message(b'{"t":"music.browse","id":1,"ref":"UC1","kind":"artist"}')["kind"] == "artist"
    assert "kind" not in decode_message(b'{"t":"music.browse","id":1,"ref":"PL1"}')
    for bad in (b'{"t":"music.browse","id":1,"ref":"UC1","kind":"song"}',
                b'{"t":"music.results","id":1,"items":[],"albums":[{"ref":"x"}]}',
                b'{"t":"music.results","id":1,"items":[],"albums":{}}'):
        with pytest.raises(ProtocolError):
            decode_message(bad)
