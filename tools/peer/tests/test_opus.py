import math
import struct

import pytest

from motoparty_peer.audio import Tone
from motoparty_peer.opus import OpusDecoder, OpusEncoder, is_dtx_packet, is_voice_activity, version
from motoparty_peer.protocol import FRAME_SAMPLES, SAMPLE_RATE

SILENCE = bytes(FRAME_SAMPLES * 2)


def samples(pcm: bytes):
    return struct.unpack(f"<{len(pcm) // 2}h", pcm)


def goertzel_power(x, freq):
    w = 2 * math.pi * freq / SAMPLE_RATE
    c = 2 * math.cos(w)
    s1 = s2 = 0.0
    for v in x:
        s1, s2 = v + c * s1 - s2, s1
    return s1 * s1 + s2 * s2 - c * s1 * s2


def test_version():
    assert version().startswith("libopus")


def test_round_trip_tone():
    enc, dec = OpusEncoder(), OpusDecoder()
    tone = Tone(440, 0.3, on_ms=None)
    out, sizes = [], []
    for _ in range(50):  # 1 s
        pkt = enc.encode(tone.frame())
        sizes.append(len(pkt))
        pcm = dec.decode(pkt)
        assert len(pcm) == FRAME_SAMPLES * 2
        out += samples(pcm)
    # ~24 kbps: 60 bytes per 20 ms frame; allow the encoder's own rate control some room
    avg = sum(sizes[5:]) / len(sizes[5:])
    assert 30 <= avg <= 90, avg
    tail = out[SAMPLE_RATE // 2 :]  # skip codec warm-up
    rms = math.sqrt(sum(v * v for v in tail) / len(tail))
    assert 0.5 * 0.3 * 32767 / math.sqrt(2) < rms < 1.5 * 0.3 * 32767 / math.sqrt(2)
    p440 = goertzel_power(tail, 440)
    for other in (220, 660, 1000, 3000):
        assert p440 > 50 * goertzel_power(tail, other)


def test_fec_and_plc_produce_a_frame():
    enc, dec = OpusEncoder(), OpusDecoder()
    tone = Tone()
    pkts = [enc.encode(tone.frame()) for _ in range(20)]
    for p in pkts[:10]:
        dec.decode(p)
    # packet 10 lost: recover it from packet 11's in-band FEC, then decode 11 normally
    rec = dec.decode_fec(pkts[11])
    assert len(rec) == FRAME_SAMPLES * 2
    assert max(abs(v) for v in samples(rec)) > 1000  # FEC carried real audio
    assert len(dec.decode(pkts[11])) == FRAME_SAMPLES * 2
    assert len(dec.conceal()) == FRAME_SAMPLES * 2


def test_dtx_on_silence_and_activity_flag():
    enc = OpusEncoder()
    beeps = Tone()  # 400 ms on / 100 ms off
    active = [enc.encode(beeps.frame()) for _ in range(500)]  # 10 s
    assert all(not is_dtx_packet(p) for p in active)
    assert sum(map(is_voice_activity, active)) >= 350  # every beep is voice activity
    quiet = []
    for _ in range(100):  # 2 s of digital silence
        p = enc.encode(SILENCE)
        quiet.append((p, enc.in_dtx))
    skipped = [p for p, _ in quiet if is_dtx_packet(p)]
    cn = [p for p, in_dtx in quiet if in_dtx and not is_dtx_packet(p)]
    assert len(skipped) > 60, "DTX should suppress most silent frames"
    # libopus still emits comfort-noise updates while in DTX; their VAD flag is clear
    assert cn and not any(is_voice_activity(p) for p in cn)


def test_steady_tone_loses_vad_but_beeps_keep_it():
    enc = OpusEncoder()
    steady = Tone(on_ms=None)
    pk = [enc.encode(steady.frame()) for _ in range(250)]
    assert not any(is_voice_activity(p) for p in pk[100:])  # SILK adapted to it as noise
    assert all(not is_dtx_packet(p) for p in pk)  # ... yet the frames are not DTX


def test_sender_skips_comfort_noise_updates():
    from motoparty_peer.voice import VoiceSender

    sent = []
    s = VoiceSender(sent.append)
    tone = Tone()
    for _ in range(20):
        s.send_pcm(tone.frame())
    n_voice = len(sent)
    ts_before = s.ts
    for _ in range(100):
        s.send_pcm(SILENCE)
    assert n_voice == 20
    assert len(sent) - n_voice < 15  # only the DTX hangover, no periodic CN updates
    assert s.ts == (ts_before + 100 * FRAME_SAMPLES) & 0xFFFFFFFF  # ts runs on through DTX


def test_wrong_frame_size():
    with pytest.raises(ValueError):
        OpusEncoder().encode(b"\0" * 100)
