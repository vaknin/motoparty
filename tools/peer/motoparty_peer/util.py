"""Console output and stdin commands shared by client and host."""

from __future__ import annotations

import asyncio
import json
import sys
import threading
import time


def log(*parts: object) -> None:
    t = time.time()
    stamp = time.strftime("%H:%M:%S", time.localtime(t)) + f".{int(t * 1000) % 1000:03d}"
    print(stamp, *parts, flush=True)


def js(msg: dict) -> str:
    return json.dumps(msg, ensure_ascii=False, separators=(",", ":"))


def stdin_lines(loop: asyncio.AbstractEventLoop) -> asyncio.Queue[str | None]:
    """Read stdin on a daemon thread (safe for ttys and pipes); None marks EOF."""
    q: asyncio.Queue[str | None] = asyncio.Queue()

    def run() -> None:
        try:
            for line in sys.stdin:
                loop.call_soon_threadsafe(q.put_nowait, line.rstrip("\r\n"))
        except (OSError, ValueError):
            pass
        try:
            loop.call_soon_threadsafe(q.put_nowait, None)
        except RuntimeError:
            pass  # loop already closed

    threading.Thread(target=run, name="stdin", daemon=True).start()
    return q
