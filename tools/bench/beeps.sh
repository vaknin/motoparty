#!/usr/bin/env bash
# A listening test: the three earcons, one at a time, each announced before it plays, so you can
# say which one you did not hear (AirPods in your ears, the Pixel left alone).
#  (1) the peer opens talk, TALK_S s, the peer closes  -> "live" (rising), then "closed" (falling)
#  (2) the peer opens talk and nobody speaks           -> the host closes on silence after
#      SILENCE_S s (the peer runs without --tone, so it sends only keepalives, nothing the host
#      can count as voice activity)                    -> "closed"
#  (3) the client's mic is unavailable and TALK is pressed on the host (unavailable.sh case (a))
#                                                      -> "error" (two low tones)
# Nothing else makes a sound: no --tone anywhere, so what you hear is earcons only.
#
#   tools/bench/beeps.sh <out-dir>              # then read <out-dir>/summary.txt
#
# Env: TALK_S (5), SILENCE_S (20, the spec value), PHONE, DRY=1 (see lib.sh; the fake host's
# `talk` stands in for the TALK press and does the silence close).
source "$(dirname "$0")/lib.sh"
bench_init "${1:-}"
TALK_S=${TALK_S:-5}; SILENCE_S=${SILENCE_S:-20}

adb_gate
need_bt
fake_host --silence-ms $((SILENCE_S * 1000))
logcat_begin
[ "$DRY" = 1 ] || awake || on_exit "sh1 input keyevent KEYCODE_SLEEP"   # leave it locked if found locked
if [ "$DRY" != 1 ]; then
  if locked; then log "WARNING: phone locked: the overlay is hidden, so beep (3) cannot press TALK"; else keep_awake; fi
fi

log "(1) talk opened and closed by the peer"
{
  sleep 3
  log "NOW: the peer opens talk -> the 'live' beep (low then high) a moment after the headset switches"
  echo talk; sleep "$TALK_S"
  log "NOW: the peer closes talk -> the 'closed' beep (high then low)"
  echo talk; sleep 3; echo quit
} | peer_client 30 --no-audio
mv "$OUT/client.log" "$OUT/client_1.log"

log "(2) talk opened by the peer, nobody speaks: the host closes on silence"
{
  sleep 3
  log "NOW: the peer opens talk and stays silent -> the 'live' beep, then nothing for ${SILENCE_S}s"
  echo talk; sleep "$SILENCE_S"
  log "NOW: ${SILENCE_S}s of silence are up -> the 'closed' beep (no beep = the host did not close)"
  sleep 5; echo quit
} | peer_client $((SILENCE_S + 25)) --no-audio
mv "$OUT/client.log" "$OUT/client_2.log"

log "(3) client mic unavailable, TALK pressed on the host"
[ "$DRY" = 1 ] || sh1 input keyevent KEYCODE_WAKEUP >/dev/null
{
  sleep 5
  host_talk || log "(3) could not press TALK"    # the overlay's TALK zone only, never MUSIC
  log "NOW: the client refuses (its mic is unavailable) -> the 'error' beep (two low tones), no talk"
  sleep 6; echo quit
} | peer_client 30 --no-audio --mic-unavailable
mv "$OUT/client.log" "$OUT/client_3.log"
logcat_dump

{
  echo "beeps, in the order you should have heard them:"
  grep -a "NOW:" "$OUT/run.log"
  # Earcons.kt logs nothing (no Log call, and no 'earcon' line in any results/ logcat), so the
  # closest the logcat gets is the talk lines each beep follows.
  echo
  if [ -s "$OUT/logcat.txt" ]; then
    echo "logcat (earcons themselves are not logged; these are the lines they follow):"
    grep -aE 'talk (open|closed)|[Uu]navailable' "$OUT/logcat.txt" | python3 -u -c 'import sys,re,time
for l in sys.stdin:
    if m := re.match(r"\s*(\d+\.\d+)\s+\d+\s+\d+\s+\S\s+(.*)", l):
        print("  %s %s" % (time.strftime("%T", time.localtime(float(m[1]))), m[2].strip()[:120]))'
  else
    echo "no logcat (dry run, or the dump failed): the times above are all there is"
  fi
  echo
  echo "VERDICT: ASK YOUR EARS (5 beeps expected: live, closed, live, closed, error)"
} | tee "$OUT/summary.txt"
