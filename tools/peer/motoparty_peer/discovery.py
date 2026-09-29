"""Finding the host (PROTOCOL.md "Discovery"): every Bonjour result is only a candidate and is
probed in parallel; the /24 TCP sweep joins after 3 s. A probe sends nothing: it only reads the
host's hello, then closes. The real handshake (which sends the client hello) is done once, on
the winner."""

from __future__ import annotations

import asyncio
import ipaddress
import json
import queue
import socket
import subprocess
import time
from collections.abc import Callable
from dataclasses import dataclass, field

from .protocol import (
    CONTROL_PORT,
    PROTO_VERSION,
    SERVICE_TYPE,
    ProtocolError,
    decode_message,
    encode_frame,
    read_frame,
)

BROWSE_SECONDS = 3.0
BONJOUR_CONNECT_TIMEOUT = 3.0  # spec: TCP connect to a Bonjour candidate, name resolution included
SWEEP_DELAY = 3.0  # spec: the /24 sweep starts 3 s after discovery without a host
SWEEP_PARALLEL = 64
SWEEP_CONNECT_TIMEOUT = 0.4
HELLO_TIMEOUT = 1.0  # spec: after connecting, a candidate has 1 s to send the host hello
SWEEP_HELLO_TIMEOUT = HELLO_TIMEOUT
FAIL_BACKOFF = 10.0  # spec: a failed candidate is not probed again for 10 s
PREFER_GRACE = 0.25  # not in the spec: how long "at once" is when waiting for the preferred host
RETRY_TICK = 0.5  # how often Bonjour candidates whose backoff ran out are probed again

_VIRTUAL_IF_PREFIXES = ("lo", "docker", "br-", "veth", "virbr", "vnet", "podman", "cni", "flannel")

_PROBE_ERRORS = (OSError, asyncio.TimeoutError, asyncio.IncompleteReadError, ProtocolError)


@dataclass(slots=True)
class BonjourResult:
    name: str
    address: str
    port: int
    txt: dict[str, str]


def _interfaces(interfaces: list[str] | None):
    from zeroconf import InterfaceChoice  # noqa: PLC0415

    return InterfaceChoice.All if interfaces is None else list(interfaces)


class Browser:
    """Background Bonjour browse of _motoparty._tcp: ``on_result`` gets every resolved instance
    with an IPv4 address, on a zeroconf thread, each time it is added or updated.

    ``interfaces`` limits the browse to those local addresses (the tests use 127.0.0.1)."""

    def __init__(self, on_result: Callable[[BonjourResult], None], *, interfaces: list[str] | None = None,
                 only: str | None = None, resolve_timeout: float = BROWSE_SECONDS) -> None:
        from zeroconf import IPVersion, ServiceBrowser, ServiceListener, Zeroconf  # noqa: PLC0415

        class Listener(ServiceListener):
            def _resolve(self, zc: Zeroconf, type_: str, name: str) -> None:
                info = zc.get_service_info(type_, name, timeout=int(resolve_timeout * 1000))
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
                    on_result(BonjourResult(instance, addrs[0], info.port, txt))

            def add_service(self, zc, type_, name):
                self._resolve(zc, type_, name)

            def update_service(self, zc, type_, name):
                self._resolve(zc, type_, name)

            def remove_service(self, zc, type_, name):
                pass

        self._zc = Zeroconf(interfaces=_interfaces(interfaces), ip_version=IPVersion.V4Only)
        try:
            ServiceBrowser(self._zc, SERVICE_TYPE, Listener())
        except BaseException:
            self._zc.close()
            raise

    def close(self) -> None:
        self._zc.close()


def browse(timeout: float = BROWSE_SECONDS, only: str | None = None,
           interfaces: list[str] | None = None) -> list[BonjourResult]:
    """Blocking: every _motoparty._tcp instance seen within ``timeout`` (candidates, not hosts).

    ``only`` restricts the results to one instance name (used by the tests); with it the
    browse returns as soon as that instance resolves."""
    results: queue.Queue[BonjourResult] = queue.Queue()
    out: dict[str, BonjourResult] = {}
    b = Browser(results.put, interfaces=interfaces, only=only, resolve_timeout=timeout)
    try:
        deadline = time.monotonic() + timeout
        while (left := deadline - time.monotonic()) > 0:
            try:
                r = results.get(timeout=left)
            except queue.Empty:
                break
            out[r.name] = r
            if only is not None:
                break
    finally:
        b.close()
    return list(out.values())


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


