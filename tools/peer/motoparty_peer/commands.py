"""Voice command grammar (PROTOCOL.md "Commands")."""

from __future__ import annotations

import unicodedata

KINDS = ("song", "album", "artist", "playlist")

_EXACT = {
    "pause": "pause",
    "stop": "pause",
    "resume": "resume",
    "continue": "resume",
    "next": "next",
    "skip": "next",
    "previous": "previous",
    "back": "previous",
    "volume up": "volumeUp",
    "louder": "volumeUp",
    "volume down": "volumeDown",
    "quieter": "volumeDown",
}

UNKNOWN_ANNOUNCE = {"t": "announce", "text": "Didn't catch that", "earcon": "error"}

# Volume is local (PROTOCOL.md "Commands"): the parser still recognises these phrases, but the
# phone that heard them changes its own volume and sends nothing.
VOLUME_ACTIONS = ("volumeUp", "volumeDown")


def _keep(ch: str) -> bool:
    # Per code point: Unicode letters (L*), marks (M*), numbers (N*), apostrophe, whitespace.
    if ch == "'" or ch.isspace():
        return True
    return unicodedata.category(ch)[0] in "LMN"


def normalise(text: str) -> list[str]:
    """Lowercase, U+2019 -> apostrophe, anything else not kept -> space, split on whitespace."""
    cleaned = "".join(ch if _keep(ch) else " " for ch in text.lower().replace("\u2019", "'"))
    words = cleaned.split()
    while words and words[0] in ("hey", "please"):
        words.pop(0)
    if words and words[-1] == "please":
        words.pop()
    return words


def parse_command(text: str) -> dict[str, str]:
    """-> {"action": ...} plus "kind"/"query" for play. Unmatched -> {"action": "unknown"}."""
    words = normalise(text)
    if not words:
        return {"action": "unknown"}
    if words[0] == "play":
        rest = words[1:]
        kind = "song"
        if len(rest) >= 2 and rest[0] == "the" and rest[1] in KINDS:
            kind, rest = rest[1], rest[2:]
        elif rest and rest[0] in KINDS:
            kind, rest = rest[0], rest[1:]
        if not rest:
            return {"action": "unknown"}
        return {"action": "play", "kind": kind, "query": " ".join(rest)}
    action = _EXACT.get(" ".join(words))
    return {"action": action} if action else {"action": "unknown"}
