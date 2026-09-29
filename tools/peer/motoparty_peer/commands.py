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
    "over": "end",
    "end talk": "end",
    "hang up": "end",
    "what's playing": "nowplaying",
    "whats playing": "nowplaying",
    "what is playing": "nowplaying",
    "what song is this": "nowplaying",
    "shuffle": "shuffle",
}

# The first phrase decides (PROTOCOL.md "Commands"): the opener's first non-empty phrase is a
# command if its result arrives within this of the phone's live earcon (inclusive) and parses.
FIRST_PHRASE_MS = 8000
ROLES = ("opener", "other", "solo")

# Actions that end the talk they are spoken in (PROTOCOL.md "Commands", Effect on the talk).
TALK_ENDING = ("play", "resume", "end")

UNKNOWN_ANNOUNCE = {"t": "announce", "text": "Didn't catch that", "earcon": "error"}

# Volume is local (PROTOCOL.md "Commands"): the parser still recognises these phrases, but the
# phone that heard them changes its own volume and sends nothing.
VOLUME_ACTIONS = ("volumeUp", "volumeDown")


def _keep(ch: str) -> bool:
    # Per code point: Unicode letters (L*), marks (M*), numbers (N*), apostrophe, whitespace.
    if ch == "'" or ch.isspace():
        return True
    return unicodedata.category(ch)[0] in "LMN"


def _words(text: str) -> list[str]:
    """Lowercase, U+2019 -> apostrophe, anything else not kept -> space, split on whitespace."""
    return "".join(ch if _keep(ch) else " " for ch in text.lower().replace("\u2019", "'")).split()


def normalise(text: str) -> list[str]:
    """`_words`, then drop leading "hey"/"please" words and one trailing "please"."""
    words = _words(text)
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


def command_text(text: str) -> str:
    """The normalisation of "Commands" up to and including trim (hey/please kept): the text a
    phone sends as `command.text`. "" = an empty phrase."""
    return " ".join(_words(text))


class FirstPhraseGate:
    """One phone's command gate for one talk (PROTOCOL.md "Commands", The first phrase decides).

    role: "opener" (this phone opened the talk), "other" (it did not) or "solo" (the host's talk
    has no client). `live_ms` is when this phone's live earcon played, on the same clock as the
    `at_ms` given to `phrase`; the gate reads no clock itself. After a None, `why` says why.
    """

    def __init__(self, role: str, live_ms: int | float = 0) -> None:
        if role not in ROLES:
            raise ValueError(f"role must be one of {ROLES}, not {role!r}")
        self.role = role
        self.live_ms = live_ms
        self.spent = False  # the opener's first phrase has been decided (or its window passed)
        self.why = ""

    def phrase(self, text: str, at_ms: int | float) -> str | None:
        """A recognised phrase whose result arrived at `at_ms` -> its command text, or None
        (conversation: never sent, never acted on, no "Didn't catch that")."""
        cmd = command_text(text)
        if not cmd:
            self.why = "empty"
            return None
        if self.role == "solo":
            return cmd  # every non-empty phrase, no window; unparsed -> "Didn't catch that"
        if self.role == "other":
            self.why = "this phone did not open the talk"
            return None
        if self.spent:
            self.why = "the first phrase is spent"
            return None
        self.spent = True
        if at_ms - self.live_ms > FIRST_PHRASE_MS:
            self.why = f"after the {FIRST_PHRASE_MS} ms window"
            return None
        if parse_command(cmd)["action"] == "unknown":
            self.why = "the first phrase does not parse"
            return None
        return cmd
