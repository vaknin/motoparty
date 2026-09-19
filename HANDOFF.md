# Coordinator handoff (2026-09-19)

For the Claude session that coordinates Motoparty. You own `PROTOCOL.md`, `fixtures/`, the
root docs and the decisions; component work goes to subagents, one per directory, in the
background, with a final report back to you.

## Read first

1. `~/.claude/plans/motorcycle-communication-application-proud-pixel.md`: the approved plan.
2. `PROTOCOL.md`: the wire contract, revised several times today. It is the source of truth
   over any code comment.
3. `fixtures/`: shared test vectors. All three implementations pass the current set.
4. Component notes: `android/HANDOFF.md` (the most detailed), `android/README.md`,
   `ios/README.md`, `tools/peer/README.md`.

## Status

| Component | State |
|---|---|
| `tools/peer` (Python, uv) | Done, incl. local volume + `unavailable` (2026-09-19). `uv run pytest`: 158 pass. New stdin: client `unavailable` / `--mic-unavailable`, fake host `mic on|off`; `raw <json>` sends the now-invalid volume action on purpose. Client and fake-host modes; its README has bench recipes. |
| `ios/` (SwiftPM + xtool) | `swift test`: 66 pass on Linux (incl. a new command parser tested against all of `fixtures/commands.json`). Local volume (`Audio/LocalVolume.swift`, hidden MPVolumeView slider ±1/16; unproven until a real iPhone) and `unavailable` are in. **The app now compiles for arm64-apple-ios, 0 errors / 0 warnings** (`cd ios && xtool dev build [--ipa] [-c release]`, unsigned, no phone or login needed; output in `ios/xtool/`). Only 2 fixes were needed, both in `Audio/SessionController.swift` (the AirPods mute gesture now uses `AVAudioApplication.inputMuteStateChangeNotification`: the handler API is macOS-only; `.allowBluetooth` → `.allowBluetoothHFP`). App target is Swift 5 mode; strict concurrency would be ~140 warnings and is a separate refactor, deliberately not done. Never run on a device; `ios/README.md` lists the 11 things only a real iPhone can answer. |
| `android/` | All 7 layers built; `./gradlew assembleDebug test` = 65 tests, 63 pass, 2 skipped (live YouTube, `-Pnetwork`); coordinator re-ran it. Local volume + `unavailable` in, plus enum validation in the codec (it had none). Verified on the Pixel vs tools/peer on home Wi-Fi: 31 min locked soak (930 pings, 0 link losses, deep doze reached), talk/music/TTS while locked (`KEYCODE_SLEEP` does lock this phone; the older note was wrong), 10 s silence close, overlay taps/drag, framing conformance 9/9, volume actions dropped as malformed. **Open:** talk open/close blocks Main ~2.7 s → post-talk sync takes ~25 s to settle; overlay lands off-screen after rotation; the two `unavailable` paths not benched on device. A follow-up agent was launched 2026-09-19 for all three (told not to touch the phone while the user is using it). Details: `android/HANDOFF.md`. |
| Phase 0 spikes | Spike 1 (xtool) waits on the user. Spikes 2–4 need the iPhone, AirPods and rides. |
| git | `git init` done, **nothing committed**. Commit only when the user asks. |

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

## Protocol gaps: decided with the user 2026-09-19 (PROTOCOL.md + fixtures already changed)

1. **Volume is local.** `volumeUp`/`volumeDown` removed from `music.control`; a phone's spoken
   or pressed volume changes that phone only and is never sent. The client runs the command
   parser locally first (new parser in iOS MotopartyCore); a host that still gets a volume
   utterance answers "Didn't catch that". A value outside an enum = malformed (drop, keep);
   two new `malformed` vectors in `control/framing.json`.
2. **Talk is not negotiable** (the user was explicit: no decline button or setting, ever). New
   `talk.close` reason `"unavailable"` only for "cannot open the mic" (cellular call, permission
   missing, route failure), either direction; new vector in `control/messages.json`.
3. **No host→client ping.** Won't do; the host keeps showing skew.

Rollout (launched 2026-09-19; **all three done**, coordinator re-ran all three test suites and the iOS device build; on-device bench of `unavailable` pending): Android agent told by message mid-run; iOS agent and tools/peer
agent launched for the same two changes. Until all three report, fixture tests may be red in
whichever component hasn't caught up. After they report: coordinator re-runs all three test
suites, then benches "unavailable" and the dropped volume action on the Pixel with the peer.

## Waiting on the user

- ~~`sudo pacman -S --needed usbmuxd libxml2-legacy`~~ done 2026-09-19 (usbmuxd 1.1.1,
  libxml2-legacy 2.13.9). The toolchain's copied `usr/lib/arch-compat/libxml2.so.2` is now
  redundant but harmless; leave it.
- **No iPhone available for now** (user, 2026-09-19 evening session): Spike 1's device steps
  and all iOS on-device work are parked. The `.xip` download + `xtool sdk install` can still
  be done without the phone and would let an iOS agent type-check the app (`xtool dev build`).
- ~~Xcode `.xip`~~ done 2026-09-19: Xcode 26.6 (17F113, Swift 6.3.3 = our toolchain; SHA-1
  verified against xcodereleases.com) → `xtool sdk install` → iPhoneOS26.5.sdk, `swift sdk list`
  shows `darwin`. The `.xip` was deleted afterwards (re-download only to rebuild the SDK).