async def probe(ip: str, port: int, connect_timeout: float, hello_timeout: float = HELLO_TIMEOUT) -> dict:
    """PROTOCOL.md "Discovery" 4: connect, read the first frame, close. Sends nothing (a client
    hello would replace the host's current client). Returns the host hello, or raises."""
    reader, writer = await asyncio.wait_for(asyncio.open_connection(ip, port), connect_timeout)
    try:
        msg = decode_message(await asyncio.wait_for(read_frame(reader), hello_timeout))
        check_host_hello(msg)
        return msg
    finally:
        writer.close()


async def sweep(candidates: list[str], port: int, parallel: int = SWEEP_PARALLEL,
                connect_timeout: float = SWEEP_CONNECT_TIMEOUT,
                hello_timeout: float = SWEEP_HELLO_TIMEOUT) -> tuple[str, dict] | None:
    """Probe every candidate, `parallel` at a time; the first valid host hello wins.

    Returns ``(ip, host hello)``; the caller then does the real :func:`handshake` with it."""
    sem = asyncio.Semaphore(parallel)
    winner: asyncio.Future[tuple[str, dict]] = asyncio.get_running_loop().create_future()

    async def one(ip: str) -> None:
        async with sem:
            if winner.done():
                return
            try:
                hello = await probe(ip, port, connect_timeout, hello_timeout)
            except _PROBE_ERRORS:
                return
            if not winner.done():
                winner.set_result((ip, hello))

    tasks = [asyncio.create_task(one(ip)) for ip in candidates]
    done_all = asyncio.gather(*tasks, return_exceptions=True)
    try:
        await asyncio.wait({winner, done_all}, return_when=asyncio.FIRST_COMPLETED)
    finally:
        for t in tasks:
            t.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
    return winner.result() if winner.done() and not winner.cancelled() else None


@dataclass(slots=True)
class Found:
    """A candidate that answered with a valid host hello (the connection is already closed)."""

    ip: str
    port: int
    hello: dict
    via: str  # "bonjour" | "sweep"
    bonjour: BonjourResult | None = None


BrowserFactory = Callable[[Callable[[BonjourResult], None]], object]  # returns something with .close()


