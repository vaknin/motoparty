# Coordinator handoff (2026-09-19, ~23:00; fourth coordinator session, Part A of its plan)

For the Claude session that coordinates Motoparty. You own `PROTOCOL.md`, `fixtures/`, the
root docs, `tools/bench/` and the decisions; component work goes to background subagents, one
per directory, with a final report back to you; they don't commit. You re-run their tests
yourself before telling the user anything is done, and keep this file current.

## Read first

1. `~/.claude/plans/motorcycle-communication-application-proud-pixel.md`: the approved plan.
   (Its "5 GHz hotspot" is moot, see "Hotspot" below.) Tonight's coordinator plan (Part A no
   phone, Part B device sessions D1–D4): `~/.claude/plans/pasted-content-id-4913-you-are-optimized-naur.md`.
2. `PROTOCOL.md`: the wire contract. It is the source of truth over any code comment.
3. `fixtures/`: shared test vectors. All three implementations pass the current set.
4. Component notes: `android/HANDOFF.md` (the most detailed), `android/README.md`,
   `ios/README.md`, `tools/peer/README.md`, and `tools/bench/README.md`.

## Do now

The user left the house after approving the plan: **no phone, adb or Bluetooth work until they
say the phone is free.** Part A (no phone) ran in this session: A1 Android layer-1 review, P1
peer `--no-mdns`, I1 iOS audit, C1 bench scripts, C2 docs; A2 (Android layer 2 tests + drift
trace) runs after A1. What is left, in order:

1. Part A is done and verified (all rows below). Offer commit 3 (nothing since `cdc33c6` is
   committed; the user did not ask for the checkpoint commit 2).
2. When the user says the phone is free: **Part B**. First the gate (below, by the
   coordinator), then D1 → D2 → D3 → D4, one agent at a time, prompts below. If the AirPods are
   not around, D3 and D4 first. After each: read its summary, a separate offline fix agent if
   something failed, then the same script again.
3. After D1–D4 and their fixes: commit 4 on the user's word.

## Part B progress (2026-09-19, ~23:15 onwards; the user said the phone is free, AirPods connected)

- Gate done: current build installed at ~23:15. YouTube (ReVanced) owns the media button.
- **D1 talk** (`results/2026-09-19-d1-talk-2`): Main no longer blocks (answers 9–117 ms), always
  back in MODE_NORMAL, no exceptions, capture alive (silence = DTX). But open→HFP median 397 /
  max 2124 ms, rising 185→503 over 6 cycles; close→media ~60 or 630–850 ms; the fast re-open
  replayed close+open (2124 ms); run 1 (`…-d1-talk`) had one 20 s talk with `950 late, 8 played`.
  D1 also fixed the bench: logcat is now streamed (the 256 KiB buffer rolled over), audio-mode
  grep uses `mAudioModeOwner` (mMode=0 = normal).
- **F1 offline fix** (105 tests): jitter buffer re-anchors after 100 ms of steady lateness (the
  950-late bug, reproduced in tests); new `audio/TalkAudio.kt` settles to the latest wanted state
  (a queued close+open collapses; re-open during teardown keeps the route); step timing lines
  (`enterCall N ms (…)`, `exitCall`, `VoiceEngine: start/stop/capture/playback …`); earcon
  AudioTracks now released (suspected cause of the rising open time). audioserver's
  `setDevicesRoleForStrategy` takes 1.0–1.25 s per switch (not our code).
- **D2 music** (`results/2026-09-19-d2-music`): the overshoot is the **ExoPlayer position
  reading flipping between two levels ~200 ms apart** on A2DP, at speed 1.0 too; the nudge math is
  right. Post-talk resume fine (±80 ms after ~14 s).
- **F2 offline fix** (111 tests): drift = median of 9 samples over 2 s; nudge only on two
  agreeing readings 2 s apart (`check: median err … waiting for confirmation`); re-seek >1 s on
  one reading; `nudge done` 1 s after the reset. Root cause (Media3 position tracker on a
  deep-buffer track over A2DP) not chased.
- **D3 unavailable** (`results/2026-09-19-d3-unavailable`): (b) host mic revoked → refused in
  23 ms, state.talk never true, permission restored. **(a) not run: the phone was locked and the
  lock screen hides the overlay.** Scripts now refuse to tap when locked and keep the screen
  awake (`keep_awake`) once unlocked. The app restarts cleared the queued track.
