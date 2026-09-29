#!/usr/bin/env bash
# Hotspot bench: move the laptop onto the Pixel's hotspot, run tools/peer against the app,
# and ALWAYS put the laptop back on home Wi-Fi (also on error, Ctrl-C or timeout).
# Runs unattended: while it runs, the laptop's internet goes through the Pixel.
#
#   tools/bench/hotspot_test.sh <out-dir>
#   tools/bench/hotspot_rtt.py  <out-dir>        # RTT per phase afterwards
#
# Env: HOME_WIFI, HOTSPOT (NetworkManager profile names), IFACE,
#      ON_S / OFF_S / MUSIC_S / TALK_S (phase lengths in seconds; 0 skips a phase),
#      MUSIC_CMD (utterance for `say`, e.g. "play song bohemian rhapsody"; empty = no music phase).
set -u
# NOTE: OUT must be absolute: the peer runs with cwd = tools/peer further down.
OUT=${1:?usage: hotspot_test.sh <out-dir>}; mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
: >"$OUT/run.log"; : >"$OUT/phases"
HOME_WIFI=${HOME_WIFI:-"Aviv's"}; HOTSPOT=${HOTSPOT:-"Aviv's Hotspot 1"}; IFACE=${IFACE:-wlp1s0}
ON_S=${ON_S:-60}; OFF_S=${OFF_S:-120}; MUSIC_S=${MUSIC_S:-120}; TALK_S=${TALK_S:-30}
MUSIC_CMD=${MUSIC_CMD:-}
PEER=$(cd "$(dirname "$0")/../peer" && pwd)
log() { echo "$(date +%T.%3N) $*" | tee -a "$OUT/run.log"; }
phase() { echo "$(date +%s.%N) PHASE $1" >>"$OUT/phases"; }
restore() {
  [ -n "${PINGER:-}" ] && kill "$PINGER" 2>/dev/null
  log "restoring home Wi-Fi"
  for i in 1 2 3 4 5; do nmcli -w 40 con up "$HOME_WIFI" >>"$OUT/run.log" 2>&1 && break; sleep 5; done
  adb connect 192.168.1.100:5555 >>"$OUT/run.log" 2>&1
  log "done: $(nmcli -t -f NAME con show --active | head -1)"
}
trap restore EXIT; trap 'exit 1' TERM INT HUP

log "joining hotspot profile '$HOTSPOT'"
nmcli -w 40 con up "$HOTSPOT" >>"$OUT/run.log" 2>&1 || { log "FAILED to join hotspot"; exit 1; }
sleep 3
log "addr: $(ip -4 -br addr show "$IFACE")"
log "link: $(iw dev "$IFACE" link | grep -E 'freq|signal|tx bitrate' | paste -sd' ')"
GW=${GW:-$(ip route | awk '/^default/{print $3; exit}')}; log "gateway (Pixel) = $GW"
ping -c 10 -i 0.3 "$GW" | tail -2 | tee -a "$OUT/run.log"
log "internet: $(curl -s -m 8 -o /dev/null -w '%{http_code} %{time_total}s' https://api.anthropic.com/ || echo none)"
# 5 ICMP pings/s for the whole run, on top of the peer's 0.5/s application pongs.
ping -i 0.2 -D -W 2 "$GW" >"$OUT/ping.txt" 2>&1 & PINGER=$!
# A stale "<gw>:5555 offline" transport from an earlier run makes every -s call fail, and adb
# re-adds it on its own between a disconnect and a connect: retry until get-state says device.
ADB="timeout 30 adb -s $GW:5555"   # an offline transport makes plain adb block for ever
for i in 1 2 3 4 5 6; do
  [ "$(adb -s "$GW:5555" get-state 2>&1 | tr -d '\r')" = device ] && break
  { adb disconnect "$GW:5555"; adb reconnect offline; sleep 2; adb connect "$GW:5555"; } >>"$OUT/run.log" 2>&1
  sleep 2
done
log "adb: $($ADB shell echo ok 2>&1 | tr -d '\r')"
[ "$($ADB shell echo ok 2>&1 | tr -d '\r')" = ok ] || { log "FAILED: no adb over the hotspot"; exit 1; }
$ADB shell input keyevent KEYCODE_WAKEUP
log "app pid: $($ADB shell pidof com.kivan.motoparty | tr -d '\r')"
log "bluetooth_on=$($ADB shell settings get global bluetooth_on | tr -d '\r') a2dp: $($ADB shell dumpsys bluetooth_manager 2>/dev/null | grep -m1 -i -E 'mActiveDevice|A2dp.*active' | tr -d '\r')"
$ADB logcat -c
log "mdns browse:"; timeout 6 avahi-browse -rpt _motoparty._tcp 2>&1 | grep '^=' | tee -a "$OUT/run.log"

cd "$PEER"
TOTAL=$((ON_S + OFF_S + MUSIC_S + TALK_S + 90))
{
  sleep 12; echo stats
  if [ "$ON_S" -gt 0 ]; then
    phase screen-on
    for ((t=0; t<ON_S; t+=20)); do $ADB shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1; sleep 20; done
    echo stats
  fi
  $ADB shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1
  if [ "$OFF_S" -gt 0 ]; then phase screen-off; sleep "$OFF_S"; echo stats; fi
  if [ -n "$MUSIC_CMD" ] && [ "$MUSIC_S" -gt 0 ]; then
    # Commands are the first phrase of a talk the client opened (PROTOCOL.md "Commands"); play closes it.
    phase music-load; echo talk; sleep 3; echo "say $MUSIC_CMD"; sleep 20      # resolve + download over the hotspot
    phase music; sleep "$MUSIC_S"; echo stats
  fi
  if [ "$TALK_S" -gt 0 ]; then
    phase talk; echo talk
    for ((t=0; t<TALK_S; t+=10)); do sleep 10; echo stats; done
    echo talk; sleep 4
  fi
  [ -n "$MUSIC_CMD" ] && { sleep 6; echo pause; sleep 2; }
  phase end
  echo quit
} | timeout "$TOTAL" uv run motoparty-peer client --no-audio --tone 2>&1 \
  | python3 -u -c 'import sys,time
for l in sys.stdin: sys.stdout.write("%.3f %s" % (time.time(), l)); sys.stdout.flush()' >"$OUT/client.log"
log "client exited"
$ADB shell "logcat -d --pid=\$(pidof com.kivan.motoparty)" >"$OUT/logcat.txt" 2>&1
$ADB shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
