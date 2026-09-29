#!/usr/bin/env bash
# The overlay after rotation: for each rotation, is the TALK button fully on screen, does a
# tap on it reach the app (logcat "trigger TALK"), and after a drag is it still on screen.
# The phone must be unlocked and left alone; the screen rotates. With no client connected a TALK
# tap opens a solo talk (call route, live beep; since 2026-09-29), so each check taps a second
# time to close it: expect a live and a closed beep per rotation. Our own MainActivity is brought to the front for
# the run (the launcher is portrait-locked, so nothing rotates behind it) and HOME is pressed at
# the end. Each drag gives two rows: "dragged-far" is the frame while the finger is still down,
# "after-release" the frame once it is let go.
# Rotation settings are ALWAYS restored on exit, and the overlay is dragged back to where it was.
# Last, in rotation 0, comes the F6 drag-to-dismiss step (DISMISS=0 skips it): the buttons are
# dragged onto the X target at the bottom centre and let go, which must hide them
# (`overlayEnabled=false`, the position in overlay.xml untouched, no stray TALK trigger) —
# three more rows, "dismiss-target" (the X window seen mid-drag), "dismissed" and "restored".
# The buttons are ALWAYS brought back afterwards, also on error or Ctrl-C (see restore_overlay:
# the shell cannot fire the notification's SHOW_OVERLAY, so it rewrites the app's own
# settings.xml through `run-as` with the app stopped and relaunches it).
#
#   tools/bench/overlay_rotation.sh <out-dir>   # then read <out-dir>/summary.txt
#
# Env: ROTATIONS ("0 1 2 3"), DISMISS (1), PHONE. No DRY mode: there is nothing peer-side to check.
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
# user_rotation only rotates the display if the app in front lets it, and the launcher is
# portrait-locked: put our own activity (no screenOrientation) in front, go home again at the end.
sh1 am start -n $PKG/.MainActivity >/dev/null; sleep 2
on_exit "sh1 input keyevent KEYCODE_HOME"
# What the display is actually at (0/1/2/3), not what we asked for.
cur_rot() {
  local r; r=$(sh1 dumpsys window displays | grep -m1 -o 'mDisplayRotation=ROTATION_[0-9]*')
  case "${r##*_}" in 0) echo 0;; 90) echo 1;; 180) echo 2;; 270) echo 3;; esac
}
logcat_begin
: >"$OUT/overlay.tsv"

