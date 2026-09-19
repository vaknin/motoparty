# Android host — handoff (2026-09-19)

Read first: `../PROTOCOL.md` (binding, updated twice today), the plan's "Android" section
(`~/.claude/plans/motorcycle-communication-application-proud-pixel.md`), `README.md` here.
Nothing is committed (instructed: no git commit).

## 1. What exists

Paths are relative to `app/src/main/java/com/kivan/motoparty/`.

| File | Status |
|---|---|
| `core/Messages.kt` | done. `@Serializable` sealed `Message`, `t` discriminator, `UnknownMessage`. `Hello.proto` and `State.queue` are required (no defaults) |
| `core/Codec.kt` | done. JSON codec + u32 framing (64 KiB cap). `ProtocolException` = fatal (invalid JSON, non-object, oversize); `MalformedMessageException` = drop & keep (missing `t`, missing/mistyped/null required field, **and a value outside a listed set** — `checkEnums`/`ENUM_FIELDS`). `checkTypes` walks the descriptor because kotlinx accepts `"7"` for a Long |
| `core/ClockEstimator.kt` | done (8-sample window, min RTT, ties most recent, negative RTT takes no slot, >500 ms jump clears window). Host doesn't use it; it's for parity/tests |
| `core/VoicePacket.kt` | done |
| `core/JitterBuffer.kt` | done per the latest spec: talk-spurt start plays `target` ms after arrival; underrun = packet after its slot, +20 ms at most once per spurt; -20 ms after 10 s; changes apply at next spurt; keepalive seqs never count as loss; seq-contiguous ts jump = silence; FEC/PLC; brief PLC then silence on an empty buffer |
| `core/CommandParser.kt` | done per the latest spec (per code point, U+2019 -> `'`, keep L*/M*/N*) |
| `link/TalkController.kt` | done (pure state machine, 10 s silence close, link loss) |
| `link/ControlServer.kt` | done. One writer coroutine per connection (socket writes on main threw NetworkOnMainThreadException), pong answered on the reader thread, second hello replaces client (old one gets `bye`), 6 s liveness watchdog |
| `link/VoiceSocket.kt` | done. UDP 47801, peer = source of last valid packet from the control client's IP, running 16 kHz `ts` clock from a random start (`currentTs()`), keepalive every 1 s idle carrying current ts, shared seq |
| `link/Discovery.kt` | done (NSD `_motoparty._tcp`, TXT proto/voice/http) |
| `audio/opus_jni.c` + `cpp/CMakeLists.txt` + `audio/Opus.kt` | done. libopus 1.5.2 static, VOIP/24 kbps/FEC 10 %/DTX/complexity 8 |
| `audio/VoiceEngine.kt` | done. Frames with `OPUS_GET_IN_DTX == 1` are not sent; every sent/received kind-1 packet = activity |
| `audio/AudioRouter.kt` | done (ref-counted MODE_IN_COMMUNICATION + setCommunicationDevice, prefers BLE headset > SCO > wired > USB) |
| `audio/Earcons.kt` | done (generated tones: LIVE, CLOSED, OK, ERROR, LISTEN) |
| `music/Catalog.kt`, `OkHttpDownloader.kt` | done (NewPipeExtractor v0.26.5; YT Music songs/albums/playlists, artist = top 20 songs; falls back to plain YouTube search; resolves progressive M4A, itag 140 preferred) |
| `music/TrackCache.kt` | done (1 GiB LRU by mtime, 1 MiB Range chunks, UA chosen by the URL's `c=` client, shared in-flight downloads, prefetch) |
| `music/TrackServer.kt` | done (hand-rolled HTTP, GET/HEAD `/track/<id>.m4a`, single Range, 404 otherwise) |
| `music/Player.kt` | done (ExoPlayer, no auto audio focus, MediaSession with `onMediaButtonEvent`, `speed`) |
| `music/SyncController.kt` | done. Prepared start with a learned start-up latency lead, check 2 s after start then every 10 s, 80 ms–1 s corrected by speed nudge (≤5 %), >1 s re-seek. The lead is now learned with a damped 1/4 step (`LEARN_DIVISOR`), not a mean of two, which was ringing on A2DP. `PlayerControls` was split out of `Player` so all of this is unit-tested |
| `music/MusicController.kt` | done (queue, load → ready ≤8 s / music.error → play at now+300, pause/resume, next/previous, talk pause + resume at now+resumeLeadMs, duck mode, mid-track join on client connect, prefetch + music.load of next) |
| `voicecmd/Transcriber.kt` | done (on-device recognizer when available, falls back to default on language/client errors) |
| `voicecmd/Announcer.kt` | done (TTS, USAGE_ASSISTANT, earcon first) |
| `LinkHost.kt` | done: all wiring and protocol decisions, main thread, `guarded{}` around event loops |
| `LinkService.kt` | done (FGS types microphone\|mediaPlayback\|connectedDevice, drops microphone if SecurityException; wake + Wi-Fi low-latency locks; notification actions Talk/Command/Stop) |
| `overlay/OverlayService.kt` | taps and drag now exercised on device. **Bug**: the saved x/y is not clamped to the display, so after a rotation to landscape the overlay sits off-screen (see section 2) |
| `trigger/Trigger.kt` | done |
| `ui/MainScreen.kt`, `MainActivity.kt` | done (status, permissions, TALK/COMMAND, now playing/queue, search, settings, log). Still not seen rendered: the one screenshot attempt caught another app in the foreground |
| `Settings.kt`, `Hub.kt`, `MotopartyApp.kt` | done |
| `tools/fetch_opus.sh` | done |

Tests (`app/src/test/...`): `CodecTest` (14), `ClockEstimatorTest`, `VoicePacketTest` (3),
`CommandParserTest` (2), `JitterBufferTest` (13), `TalkControllerTest` (7), `ControlServerTest`
(7, real loopback sockets), `TrackServerTest` (5), `SyncControllerTest` (11),
`CatalogNetworkTest` (2, skipped unless `-Pnetwork`). Every fixture section is consumed by a
test (`messages`/`valid`/`invalid`/`unknown`/`malformed`/`fatal`/`steps`/`conversions`/`cases`);
none is silently skipped.

## 2. Verified, and how

- `./gradlew assembleDebug test` -> BUILD OK, 65 tests, 63 pass, 2 skipped (network), 0 fail.
- `./gradlew :app:testDebugUnitTest -Pnetwork --tests '*CatalogNetworkTest*'` (laptop, live
  YouTube) -> pass: song/album/artist/playlist search; Bohemian Rhapsody resolved itag 140,
  5.7 MB downloaded in 2.2 s, `ftyp` box.
- Device (Pixel 8, Android 17 / SDK 37, AirPods Pro connected):
  install + `pm grant` + `appops` below, launched, no crashes in logcat. FGS types = 0x92
  (all three). `ss -lntu` on the phone shows 47800/tcp, 47801/udp, 47802/tcp.
- `avahi-browse -rpt _motoparty._tcp` from the laptop: `Pixel 8 ... 192.168.1.100;47800;
  "http=47802" "proto=1" "voice=47801"`.
- Bench with `cd ../tools/peer && uv run motoparty-peer client --host 192.168.1.100 --no-audio [--tone]`
  (stdin: `talk`, `say <text>`, `pause`, `quit`):
  - hello + state on connect, pongs rtt 5–13 ms (spikes ~50 ms = phone Wi-Fi power save).
  - talk.open answered in 18 ms with talk.open + state{talk:true}; host Opus reached the peer
    (245 packets in 6 s, decoded by the peer's libopus, 0 FEC/PLC/late); talk.close clean.
  - `say play album dark side of the moon` -> announce "Playing album The Dark Side of the
    Moon by Pink Floyd", music.load (9-track queue), peer ready, single music.play at +300 ms,
    next track's music.load prefetched; `pause` -> music.pause + state.
  - Reconnect mid-track -> state, music.load current, after ready music.play with the old
    anchor.
  - Talk during music -> state playing:false with pause position; after talk.close,
    music.play at +1445 ms (1500 lead).
  - `curl` `/track/<id>.m4a` -> 200 audio/mp4 5.7 MB in 0.37 s; `-r 100-199` -> 206 with
    Content-Range; unknown id and `/` -> 404.
  - Host drift (logcat `SyncController`): after the latency-learning rewrite, -44, -22, -84
    (nudged), -49, -34, -51 ms.
- COMMAND button tapped via `adb shell input tap` -> AudioRouter selected "AirPods Pro (type 7)",
  recognizer ran, error 7 (no speech) -> announce "Didn't catch that". Plumbing only.
- `adb shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE` -> reached our MediaSession ->
  TALK trigger (media button session = ours per `dumpsys media_session`).

### Session 2 (2026-09-19 evening) — device checks added

All against `tools/peer` on the laptop (192.168.1.102) over home Wi-Fi.

- **Baseline re-confirmed**: hello+state on connect, talk open/close, `say play album dark side
  of the moon` -> announce + 9-track queue + music.load/ready/play at +300 ms + next-track
  prefetch, `pause`, `raw` unknown/malformed frames ignored without dropping the link.
- **`KEYCODE_SLEEP` does lock this phone** — the previous note was wrong. After it,
  `dumpsys power` reports `mWakefulness=Dozing`, `dumpsys window` `screenState=SCREEN_STATE_OFF`
  and `mDreamingLockscreen=true`. So the screen-off tests below need no hands.
- **Talk with the screen off and locked**: `talk.open` answered in 68 ms, voice flowed both
  ways, music loaded and played, TTS announce spoke. No behaviour difference from screen-on.
- **10 s silence close, on device at last**: with the peer in `--no-audio` (keepalives only) and
  a quiet room, the host's own encoder went DTX and the host closed talk itself with
  `reason:"silence"` 10 s after its last non-DTX frame (12.3 s after open). Logged
  `talk closed (by host, silence)`.
- **Host-initiated talk while locked**: `input keyevent KEYCODE_MEDIA_PLAY_PAUSE` ->
  `trigger TALK from MEDIA_BUTTON` -> `talk.open{by:"host"}` at the peer, AudioRouter picked
  "AirPods Pro (type 7)".
- **Deep doze**: `dumpsys deviceidle` showed `mState=IDLE` (deep, not light) with the link up
  and pongs flowing. The FGS + wake/Wi-Fi locks survive it.
- **Soak: 31 min, locked, peer connected, 930 pings, zero link losses or reconnects.** The phone
  reached deep doze during it. Clock-offset estimate moved 55 ms over the whole run (min-RTT
  estimator; well inside the 80 ms sync threshold).
- **Overlay taps and drag** (window `ty=APPLICATION_OVERLAY`, frame `[0,657][314,1221]` at
  420 dpi, i.e. 314x564 px; TALK is the top half, MUSIC the bottom):
  - tap TALK -> `trigger TALK from OVERLAY` -> talk open; tap again -> close. Voice flowed.
  - `input swipe` moved the window by exactly the swipe delta and fired **no** trigger, so the
    drag/tap slop test is right.
  - tap MUSIC -> `trigger MUSIC from OVERLAY` -> recognizer ran -> error 7 (no speech, nobody
    could talk to it) -> `announce: Didn't catch that`. Plumbing end to end; real ASR still open.
- **Voice-command trigger while locked**: `KEYCODE_MEDIA_NEXT` -> `trigger MUSIC from
  MEDIA_BUTTON`, recognizer ran for 6.7 s in deep doze, error 7 (no speech), TTS announced.
  So the FGS `microphone` type does get the mic with the screen off; only real speech is untested.
- **Track HTTP server** re-checked with `curl`: `GET` 200 `audio/mp4` 1 059 916 B; `HEAD` 200
  with `Accept-Ranges`/`Content-Length`; `-r 100-199` 206 with `Content-Range:
  bytes 100-199/1059916`; unsatisfiable range 416; unknown id 404; `/` 404.
- **Locks actually held** (`dumpsys power` / `dumpsys wifi`): `PARTIAL_WAKE_LOCK
  'motoparty:link'` and `WifiLock{motoparty:link type=4}` (low latency).
- **Framing conformance against the real device, 9/9.** A raw-socket probe (kept at
  `scratchpad/framing_probe2.py`) sent each droppable frame — missing required field, mistyped
  field, explicit null, unknown type, no `t`, and a body of exactly 65536 bytes — down **one**
  connection and pinged after each: all six were dropped with the link up. The three fatal ones
  (invalid JSON, a JSON array, a 65537 length prefix) each closed the connection. The same probe
  scores 9/9 against `tools/peer`'s host, so the two implementations agree.
  Gotcha that cost time: the first version opened a fresh connection per case, and the still-running
  soak client kept reconnecting and replacing it, so every case looked like a failure. If a probe
  suddenly "fails" everything, check `pgrep -f motoparty-peer` first.
- **The new volume rules, on device** (against the already-updated `tools/peer`), host music
  volume read from `dumpsys audio` before and after each:
  - `raw {"t":"music.control","action":"volumeUp"}` and `"volumeDown"` -> no reply, link stayed
    up, host volume unchanged (15 -> 15). Dropped as malformed, as intended.
  - `raw {"t":"command.text","text":"louder"}` and `"volume down"` -> `announce{"Didn't catch
    that", earcon:"error"}` both times, host volume unchanged (15 -> 15).
  - the peer's own `vol+`/`vol-` are now handled locally and never reach the host.
  - `say next` still works, so the parser itself is unaffected.
- **Overlay is unreachable in landscape (bug, not fixed).** `OverlayService` stores the dragged
  position as raw pixels (`prefs` `x`/`y`) and restores it with no clamp, and the window has
  `FLAG_LAYOUT_NO_LIMITS`. After the phone rotated to landscape (1080 px tall) the overlay's frame
  was `[107,1550][421,2114]` — entirely below the display, with no way to drag it back. A `y` that
  is fine in portrait is off-screen in landscape. Fix: clamp x/y to the current display bounds both
  when restoring and on a configuration change. Left alone because it could not be re-tested on the
  device (see the note about the user picking the phone up).

#### Screen-off costs latency (measured)

The soak ran across a screen-off stretch and a screen-on stretch, same network, same peer, so
the two are directly comparable (ping RTT, ms):

| Screen | n | min | median | p90 | p99 | max |
|---|---|---|---|---|---|---|
| off (locked, deep doze) | 382 | 4 | 17 | 112 | 256 | 347 |
| on (app foreground) | 172 | 4 | 12 | 54 | 115 | 123 |

That matches Android's rule that `WIFI_MODE_FULL_LOW_LATENCY` is only *active* while the app is
foreground **and** the screen is on — the lock is held either way, but it stops helping when the
screen goes off. Consequences seen: with the screen off the jitter buffer settled at 60 ms
instead of 40 ms, and one underrun was recorded at the start of a talk spurt.

**This is probably not the real-world number.** These runs had the Pixel as a Wi-Fi *client* on
home Wi-Fi, where it power-saves. On a ride the Pixel is the SoftAP, which does not client-power-save
the same way. Measuring this on the hotspot is the single most valuable thing left (see
"Needs hands", item 2). If the tail survives there, the options are to hold the screen on
(dimmed) while talk is open, or to raise the jitter buffer's 200 ms ceiling.

Not verified: real speech ASR (needs a human voice); audio quality by ear; a hotspot link (all
testing was on home Wi-Fi); a ride.

### Protocol changes of 2026-09-19 (coordinator decision) — implemented

Two wire changes landed mid-session; PROTOCOL.md and the fixtures are the binding text.

**1. Volume is local.** `music.control.action` is now only `pause|resume|next|previous`.
- `ControlAction` in `core/Messages.kt` holds the set; `LinkHost.onMusicControl` lost its volume
  branches.
- A `command.text` from the client that parses to a volume command is answered
  `announce{"Didn't catch that", earcon:"error"}` and changes **nothing**
  (`LinkHost.onVolumeCommand`). The client is expected to handle its own volume and never send it.
- The Pixel's *own* spoken "louder"/"quieter" still changes the Pixel's volume, now with an `ok`
  earcon and **no** `announce` — the passenger must not be told about a local volume change.
- `fixtures/commands.json` is unchanged: the parser still recognises the phrases; only what the
  host does with a client-originated one changed.

**2. `talk.close` reason `"unavailable"`.** Talk is not negotiable — there is deliberately no
decline UI and no setting, and none must ever be added.
- Host -> client: when the Pixel cannot open its mic, a client `talk.open` is answered with
  `talk.close{by:"host",reason:"unavailable"}` instead of `talk.open`. Nothing is broadcast and
  `state.talk` stays false (`LinkHost.onClientTalkOpen`).
- Client -> host: `talk.close{by:"client",reason:"unavailable"}` right after our `talk.open` is an
  ordinary close request — talk closes, `talk.close` is broadcast, music resumes as usual — and the
  Pixel plays the error earcon **only if the Pixel was the one that triggered**
  (`TalkController.openedBy`, `LinkHost.onClientTalkClose`).
- The Pixel's own trigger with no usable mic: error earcon, no `talk.open` goes out.
- "Cannot open its mic" is `LinkHost.micAvailable()`: `RECORD_AUDIO` missing, the `microphone` FGS
  type not held (`Hub.micFgsType`, set by `LinkService`), or `AudioManager.mode` is `MODE_IN_CALL`/
  `MODE_RINGTONE`. `MODE_IN_COMMUNICATION` is **not** treated as busy — that is the mode our own
  talk and our own recognizer set.

**Enum values are now validated in the codec.** PROTOCOL.md's "Control channel" says a value
outside a listed set is malformed (drop, keep the connection). `Codec.checkEnums` walks
`ENUM_FIELDS` in `core/Messages.kt`, covering `hello.role`, `talk.open.by`, `talk.close.by`,
`talk.close.reason`, `music.control.action` and `announce.earcon`. Before this, `{"action":
"volumeUp"}` decoded happily into a `String` field and was silently ignored.

The third old gap, **no host->client ping**, is closed as "won't do": the host cannot measure RTT
or show link quality, and that is accepted.


## 3. Not done, in priority order

Coordinator spec updates, all implemented: (1) DTX frames not sent, kind-1 = activity,
running ts clock, keepalives carry ts; (2) spurt-based jitter depth, once-per-spurt raise;
(3) malformed = drop/keep, invalid JSON/oversize = close, `queue` required, paused state
semantics, music.error -> play alone; (4) per-code-point command normalisation; second update:
mid-track rejoin, clock jump reset, framing `malformed`/`fatal` vectors tested.

Items 1 (screen-off + soak), 5 (overlay taps/drag) and most of 4 (sync tuning) are done — see
section 2. What is left:

1. **Hotspot test** (needs hands): Pixel hotspot + client on it; check NSD over SoftAP, that
   `ACCESS_LOCAL_NETWORK` does not block anything, and above all re-measure the screen-off RTT
   tail from section 2 — on the SoftAP it may simply not exist.
2. **Real ASR** with speech through the AirPods mic (needs a voice). The plumbing is proven in
   every state incl. locked/doze; only the recognition itself is unexercised. If the platform
   recognizer turns out to ignore the BT mic, the plan's sherpa-onnx fallback is still
   unimplemented.
3. **Main-thread stall on talk open and close** — see "Talk blocks Main" in section 4. Needs a
   decision, not just a tweak; it is what makes the music sync limp for ~20 s after every talk.
4. **Finish bench-testing the `"unavailable"` flow.** The parts done on device are the volume
   rules (verified) and the codec drop of `volumeUp`/`volumeDown` (verified). The two
   `"unavailable"` paths are covered by `TalkControllerTest` and code review but were **not**
   exercised against `tools/peer`, because both need a *host-side* talk trigger and by then the
   media button belonged to another app and the overlay was off-screen in landscape. To finish:
   play a track through our app so our MediaSession wins the media button, then
   - peer `unavailable` + `KEYCODE_MEDIA_PLAY_PAUSE` -> expect `talk.open{by:"host"}`, the peer's
     `talk.close{by:"client",reason:"unavailable"}`, talk closed, music resumed, error earcon here;
   - `pm revoke com.kivan.motoparty android.permission.RECORD_AUDIO` (this force-stops the app;
     relaunch it), then peer `talk` -> expect exactly `talk.close{by:"host",reason:"unavailable"}`
     and `state.talk` still false. Re-grant afterwards.
5. **Clamp the overlay position** to the display (the landscape bug in section 2).
6. Audio quality by ear (helmet, at speed) and a ride test.
7. Nice-to-have: earcons into the voice stream, so the other rider hears them too.

## 4. Gotchas and decisions

- **Talk blocks Main for ~2.7 s, on open *and* close** (measured: `talk.close` left the host at 20:31:05.675,
  `AudioRouter: back to media mode` logged at 20:31:08.401). In `LinkHost.applyTalk`'s Close
  branch, after the messages go out, `VoiceEngine.stop()` joins two audio threads (up to
  500 ms each) and `AudioRouter.exitCall()` does `clearCommunicationDevice()` +
  `am.mode = MODE_NORMAL`, all on Main. The client is unaffected — `talk.close`, `state` and
  the resume `music.play` are all sent first, by design, and `ControlServer`'s read, write and
  6 s liveness watchdog loops all run on `Dispatchers.IO`, so a Main stall cannot lose a frame
  or trip a false link loss. The **host's own** player suffers: the
  resume is a `scope.launch` on Main scheduled at `now + resumeLeadMs` (1500 ms default), so a
  2.7 s stall makes the host start ~1.2 s late, which the next drift check turns into a
  re-seek. Self-correcting, but audible on the host.
  Not fixed on purpose: the obvious fix (run the teardown off Main) breaks the ordering that
  `AudioRouter.depth` and `VoiceEngine.running` rely on if talk is re-triggered immediately.
  Doing it properly means serialising all route changes onto one dedicated dispatcher — a real
  change to the riskiest subsystem, and it cannot be regression-tested without a second person
  to talk to. Ask the coordinator first.
- Toolchain installed (user level): `sdkmanager "ndk;29.0.14206865" "cmake;3.31.6"`.
  `ndkVersion` and CMake `version` are pinned in `app/build.gradle.kts`.
- libopus: NDK route works; no Concentus. Source is fetched by `tools/fetch_opus.sh`
  (1.5.2, SHA-256 pinned) into the git-ignored `app/src/main/cpp/opus/`; the build fails with
  a pointer to the script if missing. DRED/OSCE off.
- NewPipeExtractor from JitPack, `v0.26.5` (latest 2026-08-15), repo restricted to group
  `com.github.TeamNewPipe` in `settings.gradle.kts`. No PoTokenProvider; itag 140 URLs worked.
  Album names come back as "Album – X"; the prefix is stripped. googlevideo downloads need the
  UA of the client in the URL's `c=` param (`TrackCache.userAgentFor`).
- AGP 9 has Kotlin built in; the Kotlin version comes from the buildscript classpath (copied
  from ~/Work/Pronounce). `compileSdk 37` + `compileSdkMinor 2`. Kotlin needed explicit types
  on `LinkHost`'s mutually referencing properties ("recursive problem").
- `./gradlew test --tests X` fails ("Unknown command-line option"); use
  `./gradlew :app:testDebugUnitTest --tests X`. Network tests need `-Pnetwork`.
- Unit tests read fixtures via system property `motoparty.fixtures` set in
  `app/build.gradle.kts`; `unitTests.isReturnDefaultValues = true` lets `android.util.Log`
  no-op, which is how `ControlServer` is tested on the JVM.
- Android: never do socket I/O on the main thread (StrictMode throws; the first bench lost
  1.3 s to this). `LinkHost` runs on Main; `ControlServer.send` is queue-only.
  `DatagramSocket(null)+bind` failed on device ("port out of range:-1"); use `DatagramSocket(port)`.
  `AudioRouter.enterCall/exitCall` block main for ~0.5–1.3 s, so messages are sent before them.
- On `Main.immediate`, completing a `CompletableDeferred` can run the waiting coroutine inline
  (caused a duplicate music.play; see `MusicController.onClientReady`).
- A2DP: play()/seek restart costs ~350–700 ms before the position moves, so PROTOCOL's
  "re-seek > 80 ms" oscillated; replaced by speed nudging (documented deviation; tell the
  coordinator/iOS side).
- Device: `adb -s 192.168.1.100:5555`; the phone drops off Wi-Fi at times (ping fails, adb
  offline) — wait and `adb connect 192.168.1.100:5555`. Permissions:
  `pm grant com.kivan.motoparty android.permission.{RECORD_AUDIO,POST_NOTIFICATIONS,BLUETOOTH_CONNECT,ACCESS_LOCAL_NETWORK}`
  and `appops set com.kivan.motoparty SYSTEM_ALERT_WINDOW allow`. The user's AirPods are
  connected to the Pixel: bench audio plays in their ears, keep tests short and `pause` at the end.
- Service is not exported; drive triggers with `input tap` on the COMMAND/TALK buttons
  (find bounds with `uiautomator dump`) or `input keyevent KEYCODE_MEDIA_PLAY_PAUSE` (TALK) /
  `KEYCODE_MEDIA_NEXT` (voice command).
- The scratchpad `bench.py` from this session is obsolete; use `tools/peer`.
- **`KEYCODE_MEDIA_PLAY_PAUSE` is only a TALK trigger while *our* MediaSession is the media-button
  session.** After a reinstall with an empty queue, `dumpsys media_session` showed
  `Media button session is app.revanced.android.youtube`, and the keyevent went there and started
  a video in the user's AirPods instead of opening talk. Always check
  `dumpsys media_session | grep 'Media button session'` before using that keyevent, and play a
  track through our app first if it is not ours.
- **The phone is the user's.** During this session they picked it up, rotated it to landscape and
  started a video. Check `dumpsys power | grep mWakefulness` and
  `dumpsys media_session` before sending input events or playing audio, and put the screen back
  the way you found it (`KEYCODE_SLEEP` if it was off — that also re-locks it).
- **Almost everything can be driven from adb**, including the states that look like they need
  hands: `input keyevent KEYCODE_SLEEP` locks the phone (`dumpsys power` -> `mWakefulness=Dozing`),
  `dumpsys deviceidle` shows whether deep doze (`mState=IDLE`) was reached, `wm dismiss-keyguard`
  unlocks it again (this phone reports `deviceLocked=0`), the overlay's window frame comes from
  `dumpsys window windows | grep -A200 'Window{<id>'` so `input tap`/`input swipe` can hit its
  zones, and `dumpsys power`/`dumpsys wifi` prove the wake and Wi-Fi locks are held.
- Only two things genuinely need a human: **speaking** (real ASR, audio quality) and
  **switching the phone to its hotspot** (adb runs over the Wi-Fi that would change).
- Driving the peer's stdin from a script: `scratchpad/drive.sh` in this session piped
  `"<delay> <command>"` lines into `uv run motoparty-peer client`. Trivial, but it makes the
  benches repeatable; re-create it if it is gone.

## 5. First three things to do

1. `./gradlew assembleDebug test` (expect 65/63/2), `adb -s 192.168.1.100:5555 install -r
   app/build/outputs/apk/debug/app-debug.apk`, re-grant the four permissions + the
   `SYSTEM_ALERT_WINDOW` appop, and rerun the peer bench (`say play album …`, `talk`, `pause`)
   to confirm the baseline in section 2.
2. Take the coordinator's answer on the three protocol gaps above, especially gap 1
   (client "louder" currently changes the **host's** volume) — that one is a live misbehaviour,
   not just a missing feature.
3. Everything else left needs the user's hands; the checklist is section 6.

## 6. Needs the user's hands

Nothing here can be done over adb. In rough priority order:

1. **Hotspot link.** Turn on the Pixel's 5 GHz hotspot, join the laptop to it, then
   `cd tools/peer && uv run motoparty-peer client --no-audio` (no `--host`, so it exercises
   Bonjour-then-sweep discovery the way the iPhone will). Check: the host is found, `talk` works,
   and `stats` RTT with the phone's **screen off** — that last number is the one that matters,
   because on home Wi-Fi the screen-off tail was p99 256 ms (section 2) and the SoftAP may not
   have it at all.
2. **Real speech.** With the AirPods in, press the phone's TALK/COMMAND overlay zone (or
   `KEYCODE_MEDIA_NEXT`) and actually say "play album abbey road", then "next", then "louder".
   Watch `adb logcat --pid=$(adb -s 192.168.1.100:5555 shell pidof com.kivan.motoparty)`:
   a successful run logs `command: …` rather than `recognition failed: error 7`. This is the
   only way to learn whether the platform recognizer takes the Bluetooth mic.
3. **Audio quality by ear**, ideally in the helmet: does the 16 kHz/24 kbps Opus sound good
   enough, and is the "live" earcon audible after the A2DP->HFP switch?
4. **Power-button lock**, for completeness: `KEYCODE_SLEEP` was enough for every test here, but
   a real power-button press is what the rider does.
5. **Ride test** (plan phase 4): 30 min mixed speed, battery drain per hour, hotspot stability.
