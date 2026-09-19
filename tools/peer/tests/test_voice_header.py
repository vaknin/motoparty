import pytest

from conftest import load_fixture
from motoparty_peer.protocol import ProtocolError, VoicePacket, decode_voice

VEC = load_fixture("voice/header.json")


@pytest.mark.parametrize("case", VEC["valid"], ids=lambda c: c["hex"])
def test_valid(case):
    data = bytes.fromhex(case["hex"])
    pkt = decode_voice(data)
    assert pkt == VoicePacket(case["kind"], case["seq"], case["ts"], bytes.fromhex(case["payloadHex"]))
    assert pkt.encode() == data


@pytest.mark.parametrize("case", VEC["invalid"], ids=lambda c: c["why"])
def test_invalid(case):
    assert decode_voice(bytes.fromhex(case["hex"])) is None


def test_encode_rejects_bad_packets():
    with pytest.raises(ProtocolError):
        VoicePacket(3, 0, 0).encode()
    with pytest.raises(ProtocolError):
        VoicePacket(2, 0, 0, b"x").encode()


def test_keepalive_payload_ignored_on_decode():
    assert decode_voice(bytes.fromhex("4d0200010000014099")) == VoicePacket(2, 1, 320, b"")
