"""PROTOCOL.md "Commands", Interpretation: the fake host with its table-driven stub interpreter."""

from __future__ import annotations

import asyncio
from pathlib import Path
from types import SimpleNamespace

from motoparty_peer.host import Host, TrackInfo
from motoparty_peer.protocol import PROTO_VERSION, validate_message
from motoparty_peer.protocol import now_ms

TABLE = {
    "put on something by moby": '{"action":"play","kind":"artist","query":"Moby"}',
    "i don't like this one": '{"action":"next"}',
    "turn it up": '{"action":"volumeUp"}',
    "are you cold": '{"action":"none"}',
}


def _host(table: dict | None = TABLE, client: bool = True) -> Host:
    h = Host(SimpleNamespace(name="t", track=None, verbose=False))
    t = TrackInfo("a", Path("/nonexistent/a.m4a"), "A", "Band", None, 1000)
    h.library, h.by_id, h.track = [t], {"a": t}, t
    h.loop = asyncio.get_running_loop()
    h.current = SimpleNamespace(name="c", ip="127.0.0.1") if client else None
    h.interpret_table = table
    h.voice_port, h.http_port = 47801, 47802
    h.sent = []
    h.send = lambda msg, conn=None, **kw: h.sent.append(msg)
    return h


def _announces(h: Host) -> list[str]:
    return [m["text"] for m in h.sent if m["t"] == "announce"]


async def _client_says(h: Host, text: str) -> None:
    await h._on_message(h.current, {"t": "command.text", "text": text, "lang": "en-US"}, now_ms())


def test_hello_says_interpret_only_with_a_table():
    async def run():
        on, off = _host().hello(), _host(None).hello()
        assert on["interpret"] is True and "interpret" not in off
        assert validate_message(on) == on
        # a client's hello never carries it
        assert "interpret" not in validate_message({"t": "hello", "proto": PROTO_VERSION, "role": "client", "name": "c", "interpret": True})
    asyncio.run(run())


def test_interpreted_play_from_the_client_ends_the_talk_silently():
    async def run():
        h = _host()
        h._open_talk("client")
        await _client_says(h, "put on something by moby")
        assert not h.talk
        assert {"t": "talk.close", "by": "client", "reason": "trigger"} in h.sent
        assert _announces(h) == []
    asyncio.run(run())


def test_conversation_from_the_client_leaves_the_talk_open_and_says_nothing():
    async def run():
        h = _host()
        for phrase in ("are you cold", "not in the table at all"):
            h._open_talk("client")
            del h.sent[:]
            await _client_says(h, phrase)
            assert h.talk and h.client_command_seen
            assert [m["t"] for m in h.sent if m["t"] in ("announce", "talk.close")] == []
            h._close_talk("client", "trigger")
    asyncio.run(run())


def test_interpreted_volume_from_the_client_is_ignored():
    async def run():
        h = _host()
        h._open_talk("client")
        await _client_says(h, "turn it up")
        assert h.talk and _announces(h) == []
    asyncio.run(run())


def test_without_a_table_an_unparsed_command_text_is_as_before():
    async def run():
        h = _host(None)
        h._open_talk("client")
        await _client_says(h, "put on something by moby")
        assert h.talk and _announces(h) == ["Didn't catch that"]
    asyncio.run(run())


def test_the_hosts_own_first_phrase_is_interpreted():
    async def run():
        h = _host()
        h._open_talk("host")
        h._hear("I don't like this one")
        assert not h.talk
        assert {"t": "talk.close", "by": "host", "reason": "trigger"} in h.sent
        h._open_talk("host")
        del h.sent[:]
        h._hear("are you cold")
        h._hear("next")  # the first phrase is spent
        assert h.talk and _announces(h) == []
    asyncio.run(run())


def test_solo_conversation_gets_didnt_catch_that():
    async def run():
        h = _host(client=False)
        h._open_talk("host")
        h._hear("are you cold")
        assert h.talk and _announces(h) == ["Didn't catch that"]
        h._hear("put on something by moby")
        assert not h.talk
    asyncio.run(run())


def test_an_answer_for_a_talk_that_closed_is_dropped():
    async def run():
        h = _host()
        h.interpret_delay_ms = 30
        h._open_talk("client")
        await _client_says(h, "i don't like this one")
        assert h.talk
        h._close_talk("client", "trigger")
        del h.sent[:]
        await asyncio.sleep(0.08)
        assert h.sent == []
        # and in time it is acted on
        h._open_talk("client")
        await _client_says(h, "i don't like this one")
        await asyncio.sleep(0.08)
        assert not h.talk
    asyncio.run(run())


# ---- the clarifying question ----

ASK = '{"action":"ask","question":"Which Moby album?","kind":"artist","query":"Moby"}'
ASK_TABLE = {
    "play an album by moby": ASK,
    "put some music on": '{"action":"ask","question":"What would you like to hear?"}',
    "i don't care just play any album": '{"action":"play","kind":"album","query":"Moby"}',
    "never mind": '{"action":"none"}',
    "which what": '{"action":"ask","question":"Which one?"}',
    "louder": '{"action":"volumeUp"}',
    "garbled": None,  # the interpreter fails (a timeout)
}
QUESTION = {"t": "announce", "text": "Which Moby album?", "ask": True}


