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

## Do now (2026-09-20, end of the sixth session's offline part; supersedes the older "Do now" below)

Everything offline is done and checked by the coordinator (Android 139 tests, peer 161, four
`DRY=1` runs, the spike builds). **Committed as `ecfc144`** (last night's F3–F5 and today's F6, F7,
bench, peer, spike, docs). **Not pushed: the repo has no git remote.** The build on the Pixel is
still F3+F4+F5.

When the user says the phone is free — gate first (install the current build), one ask at a time,
say what will sound in the AirPods, F13 keep-awake, never a tap on the MUSIC zone:

1. `spikes/recognizer-pfd/run.sh pfd` ungranted, then `GRANT=1` (silent), `usage` (two quiet 1 s
   tones), `props` → decides the single button (RESEARCH §5.4). On a `no match`: re-run with
   `RECOGNIZER=default` before concluding.
2. The user speaks one real command (RESEARCH §3.3).
3. `talk_cycles.sh` on the F7 build (earcons): one `live cue: … (both)` per talk, which
   `ScoWatch: sco connected (<source>)` fires, no `(fallback)`; still 0 `waited … for Main`.
4. `beeps.sh` while the user listens (5 beeps, ~1 min incl. a 20 s silence close).
5. `overlay_rotation.sh` (silent; ends with the dismiss step, which force-stops and relaunches the
   app to bring the buttons back), then the user's own finger: drag, drop on the ✕, "Show buttons".
6. If the spike passes: an `android/` agent builds the single button (tee in
   `VoiceEngine.captureLoop`, segmented `Transcriber`, "motoparty" first word, one zone). Narrow
   task: the F7 agent ran to 177k tokens.

Offline, any time: align the iOS jitter backlog cap to Android's 400 ms and gate the iOS LIVE
earcon on the first capture buffer (`AppModel.swift:406`) — both from the pre-roll review.

## Sixth coordinator session (2026-09-20, daytime; no phone, no iPhone)

Plan: `~/.claude/plans/pasted-content-id-45cf-you-are-functional-stonebraker.md`.

