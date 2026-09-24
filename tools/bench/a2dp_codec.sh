#!/usr/bin/env bash
# Which Bluetooth codec the Pixel uses for music to the headset (A2DP). The music reaching the
# ears is re-encoded by this codec whatever the track format is, so it caps the quality: AirPods
# take AAC or SBC, and SBC is the noticeably worse one. Read-only; no sound; seconds.
#
#   tools/bench/a2dp_codec.sh            # PASS if the connected (else last) A2DP device is on AAC
#
# Env: PHONE (see lib.sh). The phone keeps the last negotiated config after a disconnect, so this
# also answers with the AirPods in their case. If it says SBC: Developer options → Bluetooth audio
# codec, or forget and re-pair the AirPods.
set -u
PHONE=${PHONE:-192.168.1.100:5555}
dump=$(timeout 30 adb -s "$PHONE" shell dumpsys bluetooth_manager | tr -d '\r') || { echo "FAILED: no adb to $PHONE"; exit 1; }
# One line per A2DP device: "<addr> <state> <codec> <rate> <bits> <mode>".
rows=$(awk '
  /^Profile: / { a2dp = ($2 == "A2dpService") }
  a2dp && /=== A2dpStateMachine for/ { addr = $4 }
  a2dp && /mConnectionState:/ { state = $2; sub(",", "", state) }
  a2dp && /^    mCodecConfig:/ {
    match($0, /codecName:[^,]*/); codec = substr($0, RSTART + 10, RLENGTH - 10)
    match($0, /mSampleRate:[^,]*/); rate = substr($0, RSTART + 12, RLENGTH - 12)
    match($0, /mBitsPerSample:[^,]*/); bits = substr($0, RSTART + 15, RLENGTH - 15)
    match($0, /mChannelMode:[^,]*/); mode = substr($0, RSTART + 13, RLENGTH - 13)
    print addr, state, codec, rate, bits, mode
  }' <<<"$dump")
[ -n "$rows" ] || { echo "VERDICT: FAILED — no A2DP device has ever connected (no codec config in the dump)"; exit 1; }
echo "$rows" | sed 's/^/a2dp: /'
row=$(grep -m1 STATE_CONNECTED <<<"$rows" || head -1 <<<"$rows")
set -- $row
when=$([ "$2" = STATE_CONNECTED ] && echo "connected" || echo "last connection")
if [ "$3" = AAC ]; then
  echo "VERDICT: PASS — $1 ($when) on AAC $4 $5"
else
  echo "VERDICT: FAILED — $1 ($when) on $3, not AAC"; exit 1
fi
