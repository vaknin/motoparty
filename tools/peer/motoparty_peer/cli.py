"""Command line: `motoparty-peer client ...` / `motoparty-peer host ...`."""

from __future__ import annotations

import argparse
import sys

from .protocol import CONTROL_PORT


def _device(v: str):
    return int(v) if v.isdigit() else v


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="motoparty-peer", description="Desktop peer for the Motoparty wire protocol")
    sub = p.add_subparsers(dest="role", required=True)

    c = sub.add_parser("client", help="act as the client (the iPhone) against a host")
    # --host skips discovery entirely, so --no-mdns alongside it is a mistake, not a no-op.
    where = c.add_mutually_exclusive_group()
    where.add_argument("--host", help="host IP; skips Bonjour and the /24 sweep")
    where.add_argument("--no-mdns", action="store_true",
                       help="skip the Bonjour browse and go straight to the /24 sweep")
    c.add_argument("--port", type=int, default=CONTROL_PORT, help="control port (default %(default)s)")
    c.add_argument("--name", help="name in our hello (default: hostname)")
    c.add_argument("--lang", default="en-US", help="BCP-47 tag for `say` (default %(default)s)")
    c.add_argument("--tone", action="store_true", help="send a 440 Hz tone instead of the microphone")
    c.add_argument("--no-audio", action="store_true", help="no sound devices; log voice stats instead")
    c.add_argument("--play", action="store_true", help="play music with mpv/ffplay at the scheduled time")
    c.add_argument("--mic-unavailable", action="store_true",
                   help="start with the mic marked unavailable: answer talk.open with "
                        "talk.close{by:'client',reason:'unavailable'} (stdin `unavailable` toggles)")
    c.add_argument("--cache-dir", default="~/.cache/motoparty-peer", help="track cache (default %(default)s)")
    c.add_argument("--input-device", type=_device, help="PortAudio input device (index or name)")
    c.add_argument("--output-device", type=_device, help="PortAudio output device (index or name)")
    c.add_argument("-v", "--verbose", action="store_true", help="also print raw ping/pong frames")

    h = sub.add_parser("host", help="act as a minimal fake host (the Pixel)")
    h.add_argument("--track", action="append",
                   help="an .m4a file, or a directory of them, to serve and search (repeatable; "
                        "`load` plays the first)")
    h.add_argument("--name", help="Bonjour instance / hello name (default: '<hostname> peer')")
    h.add_argument("--bind", default="0.0.0.0", help="listen address (default %(default)s)")
    h.add_argument("--port", type=int, help="control port (default 47800; 0 = any)")
    h.add_argument("--voice-port", type=int, help="voice UDP port (default 47801; 0 = any)")
    h.add_argument("--http-port", type=int, help="track HTTP port (default 47802; 0 = any)")
    h.add_argument("--no-mdns", action="store_true", help="do not advertise over Bonjour")
    h.add_argument("-v", "--verbose", action="store_true", help="also print ping/pong frames")
    return p


def main(argv: list[str] | None = None) -> None:
    args = build_parser().parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(line_buffering=True)
    if args.role == "client":
        from .client import run  # noqa: PLC0415
    else:
        from .host import run  # noqa: PLC0415
    run(args)


if __name__ == "__main__":
    main()
