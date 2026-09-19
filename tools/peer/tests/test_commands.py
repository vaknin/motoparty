import pytest

from conftest import load_fixture
from motoparty_peer.commands import normalise, parse_command

CASES = load_fixture("commands.json")["cases"]


@pytest.mark.parametrize("case", CASES, ids=lambda c: repr(c["text"]))
def test_fixture(case):
    assert parse_command(case["text"]) == case["expect"]


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
