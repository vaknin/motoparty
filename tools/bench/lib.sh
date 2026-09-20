# Shared by the device bench scripts in tools/bench/. Source it, don't run it.
#
# Env for every script:
#   PHONE   adb serial of the Pixel (default 192.168.1.100:5555; always -s, adb lists it twice)
#   HOST_IP the phone's address on the peer's network. Derived from PHONE, which only works for an
#           `ip:port` serial — with a USB or mDNS serial (`adb-…._adb-tls-connect._tcp`) set it.
#   DRY=1   no phone: start `motoparty-peer host` on 127.0.0.1 and run the same peer-side steps
#           against it. Checks the script and the summary, not the app.
#
# Every adb call goes through `timeout 30`: an offline transport makes plain adb block for ever.
set -u
BENCH=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PEER=$(cd "$BENCH/../peer" && pwd)
PKG=com.kivan.motoparty
PHONE=${PHONE:-192.168.1.100:5555}
DRY=${DRY:-0}
ADB="timeout 30 adb -s $PHONE"
if [ "$DRY" = 1 ]; then HOST_IP=127.0.0.1; else HOST_IP=${HOST_IP:-${PHONE%:*}}; fi

# bench_init <out-dir>: OUT (absolute: the peer runs with cwd = tools/peer), run.log.
bench_init() {
  OUT=${1:?usage: $(basename "$0") <out-dir>}; mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
  : >"$OUT/run.log"
  CLEANUPS=()
  trap bench_cleanup EXIT; trap 'exit 1' TERM INT HUP
  log "$(basename "$0") DRY=$DRY host=$HOST_IP out=$OUT"
}
log() { echo "$(date +%T.%3N) $*" | tee -a "$OUT/run.log" >&2; }
# on_exit <cmd…>: run at exit, last registered first (restores rotation, permissions, …).
on_exit() { CLEANUPS+=("$*"); }
bench_cleanup() {
  local i
  for ((i=${#CLEANUPS[@]}-1; i>=0; i--)); do log "cleanup: ${CLEANUPS[i]}"; eval "${CLEANUPS[i]}" >>"$OUT/run.log" 2>&1; done
  # The fake host ignores EOF on stdin: say quit, then kill whatever of its tree is left.
  if [ -n "${FAKE_HOST_PID:-}" ]; then
    { echo quit >&7; exec 7>&-; } 2>/dev/null; sleep 1
    pkill -f "motoparty-peer host --no-mdns --bind 127.0.0.1 --name bench" 2>/dev/null
    FAKE_HOST_PID=
  fi
  return 0
}
# Epoch-stamp every line, so client.log / host.log line up with the phone's logcat.
stamp() { python3 -u -c 'import sys,time
for l in sys.stdin: sys.stdout.write("%.3f %s" % (time.time(), l)); sys.stdout.flush()'; }
sh1() { $ADB shell "$@" 2>&1 | tr -d '\r'; }

# adb_gate: the phone answers, and log what the phone is doing right now. Exits if unreachable.
adb_gate() {
  [ "$DRY" = 1 ] && { log "dry run: no phone"; return 0; }
  local i
  for i in 1 2 3 4; do
    [ "$(timeout 10 adb -s "$PHONE" get-state 2>&1 | tr -d '\r')" = device ] && break
    { timeout 10 adb connect "$PHONE"; sleep 2; } >>"$OUT/run.log" 2>&1
  done
  [ "$(sh1 echo ok)" = ok ] || { log "FAILED: no adb to $PHONE"; exit 1; }
  log "phone: $(sh1 dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*'), top: $(top_activity)"
  APP_PIDS=$(sh1 pidof $PKG); log "app pid: $APP_PIDS"
  log "bluetooth: $(sh1 dumpsys bluetooth_manager | grep -m2 -i -E 'mActiveDevice|A2dp.*active' | paste -sd' ')"
}
# need_bt: the talk/music benches measure the AirPods (HFP/A2DP); without a Bluetooth audio device
# they would quietly measure the earpiece/speaker. NO_BT=1 runs anyway.
need_bt() {
  [ "$DRY" = 1 ] || [ "${NO_BT:-0}" = 1 ] && return 0
  local dev; dev=$(sh1 dumpsys audio | grep -m1 'Active communication device')
  log "audio device: ${dev:0:120}"
  [[ $dev == *type:bt_* || $dev == *type:ble_* ]] && return 0
  log "FAILED: no Bluetooth audio device connected (AirPods in their case?). Connect them, or NO_BT=1"; exit 1
}
top_activity() { sh1 dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity' | grep -o '[a-z][a-zA-Z0-9_.]*/[a-zA-Z0-9_.$]*' | head -1; }
awake() { sh1 dumpsys power | grep -q 'mWakefulness=Awake'; }

# phone_free: before injecting input. If the screen is on and another app (not ours, not the
# launcher) is in front, the user has the phone: wait, up to 5 min, then give up.
phone_free() {
  [ "$DRY" = 1 ] && return 0
  local i top
  for ((i=0; i<30; i++)); do
    top=$(top_activity)
    if ! awake || [[ $top == $PKG/* || $top == *launcher* || -z $top ]]; then return 0; fi
    [ "$i" = 0 ] && log "phone in use ($top); waiting"
    sleep 10
  done
  log "FAILED: phone still in use after 5 min ($top)"; exit 1
}

# The Pixel's main buffer is 256 KiB and rolls over in < 2 min, so `logcat -d` at the end loses
# the start of a run: stream everything to logcat_all.txt from the start instead.
logcat_begin() {
  [ "$DRY" = 1 ] && return 0
  $ADB logcat -c
  timeout 3600 adb -s "$PHONE" logcat -v epoch >"$OUT/logcat_all.txt" 2>&1 &
  LOGCAT_PID=$!     # timeout's pid; killing it passes the TERM on to adb
  on_exit "kill $LOGCAT_PID 2>/dev/null"
}
# Epoch timestamps, only our process: every pid the app had during the run (restart_app adds
# the new one), so a restart mid-run keeps both halves.
logcat_dump() {
  [ "$DRY" = 1 ] && return 0
  APP_PIDS="${APP_PIDS:-} $(sh1 pidof $PKG)"
  sleep 1; kill "$LOGCAT_PID" 2>/dev/null; sleep 0.5
  tr -d '\r' <"$OUT/logcat_all.txt" | awk -v p=" $APP_PIDS " 'index(p, " " $2 " ")' >"$OUT/logcat.txt"
  $ADB shell 'logcat -d -v epoch -b crash' | tr -d '\r' >"$OUT/crash.txt" 2>&1
}

# fake_host [args…]: DRY only. The peer's fake host on 127.0.0.1; its stdin is fd 7 (host_cmd).
fake_host() {
  [ "$DRY" = 1 ] || return 0
  mkfifo "$OUT/host.in"
  (cd "$PEER" && exec uv run motoparty-peer host --no-mdns --bind 127.0.0.1 --name bench "$@" \
    <"$OUT/host.in" 2>&1 | stamp >"$OUT/host.log") &
  FAKE_HOST_PID=$!
  exec 7>"$OUT/host.in"
  sleep 3
}
host_cmd() { [ "$DRY" = 1 ] && echo "$*" >&7; }

# keep_awake: until exit, poke the screen every 15 s so it can't time out and relock mid-run
# (a locked phone hides the overlay). Needs the phone unlocked to begin with. KEYCODE_WAKEUP alone
# does not reset the screen-off timer of a screen that is already on (lastUserActivityTime stays,
# checked 2026-09-20); a no-op KEYCODE_F13 counts as user activity and does.
keep_awake() {
  [ "$DRY" = 1 ] && return 0
  ( while :; do $ADB shell 'input keyevent KEYCODE_WAKEUP; input keyevent KEYCODE_F13' >/dev/null 2>&1; sleep 15; done ) &
  on_exit "kill $! 2>/dev/null"
}
locked() { sh1 dumpsys window | grep -q -E 'isKeyguardShowing=true|mShowingLockscreen=true'; }

# overlay_frame: "x1 y1 x2 y2" of our TYPE_APPLICATION_OVERLAY window, empty if not shown.
overlay_frame() { sh1 dumpsys window windows | python3 "$BENCH/bench.py" frame "$PKG"; }
# host_talk: press TALK on the host. DRY: the fake host's `talk`. Phone: tap the overlay's TALK
# zone (the upper of its two zones); LinkService's TALK intent is not exported.
host_talk() {
  if [ "$DRY" = 1 ]; then host_cmd talk; return; fi
  phone_free
  # A locked phone hides every overlay (the frame is still reported): the tap would hit the
  # lock screen. Needs the phone unlocked.
  if sh1 dumpsys window | grep -q 'isKeyguardShowing=true'; then
    log "keyguard showing (phone locked): overlay hidden, cannot press TALK"; return 1
  fi
  local f; f=$(overlay_frame)
  [ -n "$f" ] || { log "no overlay window: cannot press TALK"; return 1; }
  set -- $f
  sh1 input tap $(( ($1 + $3) / 2 )) $(( $2 + ($4 - $2) / 4 )) >/dev/null
}

# peer_client <seconds> [args…]: the peer as the iPhone, commands from stdin, into client.log.
peer_client() {
  local secs=$1; shift
  (cd "$PEER" && timeout "$secs" uv run motoparty-peer client --host "$HOST_IP" "$@" 2>&1) | stamp >"$OUT/client.log"
}

# restart_app: force-stop and launch the main activity (the host service starts from it).
restart_app() {
  sh1 am force-stop $PKG >/dev/null
  sh1 monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null
  sleep 4
  local pid; pid=$(sh1 pidof $PKG); APP_PIDS="${APP_PIDS:-} $pid"
  log "app restarted, pid $pid"
}
