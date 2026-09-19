#!/usr/bin/env bash
# Talk open/close on the Pixel, driven by the peer as the iPhone: CYCLES normal cycles, one fast
# open→close→open, one long cycle for the mic-silence question (is the host capture loop alive
# while it sends nothing: DTX, or a dead loop). Short earcons play in the AirPods if connected.
#
#   tools/bench/talk_cycles.sh <out-dir>        # then read <out-dir>/summary.txt
#
# Env: CYCLES (6), TALK_S (8), GAP_S (4), LONG_S (20), PHONE, DRY=1 (see lib.sh).
source "$(dirname "$0")/lib.sh"
bench_init "${1:-}"
CYCLES=${CYCLES:-6}; TALK_S=${TALK_S:-8}; GAP_S=${GAP_S:-4}; LONG_S=${LONG_S:-20}

adb_gate
need_bt
fake_host
logcat_begin
log "running $CYCLES cycles of ${TALK_S}s, a fast re-open, one ${LONG_S}s talk"
{
  sleep 5; echo stats
  for ((i=1; i<=CYCLES; i++)); do echo talk; sleep "$TALK_S"; echo stats; echo talk; sleep "$GAP_S"; done
  # Fast: open, close 300 ms later, open again 300 ms after that. Must end open, then close.
  echo talk; sleep 0.3; echo talk; sleep 0.3; echo talk; sleep 4; echo talk; sleep "$GAP_S"
  echo talk; sleep "$LONG_S"; echo stats; echo talk; sleep 4
  echo quit
} | peer_client $((5 + CYCLES * (TALK_S + GAP_S) + 5 + GAP_S + LONG_S + 30)) --no-audio --tone
log "client exited"
logcat_dump
if [ "$DRY" != 1 ]; then
  # mAudioModeOwner carries mMode (0 = MODE_NORMAL, 3 = MODE_IN_COMMUNICATION).
  sh1 dumpsys audio | grep -i -E 'mode owner token|active communication device|mAudioModeOwner' >"$OUT/audio_mode.txt"
fi
python3 "$BENCH/bench.py" talk "$OUT" | tee "$OUT/summary.txt"
