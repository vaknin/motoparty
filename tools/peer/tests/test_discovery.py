import asyncio
import uuid

import pytest

from motoparty_peer import discovery
from motoparty_peer.protocol import decode_message, encode_frame, read_frame


def test_sweep_candidates_cover_the_24_minus_self():
    c = discovery.sweep_candidates(["192.168.43.17"])
    assert len(c) == 253
    assert "192.168.43.17" not in c and "192.168.43.1" in c and "192.168.43.254" in c
    assert "192.168.43.0" not in c and "192.168.43.255" not in c


async def _servers():
    """A real-looking host on 127.0.0.1 and a non-Motoparty service on 127.0.0.2."""
    seen = []

    async def host(reader, writer):
        writer.write(encode_frame({"t": "hello", "proto": 1, "role": "host", "name": "H",
                                   "voicePort": 1, "httpPort": 2}))
        seen.append(decode_message(await read_frame(reader)))
        await asyncio.sleep(0.5)
        writer.close()

    async def junk(reader, writer):
        writer.write(b"SSH-2.0-OpenSSH_9.9\r\n")
        await asyncio.sleep(0.5)
        writer.close()

    h = await asyncio.start_server(host, "127.0.0.1", 0)
    port = h.sockets[0].getsockname()[1]
    j = await asyncio.start_server(junk, "127.0.0.2", port)
    return h, j, port, seen


def test_sweep_finds_the_host_and_skips_other_services():
    async def go():
        h, j, port, seen = await _servers()
        async with h, j:
            cands = ["127.0.0.3", "127.0.0.2", "127.0.0.1", "127.0.0.4"]
            conn = await discovery.sweep(cands, port, "sweeper", parallel=64)
            assert conn is not None
            assert conn.ip == "127.0.0.1" and conn.hello["name"] == "H"
            conn.writer.close()
            await asyncio.sleep(0.05)
        assert seen == [{"t": "hello", "proto": 1, "role": "client", "name": "sweeper"}]

    asyncio.run(go())


def test_sweep_nothing_found():
    async def go():
        return await discovery.sweep(["127.0.0.5", "127.0.0.6"], 1, "x")

    assert asyncio.run(go()) is None


def test_bonjour_advertise_and_browse():
    name = f"peer-test-{uuid.uuid4().hex[:8]}"
    addrs = discovery.local_ipv4s()
    if not addrs:
        pytest.skip("no IPv4 interface for mDNS")

    async def go():
        adv = discovery.Advertiser(name, 47899, 47898, 47897, addrs)
        await adv.start()
        try:
            return await asyncio.to_thread(discovery.browse, 5, name)
        finally:
            await adv.close()

    found = asyncio.run(go())
    if found is None:
        pytest.skip("mDNS multicast not usable here")
    assert found.port == 47899
    assert found.txt == {"proto": "1", "voice": "47898", "http": "47897"}
    assert found.address in addrs
