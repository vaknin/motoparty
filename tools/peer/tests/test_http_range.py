import pytest

from motoparty_peer.host import parse_range


@pytest.mark.parametrize(
    "header, expect",
    [
        ("bytes=0-1", (0, 1)),
        ("bytes=0-", (0, 99)),
        ("bytes=10-2000", (10, 99)),
        ("bytes=-10", (90, 99)),
        ("bytes=-1000", (0, 99)),
        ("bytes=100-", "unsatisfiable"),
        ("bytes=5-4", "unsatisfiable"),
        ("bytes=-0", "unsatisfiable"),
        ("bytes=0-1,5-6", None),
        ("items=0-1", None),
    ],
)
def test_parse_range(header, expect):
    assert parse_range(header, 100) == expect
