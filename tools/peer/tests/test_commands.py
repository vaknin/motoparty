import json

import pytest

from conftest import load_fixture
from motoparty_peer.commands import (
    ANSWER_GRACE_MS,
    ANSWER_MS,
    ASK_MAX_CHARS,
    FIRST_PHRASE_MS,
    INTERPRET_TIMEOUT_MS,
    INTERPRET_PLAYED,
    INTERPRET_UP_NEXT,
    MAX_ACTIONS,
    QUEUE_MAX_COUNT,
    UNDO_MS,
    FirstPhraseGate,
    command_text,
    interpretation_actions,
    normalise,
    parse_command,
    to_actions,
)

CASES = load_fixture("commands.json")["cases"]
FIRST_PHRASE = load_fixture("first_phrase.json")


@pytest.mark.parametrize("case", CASES, ids=lambda c: repr(c["text"]))
def test_fixture(case):
    assert parse_command(case["text"]) == case["expect"]


@pytest.mark.parametrize("case", FIRST_PHRASE["cases"], ids=lambda c: c["name"])
def test_first_phrase_fixture(case):
    gate = FirstPhraseGate(case["role"], live_ms=100_000, interpret=case.get("interpret", False))  # any earcon time: only the offset counts
    ask_at, got = case.get("askAtMs"), []
    for p in case["phrases"]:
        if ask_at is not None and ask_at <= p["atMs"]:
            gate.ask(100_000 + ask_at)
            ask_at = None
        got.append(gate.phrase(p["text"], 100_000 + p["atMs"]))
    assert got == case["expect"]


def test_first_phrase_window_matches_fixture():
    assert FIRST_PHRASE["firstPhraseMs"] == FIRST_PHRASE_MS


def test_first_phrase_window_spends_the_first_phrase():
    # a phrase after the window spends it even though it parses; nothing later is a command
    gate = FirstPhraseGate("opener", live_ms=0)
    assert gate.phrase("next", FIRST_PHRASE_MS + 1) is None
    assert gate.why == f"after the {FIRST_PHRASE_MS} ms window"
    assert gate.phrase("next", 1) is None and gate.why == "the first phrase is spent"


def test_first_phrase_command_text_keeps_the_quote_mapping():
    gate = FirstPhraseGate("opener", live_ms=0)
    assert gate.phrase("Play Don\u2019t Stop Me Now!", 1000) == "play don't stop me now"


def test_first_phrase_rejects_unknown_role():
    with pytest.raises(ValueError):
        FirstPhraseGate("host")


def test_command_text():
    assert command_text("  Hey, PLAY   Abbey-Road please. ") == "hey play abbey road please"
    assert command_text("...") == ""


@pytest.mark.parametrize(
    "text, expect",
    [
        ("Hey, please play the Beatles", {"action": "play", "kind": "song", "query": "the beatles"}),
        ("play the song", {"action": "unknown"}),
        ("PLAY   ALBUM   שלום עולם!", {"action": "play", "kind": "album", "query": "שלום עולם"}),
        ("please please pause", {"action": "pause"}),
        ("pause please please", {"action": "unknown"}),  # only one trailing please is dropped
        ("volume-up", {"action": "volumeUp"}),
        ("play don't stop me now", {"action": "play", "kind": "song", "query": "don't stop me now"}),
    ],
)
def test_extra(text, expect):
    assert parse_command(text) == expect


def test_normalise_keeps_apostrophe_and_digits():
    assert normalise("What's 4U?") == ["what's", "4u"]


INTERPRET = load_fixture("interpret.json")


@pytest.mark.parametrize("case", INTERPRET["cases"], ids=lambda c: c["answer"] or "empty")
def test_interpretation_fixture(case):
    window = case.get("window", {"upNext": INTERPRET_UP_NEXT, "played": INTERPRET_PLAYED})
    assert interpretation_actions(case["answer"], window["upNext"], window["played"]) == case["expect"]


@pytest.mark.parametrize(
    "text, expect",
    [
        ("play album Play", [{"type": "play", "kind": "album", "query": "play"}]),
        ("queue next 3 artist moby", [{"type": "add", "kind": "artist", "query": "moby", "where": "next", "count": 3}]),
        ("queue instead similar", [{"type": "add", "kind": "similar", "where": "instead"}]),
        ("what's playing", [{"type": "tell", "about": "track"}]),
        ("over", [{"type": "end"}]),
        ("louder", [{"type": "volumeUp"}]),
        ("skip", [{"type": "next"}]),
        ("dance", []),
    ],
)
def test_grammar_commands_as_actions(text, expect):
    assert to_actions(parse_command(text)) == expect


def test_grammar_actions_are_canonical():
    # what the grammar makes is what the interpreter's validation would keep unchanged
    for case in CASES:
        actions = to_actions(case["expect"])
        if actions:
            assert interpretation_actions(json.dumps({"actions": actions})) == actions, case["text"]


def test_answer_constants_match_the_fixtures():
    assert (INTERPRET["askMaxChars"], INTERPRET["answerMs"], INTERPRET["answerGraceMs"]) == (ASK_MAX_CHARS, ANSWER_MS, ANSWER_GRACE_MS)
    assert FIRST_PHRASE["answerMs"] == ANSWER_MS


def test_interpret_constants_match_fixture():
    assert INTERPRET["interpretTimeoutMs"] == INTERPRET_TIMEOUT_MS
    assert (INTERPRET["interpretUpNext"], INTERPRET["interpretPlayed"]) == (INTERPRET_UP_NEXT, INTERPRET_PLAYED)
    assert (INTERPRET["maxActions"], INTERPRET["undoMs"], INTERPRET["queueMaxCount"]) == (MAX_ACTIONS, UNDO_MS, QUEUE_MAX_COUNT)
