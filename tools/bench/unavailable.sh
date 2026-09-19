#!/usr/bin/env bash
# The two talk.close{reason:"unavailable"} paths on the Pixel:
#  (a) the client's mic is dead (peer --mic-unavailable) and talk is pressed on the host
#      (overlay TALK zone): expect talk.open{by:host} → client talk.close{unavailable} →
#      host talk.close + state{talk:false}, one error earcon on the Pixel.
#  (b) the host's mic is unavailable (RECORD_AUDIO revoked; that kills the app, so it is
#      restarted) and the client asks for talk: expect talk.close{by:host,reason:unavailable}.
# The permission is ALWAYS granted back and the app restarted on exit (also on error/Ctrl-C).
#
#   tools/bench/unavailable.sh <out-dir>        # then read <out-dir>/summary.txt
#
# Env: PHONE, DRY=1 (see lib.sh; the fake host's `talk` and `mic off` stand in).
source "$(dirname "$0")/lib.sh"
bench_init "${1:-}"

adb_gate
fake_host
logcat_begin
[ "$DRY" = 1 ] || awake || on_exit "sh1 input keyevent KEYCODE_SLEEP"   # leave it locked if found locked

if [ "$DRY" != 1 ]; then
  if locked; then log "WARNING: phone locked: the overlay is hidden, so case (a) cannot press TALK"; else keep_awake; fi
fi
log "(a) client mic unavailable, TALK pressed on the host"
[ "$DRY" = 1 ] || sh1 input keyevent KEYCODE_WAKEUP >/dev/null
{
  sleep 5
  host_talk || log "(a) could not press TALK"
  sleep 5; echo quit
} | peer_client 30 --no-audio --tone --mic-unavailable
mv "$OUT/client.log" "$OUT/client_a.log"

log "(b) host mic unavailable"
if [ "$DRY" = 1 ]; then
  host_cmd "mic off"
else
  on_exit "sh1 pm grant $PKG android.permission.RECORD_AUDIO; restart_app; sh1 dumpsys package $PKG | grep -m1 'RECORD_AUDIO: granted' >'$OUT/permission.txt'"
  sh1 pm revoke $PKG android.permission.RECORD_AUDIO
  sh1 input keyevent KEYCODE_WAKEUP >/dev/null   # the activity has to come up to start the host
  sleep 1; restart_app
fi
{
  sleep 5; echo talk; sleep 4; echo quit
} | peer_client 25 --no-audio --tone
mv "$OUT/client.log" "$OUT/client_b.log"
host_cmd "mic on"
logcat_dump     # before the restore restarts the app: --pid is the app of case (b)
bench_cleanup; CLEANUPS=(); trap - EXIT
python3 "$BENCH/bench.py" unavailable "$OUT" | tee "$OUT/summary.txt"
