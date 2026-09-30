# Coordinator handoff (2026-09-22, tenth session)

For the Claude session that coordinates Motoparty. You own `PROTOCOL.md`, `fixtures/`, the root
docs, `tools/bench/` and the decisions; component work goes to background subagents, one per
directory, with a final report back to you; they don't commit. You re-run their tests yourself
before telling the user anything is done, and keep this file current.

**This file is the current state, not a diary.** The session-by-session narrative up to and
including the seventh session (D1–D4 device runs, the F1–F5 fixes, the superseded "Do now" blocks)
was cut on 2026-09-20 and lives in git: `git show dd084d6:HANDOFF.md`. The Android detail — every
F-section with its evidence and its verify list — is in `android/HANDOFF.md`, which was **not** cut
and is the place to put component detail. Raw bench evidence is under `tools/bench/results/`.

## Do now (2026-09-30: implement the audit)

`AUDIT.md` is the full finding list of a seven-agent read-only scan of `7cedc94` (bugs, voice
latency, music, UI/UX on both phones), with stable IDs, the three user decisions it produced
(lock-screen Pause works; Pixel buds dropping pauses the music; no link auth for now) and a
three-round implementation plan (1 ride-breaking bugs + voice latency, 2 music, 3 UI/UX). The
user approved implementing it, in rounds or in parallel. **State on 2026-09-30, late (everything uncommitted, nothing installed, device-unverified):**
- Round 1: done, coordinator-tested.
- Round 2: done and coordinator-tested on Android (403 tests / 0 fail / 5 skipped, lint 0 errors)
  and iOS (`swift test` 182, release build clean), incl. the integration (cache priorities wired,
  gapless cancel rule in PROTOCOL.md Music flow 6, P11 in spec + `fixtures/control/framing.json`).
  The Python peer is done too (coordinator-run: see count below; fake host with gapless, `bye proto`, P4/P7 mirror);
  its report, if it finished, is `~/.cache/claude-handoff/motoparty-round2/peer.md`; re-run
  `cd tools/peer && .venv/bin/python -m pytest -q` (292 + 1 skipped before its work).
- Round 3 (UI/UX both phones, H5 release build, H10 bumps, L9 partly) is built and
  coordinator-tested: android 450 tests / 0 fail / 5 skipped, lint 0 errors, `assembleDebug` OK;
  `swift test` 211 + release build clean; peer 325 + 1 skipped. Agent reports:
  `~/.cache/claude-handoff/motoparty-round3/{android-ui,android-system,ios-ui}.md`. Not yet done by
  the coordinator: `assembleRelease` re-run, looking at all Roborazzi screenshots (3 of 27 seen,
  they look right), round-3 sections in `AUDIT.md` Status / `android/HANDOFF.md` / component
  table, the agents' cross-file requests, and any install.
- 2026-09-30, later: the release build is on the Pixel (debug uninstalled with the user's OK), and
  the 5–10 s tap-to-sound wait is fixed (`AUDIT.md` Status "Tap-to-sound fix": Media3 demux +
  batching muxer in `music/Remux.kt`, parallel pre-resolve, `MusicController.insert`). Android
  454 tests / 0 fail / 5 skipped, lint 0 errors, `assembleRelease` OK. With the phone locked the
  app can be driven from `tools/peer` (`search songs …`, `enqueue now <n>`). Still open: checks
  that need the screen (`android/HANDOFF.md` "Audit round 3"), the iPhone (not installed; the new
  MP4 layout is unplayed by AVPlayer), L8 leftovers.
- The Pixel is reachable over wireless adb (user's go-ahead 2026-09-30; read
  `~/.config/system-notes.md` "adb over Wi-Fi" first). Next: install the debug build and run the
  solo checks of `android/HANDOFF.md` "Audit round 1/2". The iPhone install is on hold (user).
`AUDIT.md` "Status" has the ID list. The sections below are the state before the audit and still hold.

## Read first

1. `~/.claude/plans/dynamic-bubbling-lemon.md`: the wired-helmet-mic plan, Stages A–E. **Stage A is
   built; B–E are on hold** pending the ride recording in "Do now", and its "Hardware" table is
   superseded by `research/MIC.md`.
   `~/.claude/plans/motorcycle-communication-application-proud-pixel.md`: the original product plan
   (its "5 GHz hotspot" is moot, see "Hotspot").
2. `PROTOCOL.md`: the wire contract. It is the source of truth over any code comment.
3. `fixtures/`: shared test vectors. All three implementations pass the current set.
4. Component notes: `android/HANDOFF.md` (the most detailed), `android/README.md`, `ios/README.md`,
   `tools/peer/README.md`, `tools/bench/README.md`.
5. **`research/` — do not read it yet.** Four documents (design research, playback, the microphone
   purchase plan, the AliExpress parts list) that are one costed fallback, worth ~₪300–1,000 of
   hardware. `research/README.md` says in a page what each answers. They become live **only** if
   the ride recording below fails; reading them before that is ~3,000 lines of context spent on a
   purchase that may never happen.

## Do now (2026-09-22, tenth session: test the microphone we already own)

**The question is one ride long, and nothing can be ordered until it is answered.** Inside a
full-face helmet at 100–130 km/h wind is 95–105 dB and speech at the mic is 75–85 dB;
intelligibility lives 300 Hz–4 kHz and wind energy below ~500 Hz, so **mic placement and wind
shielding beat codec bandwidth by a wide margin**, and the AirPods mic is in the ear, in the
turbulence. That is the case for a mouth microphone and it is still unmeasured — on this helmet,
this head, this phone. The ninth session went straight from that reasoning to a purchase plan
(`research/`); the tenth stopped and built the recorder instead.

**What changed on 2026-09-22, and it is a user decision, not a finding:** the user does **not want
music and talk at the same time — "it's this or that, never both simultaneously."** That removes
the cost the whole wired direction was paying to avoid. The A2DP↔HFP switch degrading music during
a talk is not a defect if music is meant to stop anyway. What is left of the case against
AirPods-only is **one** thing: whether the in-ear mic delivers intelligible speech at 110 km/h.
Nothing but a recording answers it.

The wire format stays 16 kHz Opus and `PROTOCOL.md` is **out of scope** either way.

**State.** F9b (finished, see below) and Stage A of the wired-mic plan were uncommitted on top of
`a1bb7ed` until 2026-09-24, when they were committed (after `d437f73` pause fix and `5fd5ba4` idea #3
music) and pushed; 194 tests and `lintDebug` 0 errors on that tree. Numbers below are from 09-22: `android`: **191 tests, 0 fail, 2 skipped** (153 → 169 with F9b → 191), fresh
`assembleDebug testDebugUnitTest --rerun-tasks`, coordinator-run. `tools/peer`: 161 pass. `DRY=1`
runs of `talk_cycles.sh`, `unavailable.sh`, `music_sync.sh`, `beeps.sh` all behave as recorded, and
`bench.py talk` still reproduces the stored F7/F8/F9a numbers exactly. **The working tree (F9a, F9b,
Stage A, solo talk) was installed on the Pixel on 2026-09-22 01:42 and has not been exercised
there**: all of it is still device-unverified, apart from 2026-09-24's `music_sync.sh` (one talk
open/close and a cold resume on this tree, 0 errors). To get F8 back, build `dd084d6`.
**No hardware has been bought.**