**Decided with the user today (don't reopen):**

- **Wired helmet mic (`RESEARCH.md` §4.8): rejected. No cable, and no other hardware either** (no
  LE Audio earbuds, no Cardo/Sena). **The AirPods mic stays a hard requirement** (as §5.1 says).
  The user asked "why can't we use the AirPods mic?": we do, and the talk itself is fine; the
  limit is Bluetooth's, not ours: AirPods do stereo music *or* a mic, never both, so every talk
  drops them to mono call quality, the call link is up 0.9–1.7 s after the press, and music is
  back within ±80 ms ~18 s after a talk. That cost is accepted. What softens it: the 20 s silence
  close, the honest go-beep (F7; the pre-roll buffer was reviewed and dropped, see below), and the
  single button (no second route switch for
  a command). Dropped for good: the USB-mic research and the USB-mic device spike; `RESEARCH.md`
  §6 "USB-C helmet mic" and "LE Audio earbuds" are won't-do. Not researched, because the answer
  made it moot: USB input in MODE_NORMAL vs A2DP, iOS input-only USB routing, charging + mic on
  one port, products. The passenger's iPhone will be a 15 or newer (USB-C), should it ever matter.
- **Overlay dismiss:** dropping the buttons on the X sets `overlayEnabled=false`; they come back
  by a "Show buttons" action on the notification and by the app's switch. **No auto-hide**: always
  shown while the service runs.
- **Single button (`RESEARCH.md` §5.4): wanted, test first.** The user expected it to exist
  already; it does not (nothing in `RESEARCH.md` is implemented). Two zones stay until the
  `EXTRA_AUDIO_SOURCE` spike (`spikes/recognizer-pfd/`) passes on the Pixel; then an agent builds
  the merge (press = talk; "motoparty, …" while open = command).
- **Silence close 10 s → 20 s** (PROTOCOL.md "Talk flow" step 3 updated by the coordinator;
  Android `TalkController.SILENCE_MS`, the peer's fake host).
- **`duckDuringTalk` stays off; the user said don't ask again.**
- Beeps (closed / error): the user was not listening for them last night → `tools/bench/beeps.sh`
  plays each once on purpose, the user listens on the next phone session.

**Done by the coordinator, no phone:** the own check of D4 run 4 (`results/2026-09-20-d4-overlay-4`):
13/13 frames on screen, 8/8 `trigger TALK`, 0 `trigger MUSIC`, `fx`/`fy` change on every drag
(0.0/1.0 corners), overlay back at `551 461 865 1025` with the start fractions, rotation restored
1 / 0, 0 errors → the agent's report holds; F5 is verified for injected drags (a real finger is
still the user's). `UIRequiresPersistentWiFi` is already in `ios/Info.plist` (RESEARCH §6 item:
done, needs only the iPhone test). `ios/README.md` now says the overlay is Pixel only.

**Agents of this session** (results below as they land): T = `tools/bench` heuristics + `beeps.sh`
+ peer 20 s; S = `spikes/recognizer-pfd/` throwaway app (recognizer on fed audio, USAGE_MEDIA vs
USAGE_VOICE_COMMUNICATION routing, UNPROCESSED property); O = `android/` F6 (drag-to-dismiss,
"Show buttons", service reacts to the setting, 20 s).

**Agent T (`tools/bench`, `tools/peer`; coordinator read the diff and re-ran everything).**
`bench.py`: capture-alive is judged against the engine's own `capture: read N … (X expected)` line
(open→close only as a fallback) → `…-d1-talk-5` has no `STALLED?` any more (336/380 … 972/981);
`errors()` counts E/F only under the app's own tags or any `Exception`/`FATAL`, lists the rest as
"not counted" → `…-d3b-unavailable` is `VERDICT: CLEAN`. Coordinator's own third fix: a collapsed
cycle (fast re-open) is not "incomplete" → `…-d1-talk-5` `VERDICT: CLEAN`; `…-d1-talk-4` stays LOOK,
rightly (that build did not collapse). New `beeps.sh` (listening test: live+closed, live+closed on
the 20 s silence, error; `NOW:` lines with the wall clock; only `host_talk`'s TALK-zone tap; adb
path unproven; earcons log nothing, `Earcons.kt` has no Log call). Peer: `TALK_SILENCE_MS = 20_000`,
READMEs. `uv run pytest`: 161 pass. `DRY=1`: `unavailable` CLEAN, `beeps` ok, `talk_cycles` ok (all
after the `lib.sh keep_awake` edit), `music_sync` ok (exit 0, full play → talk → resume → pause
trace, no fake host left behind). **"Do now" item 1 is done.**

**F6, agent O (`android/`; coordinator read the diff, fresh `assembleDebug testDebugUnitTest
--rerun-tasks`: 128 tests, 0 fail, 2 skipped). Device-unverified.** Drag shows a 112 dp ✕ at the
bottom centre of the usable area (own window, title `MotopartyDismiss`, not touchable; the buttons
window is now titled `com.kivan.motoparty:buttons`, so `bench.py frame` still picks the buttons);
ACTION_UP over it → fractions back to their ACTION_DOWN values, nothing saved,
`overlayEnabled=false` (`shared_prefs/settings.xml`), `stopSelf()`; CANCEL never dismisses.
`LinkService` now collects the setting itself (the activity's collector is gone), re-posts the
notification on a change, and swaps "Command" for **"Show buttons"** (`ACTION_SHOW_OVERLAY`) while
hidden. `SILENCE_MS = 20_000`. Target box in parent-frame px: portrait 1080x2205 →
`[393,1880][687,2174]`, landscape 2268x1017 → `[987,692][1281,986]`. Coordinator's objection: the
hit rule (centre x over the target and window bottom ≥ target top) makes ~72 % of the landscape
height count → tightened to "bottom ≥ the target's vertical centre" in F7. Still owed: a `dismiss`
step in `overlay_rotation.sh` (after agent T is out of `tools/bench`), then the user's real finger.

**F7, honest go-beep + tighter X (`android/`; coordinator read `LiveCue.kt`, `ScoWatch.kt` and the
`LinkHost` wiring, fresh `--rerun-tasks`: 139 tests, 0 fail, 2 skipped). Device-unverified. The
agent used ~177k tokens, over the user's limit: give the next one a narrower task.** LIVE now fires
when the first capture frame of this talk was read **and** the route is up (SCO connected for a
Bluetooth SCO device, at once for any other route), once per open, never after a close, not twice
on an SCO flap; a 2.5 s timer fires it anyway. Pure rule in `audio/LiveCue.kt` (10 tests);
`audio/ScoWatch.kt` caches the SCO state off Main from **two** broadcasts
(`ACTION_SCO_AUDIO_STATE_UPDATED` and `BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED`) because on
Android 17 SCO is managed by the audio framework ("Audio Managed SCO") and the classic broadcast
may be gone: **unknown which fires on the Pixel**. Bench reads: one line per talk
`live cue: session N, capture up +X ms, sco +Y ms|n/a|unknown, fired +Z ms (both|fallback)` and
`ScoWatch: sco connected (<source>)`. `(fallback)` on every AirPods talk = neither broadcast
fires → next step an `AudioDeviceCallback` on the SCO input device. A kept-route re-open logs
`+0 ms` for both. Open edge (coordinator): a quick close→open that did *not* collapse may see the
old link still "connected" when the route reports; `scoDisconnected` clears it only if the drop
arrives before the first frame. `LIVE_EARCON_DELAY_MS` is gone. X hit rule: window bottom ≥ the
target's vertical centre (178 px above the usable bottom): 10 % of the vertical travel in
portrait, 39 % in landscape (was 20 % / 72 %).

**Dismiss bench step (`tools/bench/overlay_rotation.sh`, `bench.py`; coordinator read
`dismiss_step`, `bash -n` ok, the four old overlay dirs summarise as stored, talk-5 / d3b still
CLEAN). Every adb step unproven.** `DISMISS=1` (default), rotation 0, after the "back" row: grabs
the buttons in the TALK half, 5 s swipe to the bottom centre, refuses any swipe under 40 px (moves
the buttons up first if needed), never a tap; mid-swipe dump → `bench.py window MotopartyDismiss`
(new subcommand; `frame <pkg>` unchanged); expects no buttons window, `overlayEnabled=false`,
fx/fy unchanged, no `trigger` lines; rows `dismiss-target` / `dismissed` / `restored`. Restore
(registered with `on_exit` before the swipe): `am start-service … SHOW_OVERLAY` (expected refused,
the service is not exported), else force-stop → `run-as … sed -i` on `shared_prefs/settings.xml` →
`restart_app`. Most likely to break: toybox `sed -i` under `run-as`; the swipe's end point
`(w/2, h-20)` counting as a drop on the ✕. Side fix: a failed TALK tap (`"no\n"`) never failed the
overlay verdict before; it does now. `shellcheck` is not installed here.

**Agent S: `spikes/recognizer-pfd/`** (throwaway app `com.kivan.motoparty.spike`, zero
dependencies; the coordinator built it: `./gradlew assembleDebug` ok, 2.9 MB apk; read `run.sh`: only
install / grant / logcat / `am start`, no taps, and fixed one bug: `install -g` granted RECORD_AUDIO
always, so the ungranted case could not run). `run.sh [pfd|usage|props|all]`, verdict lines
`PFD VERDICT:` / `PFD PIPE:` (a pipe never drained = the recognizer ignored the extra) /
`USAGE VERDICT:` / `PROPS VERDICT:`. Audible: two quiet 1 s tones for `usage` only. Open: whether
`RECORD_AUDIO` is needed for the pipe case (run ungranted first, then `GRANT=1`); TTS speech may
not be recognisable → a `no match` needs a `RECOGNIZER=default` re-run before concluding. Found:
`onSegmentResults` / `onEndOfSegmentedSession` are default methods, the compiler won't force them
in the `Transcriber` rewrite.

**Pre-roll buffer (`RESEARCH.md` §5.3): design review says don't build it** (Plan agent, read-only;
the coordinator checked the cited code: `LinkHost.kt:328-331` + `LIVE_EARCON_DELAY_MS = 400`,
`JitterBuffer.kt:165` backlog cap `MAX_MS + BACKLOG_SLACK_MS`, `VoiceEngine.kt:59` drop while no
session). §5.3 is confused: before the press no mic is open (opening the AirPods mic *is* the
switch), so there is nothing to keep a rolling buffer of. From `…-d1-talk-5/logcat_all.txt`, after
`talk open`: enterCall 199–563 ms, SCO up 1.17–1.38 s (cycle 2: up 0.97, dropped, up 1.88), the
talker's first captured frame 1.27–1.49 s (1.97) = 62–166 ms after its own SCO up. So the words
lost are the ones spoken in the first ~1.3–2.0 s, **never captured, not recoverable**. Listener-side
loss (listener's SCO later than the talker's mic) is ~0–0.3 s on rare cycles and the jitter buffer
already keeps the newest ≤400 ms (an accidental pre-roll). Caveat: measured with one AirPods phone
against the peer's instant mic; two AirPods phones were never measured. Found on the way: the three
receivers treat a backlog differently (Android caps 400 ms; iOS holds 100 packets = up to 2 s delay
until the next pause, `ios/…/JitterBuffer.swift:163`; the peer trims 1 frame/s, `jitter.py:223`) —
align iOS to Android's cap when `ios/` is next touched. **Recommended instead: a truthful "go"
beep**: today LIVE fires 400 ms after `enterCall`+`VoiceEngine.start` return (in cycle 1 that is
630 ms *before* SCO was up; it is only heard near SCO-up because the output blocks); fire it when
SCO audio is connected *and* the first capture frame arrived (`capture up` alone is not enough:
cycle 9 had frames 0.76 s before SCO), ~2.5 s fallback; Android `VoiceEngine` `onCaptureUp` +
`AudioRouter` SCO state + `LinkHost`; iOS `AppModel.swift:406`; no PROTOCOL change; ½–1 day + one
bench run. **The user said yes (2026-09-20): honest go-beep, no buffer, same tone.** Queued as the
next `android/` task (F7) after agent O; iOS side with it or when `ios/` is next touched.

## Do now (rewritten 2026-09-20 ~03:30, end of the fifth coordinator session)

Part A is committed (`d8fa329`). **Part B (the four device sessions) ran in the night of
2026-09-20; the evidence is in "Part B progress" below, newest entries last. Nothing of that night
is committed** (F3 + F4 + F5 in `android/`, `tools/bench/` changes, the new result dirs, both
HANDOFF files, `RESEARCH.md`); the build on the Pixel is F3+F4+F5 (installed 03:00, 123 tests).
The phone and Bluetooth are disconnected: no adb work until the user says so.

`RESEARCH.md` (2026-09-20, another session; nothing in it is implemented) holds the design
research: iOS on an internet-less hotspot, glove triggers / handlebar remote, recognition on
already-captured audio, Bluetooth audio limits, and its recommendation of a wired helmet mic
(§4.8). Its §6 is an action list; several items are answered by the night's benches (the
A2DP↔SCO switch cost: link up 0.9–1.7 s after the press, see D1 runs 4 and 5).