- F1+F2 build installed ~23:40. **The AirPods disconnected at 23:31** (before the re-run), so
  `…-d1-talk-3` ran on the earpiece: fast re-open collapsed (re-open → playback up ~200 ms), capture
  97–99 % (read = device frames, so the old shortfall was route/start-up, not the loop), max 5 late,
  0 re-anchors, MODE_NORMAL, 0 errors; step timings without BT: enterCall 2–29 ms, exitCall 7–56,
  voice stop 106–166 (join capture), capture up ~500–580, playback up ~300–350. The HFP numbers
  and the rising-open question still need the AirPods. The music re-run was not started (it would
  have played on the speaker). Earcon tracks log no `AudioTrack: stop(` on this phone, but the
  audio event log shows each earcon player released. talk_cycles/music_sync now refuse to run without a
  Bluetooth audio device (`need_bt`; `NO_BT=1` overrides).
- Waiting on the user: (1) AirPods connected → re-run `talk_cycles.sh …-d1-talk-4` and
  `music_sync.sh …-d2-music-2`; (2) phone unlocked on the home screen → D4 overlay rotation +
  D3 (a) re-run. The build on the phone is current (F1+F2); no re-install needed.

## Status

| Component | State |
|---|---|
| `tools/peer` (Python, uv) | Done, incl. local volume + `unavailable`. `uv run pytest`: 161 pass. Client stdin `unavailable` / `--mic-unavailable`, fake host `mic on\|off`; `raw <json>` sends the now-invalid volume action on purpose. New 2026-09-19 (P1): client `--no-mdns` skips Bonjour and goes straight to the /24 sweep (mutually exclusive with `--host`; the /24 comes from the laptop's own interface addresses). Untried on the hotspot. |
| `tools/bench` | **New device scripts (2026-09-19, C1), see `tools/bench/README.md`:** `talk_cycles.sh`, `music_sync.sh`, `unavailable.sh`, `overlay_rotation.sh`, shared `lib.sh`, summaries in `bench.py` (`summary.txt`, last line `VERDICT:`). Peer side dry-run on loopback (`DRY=1`) for the first three; summaries checked against the real BT-bench logcat. **Every adb step unproven** until D1–D4. Older: `hotspot_test.sh <out-dir>` (joins the Pixel hotspot, runs the peer through screen-on / screen-off / music / talk phases with a 5 Hz ICMP ping alongside, always restores home Wi-Fi) and `hotspot_rtt.py <out-dir>` (pong + ICMP per phase). Proven end to end on 2026-09-19 (the BT-on run); fixed then: relative out-dir, adb `offline` transport over the hotspot blocking for ever (now reconnect + `timeout 30` on every adb call), `GW=` override for a dry run. Results: `tools/bench/results/2026-09-19-bt-off-2.4ghz/` and `…-bt-on-2.4ghz/`. |
| `ios/` (SwiftPM + xtool) | `swift test`: 66 pass on Linux. The app compiles for arm64-apple-ios with 0 errors / 0 warnings: `cd ios && xtool dev build [--ipa] [-c release]` (unsigned, no phone or login needed). Drift rule (rate nudge), local command parser, local volume (`Audio/LocalVolume.swift`, hidden MPVolumeView slider, unproven) and `unavailable` are in. Swift 5 mode; strict concurrency (~140 warnings) deliberately not done. **Never run on a device**; `ios/README.md` lists what only a real iPhone can answer. No iPhone available for now. **Audit 2026-09-19 (I1), 6 fixes in `AppModel.swift` only** (coordinator re-ran: 66 tests, `xtool dev build` 0 errors / 0 warnings, diff read): a mic-permission prompt still up when the host closed talk no longer opens/refuses a finished talk (`talkOpenPending`); a failed open no longer resumes music along the stale anchor (waits for the host's `music.play`); after a voice command it rejoins the current anchor, not the player's last one; `interruptionEnded` goes back to media mode instead of re-activating a command/talk route; a media-services reset during talk now tells the host (`talk.close`); `state` without music clears title/now-playing. Reported, not changed: a call interruption mid-talk closes with `"trigger"`, not `"unavailable"` (harmless, the host treats both as a close); up to 3 `music.ready` for one id on a mid-track join (check how Android handles duplicates); clock offset not reset on reconnect; `hello.proto` unchecked; `TrackCache` marks a file cached before the decode check ends. New device questions for the iPhone list: whether the permission prompt appears while backgrounded/locked, which route-change notifications the A2DP↔HFP switch posts, whether `AVPlayer`/KeepAlive survive a media-services reset. |
| `android/` | Committed state (`cdc33c6`): 65 tests, verified on the Pixel (31 min locked soak, talk/music/TTS while locked and dozing, silence close, overlay taps, framing conformance, volume dropped as malformed). **Uncommitted, all verified by the coordinator 2026-09-19 ~23:00: `./gradlew assembleDebug test` = 94 tests, 0 fail, 2 skipped (network); diffs read.** Layer 1: `audio/AudioThread.kt` (one serial thread for every route change and voice start/stop, so talk no longer blocks Main ~2.7 s), overlay position as a clamped fraction (`overlay/OverlayPlacement.kt`), first-check-after-talk in `SyncController`. A1 review fixed 4 bugs in it: stale callbacks of an old talk acting on the next one (`talkSession` counter), a capture loop that outlived `stop()` reviving under the next `start()` (per-start session in VoiceEngine), a recognizer route-back dropped at service stop leaving MODE_IN_COMMUNICATION (`AudioRouter.exitAll()`), `exitCall` not reaching MODE_NORMAL if `clearCommunicationDevice` threw (`finally`); +6 `AudioThreadTest`. A late mic failure now ends talk as `talk.close{by:host,reason:unavailable}` (PROTOCOL clarified). Layer 2 (logging): per-talk counters in `core/JitterBuffer.kt`, `talk stats:` line built by new `core/TalkStats.kt` (format unchanged, pinned by a test), and a 1/s `trace:` line in `SyncController` during every nudge + 5 s after, 5 s after every start and re-seek (format in `android/HANDOFF.md` §3 and `tools/bench/README.md`); a test proves the trace changes no player call. A2's tests found 2 jitter bugs, fixed: seq span measured from the first seq instead of the lowest (hid losses); a late last packet before DTX made the pause look like loss (kept concealing, skipped the +20 ms target raise) — **a small voice playback change, unverified on the device**. Installed on the Pixel: only the layer-1 build of 21:40; the gate installs the current one. Phone left as found at 22:20: permissions granted, rotation untouched, music paused, audio mode normal. |
| Phase 0 spikes | Spike 1: SDK + `xtool auth` done, the install waits for an iPhone. Spikes 2–4 need the iPhone, AirPods and rides. |
| git | Initial commit `cdc33c6` on `master`. Everything after it is uncommitted (Android fixes, `tools/bench/`, this file). Commit only when the user asks. |

## Spec decisions made today (already in PROTOCOL.md and implemented everywhere unless noted)

- DTX frames are not sent (`OPUS_GET_IN_DTX`), so every audio packet is voice activity. The
  silence close relies on that. Unverified in practice: the AirPods mic never goes quiet
  enough; wind noise on a ride may keep talk open (Spike 4 decides).
- `ts` is a running 16 kHz clock; keepalives share `seq`; a `ts` jump with contiguous `seq`
  is silence, a `seq` gap is loss. Jitter underrun = late packet; raise at most once per spurt.
- Malformed known message = drop and keep the connection; not JSON / not an object /
  oversize = close. Fixture lists `malformed` and `fatal` in `control/framing.json`.
- Clock: negative-RTT samples take no slot; a jump of more than 500 ms clears the window
  (the iOS monotonic clock stops while the device sleeps).
- Mid-track join: the host re-sends `music.load` (current + next), then `music.play`.
- Command normalisation is per code point, and U+2019 maps to an apostrophe.
- **Music drift (changed last):** A2DP adds 350–700 ms of fresh lag on every play or seek,
  so the host starts early by the measured delay, corrects 80 ms–1 s of drift by changing
  the playback rate by up to ±5 %, and re-seeks only above 1 s. Both phones do this now.
  iOS: `DriftController` in MotopartyCore (rate = clamp(1 − e/4000, ±5 %), 0.5 % steps,
  re-check every 2 s while correcting, hold until |e| ≤ 40 ms), lead = `AVAudioSession.outputLatency`
  + user trim. Android instead sets the speed once for a computed time and learns its start lead.
  **Check on the first real iPhone run:** does `setRate(_:time:atHostTime:)` already compensate
  output latency (then outputLatency is double-counted; go back to trim only); does assigning
  `rate` disturb the scheduled start on A2DP; is `.spectral` pitch algorithm OK on CPU
  (`.timeDomain` is the fallback).

## Protocol gaps: decided with the user 2026-09-19 (in PROTOCOL.md, fixtures and all three implementations)

1. **Volume is local.** `volumeUp`/`volumeDown` removed from `music.control`; a phone's spoken
   or pressed volume changes that phone only and is never sent. The client parses its own
   utterances first; a host that still gets a volume utterance answers "Didn't catch that".
   A value outside an enum = malformed (drop, keep).
2. **Talk is not negotiable** (the user was explicit: no decline button or setting, ever).
   `talk.close` reason `"unavailable"` exists only for "cannot open the mic" (cellular call,
   permission missing, route failure), in either direction. Not yet benched on the device.
3. **No host→client ping.** Won't do.
4. (Coordinator, 2026-09-19 23:00, a clarification of 2, not a new rule.) A mic that fails
   *after* talk opened ends talk with `reason:"unavailable"` too: the host broadcasts
   `talk.close{by:"host",reason:"unavailable"}` + `state{talk:false}`. Android does this since A1;
   the peer already handles it. Added to PROTOCOL.md "Talk flow" step 4.

## Hotspot

- **5 GHz hotspot is not available on the user's Pixel ("not available in your country"), so
  2.4 GHz is the real target.**
- Test of 2026-09-19, Bluetooth OFF, hotspot on 2.4 GHz ch 11, Pixel kept home Wi-Fi as
  upstream (STA+AP), laptop internet worked through it: Bonjour found the app in 1.4 s
  (Pixel = gateway, 10.148.67.218 that time; Android randomises the subnet). Ping RTT screen
  on: median 10 / p90 15 / max 100 ms (n=44); screen off + dozing: median 14 / p90 18 / p99 48
  / max 121 ms (n=105), none over 200 ms. So the home-Wi-Fi screen-off tail (p99 229 ms) does
  not show on the SoftAP. talk.open answered in 23 ms while dozing.
- **Bluetooth ON test (2026-09-19 22:00, AirPods Pro connected, same 2.4 GHz ch 11, phone ~1 m
  away, stationary): good enough for talk; don't raise the jitter ceiling.** Coordinator re-ran
  `hotspot_rtt.py` on both result dirs; numbers hold. Pong RTT median / p90 / p99 / max, ms:
  screen on 14/41/80/80 (n=30; BT off 10/15/100/100), screen off 15/38/86/86 (n=60; BT off
  14/18/48/121), music over A2DP 20/52/94/94 (n=60), talk over HFP 14/30/118/118 (n=21; BT off
  n=5 only). Nothing over 200 ms in any phase. ICMP at 5 Hz: median 6–7 ms everywhere, p99
  51–67 ms, 1 lost packet of ~1900, worst 202 ms during the voice-command resolve + TTS, not
  during talk. Talk (30 s): 0 lost packets (fec=0 plc=0), 1 late packet, jitter target 40 → 60 →
  back to 40 ms. 5.7 MB track over the hotspot in 830 ms. No link drops; the Pixel kept home
  Wi-Fi upstream. So Bluetooth adds ~20 ms to the p90 tail and nothing that matters.
  Still untested: a ride (motion, weaker link, two real voices). The iPhone-as-5-GHz-AP fallback
  is not needed on these numbers.
- Seen in that run, handed to the Android agent: host music drift on A2DP oscillates (±150–220 ms
  swings after each rate nudge, never settles, no re-seek); the host mic sent nothing for the
  last 21 s of the talk (no seq gaps, host keepalives kept coming, so most likely DTX in a quiet
  room); `VoiceEngine` logs no receive stats, so host-side loss can't be counted yet.
- Laptop side: NetworkManager, one Wi-Fi card (`wlp1s0`), so the laptop leaves home Wi-Fi
  ("Aviv's") during the test. Use the NM profile **`Aviv's Hotspot 1`** (the profile
  `Aviv's Hotspot` is bound to a dead interface name and fails). adb over the hotspot works at
  `<gateway>:5555`. The Pixel's hotspot can't be toggled from adb without root; the user turns it on.
- Not done: the /24 sweep fallback over the hotspot (the peer client has no `--no-mdns`).

## Waiting on the user

- An iPhone on USB with Developer Mode on, then `cd ios && xtool dev` (Spike 1). The user
  logged into `xtool auth` with their **personal Apple ID**, was told about the 0xe8008024
  ban reports and decided to keep using it; don't raise it again.
- Hands-on tests: speak real commands through the AirPods ("play album abbey road", "next",
  "louder"); listen to a talk session for quality and that the "live" earcon is audible after
  the HFP switch; a ride.

## Environment facts

- The user's rules: never run sudo (hand over a `!`-prefixed one-liner); log toolchain
  changes in `~/AGENTS.md` (Swift 6.3.3, xtool 1.19.2, the Darwin SDK, NDK, CMake, usbmuxd and
  libxml2-legacy are logged). The user wants plain-language status, agents kept under
  ~200k tokens (they stopped one at 210k and restarted the coordinator too): give agents
  narrow tasks and have them keep their HANDOFF current.
- Swift is not on PATH by default: `~/.local/share/swift/swift-6.3.3-RELEASE-ubuntu24.04/usr/bin`.
- Pixel 8 (Android 17) over adb Wi-Fi, listed twice; always `adb -s 192.168.1.100:5555` on
  home Wi-Fi. The laptop is 192.168.1.102. `KEYCODE_SLEEP` locks the phone, `KEYCODE_WAKEUP`
  wakes it; no secure keyguard got in the way so far. The user sometimes picks the phone up:
  check `dumpsys power | grep mWakefulness` and the foreground activity before injecting input.
- The user's AirPods are paired to the Pixel: bench audio plays in their ears. Keep it short
  unless the test is about exactly that, and say so beforehand.
- Laptop firewall (nftables, input drop policy): the peer in *client* mode needs nothing. For
  fake-host mode ports 47800-47802 must be opened by the user (command in `tools/peer/README.md`).

## Part B: device sessions (only after the user says the phone is free)

### Gate (the coordinator, before any agent)

1. `timeout 10 adb -s 192.168.1.100:5555 get-state` = `device` (else `adb connect`, and if the
   phone is off home Wi-Fi, ask the user).
2. What the phone is doing: `dumpsys power | grep mWakefulness`, the foreground activity
   (`dumpsys activity activities | grep -m1 topResumedActivity`), is an A2DP/HFP device connected
   (`dumpsys bluetooth_manager | grep -iE 'mActiveDevice|A2dp.*active'`, `dumpsys audio | grep -iE 'mode|communication'`),
   who owns the media button (`dumpsys media_session | grep -iE 'media button|active'`).
3. Tell the user plainly whether sound will play in their AirPods in the next session, and wait
   for their go.
4. Install the current build and grant, one command so every agent starts from the same build:
   `cd android && ./gradlew installDebug && adb -s 192.168.1.100:5555 shell pm grant com.kivan.motoparty android.permission.RECORD_AUDIO`
   (+ POST_NOTIFICATIONS / overlay as `android/HANDOFF.md` lists), then launch the app once
   (`monkey -p com.kivan.motoparty -c android.intent.category.LAUNCHER 1`) and check the overlay is on.

### Order

| Order | Session | Script | Needs from the user | Sound in AirPods |
|---|---|---|---|---|
| D1 | Talk timing, fast re-open, mic-silence (DTX or dead loop) | `talk_cycles.sh` | phone left alone ~5 min | earcons, short |
| D2 | Music: resume after talk + 3 min A2DP drift trace; reading jump vs overshoot | `music_sync.sh` | AirPods connected, phone alone ~6 min | ~5 min of music |
| D3 | The two `unavailable` cases | `unavailable.sh` | phone alone ~3 min | one error earcon |
| D4 | Overlay after rotation | `overlay_rotation.sh` | phone alone and **unlocked** ~3 min | none |

D1 first: everything rides on the talk path. If the AirPods are not around, D3 and D4 first.
Home Wi-Fi is enough for all four. Only for the user's own hands afterwards: real spoken
commands, listening to a talk, a ride.

### Device agent prompt (fill in `<SCRIPT>`, `<QUESTION>`, `<EXTRA>` from the table and notes)

```
You are running one device bench session for Motoparty (/home/kivan/Work/motoparty), Android
host app on a Pixel 8 over adb Wi-Fi. The coordinator has already installed the current build
and checked the phone. Your job: run ONE script, read its summary, answer ONE question, report.
You fix nothing big on the spot: a failure gets written up; a separate offline agent fixes it.

Session: <QUESTION>

Read first (only these): /home/kivan/Work/motoparty/tools/bench/README.md, the header of
tools/bench/<SCRIPT>, and android/HANDOFF.md section 3 (state / what to verify).

Run: `cd /home/kivan/Work/motoparty && tools/bench/<SCRIPT> tools/bench/results/2026-MM-DD-<name>`
(results dir name: today's date + a short name). Then read summary.txt. Grep, don't read,
logcat.txt (it can be long).

The script is new and its adb steps are unproven. If it breaks on an adb detail (a dumpsys
format, a tap position, a grep), you MAY fix tools/bench/*.sh or bench.py (keep the fix small,
keep DRY=1 working: `DRY=1 tools/bench/<SCRIPT> <scratch dir>` must still pass for the three
scripts that have a dry run) and run again. Don't edit android/, ios/, tools/peer/, PROTOCOL.md.

<EXTRA>

Rules
- Never run sudo. No git commits. Always `adb -s 192.168.1.100:5555`, every call wrapped in
  `timeout` (an offline transport blocks for ever). Only install/grant/revoke for
  com.kivan.motoparty. Don't change Wi-Fi, hotspot or Bluetooth state.
- The user may pick the phone up: the scripts wait if another app is in front with the screen on;
  if you run adb by hand, check `dumpsys power | grep mWakefulness` and the foreground activity
  first. Leave the phone as found: music paused, locked if it was locked, settings restored.
- At most two runs of the script. Stop and report at ~100k tokens.

Final report (concise, for the coordinator): the summary's key numbers, the answer to the
question with the evidence lines, anything the script needed fixed, what's still unverified.
```

Per-session `<QUESTION>` / `<EXTRA>`:

- **D1** `talk_cycles.sh`. Question: over 6 cycles + a fast open→close→open + a 20 s talk, are
  open→HFP and close→media well under 300 ms every time (before layer 1: ~2.7 s each), does
  the phone always end in media mode, any exceptions; and in the 20 s talk, is the host capture
  loop alive (captured ≈ 50/s) with the silence being DTX, or stalled? Extra: before layer 1,
  the only data point was open→HFP 13 ms / close→media 79 ms (one cycle, BT bench).
- **D2** `music_sync.sh`. Question: does the speed-up overshoot come from the nudge or from a
  position reading that jumps? Read the `trace:` lines (1/s during a nudge + 5 s after, 5 s
  after each start; `pos`, `expected`, `err`, `speed`, …): if `err` steps by ~150–220 ms within
  one second while speed is 1.0 (or the step does not scale with the nudge size), it is the
  reading; if `err` moves steadily during the nudge and ends past zero in proportion to the
  nudge, it is the nudge. Also: the post-talk sequence (`start cold: lead …` and the drifts
  after it; before: −693 → +428 → +173 → +93 ms, ~25 s to settle; last night: lead 442, then −317).
  Extra: the coordinator's analysis of last night: every speed-up landed ~+150..+220 ms ahead
  whatever its size (−91 → +221, −189 → +147, −185 → +149), every slow-down within 20–30 ms,
  and the reading dropped 140–220 ms by itself between checks with no speed change. Decide
  with data and propose (don't implement) the fix.
- **D3** `unavailable.sh`. Question: do both refusal paths behave as PROTOCOL.md "Talk flow"
  says, and is RECORD_AUDIO granted again at the end (`permission.txt`)? Extra: case (a) taps
  the overlay TALK zone: if the tap misses, find the right spot from `dumpsys window` and fix
  `host_talk` in lib.sh.
- **D4** `overlay_rotation.sh`. Question: in every rotation is the overlay fully on screen, does
  a TALK tap reach the app (`trigger TALK` in logcat), and is it still on screen after a drag to
  the far corner; are the rotation settings back at the end? Extra: needs the phone unlocked; if
  the script says locked, stop and report (don't try to unlock).
