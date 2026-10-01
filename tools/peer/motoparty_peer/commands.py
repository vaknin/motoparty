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


INTERPRET_TIMEOUT_MS = 6000
# The context window (PROTOCOL.md "Commands", Voice actions): this many upcoming tracks, numbered
# from 1, and played tracks, numbered from -1.
INTERPRET_UP_NEXT = 25
INTERPRET_PLAYED = 5
MAX_ACTIONS = 4
UNDO_MS = 600_000

ASK_MAX_CHARS = 80
ANSWER_MS = 10_000
ANSWER_GRACE_MS = 2000

PLAY_KINDS = (*KINDS, SIMILAR)
# Actions without fields: the canonical form is just {type}.
_BARE = ("pause", "resume", "next", "previous", "shuffle", "end", "restart", "clear", "undo",
         "volumeUp", "volumeDown")
REPEAT_MODES = ("off", "track", "queue")
TELL_ABOUT = ("track", "album", "next", "remaining", "previous")
# The actions after which the voice undo keeps the upcoming queue and the repeat mode as they were.
UNDOABLE = ("add", "remove", "move", "clear", "shuffle", "repeat")


def _int(v) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def _source(obj: dict) -> dict | None:
    """`kind`/`query` of a play, add or the ask fallback -> {kind[, query]}, or None (unusable):
    the query normalised as in "Commands", an unknown or missing kind is `song`, and `similar`
    takes no query."""
    kind = obj.get("kind")
    kind = kind if isinstance(kind, str) and kind in PLAY_KINDS else "song"
    if kind == SIMILAR:
        return {"kind": kind}
    query = obj.get("query")
    query = command_text(query) if isinstance(query, str) else ""
    return {"kind": kind, "query": query} if query else None


def _positions(at, up_next: int) -> list[int] | None:
    """A non-empty list of upcoming positions 1..up_next (duplicates count once, order kept)."""
    if not isinstance(at, list) or not at or not all(_int(p) and 1 <= p <= up_next for p in at):
        return None
    return list(dict.fromkeys(at))


def _action(obj, up_next: int, played: int) -> dict | None:
    """One answer action -> its canonical form, or None (dropped). `ask` and `none` are handled
    by the caller."""
    if not isinstance(obj, dict) or not isinstance(obj.get("type"), str):
        return None
    t = obj["type"]
    if t in _BARE:
        return {"type": t}
    if t == "play":
        src = _source(obj)
        return {"type": t, **src} if src else None
    if t == "add":
        src = _source(obj)
        if src is None:
            return None
        where = obj.get("where") if obj.get("where") in ("next", "instead") else "end"
        count = obj.get("count")
        ok = _int(count) and 1 <= count <= QUEUE_MAX_COUNT
        return {"type": t, **src, "where": where, **({"count": count} if ok else {})}
    if t == "remove":
        if isinstance(obj.get("at"), list) and obj["at"]:
            at = _positions(obj["at"], up_next)
            return {"type": t, "at": at} if at else None
        artist = obj.get("artist")
        artist = command_text(artist) if isinstance(artist, str) else ""
        return {"type": t, "artist": artist} if artist else None
    if t == "move":
        at, to = _positions(obj.get("at"), up_next), obj.get("to")
        return {"type": t, "at": at, "to": to} if at and _int(to) and to >= 1 else None
    if t == "jump":
        at = obj.get("at")
        ok = _int(at) and (1 <= at <= up_next or -played <= at <= -1)
        return {"type": t, "at": at} if ok else None
    if t == "seek":
        by, to = obj.get("by"), obj.get("to")
        if _int(by) and by != 0:
            return {"type": t, "by": by}
        return {"type": t, "to": to} if _int(to) and to >= 0 else None
    if t == "repeat":
        mode = obj.get("mode")
        return {"type": t, "mode": mode} if isinstance(mode, str) and mode in REPEAT_MODES else None
    if t == "tell":
        about = obj.get("about")
        return {"type": t, "about": about} if isinstance(about, str) and about in TELL_ABOUT else None
    return None


def _ask(obj: dict) -> dict | list[dict] | None:
    """An `ask` alone -> {"ask": <question>, "fallback": [<play>] or []}; with no usable question,
    its fallback as an action list, or None."""
    src = _source(obj)
    fallback = [{"type": "play", **src}] if src and src["kind"] != SIMILAR else []
    question = obj.get("question")
    question = " ".join(question.split()) if isinstance(question, str) else ""
    if not question or len(question) > ASK_MAX_CHARS:
        return fallback or None
    return {"ask": question, "fallback": fallback}


def interpretation_actions(answer: str, up_next: int = INTERPRET_UP_NEXT,
                           played: int = INTERPRET_PLAYED) -> list[dict] | dict | None:
    """The interpreter's answer (the model's text) and the sizes of the context window it was
    given -> the voice actions to run, in canonical form; a question `{"ask": <question>,
    "fallback": <a list of at most one play action>}`; or None = conversation (PROTOCOL.md
    "Commands", Voice actions and The clarifying question; vectors: fixtures/interpret.json)."""
    try:
        obj = json.loads(answer)
    except ValueError:
        return None
    if not isinstance(obj, dict) or not isinstance(obj.get("actions"), list):
        return None
    actions, ask = [], None
    for item in obj["actions"]:
        if isinstance(item, dict) and item.get("type") == "ask":
            ask = item if ask is None else ask
        elif (a := _action(item, up_next, played)) is not None:
            actions.append(a)
    if actions:
        return actions[:MAX_ACTIONS]  # `ask` next to other actions is dropped; they run
    return _ask(ask) if ask is not None else None


def to_actions(cmd: dict[str, str]) -> list[dict]:
    """A parsed grammar command (`parse_command`) -> the same thing as voice actions
    (PROTOCOL.md "Commands", Voice actions). An unparsed phrase is the empty list."""
    a = cmd["action"]
    if a == "play":
        return [{"type": "play", "kind": cmd["kind"], "query": cmd["query"]}]
    if a == "queue":
        src = {"kind": SIMILAR} if cmd["kind"] == SIMILAR else {"kind": cmd["kind"], "query": cmd["query"]}
        return [{"type": "add", **src, "where": cmd["where"], **({"count": int(cmd["count"])} if "count" in cmd else {})}]
    if a == "nowplaying":
        return [{"type": "tell", "about": "track"}]
    return [{"type": a}] if a in _BARE else []