def _closes(h: Host) -> list[dict]:
    return [m for m in h.sent if m["t"] == "talk.close"]


def test_a_question_keeps_the_talk_open_and_the_reply_is_a_second_command_text():
    async def run():
        h = _host(ASK_TABLE)
        h._open_talk("client")
        await _client_says(h, "play an album by moby")
        assert h.talk and QUESTION in h.sent and validate_message(dict(QUESTION)) == QUESTION
        await _client_says(h, "i don't care just play any album")
        assert not h.talk and _closes(h) == [{"t": "talk.close", "by": "client", "reason": "trigger"}]
        assert _announces(h) == ["Which Moby album?"]
    asyncio.run(run())


def test_only_one_reply_is_accepted_and_never_mind_plays_nothing():
    async def run():
        h = _host(ASK_TABLE)
        h._open_talk("client")
        await _client_says(h, "play an album by moby")
        await _client_says(h, "never mind")
        assert h.talk and _closes(h) == []
        await _client_says(h, "i don't care just play any album")  # a third command.text: ignored
        assert h.talk and _announces(h) == ["Which Moby album?"]
    asyncio.run(run())


def test_a_reply_that_settles_nothing_plays_the_fallback():
    async def run():
        for reply in ("which what", "garbled"):  # asks again; fails
            h = _host(ASK_TABLE)
            h._open_talk("client")
            await _client_says(h, "play an album by moby")
            await _client_says(h, reply)
            assert not h.talk and _announces(h) == ["Which Moby album?"], reply
        # with nothing to fall back on the talk just stays open, and nothing more is asked
        h = _host(ASK_TABLE)
        h._open_talk("client")
        await _client_says(h, "put some music on")
        await _client_says(h, "which what")
        assert h.talk and _announces(h) == ["What would you like to hear?"]
    asyncio.run(run())


def test_a_reply_is_interpreted_even_when_the_grammar_parses_it():
    async def run():
        h = _host({**ASK_TABLE, "next": '{"action":"play","kind":"album","query":"Next Moby"}'})
        h._open_talk("host")
        h._hear("play an album by moby")
        assert h.talk and h.music is None
        h._hear("next")  # a reply, not the `next` command
        assert not h.talk
        # the passenger's volume words as a reply: their own keys do it, nothing happens
        h = _host(ASK_TABLE)
        h._open_talk("client")
        await _client_says(h, "play an album by moby")
        await _client_says(h, "louder")
        assert h.talk and _announces(h) == ["Which Moby album?"]
    asyncio.run(run())


def test_no_reply_in_time_plays_the_fallback():
    async def run():
        h = _host(ASK_TABLE)
        h.answer_wait_ms = 30
        h._open_talk("host")
        h._hear("play an album by moby")
        assert h.talk
        await asyncio.sleep(0.08)
        assert not h.talk and _closes(h) == [{"t": "talk.close", "by": "host", "reason": "trigger"}]
        # no fallback: the talk stays open; and a reply after the wait is not one
        h = _host(ASK_TABLE)
        h.answer_wait_ms = 30
        h._open_talk("client")
        await _client_says(h, "put some music on")
        await asyncio.sleep(0.08)
        assert h.talk
        await _client_says(h, "i don't care just play any album")
        assert h.talk
    asyncio.run(run())


def test_the_wait_of_an_answered_or_closed_question_does_nothing():
    async def run():
        h = _host(ASK_TABLE)
        h.answer_wait_ms = 30
        h._open_talk("client")
        await _client_says(h, "play an album by moby")
        h._close_talk("client", "trigger")
        h._open_talk("client")
        del h.sent[:]
        await asyncio.sleep(0.08)
        assert h.talk and h.sent == []
    asyncio.run(run())


def test_solo_asks_once_per_talk_and_a_typed_ask_is_its_fallback():
    async def run():
        h = _host(ASK_TABLE, client=False)
        h._open_talk("host")
        h._hear("put some music on")
        h._hear("never mind")
        assert h.talk and _announces(h) == ["What would you like to hear?", "Didn't catch that"]
        h._hear("play an album by moby")  # a second ask in the talk: its fallback
        assert not h.talk
    asyncio.run(run())


def test_the_client_sends_its_reply_unparsed():
    from motoparty_peer.commands import ANSWER_MS, FirstPhraseGate

    gate = FirstPhraseGate("opener", 0, interpret=True)
    assert gate.phrase("play an album by moby", 1000) == "play an album by moby"
    gate.ask(2000)
    assert gate.phrase("", 3000) is None and gate.asked_ms == 2000
    assert gate.phrase("Louder!", 2000 + ANSWER_MS) == "louder"
    assert gate.phrase("next", 2000 + ANSWER_MS) is None
