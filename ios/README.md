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
                           MusicAnchor/DriftController, LinkMath (sweep, liveness, TXT),
                           HostSelection (discovery: probe backoff, last-host preference), Earcons (WAV synth),
                           CommandParser (the grammar, run here too because volume is local;
                           FirstPhraseGate, the first-phrase rule and its 8 s window),
                           AppVolume + VolumeKeyGate (the app owns the volume keys while linked:
                           park, app level steps, hold volume up = talk),
                           TalkMode (own mic vs. host-mic talk, from talk.open's `mic`),
                           MusicStatus ("Loading…" / "Paused for talk" under now playing),
                           BrowseHistory (Search tab: recent searches, recently played)
Sources/Motoparty/       the iOS app (only compiled by xtool against the iOS SDK):
  Link/                    Discovery (NWBrowser + /24 sweep; see below), ControlClient, VoiceSocket
  Audio/                   SessionController (A2DP music ↔ HFP talk; host-mic talk stays A2DP),
                           VoiceEngine (duplex or receive only), KeepAlive, EarconPlayer,
                           LocalVolume (this phone's system volume), VolumeKey (outputVolume KVO,
                           park, app gains)
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

**Discovery** (PROTOCOL.md "Discovery"): a Bonjour result is only a candidate, since mDNS caches
keep dead services (e.g. `peer-test-*`) for up to 75 min. `Discovery` probes every result in
parallel (3 s connect incl. resolution, then 1 s for the host's `hello`; the probe sends
nothing) and, from 3 s after start, the /24 alongside it. A failed candidate is skipped for
10 s; Bonjour candidates are re-probed every 2 s. Only a valid host `hello` ends discovery; if
several answer within 300 ms, the last linked host (`lastHostName` in UserDefaults) wins. The
log shows `probe <name>: ok|timeout|refused|not a host` and `discovery: host <name> via
bonjour|sweep` (sweep logs only addresses that answered, plus one line per pass).

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
swift test            # 140 tests: fixtures, command parser + first-phrase gate, app volume +
                      # volume-key gate, talk mode (host-mic), music status line,
                      # search history, jitter buffer, Opus, drift controller
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
  `ios/` tries to. The passenger's triggers are the app's own buttons, the headset controls and
  holding volume up (below).
- **Screens:** three tabs. **Ride** has the link pill (host name and round trip), the
  now-playing card (cover from `state.music.art`, progress, previous / play-pause / next as
  `music.control`), the downloading line, a compact "Voice commands" list (the same as the
  Pixel's: say one first after pressing TALK, then it's just talk), TALK, and the last
  command heard / announced / problem lines; the gear opens Settings (latency trim, headset buttons, link details).
  **Search** sends `music.search` (Songs / Albums / Playlists); a song tap is
  `music.enqueue{mode:"now"}` with that track, its ⋯ menu (or a swipe) is Play next / Add to
  queue. An album or playlist opens a detail screen (`music.browse`) with Play / Add to queue;
  a track tap enqueues `now` the tracks from that one to the end. Tracks carry the collection
  title as `album` and its cover as the top-level `art`. **Queue** shows `state.queue`: tap =
  `music.edit jump`, swipe = `remove`, Clear (confirmed) = `clear`. Browsing is disabled while
  disconnected. With the search box empty, Search shows this phone's history
  (`BrowseHistory`, JSON in UserDefaults `browseHistory`): **Recent searches** (last 10 sent,
  newest first, one per query ignoring case, with its latest kind; tap re-runs it, Clear
  empties it) and **Recently played** (last 20 distinct tracks named by `state.music`, newest
  first; tap = `music.enqueue{mode:"now"}` with that track).
- **Play by touch during a talk** (PROTOCOL.md "Browsing" step 3, 2026-09-30): the client
  sends `music.enqueue{now}` and `music.edit{jump}` during a talk like at any other time. The
  host ends the talk and sends `talk.close`; the client closes nothing itself.
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
- **Host-mic talk** (PROTOCOL.md "Host-mic talk", 2026-09-29). When the host's
  `talk.open` carries `mic:"host"` (its Hollyland Lark A1 receiver captures both riders), the
  iPhone opens **no microphone**: `TalkMode.hostMic` (MotopartyCore) decides it once at the
  open and it stays fixed until the talk closes. The app pauses music, activates
  `SessionController`'s `.listen` route and starts `VoiceEngine.startReceiveOnly()`: the same
  jitter buffer → Opus → source → `AUPeakLimiter` → mixer playback with the same app volume
  (+3 dB boost at the top level), and nothing else. The engine never names `inputNode` (that
  alone creates the input unit), so there is no voice processing, no capture, no `mic trace`,
  no recogniser, no record-permission check and no audio sent; the voice socket's 1 s
  keepalives go on by themselves. `command.text` is never sent: the host recognises the
  passenger's first phrase on its right channel. The live earcon fires on the playback
  source's first render (`live cue: fired … (playback up)`), with the same 3.5 s fallback.
  `talk.close{by:"client",reason:"unavailable"}` is sent only if the session or the engine
  will not start, or the engine fails mid-talk (restart cap, interruption, media reset), never
  over the mic permission.
  - **Session: `.listen` is exactly the media configuration, `.playback` / `.default`, no
    options.** Chosen over `.playAndRecord` + `.allowBluetoothA2DP`: that is a different
    category, so activating it is a real session switch (a route change, possibly a different
    volume, and the built-in receiver as the default output unless `.defaultToSpeaker` is
    added), and it would make an input available for nothing. With the category unchanged,
    activating `.listen` is a no-op for the route: the Redmi Buds (or AirPods) stay on A2DP,
    there is no profile switch either way, `outputVolume` KVO keeps reading the same A2DP volume
    (so the volume-key park and hold go on as during music; `settle()` is still called at open
    and close, it is cheap), and with no headset `.playback` plays on the speaker, never the
    receiver.
  - **Restarts.** Receive only has no voice processing to echo and no input to lose, so a
    configuration change with the engine still running is always ignored (the mixer converts to
    whatever the output became). A stopped engine (a headset leaving or arriving) is restarted in
    place under the same backoff and the same cap of 6 in 10 s; if the output format changed, the
    mixer → output link is reconnected first. The failed-restart rebuild keeps the mode.
  - A talk learnt only from `state{talk:true}` (a join mid-talk) takes its mode from `state`'s
    `mic`, which the host repeats while a host-mic talk is open (`TalkMode(state:)`); without it
    the talk is an own-mic one.
  - Logs: `talk mode: host-mic (receive only)` or `talk mode: own mic` at every open; the
    `session → listen|talk|media, out: … (category/mode, out <type/name>, in <type/name>)` line;
    `voice engine started: receive only (no input), out <Hz> × <ch>`; `voice rx: first packet`;
    and `talk stats: receive only|duplex, …` with the usual rx numbers (tx stays 0).
- **No headset → loudspeaker.** The talk category has `.defaultToSpeaker` besides
  `.allowBluetoothHFP`, and if the output is still the built-in receiver after activation (or
  after a headset drops mid-talk) `SessionController` overrides it to the speaker
  (`talk output was the receiver; overridden to the speaker`). A Bluetooth HFP or wired
  headset still wins; voice processing cancels the speaker's echo. The first device run played
  talk on the earpiece (`out: מקלט`), far too quiet.
- **Configuration changes restart the same engine** (first device run, iPhone 15 / iOS 26:
  83 restarts a minute and no capture at all). Enabling voice processing reconfigures the I/O
  and posts `AVAudioEngineConfigurationChange`; the old handler rebuilt the engine, which
  toggled voice processing off and on, which posted the change again, every ~0.7 s. Now
  `VoiceEngine` builds a fresh engine only at `start` (a new talk). A change that arrives
  within 1 s of our own start while the engine still runs, or with the engine running and the
  input format unchanged, is ignored (`… still running: ignored`). Otherwise the stopped
  engine is `prepare()`d and started again in place, keeping voice processing, the graph, the
  jitter buffer and the decoder; only if the input format really changed is the capture sink
  reconnected with a new converter (`restart #n (k in 10 s) … input format changed`).
  Restarts back off (0, 100, 200, 400 ms … 2 s) and at most 6 in 10 s are allowed; one more
  gives up through `onFailure` (the usual "cannot talk" path). If an in-place restart throws,
  a full rebuild is tried once per talk.
- **The "live" earcon fires on a real signal, never on a delay** (Android's F7/F8/F9a rule,
  `LiveCue`). `VoiceEngine.onLive` is called on the main queue from the first buffer the
  capture sink delivers (in a host-mic talk: the playback source's first render) — exactly
  once per start, re-armed by the next one, and it never
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
- **Commands inside talk** (option A, 2026-09-29; PROTOCOL.md "Commands", The first phrase
  decides). There is no command mode, no MUSIC button and no wake word: one press toggles talk,
  and the phone that opened the talk may speak one command as its first phrase. The iPhone is
  the client, so it is never solo; it recognises only in a talk it opened (the host's
  `talk.open{by:"client"}`; a talk learnt only from `state`, e.g. a join mid-talk, counts as
  not ours), and in a talk the host opened it does not recognise at all. Recognition starts
  when the "live" earcon plays (on capture up or on the fallback), on the mic the talk already
  has. `VoiceEngine` tees every 16 kHz capture buffer, on the capture queue and before the
  Opus encoder (so DTX never cuts it), to `Transcriber.append`, which only counts and hops to
  its own queue (at most 50 buffers waiting; past that they are dropped), so the capture path
  never waits for the recogniser. The recogniser is on-device when the language has a model,
  and runs one `SFSpeechAudioBufferRecognitionRequest` per phrase: a final result, or 1.2 s
  without new partial text, ends the request, hands its text to `AppModel`, and the next
  request starts at once. Errors (typically "no speech detected" after a quiet stretch) are
  logged in the `voice` category and the chain restarts, backing off 1, 2, 4 … 10 s while it
  keeps failing at once; the talk itself is never touched, and a missing recogniser or speech
  authorisation only means this talk has no command. `AppModel` passes each phrase through
  `FirstPhraseGate` (MotopartyCore, pure, times passed in; `fixtures/first_phrase.json`),
  started at the live earcon: the first phrase that is not empty after normalisation and
  arrives within `FIRST_PHRASE_MS` = 8000 ms (inclusive) is a command if it parses, and
  otherwise conversation, with no "Didn't catch that"; every later phrase is conversation,
  never sent or acted on. As soon as the gate is spent (that first phrase, or an 8 s timer
  from the earcon) the transcriber is stopped for the rest of the talk, which is logged as
  `first phrase spent: recognition off for this talk`. Every phrase is logged as
  `heard: "<text>" (command|conversation)`. A command's text (normalised, fillers kept: the
  parser drops them) goes through `CommandParser`: volume is handled here (below); everything
  else, `end` ("over", "end talk", "hang up"), `nowplaying` ("what's playing") and `shuffle`
  (2026-09-30, answered with `announce`, talk stays open) included, is sent as
  `command.text{text, lang}` and the host decides what it does to the talk (`play`, `resume`
  and `end` close it with the usual `talk.close`). The host also enforces the rule: it acts
  only on the first `command.text` of a talk the client opened.
- **Volume is local** (PROTOCOL.md "Commands"): `MotopartyCore.CommandParser` runs on this
  phone's own command before anything is sent, and `volume up`/`louder`/`volume down`/
  `quieter` change *this* phone's volume with the `ok` earcon and no `command.text`. Everything
  else goes to the host unchanged. `music.control` has no volume actions any more (one on the
  wire is a malformed message and is dropped). While the link is up the command is one app
  volume level (below), like a key press; while it is down it is one system step (1/16), as
  before. iOS has no public system-volume setter, so `LocalVolume` writes the hidden `UISlider`
  of an off-screen `MPVolumeView` — the real system / AirPods volume, reading the current level
  from `AVAudioSession.outputVolume`. It is the only place that trick lives. The view is made
  at app start (next run-loop turn after `start()`), off-screen, 120×40, alpha 0.0001 (not
  hidden), and the slider is found by a recursive subview search (on iOS 26 it is not a direct
  subview; the first device run failed the park with `no MPVolumeView slider`). A `set` before
  the slider exists keeps the value and retries on the next run-loop turn, then up to 8 × 100 ms
  (`local volume: set <v> on retry <n>` / `… after <n> tries`). `local volume: slider ready`
  is logged once.
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
- **Buttons:** no headset button starts or ends a talk (2026-09-29: the earbuds sit inside the
  helmet). Talk is the Ride tab's TALK button, or a hold of volume up (below). Remote commands (lock screen, Control Center, a
  headset) control the music only: play/pause, next, previous; `pause` alone is ignored, since
  buds send it when they leave the ear. While the mic is open the iOS 17 AirPods mute gesture
  would mute it: `SessionController` observes
  `AVAudioApplication.inputMuteStateChangeNotification` and unmutes again with
  `setInputMuted(false)` (`setInputMuteStateChangeHandler` is macOS only), and the gesture does
  nothing else.
