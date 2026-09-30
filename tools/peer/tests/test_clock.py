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


def test_clock_fixture_step_reset():
    est = ClockEstimator()
    for i, step in enumerate(VEC["stepReset"]["steps"]):
        est.add(*step["sample"])
        assert est.offset == pytest.approx(step["expectOffset"]), f"step {i}"
    assert est.resets >= 1


def test_step_boundary_and_window_restart():
    est = ClockEstimator()
    est.add(0, 1000, 1000, 10)  # rtt 10, offset 995
    est.add(100, 1600, 1600, 110)  # rtt 10, offset 1495: 500 off, limit 505 -> kept
    assert est.resets == 0 and est.offset == pytest.approx(1495)  # tie on rtt: newest
    est.add(200, 2206, 2206, 210)  # offset 2001: 506 off > 505 -> window starts over
    assert est.resets == 1 and est.offset == pytest.approx(2001)
    est.add(300, 2306, 2306, 340)  # rtt 40, worse than the one kept sample
    assert est.offset == pytest.approx(2001)
    est.add(400, 2401, 2401, 1400)  # a slow pong: rtt 1000, offset 1501, limit 1000 -> kept
    assert est.resets == 1 and est.offset == pytest.approx(2001)