What is left, in order:

1. No phone needed: the coordinator's own check of D4 run 4 (`results/2026-09-20-d4-overlay-4`:
   summary, 0 `trigger MUSIC`, fx/fy in run.log) and `DRY=1` runs of `talk_cycles.sh`,
   `unavailable.sh`, `music_sync.sh` after the `lib.sh keep_awake` edit; the two `bench.py`
   heuristics (unrelated `E` lines in the unavailable verdict; `capture STALLED?` on short
   cycles). Then ask the user whether to commit.
2. Overlay: drag-to-dismiss (decide the two open points with the user first) and the "Pixel only"
   note in `ios/README.md` — see "New from the user" at the end of "Part B progress".
3. Ask the user: did they hear the closed beep and the error beep (AudioFlinger tags them `muted`).
4. `RESEARCH.md` §6, cheap items first; the wired-mic question needs the user's answer on the
   cable and on the "AirPods mic is a hard requirement" statement in `RESEARCH.md` §5.1.
5. With the phone: one real spoken command (RESEARCH §3.3; the two accidental voice commands of
   the night did open the recognizer's mic over BT-SCO for ~5 s and ended in `error 7`, nobody
   spoke, so nothing is proven either way); a real finger drag of the overlay; a ride.

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
- **D1 talk, 4th run, AirPods, F1+F2 build** (`results/2026-09-20-d1-talk-4`, 2026-09-20 ~02:00;
  coordinator read summary + logcat): 0 errors, MODE_NORMAL, capture alive (951 of 974 frames in
  the 20 s talk, 51 % DTX), 0 lost, 16 late in the whole run. **The jitter re-anchor fired on the
  device for the first time (cycles 2 and 4, 1 each) and playback carried on** — verified.
  - open→"HFP" per cycle 189, 210, 459, 248, 426, 528 ms (median 248, was 397): **not flat, not
    under 300**. All of it is one blocking call, `am.availableCommunicationDevices` right after
    `setMode` (174–523 ms) while AudioService tears down A2DP; our own work is ~0–30 ms. The
    bench's "HFP" marker is `setCommunicationDevice` returning, not the link: the real SCO link
    (`SCO_state_change … OPEN_ST` in `logcat_all.txt`) is up 0.9–1.7 s after `talk open`, all
    Bluetooth stack. So "<300 ms" is not reachable from our code; what the rider feels is ~1–1.5 s.
  - close→media 57, 786, 675, 763, 752, 797, 837 ms: the slow case nearly every time, all inside
    `setMode(MODE_NORMAL)` (601–778 ms); `VoiceEngine.stop` is 46–75 ms.
  - **Fast re-open did NOT collapse with the AirPods: no `TalkAudio:` line.** The third
    `talk.open` waited ~930 ms for Main (pings in the gap answered in 11 ms on the reader thread);
    Main unblocked 11 ms after `exitCall 573 ms` returned. Main calls into the audio system during
    teardown: `LinkHost.micAvailable()` reads `audioManager.mode`, and `Earcons.play` builds an
    AudioTrack on Main (closed/error earcons). → **F3 offline fix agent** (started 02:15): mode
    cached by listener, earcons off Main, a `… waited N ms for Main` log line, a TalkAudio test for
    a re-open while `exitCall` blocks.
  - Earcon release: no `AudioTrack: stop(` for earcons (a MODE_STATIC track may never log it, so
    the check is weak); the rising open time is explained by the AudioService call above, not by
    leaked tracks. The close earcon shows `muted` in appops each cycle: **ask the user whether
    they hear the close beep** (their listening test).
  - Bench: `talk_cycles.sh` / `music_sync.sh` don't keep the screen on and `keep_awake`'s
    `KEYCODE_WAKEUP` does not reset the screen-off timer when already awake (checked:
    `lastUserActivityTime` unchanged); `KEYCODE_F13` does. The coordinator ran its own F13 loop;
    **fix `keep_awake` in lib.sh** once no script is running.
