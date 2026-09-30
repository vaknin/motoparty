"""Voice command grammar (PROTOCOL.md "Commands")."""

from __future__ import annotations

import json

import unicodedata

KINDS = ("song", "album", "artist", "playlist")
# Queueing by voice (PROTOCOL.md "Commands"): `queue [next|instead] [<count>] <kind> <query>`
# or `… similar`.
WHERES = ("next", "instead")
SIMILAR = "similar"
QUEUE_MAX_COUNT = 50
QUEUE_SIMILAR = 20

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


def _count(word: str) -> bool:
    return len(word) <= 2 and word.isascii() and word.isdigit() and 1 <= int(word) <= QUEUE_MAX_COUNT


def parse_command(text: str) -> dict[str, str]:
    """-> {"action": ...} plus "kind"/"query" for play; for queue "where", "kind" (or
    "similar"), "query" unless similar and "count" (a decimal string) when given.
    Unmatched -> {"action": "unknown"}."""
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
    if words[0] == "queue":
        rest = words[1:]
        out = {"action": "queue", "where": "end"}
        if rest and rest[0] in WHERES:
            out["where"], rest = rest[0], rest[1:]
        # A number is a count only in front of a kind or `similar`.
        if len(rest) >= 2 and (rest[1] in KINDS or rest[1] == SIMILAR) and _count(rest[0]):
            out["count"], rest = str(int(rest[0])), rest[1:]
        if rest == [SIMILAR]:
            return {**out, "kind": SIMILAR}
        kind = "song"
        if rest and rest[0] in KINDS:
            kind, rest = rest[0], rest[1:]
        if not rest:
            return {"action": "unknown"}
        return {**out, "kind": kind, "query": " ".join(rest)}
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

    def __init__(self, role: str, live_ms: int | float = 0, interpret: bool = False) -> None:
        if role not in ROLES:
            raise ValueError(f"role must be one of {ROLES}, not {role!r}")
        self.role = role
        # The host interprets (PROTOCOL.md "Commands", Interpretation): the opener's first phrase
        # is a candidate whether it parses or not.
        self.interpret = interpret
        self.live_ms = live_ms
        self.spent = False  # the opener's first phrase has been decided (or its window passed)
        self.asked_ms: int | float | None = None  # the host's question, while its reply is awaited
        self.why = ""

    def ask(self, at_ms: int | float) -> None:
        """The host's clarifying question arrived at `at_ms`: the opener's next non-empty phrase
        within ANSWER_MS is the reply, passed on as it is (PROTOCOL.md "Commands", The clarifying
        question). Nothing changes for the other roles."""
        if self.role == "opener":
            self.asked_ms = at_ms

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
        if self.asked_ms is not None:
            asked, self.asked_ms, self.spent = self.asked_ms, None, True
            if at_ms - asked > ANSWER_MS:
                self.why = f"after the {ANSWER_MS} ms reply window"
                return None
            return cmd
        if self.spent:
            self.why = "the first phrase is spent"
            return None
        self.spent = True
        if at_ms - self.live_ms > FIRST_PHRASE_MS:
            self.why = f"after the {FIRST_PHRASE_MS} ms window"
            return None
        if not self.interpret and parse_command(cmd)["action"] == "unknown":
            self.why = "the first phrase does not parse"
            return None
        return cmd


INTERPRET_TIMEOUT_MS = 3000
INTERPRET_UP_NEXT = 5
_INTERPRETED = {
    "pause": "pause", "resume": "resume", "next": "next", "previous": "previous",
    "shuffle": "shuffle", "volumeUp": "volume up", "volumeDown": "volume down",
    "nowplaying": "what is playing", "end": "over",
}


ASK_MAX_CHARS = 80
ANSWER_MS = 10_000
ANSWER_GRACE_MS = 2000


def _interpreted_play(obj: dict) -> str | None:
    query = obj.get("query")
    if not isinstance(query, str) or not command_text(query):
        return None
    kind = obj.get("kind")
    return f"play {kind if isinstance(kind, str) and kind in KINDS else 'song'} {command_text(query)}"


def interpretation_outcome(answer: str) -> str | dict | None:
    """The interpreter's answer (the model's text) -> the command text to execute, a question
    `{"ask": <question>, "fallback": <command text or None>}`, or None = conversation
    (PROTOCOL.md "Commands", Interpretation and The clarifying question; vectors:
    fixtures/interpret.json)."""
    try:
        obj = json.loads(answer)
    except ValueError:
        return None
    if not isinstance(obj, dict) or not isinstance(obj.get("action"), str):
        return None
    action = obj["action"]
    if action == "play":
        return _interpreted_play(obj)
    if action == "queue":
        source = SIMILAR if obj.get("kind") == SIMILAR else (_interpreted_play(obj) or "")[5:]
        if not source:
            return None
        where, count = obj.get("where"), obj.get("count")
        ok = isinstance(count, int) and not isinstance(count, bool) and 1 <= count <= QUEUE_MAX_COUNT
        return " ".join(["queue", *([where] if where in WHERES else []), *([str(count)] if ok else []), source])
    if action == "ask":
        fallback = _interpreted_play(obj)
        question = obj.get("question")
        question = " ".join(question.split()) if isinstance(question, str) else ""
        if not question or len(question) > ASK_MAX_CHARS:
            return fallback
        return {"ask": question, "fallback": fallback}
    return _INTERPRETED.get(action)
