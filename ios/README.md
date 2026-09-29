# Motoparty iOS client

The iPhone (passenger) half of Motoparty: a SwiftPM package built, signed and installed from
Linux with [xtool](https://github.com/xtool-org/xtool), with no Mac and no paid developer account.
The wire protocol is [`../PROTOCOL.md`](../PROTOCOL.md). The shared test vectors are in
[`../fixtures`](../fixtures).

```
Package.swift            one automatic library product "Motoparty" = the app (xtool convention)
xtool.yml, Info.plist    bundle ID + Info.plist keys merged by xtool (background audio, mic,
                         local network/Bonjour, speech, ATS local networking, persistent Wi-Fi)
Sources/COpus/           libopus 1.5.2, portable float build (vendored; see below)
Sources/MotopartyCore/   pure Swift + Foundation, tested on Linux:
                           Messages (all control messages, Codable), Framing (u32, 64 KiB cap),
                           ClockSync, VoicePacket/VoiceSequencer, JitterBuffer, Opus wrapper,
                           MusicAnchor/DriftController, LinkMath (sweep, liveness, TXT), Earcons (WAV synth),
                           CommandParser (the grammar, run here too because volume is local; the
                           wake-word rule and WakeGate, its 5 s arming window), RemoteAction
Sources/Motoparty/       the iOS app (only compiled by xtool against the iOS SDK):
  Link/                    Discovery (NWBrowser + /24 sweep), ControlClient, VoiceSocket
  Audio/                   SessionController (A2DP music ↔ HFP talk), VoiceEngine, KeepAlive, EarconPlayer,
                           LocalVolume (this phone's system volume)
  Music/                   TrackCache (URLSession), SyncedPlayer (AVPlayer setRate atHostTime), NowPlaying
  Voice/                   Transcriber (on-device SFSpeechRecognizer fed by the talk's mic, one
                           request per phrase), Announcer (AVSpeechSynthesizer)
  UI/                      SwiftUI tabs: Ride (link pill, now playing + transport, TALK),
                           Search (songs/albums/playlists, album detail), Queue; Settings sheet
  AppModel.swift           ties it together (talk / music flows, commands inside talk)
Tests/MotopartyCoreTests/ XCTest: every fixture file, jitter buffer, Opus round trip + FEC
scripts/fetch-opus.sh    re-vendors libopus (verifies SHA-256)
```

Every file in `Sources/Motoparty` is wrapped in `#if os(iOS)`. On Linux, `swift build` and
`swift test` build COpus, MotopartyCore and the tests, and the app target compiles to an empty
module.

The app **compiles for arm64-apple-ios** against the real iOS SDK (iPhoneOS26.5, deployment
target iOS 17), with no errors and no warnings. Building needs no phone, no Apple ID and no
signing:

```bash
cd ios
xtool dev build                 # → ios/xtool/Motoparty.app (unsigned)
xtool dev build --ipa           # → ios/xtool/Motoparty.ipa (unsigned, Payload/Motoparty.app)
xtool dev build -c release      # same outputs, optimized: use this for real rides
                                # (unoptimized Opus is very slow — it says so itself)
```

`xtool dev build` wraps `swift build --swift-sdk arm64-apple-ios` (that also works on its own,
but only produces the module — not the .app bundle with the merged Info.plist). Signing and
installing happen only in `xtool dev` (no `build`), which is the step that needs the phone and
the Apple ID.

The app target stays in Swift 5 language mode on purpose (see `Package.swift`). Turning
`StrictConcurrency` on adds ~140 warnings, nearly all of them "non-Sendable `self` captured in
a `@Sendable` closure" on the queue-confined classes (`ControlClient`, `Discovery`,
`VoiceSocket`, `SessionController`, `SyncedPlayer`, …). Clearing them means actor isolation or
blanket `@unchecked Sendable`, not a local fix, so it is a deliberate separate job.

## Toolchain (already installed, user-level)

