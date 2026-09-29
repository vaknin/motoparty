import pytest

from conftest import load_fixture
from motoparty_peer.commands import normalise, parse_command, wake

CASES = load_fixture("commands.json")["cases"]
WAKE_CASES = load_fixture("wake.json")["cases"]


@pytest.mark.parametrize("case", CASES, ids=lambda c: repr(c["text"]))
def test_fixture(case):
    assert parse_command(case["text"]) == case["expect"]


@pytest.mark.parametrize("case", WAKE_CASES, ids=lambda c: repr(c["text"]))
def test_wake_fixture(case):
    assert wake(case["text"]) == case["expect"]


@pytest.mark.parametrize(
    "text, expect",
    [
        ("okay moto party hang up", "hang up"),
        ("Moto party… volume up", "volume up"),
        ("please motoparty pause", None),  # only hey/ok/okay precede the wake word
        ("motor", None),
        ("party", None),
    ],
)
def test_wake_extra(text, expect):
    assert wake(text) == expect


def test_wake_then_parse():
    assert parse_command(wake("Hey Moto Party, end talk please")) == {"action": "end"}


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
