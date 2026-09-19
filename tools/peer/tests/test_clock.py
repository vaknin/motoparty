import pytest

from conftest import load_fixture
from motoparty_peer.clock import ClockEstimator

VEC = load_fixture("clock.json")


def test_clock_fixture_steps_and_conversions():
    est = ClockEstimator()
    for i, step in enumerate(VEC["steps"]):
        est.add(*step["sample"])
        assert est.offset == pytest.approx(step["expectOffset"]), f"step {i}"
    for c in VEC["conversions"]:
        assert est.host_to_local(c["host"]) == pytest.approx(c["local"])
        assert est.local_to_host(c["local"]) == pytest.approx(c["host"])


def test_negative_rtt_does_not_enter_window():
    est = ClockEstimator()
    assert est.add(1000, 5010, 5011, 1020) is not None
    assert est.add(4000, 7990, 7991, 3985) is None
    assert est.offset == pytest.approx(4000.5)


def test_no_samples():
    est = ClockEstimator()
    assert not est.ready and est.offset is None
    with pytest.raises(RuntimeError):
        est.host_to_local(1)
