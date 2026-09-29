"""PROTOCOL.md "Discovery": probes, the sweep, candidate racing, backoff, preference, Bonjour.

Everything here runs on loopback. The Bonjour tests announce on 127.0.0.1 only with a 10 s
TTL; the one that uses the real network is opt-in (MOTOPARTY_LAN_TESTS=1), because a test
service left in the phones' mDNS caches makes the apps chase a host that is not there.
"""

import asyncio
import os
import time
import uuid

import pytest

from motoparty_peer import discovery
from motoparty_peer.protocol import encode_frame

TEST_TTL = 10  # seconds: a killed test leaves nothing stale for long
LAN_TESTS = os.environ.get("MOTOPARTY_LAN_TESTS") == "1"


def host_hello(name: str) -> dict:
    return {"t": "hello", "proto": 1, "role": "host", "name": name, "voicePort": 1, "httpPort": 2}


class FakeHost:
    """A loopback TCP server that records, per connection, every byte the peer sent.

    mode "host": sends a host hello after `delay`; "silent": accepts and says nothing (a stale
    candidate whose port is open); "close": accepts and closes at once; "junk": not Motoparty."""

    def __init__(self, name: str = "H", mode: str = "host", delay: float = 0.0) -> None:
        self.name, self.mode, self.delay = name, mode, delay
        self.accepted = 0
        self.received: list[bytes] = []
        self.server: asyncio.Server | None = None
        self.ip = ""
        self.port = 0

    async def start(self, ip: str = "127.0.0.1", port: int = 0) -> "FakeHost":
        self.server = await asyncio.start_server(self._on, ip, port)
        self.ip, self.port = self.server.sockets[0].getsockname()[:2]
        return self

    async def _on(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        self.accepted += 1
        if self.mode == "close":
            writer.close()
            return
        try:
            if self.mode == "host":
                await asyncio.sleep(self.delay)
                writer.write(encode_frame(host_hello(self.name)))
            elif self.mode == "junk":
                writer.write(b"SSH-2.0-OpenSSH_9.9\r\n")
            await writer.drain()
        except ConnectionError:
            pass
        data = b""
        try:
            while chunk := await asyncio.wait_for(reader.read(4096), 2):
                data += chunk
        except (asyncio.TimeoutError, ConnectionError):
            pass
        self.received.append(data)
        writer.close()

    async def close(self) -> None:
        self.server.close()
        await self.server.wait_closed()


def dead_port() -> int:
    """A loopback port nothing listens on (connection refused)."""
    import socket  # noqa: PLC0415

    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def fake_browser(*results: tuple[float, discovery.BonjourResult]):
    """A Browser factory that reports `results`, each after its delay, from another thread."""
    import threading  # noqa: PLC0415

    class B:
        def __init__(self, cb):
            self.timers = [threading.Timer(d, cb, (r,)) for d, r in results]
            for t in self.timers:
                t.start()
            B.made += 1

        def close(self):
            for t in self.timers:
                t.cancel()

    B.made = 0
    return B


def bj(name: str, h: "FakeHost | tuple[str, int]") -> discovery.BonjourResult:
    ip, port = (h.ip, h.port) if isinstance(h, FakeHost) else h
    return discovery.BonjourResult(name, ip, port, {"proto": "1", "voice": "1", "http": "2"})


def no_sweep(**kw) -> dict:
    return {"sweep_delay": 1000.0, "candidates": list, **kw}


# ---------------------------------------------------------------- sweep and probe


def test_sweep_candidates_cover_the_24_minus_self():
    c = discovery.sweep_candidates(["192.168.43.17"])
    assert len(c) == 253
    assert "192.168.43.17" not in c and "192.168.43.1" in c and "192.168.43.254" in c
    assert "192.168.43.0" not in c and "192.168.43.255" not in c


def test_probe_sends_no_bytes():
    async def go():
        h = await FakeHost("H").start()
        hello = await discovery.probe(h.ip, h.port, 1.0, 1.0)
        await asyncio.sleep(0.1)
        await h.close()
        return hello, h

    hello, h = asyncio.run(go())
    assert hello["name"] == "H"
    assert h.received == [b""]  # no client hello: it would replace the host's client


def test_sweep_finds_the_host_skips_other_services_and_sends_nothing():
    async def go():
        h = await FakeHost("H").start("127.0.0.1")
        j = await FakeHost(mode="junk").start("127.0.0.2", h.port)
        cands = ["127.0.0.3", "127.0.0.2", "127.0.0.1", "127.0.0.4"]
        won = await discovery.sweep(cands, h.port, parallel=64)
        await asyncio.sleep(0.1)
        await h.close()
        await j.close()
        return won, h, j

    won, h, j = asyncio.run(go())
    assert won is not None and won[0] == "127.0.0.1" and won[1]["name"] == "H"
    assert h.received == [b""] and j.received == [b""]


def test_sweep_nothing_found():
    async def go():
        return await discovery.sweep(["127.0.0.5", "127.0.0.6"], 1)

    assert asyncio.run(go()) is None


# ---------------------------------------------------------------- racing Bonjour candidates


def test_stale_candidates_lose_to_the_real_host():
    """Two stale Bonjour results arrive first (one port open but mute, one closed); the real
    host, reported last, still wins, without waiting out the mute one's hello timeout."""

    async def go():
        mute = await FakeHost(mode="silent").start("127.0.0.2")
        real = await FakeHost("Pixel").start("127.0.0.1")
        dead = ("127.0.0.3", dead_port())
        browser = fake_browser((0.0, bj("peer-test-1", mute)), (0.0, bj("peer-test-2", dead)),
                               (0.1, bj("Pixel", real)))
        d = discovery.Discovery(port=real.port, browser=browser, **no_sweep())
        t0 = time.monotonic()
        found = await d.find(timeout=5)
        took = time.monotonic() - t0
        await asyncio.sleep(0.1)
        for s in (mute, real):
            await s.close()
        return d, found, took, mute, real, dead

    d, found, took, mute, real, dead = asyncio.run(go())
    assert found is not None
    assert (found.ip, found.port, found.via) == ("127.0.0.1", real.port, "bonjour")
    assert found.hello["name"] == "Pixel" and found.bonjour.name == "Pixel"
    assert took < 0.9  # probes run in parallel: the mute candidate's 1 s hello timeout didn't block
    assert d.backed_off(*dead)
    assert mute.received == [b""] and real.received == [b""]  # probes sent nothing


def test_client_hello_only_on_the_winner(tmp_path):
    """Client._connect: probes are silent; the one real handshake goes to the winner."""
    from motoparty_peer.cli import build_parser  # noqa: PLC0415
    from motoparty_peer.client import Client  # noqa: PLC0415

    async def go():
        mute = await FakeHost(mode="silent").start("127.0.0.2")
        real = await FakeHost("Pixel").start("127.0.0.1")
        args = build_parser().parse_args(["client", "--no-audio", "--cache-dir", str(tmp_path),
                                          "--name", "N", "--port", str(real.port)])
        c = Client(args)
        c.discovery.browser = fake_browser((0.0, bj("stale", mute)), (0.05, bj("Pixel", real)))
        c.discovery.sweep_delay = 1000.0
        conn = await c._connect()
        assert conn is not None and conn.ip == "127.0.0.1"
        conn.writer.close()
        await asyncio.sleep(0.2)
        for s in (mute, real):
            await s.close()
        return c, mute, real

    c, mute, real = asyncio.run(go())
    assert mute.received == [b""]
    assert len(real.received) == 2 and real.received[0] == b""  # the probe, then the link
    assert b'"role":"client"' in real.received[1] and b'"name":"N"' in real.received[1]
    assert c.discovery.prefer == "Pixel"


# ---------------------------------------------------------------- backoff


def test_failed_candidate_is_backed_off():
    async def go(backoff: float, run_for: float):
        h = await FakeHost(mode="close").start("127.0.0.1")
        d = discovery.Discovery(port=h.port, backoff=backoff,
                                browser=fake_browser((0.0, bj("stale", h))), **no_sweep())
        assert await d.find(timeout=run_for) is None
        first = h.accepted
        assert await d.find(timeout=0.3) is None  # the table survives into the next find()
        await h.close()
        return first, h.accepted - first

    # 10 s: probed once, and not again by a find() right after
    assert asyncio.run(go(10.0, 1.6)) == (1, 0)
    # 0.6 s: re-probed once the backoff ran out (retries are checked every 0.5 s)
    first, _ = asyncio.run(go(0.6, 1.6))
    assert first in (2, 3)


def test_sweep_skips_backed_off_addresses():
    async def go():
        h = await FakeHost("H").start("127.0.0.1")
        d = discovery.Discovery(port=h.port, mdns=False, sweep_delay=0.0, candidates=lambda: ["127.0.0.1"])
        d.mark_failed("127.0.0.1", h.port)
        none = await d.find(timeout=0.5)
        d.failed.clear()
        found = await d.find(timeout=2)
        await h.close()
        return none, found, h.accepted

    none, found, accepted = asyncio.run(go())
    assert none is None and found is not None and found.via == "sweep" and accepted == 1


# ---------------------------------------------------------------- preference and the sweep


def _two_hosts(prefer):
    async def go():
        a = await FakeHost("A").start("127.0.0.1")
        b = await FakeHost("B", delay=0.1).start("127.0.0.2")
        d = discovery.Discovery(port=a.port, prefer=prefer,
                                browser=fake_browser((0.0, bj("A", a)), (0.0, bj("B", b))), **no_sweep())
        t0 = time.monotonic()
        found = await d.find(timeout=3)
        took = time.monotonic() - t0
        await a.close()
        await b.close()
        return found.hello["name"], took

    return asyncio.run(go())


def test_last_linked_host_wins_a_tie():
    assert _two_hosts("B")[0] == "B"


def test_first_to_answer_wins_without_preference():
    name, took = _two_hosts(None)
    assert name == "A" and took < 0.09  # no grace wait without a preference


def test_absent_preferred_host_costs_only_the_grace():
    name, took = _two_hosts("C")
    assert name == "A" and took < discovery.PREFER_GRACE + 0.2


def test_sweep_joins_after_the_delay_alongside_bonjour():
    async def go():
        mute = await FakeHost(mode="silent").start("127.0.0.2")
        real = await FakeHost("H").start("127.0.0.1")
        d = discovery.Discovery(port=real.port, sweep_delay=0.3, candidates=lambda: ["127.0.0.1"],
                                browser=fake_browser((0.0, bj("stale", mute))))
        t0 = time.monotonic()
        found = await d.find(timeout=3)
        took = time.monotonic() - t0
        await mute.close()
        await real.close()
        return found, took

    found, took = asyncio.run(go())
    assert found.via == "sweep" and found.ip == "127.0.0.1"
    assert 0.3 <= took < 0.9


def test_no_mdns_never_browses():
    async def go():
        h = await FakeHost("H").start("127.0.0.1")
        browser = fake_browser()
        d = discovery.Discovery(port=h.port, mdns=False, sweep_delay=0.0, browser=browser,
                                candidates=lambda: ["127.0.0.2", "127.0.0.1"])
        found = await d.find(timeout=2)
        await h.close()
        return found, browser.made

    found, made = asyncio.run(go())
    assert found.via == "sweep" and made == 0


# ---------------------------------------------------------------- Bonjour (real zeroconf)


async def _advertise_and_browse(name: str, addrs: list[str], interfaces: list[str] | None):
    adv = discovery.Advertiser(name, 47899, 47898, 47897, addrs, interfaces=interfaces, ttl=TEST_TTL)
    await adv.start()
    try:
        return await asyncio.to_thread(discovery.browse, 5, name, interfaces)
    finally:
        await adv.close()


def test_bonjour_advertise_and_browse_on_loopback():
    """Announced on 127.0.0.1 only (checked with an mDNS listener: nothing reaches wlp1s0)."""
    name = f"peer-test-{uuid.uuid4().hex[:8]}"
    try:
        found = asyncio.run(_advertise_and_browse(name, ["127.0.0.1"], ["127.0.0.1"]))
    except OSError as e:
        pytest.skip(f"loopback multicast not usable here: {e}")
    if not found:
        pytest.skip("loopback multicast not usable here")
    (r,) = found
    assert (r.name, r.address, r.port) == (name, "127.0.0.1", 47899)
    assert r.txt == {"proto": "1", "voice": "47898", "http": "47897"}


def test_discovery_over_loopback_bonjour_prefers_the_live_host():
    """Real zeroconf on 127.0.0.1: a stale announcement (nothing listening) and a live one."""
    tag = uuid.uuid4().hex[:8]

    async def go():
        real = await FakeHost("Live").start("127.0.0.1")
        stale = discovery.Advertiser(f"peer-test-stale-{tag}", dead_port(), 1, 2, ["127.0.0.1"],
                                     interfaces=["127.0.0.1"], ttl=TEST_TTL)
        live = discovery.Advertiser(f"peer-test-live-{tag}", real.port, 1, 2, ["127.0.0.1"],
                                    interfaces=["127.0.0.1"], ttl=TEST_TTL)
        await stale.start()
        await live.start()

        def browser(cb):
            return discovery.Browser(lambda r: tag in r.name and cb(r), interfaces=["127.0.0.1"])

        try:
            d = discovery.Discovery(port=real.port, browser=browser, **no_sweep())
            return await d.find(timeout=8)
        finally:
            await stale.close()
            await live.close()
            await real.close()

    try:
        found = asyncio.run(go())
    except OSError as e:
        pytest.skip(f"loopback multicast not usable here: {e}")
    if found is None:
        pytest.skip("loopback multicast not usable here")
    assert found.hello["name"] == "Live" and found.bonjour.name == f"peer-test-live-{tag}"


@pytest.mark.skipif(not LAN_TESTS, reason="announces on the real LAN; opt in with MOTOPARTY_LAN_TESTS=1 "
                    "(phones on it may cache the test service for its TTL)")
def test_bonjour_advertise_and_browse_on_the_lan():
    name = f"peer-test-{uuid.uuid4().hex[:8]}"
    addrs = discovery.local_ipv4s()
    if not addrs:
        pytest.skip("no IPv4 interface for mDNS")
    found = asyncio.run(_advertise_and_browse(name, addrs, None))
    if not found:
        pytest.skip("mDNS multicast not usable here")
    (r,) = found
    assert r.port == 47899
    assert r.txt == {"proto": "1", "voice": "47898", "http": "47897"}
    assert r.address in addrs