- **The app owns the volume** (2026-09-29: the passenger's iPhone rides locked in a jacket
  pocket, and its volume keys are the passenger's only trigger). While the link is up:
  - **Park.** The system volume is parked at 15/16 (`VolumeKeyGate.parkVolume`, one step below
    max) through `LocalVolume`'s slider, and every key reading is put straight back there, so
    both keys always move it (at 16/16 an up press would make no reading). iOS reports no key
    presses, so `VolumeKey` observes `AVAudioSession.outputVolume` by KVO — the session is
    always active here (KeepAlive between talks, the voice engine during one) — and hands every
    reading to `VolumeKeyGate` (MotopartyCore, pure, times passed in). A reading at the park
    within `ownChangeMs` = 1000 ms of asking for it is the gate's own reset and is ignored (a
    key always moves the volume *off* the park). "At the park" is within 0.02, not the 0.01
    of other comparisons: in an HFP talk the park reads back as 0.95 (device log
    2026-09-29). In that window, a reading between the previous one and the park is the
    park in transit, not a key (arming at 0.2 once read a phantom up). Keys are read against the latest reading, not
    the park, so a repeat that beats the reset still counts.
  - **App gain.** Loudness is one app level, 0...16 (`AppVolume`): 15 is 0 dB (the parked
    system volume as it is), each level below is `stepDb` = 3 dB quieter (1 = -42 dB), 0 is
    silence, and 16 is +3 dB, winning back the system step the park gives up. Talk playback
    runs source → Apple's peak limiter (`AUPeakLimiter` as an `AVAudioUnitEffect`) → main mixer:
    below unity the level is the mixer's `outputVolume`, above it the limiter's pre-gain
    (+3 dB), so the boost cannot clip. Music (`AVPlayer.volume`) and earcons / announcements
    (`AVAudioPlayer.volume`, `AVSpeechUtterance.volume`) cannot go above 1.0; boosting music
    would need an `MTAudioProcessingTap` on the player item plus a limiter, which is not cheap
    or safe enough, so **music and cues stop at unity: level 16 plays them like level 15**.
    The announcer's music duck (0.35) multiplies the music level.
  - **Single presses** of up or down are one app level each, applied at once. Holding volume
    down just keeps stepping down.
  - **Hold volume up = talk.** A held key repeats: the press, iOS's initial repeat delay, then
    fast regular repeats. A burst of `HOLD_STEPS` = 4 up readings whose first gap is at most
    `FIRST_REPEAT_GAP_MS` = 700 ms and whose later gaps are each at most `REPEAT_GAP_MS` =
    200 ms is a hold: it toggles talk once through the same `talkButton()` as TALK
    (`talk.open{by:"client"}` / `talk.close{reason:"trigger"}`), the steps the burst made are
    taken back (app gain only, so no HUD), and the rest of the hold is absorbed until the key
    has been quiet for more than `REPEAT_GAP_MS`. So a hold has no net volume change, though
    its first three steps are heard for a moment. Three taps can never be a hold, and four
    count only if the last three come within 200 ms of each other, which fingers rarely do. A
    down press ends an up burst.
  - **Re-park.** Every route change and every session switch (talk opening or closing: HFP and
    A2DP keep separate system volumes, so the volume jumps) calls `settle()`: for
    `settleMs` = 1500 ms readings are not keys, the volume is parked at once, again at every
    reading in the window, and once more when it ends. A hold that already toggled stays
    absorbed across the window (talk opens while the key is still held).
  - **Unlinked:** the keys are the plain system volume and all app gains are unity. Arming
    starts at the **remembered** app level (`AppSettings.appVolumeLevel`, UserDefaults; first
    ever `AppVolume.defaultLevel` = 12, -9 dB), never at one derived from the system volume:
    that once armed a passenger at level 3 (-36 dB, from a 20% system volume) while the parked
    system volume read 94%, and the talk was near inaudible (device log 2026-09-29). Every
    key, hold revert and spoken volume command saves the level. Disarming sets the system
    volume to `level / 16` (16 = max).
  - **Shown on RideView:** a speaker, a 16-step bar and `12/16`, always visible (dimmed while
    unlinked: the level the next link starts at). While linked the system volume always
    reads 15/16, so this is the only true volume on the phone. The voice engine logs the
    talk gain in `voice engine started` and `talk stats` (`app gain -9 dB (mixer 0.355),
    boost 0 dB`); `peak out` is measured before it.

  The `audio` log shows `volume key: up +<gap> ms (<n>/4) → level <l>` for every up press
  (`first` for a burst's first; `hold`, `absorbed` instead of the level), so the real repeat
  timing can be read off and the three constants tuned, and `volume key: down → level <l>`,
  `volume key: talk toggle, level back to <l>`, `volume key: settling, <v> is no key`,
  `volume key: armed …` / `disarmed …` and `volume key: command up|down → level <l>`. The
  Ride tab says "Hold volume up: talk" under TALK while connected.
- **Keep-alive:** a silent AVAudioEngine runs whenever talk is closed, so the locked app is
  never suspended. `UIRequiresPersistentWiFi` stops iOS from powering Wi-Fi down.

## What only a real iPhone can answer

The app has never run on a device. These are the open questions, in the order a ride needs
them:

- **Does on-device `SFSpeechRecognizer` keep up when fed from the talk's buffers**:
  voice-processed (AGC, noise suppression), 16 kHz mono float, one request per phrase,
  restarted every few seconds for a whole ride? Look for `recognition failed … retrying` in
  the `voice` log, phrases arriving late, or the recogniser refusing a second request while
  one is being cancelled.
- **The passenger's first-phrase command** in a talk the iPhone opened: whether the
  recogniser, started at the live earcon, is ready in time to catch the first words (nothing
  before its first request is heard), whether a command like "play Dark Side of the Moon"
  arrives as one phrase within the 8 s window (a pause of 1.2 s splits it, and the half that
  arrives first is spent as conversation), how often a first "can you hear me" is mistaken for
  a command, and whether stopping the transcriber mid-talk leaves the voice path untouched.
- Whether the `live` / `ok` earcons, played by `AVAudioPlayer` outside the voice-processing
  engine, leak into the mic (and so into the first phrase) during a talk.
- **The app owns the volume** (none of it has run on a phone):
  - Does the `outputVolume` KVO fire with the phone locked in a pocket, during music and during
    a talk (HFP call volume)?
  - Does the slider park work in the background with the screen locked?
  - Does the system volume HUD flash for each park (on the lock screen, over the Ride tab)?
  - Does the re-park after the HFP ↔ A2DP switch work, and does the jump land inside the
    1.5 s settle window (look for a `volume key: up|down` line right after `session →`)?
  - Does an up press at 15/16 always register? At 16/16 a press makes no reading, so each
    reset must land before the next key repeat; a hold whose repeats are lost shows gaps of
    twice the repeat interval and never toggles.
  - The real key-repeat timing, from the `volume key: up +<gap> ms` lines: the initial delay
    (under 700 ms?) and the repeat interval (under 200 ms?); how long a hold takes to toggle,
    and whether fast human taps ever make one.
  - How loud the +3 dB talk boost is behind the limiter, whether the limiter pumps, and whether
    arming at the matching level really keeps loudness steady (the iOS volume curve is not
    exactly 3 dB a step).
- **First-device-run fixes (2026-09-29), to confirm:**
  - During a talk, `voice engine started` is followed by at most one or two
    `configuration changed … ignored` / `restart #1` lines, not a line every 0.7 s;
    `live cue: fired … (capture up)` rather than `(fallback)`; the Pixel receives voice.
  - `local volume: slider ready` shortly after launch, and no `park failed` after
    `volume key: armed …`.
  - With no earbuds connected, `session → talk, out:` names the speaker (רמקול / Speaker),
    not the receiver (מקלט); with AirPods connected it still names the AirPods.
  - In talk the log read the system volume 1.0, then 0.95, while settling. 0.95 is outside the
    park's 0.01 tolerance around 15/16 = 0.9375: if parks during HFP read back as 0.95 (the call
    volume may be quantised differently), every park would look like a key after the settle
    window. Look for `volume key: down|up` lines nobody pressed during a talk.
