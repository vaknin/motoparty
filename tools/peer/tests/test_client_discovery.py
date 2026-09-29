"""Client discovery options (--no-mdns / --host). The discovery itself is in test_discovery.py."""

import pytest

from motoparty_peer import discovery
from motoparty_peer.cli import build_parser
from motoparty_peer.client import Client


def _client(tmp_path, *argv):
    args = build_parser().parse_args(["client", "--no-audio", "--cache-dir", str(tmp_path), *argv])
    return Client(args)


def test_default_browses_and_sweeps_after_3_s(tmp_path):
    d = _client(tmp_path).discovery
    assert d.mdns and d.sweep_delay == discovery.SWEEP_DELAY == 3.0 and d.port == 47800
    assert d.backoff == 10.0 and d.prefer is None


def test_no_mdns_skips_bonjour_and_sweeps_at_once(tmp_path):
    d = _client(tmp_path, "--no-mdns", "--port", "47811").discovery
    assert not d.mdns and d.sweep_delay == 0.0 and d.port == 47811


def test_host_and_no_mdns_are_mutually_exclusive(capsys):
    with pytest.raises(SystemExit) as e:
        build_parser().parse_args(["client", "--host", "192.168.1.100", "--no-mdns"])
    assert e.value.code == 2
    assert "not allowed with" in capsys.readouterr().err
