#!/usr/bin/env bash
# Music sync on the Pixel over A2DP: start a track, let it settle, talk, close, then POST_S of
# plain playback. Summary: the SyncController trace and what each speed nudge led to, to tell
# a nudge that overshoots from a position reading that jumps. ~5 min of music in the AirPods.
#
#   tools/bench/music_sync.sh <out-dir>         # then read <out-dir>/summary.txt
#
# Env: MUSIC_CMD ("play song bohemian rhapsody"), PRE_S (60), TALK_S (15), POST_S (180),
#      PHONE, DRY=1 (see lib.sh; the fake host plays a generated tone file).
source "$(dirname "$0")/lib.sh"
bench_init "${1:-}"
MUSIC_CMD=${MUSIC_CMD:-play song bohemian rhapsody}
PRE_S=${PRE_S:-60}; TALK_S=${TALK_S:-15}; POST_S=${POST_S:-180}

adb_gate
need_bt
if [ "$DRY" = 1 ]; then
  ffmpeg -loglevel error -f lavfi -i "sine=frequency=330:duration=$((PRE_S + TALK_S + POST_S + 30))" \
    -c:a aac "$OUT/track.m4a"
  fake_host --track "$OUT/track.m4a"
fi
logcat_begin
log "music: '$MUSIC_CMD', ${PRE_S}s, talk ${TALK_S}s, ${POST_S}s after"
{
  sleep 5
  if [ "$DRY" = 1 ]; then host_cmd load; else echo "say $MUSIC_CMD"; fi
  sleep "$PRE_S"; echo stats
  echo talk; sleep "$TALK_S"; echo talk
  sleep "$POST_S"; echo stats
  echo pause; sleep 2; echo quit
} | peer_client $((5 + PRE_S + TALK_S + POST_S + 40)) --no-audio --tone
log "client exited"
logcat_dump
python3 "$BENCH/bench.py" music "$OUT" | tee "$OUT/summary.txt"