- **Host-mic talk, home test** (Pixel with the Lark A1 receiver in Stereo mode, the host
  setting on, Redmi Buds 6 Pro on the iPhone, music playing): open a talk from the iPhone
  (TALK, then a hold of volume up) and from the Pixel. Look for `talk mode: host-mic (receive
  only)`, a `session → listen` line still naming the buds with `out BluetoothA2DPOutput/…` and
  `in none`, no `route change` line at the open or close, `voice engine started: receive only`,
  `live cue: fired … (playback up)`, `voice rx: first packet`, and at the close `talk stats:
  receive only, tx 0 sent …, rx <n> received` with `in none`. By ear: music pauses, the rider's
  lavalier is heard in the buds at full A2DP quality with no profile-switch gap, the passenger
  is not heard back, and music resumes after the close. Also: the orange mic dot never
  appears; a volume press during the talk is one app level (`volume key: up … → level`), and a
  hold of volume up closes it; "play …" as the passenger's first phrase in a talk the iPhone
  opened works (the host recognised it) and no `heard:` line appears on the iPhone. Then
  switch the host setting off and check the next talk logs `talk mode: own mic` and goes to HFP
  as before. With no buds, the host-mic talk plays on the speaker (`out Speaker/…`).
- **2026-09-30 additions:** "what's playing" and "shuffle" as the first phrase are recognised
  and the host's `announce` is spoken in the talk; tapping a song (or a recently played track)
  during a talk closes the talk (end earcon, back to media mode) and the song starts; the
  Ride screen with the command list still fits without scrolling on the real iPhone; history
  survives an app restart.
- The rest listed above: the AirPods mute gesture (Spike 2), `LocalVolume`'s hidden slider,
  the AirPods A2DP ↔ HFP switch time.
