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
                           CommandParser (the grammar, run here too because volume is local)
Sources/Motoparty/       the iOS app (only compiled by xtool against the iOS SDK):
  Link/                    Discovery (NWBrowser + /24 sweep), ControlClient, VoiceSocket
  Audio/                   SessionController (A2DP music ↔ HFP talk), VoiceEngine, KeepAlive, EarconPlayer,
                           LocalVolume (this phone's system volume)
  Music/                   TrackCache (URLSession), SyncedPlayer (AVPlayer setRate atHostTime), NowPlaying
  Voice/                   Transcriber (on-device SFSpeechRecognizer), Announcer (AVSpeechSynthesizer)
  UI/                      SwiftUI: status, TALK and MUSIC buttons, now playing, latency trim, settings
  AppModel.swift           ties it together (talk / music / command flows)
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
swift test            # 66 tests: fixtures, command parser, jitter buffer, Opus, drift controller
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
cd tools/peer && uv run motoparty-peer host --track X.m4a
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

- **No floating buttons on iOS.** The TALK/MUSIC overlay above other apps exists on the Pixel
  only (decided with the user 2026-09-20): iOS cannot draw over other apps, and nothing in
  `ios/` tries to. The passenger's triggers are the app's own buttons and the headset controls.
- **Talk:** TALK sends `talk.open{by:client}`, and nothing changes until the host decides. On
  `talk.open` the app pauses music and switches the session to `.playAndRecord`/`.voiceChat`
  with Bluetooth HFP. It then starts a fresh voice-processing AVAudioEngine and plays the
  "live" earcon. Capture uses an AVAudioSinkNode rather than a tap, because taps deliver at
  least 100 ms per buffer. Frames flagged by `OPUS_GET_IN_DTX` are not sent. Playback goes
  jitter buffer → Opus → AVAudioSourceNode. On `talk.close` the app switches back to
  `.playback` (A2DP), and the host resumes music with `music.play`.
- **Talk is not negotiable** (PROTOCOL.md "Talk flow"), so there is no decline button and none
  may be added. There is only *cannot*: if the record permission is denied, or activating the
  `.talk` session fails (a cellular call holds the input), or the voice engine will not start,
  the app sends `talk.close{by:"client",reason:"unavailable"}`, plays the error earcon if this
  phone was the one that asked, and returns to the media session; the host then closes talk.
  An undetermined permission is asked for once and a refusal counts as "cannot". The mirror
  case — `talk.close{reason:"unavailable"}` answering our own `talk.open`, so talk never
  opened and `state.talk` stayed false — only clears the "requesting" state and plays the
  error earcon: no audio session is torn down and no music is resumed, because none was paused.
- **Volume is local** (PROTOCOL.md "Commands"): `MotopartyCore.CommandParser` runs on this
  phone's own utterance before anything is sent, and `volume up`/`louder`/`volume down`/
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
  `AVAudioSession.outputLatency` plus the latency trim (±10 ms buttons) — so the *sound*, not
  the player, lands on the anchor.
  Drift is then handled by `DriftController` in MotopartyCore, the same rule as Android's
  `SyncController` (PROTOCOL.md "Music flow" step 4, because a seek on A2DP costs a fresh
  350-700 ms of lag): the position is compared with the anchor every 10 s (2 s while a
  correction runs or just after a start), and up to 80 ms of error is left alone; 80 ms - 1 s
  is absorbed by `AVPlayer.rate = 1 - drift / 4000` clamped to 0.95...1.05 (pitch kept, the
  item's `audioTimePitchAlgorithm` is `.spectral`); above 1 s it re-seeks and the rate goes
  back to 1. A running correction is held until the error is under 40 ms, so noise around the
  80 ms boundary cannot flap the rate.
- **Buttons:** by default an AirPods single press is talk, a double press is a voice command,
  and a triple press is previous track (configurable in Settings). iOS also sends `pause` when
  an AirPod leaves the ear, so an explicit `pause` is ignored unless enabled in Settings. While
  the mic is open, the iOS 17 AirPods mute gesture is treated as the single-press action; this
  is untested (Spike 2). `AVAudioApplication.setInputMuteStateChangeHandler` is **macOS only**
  (`API_UNAVAILABLE(ios)`): on iOS the system does the muting itself and only reports it, so
  `SessionController` observes `AVAudioApplication.inputMuteStateChangeNotification`, unmutes
  again with `setInputMuted(false)`, and ignores the unmute's own notification so one press
  stays one action.
- **Keep-alive:** a silent AVAudioEngine runs whenever talk is closed, so the locked app is
  never suspended. `UIRequiresPersistentWiFi` stops iOS from powering Wi-Fi down.