- `xtool auth` done by the user **with their personal Apple ID**,
  not a throwaway. They were told about the 0xe8008024 ban reports and **decided to keep using
  the personal ID** (2026-09-19); don't raise it again.
- Still open: the iPhone on USB with Developer Mode on, `cd ios && xtool dev`. Details: `ios/README.md`.
- Tests that need hands (locked talk and the long run are done via adb): (1) Pixel 5 GHz
  hotspot, laptop joins it, `cd tools/peer && uv run motoparty-peer client --no-audio` (no
  `--host`: tests discovery too), check `stats` RTT with the Pixel's screen off; (2) speak
  real commands through the AirPods ("play album abbey road", "next", "louder"); (3) listen
  to a talk session for quality and that the "live" earcon is audible after the HFP switch;
  (4) a ride. Why (1) matters: on home Wi-Fi with the screen off, ping p99 was 229 ms
  (screen on: 115 ms): Wi-Fi low-latency mode only holds while screen-on. As the SoftAP it
  may differ; if not, options are a dimmed screen-on during talk or a jitter ceiling above 200 ms.

## Environment facts

- The user's rules: never run sudo (hand over a `!`-prefixed one-liner); log toolchain
  changes in `~/AGENTS.md`. Swift 6.3.3, xtool 1.19.2, NDK 29.0.14206865 and CMake 3.31.6
  are already logged there.
- Swift is not on PATH by default: `~/.local/share/swift/swift-6.3.3-RELEASE-ubuntu24.04/usr/bin`.
- Pixel 8 (Android 17) over adb Wi-Fi, listed twice; always `adb -s 192.168.1.100:5555`.
  The laptop is 192.168.1.102 on the same LAN. The user's AirPods are paired to the Pixel, so
  bench audio plays in their ears: keep test audio short.
- The user opened the laptop's firewall ports 47800-47802 for the peer's fake-host mode
  (runtime nft rules; gone after a reboot or nftables reload; the command is in
  `tools/peer/README.md`).

## Next steps

1. (Launched 2026-09-19, second coordinator session) Android continuation agent with the
   prompt below, plus: simulate lock/doze via adb where possible, don't touch the phone's
   Wi-Fi/hotspot, end with a by-hand checklist for the user. If the session restarted before
   its report arrived, check `android/HANDOFF.md` for how far it got and relaunch.
2. Done 2026-09-19: iOS drift rule (see "Music drift" above); coordinator re-ran `swift test` = 62 pass.
3. When the user has installed usbmuxd and has the `.xip`, walk them through Spike 1, then
   send an iOS agent to fix the compile errors from the first `xtool dev`.
4. Done: protocol gaps decided (see above); collect the three rollout reports and verify.
5. Offer an initial git commit once the Android agent is done.

## Prompt for the Android continuation agent

```
You are continuing the Android (host) half of Motoparty in /home/kivan/Work/motoparty/android.
A previous agent built most of it; its handoff notes are the starting point.

Read first, fully, in this order:
1. /home/kivan/Work/motoparty/android/HANDOFF.md: current state, what's verified, what's left, gotchas, first steps.
2. /home/kivan/Work/motoparty/PROTOCOL.md: the binding wire contract (trust it over any code comment).
3. /home/kivan/Work/motoparty/fixtures/: shared test vectors (control, framing incl. "malformed"/"fatal", clock, voice header, commands).
4. The "Android" section of /home/kivan/.claude/plans/motorcycle-communication-application-proud-pixel.md: the spec.
5. /home/kivan/Work/motoparty/tools/peer/README.md: the finished Python client for bench tests
   (`cd tools/peer && uv run motoparty-peer client --host 192.168.1.100 --no-audio`; stdin: talk, say <text>,
   pause/resume/next/previous/vol+/vol-, raw <json>, quit).

Then do HANDOFF.md's "first three things" and carry on with its not-done list in priority order.

PROTOCOL.md's music "Drift" rule was just rewritten to match what the previous agent built (early start by
measured output delay, ±5 % rate correction, re-seek only above 1 s). Open protocol gaps; don't change the
wire format for them without asking the coordinator: no host→client volume message, no way to refuse
talk.open, no host→client ping.

Hard rules
- Never run sudo; report any root one-liner needed.
- No git commits, no pushes. Edit only android/. If PROTOCOL.md or the fixtures look wrong, report it and don't edit them.
- User-level toolchain installs are OK; list them in your report.
- Only install/uninstall/grant for com.kivan.motoparty on the phone.
- Keep test audio short: the user's AirPods are paired to the Pixel and hear the beeps.

Environment
- Arch Linux, no Android Studio, SDK ~/Android/Sdk, JDK temurin-21 (via ~/.gradle/gradle.properties).
- Pixel 8 on Android 17 over adb Wi-Fi. It is listed twice; always use `adb -s 192.168.1.100:5555`.
- The laptop is 192.168.1.102 on the same LAN and can reach the Pixel on ports 47800-47802.

Keep android/HANDOFF.md up to date as you go, so a later restart is cheap.

Final report (concise, for the coordinator):
- test counts;
- what was verified on the device against tools/peer;
- what is still stubbed or untested;
- anything installed;
- protocol issues;
- anything that needs the user.
```
