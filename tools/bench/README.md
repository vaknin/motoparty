# tools/bench

Device bench scripts: each drives the Pixel over adb plus `tools/peer` as the iPhone, and ends
with a short `summary.txt` (last line `VERDICT: …`) so a session reads a page, not a logcat.
Everything lands in the `<out-dir>` given: `run.log`, `client*.log` (the peer, epoch-stamped),
`logcat.txt` (`-v epoch`, the app's pid only), `crash.txt`, `summary.txt`.

| Script | What it answers | Sound in the AirPods | Phone must be |
|---|---|---|---|
| `talk_cycles.sh` | open→HFP / close→media ms per cycle (6 cycles, a fast open→close→open, one 20 s talk), exceptions, final audio mode, `talk stats` per cycle, host capture alive vs DTX | earcons | left alone ~3 min |
| `music_sync.sh` | the `SyncController` trace over play → settle → talk → 3 min A2DP; what each speed nudge led to, reading jumps with no speed change | ~5 min of music | left alone, AirPods connected |
| `unavailable.sh` | both `talk.close{reason:"unavailable"}` paths, expectation by expectation | one error earcon | left alone; the app restarts twice |
| `overlay_rotation.sh` | overlay frame on screen in each rotation, a TALK tap reaches the app, still on screen after a drag | none | unlocked, left alone; screen rotates |
| `hotspot_test.sh` + `hotspot_rtt.py` | RTT over the Pixel hotspot per phase (see the script header) | short | on its own hotspot |

```sh
tools/bench/talk_cycles.sh ~/bench/2026-09-20-talk && cat ~/bench/2026-09-20-talk/summary.txt
python3 tools/bench/bench.py talk <out-dir>     # re-run a summary on an existing out-dir
```

Env: `PHONE` (adb serial, default `192.168.1.100:5555`), per-script knobs in each header
(`CYCLES`, `TALK_S`, `MUSIC_CMD`, `POST_S`, `ROTATIONS`, …).

**Safety.** Every adb call is `timeout 30 adb -s $PHONE`. Before injecting input the scripts
check `mWakefulness` and the foreground app, and wait (up to 5 min) if someone is using the
phone. Anything changed is restored on exit, also on error or Ctrl-C: `unavailable.sh`
re-grants `RECORD_AUDIO` and restarts the app, `overlay_rotation.sh` restores
`accelerometer_rotation` / `user_rotation` and drags the overlay back. The host-side TALK press
is a tap on the overlay's TALK zone (LinkService's intents are not exported), so the overlay
must be on.

**Music summary.** Every `SyncController` line is in the trace table, incl. the 1/s `trace:`
lines (`pos`, `expected`, `err`, `speed`, `phase`, `t`, `playing`, …; `err`, not "drift", so
they are not counted as checks). "Next drift after a nudge" is on purpose the `nudge done:
drift N ms` line when there is one: that is where the nudge landed.

**Dry run.** `DRY=1` needs no phone: it starts `motoparty-peer host` on 127.0.0.1 (fake host
`talk` / `mic off` / `load` stand in for the Pixel) and runs the same peer-side steps and the
summary. Proven 2026-09-19 for `talk_cycles`, `unavailable` and `music_sync`; the summaries
were also checked against the real logcat in `results/2026-09-19-bt-on-2.4ghz/`.
`overlay_rotation.sh` has no dry run. **Not yet proven on the device**: every adb step of the
four new scripts (the overlay frame parse from `dumpsys window`, the TALK tap, the
revoke/restart, the audio-mode grep). The first device session fixes what breaks there.
