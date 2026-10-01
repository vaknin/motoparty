# Motoparty iOS client

The iPhone (passenger) half of Motoparty: a SwiftPM package built, signed and installed from
Linux with [xtool](https://github.com/xtool-org/xtool), with no Mac and no paid developer account.
The wire protocol is [`../PROTOCOL.md`](../PROTOCOL.md). The shared test vectors are in
[`../fixtures`](../fixtures).

```
Package.swift            two automatic library products (xtool convention): "Motoparty" = the app,
                         "MotopartyWidgets" = its widget extension (the Live Activity)
xtool.yml, Info.plist    bundle ID + Info.plist keys merged by xtool (background audio, mic,
                         local network/Bonjour, speech, ATS local networking, persistent Wi-Fi,
                         NSSupportsLiveActivities); `extensions:` names the widget extension
MotopartyWidgets-Info.plist  the extension's Info.plist keys (com.apple.widgetkit-extension)
Sources/COpus/           libopus 1.5.2, portable float build (vendored; see below)
Sources/MotopartyCore/   pure Swift + Foundation, tested on Linux:
                           Messages (all control messages, Codable), Framing (u32, 64 KiB cap),
                           ClockSync, VoicePacket/VoiceSequencer, JitterBuffer, Opus wrapper,
                           MusicAnchor/DriftController, LinkMath (sweep, liveness, TXT),
                           HostSelection (discovery: probe backoff, last-address probe, last-host
                           preference), Earcons (WAV synth),
                           Gapless (GaplessTracker for `music.next`, OutputDelay, TalkPress,
                           VolumeKeyArming),
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
Sources/MotopartyActivity/ `MotopartyActivityAttributes` (ActivityKit), linked by the app and the extension
Sources/MotopartyWidgets/  the widget extension: the Live Activity's lock-screen and Dynamic Island views
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
10 s, or 1 s when the connection was refused (a host that is restarting is not listening yet);
Bonjour candidates are re-probed every 2 s. After a link loss discovery starts at once (it
waited 1 s until 2026-09-30; it still does when the link never held for a second) and also
probes the address of the lost link, now and every second, whatever the backoff says
(`probe last address <ip>: ok`, `discovery: host <name> via last`). Only a valid host `hello` ends discovery; if
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
swift test            # 268 tests: fixtures, command parser + first-phrase gate, app volume +
                      # volume-key gate, talk mode (host-mic), music status line,
                      # search history, jitter buffer, Opus, drift controller, the
                      # screens' wording, ambient tint and per-route sync offset
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
   App IDs per week. Since 2026-09-30 the bundle holds a widget extension
   (`PlugIns/MotopartyWidgets.appex`, for the Live Activity): xtool registers a second App ID
   and profile for it (`…motoparty.MotopartyWidgets`), so an install uses 2 of the 10 App IDs,
   and, as far as known (AltStore's description of the limit; not tried here), 2 of the 3
   app slots, because iOS counts extensions. If the install is refused for that, delete the
   `extensions:` block in `xtool.yml`: the app runs the same without the Live Activity.

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
- **Screens:** three tabs. **Ride** has the link pill (the Pixel's name, or what the link is
  doing), a permissions card and a dismissible problem line when there is one, the
  now-playing card (cover from `state.music.art`, progress, previous / play-pause / next as
  `music.control`, the repeat button at the right end (off → queue → track, sent as
  `music.control{action:"repeat", mode}` and shown from `state.music.repeat`; 2026-10-01),
  "Up next", and one status line under it: "Downloading song…", "Paused for talk", or, with a
  spinner and also with nothing loaded, the host's voice search from `state.busy`
  (`Searching song "moby"…`, 2026-10-01)), the "Voice commands" chips (the same commands as the
  Pixel's, foldable: say one first after pressing TALK, then it's just talk; while the host
  interprets, `hello.interpret`, they are followed by eight smart-command examples such as
  "repeat this song", "go back 30 seconds", "undo": `VoiceCommandChip.smart`, the same words
  as Android's `SMART_COMMANDS`), and, pinned at the bottom, TALK with the app volume. While a
  phrase of an open talk can still be a command (the first-phrase window, or the reply to the
  host's question: `AppModel.commandWindow`), a "Say a command" card with the list takes the
  place of the now-playing card, as on the Pixel. Behind it all, the cover's colour as a faint
  gradient (`Ambient` in MotopartyCore, Android's `Ambient.kt` rule: the first coloured swatch,
  saturation capped, lightness banded, darkened until text keeps its contrast; read once per
  cover from a 24 px copy); the blurred cover stays the card's own backdrop. The gear opens
  Settings: name, speech language (Android's 14), "Keep screen on while riding" (while Ride
  shows; off by default), "Beep when the mic is live" (on by default, unlike Android: iOS mutes haptics while recording), the
  music sync offset of the output in use now (below), link status, version, Diagnostics. See
  "2026-09-30 audit round 3" below.
  **Search** sends `music.search` (Songs / Albums / Playlists); a song tap is
  `music.enqueue{mode:"now"}` with that track, its ⋯ menu (or a swipe) is Play next / Add to
  queue. An album or playlist opens a detail screen (`music.browse`) with Play / Add to queue;
  a track tap enqueues `now` the tracks from that one to the end. Tracks carry the collection
  title as `album` and its cover as the top-level `art`. Under Play / Add to queue, **Download**
  (2026-10-01, PROTOCOL.md "Browsing" step 6) sends `music.download{op:"start", ref, ids}` and
  reads as on the Pixel from the host's `music.downloads`: "Downloading 5/14 · Stop" with a
  spinner (a tap sends `stop`), "Retry · 12/14 saved", "Downloaded". Song rows (search results
  and collections) show the Pixel's "downloaded" mark (a tinted arrow-down circle before the
  subtitle) for ids in `music.downloads.cached` (`HostDownloads`). A Play next / Add to queue by touch
  says what it did in a toast above the mini player for 4 s, with Android's words ("Playing
  next: X", "Added to queue: X", "Added N songs"; nothing while nothing is loaded, since the
  host then plays it at once): `Toast` in MotopartyCore, fed from `AppModel.enqueue`, drawn
  with the Queue tab's banner. Once the box is edited after a search, "Results for “x”" heads
  the results. **Queue** shows `state.queue` under the current song (artist · album, as on
  the Pixel): tap =
  `music.edit jump`, swipe or ✕ = `remove`, drag = `move`, Clear (confirmed) = `clear`. Every
  edit shows at once (`QueueEdits`). A removal or a clear brings a banner with Undo for 10 s,
  as on Android: Undo of a removal is `music.enqueue{end}` plus a `move` back to its index (the
  protocol has no insert), Undo of a clear re-enqueues the songs at the end. Browsing is disabled while
  disconnected. With the search box empty, Search shows this phone's history
  (`BrowseHistory`, JSON in UserDefaults `browseHistory`): **Recent searches** (last 10 sent,
  newest first, one per query ignoring case, with its latest kind; tap re-runs it, Clear
  empties it) and **Recently played** (last 20 distinct tracks named by `state.music`, newest
  first; tap = `music.enqueue{mode:"now"}` with that track; its ⋯ menu or a swipe is Play
  next / Add to queue, as for a song result). The mini player on Search and Queue has a 2 pt
  progress line along its top (in the iOS 26 accessory, along its bottom), from
  `displayPositionMs()`, as the Pixel's has.
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
  A second TALK press (or volume-up hold) before the host decided sends
  `talk.close{by:client, reason:trigger}` and the button is TALK again; a request unanswered
  for 5 s is dropped, with the error earcon (2026-09-30, audit P7, `MotopartyCore.TalkPress`).
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
  so a talk whose capture never delivers still beeps. The beep itself only sounds with
  Settings → "Beep when the mic is live" on (on by default; iOS mutes haptics while recording);
  the moment, TALK turning red and recognition starting, is the same either way. Which one fired is logged as
  `live cue: fired +<n> ms (capture up|fallback)` in the `audio` category. A talk that ends
  before the mic was live never beeps. Android's SCO/`MicLive` conditions are deliberately
  **not** ported: iOS gives no equivalent route signal, and the sink's first buffer is the
  honest one this platform has.
- **Jitter buffer (2026-09-30, audit L1/L2).** `MotopartyCore/JitterBuffer.swift` is now the
  same algorithm as Android's `core/JitterBuffer.kt`, and `fixtures/jitter.json` (17 cases,
  `JitterFixtureTests`) pins both. New against the old Swift buffer:
  - *Shedding* (PROTOCOL.md "Shedding a backlog"): at spurt start the oldest frames go until
    the queue spans the target; during a spurt the smallest depth over 50 frames due decides
    (`> target + 120 ms` → the whole excess, `> target + 40 ms` → one frame). A playout stall
    or a slow output clock no longer stays as delay until the talk ends. The 400 ms hard cap
    (`maxTargetMs + backlogSlackMs`) stays and counts as shed too. Shed frames move `lastSeq` /
    `nextTs` as if played: no loss, no underrun, no FEC. `stats.shed` counts them;
    `stats.dropped` is only the 100-packet insert cap now.
  - *A late first packet of a new spurt* is not an underrun (nothing queued, `ts` ≥ 2 frames
    past the last played frame, only keepalives or too-late packets between).
  - Ported with it, because the vectors cover them: the re-anchor after 100 ms of late packets
    with nothing played, the restart on a `ts` more than 3 s from the playout clock (this
    replaces the old "idle after 2 s of nothing" rule), and half a frame of `ts` tolerance.
  - `stats.late` is every packet that arrived after its slot (the vectors' `underruns`);
    `stats.underruns` stays the number of target raises (one per spurt at most).
  - `talk stats:` now also logs `<n> shed` and `depth mean <ms> max <ms>`.
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
  parser drops them) goes through `ClientCommand.route` (MotopartyCore, on top of
  `CommandParser`): volume is handled here (below); everything else, `end` ("over", "end
  talk", "hang up"), `nowplaying` ("what's playing") and `shuffle` included, is sent as
  `command.text{text, lang}`. The host also enforces the rule: it acts only on the first
  `command.text` of a talk the client opened.
- **Every command ends the talk** (2026-09-30, PROTOCOL.md "Effect on the talk"). For a
  `command.text` the host closes it with the usual `talk.close` as soon as the command parses,
  also when it then fails; nothing changed here for that (`closeTalkLocally`: end earcon on
  the talk route, media route 0.22 s later, music on the host's `music.play`). A volume
  command is never sent, so this phone ends that talk itself with the `talk.close{by:"client",
  reason:"trigger"}` a TALK press sends (`requestTalkClose`), and tears down when the host's
  `talk.close` comes back, like any close. Normally (keys armed) the app level steps at once
  and the `ok` earcon plays on the talk route, which is up; the close is asked for
  `okCueHold` = 0.12 s later, so the end earcon follows the `ok` instead of covering it (a
  talk that closed in between cancels it). The Ride hint says "it is done and the talk ends".
- **Spoken replies only when there is nothing else to hear** (2026-09-30). The host no longer
  sends `announce` for a successful `play`/`resume`/`next`/`previous`/`pause` (no "Playing …").
  Nothing here waited for one: `announce` only sets the Ride line (gone after 6 s by its own
  timer), plays its earcon and speaks; "Heard: …" expires by its own timer too; the music
  status line and the lock screen follow `music.*` and `state`. Failures, `nowplaying` and
  `shuffle` still arrive as `announce`, after the host's `talk.close`, and are spoken on the
  media session (they may start in the A2DP switch gap: device check below).
- **Volume is local** (PROTOCOL.md "Commands"): `MotopartyCore.CommandParser` runs on this
  phone's own command before anything is sent, and `volume up`/`louder`/`volume down`/
  `quieter` change *this* phone's **media** volume with the `ok` earcon and no `command.text`,
  and the talk is then closed from here (above). While the link is up that is the app level,
  the one loudness of music, talk and cues, so it is changed during the talk. Should the keys
  be disarmed during a talk (the arming gap after a reconnect), the system volume is the
  volume and in a talk that is the HFP call volume, so the step and its earcon wait for the
  media route (`ClientCommand.VolumeTiming.afterMediaRoute`, applied in `restoreMediaRoute`).
  Everything else goes to the host unchanged. `music.control` has no volume actions any more (one on the
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
  `AVAudioSession.outputLatency` plus the latency trim of the current output (Settings, 10 ms
  steps; `LatencyTrims` since 2026-10-01, as on Android: one per Bluetooth/AirPlay device, keyed
  by its address so A2DP and HFP are one route, and one shared by the speaker, wired and USB
  outputs; the old single trim became the wireless default) — so the *sound*, not
  the player, lands on the anchor. An anchor further ahead (the host's lead is now its own start
  delay, up to ~1.5 s) starts exactly at it: nothing is clamped or skipped, and the drift check
  waits until the start has passed.
  Drift is then handled by `DriftController` in MotopartyCore, the same rule as Android's
  `SyncController` (PROTOCOL.md "Music flow" step 4, because a seek on A2DP costs a fresh
  350-700 ms of lag): the position is compared with the anchor every 10 s (2 s while a
  correction runs or just after a start), and up to 80 ms of error is left alone; 80 ms - 1 s
  is absorbed by `AVPlayer.rate = 1 - drift / 10000` clamped to 0.98...1.02 (pitch kept, the
  item's `audioTimePitchAlgorithm` is `.spectral`); above 1 s it re-seeks and the rate goes
  back to 1. A running correction is held until the error is under 40 ms, so noise around the
  80 ms boundary cannot flap the rate.
- **Output latency (2026-09-30, audit M2):** AVPlayer's item timeline may already be the heard
  one, which would count `outputLatency` twice. Settings → "Compensate output latency" (on = the
  behaviour so far) switches the term off at runtime (trim only); a playing track re-syncs. For
  the click-track session the log has, at every music start, `start <id> at <pos> ms in <lead>
  ms; outputLatency <x> ms (counted|not counted), trim, ahead by, route`, and after every switch
  back to media (each talk close) `output delay at the switch to media|+1.0 s|+2.5 s|+5.0 s: …,
  target <pos>, route`. Right after a talk the session can still report the HFP route's latency:
  at those three re-reads and at every route change the player compares the delay it planned
  with and plans the start again (still ahead), or restarts once when it is already playing more
  than 80 ms off (`output delay <a> → <b> ms: …`), instead of 10-20 s of rate correction.
- **Gapless (2026-09-30, audit M5, PROTOCOL.md "Music flow" step 6):** `SyncedPlayer` is an
  `AVQueuePlayer`. On `music.next{id, atHostTimeMs}`, with the track cached and the current one
  playing (or starting), the item is queued behind the current one; the player changes over on
  its own sample clock, and from then on `{id, 0, atHostTimeMs}` is the anchor
  (`MotopartyCore.GaplessTracker`). A queue was chosen over a second player started with
  `setRate(_:time:atHostTime:)`: the second player would land exactly on the new anchor and turn
  whatever sync error the phone has (under 80 ms) into a gap or an overlap at every change, the
  queue carries the error across silently and the drift rule goes on correcting it. The host's
  `music.play{id, 0, sameAnchor}` and `state` at the change do not seek or restart, whether
  they come after the local change or before it (if the change then does not happen within
  1.5 s, the track is started the usual way). The queued track is dropped by pause, stop, a
  talk, any local suspend, a `music.play` that moves the current track's timeline or names
  another track, and replaced by a `music.next` naming another track. A `music.play` / `state`
  that only repeats the current timeline keeps it: the current track still ends when it said.
  Anything not ready (not cached, nothing playing) is ignored and the track starts on its
  `music.play`, with a gap, as before. Log: `gapless: <id> queued…`, `gapless: now <id>,
  changed <n> ms early`.
- **The "end" earcon (2026-09-30, audit M10):** after an own-mic talk it plays on the talk route,
  which is up, and the session goes back to media 0.22 s later; played after the switch it fell
  into the ~1 s the buds take to bring A2DP back. (Waiting for A2DP instead, with Android's
  pre-roll, would put the cue on the resuming music.) It costs 0.22 s of the host's 1.5 s resume
  lead and moves no anchor; a `music.play` that arrives meanwhile starts once the media session
  is back. A host-mic talk never left the media session, so nothing changes there; after an
  interruption or a media reset there is no cue and no wait.
- **Media-services reset (2026-09-30, audit M11):** every player is thrown away and made again
  (`SyncedPlayer`'s AVQueuePlayer, the cached earcon players, the speech synthesizer, the
  keep-alive engine; the voice engine builds a new AVAudioEngine at every talk anyway), then the
  host's last anchor is played again.
- **Traffic classes (2026-09-30, audit P3):** the control connection is `serviceClass =
  .signaling`, track downloads are `networkServiceType = .background` (voice was already
  `.interactiveVoice`).
- **Protocol version:** a host whose `hello.proto` is not `Hello.currentProto` (1) is refused:
  the app drops the link, shows why, and waits for Settings → Reconnect.
- **Clock (2026-09-30, audit P5):** the window is cleared only when a sample is off by more than
  `500 + rtt / 2` ms (its own RTT), so one slow pong no longer throws a good estimate away
  (`fixtures/clock.json` `stepReset`). A reconnect to the same host (by `hello` name) keeps the
  window; another host resets it.
- **Per connection:** a new connection answers each track's `music.ready` at most once (every answer makes the
  host re-send the anchor, which on A2DP costs a fresh seek).
- **Track cache:** a download lands as `<id>.part.m4a` and is renamed to `<id>.m4a` only after
  AVFoundation reports it playable with a duration, so nothing plays or answers `music.ready`
  from a file that is still being checked. Leftover `.part.m4a` files are deleted at launch.
- **Buttons:** no headset button starts or ends a talk (2026-09-29: the earbuds sit inside the
  helmet). Talk is the Ride tab's TALK button, or a hold of volume up (below). Remote commands (lock screen, Control Center, a
  headset) control the music only (user decision U-D1, 2026-09-30): `pause` sends
  `music.control pause`, `play` only ever resumes, the toggle toggles, plus next and previous.
  Accepted cost: a bud that sends `pause` when it leaves the ear pauses the ride's music; one
  put back in while music plays changes nothing. While the mic is open the iOS 17 AirPods mute gesture
  would mute it: `SessionController` observes
  `AVAudioApplication.inputMuteStateChangeNotification` and unmutes again with
  `setInputMuted(false)` (`setInputMuteStateChangeHandler` is macOS only), and the gesture does
  nothing else.
- **The app owns the volume** (2026-09-29: the passenger's iPhone rides locked in a jacket
  pocket, and its volume keys are the passenger's only trigger). While the link is up, and for
  10 s of searching / connecting after it drops, so a hotspot hiccup does not disarm and re-arm
  the keys with two volume jumps (2026-09-30, audit H9, `VolumeKeyArming`; a hold in that gap
  gets the error earcon):
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

The app ran on the iPhone on 2026-09-29/30 (link, buds routing, passenger→rider voice, music
sync; see `HANDOFF.md`), but nothing built after 2026-09-30 14:30 has been on the phone. These are
the open questions, in the order a ride needs them:

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
- **2026-09-30 audit round (built offline, none of it run on the iPhone yet):**
  - *R1* `KeepAlive.start()` asks the engine, not its own flag, and `interruptionBegan` stops
    it. Check: lock the phone with music paused, take a call (or hold Siri), end it, wait a
    minute locked: the link is still up (Pixel shows the client) and a volume-up hold opens talk.
  - *R8* Headset gone → `music: held, the headset is gone`; this phone stays silent through the
    host's next `state` / `music.play`, the Ride button shows Play and a line says why. A
    headset back → `music: headset back, hold ended` and the music rejoins the host's position.
    Play on this phone (Ride button, lock screen, a tapped song, a queue jump) ends the hold and
    plays on the speaker; music the host is already playing is not touched. Check both,
    and that ending a talk (HFP → A2DP) never leaves the hold set (it clears itself within 1 s
    if a route change was missed).
  - *U-D1* Lock screen / Control Center: Pause pauses both phones, Play resumes, Play while
    playing does nothing; take an AirPod out (pauses) and put it back (resumes, never toggles).
  - *R7* The capture closures hold the talk's own `VoiceSocket`; `linkLost` stops the voice
    engine before the socket. Check: drop Wi-Fi mid-talk a few times, no crash.
  - *P6* An interruption or a media-services reset during a talk sets `micUnavailable`, so a
    `state{talk:true}` in flight does not reopen the talk into the call. Check: a call during a
    talk gives one `talk.close unavailable` in the Pixel log, not two, and TALK works after it.
  - *L6* Voice processing's ducking of other audio is set to the minimum. Check: the live / end
    earcons and an `announce` during an own-mic talk are as loud as in a host-mic talk.
  - *L10* `.media` / `.listen` ask for 48 kHz and the default I/O buffer again. Check: after a
    talk, `session → media` and music plays clean; drift settles as before the talk.
  - *L1/L2* Check `talk stats:` after a few minutes of talk: `depth mean` near the jitter
    target, `max` not stuck near 400, `shed` small; by ear, no growing delay after a route
    switch mid-talk and no clipped first syllable after a pause.
  - *P8* Voice keepalive tick is 250 ms (still one keepalive per second of no audio).
- **2026-09-30 audit round 2 (built offline, none of it run on the iPhone yet):**
  - *M5* Let two queued tracks play through: `gapless: <b> queued for host time …` during the
    first, `gapless: now <b>, changed <n> ms early` at the change (n near 0), no `start <b>` line
    after it, no gap by ear, and drift within 80 ms a few seconds into the second track. Then
    pause, seek, or talk during the first track: `gapless: queued track dropped`, and the host
    sends `music.next` again once it plays. Is `AVQueuePlayer` really gapless on these m4a files?
  - *M1* A track started by touch begins at its first note on both phones: `start <id> at <trim>
    ms in <lead> ms` with lead up to ~1500, and no `drift … → reseek` right after it.
  - *M2* One session with a click track, AirPods on both phones: compare "Compensate output
    latency" on and off (trim 0) and keep the one that lines up; note `outputLatency` and the
    route from the `start` line. After an own-mic talk: the `output delay +1.0 s` line shows the
    A2DP route, and either no `output delay a → b` line or exactly one.
  - *M10* The end earcon is heard after every own-mic talk, and the music still resumes on the
    beat (the media session is back 0.22 s later than before).
  - *M11* Force a media-services reset (Settings → Developer → Reset Media Services) while music
    plays: it comes back at the host's position within a few seconds, earcons and `announce`
    work after it, TALK works.
  - *P3* During a track download on the hotspot, TALK opens as fast as without one.
  - *P4* Force-stop and restart the Pixel app: `probe last address <ip>: ok` and the link is back
    within about 2 s of the host listening, not 10 s.
  - *P7* With the host unreachable but the link not yet lost (Pixel Wi-Fi off, within 6 s):
    TALK shows "TALK…", a second press puts it back at once; left alone it goes back after 5 s
    with the error earcon.
  - *H9* Toggle the Pixel's hotspot for a few seconds: no `volume key: disarmed` / `armed` pair
    in the log, no volume HUD; off for more than 10 s: disarmed once, armed once when it is back.
  - *M5 cancel rule (spec rewrite of Music flow 6)* `GaplessTracker` now tells a `music.play`
    message from a `state`. A `music.play` for the current track on its unchanged anchor takes
    the queued track out without a seek (`gapless: queued track taken back by the host`); a
    `state` repeating that anchor keeps it. A `state` with no `music`, not playing, or on a track
    that is neither current nor queued takes it out (`gapless: queued track dropped (state: …)`).
    After the host's change (`state` / `music.play` naming the queued track on its anchor) a
    `music.next` for another track waits (`gapless: <c> waits for the change`) and is queued
    after the local change, never in place of the queued one. A `state` / `music.play` still
    naming the old track on its old anchor right after the local change is ignored (`gapless:
    <a> on its old anchor ignored after the change`). A `music.next` that arrives while its
    `music.play` has not started yet (download, end-earcon hold) is queued right after that
    start. Device checks: edit the queue during a track with a next queued (add at the end: the
    next stays; remove the next track: `taken back`, then a new `queued` line, no seek, no
    audible hiccup); skip during a track with a next queued: the old track stops, the old next
    never sounds; three tracks in a row: `waits for the change` or `queued` for the third, both
    changes without a gap; after a talk: `queued` again within a second of the restart; last
    track of the queue ends: shown paused at 0:00, play starts it from the top.
  - *P11* A control frame that is not well-formed UTF-8 closes the connection (checked before
    the JSON parser, all four fixture vectors). A host that says `bye{reason:"proto"}` or sends
    another `proto` in `hello` is shown in the problem line and left alone (no probe, no
    connect, under its Bonjour name, its address and its `hello` name) until Settings →
    Reconnect or an app restart; other hosts are still found. Device check: a peer host with
    `proto` 2 (or one answering `bye proto`): one `protocol mismatch` / `not a host (proto 2)`
    line, the red text on the Ride screen, no further probes of it in the log; Reconnect probes
    it once more.
- **2026-09-30, every command ends the talk / fewer spoken replies** (not run on a phone):
  - "louder" / "quieter" as the first phrase of a talk opened here: `heard: "louder" (command)`,
    `volume key: command up → level <l>`, the `ok` blip, then the end earcon (two distinct
    cues, not one smeared), the talk closes on both phones and music that was playing resumes
    at the new level. Is 0.12 s enough for the `ok` to be heard whole over HFP?
  - "next" with music playing before the talk: the talk closes by itself and the next track
    plays; "pause": the talk closes and the music stays paused; "play <song>": no spoken
    "Playing …" on either phone, the closing earcon and the music only.
  - "what's playing" / "shuffle": the talk closes, then the reply is spoken. Is its first word
    lost in the ~1 s the buds take to bring A2DP back? A failure ("play <nonsense>"): the talk
    closes, the error earcon and "Couldn't find …" are heard, music that was playing resumes.
- **2026-09-30 additions:** "what's playing" and "shuffle" as the first phrase are recognised
  and the host's `announce` is spoken (after the talk, which every command now ends); tapping a song (or a recently played track)
  during a talk closes the talk (end earcon, back to media mode) and the song starts; the
  Ride screen with the command list still fits without scrolling on the real iPhone; history
  survives an app restart.
- **2026-09-30 audit round 3 (UI; nothing here has been seen on a phone: SwiftUI cannot be
  rendered on Linux).** See the section below for what changed; check on the iPhone:
  - Icon on the home screen (xtool copies `Icon.png` and sets `CFBundleIconFile`; if iOS shows
    a blank icon, the legacy key is not enough and the icon needs an asset catalog).
  - Ride: TALK pinned above the tab bar; the card's cover is large on a tall phone and small
    beside the title on a short one; with the commands unfolded the top part scrolls and TALK
    does not move; largest Dynamic Type still reaches everything.
  - TALK: orange "TALK" → amber "Connecting…" with a pulsing mic from the press until the live
    beep → red "END TALK", "Talking · 0:12" (own mic) or "Talking · rider's mic · 0:12"
    (Lark). A tap is felt on the press; the bump at "live" is expected only in a Lark talk
    (iOS mutes haptics while the mic records).
  - No redraw stutter: scroll a 200-song queue while music plays and a talk opens.
  - Queue: ✕ removes a row at once and it never comes back; two quick ✕ remove the two rows
    pressed (not a neighbour); a tap on a row right after a ✕ plays that row; swipe-delete
    does the same; the badge says 99+ past 99; rows show art and duration.
  - Search: system search field in the navigation bar; Songs / Albums / Playlists scope bar
    appears when the field is tapped; recent searches re-run; the playing song is marked and
    not tappable under "Recently played"; an empty album says "No songs in this one".
  - Mini player on Search and Queue (above the tab bar, not covering the last row); its tap
    opens Ride; a LIVE chip during a talk.
  - Lock screen: cover, title, artist; the clock matches the Pixel within a second, also for a
    track that is still downloading; "Talking with <Pixel>" during a talk; album after the
    `music.load`.
  - Problem line: deny the mic in Settings → the card with "Open Settings" on Ride; a talk
    error's red line goes on ✕ and on the next talk that opens; "Heard: …" and the
    announcement go after ~6 s.
  - Settings: languages by name; "Music sync offset" with its footer; Diagnostics folded.
  - Open, unchanged: the 5 s unanswered-TALK timeout plays the error earcon; Play while the
    music is held for "headset gone" plays on the speaker.
- **2026-09-30 tab accessory and Live Activity (built on Linux only, nothing seen on a
  phone; the first install with the extension is itself the first check).**
  - Install: `xtool dev -c release` signs the app and `PlugIns/MotopartyWidgets.appex` (a
    second App ID; see "Free-account limits"). If it is refused, note the error.
  - Mini player, iOS 26.1 or later: on Search and Queue it is the glass capsule above the
    tab bar (cover, title, artist, play/pause, the LIVE / Connecting… chip in a talk), and
    there is no second bar below the list; not on Ride; gone with neither track nor talk;
    a tap opens Ride; the last list row is not covered; largest text size still fits the
    capsule. iOS 17 to 26.0: the bar as before.
  - Live Activity: open the app and let it link, then lock the phone: "Motoparty ·
    Connected to <Pixel>" on the lock screen; start music from the Pixel with the iPhone
    locked: title and artist, the playing / paused glyph follows; a talk: "Connecting…",
    then "Talking with <Pixel>" with a running clock, and back to the track at its end; a
    link loss with nothing playing: "Looking for <Pixel>…". On a phone with a Dynamic
    Island: the glyph left, clock or glyph right, the expanded view on a long press. The
    log has `live activity: started` once and no line per state message.
  - Swipe it away on the lock screen: it stays away until the app is opened again. Quit
    the app from the switcher: the activity goes (if it stays, it goes at the next launch:
    `live activity: ending 1 left over`). Settings → Motoparty → Live Activities off:
    nothing shows and nothing else changes.
  - Music that starts before the app was ever linked in front (app launched, never linked,
    phone locked) has no activity until the app is next opened: ActivityKit only starts one
    in the foreground.
- The rest listed above: the AirPods mute gesture (Spike 2), `LocalVolume`'s hidden slider,
  the AirPods A2DP ↔ HFP switch time.

## 2026-09-30 audit round 3 (UI/UX)

Built and unit-tested on Linux only (`swift test` 211, then 222 with the Live Activity;
`xtool dev build -c release` clean); the device checklist is under "What only a real iPhone
can answer".

- **Decisions live in `MotopartyCore/UIModel.swift`** (tested in `UIModelTests`): `TalkPhase`
  (TALK / Connecting… / END TALK and the caption), `LinkWording` ("Looking for <Pixel>…"),
  `Notice` (which success clears which problem), `QueueRow` + `QueueEdits` (row keys
  `<id>#<occurrence>`, removals, moves and Undos in flight, the index to send) + `QueueUndo`, `QueueText`, `SearchWording`,
  `VoiceCommandChip`, `ArtURL` (googleusercontent size rewrite), `PlaybackPosition`,
  `LockScreenInfo`, `TrackTime`.
- **Brand:** tint `#FF7A2F`, always dark, `Icon.png` via `iconPath:` (`scripts/make-icon.sh`
  redraws it with ImageMagick). Colours in `UI/Theme.swift` mirror Android's `Palette`.
- **Ride** cannot overflow: a `ScrollView` above, TALK and the volume pinned with
  `.safeAreaInset`. The round trip left the pill for Settings → Diagnostics.
- **AppModel (UI-facing only):** `talkLive`, `talkLiveSince` and a published `talkMode`;
  `problem` is a `Notice`; `micDenied` / `speechDenied` feed the permissions card;
  `downloading` is gone (the status line covers it per track); "Heard" and the announcement
  expire after 6 s; equal values are no longer re-published, and the round trip, drift and
  route live in `LinkStats`, which only Settings → Diagnostics observes. `@Observable` was not
  adopted (not verifiable without a device).
- **Lock screen** (`Music/NowPlaying.swift`): cover, the host's timeline, updated on every
  `music.load` / `music.play`, "Talking with <Pixel>" during a talk.
- **Images:** `UI/Artwork.swift` `ArtLoader` (one memory + disk cache for rows, the card and the
  lock screen), sized requests (144 / 288 / 544 px), fade-in.
- **Mini player as the tab accessory (added later the same day, device-unverified).** On
  iOS 26.1+ `ContentView` puts `MiniPlayer` into `.tabViewBottomAccessory(isEnabled:)`
  (enabled on Search and Queue while there is a track or a talk, the old conditions) and
  the per-tab `.safeAreaInset` is left out, so it is never there twice; iOS 17 to 26.0 keep
  the inset bar. 26.1 rather than 26.0: the iOS 26.5 SDK declares the plain
  `tabViewBottomAccessory(content:)` for 26.0 and only the `isEnabled:` form (26.1) can hide
  the capsule. `MiniPlayer.Style` drops the bar's own background in the accessory, and in
  `tabViewBottomAccessoryPlacement == .inline` also the artist line and the chip's word
  (the tab bar is not set to minimise, so `.inline` is not expected today).
- **Live Activity (added later the same day, device-unverified).** Display only: track and
  artist with playing / paused, "Connecting…" / "Talking with <Pixel>" with the talk's clock,
  or "Connected to <Pixel>" / "Looking for <Pixel>…" with nothing playing. No cover (the
  extension cannot read the app's cache without an app group) and no buttons (an interactive
  Talk button needs App Intents metadata that Xcode generates).
  - `MotopartyCore/LiveActivity.swift` (tested in `LiveActivityTests`): `LiveActivityState`
    (the content, finished lines) and `LiveActivityTracker` (start / update / end / nothing;
    equal states send nothing; a start only when the app is in front and activities are
    allowed; swiped away = stays away until the app is opened).
  - `Music/LiveActivity.swift` `LiveActivityController` makes the ActivityKit calls, in
    order; `AppModel.updateLiveActivity()` feeds it from `updateNowPlaying()`, the link's
    `didSet` and the live cue. The activity exists from the first link on (not only with a
    track), because it can only be started in the foreground and music usually starts with
    the phone in a pocket. Leftovers of a killed run are ended at launch, the running one at
    `willTerminate`. With Live Activities off in Settings nothing is asked.
  - The extension: product `MotopartyWidgets` (`Package.swift`), `extensions:` in `xtool.yml`,
    `MotopartyWidgets-Info.plist`; xtool 1.19.2 links it with `-e _NSExtensionMain` and
    bundles `PlugIns/MotopartyWidgets.appex`, bundle ID `com.vaknin.motoparty.MotopartyWidgets`.
- **Not done:** `.searchSuggestions` (the history list under the empty field does that job).
