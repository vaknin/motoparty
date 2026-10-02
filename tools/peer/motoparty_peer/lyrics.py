"""Synced lyrics (PROTOCOL.md "Tracks", Lyrics): LRC to lines and word times, the timeline, and
the client side of `GET /lyrics/<id>.json` with its 503 retry rule."""

from __future__ import annotations

import asyncio
import bisect
import json
import re
import urllib.error
import urllib.request
from collections.abc import Awaitable, Callable

from .util import log

# PROTOCOL.md: a 503 is tried again after 5 s, at most 6 times per id.
RETRY_S = 5.0
MAX_RETRIES = 6
# Word i starts 75 ms per code point after the line, unless that runs into the next line.
MS_PER_CHAR = 75
CONTENT_TYPE = "application/json; charset=utf-8"

_STAMP = re.compile(r"\[([0-9]+):([0-9]+)(?:\.([0-9]+))?]")
_BLANKS = re.compile(r"[ \t]+")


def _stamp_ms(m: re.Match) -> int:
    frac = (m[3] or "").ljust(3, "0")[:3]
    return int(m[1]) * 60000 + int(m[2]) * 1000 + int(frac)


def words(text: str, line_ms: int, next_ms: int | None) -> list[dict]:
    """The line's words with their start times. Lengths are code points (len() of a str)."""
    parts = [w for w in _BLANKS.split(text) if w]
    total = sum(len(w) for w in parts)
    squeeze = next_ms is not None and line_ms + MS_PER_CHAR * total > next_ms
    out, c = [], 0
    for w in parts:
        ms = line_ms + (c * (next_ms - line_ms) // total if squeeze else MS_PER_CHAR * c)
        out.append({"ms": ms, "text": w})
        c += len(w)
    return out


def parse_lrc(lrc: str) -> list[dict]:
    """LRC text -> [{"ms", "text", "words": [{"ms", "text"}]}], sorted by ms (stable)."""
    timed: list[tuple[int, str]] = []
    for raw in lrc.split("\n"):
        line = raw.removesuffix("\r")
        stamps, pos = [], 0
        while m := _STAMP.match(line, pos):
            stamps.append(_stamp_ms(m))
            pos = m.end()
        text = line[pos:].strip(" \t")
        timed += [(ms, text) for ms in stamps]  # no stamp ([ar:…] tags, plain text): dropped
    timed.sort(key=lambda x: x[0])  # list.sort is stable
    return [
        {"ms": ms, "text": text, "words": words(text, ms, timed[i + 1][0] if i + 1 < len(timed) else None)}
        for i, (ms, text) in enumerate(timed)
    ]


def timeline(lines: list[dict], t: int) -> tuple[int, int]:
    """At lyrics position t: (current line index, -1 before the first line; how many of its words
    are sung)."""
    i = bisect.bisect_right([ln["ms"] for ln in lines], t) - 1
    if i < 0:
        return -1, 0
    return i, sum(1 for w in lines[i]["words"] if w["ms"] <= t)


def served(id_: str, lines: list[dict]) -> bytes:
    """The 200 body for /lyrics/<id>.json."""
    return json.dumps({"id": id_, "source": "lrclib", "lines": lines}, ensure_ascii=False,
                      separators=(",", ":")).encode()


def _int(v) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def decode(body: dict) -> dict:
    """A 200 body -> {"id", "source", "lines"} with unknown fields dropped. Raises ValueError."""
    if not isinstance(body, dict) or not isinstance(body.get("id"), str) or not isinstance(body.get("source"), str):
        raise ValueError("lyrics: id and source must be strings")
    if not isinstance(body.get("lines"), list):
        raise ValueError("lyrics: lines must be a list")
    lines = []
    for ln in body["lines"]:
        if not isinstance(ln, dict) or not _int(ln.get("ms")) or not isinstance(ln.get("text"), str) \
                or not isinstance(ln.get("words"), list):
            raise ValueError(f"lyrics: bad line {ln!r}")
        ws = []
        for w in ln["words"]:
            if not isinstance(w, dict) or not _int(w.get("ms")) or not isinstance(w.get("text"), str):
                raise ValueError(f"lyrics: bad word {w!r}")
            ws.append({"ms": w["ms"], "text": w["text"]})
        lines.append({"ms": ln["ms"], "text": ln["text"], "words": ws})
    return {"id": body["id"], "source": body["source"], "lines": lines}


def get_once(url: str, timeout: float = 10.0) -> tuple[int, dict | None]:
    """Blocking GET: (status, decoded body for a 200). Raises RuntimeError on anything else than
    200/404/503 or a bad body."""
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            ctype = r.headers.get("Content-Type", "")
            if ctype != CONTENT_TYPE:
                log(f"warning: {url} served Content-Type {ctype!r}, PROTOCOL.md says {CONTENT_TYPE!r}")
            data = r.read()
            status = r.status
    except urllib.error.HTTPError as e:
        if e.code in (404, 503):
            return e.code, None
        raise RuntimeError(f"HTTP {e.code}") from None
    except (urllib.error.URLError, OSError, TimeoutError) as e:
        raise RuntimeError(f"lyrics fetch failed: {getattr(e, 'reason', e)}") from None
    if status != 200:
        raise RuntimeError(f"HTTP {status}")
    try:
        return 200, decode(json.loads(data.decode("utf-8")))
    except (UnicodeDecodeError, ValueError) as e:
        raise RuntimeError(f"bad lyrics body: {e}") from None


async def fetch(
    url: str,
    *,
    retry_s: float = RETRY_S,
    max_retries: int = MAX_RETRIES,
    sleep: Callable[[float], Awaitable[object]] = asyncio.sleep,
    get: Callable[[str], tuple[int, dict | None]] = get_once,
) -> tuple[int, dict | None]:
    """GET with the 503 rule: after a 503, wait retry_s and try again, at most max_retries times.
    -> (200, body), (404, None), or (503, None) when the retries ran out (give up until the id is
    loaded again). Raises RuntimeError like get_once."""
    retries = 0
    while True:
        status, body = await asyncio.to_thread(get, url)
        if status != 503 or retries >= max_retries:
            return status, body
        retries += 1
        await sleep(retry_s)
