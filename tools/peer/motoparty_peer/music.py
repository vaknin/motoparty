"""Track handling: MP4 metadata, client-side download/cache and scheduled local playback."""

from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from .protocol import now_ms
from .util import log


@dataclass(slots=True)
class TrackInfo:
    id: str
    file: Path
    title: str
    artist: str
    album: str | None
    duration_ms: int
    art: str | None = None  # cover URL from a music.enqueue; the local files have none

    @property
    def path(self) -> str:
        return f"/track/{self.id}.m4a"


def track_id(file: Path) -> str:
    """11-char URL-safe id (same alphabet as YouTube ids) derived from the file content."""
    h = hashlib.sha1(file.read_bytes()).digest()
    return base64.urlsafe_b64encode(h).decode()[:11]


def _mp4_boxes(data: bytes, start: int, end: int):
    i = start
    while i + 8 <= end:
        size, kind = struct.unpack_from(">I4s", data, i)
        hdr = 8
        if size == 1:
            if i + 16 > end:
                return
            size = struct.unpack_from(">Q", data, i + 8)[0]
            hdr = 16
        elif size == 0:
            size = end - i
        if size < hdr or i + size > end:
            return
        yield kind, i + hdr, i + size
        i += size


def mp4_duration_ms(data: bytes) -> int | None:
    """Duration from moov/mvhd, no external tools."""
    for kind, s, e in _mp4_boxes(data, 0, len(data)):
        if kind != b"moov":
            continue
        for k2, s2, _e2 in _mp4_boxes(data, s, e):
            if k2 != b"mvhd":
                continue
            version = data[s2]
            if version == 1:
                timescale, duration = struct.unpack_from(">IQ", data, s2 + 4 + 16)
            else:
                timescale, duration = struct.unpack_from(">II", data, s2 + 4 + 8)
            return round(duration * 1000 / timescale) if timescale else None
    return None


def looks_like_mp4(data: bytes) -> bool:
    return len(data) >= 12 and data[4:8] == b"ftyp"


def probe(file: Path) -> dict:
    """ffprobe format info if ffprobe exists, else {}."""
    if not shutil.which("ffprobe"):
        return {}
    try:
        out = subprocess.run(
            ["ffprobe", "-v", "error", "-print_format", "json", "-show_format", "-show_streams", str(file)],
            capture_output=True, text=True, timeout=10,
        )
    except (OSError, subprocess.SubprocessError):
        return {}
    if out.returncode != 0:
        return {"error": out.stderr.strip() or f"ffprobe exit {out.returncode}"}
    try:
        return json.loads(out.stdout)
    except ValueError:
        return {}


def load_track(file: Path) -> TrackInfo:
    data = file.read_bytes()
    if not looks_like_mp4(data):
        raise ValueError(f"{file} is not an MP4/M4A file")
    info = probe(file)
    tags = {k.lower(): v for k, v in (info.get("format", {}).get("tags") or {}).items()}
    duration = mp4_duration_ms(data)
    if duration is None and info.get("format", {}).get("duration"):
        duration = round(float(info["format"]["duration"]) * 1000)
    return TrackInfo(
        id=track_id(file),
        file=file,
        title=tags.get("title") or file.stem,
        artist=tags.get("artist") or "Unknown artist",
        album=tags.get("album") or None,
        duration_ms=duration or 0,
    )


def load_library(paths: list[str]) -> list[TrackInfo]:
    """The host's tracks: each path is an .m4a file or a directory of them. Dedup by id."""
    files: list[Path] = []
    for p in map(Path, paths):
        files += sorted(p.glob("*.m4a")) if p.is_dir() else [p]
    out: dict[str, TrackInfo] = {}
    for f in files:
        t = load_track(f)
        out.setdefault(t.id, t)
    return list(out.values())


# PROTOCOL.md "Browsing": only YouTube-style ids travel as refs and track ids.
VALID_ID = re.compile(r"[A-Za-z0-9_-]{1,64}")


def album_ref(album: str) -> str:
    """A stable ref for a local album name (the real host uses a YouTube playlist id)."""
    return "al" + base64.urlsafe_b64encode(hashlib.sha1(album.encode()).digest()).decode()[:14]


