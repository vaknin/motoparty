"""Lyrics (PROTOCOL.md "Tracks", Lyrics): LRC parsing and the timeline against
fixtures/lyrics.json, the fake host's /lyrics/<id>.json route and the client's fetch with the 503
retry rule. All lyric text here is made up."""

import asyncio
import json
import shutil
import threading
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace

import pytest

from conftest import load_fixture
from motoparty_peer import lyrics
from motoparty_peer.host import Host, _make_handler
from motoparty_peer.music import TrackInfo

FX = load_fixture("lyrics.json")


@pytest.mark.parametrize("case", FX["parse"], ids=lambda c: c["name"])
def test_parse_fixture(case):
    assert lyrics.parse_lrc(case["lrc"]) == case["lines"]


@pytest.mark.parametrize("step", FX["timeline"]["steps"], ids=lambda s: f"t={s['t']}")
def test_timeline_fixture(step):
    lines = FX["parse"][FX["timeline"]["case"]]["lines"]
    assert lyrics.timeline(lines, step["t"]) == (step["line"], step["sung"])


def test_decode_served_fixture():
    got = lyrics.decode(FX["served"])
    assert got == {"id": FX["served"]["id"], "source": "lrclib", "lines": FX["parse"][0]["lines"]}


@pytest.mark.parametrize("body", [
    [],
    {"source": "lrclib", "lines": []},
    {"id": "x", "source": "lrclib", "lines": {}},
    {"id": "x", "source": "lrclib", "lines": [{"ms": "1", "text": "a", "words": []}]},
    {"id": "x", "source": "lrclib", "lines": [{"ms": True, "text": "a", "words": []}]},
    {"id": "x", "source": "lrclib", "lines": [{"ms": 1, "text": "a", "words": [{"ms": 1}]}]},
])
def test_decode_rejects_bad_bodies(body):
    with pytest.raises(ValueError):
        lyrics.decode(body)


def test_served_round_trips():
    lines = lyrics.parse_lrc("[00:01]Paper kites\n[00:03]")
    assert lyrics.decode(json.loads(lyrics.served("abc", lines))) == {"id": "abc", "source": "lrclib", "lines": lines}


def test_leading_whitespace_before_a_stamp_is_plain_text():
    assert lyrics.parse_lrc(" [00:01]Late stamp") == []


# ---------------------------------------------------------------------- the fake host's route

LRC = "[ti:Invented]\n[00:00.50]Copper rain on tin\n[00:04]\n[00:05.25]Ünder the 🎵 lamp\n"


@pytest.fixture
def server(tmp_path):
    """A Host with two tracks (one with a sidecar .lrc) behind its real HTTP handler."""
    with_lrc, without = tmp_path / "song.m4a", tmp_path / "other.m4a"
    for f in (with_lrc, without):
        f.write_bytes(b"not really audio")
    (tmp_path / "song.lrc").write_text(LRC, encoding="utf-8")
    h = Host(SimpleNamespace(name="t", track=None))
    lib = [TrackInfo("withLyrics1", with_lrc, "Song", "Band", None, 9000),
           TrackInfo("noLyrics_22", without, "Other", "Band", None, 9000)]
    h.library, h.by_id = lib, {t.id: t for t in lib}
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), _make_handler(h))
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{httpd.server_address[1]}"
    yield h, base
    httpd.shutdown()
    httpd.server_close()


def request(url: str, method: str = "GET"):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, method=method), timeout=3) as r:
            return r.status, r.headers, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()


def test_route_200(server):
    _, base = server
    status, headers, body = request(f"{base}/lyrics/withLyrics1.json")
    assert status == 200
    assert headers["Content-Type"] == "application/json; charset=utf-8"
    assert int(headers["Content-Length"]) == len(body)
    got = json.loads(body.decode("utf-8"))
    assert got == {"id": "withLyrics1", "source": "lrclib", "lines": lyrics.parse_lrc(LRC)}
    assert [ln["text"] for ln in got["lines"]] == ["Copper rain on tin", "", "Ünder the 🎵 lamp"]


def test_route_head(server):
    _, base = server
    _, _, body = request(f"{base}/lyrics/withLyrics1.json")
    status, headers, empty = request(f"{base}/lyrics/withLyrics1.json", "HEAD")
    assert status == 200 and empty == b""
    assert headers["Content-Type"] == "application/json; charset=utf-8"
    assert int(headers["Content-Length"]) == len(body)
    assert request(f"{base}/lyrics/noLyrics_22.json", "HEAD")[0] == 404