- **Swift 6.3.3** (the ubuntu24.04 build, signature verified) is in
  `~/.local/share/swift/swift-6.3.3-RELEASE-ubuntu24.04`. xtool's docs pin Swift 6.3 with
  Xcode 26, so this is 6.3.x, not the newer 6.4.0. Add it to `~/.bashrc`:

  ```bash
  export PATH="$HOME/.local/share/swift/swift-6.3.3-RELEASE-ubuntu24.04/usr/bin:$PATH"
  ```

  Arch compat: the toolchain wants `libncurses.so.6` and `libxml2.so.2`, and Arch ships only
  `libncursesw.so.6` and `libxml2.so.16`. So `usr/lib/arch-compat/` inside the toolchain holds
  a `libncurses.so.6 → /usr/lib/libncursesw.so.6` symlink and `libxml2.so.2`, taken from Arch's
  `libxml2-legacy-2.13.9-2` package after checking its SHA-256 against the signed sync DB. The
  toolchain's RUNPATH finds both via symlinks, so no `LD_LIBRARY_PATH` is needed. That copied
  libxml2 links `libicuuc.so.78`, so an ICU soname bump would break it. The durable fix is
  `sudo pacman -S libxml2-legacy` (in the one-liner below), after which you can delete
  `usr/lib/arch-compat/libxml2.so.2` and the two `libxml2.so.2` symlinks. lldb, which needs
  libpython3.12 and libedit.so.2, is not fixed and not needed.
- **xtool 1.19.2**: the AppImage is at `~/.local/bin/xtool` (SHA-256 matches the GitHub release
  digest). It runs with the system fuse3; no fuse2 needed.

## Core tests (Linux)

```bash
cd ios
swift build           # COpus + MotopartyCore (+ empty app module)
swift test            # 80 tests: fixtures, command parser + wake word, jitter buffer, Opus, drift controller
```

Opus prints "compiling without optimization" in debug builds. That is expected. Use
`swift build -c release` or `xtool dev -c release` for real use.

## Spike 1: install on the iPhone from Arch

1. **Root packages (one line):**

   ```bash
   sudo pacman -S --needed usbmuxd libxml2-legacy
   ```

   `usbmuxd` is what xtool uses to talk to the phone over USB. It is udev-activated, so replug
   the phone after installing. `libimobiledevice` 1.4.0 (`ideviceinfo`, `idevicepair`,
   `idevicesyslog`) is already installed.

2. **Xcode SDK** — *done*: built from Xcode 26.6, giving iPhoneOS26.5.sdk in
   `~/.config/swiftpm/swift-sdks/darwin.artifactbundle` (`swift sdk list` → `darwin`, target
   triple `arm64-apple-ios`). To redo it: sign in at
   <https://developer.apple.com/download/all/?q=Xcode> with an Apple
   ID in the browser, and download the latest **Xcode 26.x** `.xip` (26.4 or later ships Swift
   6.3). Use a throwaway Apple ID (see step 3). Then build the iOS Swift SDK. No login is
   needed for this step. It takes a while and needs plenty of free disk, because the `.xip`
   expands to well over 10 GB while the SDK is extracted:

   ```bash
   xtool sdk install ~/Downloads/Xcode_26.x.xip
   xtool sdk status        # → installed; `swift sdk list` shows darwin
   ```

   If you only find Xcode 27 (newer Swift), install the matching Swift toolchain too
   (6.4.0 at the time of writing), the same way as above.

3. **Apple ID login:** `xtool auth` (or `xtool setup`, which runs auth + sdk). Choose
   **Password** mode and enter the Apple ID, password and 2FA code. Use a **throwaway Apple ID**:
   there are 2026 reports of free IDs getting "profile banned" (`0xe8008024`) after sideloading
   through the private APIs.

4. **Phone:** connect it by USB, unlock it, tap **Trust**, and check `ideviceinfo` prints the
   device. Then:

   ```bash
   cd ios
   xtool dev               # build (first run compiles the SDK modules: minutes) + sign + install
   ```

   - If it asks for **Developer Mode**: Settings → Privacy & Security → Developer Mode → on,
     reboot, run `xtool dev` again.
   - On first launch, trust the profile: Settings → General → VPN & Device Management → your
     Apple ID → Trust.
   - If a pairing error follows the Trust dialog, run `xtool dev` again.
   - To check the toolchain + signing loop apart from our code, run
     `xtool new Hello --skip-setup && cd Hello && xtool dev` in a scratch directory.
   - The bundle ID is `com.vaknin.motoparty`. xtool prefixes it (`XTL-xxxx.`) for free accounts.

