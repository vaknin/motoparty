import pytest

from conftest import load_fixture
from motoparty_peer.commands import (
    FIRST_PHRASE_MS,
    FirstPhraseGate,
    command_text,
    normalise,
    parse_command,
)

CASES = load_fixture("commands.json")["cases"]
FIRST_PHRASE = load_fixture("first_phrase.json")


@pytest.mark.parametrize("case", CASES, ids=lambda c: repr(c["text"]))
def test_fixture(case):
    assert parse_command(case["text"]) == case["expect"]


@pytest.mark.parametrize("case", FIRST_PHRASE["cases"], ids=lambda c: c["name"])
def test_first_phrase_fixture(case):
    gate = FirstPhraseGate(case["role"], live_ms=100_000)  # any earcon time: only the offset counts
    got = [gate.phrase(p["text"], 100_000 + p["atMs"]) for p in case["phrases"]]
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