@pytest.mark.parametrize("path", [
    "/lyrics/noLyrics_22.json",  # known track, no sidecar
    "/lyrics/unknownId12.json",  # valid id, not a track of ours
    "/lyrics/bad%20id.json",  # invalid ids
    "/lyrics/bad.id.json",
    "/lyrics/.json",
    "/lyrics/withLyrics1",
    "/lyrics/withLyrics1.lrc",
    "/lyrics/x/withLyrics1.json",
])
def test_route_404(server, path):
    _, base = server
    status, headers, body = request(base + path)
    assert status == 404 and body == b""


def test_route_404_when_the_lrc_has_nothing_timed(server, tmp_path):
    _, base = server
    (tmp_path / "other.lrc").write_text("[ar:Nobody]\nuntimed words\n", encoding="utf-8")
    assert request(f"{base}/lyrics/noLyrics_22.json")[0] == 404


def test_route_503_first_n_times_per_id(server):
    h, base = server
    h.lyrics_503 = 2
    url = f"{base}/lyrics/withLyrics1.json"
    assert [request(url)[0] for _ in range(3)] == [503, 503, 200]
    assert request(f"{base}/lyrics/noLyrics_22.json")[0] == 503  # its own count
    assert request(f"{base}/lyrics/unknownId12.json")[0] == 404  # unknown ids are never 503


def test_track_route_still_served(server):
    h, base = server
    status, headers, body = request(f"{base}/track/withLyrics1.m4a")
    assert status == 200 and headers["Content-Type"] == "audio/mp4" and body == b"not really audio"


# ---------------------------------------------------------------------- the client's fetch

def fetch(url, **kw):
    sleeps: list[float] = []

    async def sleep(s):
        sleeps.append(s)

    return asyncio.run(lyrics.fetch(url, sleep=sleep, **kw)), sleeps


def test_fetch_200(server):
    _, base = server
    (status, body), sleeps = fetch(f"{base}/lyrics/withLyrics1.json")
    assert status == 200 and body["lines"] == lyrics.parse_lrc(LRC) and sleeps == []


def test_fetch_404_is_final(server):
    _, base = server
    assert fetch(f"{base}/lyrics/noLyrics_22.json") == ((404, None), [])


def test_fetch_retries_503_every_5_s(server):
    h, base = server
    h.lyrics_503 = 6  # the sixth retry, the seventh request, gets it
    (status, body), sleeps = fetch(f"{base}/lyrics/withLyrics1.json")
    assert status == 200 and body["id"] == "withLyrics1"
    assert sleeps == [5.0] * 6


def test_fetch_gives_up_after_6_retries(server):
    h, base = server
    h.lyrics_503 = 7
    assert fetch(f"{base}/lyrics/withLyrics1.json") == ((503, None), [5.0] * 6)
    assert h.lyrics_503_sent["withLyrics1"] == 7


def test_fetch_delay_is_injectable(server):
    h, base = server
    h.lyrics_503 = 1
    (status, _), sleeps = fetch(f"{base}/lyrics/withLyrics1.json", retry_s=0.01)
    assert status == 200 and sleeps == [0.01]


def test_fetch_other_failures_raise(server):
    _, base = server
    calls = []

    def get(url):
        calls.append(url)
        raise RuntimeError("HTTP 500")

    with pytest.raises(RuntimeError):
        fetch(f"{base}/lyrics/withLyrics1.json", get=get)
    with pytest.raises(RuntimeError, match="lyrics fetch failed"):
        lyrics.get_once("http://127.0.0.1:1/lyrics/withLyrics1.json", timeout=1)


# ---------------------------------------------------------------------- the CLIs, end to end

@pytest.mark.skipif(not shutil.which("ffmpeg"), reason="needs ffmpeg for a real track")
def test_client_fetches_and_prints_lyrics_of_the_loaded_track(tmp_path):
    import subprocess

    from test_integration import start_pair

    track = tmp_path / "tone.m4a"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=330:duration=2",
                    "-c:a", "aac", "-b:a", "48k", str(track)], check=True, timeout=30)
    track.with_suffix(".lrc").write_text("[00:00.10]Tin cup morning\n[00:01.00]Blue fence evening\n",
                                         encoding="utf-8")
    host, client, _ = start_pair(tmp_path, track, "--lyrics")
    try:
        client.expect(r"<< \{\"t\":\"hello\"")
        host.send("load")
        load = client.msg("<<", "music.load")
        client.expect(rf"lyrics: {load['id']} 2 lines \(2 sung\) from lrclib")
        host.expect(rf'"GET /lyrics/{load["id"]}\.json HTTP/1.1" 200')
        client.send("lyrics")
        client.expect(r"\[0:00\.100\] Tin cup morning")
        client.expect(r"\[0:01\.000\] Blue fence evening")
        client.send("lyrics off")
        client.send("lyrics")
        client.expect(r"lyrics: off")
    finally:
        client.stop()
        host.stop()
