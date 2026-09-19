"""Finding the host: Bonjour browse, then the /24 TCP sweep (PROTOCOL.md "Discovery")."""

from __future__ import annotations

import asyncio
import ipaddress
import json
import queue
import socket
import subprocess
from dataclasses import dataclass

from .protocol import (
    PROTO_VERSION,
    SERVICE_TYPE,
    ProtocolError,
    decode_message,
    encode_frame,
    read_frame,
)

BROWSE_SECONDS = 3.0
SWEEP_PARALLEL = 64
SWEEP_CONNECT_TIMEOUT = 0.4
SWEEP_HELLO_TIMEOUT = 1.0  # not in the spec: how long a connected candidate gets to say hello

_VIRTUAL_IF_PREFIXES = ("lo", "docker", "br-", "veth", "virbr", "vnet", "podman", "cni", "flannel")


@dataclass(slots=True)
class BonjourResult:
    name: str
    address: str
    port: int
    txt: dict[str, str]


def browse(timeout: float = BROWSE_SECONDS, only: str | None = None) -> BonjourResult | None:
    """Blocking: first _motoparty._tcp instance with an IPv4 address, or None after timeout.

    ``only`` restricts the result to one instance name (used by the tests)."""
    from zeroconf import IPVersion, ServiceBrowser, ServiceListener, Zeroconf  # noqa: PLC0415

    results: queue.Queue[BonjourResult] = queue.Queue()

    class Listener(ServiceListener):
        def _resolve(self, zc: Zeroconf, type_: str, name: str) -> None:
            info = zc.get_service_info(type_, name, timeout=int(timeout * 1000))
            if info is None:
                return
            addrs = info.parsed_addresses(IPVersion.V4Only)
            if not addrs or info.port is None:
                return
            txt = {
                (k.decode(errors="replace") if isinstance(k, bytes) else str(k)):
                (v.decode(errors="replace") if isinstance(v, bytes) else "")
                for k, v in (info.properties or {}).items()
            }
            instance = name.removesuffix("." + type_)
            if only is None or instance == only:
                results.put(BonjourResult(instance, addrs[0], info.port, txt))

        def add_service(self, zc, type_, name):
            self._resolve(zc, type_, name)

        def update_service(self, zc, type_, name):
            self._resolve(zc, type_, name)

        def remove_service(self, zc, type_, name):
            pass

    zc = Zeroconf(ip_version=IPVersion.V4Only)
    try:
        ServiceBrowser(zc, SERVICE_TYPE, Listener())
        try:
            return results.get(timeout=timeout)
        except queue.Empty:
            return None
    finally:
        zc.close()


def local_ipv4s() -> list[str]:
    """IPv4 addresses of this machine's real interfaces (no loopback/containers/bridges)."""
    addrs: list[str] = []
    try:
        out = subprocess.run(["ip", "-j", "-4", "addr", "show", "up"], capture_output=True, text=True, timeout=2)
        for iface in json.loads(out.stdout or "[]"):
            if iface.get("ifname", "").startswith(_VIRTUAL_IF_PREFIXES):
                continue
            for a in iface.get("addr_info", []):
                if a.get("family") == "inet" and a.get("local"):
                    addrs.append(a["local"])
    except (OSError, ValueError, subprocess.SubprocessError):
        pass
    if not addrs:  # non-Linux or no iproute2: address of the default route
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.connect(("192.0.2.1", 9))  # TEST-NET, nothing is sent
                addrs.append(s.getsockname()[0])
        except OSError:
            pass
    return addrs


def sweep_candidates(own: list[str] | None = None) -> list[str]:
    """Every address of each own /24, excluding our own addresses."""
    own = local_ipv4s() if own is None else own
    seen: set[str] = set()
    out: list[str] = []
    for ip in own:
        net = ipaddress.ip_network(f"{ip}/24", strict=False)
        for host in net.hosts():
            h = str(host)
            if h not in own and h not in seen:
                seen.add(h)
                out.append(h)
    return out


