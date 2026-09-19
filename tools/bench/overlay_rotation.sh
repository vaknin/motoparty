#!/usr/bin/env bash
# The overlay after rotation: for each rotation, is the TALK/MUSIC window fully on screen, does a
# tap on TALK reach the app (logcat "trigger TALK"), and after a drag is it still on screen.
# The phone must be unlocked and left alone; the screen rotates. No sound (no client connected,
# so TALK only logs "talk: no client connected").
# Rotation settings are ALWAYS restored on exit, and the overlay is dragged back to where it was.
#
#   tools/bench/overlay_rotation.sh <out-dir>   # then read <out-dir>/summary.txt
#
# Env: ROTATIONS ("0 1 2 3"), PHONE. No DRY mode: there is nothing peer-side to check.
source "$(dirname "$0")/lib.sh"
bench_init "${1:-}"
ROTATIONS=${ROTATIONS:-0 1 2 3}
[ "$DRY" = 1 ] && { log "overlay_rotation has no dry run: it is all adb"; exit 0; }

adb_gate
phone_free
if sh1 dumpsys window | grep -q -E 'mShowingLockscreen=true|isKeyguardShowing=true|mDreamingLockscreen=true'; then
  log "FAILED: the phone is locked; unlock it and run again"; exit 1
fi
keep_awake
ACC=$(sh1 settings get system accelerometer_rotation); USR=$(sh1 settings get system user_rotation)
on_exit "sh1 settings put system accelerometer_rotation $ACC; sh1 settings put system user_rotation $USR"
sh1 settings put system accelerometer_rotation 0
read -r PW PH < <(sh1 wm size | awk -F'[ x]' '/Override/{o=$3" "$4} /Physical/{p=$3" "$4} END{print (o?o:p)}')
log "display ${PW}x${PH}, rotation settings were accelerometer=$ACC user=$USR"
logcat_begin
: >"$OUT/overlay.tsv"

row() {  # row <step> <rot> <tap-result>
  local w=$PW h=$PH
  (( $2 % 2 )) && { w=$PH; h=$PW; }
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$w" "$h" "$(overlay_frame)" "$3" >>"$OUT/overlay.tsv"
}
tap_talk() {  # prints yes/no: did a TALK tap reach the app
  local f; f=$(overlay_frame); [ -n "$f" ] || { echo no; return; }
  set -- $f
  $ADB logcat -c
  sh1 input tap $(( ($1 + $3) / 2 )) $(( $2 + ($4 - $2) / 4 )) >/dev/null; sleep 1.5
  sh1 "logcat -d --pid=\$(pidof $PKG)" | grep -q 'trigger TALK' && echo yes || echo no
}

START=$(overlay_frame); log "overlay at start: ${START:-none}"
[ -n "$START" ] || { log "FAILED: no overlay window (is the overlay on in the app?)"; exit 1; }
set -- $START; SX=$(( ($1 + $3) / 2 )); SY=$(( $2 + ($4 - $2) * 3 / 4 ))   # grab on MUSIC: a drag, not a tap
for r in $ROTATIONS; do
  phone_free
  sh1 settings put system user_rotation "$r"; sleep 2.5
  row rotated "$r" "$(tap_talk)"
  f=$(overlay_frame)
  if [ -n "$f" ]; then
    set -- $f
    sh1 input swipe $(( ($1 + $3) / 2 )) $(( $2 + ($4 - $2) * 3 / 4 )) 5000 5000 600 >/dev/null; sleep 1.5
    row dragged-far "$r" "$(tap_talk)"
  fi
done
# Back to rotation 0 and roughly the starting position (the app stores it as a fraction).
sh1 settings put system user_rotation 0; sleep 2.5
set -- $(overlay_frame)
[ $# = 4 ] && sh1 input swipe $(( ($1 + $3) / 2 )) $(( $2 + ($4 - $2) * 3 / 4 )) "$SX" "$SY" 800 >/dev/null
sleep 1.5; row back 0 -
log "overlay at end: $(overlay_frame) (start: $START)"
logcat_dump
python3 "$BENCH/bench.py" overlay "$OUT" | tee "$OUT/summary.txt"
