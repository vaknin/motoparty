# Motoparty — Android host

The Pixel side of Motoparty: hotspot host, Bonjour advert, control/voice/track servers, talk
authority, shared-music player and voice-command executor. Wire contract: `../PROTOCOL.md`.

Hand-written Gradle project (no Android Studio), mirroring `~/Work/Pronounce`: AGP 9.4.0,
Kotlin 2.4.10, Gradle 9.6.1 wrapper, compileSdk 37.2, minSdk 29, target 37, arm64-v8a only.

## One-time setup

```sh
# NDK + CMake for libopus (user-level, no sudo)
~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager "ndk;29.0.14206865" "cmake;3.31.6"
# libopus source (not committed; git-ignored)
tools/fetch_opus.sh
```

`tools/fetch_opus.sh` downloads `opus-1.5.2.tar.gz` from downloads.xiph.org, checks its
SHA-256 and unpacks it into `app/src/main/cpp/opus/`. `app/src/main/cpp/CMakeLists.txt` builds it
as a static library (DRED/OSCE off, no tests/programs) and links it with the ~80-line JNI shim
`opus_jni.c` into `libmotoparty_opus.so`. The Gradle build fails with a pointer to the script if
the source is missing. To bump libopus, change `VERSION`/`SHA256` in the script and re-run it.

## Build, test, install

```sh
./gradlew test                       # JVM unit tests (protocol fixtures from ../fixtures)
./gradlew :app:testDebugUnitTest -Pnetwork --tests '*CatalogNetworkTest*'   # live YouTube
./gradlew testDebugUnitTest -Pscreenshots --tests '*ScreensTest*'   # PNGs of every tab → app/build/outputs/roborazzi/
./gradlew assembleDebug
adb -s 192.168.1.100:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.1.100:5555 shell am start -n com.kivan.motoparty/.MainActivity
```

The Pixel shows up twice in `adb devices` (`…:5555` and an `_adb-tls-connect` entry); always
pass `-s`.

Unit tests read `../fixtures` through the `motoparty.fixtures` system property that
`app/build.gradle.kts` sets on every test task. Covered: control codec round-trips, framing
(64 KiB cap, UTF-8, unknown types/fields), clock estimator, UDP header, command parser (all
fixtures), jitter buffer, talk state machine, `ControlServer` over loopback sockets (hello/state,
ping/pong, replacement, 6 s liveness, oversize frame), and `TrackServer` (Range, 404s).

## Permissions

Runtime (the app asks on first launch; or grant by hand):

```sh
P=com.kivan.motoparty; A="adb -s 192.168.1.100:5555"
$A shell pm grant $P android.permission.RECORD_AUDIO
$A shell pm grant $P android.permission.POST_NOTIFICATIONS
$A shell pm grant $P android.permission.BLUETOOTH_CONNECT
$A shell pm grant $P android.permission.ACCESS_LOCAL_NETWORK     # Android 17
$A shell appops set $P SYSTEM_ALERT_WINDOW allow                  # floating TALK button
```

Open the app after granting RECORD_AUDIO so the foreground service re-claims its `microphone`
type (it cannot be claimed from the background).

## Layout (`app/src/main/java/com/kivan/motoparty/`)

