#!/usr/bin/env python3
"""Summaries for the tools/bench device scripts, and one helper they call.

  bench.py talk <out-dir>          talk_cycles.sh: per-cycle route timings, talk stats, peer answers
  bench.py music <out-dir>         music_sync.sh: SyncController trace and what each nudge did
  bench.py unavailable <out-dir>   unavailable.sh: the two refusal paths, expectation by expectation
  bench.py overlay <out-dir>       overlay_rotation.sh: overlay frame per rotation, on screen, tappable
  bench.py frame <package>         stdin `dumpsys window windows` -> "x1 y1 x2 y2" of our overlay

Inputs in <out-dir>: logcat.txt (`-v epoch`; the older threadtime format also parses, times are
then seconds of the day), client*.log (epoch-stamped peer output), audio_mode.txt, overlay.tsv.
"""
import json, os, re, statistics as st, sys

EPOCH = re.compile(r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+([VDIWEF])\s+(.+?)\s*: (.*)$")
THREADTIME = re.compile(r"^\d\d-\d\d (\d\d):(\d\d):(\d\d\.\d+)\s+\d+\s+\d+\s+([VDIWEF])\s+(.+?)\s*: (.*)$")


def logcat(out):
    """[(t, level, tag, msg)] from logcat.txt, [] if there is none (dry run)."""
    p = f"{out}/logcat.txt"
    if not os.path.exists(p):
        return []
    ev = []
    for line in open(p, errors="replace"):
        if m := EPOCH.match(line):
            ev.append((float(m[1]), m[2], m[3], m[4]))
        elif m := THREADTIME.match(line):
            ev.append((int(m[1]) * 3600 + int(m[2]) * 60 + float(m[3]), m[4], m[5], m[6]))
    return ev


def client(out, name="client.log"):
    """[(t, dir, obj-or-text)]: dir is '>>', '<<' (obj = parsed JSON) or '' (obj = the text)."""
    p = f"{out}/{name}"
    if not os.path.exists(p):
        return []
    ev = []
    for line in open(p, errors="replace"):
        parts = line.rstrip("\n").split(" ", 2)
        if len(parts) < 3:
            continue
        t, rest = float(parts[0]), parts[2]
        if rest[:3] in (">> ", "<< ") and rest[3:4] == "{":
            try:
                ev.append((t, rest[:2], json.loads(rest[3:])))
                continue
            except ValueError:
                pass
        ev.append((t, "", rest))
    return ev


def errors(ev, out):
    bad = [e for e in ev if e[1] in "EF" or "Exception" in e[3]]
    crash = [l for l in open(f"{out}/crash.txt", errors="replace") if l.strip() and not l.startswith("-")] \
        if os.path.exists(f"{out}/crash.txt") else []
    print(f"\nerrors in logcat: {len(bad)}, crash buffer lines: {len(crash)}")
    for e in bad[:8]:
        print(f"  {e[1]} {e[2]}: {e[3][:160]}")
    for l in crash[:5]:
        print("  crash:", l.rstrip()[:160])
    return len(bad) + len(crash)


def ms(a, b):
    return "-" if a is None or b is None else f"{(b - a) * 1000:.0f}"


# ---------------------------------------------------------------------------------------- talk

STATS = re.compile(r"tx (\d+) sent of (\d+) captured \((-?\d+) DTX\), rx (\d+) received, (\d+) played, "
                   r"(\d+) lost, (\d+) late, (\d+) FEC, (\d+) PLC, (\d+) keepalives, jitter target (\d+) ms")


def talk_cycles(ev):
    cycles = []
    for t, lvl, tag, msg in ev:
        c = cycles[-1] if cycles else None
        if tag == "Motoparty" and (m := re.match(r"talk open \(by (\w+)\)", msg)):
            cycles.append(dict(open=t, by=m[1], hfp=None, dev=None, close=None, reason=None,
                               media=None, stats=None, uses=0, kept=False, collapsed=False, detail=[]))
        elif c is None:
            continue
        elif tag == "AudioRouter" and msg.startswith("communication device") and c["close"] is None:
            c["uses"] += 1
            if c["hfp"] is None:
                c["hfp"], c["dev"] = t, msg[len("communication device "):]
        elif tag == "Motoparty" and (m := re.match(r"talk closed \(by (\w+), (\w+)\)", msg)):
            c["close"], c["reason"] = t, f"{m[1]}/{m[2]}"
        elif tag == "AudioRouter" and msg == "back to media mode" and c["close"] and c["media"] is None:
            c["media"] = t
        elif tag == "VoiceEngine" and msg.startswith("talk stats:"):
            c["stats"] = msg
        elif tag == "TalkAudio" and msg.startswith("re-open"):
            # The previous close never tore down: the route was kept for this talk (TalkAudio).
            c["kept"] = True
            if len(cycles) > 1:
                cycles[-2]["collapsed"] = True
            c["detail"].append(f"{tag}: {msg}")
        elif (tag == "AudioRouter" and re.match(r"(enterCall|exitCall) \d+ ms", msg)) \
                or (tag == "VoiceEngine" and not msg.startswith("talk stats:")):
            c["detail"].append(f"{tag}: {msg}")
    return cycles


def peer_answers(cev):
    """Our talk.open / talk.close requests and how long the host took to answer each."""
    rows, pending = [], None
    for t, d, o in cev:
        if d == ">>" and isinstance(o, dict) and o.get("t") in ("talk.open", "talk.close"):
            pending = (t, o["t"])
        elif pending and d == "<<" and isinstance(o, dict) and o.get("t") in ("talk.open", "talk.close"):
            rows.append((pending[1], o.get("by"), o.get("reason", ""), (t - pending[0]) * 1000))
            pending = None
        elif pending and d == "" and "TALK REFUSED" in o:
            rows.append((pending[1], "host", "REFUSED", (t - pending[0]) * 1000))
            pending = None
    if pending:
        rows.append((pending[1], None, "NO ANSWER", None))
    return rows


def cmd_talk(out):
    ev, cev = logcat(out), client(out)
    cycles = talk_cycles(ev)
    print(f"talk cycles in logcat: {len(cycles)}")
    if cycles:
        print(f"{'#':>2} {'by':6} {'open→HFP':>8} {'close→media':>11} {'dur s':>6} {'captured':>8} "
              f"{'exp':>5} {'sent':>5} {'DTX':>5} {'rx':>5} {'lost':>4} {'late':>4} {'FEC':>3} {'PLC':>3}  capture / reason")
    bad = 0
    for i, c in enumerate(cycles, 1):
        dur = (c["close"] - c["open"]) if c["close"] else None
        s = STATS.search(c["stats"] or "")
        cols = ["kept" if c["kept"] and not c["hfp"] else ms(c["open"], c["hfp"]),
                "collapsed" if c["collapsed"] and not c["media"] else ms(c["close"], c["media"])]
        note = c["reason"] or "NOT CLOSED"
        if c["collapsed"] and not c["stats"]:
            note += " (re-opened before its teardown finished: its stats are in the next cycle's line)"
        if s and dur:
            sent, cap, dtx, rx, _, lost, late, fec, plc = (int(x) for x in s.groups()[:9])
            exp = dur * 50
            alive = "capture alive" if cap >= 0.85 * exp else f"capture STALLED? ({cap / exp:.0%} of expected)"
            quiet = f", {dtx / cap:.0%} DTX" if cap else ""
            print(f"{i:>2} {c['by']:6} {cols[0]:>8} {cols[1]:>11} {dur:6.1f} {cap:8} {exp:5.0f} {sent:5} {dtx:5} "
                  f"{rx:5} {lost:4} {late:4} {fec:3} {plc:3}  {alive}{quiet}; {note}")
        else:
            print(f"{i:>2} {c['by']:6} {cols[0]:>8} {cols[1]:>11} {dur if dur is None else round(dur, 1)!s:>6} "
                  f"{'(no talk stats line)':>40}  {note}")
        if (c["hfp"] is None and not c["kept"]) or (c["media"] is None and not c["collapsed"]) \
                or c["close"] is None:
            bad += 1
        if c["dev"] and not c["dev"].endswith(": true"):
            print(f"   route: {c['dev']}")
        for d in c["detail"]:
            print(f"      {d[:150]}")
    rows = peer_answers(cev)
    if rows:
        print("\npeer requests (host answer time, ms):")
        for what, by, reason, dt in rows:
            print(f"  {what:10} -> by={by} {reason:10} {'-' if dt is None else f'{dt:.0f}'}")
    for name, v in (("open→HFP", [(c["hfp"] - c["open"]) * 1000 for c in cycles if c["hfp"]]),
                    ("close→media", [(c["media"] - c["close"]) * 1000 for c in cycles if c["media"] and c["close"]])):
        if v:
            print(f"{name} ms: median {st.median(v):.0f}, max {max(v):.0f} (n={len(v)})")
    nerr = errors(ev, out) if ev else 0
    if os.path.exists(f"{out}/audio_mode.txt"):
        print("\nfinal audio state:\n  " + open(f"{out}/audio_mode.txt").read().strip().replace("\n", "\n  "))
    if not ev:
        print("\nVERDICT: NO LOGCAT (dry run, or the dump failed): only the peer side above")
        return
    print(f"\nVERDICT: {'CLEAN' if ev and not bad and not nerr else 'LOOK AT THE ROWS ABOVE'}"
          f" ({bad} incomplete cycles, {nerr} errors)")


# ---------------------------------------------------------------------------------------- music

DRIFT = re.compile(r"drift (-?\d+) ms")
NUDGE = re.compile(r"drift (-?\d+) ms: speed ([\d.]+) for (\d+) ms")


def cmd_music(out):
    ev = logcat(out)
    cev = client(out)
    if cev:
        t0 = cev[0][0]
        print("peer side (s since the peer started):")
        for t, d, o in cev:
            if d and o.get("t", "").split(".")[0] in ("music", "talk"):
                print(f"  {t - t0:7.1f}  {d} {json.dumps(o)[:140]}")
            elif not d and o.startswith(("music:", "TALK")):
                print(f"  {t - t0:7.1f}  {o[:140]}")
        print()
    rows = [e for e in ev if e[2] == "SyncController"
            or (e[2] == "Motoparty" and re.match(r"talk (open|closed)|command:|announce:", e[3]))]
    if not rows:
        print("no SyncController lines" + (" (no logcat: dry run?)" if not ev else ""))
        return
    t0 = rows[0][0]
    print("trace (s since the first line):")
    for t, _, tag, msg in rows:
        print(f"  {t - t0:7.1f}  {'' if tag == 'SyncController' else tag + ': '}{msg}")
    # What each rate nudge led to: the next drift reading after it.
    sync = [(t, m) for t, _, tag, m in rows if tag == "SyncController"]
    print("\nnudges (drift before → speed × time → nudge done (one raw read) → next filtered reading):")
    ups, downs = [], []
    for i, (t, m) in enumerate(sync):
        if not (n := NUDGE.search(m)):
            continue
        rest = sync[i + 1:]
        after = next(((t2, DRIFT.search(m2)) for t2, m2 in rest
                      if DRIFT.search(m2) and not m2.startswith("nudge done")), None)
        done = next((DRIFT.search(m2) for t2, m2 in rest
                     if m2.startswith("nudge done") and (after is None or t2 <= after[0])), None)
        e = int(after[1][1]) if after else None
        d, s = int(n[1]), float(n[2])
        (ups if s > 1 else downs).append((d, e))
        print(f"  {d:+5d} ms  speed {s:.4f} × {n[3]} ms  →  done {'-' if done is None else f'{int(done[1]):+d}'}"
              f"  →  {'-' if e is None else f'{e:+d} ms'}{'' if after is None else f'  ({after[0] - t:.1f} s later)'}")
    for name, v in (("speed-ups", ups), ("slow-downs", downs)):
        v = [(d, e) for d, e in v if e is not None]
        if v:
            print(f"  {name}: next reading {', '.join(f'{e:+d}' for _, e in v)} ms "
                  f"(mean {st.mean(e for _, e in v):+.0f}); size of nudge {', '.join(f'{d:+d}' for d, _ in v)}")
    waits = [m for _, m in sync if m.startswith("check:")]
    print(f"\nunconfirmed readings (check: … waiting): {len(waits)}, nudges: {len(ups) + len(downs)}, "
          f"re-seeks: {sum('seeking' in m for _, m in sync)}")
    # A (re)start in between (after talk, a seek) resets the chain.
    plain = [(t, int(DRIFT.search(m)[1]) if DRIFT.search(m) else None, bool(NUDGE.search(m)))
             for t, m in sync if (DRIFT.search(m) and not m.startswith("nudge done")) or m.startswith("start ")]
    jumps = [(a, b) for a, b in zip(plain, plain[1:])
             if None not in (a[1], b[1]) and not a[2] and abs(b[1] - a[1]) > 100]
    print(f"\nreading moved > 100 ms between two checks with no speed change in between: {len(jumps)}")
    for a, b in jumps:
        print(f"  {a[1]:+d} → {b[1]:+d} ms over {b[0] - a[0]:.1f} s (at {a[0] - t0:.1f} s)")
    leads = [m for _, m in sync if m.startswith("start ")]
    if leads:
        print("\nstarts:", *leads, sep="\n  ")
    errors(ev, out)


# ---------------------------------------------------------------------------------- unavailable

def expect(ok, what):
    print(f"  [{'ok' if ok else 'FAIL'}] {what}")
    return ok


def cmd_unavailable(out):
    ev = logcat(out)
    msgs = [m for _, _, tag, m in ev if tag == "Motoparty"]
    good = True
    print("(a) client mic unavailable, talk pressed on the host:")
    a = client(out, "client_a.log")
    objs = [(d, o) for _, d, o in a if isinstance(o, dict)]
    i_open = next((i for i, (d, o) in enumerate(objs) if d == "<<" and o.get("t") == "talk.open"), None)
    good &= expect(i_open is not None and objs[i_open][1].get("by") == "host", "<< talk.open{by:host}")
    rest = objs[i_open + 1:] if i_open is not None else []
    i_cl = next((i for i, (d, o) in enumerate(rest) if d == ">>" and o.get("t") == "talk.close"), None)
    good &= expect(i_cl is not None and rest[i_cl][1].get("reason") == "unavailable",
                   ">> talk.close{by:client,reason:unavailable}")
    rest = rest[i_cl + 1:] if i_cl is not None else []
    good &= expect(any(d == "<<" and o.get("t") == "talk.close" for d, o in rest), "<< talk.close (host broadcast)")
    states = [o for d, o in rest if d == "<<" and o.get("t") == "state"]
    good &= expect(bool(states) and states[-1].get("talk") is False, "<< state{talk:false} last")
    if ev:
        good &= expect(any("client microphone unavailable" in m for m in msgs), "logcat: client microphone unavailable")
    print("(b) host mic unavailable (RECORD_AUDIO revoked), talk asked by the client:")
    b = client(out, "client_b.log")
    good &= expect(any(d == "" and "TALK REFUSED by host" in o for _, d, o in b), "TALK REFUSED by host")
    states_b = [o for _, d, o in b if d == "<<" and isinstance(o, dict) and o.get("t") == "state"]
    good &= expect(bool(states_b) and not any(o.get("talk") for o in states_b),
                   "connected (a state arrived) and state.talk never true")
    if ev:
        good &= expect(any("talk.open refused: microphone unavailable" in m for m in msgs),
                       "logcat: talk.open refused: microphone unavailable")
    if os.path.exists(f"{out}/permission.txt"):
        print("permission afterwards:", open(f"{out}/permission.txt").read().strip())
    nerr = errors(ev, out) if ev else 0
    print(f"\nVERDICT: {'CLEAN' if good and not nerr else 'LOOK AT THE FAILS ABOVE'}")


# -------------------------------------------------------------------------------------- overlay

def cmd_overlay(out):
    rows = [l.split("\t") for l in open(f"{out}/overlay.tsv") if l.strip()]
    good = True
    print(f"{'step':14} {'rot':>3} {'display':>10} {'frame':>24}  on screen  tap")
    for step, rot, w, h, frame, tap in rows:
        f = [int(x) for x in frame.split()] if frame.strip() else None
        w, h = int(w), int(h)
        on = f is not None and f[0] >= 0 and f[1] >= 0 and f[2] <= w and f[3] <= h
        good &= on and tap != "no"
        print(f"{step:14} {rot:>3} {f'{w}x{h}':>10} {frame or 'NO WINDOW':>24}  {'yes' if on else 'NO':9}  {tap}")
    ev = logcat(out)
    errors(ev, out) if ev else None
    print(f"\nVERDICT: {'CLEAN' if good else 'LOOK AT THE ROWS ABOVE'}")


# ---------------------------------------------------------------------------------------- frame

FRAME = re.compile(r"(?<![A-Za-z])(?:mFrame|frame)=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def cmd_frame(pkg):
    blocks, cur = [], None
    for line in sys.stdin:
        if re.match(r"\s*Window #\d+ Window\{", line):
            cur = [line]
            blocks.append(cur)
        elif cur is not None:
            cur.append(line)
    for b in blocks:
        if pkg in b[0] and any("ty=APPLICATION_OVERLAY" in l for l in b):
            for l in b:
                if m := FRAME.search(l):
                    print(*m.groups())
                    return


if __name__ == "__main__":
    cmd, arg = sys.argv[1], sys.argv[2]
    {"talk": cmd_talk, "music": cmd_music, "unavailable": cmd_unavailable,
     "overlay": cmd_overlay, "frame": cmd_frame}[cmd](arg)
