"""Client discovery path selection (--no-mdns / --host), with Bonjour and the sweep stubbed out."""

import asyncio

import pytest

from motoparty_peer import discovery
from motoparty_peer.cli import build_parser
from motoparty_peer.client import Client


def _client(tmp_path, *argv):
    args = build_parser().parse_args(["client", "--no-audio", "--cache-dir", str(tmp_path), *argv])
    return Client(args)


@pytest.fixture
def stubs(monkeypatch):
    calls = {"browse": 0, "sweep": []}
    sentinel = object()

    def browse(*_a, **_k):
        calls["browse"] += 1
        return None

    async def sweep(cands, port, name, **_k):
        calls["sweep"].append((cands, port, name))
        return sentinel

    monkeypatch.setattr(discovery, "browse", browse)
    monkeypatch.setattr(discovery, "sweep_candidates", lambda own=None: ["10.0.0.2", "10.0.0.3"])
    monkeypatch.setattr(discovery, "sweep", sweep)
    return calls, sentinel


def test_no_mdns_skips_bonjour_and_sweeps(tmp_path, stubs):
    calls, sentinel = stubs
    c = _client(tmp_path, "--no-mdns", "--name", "N")
    assert asyncio.run(c._connect()) is sentinel
    assert calls["browse"] == 0
    assert calls["sweep"] == [(["10.0.0.2", "10.0.0.3"], 47800, "N")]


def test_default_browses_first_then_sweeps(tmp_path, stubs):
    calls, sentinel = stubs
    c = _client(tmp_path)
    assert asyncio.run(c._connect()) is sentinel
    assert calls["browse"] == 1
    assert len(calls["sweep"]) == 1


def test_host_and_no_mdns_are_mutually_exclusive(capsys):
    with pytest.raises(SystemExit) as e:
        build_parser().parse_args(["client", "--host", "192.168.1.100", "--no-mdns"])
    assert e.value.code == 2
    assert "not allowed with" in capsys.readouterr().err