5. **Logs:** `idevicesyslog | grep -i motoparty` (categories link, audio, music, voice, app).

6. **Free-account limits:** the profile expires after **7 days**, so re-run `xtool dev` (or
   `xtool dev -c release`) before each ride. Free accounts allow at most 3 sideloaded apps and 10
   App IDs per week.

### Testing without the Pixel

`tools/peer` has a fake host. Run it on the laptop, with the laptop and the iPhone on the same
Wi-Fi:

```bash
# laptop firewall: let the iPhone reach the fake host (nft ruleset reload removes it again)
sudo sh -c 'nft insert rule inet filter input tcp dport { 47800, 47802 } accept && nft insert rule inet filter input udp dport 47801 accept'
cd tools/peer && uv run motoparty-peer host --track tracks/test-opus.m4a
```

`tools/peer/tracks/` (gitignored) holds two 90 s stereo test tracks, 440 Hz on the left and an
880 Hz beep on the right: `test-opus.m4a` (Opus in MP4, what the Pixel sends first) and
`test-aac.m4a` (the AAC fallback). If AVPlayer cannot play the Opus one, the app answers
`music.error "not decodable"`; the fake host does not fall back on its own, so restart it with
the AAC track. To regenerate them:

```bash
cd tools/peer/tracks
ffmpeg -f lavfi -i "sine=frequency=440:duration=90,volume=0.3" \
  -f lavfi -i "sine=frequency=880:duration=90:beep_factor=4" \
  -filter_complex "[0][1]amerge=inputs=2" -ar 48000 -c:a libopus -b:a 160k -f mp4 test-opus.m4a
ffmpeg -i test-opus.m4a -c:a aac -b:a 192k test-aac.m4a
```

The fake host advertises `_motoparty._tcp`, serves the track, and answers `command.text`. While
talk is open it echoes voice back, so the iPhone hears itself. Its stdin takes `load`, `play`,
`pause`, `talk`, `announce <text>` and `raw <json>`. See `tools/peer/README.md`.

## Fallbacks if xtool on Linux fails

A. **CI build + local install.** A GitHub Actions macOS runner has Xcode, and building an
   `.ipa` needs no Apple login. Put this at `.github/workflows/ios.yml` in the repo root. It is
   untested, and the path inside `xtool.app.zip` is assumed:

   ```yaml
   name: ios
   on: workflow_dispatch
   jobs:
     ipa:
       runs-on: macos-latest
       steps:
         - uses: actions/checkout@v4
         - run: |
             curl -fL https://github.com/xtool-org/xtool/releases/download/1.19.2/xtool.app.zip -o xtool.zip
             unzip -q xtool.zip
             cd ios && ../xtool.app/Contents/MacOS/xtool dev build --ipa -c release
         - uses: actions/upload-artifact@v4
           with: { name: Motoparty.ipa, path: ios/xtool/Motoparty.ipa }
   ```

   Download the artifact, then run `xtool install Motoparty.ipa` on Linux. That signs it with
   the free Apple ID and installs it, with no Xcode.xip needed locally. To use Xcode's own
   build instead, generate a project with `xtool dev generate-xcode-project` (macOS only) and
   run `xcodebuild -sdk iphoneos CODE_SIGNING_ALLOWED=NO`, then zip `Payload/Motoparty.app`
   into an `.ipa`.

B. **zsign + ideviceinstaller.** If xtool's signing is what fails but you have a certificate
   and a provisioning profile: `zsign -k cert.p12 -p <pw> -m profile.mobileprovision -o
   signed.ipa Motoparty.ipa`, then `ideviceinstaller -i signed.ipa`. zsign comes from
   github.com/zhlynn/zsign and ideviceinstaller from the AUR; neither is in the official repos.

C. **SideStore** on the phone: install the unsigned `.ipa` through SideStore. It signs on the
   device and refreshes the 7-day profile over Wi-Fi, without the laptop.

## Opus vendoring