row() {  # row <step> <rot> <tap-result> [frame]; with a 4th argument the frame is used as given,
         # empty included (the dismiss step writes rows for a window that is meant to be gone).
  local w=$PW h=$PH f=${4-}
  (( $2 % 2 )) && { w=$PH; h=$PW; }
  [ $# -ge 4 ] || f=$(overlay_frame)
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$w" "$h" "$f" "$3" >>"$OUT/overlay.tsv"
}
# The stored fraction (fx = left/(W-w), fy likewise inside the insets): it is what a drag has to
# change for the position to survive the next place() — read-only, into run.log.
prefs() { sh1 run-as $PKG cat shared_prefs/overlay.xml | grep -o 'name="f[xy]" value="[0-9.E-]*"' | tr '\n' ' '; }
tap_talk() {  # prints yes/no: did a TALK tap reach the app
  local f; f=$(overlay_frame); [ -n "$f" ] || { echo no; return; }
  set -- $f
  $ADB logcat -c
  sh1 input tap $(( ($1 + $3) / 2 )) $(( $2 + ($4 - $2) / 4 )) >/dev/null; sleep 1.5
  sh1 "logcat -d --pid=\$(pidof $PKG)" | grep -q 'trigger TALK' && echo yes || echo no
  # The tap opened a solo talk: close it, and let the route settle before the next rotation.
  sh1 input tap $(( ($1 + $3) / 2 )) $(( $2 + ($4 - $2) / 4 )) >/dev/null; sleep 2
}

START=$(overlay_frame); log "overlay at start: ${START:-none}, fractions: $(prefs)"
[ -n "$START" ] || { log "FAILED: no overlay window (is the overlay on in the app?)"; exit 1; }
set -- $START; SX=$(( ($1 + $3) / 2 )); SY=$(( $2 + ($4 - $2) * 3 / 4 ))   # grab low on the button: a drag, not a tap
for r in $ROTATIONS; do
  phone_free
  sh1 settings put system user_rotation "$r"; sleep 3
  a=$(cur_rot); [ "$a" = "$r" ] || log "WARNING: asked for rotation $r, display is at ${a:-?}"
  a=${a:-$r}
  row rotated "$a" "$(tap_talk)"
  f=$(overlay_frame)
  if [ -n "$f" ]; then
    set -- $f
    dx=$(( ($1 + $3) / 2 )); dy=$(( $2 + ($4 - $2) * 3 / 4 ))   # grab low on the button: a drag, not a tap
    # Since F5 a drag sticks, so each rotation starts wherever the last one left the overlay: drag
    # AWAY from the corner it is in, or the swipe asks for a position it is already clamped to and
    # proves nothing. 0 is as far out of bounds as 5000: the grab point is well inside the window.
    dw=$PW; dh=$PH; (( a % 2 )) && { dw=$PH; dh=$PW; }
    tx=5000; ty=5000
    (( dx > dw / 2 )) && tx=0
    (( dy > dh / 2 )) && ty=0
    # Where the drag actually puts it: the frame while the finger is still down (a 5 s swipe in
    # the background, read 4 s in — by then the finger is out of bounds whichever way it went, so
    # the frame is the clamped one), then the frame once it is released.
    held=$(sh1 "(input swipe $dx $dy $tx $ty 5000 >/dev/null 2>&1 &); sleep 4; dumpsys window windows" \
      | python3 "$BENCH/bench.py" frame "$PKG")
    row dragged-far "$a" - "$held"
    sleep 2.5
    log "rot $a fractions after release: $(prefs)"
    row after-release "$a" "$(tap_talk)"
  fi
done
# Back to rotation 0 and roughly the starting position (the app stores it as a fraction). Only if
# it really moved: a swipe shorter than the touch slop is a tap, i.e. a TALK trigger (the mic!),
# and the frame comes back a pixel or two off even when nothing was dragged. 2026-09-20: a 2 px
# "restore" swipe opened the voice command; hence the 40 px threshold, not "!= $START".
sh1 settings put system user_rotation 0; sleep 3
set -- $(overlay_frame)
if [ $# = 4 ]; then
  gx=$(( ($1 + $3) / 2 )); gy=$(( $2 + ($4 - $2) * 3 / 4 ))
  if [ $(( gx > SX ? gx - SX : SX - gx )) -gt 40 ] || [ $(( gy > SY ? gy - SY : SY - gy )) -gt 40 ]; then
    sh1 input swipe "$gx" "$gy" "$SX" "$SY" 800 >/dev/null
  fi
fi
sleep 1.5; ENDROT=$(cur_rot); row back "${ENDROT:-0}" -
log "overlay at end: $(overlay_frame) (start: $START), fractions: $(prefs)"

# ---------------------------------------------------------- drag the buttons onto the X (F6)
# Last, because it is the one step that takes the buttons window away: dropping them on the X
# target writes overlayEnabled=false and stops the overlay service. DISMISS=0 skips it.
# frame_delta "x1 y1 x2 y2" "x1 y1 x2 y2": the largest corner difference, 99999 if one is empty.
frame_delta() {
  local a b i d m=0
  a=($1); b=($2)
  { [ ${#a[@]} = 4 ] && [ ${#b[@]} = 4 ]; } || { echo 99999; return; }
  for i in 0 1 2 3; do
    d=$(( a[i] - b[i] )); (( d < 0 )) && d=$(( -d ))
    (( d > m )) && m=$d
  done
  echo "$m"
}
# restore_overlay: bring the buttons back, whatever happened. Idempotent (a no-op while they are
# up), so it is safe both inline and as the on_exit handler registered before the swipe.
#   1. the notification's "Show buttons" action fires LinkService's SHOW_OVERLAY, but that service
#      is android:exported="false", so `am start-service` from the shell is expected to be refused
#      (SecurityException). It costs a second, it changes nothing if it fails, and it leaves the
#      app running when it works, so it is tried first.
#   2. what actually works without root: the *app's own uid* rewrites its SharedPreferences.
#      `overlayEnabled` only reaches the running app through a StateFlow updated in-process, so the
#      app must be stopped while the file is edited (a live process would also overwrite it on the
#      next settings write). force-stop, `run-as` + sed, relaunch: MainActivity starts LinkService,
#      whose collector reads overlayEnabled=true and starts the overlay. Needs a debuggable build
#      (`run-as`) — the same one `prefs()` above already relies on.
restore_overlay() {
  [ -n "$(overlay_frame)" ] && return 0
  log "restore: am start-service SHOW_OVERLAY -> $(sh1 am start-service -n $PKG/.LinkService -a $PKG.SHOW_OVERLAY | tr '\n' ' ')"
  sleep 2
  [ -n "$(overlay_frame)" ] && { log "restore: the SHOW_OVERLAY intent was accepted"; return 0; }
  sh1 am force-stop $PKG >/dev/null
  sh1 "run-as $PKG sed -i '/overlayEnabled/s/value=\"false\"/value=\"true\"/' shared_prefs/settings.xml"
  log "restore: settings.xml says $(sh1 run-as $PKG cat shared_prefs/settings.xml | grep -o 'name="overlayEnabled" value="[a-z]*"')"
  restart_app
  local i
  for i in 1 2 3 4 5; do
    [ -n "$(overlay_frame)" ] && { log "restore: the buttons are back"; return 0; }
    sleep 2
  done
  log "FAILED to restore the buttons. By hand: adb -s $PHONE shell run-as $PKG sed -i" \
      "'/overlayEnabled/s/false/true/' shared_prefs/settings.xml, then open the app"
  return 1
}
dismiss_step() {  # dismiss_step <rotation>
  local rot=${1:-0} dw=$PW dh=$PH f gx gy tx ty upy
  local dump held xf pref0 pref1 en ov mark nm nt nh after note bad rframe delta
  (( rot % 2 )) && { dw=$PH; dh=$PW; }
  f=$(overlay_frame)
  [ -n "$f" ] || { log "dismiss: SKIPPED, there is no buttons window to drag"; return 0; }
  # Before anything is dragged: an error, a Ctrl-C or a failed check must still give the rider
  # the buttons back. Registered last, so bench_cleanup runs it first (before HOME / rotation).
  on_exit "restore_overlay"
  tx=$(( dw / 2 )); ty=$(( dh - 20 ))          # bottom centre: inside the X in both rotations
  set -- $f; gx=$(( ($1 + $3) / 2 )); gy=$(( $2 + ($4 - $2) / 4 ))
  # NEVER a short swipe: under the touch slop it is a TAP, which opens a talk with the microphone
  # (a tap on the old MUSIC zone bit us twice on 2026-09-20). If the button already sits at the
  # bottom it is moved up first, with a swipe that is itself long.
  if [ $(( ty - gy )) -lt 40 ]; then
    upy=$(( dh / 5 ))
    if [ $(( gy - upy )) -gt 40 ]; then
      log "dismiss: the buttons are already at the bottom; moving them up first"
      sh1 input swipe "$gx" "$gy" "$gx" "$upy" 800 >/dev/null; sleep 2
      f=$(overlay_frame)
      [ -n "$f" ] || { log "dismiss: SKIPPED, the buttons vanished while moving them up"; return 0; }
      set -- $f; gx=$(( ($1 + $3) / 2 )); gy=$(( $2 + ($4 - $2) / 4 ))
    fi
  fi
  if [ $(( ty - gy )) -lt 40 ]; then
    log "dismiss: SKIPPED, the swipe would be under 40 px (that is a tap, i.e. the mic)"; return 0
  fi
  pref0=$(prefs)
  log "dismiss: buttons at $f, fractions $pref0; dragging ($gx,$gy) -> ($tx,$ty) over 5 s"
  mark=$(wc -l <"$OUT/logcat_all.txt")
  # Same shape as the drags above: a 5 s swipe in the background, one dump 4 s in — the finger is
  # then at the bottom centre and the X must be up. The buttons window and the X come out of the
  # same dump (`frame` matches the package, `window` the title; see android/HANDOFF.md F6).
  dump="$OUT/dismiss_windows.txt"
  sh1 "(input swipe $gx $gy $tx $ty 5000 >/dev/null 2>&1 &); sleep 4; dumpsys window windows" >"$dump"
  held=$(python3 "$BENCH/bench.py" frame "$PKG" <"$dump")
  xf=$(python3 "$BENCH/bench.py" window MotopartyDismiss <"$dump")
  log "dismiss: mid-drag buttons at ${held:-none}, X target at ${xf:-NO MotopartyDismiss WINDOW}"
  row dismiss-target "$rot" "$([ -n "$xf" ] && echo "ok X up mid-drag, buttons $held" || echo "FAIL no MotopartyDismiss window")" "$xf"
  sleep 3                                       # the swipe ends ~1 s after the dump, then ~2 s
  after=$(overlay_frame)
  pref1=$(prefs)
  en=$(sh1 run-as $PKG cat shared_prefs/settings.xml | grep -o 'name="overlayEnabled" value="[a-z]*"')
  ov=${en##*value=\"}; ov=${ov%\"}              # true / false / empty if the key is not there
  nt=$(tail -n +$(( mark + 1 )) "$OUT/logcat_all.txt" | grep -c 'trigger TALK')
  nh=$(tail -n +$(( mark + 1 )) "$OUT/logcat_all.txt" | grep -c 'hidden by drag to the X')
  bad=
  [ -n "$after" ] && bad="$bad buttons-still-up($after)"
  [ "$ov" = false ] || bad="$bad overlayEnabled=${ov:-absent}"
  [ "$pref1" = "$pref0" ] || bad="$bad fx/fy-changed"
  [ "$nt" = 0 ] || bad="$bad trigger-TALK-x$nt"
  note="ok pref=false fx/fy=same no stray trigger"
  [ -n "$bad" ] && note="FAIL:$bad"
  # Hub.log goes to Log.i(tag Motoparty); it is a nice-to-have, never the verdict.
  note="$note, Hub.log line: $([ "$nh" = 0 ] && echo no || echo yes)"
  log "dismiss: after release buttons=${after:-none}, overlayEnabled=${ov:-absent}, fractions $pref1," \
      "trigger TALK x$nt, 'hidden by drag' x$nh"
  row dismissed "$rot" "$note" "$after"
  restore_overlay
  rframe=$(overlay_frame)
  delta=$(frame_delta "$rframe" "$f")
  if [ "$delta" -le 8 ]; then
    note="ok back at the pre-drag frame (off by $delta px)"
  else
    note="FAIL back at ${rframe:-NO WINDOW}, was $f"
  fi
  log "dismiss: restored to ${rframe:-none} (was $f), fractions $(prefs)"
  row restored "$rot" "$note" "$rframe"
}
[ "${DISMISS:-1}" = 1 ] && dismiss_step "${ENDROT:-0}"

logcat_dump
python3 "$BENCH/bench.py" overlay "$OUT" | tee "$OUT/summary.txt"
