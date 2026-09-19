#!/usr/bin/env python3
"""RTT per phase from a hotspot_test.sh output directory.

Two sources, both bucketed by tools/bench/hotspot_test.sh's `phases` file:
  pong  - the peer's application-level ping/pong over TCP, 1 per 2 s (client.log)
  icmp  - `ping -i 0.2` to the gateway for the whole run, 5 per s (ping.txt, newer runs)
"""
import re, statistics as st, sys, os
out = sys.argv[1]
ph = [(0.0, "connect")] + [(float(l.split()[0]), l.split()[2])
                           for l in open(f"{out}/phases") if " PHASE " in l]


def bucket(samples):
    b = {}
    for t, r in samples:
        b.setdefault([n for s, n in ph if s <= t][-1], []).append(r)
    return b


def report(label, samples, sent=None):
    b = bucket(samples)
    order = [n for _, n in ph if n in b]
    for n in order:
        v = sorted(b[n])
        q = lambda p: v[min(len(v) - 1, int(p * len(v)))]
        extra = ""
        if sent is not None:
            s = len([1 for t, _ in sent if [nm for x, nm in ph if x <= t][-1] == n])
            extra = f" loss={100 * (s - len(v)) / s:.1f}%" if s else ""
        print(f"{label:5s} {n:11s} n={len(v):4d} median={st.median(v):.0f} p90={q(.9):.0f} "
              f"p99={q(.99):.0f} max={v[-1]:.0f} >100ms={sum(x > 100 for x in v)} "
              f">200ms={sum(x > 200 for x in v)}{extra}")


pongs = []
for l in open(f"{out}/client.log"):
    m = re.match(r"(\d+\.\d+) .* pong id=\d+ rtt=(-?[\d.]+)ms", l)
    if m:
        pongs.append((float(m[1]), float(m[2])))
report("pong", pongs)

p = f"{out}/ping.txt"
if os.path.exists(p):
    print()
    icmp, got = [], {}
    for l in open(p):
        m = re.match(r"\[(\d+\.\d+)\].*icmp_seq=(\d+).* time=([\d.]+) ms", l)
        if m:
            rt, seq, rtt = float(m[1]), int(m[2]), float(m[3])
            got[seq] = rt - rtt / 1000.0        # when the request went out
            icmp.append((got[seq], rtt))
    # every seq from first to last was sent, 5 per second; missing ones were lost
    sent = []
    if got:
        lo, hi = min(got), max(got)
        for s in range(lo, hi + 1):
            sent.append((got.get(s, got[lo] + (s - lo) * 0.2), 0.0))
    report("icmp", icmp, sent=sent)