def search(library: list[TrackInfo], kind: str, query: str) -> list[dict]:
    """music.search over the local tracks: substring match on title/artist/album."""
    q = query.casefold()
    songs = [t for t in library if any(q in (v or "").casefold() for v in (t.title, t.artist, t.album))]
    if kind == "songs":
        return [song_item(t) for t in songs]
    if kind == "albums":
        albums: dict[str, dict] = {}
        for t in songs:
            if t.album and t.album not in albums:
                n = sum(1 for u in library if u.album == t.album)
                albums[t.album] = {"ref": album_ref(t.album), "title": t.album, "artist": t.artist, "count": n}
        return list(albums.values())
    return []  # playlists: the fake host has none


def browse(library: list[TrackInfo], ref: str) -> list[dict] | None:
    """An album's songs in library order; None for an unknown ref."""
    songs = [song_item(t) for t in library if t.album and album_ref(t.album) == ref]
    return songs or None


def song_item(t: TrackInfo) -> dict:
    return {"ref": t.id, "title": t.title, "artist": t.artist, "durationMs": t.duration_ms}


_SAFE = re.compile(r"[^A-Za-z0-9_-]")


def cache_path(cache_dir: Path, id_: str) -> Path:
    return cache_dir / f"{_SAFE.sub('_', id_)}.m4a"


def download(url: str, dest: Path, timeout: float = 30.0) -> int:
    """Blocking whole-file download to dest (atomic rename). Returns bytes. Raises RuntimeError."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(".part")
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r, open(tmp, "wb") as f:
            if r.status != 200:
                raise RuntimeError(f"HTTP {r.status}")
            ctype = r.headers.get("Content-Type", "")
            if ctype != "audio/mp4":
                log(f"warning: {url} served Content-Type {ctype!r}, PROTOCOL.md says audio/mp4")
            shutil.copyfileobj(r, f)
            expected = r.headers.get("Content-Length")
        size = tmp.stat().st_size
        if expected is not None and int(expected) != size:
            raise RuntimeError(f"short download: {size} of {expected} bytes")
        os.replace(tmp, dest)
        return size
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"HTTP {e.code}") from None
    except (urllib.error.URLError, OSError, TimeoutError) as e:
        raise RuntimeError(f"download failed: {getattr(e, 'reason', e)}") from None
    finally:
        tmp.unlink(missing_ok=True)


def check_decodable(file: Path) -> None:
    """Raise RuntimeError unless the file is an MP4 with an audio stream."""
    with open(file, "rb") as f:
        head = f.read(12)
    if not looks_like_mp4(head):
        raise RuntimeError("not an MP4 file (no ftyp box)")
    info = probe(file)
    if "error" in info:
        raise RuntimeError(f"not decodable: {info['error']}")
    if info and not any(s.get("codec_type") == "audio" for s in info.get("streams", [])):
        raise RuntimeError("no audio stream")


class LocalPlayer:
    """Plays a cached file at a scheduled local monotonic time with mpv or ffplay."""

    def __init__(self, enabled: bool) -> None:
        self.cmd = None
        if enabled:
            if shutil.which("mpv"):
                self.cmd = "mpv"
            elif shutil.which("ffplay"):
                self.cmd = "ffplay"
            else:
                log("--play: neither mpv nor ffplay found; will only log schedules")
        self._proc: subprocess.Popen | None = None
        self._timer: asyncio.TimerHandle | None = None

    def schedule(self, file: Path, position_ms: float, local_start_ms: float) -> None:
        if self.cmd is None:
            return
        self.stop()
        delay = local_start_ms - now_ms()
        if delay < 0:  # late join: start now, further into the track
            position_ms -= delay
            delay = 0
        loop = asyncio.get_running_loop()
        self._timer = loop.call_later(delay / 1000, self._start, file, position_ms)

    def _start(self, file: Path, position_ms: float) -> None:
        self._timer = None
        secs = f"{max(0.0, position_ms) / 1000:.3f}"
        if self.cmd == "mpv":
            argv = ["mpv", "--no-video", "--really-quiet", "--no-terminal", f"--start={secs}", str(file)]
        else:
            argv = ["ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", "-ss", secs, str(file)]
        log(f"player: {self.cmd} starting at {secs} s")
        self._proc = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def stop(self) -> None:
        if self._timer is not None:
            self._timer.cancel()
            self._timer = None
        if self._proc is not None:
            if self._proc.poll() is None:
                self._proc.terminate()
                try:
                    self._proc.wait(1)
                except subprocess.TimeoutExpired:
                    self._proc.kill()
            self._proc = None