- **D2 music, 2nd run, AirPods, F2 filter** (`results/2026-09-20-d2-music-2`, ~02:20; coordinator
  read summary + every SyncController line): **PASS.** Settled drift min −79 / max +53 / median
  −36 ms over 20 readings (all within ±80). **2 nudges (was 18), both speed-ups 64 s apart, no
  slow-down at all, so no opposite-direction pairs; 0 re-seeks;** 6 `waiting for confirmation`
  checks of which 4 led to nothing (the filter absorbing the flip: −101, −83, −82, and −221
  replaced by −327). The raw flip is still there under the filter (every 2 s window has a
  221–299 ms spread at speed 1.0; trace `err 10 → −245 → −16`); the median lands between the two
  levels, hence a ±80 band rather than ±0. Media3 root cause still not chased.
  Open points: after the talk, back within ±80 ms took ~18 s (was ~14 s; the confirmation step
  costs ~4 s) — decide whether the post-start path should skip confirmation; right after the
  cold start the raw err ramped −7 → −89 → −220 → −320 over 3 s at speed 1.0 (a slew, not a flip;
  not investigated); the >1 s re-seek path never triggered, so it is unverified on the device.
- **F3 offline fix** (coordinator read the diff, fresh `./gradlew testDebugUnitTest --rerun`: **115
  tests, 0 fail, 2 skipped**; installed on the Pixel 02:33): new `audio/AudioModeWatch.kt` (mode
  cached off Main by `OnModeChangedListener`, 1 s poll below API 31), `micAvailable()` reads only
  local state, every earcon + `adjustStreamVolume` posted to the audio thread (`LinkHost.earcon`),
  new `core/MainLag.kt` → log line `control message talk.open waited 930 ms for Main` from 100 ms
  up, TalkAudioTest for a re-open while `exitCall` blocks. Not changed: reading
  `availableCommunicationDevices` before `setMode` (inferred safe, not measured). **Next
  `talk_cycles.sh` must show** `TalkAudio: re-open during teardown…` in the fast re-open, no
  `waited … for Main` lines, talk.open answered < 100 ms.