**F9b is finished, not half-applied** (the eighth session was stopped mid-way and left it in the
tree). New pure `audio/MediaCue.kt` (16 tests) gates every sound that plays on the *media* route —
CLOSED, ERROR, the OK of a local volume change, and the spoken replies — on (a) `exitCall` having
returned at depth 0 and (b), **only for an SCO route**, the framework having reported a
communication device other than `bt_sco` after it; a 2 s timer is the safety net, a re-opened talk
drops a stale CLOSED, and off SCO a sound still plays on the same Main turn (`played +0 ms`), which
the t3c earpiece bench says it must. `AudioRouter` gained `onRouteHeld` and `onRouteReleased(wasSco)`;
`ScoWatch` gained a raw `onDevice` callback, because F8's dedupe swallows exactly the `earpiece|none`
report this needs. Bench line: `media cue: closed, released +6 ms, device earpiece +415 ms, played
+415 ms (both)`.

**Stage A (instrumentation) is built.** It is the thing every later A/B depends on, and none of it
existed before: the app could not record a single sample of its own capture, and had never asked the
framework what devices the phone has.
- `audio/PcmDump.kt` + `audio/Wav.kt` (+ 11 tests): the capture loop's PCM, pre-encode, to a WAV
  file, one per talk, behind the new `captureDump` debug setting (off by default; switch at the
  bottom of the app's Settings card). The `voice-capture` thread never blocks and never allocates on
  the frame path — a pre-allocated pool, a bounded queue, a private daemon writer thread, **drop and
  count on overflow**; `close()` does not join, because that teardown has 500 ms and the writer may
  have 2 s of queue plus a header rewrite left. Capped at 40 MB (~20 min). Files land in
  `/sdcard/Android/data/com.kivan.motoparty/files/captures/capture-<yyyyMMdd-HHmmss>.wav`, which
  `adb pull` reaches without root. Line: `capture dump: capture-20260920-143012.wav, 1193 frames,
  23.9 s, 763520 bytes, 0 dropped` — `dropped` is the only number that means something is wrong.
- **Solo talk (2026-09-22).** With `captureDump` on and **no client connected**, a talk trigger is
  no longer refused: it opens the ordinary talk path (call route, `VOICE_COMMUNICATION` capture,
  live beep, the WAV) with no far end, so the baseline ride needs no passenger and no peer. It
  ends on the trigger, like every talk. Log line `talk: no client connected, recording solo`. With the switch off the refusal is unchanged.
  The user's position as of this date: **music and talk are never wanted at the same time**, so
  the HFP profile switch is not a cost in itself; whether the in-ear mic survives the helmet at
  speed is the only open question about AirPods-only, and this recording is what answers it.
- `audio/DeviceRoster.kt` (pure, 10 tests) + `audio/DeviceWatch.kt`: `AudioManager.getDevices()` and
  an `AudioDeviceCallback` on their own daemon thread, never Main. Lines
  `audio devices: in builtin_mic#3 "Pixel 8", bt_sco#10 "AirPods Pro" · out earpiece#1 …`, plus
  `audio devices +: …` / `-: …` on every plug and unplug, followed by the full roster. The id is
  carried because it is what `AudioRecord.setPreferredDevice()` takes in Stage C. New UI row
  "Audio devices" under "Call device" (which keeps its own meaning: what *our* call route picked).
- `ScoRule.describe` gained `usb_device`, `usb_accessory`, `builtin_mic`, `speaker_safe`,
  `telephony`, `remote_submix`; `VoiceEngine.inputName` is gone, it now calls `describe` directly.

**Next, in order:**
1. **The bench check, in a quiet room, five minutes** (nobody has ever heard this code run). Switch
   `captureDump` on, AirPods in, press TALK, wait for the live beep, speak, press again. The log
   must show `talk: no client connected, recording solo`, then `capture dump: …, 0 dropped`. **The
   line that decides whether the file is worth anything is `mic trace: … routed=…`: it must say
   `bt_sco`, not `builtin_mic`.** A recording made on the phone's own mic answers a different
   question. `adb pull /sdcard/Android/data/com.kivan.motoparty/files/captures/`.
2. **The ride recording** — the whole of the current question. `captureDump` on, no client needed,
   ~110 km/h, visor down, **speak the speed out loud as it changes** so the WAV is self-labelling.
   One file per talk, capped at 40 MB ≈ 21 min. Then listen: *can a passenger understand this?*
   - **Intelligible** → buy nothing. The remaining work is a high-pass at 150–200 Hz and noise
     suppression tuned for wind, in the app, on the capture we already have, plus `research/`
     becoming history. `research/PLAYBACK.md` §8 T2 (the ear-position spectrum) is the free
     measurement that would tune it.
   - **Mush** → `research/README.md`, then `research/MIC.md` §5: the free eraser-block fit test
     first, then a ₪299–350 Hollyland Lark A1 or a ₪312 Sennheiser XS Lav, bought locally and
     returnable. Nothing there is ordered and nothing there should be ordered before this step.
   - **Result, 2026-09-27: mush.** Two rides recorded (`captures/capture-20260927-094754.wav`,
     6 min; `…-135708.wav`, 12.5 min; gitignored). User: *"sounds terrible whenever speed is >0."*
     Whether they were `bt_sco` and not `builtin_mic` was **not confirmed** (logcat had rolled).
     A second research pass then found that **one** Lark A1 Duo (₪175, Ivory, new) in Stereo mode
     can mic both riders — `research/MIC.md` §2.2, gates S1–S4 in §6, gap measurement in §5. **Bought
     2026-09-28** (Ivory, Duo Mini USB-C, ₪175). **Same evening: S1, S2, S3 pass** (details and
     numbers in `research/MIC.md` §"Status", 2026-09-28 evening): UAC 48 kHz stereo; Stereo mode
     persists in the receiver; **Mic1 = pink LED = left = rider**, Mic2 = yellow = right; the Pixel's
     stock Camera records two distinct channels. Gain 5/6 clips at a hand's width → A2 at ~3. **S4
     passes too (2026-09-28, 23:08)**: the new debug "USB stereo test" (`audio/UsbStereoProbe.kt`,
     outside the talk path, `MODE_NORMAL`) got two distinct channels from **all three** sources —
     `UNPROCESSED`, `MIC`, `CAMCORDER` — routed to the USB device, 48 kHz 2 ch, media still on
     `bt_a2dp`, no `voip_tx`. Numbers in `research/MIC.md` §"Status". **A2 passes at gain 3**
     (persists without the app; clean at raised, one 0.6 ms clip in 12 s of shouting, no pumping).
     Gap measurement waived (user: the TX with fur fits);
     **A3's 30 min soak skipped** by the user, its RF question folded into the ride recording. **Helmet
     test done** (laptop, fur, visor down): like A2 — clean at raised, shouting clips ~0.6 ms, no
     breath pops; gain 2 recommended for the ride. **Long recording written, installed and
     desk-checked** (button "start long Lark recording"; runs with the screen locked, 0 dropped; ENC
     on drops the background ~16 dB and gates to near-silence between words). **Next: the ride** —
     ENC Off then On on the same stretch, gain 2, EQ "Equalization". "Schedule power off" (15 min)
     applies only to an unpaired TX (user read the ⓘ). Helmet mount: tape for the first ride, then
     hook-and-loop cut to the TX back (`research/MIC.md` §"Status").
   - **In between** → the honest case for the whole `research/` plan, and the one where the user's
     ears, not this file, decide.
3. **Talk/command control: option A built offline (2026-09-29), device-unverified** — see
   "Decisions in force"; the device checklist is `android/HANDOFF.md` "Commands inside talk,
   option A" and `ios/README.md` "What only a real iPhone can answer". Press-only talk is done too.
   Earbud presses no longer touch a talk on either phone (user, 2026-09-29: the earbuds sit
   inside the helmet); media keys control the music only. Talk is the Pixel overlay /
   notification / Ride tab and the iPhone's TALK button. The wake word was replaced the same day by
   **"the first phrase decides"** (built offline in all three, device-unverified; coordinator-run
   2026-09-29: android 239 tests / 0 fail / 5 skipped, iOS `swift test` 75 + `xtool dev build -c
   release` clean, peer 210 pass; `tools/bench/hotspot_test.sh` now opens a talk before its `say`). **Passenger pocket
   trigger (user, 2026-09-29):** the iPhone rides locked in a jacket pocket, so **holding volume
   up toggles talk** while the link is up, built as **"the app owns the volume"** (offline,
   device-unverified; coordinator-run 2026-09-29: `swift test` 101 pass, `xtool dev build -c
   release` clean): while linked the system volume is parked at 15/16 and every press is reset
   to it; single presses step an app level 0–16 (3 dB steps, 15 = 0 dB, 16 = +3 dB via an
   AUPeakLimiter on talk; music and earcons cap at 1.0); a hold (4 steps, first gap ≤ 700 ms, then
   ≤ 200 ms, all guesses to tune from the `volume key: up +<gap> ms` log lines) toggles talk and
   takes its steps back; re-park after route changes and talk open/close. Details and the device
   checklist: `ios/README.md`. A $1–3 BLE "iTag" button read over CoreBluetooth is the fallback if
   the device run disappoints. The passenger wears Redmi Buds 6 Pro, not AirPods.
   **First two-phone device run (2026-09-29 evening, uncommitted fixes, all coordinator-tested:
   android 266 / 0 fail / 5 skipped, iOS 101 + release build; installed on both phones):**
   iPhone voice-engine restart loop fixed (restart in place, capped); MPVolumeView slider created
   at start + retried; no-headset talk → loudspeaker on both phones; Pixel latency trim now per
   output route (`music/LatencyTrims.kt`; the gap was the 260 ms AirPods trim applied on the
   speaker); host no longer names an uncached track in `state` (iPhone 404 race); Pixel AirPods
   mic silence after a builtin→SCO route migration fixed (F9c: wait for SCO, else re-open the
   recorder); iOS now logs `talk stats:` / `mic trace:`. The last three are **not yet re-tested on
   the phones**. Link, buds routing (iPhone in/out = Redmi Buds), passenger→rider voice and music
   sync worked.
4. **Host-mic talk (Lark) built offline (2026-09-29, late), device-unverified.** User decisions
   in "Decisions in force"; spec `PROTOCOL.md` "Host-mic talk" (`mic:"host"` on `talk.open` and
   `state`). Coordinator-run: android 285 / 0 fail / 5 skipped (`--rerun-tasks`; fixtures are now
   a Gradle test input, so edited vectors re-run the tests), iOS `swift test` 110 + release build
   clean, peer 230. Pixel: `audio/TalkMic.kt` (chooser), `audio/LarkDsp.kt` (150 Hz high-pass,
   48→16 kHz decimator), `audio/LarkEngine.kt`, settings `larkTalk` (on) / `larkSwap` (off); the
   home checklist is `android/HANDOFF.md` "Host-mic talk (Lark)". iPhone: `.listen` = the media
   session, receive-only engine; `ios/README.md` "Host-mic talk". Device questions: does the USB
   route hold with AirPods on A2DP; passenger→rider delay over A2DP; clock drift (dropped count
   in `lark stats:`); `getRoutedDevice()` confirming USB within 2 s (else every Lark talk fails
   `unavailable`). `resumeLeadMs` / cold-start hold could shrink for Lark talks (not done).
   **Discovery bug found and fixed 2026-09-29 (~22:30):** the iPhone locked onto stale Bonjour
   services `peer-test-<hex>` that the peer test suite had advertised on the real LAN *and* on
   the laptop's USB tether to the iPhone (172.20.10.5), with zeroconf's 75-min TTL. By 22:25
   nothing answered for them any more (an mDNS PTR query got no reply; avahi only showed its own
   cache, resolves timed out), so it was caches, not a live responder; toggling Wi-Fi didn't clear
   the iPhone's cache for the USB interface. The real bug: the client took `results.first`, treated
   it as the host and stopped browsing and sweeping. Fix (PROTOCOL.md "Discovery" rewritten): a
   Bonjour result is only a candidate; the client probes all of them in parallel (plus the /24
   sweep after 3 s), sends nothing, and the host is the first valid `hello role:host`; failures are
   backed off 10 s; the last linked host name wins ties. Tests no longer advertise on the LAN
   (opt-in, short TTL). Android: host tolerates probes, NSD registration retries.
   **Verified on devices 2026-09-29/30 (~23:35–00:05):** the iPhone linked via Bonjour in about 0.1 s.
   Lark talks work both ways once the receiver enumerates. Fixed and installed that night (uncommitted):
   - Both apps show a music status line ("Loading…", "Waiting for iPhone…", "Paused for talk").
   - Pixel: a "Lark receiver not detected" banner on the Ride tab. It showed up because the receiver
     once stayed plugged in without enumerating.
   - Earcons that don't go to the call route now play on the media stream with a 150 ms pre-roll.
     They used system sonification, which is muted on the Pixel, so the AirPods got no beep.
   - MediaCue: the close cue no longer hangs on its 2 s fallback.
   - Pixel: a `lark: … silent all talk` log line.
   - iOS: the app volume is remembered (first run: 12/16) and shown on the Ride screen. Before, it was
     derived from the system volume at connect, which gave -33 dB, so the rider sounded "weak".
   - iOS: fixed a phantom volume-key step and set the HFP park tolerance to 0.02.
   The user reported "good now". A ~50 ms offset between the AirPods and the Redmi Buds is handled with
   Pixel Settings → Latency trim.
5. **Only if the ride says buy:** the staged plan in `~/.claude/plans/dynamic-bubbling-lemon.md`
   (Stage B the bench hour, C the routing code, D the music setting, E the A/B ride) still stands
   as written, with `research/MIC.md` overriding its Hardware table on what to buy.

## Where each component stands

| Component | State |
|---|---|
| `android/` | The host. 243 tests, 0 fail, 5 skipped (2026-09-29, after option A; incl. 9 screenshot tests; the 5 skipped are the live YouTube tests, which pass with `-Pnetwork`). Everything through F9b + Stage A + solo talk is written and coordinator-verified offline, and **installed on the Pixel on 2026-09-22 01:42 — but never run there**, so F9a, F9b, Stage A and solo talk are all device-unverified. Per-file state and every F-section: `android/HANDOFF.md`. |
| `ios/` | The passenger. `swift test`: 101 pass on Linux (2026-09-29, after "the app owns the volume"; release build clean); `cd ios && xtool dev build` compiles clean for arm64-apple-ios (unsigned, no phone needed). Jitter backlog capped at 400 ms and the LIVE earcon gated on the first capture buffer (`c7d7d76`, 69 tests). **Never run on a device.** `SessionController.swift:122-125` matches `.bluetoothHFP` only and has no wired branch — a later job, rider first. `ios/README.md` lists what only a real iPhone can answer. 2026-09-29: release build clean (xtool auth valid to 2027-09), audit items fixed; next is `xtool dev -c release` with the iPhone 15 on USB-C. |
| `tools/peer` | Python/uv fake host + client. `.venv/bin/python -m pytest -q`: 213 pass (2026-09-29, after option A; `hear <phrase>` simulates an in-talk phrase, `say` is unchanged; `uv run pytest` fails until `.venv` is recreated, its pytest script points at an old path). |
| `tools/bench` | `talk_cycles.sh`, `music_sync.sh`, `unavailable.sh`, `overlay_rotation.sh`, `beeps.sh`, shared `lib.sh`, summaries in `bench.py` (`summary.txt`, last line `VERDICT:`). All adb paths are proven on the device (`beeps.sh`'s silence step is gone with the silence close; it now expects 3 beeps). `HOST_IP` is overridable. `results/*/logcat_all.txt` (whole-phone dumps) are gitignored; the filtered `logcat.txt` is committed. |
| `spikes/recognizer-pfd/` | Throwaway app, its question answered (see "Recognizer" below). Still installed on the Pixel. |
| git | Remote `origin` = https://github.com/vaknin/motoparty (**public**: nothing personal in commits). **Commit only when the user asks.** |

**UI/UX pass and touch browsing (2026-09-29, committed `47ce8bf`, device-unverified).** The user found
both apps ugly and choosing music clunky, and chose: the passenger can search and queue from the
iPhone (a protocol addition), cover art, and screenshot tests. `PROTOCOL.md` gained a "Browsing"
section and five messages (`music.search`, `music.browse`, `music.results`, `music.enqueue`,
`music.edit`) plus optional `state.music.art`; still `proto:1`, since old peers ignore unknown
types. The host keeps at most 200 upcoming tracks so `state` stays under 64 KiB. Both apps now
have tabs: **Pixel** Ride / Search / Queue / Settings (Songs, Albums and Playlists chips; albums
open before they play; ⋮ = play next / add to queue; tap-to-jump and ✕ in the queue; link numbers,
debug tools and the log are folded into Settings → Diagnostics). **iPhone** Ride / Search / Queue
(transport controls on the main screen, latency trim moved to Settings). Pixel screens render on
Linux: `./gradlew testDebugUnitTest -Pscreenshots --tests '*ScreensTest*'` →
`android/app/build/outputs/roborazzi/*.png`. To check on the devices: cover images loading over
the hotspot, the ⋮ menu in iPhone list rows, and whether the iPhone Ride tab fits without
scrolling.
Live YouTube check of the browsing code passed (2026-09-29, `CatalogNetworkTest` with
`-Pnetwork`): album search returns `OLAK5uy_…` ids with 544 px square covers, playlist search
returns `PL…` ids (no track counts: YouTube leaves `streamCount` empty, so the UI shows none),
`browse` gives Dark Side of the Moon's 10 tracks in order with the album's 640 px cover. Song
results list only 60/120 px art, so `Catalog.bestImage` rewrites resizable googleusercontent
URLs to `=w544-h544` (verified to return a real 544×544 JPEG).

**The 2026-09-19 audit's `ios/` items are fixed (2026-09-29, uncommitted, still device-unverified):**
a call interruption or media-services reset mid-talk now closes with `"unavailable"` (PROTOCOL.md
"Talk flow" step 4); `music.ready` goes out at most once per track per connection (Android's
`onClientReady` re-sends the anchor on every duplicate, which would re-seek the iPhone); a new
connection resets the clock estimate; a host with another `hello.proto` is refused; `TrackCache`
downloads to `<id>.part.m4a` and renames only after the decode check. Headset labels in Settings
are generic now (the passenger has **Redmi Buds 6 Pro** on an **iPhone 15**, not AirPods).
Test tracks for the fake host: `tools/peer/tracks/` (gitignored, recipe in `ios/README.md`).
Still unverified on a real phone: whether the mic-permission
prompt appears while backgrounded or locked, which route-change notifications the A2DP↔HFP switch
posts, and whether `AVPlayer`/KeepAlive survive a media-services reset, and whether `AVPlayer` plays
the Opus-in-MP4 tracks (iOS 17+ should; if not, the host falls back to AAC on the first
`music.error "not decodable"` — look for "AAC for the rest of this session" in logcat). `AudioModeWatch`'s listener
on Android has still never produced a positive log line either.

## Decisions in force (don't reopen without the user)

- **Music UX round (2026-09-30, coordinator recommendation after the user's questions; built offline,
  device-unverified).** Touch play (`enqueue now`, `jump`, a history tap) during a talk **ends the
  talk** and plays, like a spoken `play …`; play next/add/remove/clear leave it open (PROTOCOL.md
  Browsing 3). New commands `what's playing` (`nowplaying`) and `shuffle`, both stay in the talk.
  The Pixel pre-downloads the next 3 tracks and gets a per-album/playlist **Download** button (the
  iPhone pulls from the Pixel, so nothing there). Recent searches + recently played on the Search
  tab of both phones, local to each. The wake word is gone from both Ride screens, replaced by a
  command list. **Suggested, not built, awaiting the user:** a `battery` command (both phones'
  battery; needs a small wire addition).
  Coordinator-run 2026-09-30 00:30: android 326 tests / 0 fail / 5 skipped, iOS `swift test` 140,
  peer 260 + 1 skipped; installed on both phones (Pixel debug, iPhone release). Device checklists:
  `android/HANDOFF.md` and `ios/README.md` "2026-09-30 additions".

- **Music quality (idea #3, 2026-09-24):** the best YouTube stream without Premium: Opus itag 251,
  remuxed to MP4 on the Pixel, with AAC itag 140 as the fallback. FLAC and 320 kbps are not possible:
  YouTube has no lossless audio, 256 kbps needs Premium (ReVanced does not change that), and A2DP
  re-encodes everything to AAC for the AirPods anyway. `tools/bench/a2dp_codec.sh` checks the Pixel
  is on AAC and not SBC.

- **Music and talk are never wanted simultaneously** (user, 2026-09-22, verbatim: *"it's this or
  that, never both"*). This is the load-bearing one: the A2DP↔HFP switch costs music quality only
  during a talk, which is time the user wants no music in. Do not re-derive an architecture from
  "but the music degrades".
- **The microphone is the Lark A1 in Stereo mode, and the earbud mics are the fallback** (the
  2026-09-27 ride recording was "mush"; Lark bought and gated 2026-09-28). **Host-mic talk**
  (user, 2026-09-29; `PROTOCOL.md` "Host-mic talk"): with the receiver in the Pixel, the Pixel
  captures both riders (L = pink = rider, R = yellow = passenger), sends L to the iPhone as today's
  voice, plays R locally into the rider's earbuds, and says `talk.open{mic:"host"}`; the iPhone
  opens no mic and only listens. **Both phones keep the earbuds in media mode (A2DP)** during such
  a talk, so there is no call switch. The passenger's first-phrase command is recognised by the
  Pixel on R. **No sidetone.** Music still pauses during talk. Without the receiver (or with the
  Pixel setting off) talk is exactly the earbud-mic path as before.
- **Volume is local.** `volumeUp`/`volumeDown` are not in `music.control`; a phone's spoken or
  pressed volume change never goes on the wire. A value outside an enum = malformed (drop, keep).
- **Talk is not negotiable** (the user was explicit: no decline button or setting, ever).
  `talk.close{reason:"unavailable"}` means only "cannot open the mic", in either direction —
  including a mic that fails *after* talk opened.
- **No host→client ping.** Won't do.
- **Overlay:** dropping the buttons on the ✕ sets `overlayEnabled=false`; they come back via the
  notification's "Show buttons" and the app's switch. **No auto-hide** — always shown while the
  service runs. The ✕ is "a bit ugly"; cosmetic, parked. Pixel only; iOS has no overlay and won't.
- **Single button:** wanted, and the spike says it is buildable; option A (below) is its plan now,
  not the old steps 6a/6b. Note its motivation is **back to full strength**: on the AirPods route every
  talk pays the 1–1.5 s switch, so a press that does the right thing first time is worth more, not
  less.
- **`duckDuringTalk` stays off**, and the Stage D "setting flippable on the road, defaulting per
  mode" is **on hold with Stage D** — it only ever made sense on a route that does not switch. On
  the AirPods route music cannot play through a talk at all, and the user does not want it to.
- **Talk ends on a press only** (user, 2026-09-29). The 20 s silence close could not fire on the
  AirPods mic (never DTX, below); a level-over-noise-floor detector was the rejected alternative.
  **Removed 2026-09-29** from `PROTOCOL.md`, `fixtures/`, the host (`TalkController` has no clock
  now, `VoiceEngine` no `onActivity`, `soloTalk` gone), the iOS enum and the Python peer
  (`--silence-ms` gone; `is_voice_activity` stays for its stats line). `"silence"` is no longer a
  valid `talk.close` reason, so it is dropped like any unknown enum value.
- **Talk/command control: option A, "commands inside talk"** (user, 2026-09-29; built offline the
  same day, spec in `PROTOCOL.md` "Commands", vectors `fixtures/first_phrase.json`; chosen over tap/hold on
  one button, handlebar-only, and hands-free). A press with no client connected always opens a solo
  talk now (commands alone; `captureDump` only decides the WAV). One action everywhere: a press toggles talk. While
  a talk is open, the phone that opened it runs speech recognition on the mic that is already live.
  **The first phrase decides** (user, 2026-09-29, replacing the "Moto party" wake word the same
  day; "I'm fine with" losing it): the opener's first phrase within 8 s of its live beep is a
  command if it parses, anything else — and every later phrase, and anything from the other
  phone — is conversation. A solo talk still treats every phrase as a command. The host only
  accepts a client's `command.text` in a talk the client opened, once. A music command
  (`play …`, `resume`) also ends the talk. The separate command mode, its route switch and the
  overlay's MUSIC zone go; the overlay becomes one big button. The handlebar box (Honda node) is later one physical button for the same action;
  hands-free (wake word, open-on-voice) is parked until the mic direction is known — it needs a mic
  that is open all the time without the HFP route, which the Lark could be. **Earbud presses
  trigger nothing** (user, 2026-09-29: the earbuds sit inside the helmet): media keys control the
  music only, on both phones; the settings for them are gone. Open: recognition quality at speed.
- **Wire format:** 16 kHz Opus, 20 ms frames, 24 kbps VOIP. A sample-rate change is out of scope —
  8 hard-coded constants across three implementations, ~40 test assertions, and the Python peer's
  silence detector reads the SILK VAD bit and stops working above 16 kHz. There is no negotiation
  mechanism to stage it, only `proto:1`.
- Spec details, all implemented: DTX frames are not sent, so every audio packet is voice activity;
  `ts` is a running 16 kHz clock and keepalives share `seq` (a `ts` jump with contiguous `seq` is
  silence, a `seq` gap is loss); malformed known message = drop and keep, not-JSON / not-an-object /
  oversize = close; negative-RTT clock samples take no slot and a >500 ms jump clears the window;
  mid-track join re-sends `music.load` (current + next) then `music.play`; command normalisation is
  per code point and U+2019 maps to an apostrophe.
- **Music drift:** A2DP adds 350–700 ms of fresh lag on every play or seek, so the host starts early
  by the measured delay, corrects 80 ms–1 s of drift by changing playback rate up to ±2 % (spread
  over 10 s, so it is not heard; was ±5 % over 4 s until 2026-09-24, idea #3), and
  re-seeks only above 1 s. **Check on the first real iPhone run:** does `setRate(_:time:atHostTime:)`
  already compensate output latency (then `outputLatency` is double-counted — go back to trim only);
  does assigning `rate` disturb the scheduled start on A2DP; is `.spectral` pitch algorithm OK on
  CPU (`.timeDomain` is the fallback).

## What the device benches established (durable; the evidence is in `tools/bench/results/`)

Pixel 8 / Android 17 / AirPods Pro, 2026-09-19 and 2026-09-20.

- **The A2DP↔SCO switch is not ours to make fast.** `setCommunicationDevice` returning is not the
  link. `am.availableCommunicationDevices` blocks 174–563 ms right after `setMode`, while
  AudioService tears down A2DP; `setMode(MODE_NORMAL)` on the way back blocks 600–800 ms; our own
  work is ~0–30 ms. The **real SCO link is up 0.9–1.7 s after the press**. "<300 ms" is not
  reachable; what the rider feels is ~1–1.5 s.
- **The SCO link flaps:** `OPEN → CLOSING` 27–47 ms later → `OPEN` again ~0.8–1.0 s later, in 3–5 of
  8 talks. That is what makes live ~2.0–2.5 s instead of ~1.2–1.4 s. `ScoWatch` sees none of it.
  Cause unknown; open.
- **`ACTION_SCO_AUDIO_STATE_UPDATED` is a lie about the link:** "connected" 534–1100 ms *before* the
  stack opens it, 18/18, and it misses every flap. `onCommunicationDeviceChanged(bt_sco)` lands
  +184…+530 ms *after* the last `OPEN_ST`, never before. Hence F8.
- **The AirPods mic is ~never DTX:** `tx 1193 sent of 1193 captured (0 DTX)` in 24 s of a quiet room;
  0 % DTX in 5 of 8 talks. Opus's VAD takes the SCO mic's noise for voice, and on a motorcycle it
  will never be quiet. This is what breaks the silence close.
- **Capture can start on the built-in mic** (`(null) => microphones -> voip-capture-0`) and move to
  `bluetooth-sco-headset-microphones` ~0.1 s later — so "first captured frame" ≠ "first frame from
  the headset". Hence F9a.
- **The phone is ready before the ears are:** in all 8 talks the HAL path was applied, the stream
  started and nothing re-routed 0.3–0.5 s *before* the beep the user still heard cut off. The AirPods
  start rendering later than the phone starts sending, by a varying amount the phone gets no event
  for.
- **Earcons:** AudioFlinger tags the closed/error tones `muted` in appops every cycle, and earcon
  `AudioTrack: stop(` lines are never logged on this phone (a MODE_STATIC track may never log one),
  so any bench check built on them is void. The user's ears are the instrument.
- **Music position on A2DP:** the ExoPlayer/Media3 position reading flips between two levels ~200 ms
  apart, at speed 1.0 too. The nudge math is right; the median filter lands between the levels, hence
  a ±80 ms band rather than ±0. Root cause (Media3 position tracker on a deep-buffer track) not
  chased. Post-talk resume is back within ±80 ms after ~18 s.
- **Recognizer** (spike, PASS): Google's on-device recognizer reads `EXTRA_AUDIO_SOURCE`, opens no
  mic, **does not need `RECORD_AUDIO`**, and `EXTRA_SEGMENTED_SESSION` works (one `onSegmentResults`
  per phrase, ~0.9–1.0 s after the phrase ends; partials near real time). The wake word comes back
  as **"Moto party"** (two words, capitalised) despite `EXTRA_BIASING_STRINGS`, so a parser must
  accept "motoparty" / "moto party" and trim. `AudioSource.UNPROCESSED` is **not** available on the
  Pixel 8. In `MODE_NORMAL` both `USAGE_MEDIA` and `USAGE_VOICE_COMMUNICATION` route to A2DP (says
  nothing about `MODE_IN_COMMUNICATION`). The recognizer does hear the user through the AirPods mic
  while we hold `MODE_IN_COMMUNICATION` (`research/RESEARCH.md` §3.3 settled).
- **The two-zone command path is bad** and the log explains every complaint: the LISTEN beep falls
  into the route change (a fixed `SCO_SETTLE_MS`, the disease F7 cured for LIVE), the mic starts
  ~1.5 s after the press, and the spoken reply plays straight across the teardown — part SCO, part
  phone speaker, then A2DP. The single button removes the separate command route switch, which is
  the biggest cost here.
- **Verified working on the device:** the jitter re-anchor (fired twice, playback carried on); both
  `unavailable` paths per `PROTOCOL.md` "Talk flow"; the overlay in all four rotations, injected
  drags and the user's own finger (drag, drop on the ✕, "Show buttons"); talk path `VERDICT: CLEAN`
  with 0 `waited … for Main` lines since F3.

## Parked, not lost

- The **single button** is now part of option A (above).
- The **SCO flap**, and the pointless SCO bounce on a refused talk (the host enters the call route
  for a talk the client refuses and leaves it 1.5 s later).
- **`bench.py` parses none of** the `live cue` / `media cue` / `mic trace` / `capture dump` /
  `audio devices` lines: every table in this file was made by hand. Do it when nobody else is in
  `tools/bench`.
- Two `motoparty-audio` threads were once seen alive in one process. Unexplained.
- The overlay ✕ is a bit ugly (cosmetic).

## Hotspot

- **5 GHz is not available on the user's Pixel ("not available in your country"), so 2.4 GHz is the
  real target.** Tested with Bluetooth on (AirPods connected, ch 11, phone ~1 m, stationary):
  **good enough for talk; don't raise the jitter ceiling.** Pong RTT median/p90/p99/max in ms —
  screen on 14/41/80/80, screen off 15/38/86/86, music over A2DP 20/52/94/94, talk over HFP
  14/30/118/118. Nothing over 200 ms in any phase. ICMP at 5 Hz: median 6–7 ms, p99 51–67 ms, 1 lost
  packet of ~1900. A 30 s talk: 0 lost, 1 late, jitter target 40 → 60 → 40 ms. Bonjour found the app
  in 1.4 s. So Bluetooth adds ~20 ms to the p90 tail and nothing that matters. Untested: a ride
  (motion, weaker link, two real voices).
- Laptop side: NetworkManager, one Wi-Fi card (`wlp1s0`), so the laptop leaves home Wi-Fi during a
  hotspot test. Use the NM profile **`Aviv's Hotspot 1`** (`Aviv's Hotspot` is bound to a dead
  interface name and fails). adb over the hotspot works at `<gateway>:5555`; the Pixel's hotspot
  cannot be toggled from adb without root, so the user turns it on. Android randomises the subnet.
- Not done: the /24 sweep fallback over the hotspot (`--no-mdns` exists in the peer client but was
  never tried there).

## Running a device session

**Gate, every time, before anything else — and one ask at a time:**

1. `timeout 10 adb -s 192.168.1.100:5555 get-state` = `device` (else `adb connect`; if the phone is
   off home Wi-Fi, ask the user).
2. What the phone is doing: `dumpsys power | grep mWakefulness`, the foreground activity, whether an
   A2DP/HFP device is connected (`dumpsys audio | grep -iE 'mode|communication'`), who owns the
   media button.
3. **Say plainly what will make a sound in the user's ears, and wait for their go.**
4. Install the current build so every step starts from the same one:
   `cd android && ./gradlew installDebug`, then grant RECORD_AUDIO / POST_NOTIFICATIONS / overlay as
   `android/HANDOFF.md` lists, launch once, check the buttons window is up.

Rules: never `sudo`; no git commits; always `adb -s 192.168.1.100:5555` with every call wrapped in
`timeout` (an offline transport blocks for ever); only install/grant/revoke for
`com.kivan.motoparty`; don't change Wi-Fi, hotspot or Bluetooth state. The user may pick the phone
up — the scripts wait if another app is in front with the screen on. Leave the phone as found: music
paused, locked if it was locked, settings restored. Keep the screen awake with `KEYCODE_F13`
(`KEYCODE_WAKEUP` does not reset the screen-off timer when the phone is already awake).
**Re-read every summary from the result files yourself; never take an agent's report for it.**

## Waiting on the user

- ~~**The ride recording**~~ — done 2026-09-27, verdict mush (see "Do now" step 2). Now waiting on:
  **the ride** on the Lark A1 Duo, ENC Off then On (arrived 2026-09-28; S1–S4, A2 and the helmet
  test pass, gain now 2; gap measurement waived, A3 soak folded into the ride, A4 deferred — user
  decisions). Hook-and-loop tape for the helmet mount is in the user's AliExpress cart.
  Return window is running from 2026-09-28.
- An iPhone on USB with Developer Mode on, then `cd ios && xtool dev` (Spike 1). The user logged into
  `xtool auth` with their **personal Apple ID**, was told about the 0xe8008024 ban reports and
  decided to keep using it; don't raise it again.
- Hands-on: real spoken commands through the AirPods, a listening pass on the F9a/F9b beeps, a ride.

## Environment facts

- **Never run `sudo`** — hand the user a `!`-prefixed one-liner instead. Toolchain changes are
  logged in `~/AGENTS.md` (Swift 6.3.3, xtool 1.19.2, the Darwin SDK, NDK, CMake, usbmuxd,
  libxml2-legacy). The user wants plain-language status and agents kept under ~200k tokens (they
  stopped one at 210k): give agents narrow tasks and have them keep their own HANDOFF current.
- Swift is not on PATH by default:
  `~/.local/share/swift/swift-6.3.3-RELEASE-ubuntu24.04/usr/bin`.
- Pixel 8 (Android 17) over adb Wi-Fi, listed twice: always `adb -s 192.168.1.100:5555` on home
  Wi-Fi. *2026-09-28:* `adb connect 192.168.1.100:5555` was refused; the phone was reachable as
  `adb -s adb-37171FDJH0071S-omL9n2._adb-tls-connect._tcp` (Wireless debugging, mDNS) — check
  `adb devices -l` first. The laptop is 192.168.1.102. `KEYCODE_SLEEP` locks, `KEYCODE_WAKEUP` wakes.
- The user's AirPods are paired to the Pixel: bench audio plays **in their ears**. Keep it short
  unless the test is about exactly that, and say so beforehand.
- Laptop firewall (nftables, input drop policy): the peer in *client* mode needs nothing; fake-host
  mode needs ports 47800–47802 opened by the user (command in `tools/peer/README.md`).
- Phone left at the end of the seventh session: music mode, buttons on (`overlayEnabled=true`,
  `fx=0.662 fy=0.313`), the spike app still installed with RECORD_AUDIO granted.