def client_hello(name: str) -> dict:
    return {"t": "hello", "proto": PROTO_VERSION, "role": "client", "name": name}


def check_host_hello(msg: dict) -> None:
    if msg.get("t") != "hello":
        raise ProtocolError(f"expected hello first, got {msg.get('t')!r}")
    if msg["role"] != "host":
        raise ProtocolError(f"peer says role {msg['role']!r}, expected 'host'")
    if msg["proto"] != PROTO_VERSION:
        raise ProtocolError(f"peer speaks proto {msg['proto']}, we speak {PROTO_VERSION}")


@dataclass(slots=True)
class Connection:
    ip: str
    port: int
    reader: asyncio.StreamReader
    writer: asyncio.StreamWriter
    hello: dict  # the host's hello


async def handshake(ip: str, port: int, name: str, connect_timeout: float, hello_timeout: float) -> Connection:
    """TCP connect, send our hello, require a valid host hello back."""
    reader, writer = await asyncio.wait_for(asyncio.open_connection(ip, port), connect_timeout)
    try:
        sock = writer.get_extra_info("socket")
        if sock is not None:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        writer.write(encode_frame(client_hello(name)))
        await writer.drain()
        msg = decode_message(await asyncio.wait_for(read_frame(reader), hello_timeout))
        check_host_hello(msg)
        return Connection(ip, port, reader, writer, msg)
    except BaseException:
        writer.close()
        raise


async def sweep(candidates: list[str], port: int, name: str, parallel: int = SWEEP_PARALLEL,
                connect_timeout: float = SWEEP_CONNECT_TIMEOUT,
                hello_timeout: float = SWEEP_HELLO_TIMEOUT) -> Connection | None:
    """Try every candidate, `parallel` at a time; first valid host hello wins."""
    sem = asyncio.Semaphore(parallel)
    winner: asyncio.Future[Connection] = asyncio.get_running_loop().create_future()

    async def probe(ip: str) -> None:
        async with sem:
            if winner.done():
                return
            try:
                conn = await handshake(ip, port, name, connect_timeout, hello_timeout)
            except (OSError, asyncio.TimeoutError, asyncio.IncompleteReadError, ProtocolError):
                return
            if winner.done():
                conn.writer.close()
            else:
                winner.set_result(conn)

    tasks = [asyncio.create_task(probe(ip)) for ip in candidates]
    done_all = asyncio.gather(*tasks, return_exceptions=True)
    try:
        await asyncio.wait({winner, done_all}, return_when=asyncio.FIRST_COMPLETED)
    finally:
        for t in tasks:
            t.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
    return winner.result() if winner.done() and not winner.cancelled() else None


class Advertiser:
    """Bonjour registration of the host (instance name = device name). Use from asyncio."""

    def __init__(self, name: str, port: int, voice_port: int, http_port: int, addresses: list[str]) -> None:
        from zeroconf import ServiceInfo  # noqa: PLC0415

        host = socket.gethostname().split(".")[0] or "motoparty"
        self.info = ServiceInfo(
            SERVICE_TYPE,
            f"{name}.{SERVICE_TYPE}",
            port=port,
            properties={"proto": str(PROTO_VERSION), "voice": str(voice_port), "http": str(http_port)},
            server=f"{host}.local.",
            addresses=[socket.inet_aton(a) for a in addresses],
        )
        self._azc = None

    async def start(self) -> None:
        from zeroconf import IPVersion  # noqa: PLC0415
        from zeroconf.asyncio import AsyncZeroconf  # noqa: PLC0415

        self._azc = AsyncZeroconf(ip_version=IPVersion.V4Only)
        await self._azc.async_register_service(self.info, allow_name_change=True)

    async def close(self) -> None:
        if self._azc is None:
            return
        try:
            await self._azc.async_unregister_service(self.info)
        finally:
            await self._azc.async_close()
            self._azc = None