@dataclass
class Discovery:
    """The client side of PROTOCOL.md "Discovery", reusable across reconnects (it keeps the
    backoff table and the name of the last host linked).

    ``find()`` browses and, after ``sweep_delay``, sweeps alongside; every Bonjour result and
    every sweep address is probed (:func:`probe`, nothing sent). The first valid host hello
    wins, unless another host named ``prefer`` answers within ``PREFER_GRACE``. A candidate
    that fails is not probed again for ``backoff`` seconds; Bonjour candidates are re-probed
    once their backoff runs out, for as long as ``find()`` runs."""

    port: int = CONTROL_PORT
    mdns: bool = True
    prefer: str | None = None  # hello name of the last host this client linked to
    sweep_delay: float = SWEEP_DELAY
    backoff: float = FAIL_BACKOFF
    bonjour_connect_timeout: float = BONJOUR_CONNECT_TIMEOUT
    sweep_connect_timeout: float = SWEEP_CONNECT_TIMEOUT
    hello_timeout: float = HELLO_TIMEOUT
    parallel: int = SWEEP_PARALLEL
    prefer_grace: float = PREFER_GRACE
    candidates: Callable[[], list[str]] | None = None  # sweep addresses (default: own /24s)
    browser: BrowserFactory | None = None  # default: a real zeroconf Browser
    log: Callable[[str], None] = lambda _m: None
    failed: dict[tuple[str, int], float] = field(default_factory=dict)  # key -> monotonic time of failure

    def backed_off(self, ip: str, port: int) -> bool:
        t = self.failed.get((ip, port))
        return t is not None and time.monotonic() - t < self.backoff

    def mark_failed(self, ip: str, port: int) -> None:
        self.failed[(ip, port)] = time.monotonic()

    async def find(self, timeout: float | None = None) -> Found | None:
        loop = asyncio.get_running_loop()
        answered: list[Found] = []
        got = asyncio.Event()
        bonjour: dict[tuple[str, int], BonjourResult] = {}
        probing: set[tuple[str, int]] = set()
        tasks: set[asyncio.Task] = set()
        sem = asyncio.Semaphore(self.parallel)

        async def run_probe(key: tuple[str, int], via: str, res: BonjourResult | None) -> None:
            try:
                if via == "sweep":
                    async with sem:
                        hello = await probe(*key, self.sweep_connect_timeout, self.hello_timeout)
                else:
                    hello = await probe(*key, self.bonjour_connect_timeout, self.hello_timeout)
            except _PROBE_ERRORS as e:
                self.mark_failed(*key)
                if via == "bonjour":
                    self.log(f"discovery: candidate {res.name!r} at {key[0]}:{key[1]} failed "
                             f"({type(e).__name__}: {e}); not probed again for {self.backoff:g} s")
                return
            finally:
                probing.discard(key)
            answered.append(Found(key[0], key[1], hello, via, res))
            got.set()

        def start_probe(key: tuple[str, int], via: str, res: BonjourResult | None = None) -> asyncio.Task | None:
            if key in probing or self.backed_off(*key):
                return None
            probing.add(key)
            t = asyncio.create_task(run_probe(key, via, res))
            tasks.add(t)
            t.add_done_callback(tasks.discard)
            return t

        def on_bonjour(res: BonjourResult) -> None:  # on the event loop
            key = (res.address, res.port)
            if key not in bonjour:
                self.log(f"discovery: bonjour candidate {res.name!r} at {res.address}:{res.port} txt={res.txt}")
            bonjour[key] = res
            start_probe(key, "bonjour", res)

        async def retry_bonjour() -> None:
            while True:
                await asyncio.sleep(RETRY_TICK)
                for key, res in list(bonjour.items()):
                    start_probe(key, "bonjour", res)

        async def sweeper() -> None:
            await asyncio.sleep(self.sweep_delay)
            while True:
                cands = (self.candidates or sweep_candidates)()
                self.log(f"discovery: sweeping {len(cands)} addresses on port {self.port}")
                round_ = [t for ip in cands if (t := start_probe((ip, self.port), "sweep"))]
                if round_:
                    await asyncio.gather(*round_, return_exceptions=True)
                await asyncio.sleep(1.0)  # addresses that failed stay backed off for `backoff`

        browser = None
        helpers = [asyncio.create_task(sweeper())]
        try:
            if self.mdns:
                self.log("discovery: browsing _motoparty._tcp")

                def from_thread(res: BonjourResult) -> None:
                    loop.call_soon_threadsafe(on_bonjour, res)

                factory = self.browser or (lambda cb: Browser(cb))
                browser = await asyncio.to_thread(factory, from_thread)
                helpers.append(asyncio.create_task(retry_bonjour()))
            try:
                await asyncio.wait_for(got.wait(), timeout)
            except asyncio.TimeoutError:
                return None
            if self.prefer is not None:
                deadline = loop.time() + self.prefer_grace
                while not any(f.hello["name"] == self.prefer for f in answered):
                    left = deadline - loop.time()
                    if left <= 0:
                        break
                    got.clear()
                    try:
                        await asyncio.wait_for(got.wait(), left)
                    except asyncio.TimeoutError:
                        break
            for f in answered:
                if f.hello["name"] == self.prefer:
                    return f
            return answered[0]
        finally:
            for t in [*helpers, *tasks]:
                t.cancel()
            await asyncio.gather(*helpers, *tasks, return_exceptions=True)
            if browser is not None:
                await asyncio.to_thread(browser.close)


class Advertiser:
    """Bonjour registration of the host (instance name = device name). Use from asyncio.

    ``interfaces`` limits the announcement to those local addresses (None = every interface);
    ``ttl`` (seconds) overrides zeroconf's record TTLs (120 s host / 75 min other), so a test
    that is killed before it unregisters leaves nothing stale for long."""

    def __init__(self, name: str, port: int, voice_port: int, http_port: int, addresses: list[str], *,
                 interfaces: list[str] | None = None, ttl: int | None = None) -> None:
        from zeroconf import ServiceInfo  # noqa: PLC0415

        host = socket.gethostname().split(".")[0] or "motoparty"
        ttls = {} if ttl is None else {"host_ttl": ttl, "other_ttl": ttl}
        self.info = ServiceInfo(
            SERVICE_TYPE,
            f"{name}.{SERVICE_TYPE}",
            port=port,
            properties={"proto": str(PROTO_VERSION), "voice": str(voice_port), "http": str(http_port)},
            server=f"{host}.local.",
            addresses=[socket.inet_aton(a) for a in addresses],
            **ttls,
        )
        self.interfaces = interfaces
        self._azc = None

    async def start(self) -> None:
        from zeroconf import IPVersion  # noqa: PLC0415
        from zeroconf.asyncio import AsyncZeroconf  # noqa: PLC0415

        self._azc = AsyncZeroconf(interfaces=_interfaces(self.interfaces), ip_version=IPVersion.V4Only)
        await self._azc.async_register_service(self.info, allow_name_change=True)

    async def close(self) -> None:
        if self._azc is None:
            return
        try:
            await self._azc.async_unregister_service(self.info)
        finally:
            await self._azc.async_close()
            self._azc = None
