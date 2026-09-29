import asyncio
import json

import pytest

from conftest import load_fixture
from motoparty_peer.protocol import (
    MAX_FRAME,
    FatalFrame,
    FrameDecoder,
    FrameTooLarge,
    ProtocolError,
    check_length,
    decode_message,
    encode_frame,
    encode_message,
    frame,
    is_known,
    read_frame,
)

MESSAGES = load_fixture("control/messages.json")["messages"]
FRAMING = load_fixture("control/framing.json")


def test_every_type_has_a_vector():
    from motoparty_peer.protocol import KNOWN_TYPES

    assert {m["t"] for m in MESSAGES} == set(KNOWN_TYPES)


@pytest.mark.parametrize("msg", MESSAGES, ids=lambda m: m["t"])
def test_message_round_trip(msg):
    raw = json.dumps(msg, ensure_ascii=False).encode()
    decoded = decode_message(raw)
    assert decoded == msg
    again = decode_message(encode_message(decoded))
    assert again == msg


@pytest.mark.parametrize("case", FRAMING["valid"], ids=lambda c: c["json"][:20])
def test_valid_frames(case):
    data = bytes.fromhex(case["hex"])
    bodies = FrameDecoder().feed(data)
    assert len(bodies) == 1
    assert json.loads(bodies[0]) == json.loads(case["json"])
    assert decode_message(bodies[0]) == json.loads(case["json"])
    # our encoder produces exactly these bytes (compact separators, raw UTF-8)
    assert encode_frame(json.loads(case["json"])) == data


def test_frames_split_across_reads():
    data = b"".join(bytes.fromhex(c["hex"]) for c in FRAMING["valid"])
    dec = FrameDecoder()
    out = []
    for b in data:
        out += dec.feed(bytes([b]))
    assert [json.loads(o) for o in out] == [json.loads(c["json"]) for c in FRAMING["valid"]]


@pytest.mark.parametrize("case", FRAMING["invalid"], ids=lambda c: c["why"])
def test_invalid_header_rejected_before_body(case):
    header = bytes.fromhex(case["hex"])
    with pytest.raises(FrameTooLarge):
        check_length(header)
    with pytest.raises(FrameTooLarge):
        FrameDecoder().feed(header)  # no body bytes at all: must still reject

    async def go():
        r = asyncio.StreamReader()
        r.feed_data(header)  # body never arrives; must not wait for it
        await asyncio.wait_for(read_frame(r), 1)

    with pytest.raises(FrameTooLarge):
        asyncio.run(go())


def test_max_frame_boundary():
    assert check_length((MAX_FRAME).to_bytes(4, "big")) == 65536
    with pytest.raises(FrameTooLarge):
        check_length((MAX_FRAME + 1).to_bytes(4, "big"))
    with pytest.raises(FrameTooLarge):
        frame(b"x" * (MAX_FRAME + 1))
    assert len(frame(b"x" * MAX_FRAME)) == MAX_FRAME + 4


@pytest.mark.parametrize("case", FRAMING["unknown"], ids=lambda c: c["_doc"])
def test_unknown_types_and_fields(case):
    msg = decode_message(case["json"].encode())
    src = json.loads(case["json"])
    if src["t"] == "future.thing":
        assert not is_known(msg)
        assert msg == {"t": "future.thing"}
    else:
        assert is_known(msg)
        assert msg == {"t": "ping", "id": 1, "t0": 2}  # extra field dropped


@pytest.mark.parametrize(
    "raw, why",
    [
        ('{"t":"ping","id":1}', "missing required"),
        ('{"t":"ping","id":1.0,"t0":2}', "float for int"),
        ('{"t":"ping","id":true,"t0":2}', "bool for int"),
        ('{"t":"bye","reason":null}', "null optional"),
        ('{"t":"announce","text":"x","earcon":null}', "null optional"),
        ('{"t":"talk.open","by":"passenger"}', "bad enum"),
        ('{"t":"talk.close","by":"host","reason":"bored"}', "bad enum"),
        ('{"t":"music.control","action":"volume_up"}', "bad enum"),
        ('{"t":"music.control","action":"volumeUp"}', "volume is local, not a wire action"),
        ('{"t":"music.control","action":"volumeDown"}', "volume is local, not a wire action"),
        ('{"t":"hello","proto":1,"role":"host","name":"x"}', "host hello without ports"),
        ('{"t":"state","talk":false}', "state without queue"),
        ('{"t":"state","talk":false,"queue":[{"id":"a","title":"b"}]}', "queue item missing artist"),
        ('{"t":"state","talk":"no","queue":[]}', "string for bool"),
        ('[{"t":"bye"}]', "not an object"),
        ('{"type":"bye"}', "no t"),
        ('{"t":7}', "non-string t"),
        ("{not json", "invalid json"),
    ],
)
def test_malformed_messages_rejected(raw, why):
    with pytest.raises(ProtocolError):
        decode_message(raw.encode())


def test_enums_match_the_spec():
    """PROTOCOL.md: music.control has no volume actions; talk.close has "unavailable"."""
    from motoparty_peer.protocol import SCHEMAS

    assert SCHEMAS["music.control"]["action"][0] == ("pause", "resume", "next", "previous")
    assert set(SCHEMAS["talk.close"]["reason"][0]) == {"trigger", "link", "unavailable"}


@pytest.mark.parametrize("by", ["host", "client"])
def test_talk_close_unavailable_round_trips(by):
    msg = {"t": "talk.close", "by": by, "reason": "unavailable"}
    assert decode_message(encode_message(msg)) == msg


def test_every_malformed_vector_is_exercised():
    """Every fixture vector must be a distinct JSON body (no silently duplicated coverage)."""
    bodies = [c["json"] for c in FRAMING["malformed"]]
    assert len(set(bodies)) == len(bodies)
    assert '{"t":"music.control","action":"volumeUp"}' in bodies
    assert any(json.loads(b).get("t") == "talk.close" for b in bodies)


def test_invalid_utf8_rejected():
    with pytest.raises(ProtocolError):
        decode_message(b'{"t":"announce","text":"\xff"}')


def test_client_hello_ports_dropped():
    m = decode_message(b'{"t":"hello","proto":1,"role":"client","name":"x","voicePort":1}')
    assert m == {"t": "hello", "proto": 1, "role": "client", "name": "x"}


def test_encode_refuses_unknown_and_invalid():
    with pytest.raises(ProtocolError):
        encode_message({"t": "future.thing"})
    with pytest.raises(ProtocolError):
        encode_message({"t": "ping", "id": 1})


def test_optional_fields_omitted_not_null():
    body = encode_message({"t": "announce", "text": "hi"})
    assert body == b'{"t":"announce","text":"hi"}'


@pytest.mark.parametrize("case", FRAMING["malformed"], ids=lambda c: c["_doc"])
def test_malformed_vectors_are_dropped_not_fatal(case):
    with pytest.raises(ProtocolError) as e:
        decode_message(case["json"].encode())
    assert not isinstance(e.value, FatalFrame)


@pytest.mark.parametrize("case", FRAMING["fatal"], ids=lambda c: c["_doc"])
def test_fatal_vectors_close(case):
    bodies = FrameDecoder().feed(bytes.fromhex(case["hex"]))
    assert len(bodies) == 1
    with pytest.raises(FatalFrame):
        decode_message(bodies[0])