| Package / file | What |
|---|---|
| `core/` | Pure Kotlin, JVM-tested: `Messages`/`Codec` (JSON + u32 framing), `ClockEstimator`, `VoicePacket`, `JitterBuffer`, `CommandParser`, `WakeWord` + `PhraseGate` (wake word, arming, solo talk), `CommandEffect` (command + talk state → close the talk? where the reply goes) |
| `LinkService` | Foreground service (`microphone\|mediaPlayback\|connectedDevice`), wake + Wi-Fi low-latency locks, notification with Talk/Stop (plus "Show buttons" while the overlay is off) |
| `LinkHost` | Wires everything; the one place protocol decisions are made (main thread) |
| `link/` | `Discovery` (NSD `_motoparty._tcp`, TXT proto/voice/http), `ControlServer` (TCP 47800), `VoiceSocket` (UDP 47801), `TalkController` (talk authority; ends on a trigger or link loss) |
| `audio/` | `Opus` (JNI), `VoiceEngine` (AudioRecord VOICE_COMMUNICATION 16 kHz → Opus → UDP; UDP → jitter → Opus FEC/PLC → AudioTrack), `AudioRouter` (MODE_IN_COMMUNICATION + `setCommunicationDevice`), `Earcons` (generated tones), `LiveCue` (when the "live" beep may play: first captured frame **and** the SCO link up, fallback timer 2.5 s), `ScoWatch`/`AudioModeWatch` (Bluetooth SCO link state and audio mode, cached off Main) |
| `music/` | `Catalog` (NewPipeExtractor, YouTube Music search, Opus itag-251 resolve with AAC itag-140 fallback), `OkHttpDownloader`, `TrackCache` (1 GiB LRU, ranged download, prefetch; `tracks-opus/` and, after a client's "not decodable", `tracks/` for AAC), `Remux` + `OpusDops` (WebM Opus → MP4, packet copy; fixes Media3's little-endian `dOps`), `TrackServer` (hand-rolled HTTP on 47802), `Player` (ExoPlayer + MediaSession; outside transport controls go through `MusicController`), `SyncController` (scheduled start with learned start-up latency, 10 s drift check, speed nudge 80 ms–1 s, re-seek > 1 s, latency trim), `MusicController` (queue, load/ready/play, talk pause/resume) |
| `voicecmd/` | `TalkRecognizer` (in-talk commands: on-device SpeechRecognizer fed the talk's own capture through a pipe — `audio/PcmTee`, never blocks the capture thread — segmented session, falls back to the default service; API 33+), `Announcer` (TTS + earcons, on the media route or, in a talk, the call route) |
| `overlay/OverlayService` | Draggable TYPE_APPLICATION_OVERLAY with one TALK button (120×120 dp), colour = state; drag onto the X at the bottom to hide (sets `overlayEnabled=false`, back via the notification's "Show buttons") |
| `trigger/` | `Triggers`: one stream fed by overlay, headset buttons, notification and UI |
| `ui/` | `MainScreen` (tabs, stateless `Motoparty(...)`), `RideTab`, `SearchTab`, `QueueTab`, `SettingsTab` (Diagnostics: link numbers, debug tools, log), `Theme`, `Icons` (path data, no icon library), `Common` (Coil cover art, rows) |

**One action everywhere** (option A, 2026-09-29): the overlay button, the Ride tab's TALK, the
notification's Talk and the headset's play/pause all toggle talk. There is no command mode.
Commands are spoken **inside a talk** (PROTOCOL.md "Commands"): "Moto party, play album …",
"… next", "… pause", "… over"; a bare "Moto party" arms the next phrase for 5 s (LISTEN earcon);
with no passenger connected a press opens a **solo talk** where every phrase is a command
(`captureDump` only decides whether its WAV is written). `play`/`resume` close the talk and the
music starts after the headset is back on A2DP; `over`/`end talk`/`hang up` close it like a press.
Every phrase is logged locally as `heard: "<text>" (command|conversation|armed)`; nothing about
conversation goes on the wire.

Headset buttons (via the MediaSession): play/pause → talk toggle (setting: or music
play/pause); next → next track; previous → previous track. Transport controls from outside controllers (KDE Connect, lock screen, watch)
are not headset buttons: play/pause/next/previous always act on the music, for both phones.

## Bumping NewPipeExtractor

YouTube breaks extraction now and then. Bump `newpipeExtractor` in `gradle/libs.versions.toml`
to the newest tag on https://github.com/TeamNewPipe/NewPipeExtractor/releases and run the
`-Pnetwork` test above before installing.

## Known gaps

- The host only sees `t1 - t0` from pings, so the UI's "clock skew" includes one-way delay;
  there is no host-side RTT (the protocol has no H→C ping).
- Music drift: moderate drift (80 ms–1 s) is corrected by playing up to 2 % faster/slower,
  not by re-seeking as PROTOCOL.md says, because on A2DP every seek restarts the output with
  ~400 ms of new lag (see `SyncController`). Only > 1 s re-seeks.
- Client `volumeUp/volumeDown` (`music.control`) changes the **host's** media volume; the
  protocol has no H→C volume message. A spoken volume command is local (PROTOCOL.md): in a talk
  the rider's changes the call stream, outside one the media stream.
- **The key device question:** while talk is open the headset is in HFP, so earbud presses may
  arrive as call controls, not media keys — then a press cannot *end* a talk from the earbuds.
  End it with the overlay/notification/UI, or say "Moto party, over". Talk ends on a press (or
  `end`/`play`/`resume`) only: there is no silence close (removed 2026-09-29).
- In-talk commands need Android 13 (`EXTRA_AUDIO_SOURCE`); below it talk works without them
  (logged once). Whether the recognizer is fed properly while the phone is in
  `MODE_IN_COMMUNICATION` on the ride is device-unverified (the spike ran in `MODE_NORMAL`, fed
  from a file).
- In a solo talk a phrase within 2 s of our own TTS is dropped as its echo (`heard: … (own speech,
  ignored)`), so "Didn't catch that" cannot feed itself.
- sherpa-onnx fallback ASR (plan) is not implemented.
- `Earcons` and TTS are not mixed into the voice stream, so the remote side does not hear
  them (each phone plays its own).