`scripts/fetch-opus.sh` downloads opus 1.5.2 from downloads.xiph.org, checks its SHA-256, and
copies only the portable float sources (CELT, SILK, SILK float, and the `src` lists from the
release's `*_sources.mk`) plus all headers into `Sources/COpus`. Those sources are committed,
so builds need no network. No SIMD, asm or DNN code is included, so the same files build for
Linux x86_64 and iOS arm64. Build settings are in `Sources/COpus/config.h`
(`OPUS_BUILD`, `USE_ALLOCA`, `HAVE_LRINTF`, float). `include/motoparty_opus.h` wraps the
variadic `opus_*_ctl` calls, because Swift cannot call C varargs.

## Behaviour notes

- **No floating buttons on iOS.** The TALK overlay above other apps exists on the Pixel
  only (decided with the user 2026-09-20): iOS cannot draw over other apps, and nothing in
  `ios/` tries to. The passenger's triggers are the app's own buttons and the headset controls.
- **Screens:** three tabs. **Ride** has the link pill (host name and round trip), the
  now-playing card (cover from `state.music.art`, progress, previous / play-pause / next as
  `music.control`), the downloading line, TALK, and the last command heard / announced /
  problem lines; the gear opens Settings (latency trim, headset buttons, link details).
  **Search** sends `music.search` (Songs / Albums / Playlists); a song tap is
  `music.enqueue{mode:"now"}` with that track, its ⋯ menu (or a swipe) is Play next / Add to
  queue. An album or playlist opens a detail screen (`music.browse`) with Play / Add to queue;
  a track tap enqueues `now` the tracks from that one to the end. Tracks carry the collection
  title as `album` and its cover as the top-level `art`. **Queue** shows `state.queue`: tap =
  `music.edit jump`, swipe = `remove`, Clear (confirmed) = `clear`. Browsing is disabled while
  disconnected.
- **Browsing requests:** every search or browse takes the next request id; each list (search,
  collection) shows only its newest request's `music.results` and gives up after 20 s without
  an answer. `MusicEnqueue.fitted()` (MotopartyCore) keeps an enqueue at 200 tracks and under
  64 KiB: per-track art goes first, then trailing tracks.
- **Talk:** TALK sends `talk.open{by:client}`, and nothing changes until the host decides. On
  `talk.open` the app pauses music and switches the session to `.playAndRecord`/`.voiceChat`
  with Bluetooth HFP. It then starts a fresh voice-processing AVAudioEngine and plays the
  "live" earcon. Capture uses an AVAudioSinkNode rather than a tap, because taps deliver at
  least 100 ms per buffer. Frames flagged by `OPUS_GET_IN_DTX` are not sent. Playback goes
  jitter buffer → Opus → AVAudioSourceNode. On `talk.close` the app switches back to
  `.playback` (A2DP), and the host resumes music with `music.play`.
- **The "live" earcon fires on a real signal, never on a delay** (Android's F7/F8/F9a rule,
  `LiveCue`). `VoiceEngine.onCaptureUp` is called on the main queue from the first buffer the
  capture sink delivers — exactly once per `start`, re-armed by the next one, and it never
  blocks the sink thread (it flips a flag under an unfair lock and hops to Main). `AppModel`
  arms the cue when the voice engine starts and plays it on that callback, at most once per
  talk open, with a 3.5 s fallback timer (the same number as Android's `LiveCue.TIMEOUT_MS`)
  so a talk whose capture never delivers still beeps. Which one fired is logged as
  `live cue: fired +<n> ms (capture up|fallback)` in the `audio` category. A talk that ends
  before the mic was live never beeps. Android's SCO/`MicLive` conditions are deliberately
  **not** ported: iOS gives no equivalent route signal, and the sink's first buffer is the
  honest one this platform has.
- **Jitter buffer backlog cap.** Besides the adaptive target of PROTOCOL.md ("Voice"), `pull`
  holds at most `maxTargetMs + backlogSlackMs` = 400 ms = 20 frames, the same hard cap as
  Android's `MAX_MS + BACKLOG_SLACK_MS`. A burst the output never drained (a route switch, a
  Wi-Fi stall) used to sit there as up to 2 s of delay until the next pause; now the oldest
  frames go and the playout cursor moves right behind the last one dropped (`lastSeq`, `nextTs`),
  so they are not counted as loss and the following frame plays in the same spurt. Only
  `stats.dropped` records them. The 100-packet insert cap stays, exactly as on Android.
- **Talk is not negotiable** (PROTOCOL.md "Talk flow"), so there is no decline button and none
  may be added. There is only *cannot*: if the record permission is denied, or activating the
  `.talk` session fails (a cellular call holds the input), or the voice engine will not start,
  the app sends `talk.close{by:"client",reason:"unavailable"}`, plays the error earcon if this
  phone was the one that asked, and returns to the media session; the host then closes talk.
  An undetermined permission is asked for once and a refusal counts as "cannot". The mirror
  case — `talk.close{reason:"unavailable"}` answering our own `talk.open`, so talk never
  opened and `state.talk` stayed false — only clears the "requesting" state and plays the
  error earcon: no audio session is torn down and no music is resumed, because none was paused.
- **Commands inside talk** (option A, 2026-09-29; PROTOCOL.md "Commands"). There is no
  command mode and no MUSIC button: one press toggles talk, and while a talk is open the
  phone recognises speech on the mic the talk already has. `VoiceEngine` tees every 16 kHz
  capture buffer, on the capture queue and before the Opus encoder (so DTX never cuts it), to
  `Transcriber.append`, which only counts and hops to its own queue (at most 50 buffers
  waiting; past that they are dropped), so the capture path never waits for the recogniser.
  The recogniser is on-device when the language has a model, and runs one
  `SFSpeechAudioBufferRecognitionRequest` per phrase: a final result, or 1.2 s without new
  partial text, ends the request, hands its text to `AppModel`, and the next request starts at
  once, until the talk closes. Errors (typically "no speech detected" after a quiet stretch)
  are logged in the `voice` category and the chain restarts, backing off 1, 2, 4 … 10 s while
  it keeps failing at once; the talk itself is never touched, and a missing recogniser or
  speech authorisation only means this talk has no commands. `AppModel` passes each phrase
  through `WakeGate`: the client is never solo, so the wake word is always required
  ("motoparty", "moto party" or "motor party" first, after any "hey"/"ok"/"okay";
  `fixtures/wake.json`). A phrase that is only the wake word arms the gate and plays the
  `listen` earcon (one 1175 Hz blip, Android's `LISTEN`): the next phrase within 5 s is a
  command without it. Anything else is conversation, never sent or acted on. Every phrase is
  logged as `heard: "<text>" (command|conversation|armed)`. A command's text (after the wake
  word, normalised) goes through `CommandParser`: volume is handled here (below); everything
  else, `end` ("over", "end talk", "hang up") included, is sent as
  `command.text{text, lang}` and the host decides what it does to the talk (`play`, `resume`
  and `end` close it with the usual `talk.close`).
- **Volume is local** (PROTOCOL.md "Commands"): `MotopartyCore.CommandParser` runs on this
  phone's own command before anything is sent, and `volume up`/`louder`/`volume down`/
  `quieter` change *this* phone's volume with the `ok` earcon and no `command.text`. Everything
  else goes to the host unchanged. `music.control` has no volume actions any more (one on the
  wire is a malformed message and is dropped). iOS has no public system-volume setter, so
  `LocalVolume` writes the hidden `UISlider` of an off-screen `MPVolumeView` — the real system
  / AirPods volume, in steps of 1/16 (one hardware-button press), reading the current level
  from `AVAudioSession.outputVolume`. That private-view trick and the system volume HUD it may
  show are the parts to check on a real phone; if it ever stops working, only `LocalVolume`
  changes. The hardware volume buttons need nothing: they are already local.
- **Music:** each track is downloaded in full, then `music.ready` is sent. Playback uses
  `AVPlayer.setRate(1, time:, atHostTime:)`, converting host clock → local clock → CMClock host
  time. The player starts early by this phone's output delay —
  `AVAudioSession.outputLatency` plus the latency trim (Settings, 10 ms steps) — so the *sound*, not
  the player, lands on the anchor.
  Drift is then handled by `DriftController` in MotopartyCore, the same rule as Android's
  `SyncController` (PROTOCOL.md "Music flow" step 4, because a seek on A2DP costs a fresh
  350-700 ms of lag): the position is compared with the anchor every 10 s (2 s while a
  correction runs or just after a start), and up to 80 ms of error is left alone; 80 ms - 1 s
  is absorbed by `AVPlayer.rate = 1 - drift / 4000` clamped to 0.95...1.05 (pitch kept, the
  item's `audioTimePitchAlgorithm` is `.spectral`); above 1 s it re-seeks and the rate goes
  back to 1. A running correction is held until the error is under 40 ms, so noise around the
  80 ms boundary cannot flap the rate.
- **Protocol version:** a host whose `hello.proto` is not `Hello.currentProto` (1) is refused:
  the app drops the link, shows why, and waits for Settings → Reconnect.
- **Per connection:** a new connection resets the clock estimate (it may be another host, or a
  restarted one) and answers each track's `music.ready` at most once (every answer makes the
  host re-send the anchor, which on A2DP costs a fresh seek).
- **Track cache:** a download lands as `<id>.part.m4a` and is renamed to `<id>.m4a` only after
  AVFoundation reports it playable with a duration, so nothing plays or answers `music.ready`
  from a file that is still being checked. Leftover `.part.m4a` files are deleted at launch.
- **Buttons:** by default play/pause (AirPods single press) is talk, next (double press) is
  next track and previous (triple press) is previous track (configurable in Settings). A
  "command" action saved by an older build falls back to that button's default
  (`RemoteAction.stored`). The app only sees the remote
  commands play/pause, next and previous, so other buds (the passenger's Redmi Buds 6 Pro) work
  once their own app maps gestures to those three. iOS also sends `pause` when
  an AirPod leaves the ear, so an explicit `pause` is ignored unless enabled in Settings. While
  the mic is open, the iOS 17 AirPods mute gesture is treated as the single-press action; this
  is untested (Spike 2). `AVAudioApplication.setInputMuteStateChangeHandler` is **macOS only**
  (`API_UNAVAILABLE(ios)`): on iOS the system does the muting itself and only reports it, so
  `SessionController` observes `AVAudioApplication.inputMuteStateChangeNotification`, unmutes
  again with `setInputMuted(false)`, and ignores the unmute's own notification so one press
  stays one action.
- **Keep-alive:** a silent AVAudioEngine runs whenever talk is closed, so the locked app is
  never suspended. `UIRequiresPersistentWiFi` stops iOS from powering Wi-Fi down.

## What only a real iPhone can answer

The app has never run on a device. These are the open questions, in the order a ride needs
them:

- **Do the Redmi Buds 6 Pro gestures reach `MPRemoteCommandCenter` during a talk**, while the
  session is `.playAndRecord`/`.voiceChat` with Bluetooth HFP up? A press is the only way to
  end a talk besides saying "Moto party, over"; if HFP turns gestures into call controls (or
  into the mute gesture, as AirPods do on iOS 17) nothing here sees them. Every press is logged in
  the `app` category as `remote button: <which>, talk=<bool>` (the mute gesture included).
- **Does on-device `SFSpeechRecognizer` keep up when fed from the talk's buffers**:
  voice-processed (AGC, noise suppression), 16 kHz mono float, one request per phrase,
  restarted every few seconds for a whole ride? Look for `recognition failed … retrying` in
  the `voice` log, phrases arriving late, or the recogniser refusing a second request while
  one is being cancelled.
- **Wake-word accuracy** at speed in a helmet: how "Moto party" comes back (the
  `contextualStrings` bias it; only three spellings count), how often conversation starts
  with something that sounds like it, and whether 1.2 s of silence splits "Moto party …
  play …" into two phrases (the 5 s arming window is meant to cover that).
- Whether the `listen` / `ok` earcons, played by `AVAudioPlayer` outside the voice-processing
  engine, leak into the mic (and to the rider) during a talk.
- The rest listed above: the AirPods mute gesture (Spike 2), `LocalVolume`'s hidden slider,
  the AirPods A2DP ↔ HFP switch time.
