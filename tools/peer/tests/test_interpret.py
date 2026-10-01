"""PROTOCOL.md "Commands", Interpretation: the fake host with its table-driven stub interpreter."""

from __future__ import annotations

import asyncio
from pathlib import Path
from types import SimpleNamespace

from motoparty_peer.host import Host, TrackInfo
from motoparty_peer.protocol import PROTO_VERSION, validate_message
from motoparty_peer.protocol import now_ms

TABLE = {
    "put on something by moby": '{"actions":[{"type":"play","kind":"artist","query":"Moby"}]}',
    "i don't like this one": '{"actions":[{"type":"next"}]}',
    "turn it up": '{"actions":[{"type":"volumeUp"}]}',
    "are you cold": '{"actions":[{"type":"none"}]}',
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

ASK = '{"actions":[{"type":"ask","question":"Which Moby album?","kind":"artist","query":"Moby"}]}'
ASK_TABLE = {
    "play an album by moby": ASK,
    "put some music on": '{"actions":[{"type":"ask","question":"What would you like to hear?"}]}',
    "i don't care just play any album": '{"actions":[{"type":"play","kind":"album","query":"Moby"}]}',
    "never mind": '{"actions":[{"type":"none"}]}',
    "which what": '{"actions":[{"type":"ask","question":"Which one?"}]}',
    "louder": '{"actions":[{"type":"volumeUp"}]}',
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
        h = _host({**ASK_TABLE, "next": '{"actions":[{"type":"play","kind":"album","query":"Next Moby"}]}'})
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


# ---- voice actions (PROTOCOL.md "Commands", Voice actions) ----

def _queued(n: int = 5, table: dict | None = None) -> Host:
    """A host playing track 0 of a library of n + 3, with n upcoming and 2 played."""
    h = _host(table or {})
    lib = [TrackInfo(f"t{i}", Path(f"/nonexistent/{i}.m4a"), f"T{i}", "Moby" if i % 2 else "Coldplay", None, 60_000)
           for i in range(n + 3)]
    h.library, h.by_id = lib, {t.id: t for t in lib}
    h.history, h.track, h.queue = lib[:2], lib[2], lib[3:]
    h.music = {"id": "t2", "title": "T2", "artist": "Coldplay", "playing": False, "positionMs": 5000,
               "atHostTimeMs": now_ms(), "durationMs": 60_000}
    return h


def _ids(h: Host) -> list[str]:
    return [t.id for t in h.queue]


def _say(h: Host, phrase: str, answer: str) -> None:
    h.interpret_table[phrase] = answer
    h._open_talk("host")
    h._hear(phrase)


def test_remove_move_clear_and_undo():
    async def run():
        h = _queued()
        assert _ids(h) == ["t3", "t4", "t5", "t6", "t7"]
        _say(h, "drop the next two", '{"actions":[{"type":"remove","at":[1,2]}]}')
        assert not h.talk and _ids(h) == ["t5", "t6", "t7"] and _announces(h) == ["Removed 2 songs"]
        _say(h, "put that last", '{"actions":[{"type":"move","at":[1],"to":9}]}')
        assert _ids(h) == ["t6", "t7", "t5"] and _announces(h)[-1] == "Moved T5 by Moby to the end"
        _say(h, "no coldplay", '{"actions":[{"type":"remove","artist":"coldplay"}]}')
        assert _ids(h) == ["t7", "t5"] and _announces(h)[-1] == "Removed T6 by Coldplay"
        _say(h, "put it back", '{"actions":[{"type":"undo"}]}')
        assert _ids(h) == ["t6", "t7", "t5"] and _announces(h)[-1] == "Put back 1 song"
        _say(h, "put it back", '{"actions":[{"type":"undo"}]}')
        assert _announces(h)[-1] == "Nothing to undo"
        _say(h, "clear it and say what's next", '{"actions":[{"type":"clear"},{"type":"tell","about":"next"}]}')
        assert h.queue == [] and _announces(h)[-1] == "Cleared the queue. Nothing after this"
        assert h.sent[-1]["earcon"] == "ok"
    asyncio.run(run())


def test_positions_resolve_against_the_snapshot():
    async def run():
        h = _queued()
        h.interpret_delay_ms = 20
        _say(h, "drop the second", '{"actions":[{"type":"remove","at":[2]}]}')
        del h.queue[0]  # the queue changes while the model thinks
        await asyncio.sleep(0.05)
        assert _ids(h) == ["t5", "t6", "t7"]  # t4 went, not t5
        _say(h, "drop the second", '{"actions":[{"type":"remove","at":[2]}]}')
        h.queue.remove(h.by_id["t6"])
        await asyncio.sleep(0.05)
        assert _ids(h) == ["t5", "t7"] and _announces(h)[-1] == "Nothing to remove"
        assert h.sent[-1]["earcon"] == "error"
    asyncio.run(run())


def test_jump_back_seek_and_repeat():
    async def run():
        h = _queued()
        _say(h, "the one before last", '{"actions":[{"type":"jump","at":-2}]}')
        assert h.track.id == "t0" and _ids(h) == ["t3", "t4", "t5", "t6", "t7"] and h.history[-1].id == "t2"
        h.load_task.cancel()
        h = _queued()
        _say(h, "back thirty seconds and repeat this", '{"actions":[{"type":"seek","by":-30},{"type":"repeat","mode":"track"}]}')
        assert h.music["positionMs"] == 0 and _announces(h) == []
        st = h.state()
        assert st["music"]["repeat"] == "track" and validate_message(st) == st
        _say(h, "go to a minute ten", '{"actions":[{"type":"seek","to":70}]}')
        assert h.music["positionMs"] == 60_000
        _say(h, "repeat off", '{"actions":[{"type":"repeat","mode":"off"}]}')
        assert "repeat" not in h.state()["music"]
    asyncio.run(run())


def test_the_passengers_interpreted_volume_is_dropped_from_the_list():
    async def run():
        h = _queued()
        h.interpret_table["louder and skip"] = '{"actions":[{"type":"volumeUp"},{"type":"next"}]}'
        h._open_talk("client")
        await _client_says(h, "louder and skip")
        assert not h.talk and h.track.id == "t3"
        h.load_task.cancel()
    asyncio.run(run())


def test_music_edit_move():
    async def run():
        h = _queued()
        await h._on_message(h.current, validate_message({"t": "music.edit", "op": "move", "index": 3, "id": "t6", "to": 0}), now_ms())
        assert _ids(h) == ["t6", "t3", "t4", "t5", "t7"]
        await h._on_message(h.current, validate_message({"t": "music.edit", "op": "move", "index": 0, "id": "t6", "to": 99}), now_ms())
        assert _ids(h) == ["t3", "t4", "t5", "t7", "t6"]
        await h._on_message(h.current, validate_message({"t": "music.edit", "op": "move", "index": 0, "id": "stale", "to": 2}), now_ms())
        assert _ids(h) == ["t3", "t4", "t5", "t7", "t6"]
    asyncio.run(run())


def test_client_touch_repeat_sets_the_mode_and_keeps_the_voice_undo():
    """PROTOCOL.md "Repeat by touch": music.control repeat sets the mode, one state per change."""
    async def run():
        h = _queued()
        _say(h, "drop the next one", '{"actions":[{"type":"remove","at":[1]}]}')
        undo = h.undo
        assert undo is not None
        for mode, wire in (("queue", "queue"), ("track", "track"), ("off", None)):
            del h.sent[:]
            msg = validate_message({"t": "music.control", "action": "repeat", "mode": mode})
            await h._on_message(h.current, msg, now_ms())
            states = [m for m in h.sent if m["t"] == "state"]
            assert len(states) == 1 and states[0]["music"].get("repeat") == wire
            assert validate_message(states[0]) == states[0]
            del h.sent[:]
            await h._on_message(h.current, msg, now_ms())  # the same mode again changes nothing
            assert [m for m in h.sent if m["t"] == "state"] == []
        assert h.undo is undo
        _say(h, "put it back", '{"actions":[{"type":"undo"}]}')
        assert _announces(h)[-1] == "Put back 1 song"
    asyncio.run(run())


def test_repeat_at_the_end_of_a_track():
    async def run():
        h = _queued()
        h.music["playing"] = True
        h.repeat = "track"
        h._track_ended()
        plays = [m for m in h.sent if m["t"] == "music.play"]
        assert h.track.id == "t2" and plays[-1]["id"] == "t2" and plays[-1]["positionMs"] == 0
        assert not any(m["t"] == "music.next" for m in h.sent)
        h = _queued(0)
        h.music["playing"] = True
        h.repeat = "queue"
        h._track_ended()  # the queue starts again from the first track it still holds
        assert h.track.id == "t0" and _ids(h) == ["t1", "t2"] and h.history[-1].id == "t2"
        h.load_task.cancel()
    asyncio.run(run())