- **D4 overlay** (`results/2026-09-20-d4-overlay-2`; `…-d4-overlay` is invalid: the portrait-locked
  launcher in front meant the display never rotated — the script now starts our MainActivity and
  reads the real `mDisplayRotation`): **at rest, fully on screen and the TALK tap reaches the app
  in all four rotations (8/8), settings restored (accelerometer 1 / user 0, checked by the
  coordinator).** Two drag bugs → **F4 offline fix agent** (started 02:40): the drag clamp uses the
  full display bounds but the window lives in a parent frame inset by status bar/cutout, so the far
  corner is 74–132 px off screen; a drag ending in ACTION_CANCEL is never saved and snaps back
  (seen with injected swipes only; **a real finger drag is for the user to try**). Run 1 also
  opened a voice command by accident (a zero-length restore swipe read as a MUSIC tap; fixed in
  the script).
- **D3 (a), F3 build** (`results/2026-09-20-d3b-unavailable`; coordinator read summary + client_a.log):
  **PASS, all 8 expectations ok, per PROTOCOL "Talk flow".** Overlay tap → `talk open (by host)` →
  client `talk.close{by:client,reason:unavailable}` → host broadcast of that close +
  `state{talk:false}` 12 ms later; host log `client microphone unavailable` 61 ms after the open;
  MODE_NORMAL at the end; (b) still passes; RECORD_AUDIO granted again. The verdict line says
  LOOK only because bench.py counts one unrelated `E ActivityThread: … androidx.car.app.connection`
  line (**bench.py: don't fold unrelated E lines into the unavailable verdict**). The error earcon
  is created ~1.65 s after the close (it queues behind `exitCall` 980 ms on the audio thread since
  F3) and AudioFlinger tags it `muted`: **ask the user whether they heard it (02:34:22).** No
  `waited … for Main` line fired, so that line is still unproven on the device. Not exercised:
  (a) with music playing, the mic dying after talk opened.
- **F4 offline fix** (coordinator read the diff, fresh rerun: **122 tests, 0 fail, 2 skipped**):
  `OverlayService.usableSize()` = bounds minus `getInsetsIgnoringVisibility(systemBars|cutout)`
  (pure part `OverlayPlacement.usable`), `ACTION_CANCEL` ends a drag like UP (never fires a
  trigger). F3+F4 build installed on the Pixel (`lastUpdateTime` 02:38:36); `talk_cycles.sh
  …-d1-talk-5` running on it, then `overlay_rotation.sh …-d4-overlay-3` (`dragged-far` must be on
  screen in all four rotations).
- **D1 talk, 5th run, AirPods, F3+F4 build** (`results/2026-09-20-d1-talk-5`; coordinator grepped
  logcat + summary): **F3 verified.** Fast re-open: `TalkAudio: re-open during teardown: call
  route kept (session 8)`, one `enterCall` and one `exitCall` (2 ms) for the whole trio, the third
  `talk.open` answered in **14 ms (was 940)**, all 18 requests 14–54 ms, **0 `waited … for Main`
  lines**, 0 errors, MODE_NORMAL, 20 s talk capture 49.5 frames/s, lost 2 / late 29 in the run,
  re-anchors 1+1. For the rider the fast re-open is ~1.3 s to SCO up, ~1.5 s to voice (was ~2.2 s):
  the BT stack still drops and re-makes the SCO link when the AudioTrack/AudioRecord restart,
  though we kept the route.
  - System-call times, not ours, unchanged in kind: open (`availableCommunicationDevices`) 202,
    489, 563, 532, 481, 486 ms (median 486; run 4: 248 — two samples, noise or not is open; a
    cheap experiment is reading the device list before `setMode`, log it once in MODE_NORMAL
    first); close (`setMode(NORMAL)`) 708–784 ms every time. **Answer to the open question: the
    switch is not under 300 ms and cannot be from our side; link-up is 0.9–1.7 s after the press.**
  - Earcons on the audio thread: the closed tone now starts ~1.3 s after the close (median; was
    ~0.8 s) because it queues behind `exitCall`; the live tone lands 50–220 ms after SCO up. A
    collapsed close plays no closed tone (8 tones for 9 closes). All closed tones still tagged
    `muted` by AudioFlinger, as before F3. **All of this is for the user's ears.**
  - `capture STALLED?` on 6 of 8 short cycles is the bench's 8 s window vs a 0.8–1.5 s capture
    start, not a dead loop (49.7 frames/s while running): **fix the bench.py heuristic** (compare
    with the capture line's own window). bench.py now prints Main-lag and `TalkAudio:` lines.
  - Unproven: `AudioModeWatch`'s listener firing (no positive log line).
- **D4 overlay, 3rd run, F4 build** (`results/2026-09-20-d4-overlay-3`, VERDICT CLEAN): at rest on
  screen + TALK tap 8/8 in all four rotations (resting frames moved 27–95 px: same fraction, now of
  the usable area); **`dragged-far` now fully on screen in all four rotations** (rot 0
  `766 1773 1080 2337`, rot 90 `2086 453 2400 1017`, rot 180 `766 1704 1080 2268`, rot 270
  `1954 453 2268 1017`) — F4's clamp verified. **A drag still never persists**, in-screen drags
  too, `overlay.xml` fx/fy never change. Cause (coordinator, from the code): the root's
  `addOnLayoutChangeListener { place() }` runs on the layout pass every MOVE's `updateViewLayout`
  causes and resets `params` to the stored fraction, so UP saves the old fraction; a regression
  of the fraction change in `d8fa329`, so a real finger is affected too. → **F5 offline fix agent**
  (started ~03:00), then `overlay_rotation.sh …-d4-overlay-4`. The run's end-of-run restore swipe
  (2 px) was read as a MUSIC tap and opened a ~5 s voice command (mic); the script now only swipes
  when more than 40 px off. Rotation settings verified 1 / 0 afterwards.
  `lib.sh keep_awake` now also sends `KEYCODE_F13` (done by the coordinator).
- **F5 offline fix** (coordinator read the diff, fresh rerun: **123 tests, 0 fail, 2 skipped**;
  installed 03:00): ACTION_MOVE now updates `fx`/`fy` from the clamped params, so the layout
  listener's `place()` is a no-op mid-drag; round trip `position(fraction(x)) == x` pinned by a test.
- **D4 overlay, 4th run, F5 build** (`results/2026-09-20-d4-overlay-4`, VERDICT CLEAN) — **the
  agent's report only; the user stopped the session before the coordinator's own check (summary,
  `trigger MUSIC` count, final frame, rotation settings, DRY runs of the three scripts after the
  `lib.sh keep_awake` edit). Do that check first tomorrow.** Reported: `after-release` =
  `dragged-far` in all four rotations, `overlay.xml` fx/fy change on each drag, every frame on
  screen incl. far-corner fractions carried into the next rotation, 8/8 TALK taps, 0 `trigger
  MUSIC`, overlay dragged back to `551 461 865 1025` exactly, rotation 1 / 0.
  `overlay_rotation.sh` changed again (drags away from the current corner, 5 s swipe, logs fx/fy).
- **Session ended ~03:15 on the user's word; the phone and Bluetooth are disconnected. Nothing is
  committed**: uncommitted = F3 + F4 + F5 in `android/`, `tools/bench/` (`bench.py`, `lib.sh`,
  `overlay_rotation.sh`), the new result dirs, both HANDOFF files.
- **New from the user (2026-09-20, for tomorrow), overlay:**
  1. *Only the Pixel has the overlay.* Checked: `ios/` has no overlay code at all (iOS cannot draw
     over other apps anyway). Keep it that way; say so in `ios/README.md` if it is not there.
  2. *A way to dismiss the overlay by dragging it:* while dragging, show a trash / X target at the
     bottom of the screen; dropping the overlay on it hides it. The user's complaint: it is on
     screen now and they cannot make it go away, "even if I close the app". What exists today: the
     `overlayEnabled` setting (MainActivity toggles `OverlayService` from it) and the foreground
     notification's "Stop" action (`LinkService.ACTION_STOP`, stops everything); closing the
     activity leaves `LinkService` + the overlay running by design. To decide with the user
     tomorrow: does drop-on-X just set `overlayEnabled=false` (back via the app's setting — and
     then the notification should offer "Show buttons"), and should the overlay hide by itself
     when no ride/link is active? Then an offline agent + a bench step in `overlay_rotation.sh`.
- Open questions only the user's ears answer: did they hear the closed beep (now ~1.3 s after the
  close, none on a collapsed re-open) and the error beep (02:34:20); AudioFlinger tags them `muted`.
- (older) Waiting on the user: (1) AirPods connected → re-run `talk_cycles.sh …-d1-talk-4` and
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
| git | `master`: `cdc33c6` initial, **`d8fa329` (2026-09-20 ~00:15) everything of the fourth session**: layer 1+2, A1/A2/I1/P1 fixes, F1/F2 device fixes, `tools/bench/`, docs. **Not clean since the night of 2026-09-20: F3/F4/F5 (`android/`, 123 tests), `tools/bench/`, result dirs, HANDOFFs and `RESEARCH.md` are uncommitted.** `tools/bench/results/*/logcat_all.txt` (whole-phone dumps) are gitignored; the filtered `logcat.txt` is committed. Commit only when the user asks. |

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
