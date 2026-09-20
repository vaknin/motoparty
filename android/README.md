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
$A shell appops set $P SYSTEM_ALERT_WINDOW allow                  # floating TALK/MUSIC
```

Open the app after granting RECORD_AUDIO so the foreground service re-claims its `microphone`
type (it cannot be claimed from the background).

## Layout (`app/src/main/java/com/kivan/motoparty/`)

| Package / file | What |
|---|---|
| `core/` | Pure Kotlin, JVM-tested: `Messages`/`Codec` (JSON + u32 framing), `ClockEstimator`, `VoicePacket`, `JitterBuffer`, `CommandParser` |
| `LinkService` | Foreground service (`microphone\|mediaPlayback\|connectedDevice`), wake + Wi-Fi low-latency locks, notification with Talk/Command/Stop (Command becomes "Show buttons" while the overlay is off) |
| `LinkHost` | Wires everything; the one place protocol decisions are made (main thread) |
| `link/` | `Discovery` (NSD `_motoparty._tcp`, TXT proto/voice/http), `ControlServer` (TCP 47800), `VoiceSocket` (UDP 47801), `TalkController` (talk authority, 20 s silence close) |
| `audio/` | `Opus` (JNI), `VoiceEngine` (AudioRecord VOICE_COMMUNICATION 16 kHz → Opus → UDP; UDP → jitter → Opus FEC/PLC → AudioTrack), `AudioRouter` (MODE_IN_COMMUNICATION + `setCommunicationDevice`), `Earcons` (generated tones), `LiveCue` (when the "live" beep may play: first captured frame **and** the SCO link up, fallback timer 2.5 s), `ScoWatch`/`AudioModeWatch` (Bluetooth SCO link state and audio mode, cached off Main) |
| `music/` | `Catalog` (NewPipeExtractor, YouTube Music search, itag-140 resolve), `OkHttpDownloader`, `TrackCache` (1 GiB LRU, ranged download, prefetch), `TrackServer` (hand-rolled HTTP on 47802), `Player` (ExoPlayer + MediaSession), `SyncController` (scheduled start with learned start-up latency, 10 s drift check, speed nudge 80 ms–1 s, re-seek > 1 s, latency trim), `MusicController` (queue, load/ready/play, talk pause/resume) |
| `voicecmd/` | `Transcriber` (on-device SpeechRecognizer, falls back to the default service), `Announcer` (TTS + earcons) |
| `overlay/OverlayService` | Draggable TYPE_APPLICATION_OVERLAY with TALK and MUSIC zones (112×100 dp), colour = state; drag onto the X at the bottom to hide (sets `overlayEnabled=false`, back via the notification's "Show buttons") |
| `trigger/` | `Triggers`: one stream fed by overlay, headset buttons, notification and UI |
| `ui/MainScreen` | Link status, now playing/queue, search, settings, log |

Headset buttons (via the MediaSession): play/pause → talk toggle (setting: or music
play/pause); next (AirPods double press) → voice command (setting: or next track); previous →
previous track.

## Bumping NewPipeExtractor

YouTube breaks extraction now and then. Bump `newpipeExtractor` in `gradle/libs.versions.toml`
to the newest tag on https://github.com/TeamNewPipe/NewPipeExtractor/releases and run the
`-Pnetwork` test above before installing.

## Known gaps

- The host only sees `t1 - t0` from pings, so the UI's "clock skew" includes one-way delay;
  there is no host-side RTT (the protocol has no H→C ping).
- Silence close counts every sent/received kind-1 packet as activity (frames encoded in DTX
  are not sent). A noisy mic (wind, AirPods in a room) never goes DTX, so talk stays open.
- Music drift: moderate drift (80 ms–1 s) is corrected by playing up to 5 % faster/slower,
  not by re-seeking as PROTOCOL.md says, because on A2DP every seek restarts the output with
  ~400 ms of new lag (see `SyncController`). Only > 1 s re-seeks.
- Client `volumeUp/volumeDown` (`music.control` or voice) changes the **host's** media volume;
  the protocol has no H→C volume message.
- While talk is open the headset is in HFP, so AirPods presses arrive as call controls, not
  media keys; end talk with the overlay/notification/UI (or the 20 s silence close).
- sherpa-onnx fallback ASR (plan) is not implemented; the platform recognizer may ignore the
  Bluetooth mic.
- `Earcons` and TTS are not mixed into the voice stream, so the remote side does not hear
  them (each phone plays its own).
