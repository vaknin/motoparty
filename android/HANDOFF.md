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
| `core/JitterBuffer.kt` | done per the latest spec: talk-spurt start plays `target` ms after arrival; underrun = packet after its slot, +20 ms at most once per spurt; -20 ms after 10 s; changes apply at next spurt; keepalive seqs never count as loss; seq-contiguous ts jump = silence; FEC/PLC; brief PLC then silence on an empty buffer. A packet that came too late to play also counts as "seen" for the silence-gap test (fixed 2026-09-19 night). Per-talk counters for `talk stats` (layer 2, section 3 item 3) |
| `core/CommandParser.kt` | done per the latest spec (per code point, U+2019 -> `'`, keep L*/M*/N*); `Command.End` since 2026-09-29 |
| `core/FirstPhrase.kt`, `core/CommandEffect.kt` | new 2026-09-29, pure, tested: the first-phrase rule — opener / other / solo, the 8 s window from the live earcon, `isSpent` (`FirstPhraseGate`, replaced the wake word the same day) — and "command + talk state → effect" (`CommandEffect`). Section 2, "Commands inside talk" |
| `link/TalkController.kt` | done (pure state machine: trigger, link loss, `onCommandClose(by)` for a spoken `play`/`resume`/`end`; the 20 s silence close of F6 was removed 2026-09-29) |
| `link/ControlServer.kt` | done. One writer coroutine per connection (socket writes on main threw NetworkOnMainThreadException), pong answered on the reader thread, second hello replaces client (old one gets `bye`), 6 s liveness watchdog |
| `link/VoiceSocket.kt` | done. UDP 47801, peer = source of last valid packet from the control client's IP, running 16 kHz `ts` clock from a random start (`currentTs()`), keepalive every 1 s idle carrying current ts, shared seq |
| `link/Discovery.kt` | done (NSD `_motoparty._tcp`, TXT proto/voice/http) |
| `audio/opus_jni.c` + `cpp/CMakeLists.txt` + `audio/Opus.kt` | done. libopus 1.5.2 static, VOIP/24 kbps/FEC 10 %/DTX/complexity 8 |
| `core/TalkStats.kt` | done. Pure: the `talk stats` loss arithmetic and the exact line format (layer 2) |
| `audio/VoiceEngine.kt` | done. Since F9a the capture loop also measures each raw frame's peak, drives `MicLive` (routing listener on its own `voice-route` thread + a `routedDevice` re-read every 25 frames while the input is not SCO yet), reports `onMicLive` once per `start` and keeps `micLiveAtMs` (carried across a collapsed re-open like `captureUpAtMs`), and prints the `mic trace:` / `capture routed to …` diagnostics — section 2, "F9a". Since F9c it waits (bounded) for SCO before opening the recorder on an SCO route, and re-opens a recorder that migrated onto SCO from another input (`CaptureReopen`) — section 2, "F9c". Logs one `talk stats:` line per talk at `stop` (built by `TalkStats`). Frames with `OPUS_GET_IN_DTX == 1` are not sent; every sent/received kind-1 packet = activity. `start`/`stop` belong on the audio thread. Each `start(onFailed)` is a session (an `AtomicReference`, not a `running` flag): a loop that outlives `stop`'s 500 ms join cannot carry on under the next `start`, and only the first failure of a still-current session reports, to that session's `onFailed` |
| `audio/AudioRouter.kt` | done (ref-counted MODE_IN_COMMUNICATION + setCommunicationDevice, prefers BLE headset > SCO > wired > USB). Audio-thread only; `selectedDevice` is published from there instead of queried from Main. `enterCall` counts itself before it can throw, so every caller pairs it with `exitCall` regardless; `exitCall` sets `MODE_NORMAL` in a `finally`; `exitAll` is the shutdown backstop. Two callbacks on the audio thread: `onRouteHeld` (depth 0 → 1, before the blocking work) and `onRouteReleased(wasSco)` — F8's invalidation and F9b's two edges. **Stage C will have to change `preference()`**: it ranks wired (2) and USB headset (3) *below* both Bluetooth types, and has no entry at all for `TYPE_USB_DEVICE` / `TYPE_USB_ACCESSORY` |
| `audio/LiveCue.kt` | done, unit-tested (`LiveCueTest`, 12). Pure: when the "live" earcon may play — first captured frame **and** the route really up (Bluetooth call audio really flowing, for an SCO headset) **and**, since F9a, the headset's own mic signal arriving (`MicLive`); the last two only for an SCO route. One fire per open, 3.5 s fallback timer (2.5 s until F8). See section 2, "F7", "F8" and "F9a" |
| `audio/MicLive.kt` | done, unit-tested (`MicLiveTest`, 8). Pure: the F9a condition (c) — the recorder is routed to a Bluetooth SCO *input* and, after that moment, `FRAMES_NEEDED` = 10 consecutive 20 ms frames have a peak above `PEAK_THRESHOLD` = 16. Fed by `VoiceEngine`'s capture loop; the only live-earcon signal that has travelled back from the earpieces. Constants to be tuned from the `mic trace:` line — section 2, "F9a" |
| `audio/ScoWatch.kt` + `audio/ScoRule.kt` | done. Is Bluetooth call audio really flowing? Cached off Main. Since F8 (device-measured) the source on API 31+ is `addOnCommunicationDeviceChangedListener` (`type == TYPE_BLUETOOTH_SCO`), seeded once from `getCommunicationDevice()`, invalidated synchronously when `exitCall` clears the route (`onRouteReleased`); the legacy broadcasts are logged `[not used]` there and only drive below API 31. Since F9b a second, **raw** `onDevice` callback reports *every* framework dispatch before that dedupe, which is what `MediaCue` waits on. `ScoRule` is the pure part (which API level, which device types, the log spelling — now including the USB and built-in types `DeviceRoster` needs), unit-tested (`ScoRuleTest`, 5) — section 2, "F8" |
| `audio/MediaCue.kt` | done, unit-tested (`MediaCueTest`, 16). Pure: when a sound that plays on the **media** route may play (F9b) — CLOSED, ERROR, the OK of a local volume change, the spoken replies. `exitCall` returned at depth 0, **and** for an SCO route a communication device other than `bt_sco` reported after it; 2 s fallback; a re-opened talk drops a stale CLOSED; off SCO it plays on the same Main turn (`played +0 ms`). Line: `media cue: closed, released +6 ms, device earpiece +415 ms, played +415 ms (both)` |
| `audio/PcmDump.kt` + `audio/Wav.kt` | done, unit-tested (`PcmDumpTest` 7 + `WavTest` 4). Stage A of the wired-mic plan: the capture loop's own PCM to a WAV file, one per talk, behind the `captureDump` debug setting (off by default). Pre-allocated pool + bounded queue + private daemon writer thread, **drop and count** on overflow — the `voice-capture` thread never blocks on I/O and never allocates on the frame path; `close()` does not join. Capped at 40 MB. Files under `files/captures/`, pullable without root. Line: `capture dump: <name>.wav, N frames, X.X s, N bytes, N dropped` |
| `audio/DeviceRoster.kt` + `audio/DeviceWatch.kt` | done, unit-tested (`DeviceRosterTest`, 10). Stage A: `getDevices()` + an `AudioDeviceCallback` on their own daemon thread (never Main — both are binder calls into the audio service). `DeviceRoster` is the pure part: sorted, deduped, ids kept (what `setPreferredDevice` will take in Stage C). Lines `audio devices: in … · out …` and `audio devices +:` / `-:` on every plug, each followed by the full roster; short form in the new "Audio devices" UI row |
| `audio/AudioThread.kt` | done, reviewed, unit-tested (`AudioThreadTest`). The single serial thread every route change and voice start/stop runs on (section 4, "Talk no longer blocks Main"). Not yet run on the device |
| `audio/Earcons.kt` | done (generated tones: LIVE, CLOSED, OK, ERROR; LISTEN went with the wake word) |
| `music/Catalog.kt`, `OkHttpDownloader.kt` | done (NewPipeExtractor v0.26.5; YT Music songs/albums/playlists, artist = top 20 songs; falls back to plain YouTube search; resolves progressive M4A, itag 140 preferred) |
| `music/TrackCache.kt` | done (1 GiB LRU by mtime, 1 MiB Range chunks, UA chosen by the URL's `c=` client, shared in-flight downloads, prefetch) |
| `music/TrackServer.kt` | done (hand-rolled HTTP, GET/HEAD `/track/<id>.m4a`, single Range, 404 otherwise) |
| `music/Player.kt` | done (ExoPlayer, no auto audio focus, MediaSession with `onMediaButtonEvent`, `speed`). Outside controllers (KDE Connect, lock screen, watch): `onConnectAsync` accepts every controller with full commands, because media3's default gives an untrusted one read access only, and KDE Connect is untrusted here (package visibility hides it: "Package org.kde.kdeconnect_tp doesn't exist"), which is why its pause did nothing. `SessionPlayer`, a `ForwardingPlayer`, turns play/pause/stop/next/previous into `RemoteAction`s that `LinkHost` routes like the client's `music.control`, so they act on both phones; seek, speed and playlist commands are not offered |
| `music/SyncController.kt` | done. Prepared start with a learned start-up latency lead, check 2 s after start then every 10 s, 80 ms–1 s corrected by speed nudge (≤5 %), >1 s re-seek. The lead is now learned with a damped 1/4 step (`LEARN_DIVISOR`), not a mean of two, which was ringing on A2DP. `PlayerControls` was split out of `Player` so all of this is unit-tested. Two learned leads now: `startLatencyMs` (warm) and `coldStartLatencyMs` (a start into a route that was just rebuilt — the resume after talk or a voice command); `hold`/`release` are nesting. Layer 2 logging: `nudge done:` and the `trace:` lines (section 3 item 3); logging only. Since D2: every drift figure is a 9-sample/2 s median, and 80 ms..1 s is corrected only after a second, agreeing reading (section 3 item 3, "D2 bench") |
| `music/MusicController.kt` | done (queue, load → ready ≤8 s / music.error → play at now+300, pause/resume, next/previous, talk pause + resume at now+resumeLeadMs, duck mode, mid-track join on client connect, prefetch + music.load of next) |
| `voicecmd/TalkRecognizer.kt` + `audio/PcmTee.kt` | new 2026-09-29 (replaces the one-shot `Transcriber`, deleted): in-talk recognition on the talk's own capture. Device-unverified |
| `voicecmd/Announcer.kt` | done (TTS, USAGE_ASSISTANT, earcon first; in a talk USAGE_VOICE_COMMUNICATION + call-route earcon; `spokeWithin` for the solo echo guard) |
| `LinkHost.kt` | done: all wiring and protocol decisions, main thread, `guarded{}` around event loops. Talk audio work goes through `AudioThread`; `talkSession` drops late callbacks of an earlier talk |
| `LinkService.kt` | done (FGS types microphone\|mediaPlayback\|connectedDevice, drops microphone if SecurityException; wake + Wi-Fi low-latency locks; notification actions Talk/Stop, plus "Show buttons" while the overlay is off; the "Command" action went with the command mode on 2026-09-29). Since F6 it also collects `settings.overlayEnabled` and starts/stops the overlay itself, so the switch works with the activity gone |
| `overlay/OverlayService.kt` + `overlay/OverlayPlacement.kt` | done. Since 2026-09-29 one 120×120 dp TALK button (the MUSIC zone is gone). The position is stored as a fraction of the free travel and clamped on restore, on every layout, on rotation and during the drag itself; the landscape bug is fixed in code. Since the D4 overlay bench (2026-09-20) the clamp runs against the **usable** area, not the display bounds: `params.x/y` are relative to the window's parent frame, which excludes the status bar / cutout / navigation bar (rotation 0: `parent=[0,132][1080,2337]` on 1080x2400), so a far-corner drag used to end 132 px below the screen. `usableSize()` now subtracts `currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(systemBars \| displayCutout)` (API 30+; `displayMetrics` below that), and `OverlayPlacement.usable()` is the pure arithmetic for it. `ACTION_CANCEL` now ends the drag and saves the position like `ACTION_UP` (it never fires a trigger) — before, a cancelled drag was dropped and the next layout pass snapped the buttons back. Both are device-unverified. F6 (2026-09-20) adds drag-to-dismiss: a second, untouchable X window at the bottom centre while a drag runs, and dropping the buttons on it restores the pre-drag position, sets `overlayEnabled=false` and stops the service (see section 2, "F6", for the geometry, the hit rule and the window titles). `OverlayPlacement` is pure and unit-tested (21) |
| `trigger/Trigger.kt` | done |
| `ui/MainScreen.kt`, `MainActivity.kt` | done (status, permissions, TALK/COMMAND, now playing/queue, search, settings, log). Still not seen rendered: the one screenshot attempt caught another app in the foreground |
| `Settings.kt`, `Hub.kt`, `MotopartyApp.kt` | done |
| `tools/fetch_opus.sh` | done |

Tests (`app/src/test/...`), 153 in all by the last run (the list may lag), 2 skipped: `CodecTest` (14), `ClockEstimatorTest` (1),
`VoicePacketTest` (3), `CommandParserTest` (2), `JitterBufferTest` (20), `TalkStatsTest` (4),
`TalkControllerTest` (7), `ControlServerTest` (7, real loopback sockets), `TrackServerTest` (5),
`SyncControllerTest` (23), `MainLagTest` (3), `StepTimerTest` (2), `TalkAudioTest` (7),
`LiveCueTest` (12: the live-earcon rule — normal order, capture before the link, the link before the
route, all three signals in each arrival order, an SCO flap, a non-Bluetooth route (needs neither
link nor mic signal), the timeout fallback, a headset whose mic signal never arrives, close before
ready, stale sessions, a collapsed re-open, and the exact `live cue:` line),
`MicLiveTest` (8: the F9a "the headset's own mic is delivering" run — silence then signal, signal
before the SCO-routed moment does not count, one silent frame restarts the run, the threshold itself
is silence, fires once and survives a later flap, a route that leaves SCO before the mic was live
starts over, `reset` is a new engine, and the tunable K/threshold),
`ScoRuleTest` (4: the F8 source per API level, only a routed `TYPE_BLUETOOTH_SCO` counts as
connected, a BLE headset does not, and the device spelling in the log line),
`OverlayPlacementTest` (22: travel/clamp/fraction round-trips, the usable area from bounds + insets,
the D4 far corner on screen in all four rotations, and the F6 X target — where it sits, that a
drop at the bottom centre hits it in portrait *and* in landscape, where the window is more than
half the usable height, and the F7 share of the vertical travel that counts, 10 % / 39 %), `AudioThreadTest` (6: order and one-at-a-time, failure to the shared
handler on the caller's scope, per-post handler, failure delivered before the job completes,
cancelled waiter does not cancel the work, post after shutdown), `CatalogNetworkTest` (2, skipped
unless `-Pnetwork`). Every fixture section is consumed by a
test (`messages`/`valid`/`invalid`/`unknown`/`malformed`/`fatal`/`steps`/`conversions`/`cases`);
none is silently skipped.

## 2. Verified, and how

- `./gradlew assembleDebug testDebugUnitTest` -> BUILD OK, 153 tests, 151 pass, 2 skipped
  (network), 0 fail (after F9a, 2026-09-20; was 143 after F8, 139 after F7, 128 after F6, 123 before
  it, 115 after the D1-bench-4 Main-blocking fix, 111 after the D2 filtered-drift fix).
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
- **Overlay rotation bench D4** (`tools/bench/results/2026-09-20-d4-overlay-2/summary.txt`,
  Pixel 8 1080x2400). At rest the overlay is fully on screen and tappable in all four rotations —
  the landscape bug above is really fixed. But every `dragged-far` row was off screen, because the
  clamp used the display bounds while `params.x/y` are relative to the window's parent frame
  (rotation 0 `parent=[0,132][1080,2337]`, `mAttrs` y=365 vs. real frame y=497): the far corner
  landed at `766 1968 1080 2532`, 132 px below the display (rot 90 `2218 590 2532 1154`,
  132 right + 74 below; rot 180 and 270, 74 below). Every `after-release` row also snapped back to
  the pre-drag frame, i.e. the drag was never saved — `DragOrTap` handled only DOWN/MOVE/UP, and
  `input swipe` to an off-screen point ends in `ACTION_CANCEL`.
  **Fixed in code, unverified on the device:** the clamp and `place()` now run against the usable
  area (bounds minus `getInsetsIgnoringVisibility(systemBars | displayCutout)`, API 30+, with
  `displayMetrics` below), and `ACTION_CANCEL` ends the drag and saves the position exactly as
  `ACTION_UP` does — without ever firing a trigger. `OverlayPlacement.usable()` holds the
  arithmetic and the unit tests use these bench numbers.
  **The next `tools/bench/overlay_rotation.sh` run must show:** every `dragged-far` row `on
  screen = yes` in all four rotations (rot 0 should end at y 2337, not 2532). Then the
  `after-release` rows tell whether CANCEL was the whole story: staying at the far corner means the
  save now happens; snapping back to the pre-drag frame means something else still drops the drag
  (look for a `place()` from a layout pass that beats the save, and note that a real finger may
  never send CANCEL at all — that path is still only reasoned about, not seen).
  **The drag never persisting was that other cause** (`2026-09-20-d4-overlay-3`: in-screen drags
  snapped back too and `shared_prefs/overlay.xml` never changed mtime, so CANCEL was not it). The
  layout listener calls `place()`, which writes `params.x/y` back from the stored `fx/fy`; each
  ACTION_MOVE's `updateViewLayout` triggers exactly such a layout pass, so every move was undone
  and ACTION_UP then "saved" the fraction that was already stored — an unchanged `putFloat`, which
  SharedPreferences drops without writing the file. Fix: ACTION_MOVE now updates `fx/fy` from the
  clamped `params.x/y` as it drags, so `place()` is a no-op mid-drag (its `x == params.x` early
  return) and a rotation during a drag still places the window proportionally; UP/CANCEL only
  persist. This needs `position(fraction(x)) == x` exactly or `place()` would fight the finger by a
  pixel — it does, pinned for every x in 0..travel at the Pixel 8 sizes by
  `every pixel position round-trips exactly` in `OverlayPlacementTest` (float error there is ~1e-4,
  far under the half pixel `roundToInt` needs).
  **So the next `overlay_rotation.sh` run must also show** `after-release` = the `dragged-far`
  frame, not the resting frame, in all four rotations — and the script's end-of-run restore drag
  has to really bring the overlay back, instead of appearing to work because nothing ever moved.

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

### F6 (2026-09-20) — drag the buttons onto the X to put them away; 20 s silence close

The rider's complaint: the floating TALK/MUSIC buttons could not be got rid of, "even if I close
the app". Two causes — there was no gesture for it, and only `MainActivity` watched the
`overlayEnabled` setting, so with the activity gone the switch changed nothing. Code only,
**nothing below is device-verified**.

- **Drag to dismiss** (`overlay/OverlayService.kt`). When a drag starts (past the touch slop) a
  second `TYPE_APPLICATION_OVERLAY` window appears: a 112 dp circle with an ✕,
  `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_NO_LIMITS`, so it can never take the drag
  away from the buttons. It highlights (dark grey at 0.85 scale -> red, white ring, full size)
  while the buttons are over it, and is removed on UP, on CANCEL and in `onDestroy`.
- **Hit rule** (pure, `OverlayPlacement.overDismiss`, tested): the dragged window's centre x is
  over the target **and its bottom edge has reached the target's vertical centre**
  (`(top+bottom)/2`), i.e. the target grown downwards to the bottom of the usable area. It cannot
  be plain centre-in-rect: the clamp parks the window's bottom edge *at* the usable bottom, i.e.
  below the target's own bottom edge, and in landscape the buttons window (564 px) is more than
  half the usable height (1017 px), so a centre-in-rect target would have ~12 px of reachable
  travel. **Since F7 the vertical line is the target's centre, not its top edge** (it was the top
  edge until 2026-09-20 F7, which made 72 % of the landscape travel count — a drag released
  anywhere near the horizontal centre hid the buttons almost regardless of height). The line sits
  a fixed 178 px above the usable bottom, so the same 178 px of travel count in every rotation:
  **39 % of the vertical travel in landscape, 10 % in portrait** (was 72 % / 20 %). The other half
  of what makes the gesture deliberate is the horizontal condition (the centre must be in the
  middle 294 px of 2268, ~15 % of the horizontal travel).
- **On a drop on the X**: the drag is *not* saved — `fx`/`fy` are restored to the values they had
  at ACTION_DOWN (nothing was written to `overlay.xml` mid-drag, so the stored position is still
  the old one), haptic feedback, `overlayEnabled=false` through `MotopartyApp.instance.settings`,
  `stopSelf()`. The F5 fix in the MOVE branch (fx/fy follow the finger so the layout listener's
  `place()` is a no-op mid-drag) is untouched.
- **ACTION_CANCEL never dismisses** (decision): the system taking the gesture away is not the
  rider letting go. CANCEL ends the drag and saves the position exactly as before F6.
- **The service follows the setting** (`LinkService.onCreate`): it collects
  `settings.flow.map { it.overlayEnabled }.distinctUntilChanged()` and calls `maybeStartOverlay`
  plus a notification re-post. A `StateFlow` replays, so this also does the initial start, and the
  now-redundant collector in `MainActivity` was removed (the permission path is unchanged: the
  activity's `onResume` still calls `maybeStartOverlay`, which is what picks up a newly granted
  "draw over other apps").
- **"Show buttons" notification action** (`LinkService.notification`): Android shows three
  actions, so while the buttons are hidden (and `canDrawOverlays`) the middle slot shows
  "Show buttons" instead of "Command" — Talk/End talk and Stop always stay. It fires
  `ACTION_SHOW_OVERLAY` ("com.kivan.motoparty.SHOW_OVERLAY"), handled in `onStartCommand`, which
  only writes `overlayEnabled=true`; the collector does the rest. The `intent?.action == null ->
  startInForeground()` path is untouched (the new action is non-null, so it does not re-claim the
  FGS types).
- **`TalkController.SILENCE_MS` is now `20_000L`** (was 10 s; user decision of today; PROTOCOL.md
  is the coordinator's). `TalkControllerTest` and the 10 s mentions in `README.md` and
  `VoiceEngine.kt` were updated. Section 2's old "10 s silence close, on device at last" bench
  entry is history and was left alone — but the **next silence bench must wait 20 s**
  (`tools/bench/beeps.sh` takes `SILENCE_S`; the coordinator owns `tools/`).

**Window titles, for `dumpsys window windows`.** The buttons window is now titled
`com.kivan.motoparty:buttons` and the X target `MotopartyDismiss`. This was chosen so that
`tools/bench/bench.py cmd_frame` (which takes the **first** `Window #n Window{...}` block whose
header contains the package *and* has `ty=APPLICATION_OVERLAY`) keeps picking the buttons: the
buttons' title still contains the package string, the X's deliberately does not, so the X is
invisible to `bench.py frame` even in `overlay_rotation.sh`'s mid-drag dump (that script dumps
`dumpsys window windows` 4 s into a 5 s swipe — with a package-bearing title the X, added last and
therefore likely higher in z-order, could have been picked instead of the buttons). `bench.py` was
**not** edited. To watch the X itself, grep for `MotopartyDismiss`.

**The X target's geometry** (so a bench script can compute a drop point), all in the window's
inset parent frame — bounds minus the `systemBars|displayCutout` insets, i.e. the same frame
`params.x/y` use; add the frame's origin to get display coordinates:

    size   = 112 dp  (294 px at density 2.625), square, circular background
    left   = (usableW - size) / 2          right  = left + size
    bottom = usableH - 12 dp (31 px)       top    = bottom - size

Portrait (usable 1080 x 2205): `[393,1880][687,2174]`. Landscape (usable 2268 x 1017):
`[987,692][1281,986]`. A drop counts when the buttons window's centre x is in `left..right` and
its bottom edge is at or below `(top+bottom)/2` (portrait 2027, landscape 839; F7 — it was `top`
before) — dragging to the bottom centre of the screen always satisfies both.

**Verify on the device (F6):**

1. Drag the buttons: the ✕ appears at the bottom centre while the finger is down, and disappears
   on release. `dumpsys window windows | grep -A2 MotopartyDismiss` mid-drag shows its frame.
2. Drag them *onto* the ✕ and let go: the ✕ turns red as they come over it, the buttons vanish,
   the notification's middle action becomes "Show buttons".
   `adb shell run-as com.kivan.motoparty cat shared_prefs/settings.xml` must show
   `<boolean name="overlayEnabled" value="false" />` (file `settings.xml`, key `overlayEnabled`;
   the *position* still lives in `shared_prefs/overlay.xml`, keys `fx`/`fy`).
3. `shared_prefs/overlay.xml` must be **unchanged** by that drag (same `fx`/`fy`, same mtime):
   a dismissed drag is not a move.
4. Tap "Show buttons" on the notification: the buttons come back **at the position they had
   before the dismissing drag**, `overlayEnabled` is `true` again and the action goes back to
   "Command". The app's own switch must still do the same with the app open.
5. A drag released **anywhere else** still saves: the window stays where it was dropped and
   `fx`/`fy` in `overlay.xml` change (the F5 regression test — `overlay_rotation.sh` covers it).
6. Swipe the app away in recents (the foreground service and its notification stay; do **not**
   `am force-stop`, which kills the service too): with no activity left, both the drop-on-X and
   "Show buttons" must still work. That is the whole point of moving the collector into
   `LinkService` — and it is the rider's original complaint ("even if I close the app").
7. Talk with nobody speaking now closes after **20 s**, `talk.close{reason:"silence"}`.

### F7 (2026-09-20) — the "live" beep now means "your mic is live, talk now"

User decision of today: **no pre-roll buffer** (words spoken before the AirPods mic delivers were
never captured and cannot be saved) — instead the go-beep must be truthful. Same tone, no protocol
change (PROTOCOL.md "Talk flow" step 2 already only says: play the "live" earcon when the mic is
open). Code only, **nothing below is device-verified**.

**What was wrong.** `LinkHost` fired LIVE at `opened.join()` + a fixed `LIVE_EARCON_DELAY_MS =
400`, i.e. 400 ms after `enterCall` + `VoiceEngine.start` *returned*, which has nothing to do with
the microphone. On `tools/bench/results/2026-09-20-d1-talk-5` (AirPods): `enterCall` takes
199–563 ms, the **SCO link is up 1.17–1.38 s after the press** (cycle 1: up at 0.97 s, dropped, up
for good at 1.88 s), and the **first captured frame comes 62–166 ms after SCO up**. So cycle 1's
earcon was issued ~630 ms *before* the link existed; it was only heard later because the output
blocks until the link is up. "First capture frame" alone is no better: in the fast re-open a frame
arrived **0.76 s before** SCO was up (the mic was delivering something, not the rider's voice).

**The rule now** (`audio/LiveCue.kt`, pure, 10 JVM tests). LIVE fires when **both** hold:
(a) the capture loop of this talk read its **first frame**, and (b) the **route is really up** —
for a Bluetooth SCO device that means the SCO audio link is connected; any other route (earpiece,
speaker, wired, `TYPE_BLE_HEADSET`) is up the moment `enterCall` returns. Exactly one fire per
open, and a fallback timer (**2.5 s** after the open, `LiveCue.TIMEOUT_MS`) plays it anyway so the
beep can never be missing. The old guards are kept: talk still open and `session == talkSession`.
Cases covered by the tests: normal order, capture before SCO, SCO before the route reports, an SCO
flap before the beep (cycle 1 — one beep, at the second connect), a non-Bluetooth route, the
timeout, close before ready (no beep at all), stale-session events, and a collapsed fast re-open.

**Wiring:**
- **`audio/ScoWatch.kt`** (new, next to `AudioModeWatch`). **Its source is superseded by F8 below:
  the classic broadcast turned out to be the only one that fires here, and it fires 534–1100 ms
  *before* the link exists, so on API 31+ the communication-device listener drives the earcon and
  the broadcasts are only logged `[not used]`.** As built in F7 it was the SCO link state cached off Main, on
  its own daemon thread `motoparty-sco` (the registration itself is posted there too). It listens
  to **two** broadcasts, because one of them may not exist on this phone:
  `AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED` (`SCO_AUDIO_STATE_CONNECTED`) — the classic
  signal, documented for the deprecated `startBluetoothSco()` path — and
  `BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED` (`STATE_AUDIO_CONNECTED`), sent by the Bluetooth
  stack itself (needs `BLUETOOTH_CONNECT`, which we hold). Neither can fire early: both describe
  the link, not the request for one. It logs one line per change, `ScoWatch: sco connected
  (ACTION_SCO_AUDIO_STATE_UPDATED|ACTION_AUDIO_STATE_CHANGED|sticky)`, which is how a bench run
  tells us which one is real here. **Why two:** Android 17 moved SCO management from the Bluetooth
  stack into the audio framework ("Audio Managed SCO",
  `source.android.com/docs/core/audio/sco-audio-mgmt`), where device state is reported through
  `AudioDeviceCallback` "instead of legacy broadcasts" — so on this exact phone the classic
  broadcast is doubtful, and the Bluetooth one is the backstop. If *both* stay silent, every talk
  beeps on the timer and says `(fallback)`; the fix would then be an `AudioDeviceCallback` on the
  SCO **input** device. (`AudioRecord.getRoutedDevice()` is **not** a candidate: the bench shows
  the input patch to `bluetooth-sco-headset-microphones` created 0.8 s *before* the link is up.)
- **`AudioRouter`** publishes `selectedType` (the `AudioDeviceInfo` type) next to `selectedDevice`
  and derives `needsSco`; both cleared by `exitCall`. The legacy (<31) `startBluetoothSco` branch
  counts as SCO too.
- **`VoiceEngine`** takes an `onCaptureUp(atMs)` callback, invoked once per `start` from the
  capture thread where the `capture up` line is logged — it only posts to Main, never blocks the
  loop — and keeps `captureUpAtMs`. That field is cleared by `start` only, so an engine **carried
  across a collapsed close/open keeps it**: the re-opened talk asks for it when its route job
  completes (no new first frame will ever come) and it counts as +0 ms.
- **`TalkAudio.ownerSession`** exposes which talk the running engine belongs to, so a capture-up
  callback lands on the session that owns the mic *now*.
- `LIVE_EARCON_DELAY_MS` is gone. `SCO_SETTLE_MS` (the recognizer's LISTEN earcon) is untouched.
  The LIVE earcon is still played through `LinkHost.earcon(LIVE, call = true)`, i.e. on the audio
  thread, in call mode (F3).

**One log line per talk, for the bench** (`Motoparty:` tag, `Hub.log`; `LiveCueTest.the log line
is what the bench parses` pins it), every offset in ms from the talk open:

    live cue: session <n>, capture up <+N ms|none>, sco <+N ms|n/a|unknown>, fired +<N> ms (both|fallback)

e.g. `live cue: session 8, capture up +1310 ms, sco +1240 ms, fired +1312 ms (both)`.
`capture up none` = no frame was ever read (only possible on a `fallback`); `sco n/a` = the route
is not a Bluetooth SCO one; `sco unknown` = the route never reported before the timer fired;
`sco none` = an SCO route whose link was never seen up. `sco +0 ms` = the link was already up when
this talk opened, i.e. a re-open that kept the route.

**Verify on the device (F7):**

1. One `live cue: …` line per talk open, and it says **`(both)`** with AirPods. `sco` should land
   at 1.1–1.4 s and `capture up` 60–170 ms after it; `fired` = the later of the two.
2. The beep is **heard after** the SCO link is up (the `bta_ag_sco.cc … -> BTA_AG_SCO_OPEN_ST`
   line in `logcat_all`) and never before it. Speaking right after the beep must be heard by the
   other phone in full — that is the whole point.
3. `ScoWatch: sco connected (…)` appears at all, and the source in the brackets tells which
   broadcast survives on Android 17 — note it in this file. **Answered:** only
   `ACTION_SCO_AUDIO_STATE_UPDATED`, 18/18, and it is too early to be trusted (F8).
4. Fast re-open (talk close + open ~300 ms apart, the `TalkAudio: re-open …: call route kept`
   case): **exactly one** beep per open, the second one immediately (`capture up +0 ms,
   sco +0 ms`), never two beeps for one open and never a silent open.
5. `(fallback)` must not appear with AirPods. If it does on every talk, neither broadcast fires —
   see the `AudioDeviceCallback` note above.
6. An SCO flap inside one talk still beeps once.
7. The closed / error / OK / LISTEN earcons are unchanged (still one `AudioTrack: stop(..)` line
   each, F5).

(Run on the device this morning: every talk did say `(both)` and `ScoWatch: sco connected
(ACTION_SCO_AUDIO_STATE_UPDATED)` did appear — but item 2 failed, the beep was still before the
link. *Why* is F8, next.)

### F8 (2026-09-20) — the live beep waits for the communication device, not the SCO broadcast

F7's gate was honest in shape and wrong in its input. Device run
`tools/bench/results/2026-09-20-t3-talk-f7` (Pixel 8 / Android 17 / AirPods Pro,
coordinator-verified):

- `ACTION_SCO_AUDIO_STATE_UPDATED` is the **only** source that ever appeared (18/18); the
  Bluetooth-stack broadcast never fired. But its "connected" arrives **534–1100 ms before** the
  stack's `SCO_state_change … ->[BTA_AG_SCO_OPEN_ST(0x06)]` in 9/9 talks: it reports the audio
  framework's *intent* to build a link, not the link. One talk beeped 648 ms before the first
  `OPEN_ST` and 1630 ms before the link that stayed up
  (`live cue: session 4, capture up +738 ms, sco +536 ms, fired +738 ms (both)`).
- In **5 of 8 talks the stack flaps**: `OPEN_ST` → `CLOSING` 27–47 ms later → `OPEN_ST` again
  ~0.8–1.0 s later. `ScoWatch` saw none of it.
- The honest signal is the framework's own dispatch, `AS.AudioDeviceBroker: Dispatch
  onCommunicationDeviceChanged: AudioDeviceInfo: type: bt_sco …`, which lands **+184…+530 ms after
  the LAST `OPEN_ST`, never before it, in every talk**; a non-SCO type (`earpiece`, …) follows every
  close. Timeline of one talk (s): open 661.472 · broadcast "connected" 662.008 · `OPEN_ST` 662.859 ·
  `CLOSING` 662.886 · `OPEN_ST` 663.841 · `onCommunicationDeviceChanged bt_sco` 664.290.

**What changed.** `LiveCue` and its rule are untouched (still: first captured frame **and** the
route really up, one fire per open, 2.5 s fallback, same 10 tests) — only what feeds "the route is
really up" changed.

- **`audio/ScoWatch.kt`**: on API 31+ the state comes from
  `AudioManager.addOnCommunicationDeviceChangedListener(executor, listener)` — connected = the
  reported device is non-null and `type == TYPE_BLUETOOTH_SCO`; anything else (null, earpiece,
  speaker, A2DP…) = disconnected. The executor is the `motoparty-sco` watcher thread's own handler,
  so nothing lands on Main and the registration (a binder call) is posted there as well. The two
  broadcasts are still registered and **logged only**, as
  `ScoWatch: broadcast sco connected (ACTION_SCO_AUDIO_STATE_UPDATED) [not used]`, so a bench run
  can keep lining the two timelines up; below API 31 they still drive, unchanged (minSdk 29, where
  `AudioRouter` also uses legacy `startBluetoothSco()`).
- **`audio/ScoRule.kt`** (new, pure, `ScoRuleTest`, 4 tests): which API level uses which source
  (`COMMUNICATION_DEVICE_SDK = 31`), which device types count as connected, and the device spelling
  used in the log line. **`TYPE_BLE_HEADSET` deliberately does not count as connected**: the flag is
  read only through `LiveCue`'s `needsSco` gate, which is on exactly when the selected route *is*
  `TYPE_BLUETOOTH_SCO` (`AudioRouter.needsSco`); a BLE headset, like every non-SCO route, is up as
  soon as `enterCall` returns and never consults the flag, so counting it here could only open an
  SCO talk's gate with a device that is not its link.
- **Initial state** (there is no callback for the state that already holds at registration): the
  watcher reads `getCommunicationDevice()` once, on the watcher thread, right after registering.
  Because the listener reports on that same handler, a change racing with the registration is queued
  *behind* that read, so it cannot be lost or applied out of order.
- **Stale state at the open edge** (`LinkHost` polls `sco.connected` when the route reports):
  `AudioRouter` now takes an `onRouteReleased` callback, invoked on the audio thread in `exitCall`'s
  `finally` right after `clearCommunicationDevice()`, and `ScoWatch.onRouteReleased()` sets the flag
  to false **synchronously** (`update` is `@Synchronized`, as two threads now reach it) and logs
  `ScoWatch: sco disconnected (call route released)`. The framework's own `earpiece` dispatch for
  that teardown arrives hundreds of ms later — possibly after the *next* talk's `enterCall` has
  already returned — which is the old "quick close→open that did not collapse may see the old link
  still connected" edge. Nothing sets the flag on `enterCall`: selecting the device is precisely the
  intent-not-link signal this entry removes.
- **The kept-route fast re-open is unaffected**, by construction: `TalkAudio: re-open …: call route
  kept` never calls `exitCall`, so the flag stays true from the link that is genuinely still up, and
  the re-opened talk beeps at once (`capture up +0 ms, sco +0 ms`), still exactly once.
- `LinkHost` wiring is otherwise unchanged (same `(connected, atMs)` callback shape), and the
  `live cue: …` line format is byte-for-byte the F7 one.

**Known consequence — the fallback may now win a flapping talk.** The honest signal is later than
the one it replaces: without a flap it should land ~1.6–1.9 s after the talk open (last `OPEN_ST`
≈ +1.4 s, dispatch +184…530 ms), but in the 5-of-8 flapping talks the last `OPEN_ST` is ~1 s later,
i.e. the dispatch is at ~2.4–2.9 s — the bench's one full example was **+2818 ms**, past
`LiveCue.TIMEOUT_MS` (2.5 s). Such a talk will beep on the timer and say `(fallback)`, i.e. slightly
early again. **Coordinator, same day: `TIMEOUT_MS` raised to 3.5 s** (from test 3's own numbers the
dispatch was +2.31…+2.82 s after the open in the flapping talks, +1.19 s without a flap; `LiveCueTest`
adjusted, 143 tests pass). The timer stays the "never silent" guarantee; a talk that only beeps after
~2.8 s is the flap's cost, and fixing the flap itself is not in scope here.

**Verify on the device (F8):**

1. Every talk's `live cue: …` line says **`(both)`** — see the caveat above; if a talk says
   `(fallback)`, check whether its SCO flapped and note the numbers here.
2. `ScoWatch: sco connected (communication device bt_sco)` appears once per talk and is **never
   earlier than the last `->[BTA_AG_SCO_OPEN_ST` of that talk** in `logcat_all` (expect
   +184…+530 ms after it).
3. The `live cue:` line, and the beep, come **after** that `ScoWatch` line.
4. One beep per open, never two, never none. The fast re-open (`TalkAudio: re-open …: call route
   kept`) still beeps immediately, `capture up +0 ms, sco +0 ms`.
5. Speaking right after the beep is heard by the other phone in full — the whole point.
6. `ScoWatch: broadcast sco connected (…) [not used]` lines are still there and still early; they
   are the comparison, not the source.
7. After each close, `ScoWatch: sco disconnected (call route released)` (or a `communication device
   earpiece|none` line) shows up, and no talk ever starts with a stale `sco +0 ms` unless its route
   was really kept.

### F9a (2026-09-20) — the live beep also waits for the headset's mic signal

**Why.** F8's gate is honest about *this phone* and that is not enough. Device run
`tools/bench/results/2026-09-20-t3b-talk-f8` (Pixel 8 / Android 17 / AirPods Pro,
coordinator-verified): the cue fires **+329…+598 ms after the Bluetooth stack's last
`BTA_AG_SCO_OPEN_ST`**, and on the phone's side everything is ready **0.3–0.5 s before the beep** —
the HAL path `voip-playback-0 -> bluetooth-sco-default` is applied, the stream is started, nothing
re-routes during the beep. The user **still hears the beep cut off in some talks**, typically the
ones where the beep comes before the passenger's voice becomes audible. So the AirPods start
rendering call audio a varying time *after* the phone starts sending it, and the phone is told
nothing about that. The same HAL log also shows some talks starting capture on the **built-in** mic
(`(null) => microphones -> voip-capture-0`) and being moved to `bluetooth-sco-headset-microphones`
~0.1 s later — so "the first captured frame" (condition (a)) is not "the first frame from the
headset" either.

The one thing that really travels back from the earpieces is their **microphone**. User's
requirement, verbatim: *"we need to async await for talk mode to be fully ready somehow. I'm fine
with a small delay, not 'sleep' delay, but real async delay. I want 0 cutoffs."* — so no fixed delay
anywhere; the only timer is the old "never silent" safety net.

**The rule now.** LIVE fires when **all three** hold: (a) the first captured frame of this talk, (b)
the route really up (F8, the framework's communication device = `bt_sco`), **(c) the headset's own
mic signal is arriving**. (b) and (c) are required only when the route needs SCO
(`AudioRouter.needsSco`); on any other route (earpiece, speaker, wired, BLE headset) the cue still
fires as soon as (a) and the route report are in. One fire per open, never after the close, same
`LiveCue.TIMEOUT_MS` = **3.5 s** fallback logged `(fallback)`.

- **`audio/MicLive.kt`** (new, pure, `MicLiveTest`, 8 tests) decides (c): the recorder is routed to a
  Bluetooth SCO **input**, and *after that moment* **`FRAMES_NEEDED` = 10** consecutive 20 ms frames
  (200 ms) have a peak |sample| **above `PEAK_THRESHOLD` = 16** (−66 dBFS). A frame read before the
  SCO moment never counts however loud it was (that is the built-in mic, or bike noise on it); one
  silent frame inside the run restarts the count, so a single click of switching noise cannot open the
  gate. It reports once per engine; leaving the SCO input *before* the mic was established starts it
  over, *after* it changes nothing. **Both constants are first guesses, to be tuned from the
  `mic trace:` line of the next device run.**
- **`audio/VoiceEngine.kt`**: the capture loop computes each raw frame's peak **before** the encoder
  (one pass over 320 shorts, no allocation), feeds `MicLive`, and calls the new
  **`onMicLive(atMs)`** callback once per `start` — same contract as `onCaptureUp` (capture thread,
  `elapsedRealtime`, must not block; `LinkHost` hops to Main itself). The routed device comes from
  `AudioRecord.addOnRoutingChangedListener` (API 24+) registered on a dedicated `voice-route`
  `HandlerThread` (never Main, which a talk open can block) — the listener only hands a
  `(type, atMs)` report over and the capture loop applies it on its next frame, so the loop stays the
  single owner of that state. Backstops: `routedDevice` is read once right after `startRecording()`,
  and re-read every **`ROUTE_RECHECK_FRAMES` = 25** frames (~500 ms) *while the input is still not
  SCO* — a getter inside a loop that already runs, not a sleep. `micLiveAtMs` is kept and cleared by
  `start` only, so an engine **carried across a collapsed close/open keeps it** and the re-opened
  talk counts it as +0 ms, exactly like `captureUpAtMs`.
- **`LiveCue`**: new `micLive(session, atMs)` event and `Fire.micMs`; `ready()` requires
  `micAt != null` when `needsSco`. `scoDisconnected()` deliberately does **not** clear `micAt`: the
  mic signal is reported once per engine, so clearing it could never be undone and every flapping
  talk would fall back to the timer. The flap's honesty is condition (b), which the link's return
  re-establishes.
- **`LinkHost`**: `onMicLive = { atMs -> scope.launch { onMicLive(atMs) } }` next to `onCaptureUp`,
  `onMicLive` on Main does `liveCue.micLive(talkAudio.ownerSession, atMs)`, and the talk-open job
  polls `voice.micLiveAtMs` right after `voice.captureUpAtMs` for the kept-route re-open.
- Untouched, by instruction: the CLOSED/ERROR earcons, the voice-command path (`listenForCommand`,
  `SCO_SETTLE_MS`), `ScoWatch`/`ScoRule`/`AudioRouter`, the overlay, the protocol, DTX/`onActivity`.
  No new dependencies. The SCO flap itself is still not addressed.

**The log lines.** The `live cue:` line gains one field, before the bracket (`tools/bench/bench.py`
parses nothing of this line today — grep says it never mentions `live cue`, so nothing breaks; the
words `(both)` and `(fallback)` are unchanged for old greps). `Motoparty:` tag via `Hub.log`, pinned
by `LiveCueTest.the log line is what the bench parses`:

    live cue: session <n>, capture up <+N ms|none>, sco <+N ms|n/a|unknown>, mic <+N ms|n/a|unknown>, fired +<N> ms (both|fallback)

e.g. `live cue: session 8, capture up +1310 ms, sco +1240 ms, mic +1502 ms, fired +1502 ms (both)`.
`mic` reads like `sco`: `n/a` = not an SCO route (nothing to wait for), `unknown` = the route never
reported, `none` = an SCO route whose headset mic was never established, `+0 ms` = carried from an
engine that was already live (a re-open that kept the route).

Two new `VoiceEngine:` lines per talk, the tuning data for the next run:

    VoiceEngine: capture routed to <builtin_mic|bt_sco|…|none> +<N> ms
    VoiceEngine: mic trace: session-start +0 ms routed=<type>@+<A> ms, sco@+<B> ms, live@+<C> ms, peaks/100ms: 0 0 0 0 3 5 812 1033 …

`capture routed to …` is one line per change of the recorder's input device (offsets from the capture
thread's start). `mic trace:` is one line per talk, printed at 4 s of capture or at the engine's stop,
whichever comes first: `routed=` is the **first** input device reported (`none` if the framework never
said), `sco@` the moment `MicLive` accepted an SCO input, `live@` the moment (c) fired, then the
**loudest sample per 100 ms bucket** for up to 40 buckets (4 s). Nothing else is logged per frame.
(An engine carried across a collapsed re-open traces only its first 4 s, i.e. the first talk's.)

**Verify on the device (F9a):**

1. Every talk's `live cue: …` line says **`(both)`**, and `mic +M ms` is present and plausible: at or
   after `sco` by roughly 200 ms + the headset's own lag (expect ~+200…+800 ms after `sco`), and
   `fired` = `mic` in most talks. `mic` far *before* `sco` means the SCO input was routed long before
   the framework's communication device — note the numbers, it changes which signal is the late one.
2. **The beep is no longer cut off** — the whole point. Speaking immediately after it must be heard
   by the other phone in full, and the beep itself must be heard whole.
3. Beep latency after the press: note it per talk (F8 measured ~1.6–2.9 s to the cue; F9a adds the
   headset's render lag + 200 ms). If the user finds it too long, `FRAMES_NEEDED` is the knob.
4. The `mic trace:` peaks show **zeros (or tiny values) until the headset is really live, then real
   level**. If the buckets between `sco@` and `live@` show a steady low level (a hiss, not zeros), the
   SCO input is delivering before the earpieces are in the call: raise `PEAK_THRESHOLD` above that
   level and/or `FRAMES_NEEDED`. If `live@` lands long after the first real level, lower
   `FRAMES_NEEDED`. If the trace shows level *before* `sco@`, that is the built-in mic and is already
   ignored — no change needed.
5. `capture routed to builtin_mic …` then `capture routed to bt_sco …` confirms the HAL finding; how
   often each talk starts on the built-in mic, and how late the SCO input arrives, belongs in this
   file after the run.
6. No talk says `(fallback)` with AirPods. If one does, read its `mic trace:`: `live@none` with real
   peaks means the run/threshold are wrong; `sco@none` means the routing listener never reported (and
   the 25-frame re-read did not help either) — then `AudioRecord.getRoutedDevice()` is useless here
   and (c) needs a different source.
7. One beep per open, never two, never none; the fast re-open (`TalkAudio: re-open …: call route
   kept`) still beeps immediately with `capture up +0 ms, sco +0 ms, mic +0 ms`.
8. The CLOSED / ERROR / OK / LISTEN earcons and the voice-command path are unchanged.

### F9b (2026-09-20) — sounds on the *media* route wait for the media route

**Why.** `TalkAudio` played CLOSED the instant `exitCall()` returned, and the ERROR earcon, the OK
of a local volume change and the spoken replies were issued the same way, right behind a route
teardown. `exitCall` normally blocks 0.7–1.0 s, by which time the media route is back — but
`tools/bench/results/2026-09-20-t4-beeps` measured it at **6 ms** once, and the user heard that beep
on the phone speaker *and* the AirPods, cut off. A sound timed by a call that *happens* to block is
gated on nothing at all. Same disease as F7/F8/F9a, other direction.

**The rule** (pure, `audio/MediaCue.kt`, 16 tests). A media-route sound may play when (a) the call
route has been released — `exitCall` returned at nesting depth 0 — **and** (b) *only if that route
was SCO*, the framework has reported a communication device other than `bt_sco` after it. Tearing an
SCO link down is the part that takes real time, and the framework's own `earpiece`/`none` dispatch is
the honest end of it. **Off SCO, (b) does not apply and the sound plays on the same Main turn**: the
t3c earpiece bench had `exitCall` at 5–16 ms and nothing may be added to that. A sound asked for with
no route held and none draining is instant — the common case. 2 s fallback, so a missing signal costs
a late sound, never a silent one. A re-opened talk drops a pending CLOSED (it would be a lie); an
error or a spoken reply is still true and waits for the next release.

**Wiring.** `AudioRouter.onRouteHeld` (depth 0 → 1, before the blocking work) and
`onRouteReleased(wasSco)` — `wasSco` is read before `selectedType` is cleared. `ScoWatch` gained a
raw `onDevice(type, atMs)` callback that fires on **every** framework dispatch *before* F8's dedupe:
the dedupe swallows exactly the `earpiece|none` this needs, because `onRouteReleased` has already
flipped `connected`. `LinkHost` owns the pending map on Main; `Earcons` now logs which device each
tone actually went out on (`Earcons: closed routed to earpiece`) — F9a's lesson.

**Verify on the device** (`beeps.sh`, the user listening): 0 cut-off closed beeps; one `media cue:`
line per sound; `device n/a, played +0 ms` on every non-SCO route (a regression here is audible at
once); no `(fallback)`.

### Stage A of the wired-mic plan (2026-09-20) — instrumentation

**Why.** `~/.claude/plans/dynamic-bubbling-lemon.md` reverses the AirPods-only decision: at
100–130 km/h in a full-face helmet, mic *placement* beats codec bandwidth by a wide margin, and the
AirPods mic is in the ear, in the turbulence. Every stage after this is an A/B of one microphone
against another — and this app could not record a single sample of its own capture, nor had it ever
asked the framework what devices the phone has (no `getDevices`, no `AudioDeviceCallback`, no
`ACTION_HEADSET_PLUG`, no `setPreferredDevice` anywhere).

**What was built.** `audio/PcmDump.kt` + `audio/Wav.kt` (the capture loop's PCM, pre-encode, to a WAV
per talk, behind the `captureDump` setting) and `audio/DeviceRoster.kt` + `audio/DeviceWatch.kt` (the
inventory and every plug/unplug). Details in the table in section 1. The rule that shaped `PcmDump`:
the `voice-capture` thread is `THREAD_PRIORITY_URGENT_AUDIO` and must never block on I/O or allocate
on the frame path, so frames go to a pool-backed bounded queue and are **dropped and counted** rather
than waited for, and `close()` does not join.

**Verify on the device:** `captureDump` on → one `capture dump:` line per talk with `0 dropped`, a
WAV that plays and is the right length, and **no change** to `capture: read N frames … (N expected)`
or to any `live cue:` timing; `audio devices:` at start-up listing the AirPods and the built-in
devices, and `+`/`-` lines when a cable goes in (Stage B's first reading).

### S4 USB stereo probe (2026-09-28) — the Lark A1 receiver as two channels

**Why.** Gate S4 of `research/MIC.md` §6: one Lark A1 Duo in Stereo mode puts the rider on L and
the passenger on R; the question was whether *this app* (not just the stock Camera) gets both.
`VOICE_COMMUNICATION` cannot — its `voip_tx` port is mono — so the talk path was not the place to ask.

**What was built.** `audio/UsbStereoProbe.kt`: a debug button at the bottom of Settings
(`UiAction.UsbStereoProbe`, run by `LinkHost`, refused during a talk; TALK is refused while it runs).
Own daemon thread; never touches the audio mode or the communication device. One 15 s clip per
source (`UNPROCESSED`, `MIC`, `CAMCORDER`), 48 kHz stereo 16-bit, `setPreferredDevice(usb)`, no
effects; beep (media stream) at start, double beep at end; WAV `captures/usb-<source>-<stamp>.wav`
through `PcmDump` (which gained `channels`, default 1). Pure `audio/StereoStats.kt` (6 tests) gives
the verdict from half-second windows: TWO CHANNELS when both an L-louder and an R-louder window
(≥ 10 dB, louder side ≥ −45 dBFS) occur at least twice. Lines: `usb probe <src>: routed … format
48000 Hz 2 ch, mode 0, media out [bt_a2dp]` and `usb probe <src>: L … → TWO CHANNELS`.

**Verified on the device, 2026-09-28 23:08:** all three sources TWO CHANNELS, 0 dropped, A2DP
untouched; numbers in `research/MIC.md` §"Status". 201 tests, 0 fail, 2 skipped; `lintDebug` 0 errors.

**Long recording (same evening, for the ride).** `startLong`/`stopLong` on the same class, second
button under the first ("start long Lark recording" / "Stop long Lark recording",
`UiAction.LongRecording`, `LinkStatus.longRecording`): source `MIC`, until stopped or 1.6 GB (~2 h
25 min), file `usb-ride-<stamp>.wav`; progress line every 5 min with the dump's `dropped`, and the
route re-read every second (`route changed to …` — a pulled receiver would otherwise fall back to the
built-in mic silently). `LinkHost.stop()` ends it. Installed 2026-09-28 23:41; Desk check 23:49: 47.7 s with the
screen locked 2.7 s in, 0 dropped, route stayed `usb_device`.

### Commands inside talk, option A (2026-09-29) — one button, commands spoken in the talk

**Why.** The user chose option A (root `HANDOFF.md`, "Decisions in force"): a press toggles talk,
everywhere, and commands are spoken inside the talk. The two-zone command path (its own route
switch, a fixed `SCO_SETTLE_MS`, the reply spoken across the teardown) is gone. Later the same day
the "Moto party" wake word and its arming (LISTEN earcon) went too: **the first phrase decides**.
Spec: `PROTOCOL.md` "Commands" (*The first phrase decides*, *Solo talk*, *Effect on the talk*),
`fixtures/commands.json` (35), `fixtures/first_phrase.json` (12).

**Removed.** `TriggerKind.MUSIC` (the enum keeps `TALK` only), `LinkHost.listenForCommand` + its
route switching + `SCO_SETTLE_MS` + `listenJob`, `voicecmd/Transcriber.kt`, `LinkService.ACTION_MUSIC`
and the notification's "Command" action, the overlay's MUSIC zone, the Ride tab's MUSIC button,
`Settings.headsetNext` (next is always next track; a stored `headsetNext` is ignored and removed on
the next save), `LinkStatus.listening`, `Palette.Music`/`Listening`. `UiAction.Command` stays (typed
path, no UI sends it today) and goes through `executeCommand` like everything else.

**Built.**
- `core/FirstPhrase.kt`: `FirstPhraseGate`, pure and clock-injected. `open(role)` per talk
  (`OPENER` = the host opened it, `OTHER` = the client did, `SOLO` = no client at the open; fixed for
  the talk), `live(atMs)` at our live earcon, `onPhrase(text, nowMs)` → the normalised command text
  or null (conversation). Opener: the first phrase non-empty after normalisation, arriving within
  `FIRST_PHRASE_MS` = 8000 ms (inclusive) of the live earcon, is a command if `CommandParser` does
  not say unknown; either way it is used, and every later phrase is conversation. A phrase before
  the live earcon counts as inside the window. Solo: every non-empty phrase, no window (unknown →
  "Didn't catch that"). `isSpent(nowMs)`: no later phrase can be a command. `core/CommandEffect.kt`:
  `CommandEffect.of(cmd, talkOpen, fromClient)` → `closeBy` (speaker's role, or null) + `Reply`
  (`MEDIA`, `CALL` = spoken in the talk now, `AFTER_CLOSE`, `NONE`) + `callVolume`.
- `audio/PcmTee.kt`: the capture loop tees every raw frame (before the encoder, so DTX cannot cut
  it) through a pool + bounded queue to a writer thread that writes PCM16 LE into the pipe; drops and
  counts when full, never blocks the capture thread (`VoiceEngine.tee`, a volatile set from Main).
- `voicecmd/TalkRecognizer.kt`: when the talk's capture is up (`onCaptureUp`, or already up on a
  collapsed re-open), an on-device `SpeechRecognizer` with the spike's extras
  (`EXTRA_AUDIO_SOURCE` = pipe read end, 16 kHz mono PCM16, `EXTRA_SEGMENTED_SESSION`, biasing
  strings); on-device refusal → the default service; a session that ends while the talk lasts is
  restarted (500 ms, given up after 3 quick failures in a row). Talk close → EOF (write end closed),
  destroy on the end callback or after 3 s. API < 33: logged once, talk without commands. Errors are
  logged only (`talk recognizer: …`); they never touch the talk.
- `LinkHost`: `applyTalk(Open)` sets the gate's role from `action.by` and `control.hasClient()`;
  `fireLive` hands it the live earcon's time (`clock()` on Main as the beep is posted) and, for the
  opener, checks again 8001 ms later. The recognizer starts (`startRecognizer`, on capture up) only
  while the gate is not spent, so never in a client-opened talk; `stopRecognizerIfSpent` stops it
  (`talk recognizer: first phrase spent, stopping`) after the first phrase is used or the window
  has passed — never in a solo talk. `onPhrase`: one line per phrase, `heard: "<text>"
  (command|conversation)`, plus `(after the talk, ignored)` and, in a solo talk only,
  `(own speech, ignored)` for a phrase within 2 s of our own TTS (otherwise "Didn't catch that"
  could recognise itself forever). The client's `command.text` goes through `onClientCommand`: acted
  on (`executeCommand(…, fromClient = true)`) only while a talk is open, the client opened it, and
  it is that talk's first; anything else is `command: "…" (client) ignored: <why>`, no announce.
- Effects: `play` drops the resume the talk was holding, sets `MusicController.startNotBefore(close
  + resumeLeadMs)` and closes the talk; after the search *and* the teardown (`talkClosed.join()`) it
  `setQueue`s and announces — on failure the music the talk paused resumes and the error is
  announced. `resume` parks the track (`music.resume()` in a talk), closes, and the ordinary
  after-talk resume starts it; "Resuming" / "Nothing to resume" after the teardown. `end` closes
  like a press (music resumes), no announce. `pause` cancels the resume and it stays cancelled
  through a later `next`/`previous` in the same talk (`MusicController.resumeCancelled`);
  `next`/`previous` park the new track for after the talk. Their replies are spoken in the talk
  (`Announcer` with `USAGE_VOICE_COMMUNICATION`, earcon `call = true`). Volume in a talk:
  `STREAM_VOICE_CALL`, tone on the call route; outside, media as before.
- Solo talk: with no client a press always opens one (`captureDump` only decides the WAV). Log line
  `talk: no client connected, recording solo (WAV|no WAV)` — the bench's substring is intact.

**Tests.** `FirstPhraseGateTest` (5: every `first_phrase.json` case, spent after the first phrase
or the window, before the live earcon, roles, a new talk), `CommandEffectTest` (5),
`PcmTeeTest` (5: never blocks and drops when full, a stuck pipe blocks only the writer, LE bytes +
EOF on close, wrong-size frames, broken pipe), `TalkControllerTest` +1, `CommandParserTest` reads
`end`; `ScreensTest` +1 (`2b-ride-talking-solo`). 239 tests, 0 fail, 5 skipped (after the
first-phrase change; 243 with the wake-word tests); `lintDebug` 0 errors.

**Verify on the device.**
1. (Dropped 2026-09-29: earbud presses no longer trigger talk — the earbuds sit inside the helmet.)
2. The recognizer is fed while the phone holds `MODE_IN_COMMUNICATION`: `talk recognizer:
   listening (on-device …)`, `heard:` lines during a talk, `pcm tee: N frames, 0 dropped` at the end,
   and **no change** to `capture: read N frames … (N expected)` or the `live cue:` timing.
3. Desk check: tap TALK, hear the live beep, say "play Dark Side of the Moon" — the talk closes
   (`heard: "…" (command)`, `talk closed (by host, trigger)`) and the album plays, after A2DP is
   back (no play into HFP, `media cue` for the announce), and the passenger's copy too.
4. First phrase through the AirPods mic at speed and at rest: a conversational first phrase is
   `(conversation)`, gets no "Didn't catch that", and is followed by `talk recognizer: first phrase
   spent, stopping`; staying silent past 8 s gives the same line, and a command after that does
   nothing. Watch whether 8 s is long enough to start speaking on the move.
5. A talk the iPhone opened: no `talk recognizer: listening` on the Pixel; the passenger's first
   command works, a second one in the same talk logs `(client) ignored: not the talk's first`.
6. Solo talk (no iPhone): a press opens it, every phrase is a command ("next", then "pause" long
   after 8 s), and our own replies are not recognised as commands (`own speech, ignored`).

### No headset → speaker (2026-09-29)

With no earbuds and no wired set, `enterCall` used to set no communication device, so the phone
played talk and the call-route earcons through the **earpiece** — inaudible at a desk (device run:
`AudioRouter: enterCall 1 ms (setMode 1, devices 0)`, `Earcons: live routed to earpiece`).
`AudioRouter.choose()` (pure, `AudioRouterChoiceTest`, 4) now picks a headset in the old order
(BLE > SCO > wired > USB headset) and, with none, `TYPE_BUILTIN_SPEAKER` from
`availableCommunicationDevices`. The timing line names the choice:
`AudioRouter: enterCall 3 ms (setMode 1, devices 0, setCommunicationDevice 2) → speaker (no headset)`
(a headset: `→ bt_sco`, `→ ble_headset`…). The speaker is not SCO, so F8/F9b gates don't apply to it.
**Verify on the device:** `Earcons: live routed to speaker`, talk audible from the speaker; with the
AirPods connected, `→ bt_sco` exactly as before.

### First two-phone run fixes (2026-09-29)

1. **Latency trim per output route.** The one `latencyTrimMs` (set ~260 for the AirPods) was
   applied on the speaker too: Pixel ~240 ms ahead. Now `Settings.trims: LatencyTrims`
   (`music/LatencyTrims.kt`, pure, `LatencyTrimsTest` 8): one value per Bluetooth address, a
   Bluetooth default for devices without one (the old value migrates there; prefs
   `trimLocalMs` / `trimBluetoothDefaultMs` / `trimDevices`, `latencyTrimMs` removed on first
   save), one value for the phone's own outputs (speaker/wired/USB, starts at 0).
   `DeviceWatch` picks the media route (`MediaRoute.pick`: `getAudioDevicesForAttributes(USAGE_MEDIA)`
   on API 33+, else BT > wired > speaker; SCO = its BT device, the earpiece is ignored so a talk
   doesn't flip it) and LinkHost re-applies via `SyncController.retrim()`: while playing it
   re-reads at once and absorbs the difference by the usual nudge (no restart); a start not yet
   played is redone. Settings shows/edits the current route's trim, labelled with its name.
2. **Logs:** `music control <action> from <client|ui|mediakey|remote>` (replaces `remote …`),
   `pause at N ms`, `resume from N ms`, `media route: <name> (Bluetooth)`,
   `trim A -> B ms, route <name>`; every `trace:` line now ends `…, t T, trim N ms, route <name>`
   before the player info.
3. **HTTP 404 race.** `state.music` is omitted until the current track is cached (servable), so
   the iPhone no longer prefetches a 404; `onClientError` only ends the ready-wait for a track
   whose `music.load` is outstanding, others log `music.error for X ignored (no music.load
   awaiting it)`; each start uses a fresh waiter. `MusicController` now takes `TrackStore` /
   `LocalPlayer` interfaces so `MusicControllerTest` (5) runs it on the JVM.
   **Verify on the device:** both phones on speakers → trim 0 on the Pixel and in sync; AirPods
   back → `trim 0 -> 260 ms, route AirPods Pro`; new track → no `HTTP 404` on the iPhone.

### F9c (2026-09-29) — the recorder must open on SCO, not migrate onto it

**Why.** Device run 2026-09-29 (AirPods Pro on the Pixel): the rider's voice did not reach the
passenger. Every talk whose `AudioRecord` was first routed to the built-in mic and only later to SCO
(`capture routed to builtin_mic +1928 ms`, `… bt_sco +3455 ms`) was near-silent after the switch
(`peaks … 4 0 0 0 0 …`) and almost all DTX (`tx 91 sent of 2626 captured (2535 DTX)`); the one talk
routed **straight** to `bt_sco` sent everything (`tx 471 sent of 471 captured (0 DTX)`). A
VOICE_COMMUNICATION recorder with AEC + NS set up on the built-in mic does not recover on the SCO
input. Not a regression of today's `choose()` change: `setCommunicationDevice` never blocked until
SCO was up, before or after (the diff only changed *which* device is picked); `enterCall` returning
in 6–10 ms is normal. The built-in-mic start was already in the F9a HAL log; its cost was not seen.

**The fix, both halves.**
- **Wait** (`VoiceEngine(awaitCaptureRoute)`, `ScoWatch.awaitConnected`): on a route that
  `router.needsSco`, the capture thread opens the recorder only once ScoWatch reports SCO as the
  communication device, at most `LinkHost.CAPTURE_SCO_WAIT_MS` = 2.5 s, then opens anyway. Woken by
  the report (monitor `notifyAll` in `update`), not polling; re-checks the session every 50 ms so a
  close inside the wait releases the thread well within `stop`'s 500 ms join. The speaker / wired /
  BLE routes return null at once: no wait.
- **Re-open** (`audio/CaptureReopen.kt`, pure, `CaptureReopenTest` 7): if the *current* recorder
  reported a non-SCO input and then SCO, the capture thread releases it (listener, effects) and opens
  a fresh one with fresh AEC/NS; playback, session, encoder, dump and tee carry on. At most 2 per
  session. SCO → built-in (headset left) is not re-opened. Routing reports carry the recorder's
  generation so a late report of the released one is ignored. The migrated recorder's SCO report is
  **not** given to `MicLive`, so the live cue's (c) comes from the re-opened recorder only. The
  sender's ts skips the frames the re-open took.

**Log lines** (`VoiceEngine:`):

    capture waited for sco <N> ms[ (not up, opening anyway)]     ← only on an SCO route
    capture reopened on bt_sco +<N> ms (was builtin_mic, took <M> ms)
    mic trace: session-start +0 ms routed=<first>@+A ms, sco@+B ms, reopened@<+C ms|none>, live@…, peaks/100ms: …

`routed=` stays the *first* input; `sco@` is the SCO moment MicLive accepted (the re-opened
recorder's); `reopened@` is new. A second `capture routed to bt_sco` line follows a re-open
(bench.py keeps the last). Tests: 266, 0 fail, 5 skipped (was 259).

**Verify on the device (F9c):** with AirPods, every talk shows `capture waited for sco …` and then
`capture routed to bt_sco` first (no `builtin_mic`), `talk stats` with ~0 DTX while speaking, the
passenger hears the rider. Any talk with `capture reopened on …` must also be ~0 DTX after it. If
`reopened on builtin_mic` or a second re-open appears, the SCO input was not ready yet at re-open
time — note the timings. No-headset talks show no `waited` line.

### Host-mic talk (Lark) (2026-09-29)

**Why.** Mic placement beats everything under a full-face helmet at speed, and one Lark A1 Duo in
Stereo mode already delivers both riders to the Pixel as two clean channels (S4). The user decided
(2026-09-29): with the receiver plugged in, the Pixel captures both; the rider (left, TX1 pink) is
sent to the iPhone as today's voice; the passenger (right, TX2 yellow) is played locally into the
rider's AirPods and never sent; nobody hears themselves; **both phones stay in media mode** (no
`enterCall`, no `setCommunicationDevice`, no SCO, MODE_NORMAL). Spec: `PROTOCOL.md` "Host-mic talk",
`talk.open{…, mic:"host"}`, `state.mic`, "Commands" first paragraph. The earbud path (call route,
SCO, VOICE_COMMUNICATION, F8/F9 cues, ScoWatch/ScoRule/MicLive/TalkAudio's collapse) is untouched
and is the fallback whenever there is no USB stereo input or the setting is off.

**Built.**
- `audio/TalkMic.kt` (pure, `TalkMicTest` 6): `TalkMic.choose(roster, larkTalk)` → `Lark(id, type,
  name)` for a USB input (`usb_device` > `usb_headset` > `usb_accessory`, then lowest id) whose
  channel counts include 2 or are empty (= any), else `Earbuds(reason)` (`setting off`, `no device
  list yet`, `no USB input`, `USB input … is mono`). `LarkRouteCheck`: the recorder must report the
  receiver's id; another device → fail at once; nothing for 2 s without ever confirming → fail; a
  null after confirming is ignored.
- `audio/LarkDsp.kt` (pure, `LarkDspTest` 8): `HighPass` (RBJ Butterworth biquad, 150 Hz, −3 dB at
  the corner, 50 Hz < −18 dB, DC gone), `Decimator` (48 → 16 kHz, 127-tap Kaiser-windowed sinc,
  −6 dB at 6 kHz, 1 kHz ±0.1 dB, 7–15 kHz < −60 dB, 1.3 ms group delay, streaming = one-shot),
  `Pcm.deinterleave` (+swap) / `toPcm16`, `LarkPipeline` (per 20 ms: 1920 interleaved samples →
  split+swap → HPF both → `rider16` 320 / `passenger48` 960 / `passenger16` 320, the last only when
  the recognizer wants the passenger), `LarkLevels` (raw L/R RMS dBFS + peak for the stats line).
  No allocation after construction.
- `audio/LarkEngine.kt`: the sibling of `VoiceEngine` for this mode, one `lark-capture` thread
  (URGENT_AUDIO). `AudioSource.MIC`, 48 kHz `CHANNEL_IN_STEREO` 16-bit, `setPreferredDevice(usb)`,
  no AEC/NS, never touches the mode. Per frame: raw → dump (when `captureDump`) + levels →
  pipeline → Opus (16 kHz mono, wire unchanged, DTX not sent, same `ts` clock) and the `PcmTee`
  (rider, or passenger when `asrPassenger`) → passenger `AudioTrack` (USAGE_MEDIA,
  CONTENT_TYPE_SPEECH, 48 kHz mono, LOW_LATENCY, 60 ms buffer) written **non-blocking** — a frame
  it cannot take is dropped and counted. No receive playback: client audio in a host-mic talk is
  dropped and counted (`onPacket`), logged once. Route check on every routing report and every 25
  frames; a wrong route, a read error, or the receiver missing at start fails the session →
  `onMicFailed` → `talk.close{by:"host", reason:"unavailable"}`.
- `TalkAudio.open(session, lark)`: a lark talk takes no call route (only `larkStart`/`larkStop`);
  a collapsed re-open that changes mode stops the other engine and exits an earbud route
  (`TalkAudioTest` +4).
- `LinkHost`: at each open `TalkMic.choose(devices.devices, larkTalk)` (the roster `DeviceWatch`
  now publishes, with channel counts; no binder call on Main), fixed for the talk;
  `talk.open{by, mic:"host"}` and `state.mic:"host"` while it is open. Live earcon: `needsSco`
  false → fires at capture-up, played with `call = false` (media route); in-talk replies and the
  volume tone go on the media route, and volume changes `STREAM_MUSIC`. Commands: host-opened or
  solo → recognizer on the rider; client-opened → gate role OPENER on the host, recognizer on the
  passenger, the first phrase acted on as the client's `command.text` (`executeCommand(…,
  fromClient = true)`), spending the client's one command; a passenger volume phrase is ignored
  (logged, no announce); any `command.text` during a host-mic talk is ignored
  (`(client) ignored: host-mic talk`). Unplug: `DeviceWatch.onDevices` without the talk's device id
  → close `unavailable`; the next talk chooses again (→ earbuds). Capture dump in this mode:
  `capture-lark-<stamp>.wav`, raw 48 kHz stereo (unswapped, unfiltered), capped at 400 MB (~35 min).
  Solo host-mic talk works (both captured, rider recognised, every phrase a command).
- Codec: optional `mic` on `talk.open` and `state`, `("talk.open","mic")` and `("state","mic")` in
  `ENUM_FIELDS` → `"both"` etc. drop, keep (`CodecTest` +1; fixtures). `app/build.gradle.kts`: the
  fixtures dir is now a declared test input — before, an edited fixture left the tests UP-TO-DATE.
- Settings: `larkTalk` (default on, "Use USB stereo mic (Lark) for talk") and `larkSwap` (default
  off), switches at the top of the Voice group.
- Untouched: `UsbStereoProbe` + long recording (still refused during a talk, TALK refused during
  them), `resumeLeadMs`, the `sync.hold()`/`release(cold = true)` around the close. **Could be
  shortened later:** with no profile switch the close's route is back at once, so `resumeLeadMs`
  and the cold start could be skipped for a host-mic talk (PROTOCOL.md allows it); left as is.

Tests: 285, 0 fail, 5 skipped (was 266). `assembleDebug` ok, `lintDebug` 0 errors.

**Log lines** (`Motoparty` tag / in-app log):

    talk mic: lark usb_device#<id> "<name>", swap off        ← or: talk mic: earbuds (<reason>)
    lark: routed usb_device#<id> "<name>" (preferred accepted=true) format 48000 Hz 2 ch, mode 0, media out [bt_a2dp], passenger out bt_a2dp
    live cue: session N, capture up +X ms, sco n/a, mic n/a, fired +X ms (both)
    talk asr: listening on left (rider)                        ← right (passenger) when the iPhone opened
    heard: "<text>" (passenger, command|conversation)
    lark: client audio in a host-mic talk, dropping it         ← only an old client
    lark stats: <s> s, L rms <dBFS> peak <x>, R rms <dBFS> peak <x>, sent <n> frames, played <n>, dropped <n>, client audio dropped <n>
    microphone unavailable: lark: recorder routed to builtin_mic#15 …, not the receiver   ← or: lark receiver unplugged

**Device checklist (home test).** Lark RX in the Pixel's USB-C, Stereo mode; TX1 (pink) on the
rider, TX2 (yellow) on the passenger; AirPods Pro on the Pixel (rider), iPhone with its own
earbuds (passenger).
1. Plug in → `audio devices +: in usb_device#… "…"`. Tap TALK → `talk mic: lark …, swap off`,
   **no** `AudioRouter: enterCall`, `lark: routed usb_device… mode 0, media out [bt_a2dp],
   passenger out bt_a2dp`, the live beep in the AirPods at capture-up (`sco n/a`). Music pauses and
   resumes as before.
2. Rider speaks → the iPhone hears them (`lark stats` L rms well above R, `sent` > 0); passenger
   speaks → the rider hears them in the AirPods (`played` ≈ seconds × 50, `dropped` ≈ 0). Nobody
   hears themselves. **Listen for the passenger's delay into A2DP** (AirPods ~150–250 ms is expected)
   and for clicks (dropped frames = the output clock slower than the Lark's).
3. TX2 off → `R rms -inf dBFS peak 0`. `larkSwap` on → the channels trade (`talk asr` side flips).
4. iPhone opens the talk → `talk asr: listening on right (passenger)`; passenger says "next" → the
   track skips, "Next: …" on both phones; "volume up" from the passenger → `ignored: volume is the
   passenger's own`, no announce. Rider-opened → `listening on left (rider)`, as before.
5. Unplug mid-talk → `microphone unavailable: …`, `talk closed (by host, unavailable)`, error beep;
   the next TALK logs `talk mic: earbuds (no USB input)` and runs the SCO path exactly as before.
6. `captureDump` on → `capture-lark-<stamp>.wav`, 48 kHz 2 ch, `0 dropped`.
7. Solo (no iPhone): TALK works, every phrase a command.
Open questions only the device answers: whether the USB input route holds while the AirPods are on
A2DP and music was just paused; the passenger playback latency and drift into A2DP; whether the
screen-off Pixel keeps the MIC-source recorder delivering (the long recording suggests yes).

### Commands card, nowplaying/shuffle, play by touch ends a talk, caching, history (2026-09-30)

- **Ride tab**: the "Moto party" hints are gone. Under TALK there is now a "Voice commands" card
  listing every command in big chips (`play <song>`, `play album / artist / playlist <name>`,
  pause/resume, next/previous, louder/quieter, what's playing/shuffle, over). Its line says "say one
  of these first, after that it's just talk", or "Alone, every phrase is a command" with no client.
- **`nowplaying` / `shuffle`** (`CommandParser`, `CommandEffect`, `LinkHost.executeCommand`): both
  leave the talk open and reply in it. "<title> by <artist>" (title alone if no artist) / "Shuffled"
  with earcon `ok`; "Nothing playing" / "Nothing to shuffle" (<2 upcoming) with `error`.
  `QueueEdits.shuffled` keeps the current track and never hands back the same order.
- **Play by touch ends a talk** (PROTOCOL.md "Browsing" step 3): `LinkHost.playByTouch`. A `now`
  enqueue (`TouchPlay.enqueueEndsTalk`) or a valid jump, from the client or our own screens (Search
  "play now", Queue jump, a Recently played tap), with a talk open: `music.beforePlayEndsTalk`
  (drops the talk's resume + `startNotBefore(now + resumeLeadMs)`, shared with the spoken `play`),
  `talk.close{by:<toucher>, "trigger"}`, then the queue changes at once and our player is
  `sync.hold()`-ed until the route-back job finishes. A stale jump closes nothing. next/end/remove/
  clear leave the talk open. "Paused for talk" still shows for music that was playing at the open.
- **Caching**: `MusicController.prefetchNext` caches the next **3** upcoming, one at a time (a new
  queue restarts it); only the next one gets a `music.load`. Album/playlist **Download** button
  (`CollectionDownloads`): one track at a time across all collections, "Downloading 5/14 · Stop"
  → "Downloaded" (or "Retry · 13/14 saved"); Stop cancels after the track in flight, which is kept.
  Song rows show a small check when cached (`LinkStatus.cached`, from `TrackCache.ids()`, refreshed
  after every download). Same 1 GB LRU, so a big playlist can evict older tracks.
- **Search history** (`music/History.kt` pure, `HistoryStore.kt` in SharedPreferences "history"
  as JSON): with the box empty, Recent searches (10, newest first, de-duplicated by text ignoring
  case, the newest chip wins, Clear button) and Recently played (20, by id; fed by
  `MusicController.onStarted`, i.e. a track that really started, whoever queued it; a resume is not
  a start). Tap re-runs the search / plays now (and so ends a talk).
- Tests: `HistoryTest`, `CollectionDownloadsTest`, `TouchPlayTest`, shuffle in `QueueEditsTest`,
  six new `MusicControllerTest` cases (3-ahead prefetch, nowplaying text, shuffle, history hook,
  parked start, touch play in a talk), two new screenshots (`4b-search-history`,
  `5b-album-downloading`). LinkHost itself is still not JVM-testable.

Device checklist:
1. Ride tab: the commands card fits and reads at a glance; with and without the iPhone connected
   the line under "Voice commands" changes.
2. Talk, say "what's playing" → "<title> by <artist>" in the talk, talk stays open. Nothing loaded
   → "Nothing playing" + error tone. "shuffle" with 3+ upcoming → "Shuffled", Queue tab reorders,
   iPhone queue too; with 1 upcoming → "Nothing to shuffle".
3. Music playing, open a talk, tap a song on Search (and separately jump in Queue, and on the
   iPhone play-now) → talk closes (`play by touch (by host|client) ends the talk` in the log), the
   old song does not come back, the new one starts after the A2DP switch on both phones. "Play
   next"/"Add to queue"/remove during a talk leave it open.
4. Play a queue, watch `cacheMb` climb by ~3 tracks ahead; turn Wi-Fi/data off for a few minutes
   and the next 3 tracks still play.
5. Open an album, tap Download: progress climbs, Stop works, check marks appear on the rows,
   "Downloaded" at the end. Airplane mode → those songs play.
6. Search a few things, play a few songs, force-stop and reopen: both history lists survive; tap a
   recent search re-runs it on the right chip; Clear empties searches only.

### Audit round 1 (2026-09-30) — ride-breaking bugs and voice latency

Built offline, **device-unverified**. Coordinator-run: 361 tests / 0 fail / 5 skipped, `lintDebug`
0 errors / 21 warnings (`--rerun-tasks`). IDs are `AUDIT.md`'s.

- **L1/L2** `core/JitterBuffer.kt`: backlog shedding at spurt start and during a spurt, a late first
  packet after silence starts a spurt (PROTOCOL.md "Voice", vectors `fixtures/jitter.json`, 17 cases).
  Known cost: a bunch of frames arriving at once after a pause is cut to the target (latency over
  the word onset). `talk stats` now ends `…, N shed, depth mean N max N ms`.
- **P5** `core/ClockEstimator.kt`: step reset only above `500 + rtt/2`.
- **L3** talk track asks a 40 ms buffer and grows on underruns (`audio/PlaybackFill.kt`); Lark
  passenger track holds a 40 ms fill with watermarks. **L4** whole-frame writes only. **L5** periodic
  `routedDevice` reads moved to the route threads. **L7** earcon release off Main.
- **R4** `onPlayerError` reloads and re-applies the anchor cold; second error skips the track.
  **R5** per-chunk retry at the same offset (1/2/4/8 s), 403/410 re-resolves, `.part` kept and
  continued; final failure skips (cached first), three in a row stops and keeps the queue; with no
  network the track waits (`music/NetworkWatch.kt`). **R9** a pause during a load parks the track
  paused. **U-D2** `ACTION_AUDIO_BECOMING_NOISY` pauses both phones, ignored during a talk and for
  3 s after it.
- **R2** FGS start cannot crash-loop, `ACTION_TALK` retries the microphone type, `LinkStatus.micOff`
  + notification text "Microphone off – tap to restore" (**Ride tab does not show it yet: round 3**).
  **R3** a client joining a solo talk ends its commands. **R6** rotation no longer restarts a stopped
  host. **P1** a same-name reconnect keeps the talk (only when the hello replaces a connection the
  host still holds). **P2** connect-time `state` is a snapshot. **P9** client swap under one lock.
  **P10** `bye` survives Stop.

**Device checklist** (log line to look for):
1. Talk by earbuds, then Lark: `talk stats … N shed, depth mean … max …` — mean near the target
   (40–60 ms), and listen for clicks. After a route switch mid-talk the delay must not stay.
2. `playback track: mode …, buffer X of CAP frames (asked 640, granted G, grew Nx), underruns U` at
   talk stop; `grew` on most talks → raise `PLAYBACK_BUFFER_FRAMES`.
3. `lark playback: … hold H ms (grew Nx), underruns U, fill min/mean/max, … partial P, unknown fill K`;
   `partial`/`unknown fill` > 0 means the fill estimate is unreliable on this phone.
4. `capture routed to …` still appears; earcons still audible.
5. AirPods into the case mid-song → `becoming noisy: pausing the music`, iPhone pauses. Open/close
   talks with music: `audio becoming noisy` should not appear at all. Unplugging the Lark must not pause.
6. Airplane mode mid-download → `load <id> failed with no network (…); waiting for it`, plays when
   data is back. Does `online` follow mobile data while the Pixel is the hotspot host?
7. Pause while a song is still loading → `parked paused at 0 ms: paused while starting`.
8. Kill the process with the host running (never tested): no crash loop, `microphone off: service
   type refused…`, then Talk in the notification or opening the app → `microphone restored`.
9. Solo talk, then connect the iPhone → `talk: client joined a solo talk, commands off for the rest of it`.
10. Stop, rotate: host stays stopped. Stop: the iPhone logs a `bye` ("host stopping").
11. Wi-Fi blip mid-talk → `talk kept: "<name>" reconnected`.

### Audit round 2 (2026-09-30) — music

Built offline, **device-unverified**. Coordinator-run after integration: 403 tests / 0 fail /
5 skipped, `lintDebug` 0 errors / 21 warnings (`--rerun-tasks`). IDs are `AUDIT.md`'s.

- **M1** `SyncController.startLeadMs(cold)`: every start and resume is anchored far enough ahead for
  the Pixel to be audible *at* the anchor (learned start latency + 250 ms preparation + 50 ms, never
  under 300). **M5** gapless: `MusicController.armGapless` queues the next file behind the current
  one in ExoPlayer and sends `music.next`; at the change only the anchor is adopted
  (`SyncController.adopt`), no pause/seek. Taken back with the current `music.play` sent again
  unchanged (`gapless: cancelled`); after any `music.play` the host sends `music.next` again
  (PROTOCOL.md Music flow 6). **M7** end of queue parks the last track paused at 0 (`music.pause`,
  no `music.stop`); spoken `next` on the last track answers "End of queue". **M9** one more
  `music.load` 2 s after the first `music.error`.
- **M3/M4/M6/M8** `music/TrackCache.kt` rewritten: 4 parallel ranges per download, one download at a
  time by priority (`CURRENT > NEXT > PREFETCH > COLLECTION`), a lower one is paused and continues
  from its `.part`; stream addresses cached 1 h and looked up ahead (`preResolve`: the top 3 song
  results and the 3 tracks ahead); ids/size in memory (no 1 Hz directory listing on Main); LRU
  eviction that never takes the playing or the next track. `MusicController.cacheHints()` calls
  `retain`/`protect`/`preResolve` on every queue or index change. **Behaviour change:** cancelling
  the last waiter of `ensure` stops the download (bytes kept).
- **P3** DSCP: control socket CS5 (`0xA0`), track server CS1 (`0x20`). **P11** a client `hello` with
  another `proto` gets `bye{reason:"proto"}`; invalid UTF-8 closes the connection.
- **H6** no stale notification after Stop. **H7** the USB probe blocks only talk opens; a client
  `talk.open` during it is refused `unavailable`. **H8** the overlay redraws only on talk/client
  changes. `state.queue` items carry `durationMs`/`art` (`link/StateFit.kt` drops art above 48 KiB).

**Device checklist** (log line to look for):
1. Start a song: `play <id> from 0 ms, anchor in ~600 ms`, `start warm: lead …`, and **no**
   `start past the anchor by N ms`; the first `trace:` lines show `pos` near `expected`. The first
   half-second of the song is audible on the Pixel.
2. Let one song run into the next (both cached, iPhone ready): `gapless: <next> queued behind <cur>,
   change in N ms`, then `Player: gapless: <cur> -> <next>` and `gapless: now <next>, N ms after its
   anchor` (N within ±100), no `start warm` at the change, the next `drift` line small. Listen on
   both phones: no gap, no jump. Then: pause/resume, a talk, and a queue edit shortly before the end
   (`gapless: cancelled`, then queued again).
3. Last song ends: `end of queue: <id> parked paused at its start`; Play starts it again; Previous works.
4. Tap a new song on LTE: time from tap to sound (parallel ranges, pre-resolved top results).
   Skip next-next-next: `download <id>: cancelled` each; back to one: `download <id>: continuing, N of
   T there`. During an album download tap a song: `download <id>: waits for a CURRENT download`.
   Full cache: `cache: evicted <id>` never names the playing or next track.
5. `music.error for <id>: …; sending music.load again in 2 s` → `music.load for <id> sent again`
   (force it by blocking the iPhone's download once).
6. Stop in the app → the notification shade is empty. Settings cache size appears a moment after
   start; `MainLag` shows no 1 Hz stall.
7. With a third machine on the hotspot: `tcpdump -v port 47800` shows `tos 0xa0`, `port 47802`
   shows `tos 0x20`; voice does not stutter while the iPhone downloads a song.
8. USB probe running: a passenger talk request is refused (`talk.open refused: usb probe running`),
   and the rider can still end a talk.

### Audit round 3 (2026-09-30) — UI, notification, release build

Built offline, **device-unverified** (450 tests / 0 fail / 5 skipped, lint 0 errors). Reports:
`~/.cache/claude-handoff/motoparty-round3/{android-ui,android-system}.md`.

Checked on the Pixel 2026-09-30 (release build, screen locked, `tools/peer` as the client): start,
NSD, search, `play <id> from 0 ms, anchor in 600–645 ms` with no `start past the anchor`, remux
50–700 ms, `gapless: now <id>, -74 ms after its anchor` between two cached tracks, end of queue
parks paused at 0, MediaStyle notification with Talk/Stop. Everything below that needs eyes on the
screen or the iPhone is still open. New: an Opus MP4 from the batching muxer (`Remux.kt`) has not
been played by AVPlayer yet.

1. Ride: Connecting… → LIVE against the beep; END TALK pill; command card during the first-phrase
   window, gone after 8 s or the first phrase; "Heard" line; haptics through gloves.
2. Mic-off banner tap restores the microphone type; UA14 with a permanently denied permission.
3. Ride layout under real insets, portrait and landscape (rail); progress bar smooth after pause,
   talk, track change; snackbar vs mini-player.
4. Queue Undo at first, middle, last position with the iPhone connected (one `state` push).
5. Long-press on the link line stops the host (confirm); Stop also in Settings.
6. Media notification: art, progress, prev/play/next plus Talk and Stop; Talk flips to End talk;
   mic-off falls back to the plain notification; media keys unchanged; nothing left after Stop.
7. Overlay: colours, "END TALK" fits, pulse until the live beep. Launcher/themed/status icons.
8. Release build (needs the uninstall, see README "Release build"): start, link, YouTube search and
   play (Rhino + extractor under R8), settings/history, a talk both ways, cover art.
9. L9: `Earcons: live routed to … (prepared)` in logcat; beep audible on AirPods.

### Audit leftovers (2026-09-30) — ambient colour, marquee title, mic meter

The three UI items round 3 skipped (audit "UI improvements" 1 and 7). Built offline,
**device-unverified** (477 tests / 0 fail / 5 skipped, lint 0 errors, `assembleRelease` builds).
Report: `~/.cache/claude-handoff/motoparty-leftovers/android-ui.md`.

- **Ambient colour** (`ui/Ambient.kt`): the Ride tab draws a vertical glow of the cover's colour
  behind everything (nothing at the top, strongest at 30 % of the height, gone by 85 %, so TALK
  and the tab bar stay on the plain surface). `ArtTints.load` asks Coil 3 for the cover at 96 px
  with `allowHardware(false)`, reads `androidx.palette` (palette-ktx 1.0.0, the one new
  dependency) on `Dispatchers.Default`, and remembers the answer per art URL (64 entries; a
  failed load is not remembered). The rule is the pure `ambientTint` (`AmbientTest`): first
  non-grey swatch of vibrant / dark vibrant / light vibrant / muted / dark muted / light muted /
  dominant; saturation ≤ 0.70, lightness 0.30–0.50; then darkened until 25 % of it over the
  surface has a relative luminance ≤ 0.024 (secondary text ≥ 7:1, warning red ≥ 3.5:1 for every
  cover colour). No art, loading, error, grey cover: plain surface. A track change fades the
  colour over 700 ms; it is read in a draw lambda, so the fade recomposes nothing. Controls keep
  the fixed scheme.
- **Marquee**: the title on Ride (both layouts) and in the mini-player is one line with
  `basicMarquee(iterations = Int.MAX_VALUE)`; it scrolls only when it does not fit. Ride's title
  was two lines with an ellipsis before. Artist lines are unchanged.
- **Mic meter**: `audio/MicLevel.peak` is a `@Volatile` int the two capture loops store once per
  20 ms frame (VoiceEngine: the peak it already computed for the mic trace; LarkEngine: the peak
  of the rider's 16 kHz channel) — no allocation, lock or call that blocks. Zeroed at talk start
  and when the loop ends. The bar sits in the LIVE pill (`MicMeter` in `RideTab.kt`), polls every
  60 ms only while the pill is composed and the activity is started, and redraws only itself;
  nothing goes through `Hub`. `micLevel` maps −48…−6 dB to 0…1, `meterStep` rises at once and
  falls a full bar in 450 ms (`LogicTest`).

On-screen device checklist (nothing here has been seen on the phone):

1. Ride with a colourful cover: the glow is visible but quiet; text, the progress line and the
   amber status line stay readable in daylight; no visible band or hard edge at the top, against
   the tab bar, or (landscape) against the rail and the right screen edge.
2. Next track: the colour fades to the new cover's, no flash to black in between; a track
   without art and a grey cover leave the plain surface. Offline with a cached track: colour
   appears if the cover is in Coil's disk cache, plain otherwise.
3. Yellow / white-ish covers: the mic-off and error banners on top of the glow are still readable.
4. A long title scrolls after about a second, loops, and restarts on the next track; a short
   one stands still and stays centred (portrait). Same in the mini-player on the other tabs.
   Check the scroll does not cost frames on the release build.
5. Live talk on the AirPods mic: the bar in the LIVE pill follows the rider's voice, rests empty
   (or nearly) in a quiet room, and does not sit pinned at full at riding wind noise — the
   −48…−6 dB range (`METER_FLOOR_DB` / `METER_FULL_DB` in `ui/Logic.kt`) is a guess until then.
6. The same with the Lark receiver (host-mic talk), and a talk the passenger opened.
7. Bar is empty at the first instant of the next talk (no stale level), in portrait and landscape.
8. `talk stats` / `capture:` lines: `slowest encode+send` no worse than before the meter.

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
3. **Verify the audio thread on the device** (code done and reviewed, section 4 "Talk no longer
   blocks Main"). Bench it with `tools/peer`: (a) `talk.close` -> `AudioRouter: back to media mode`
   should still take ~1–2.7 s, but Main must stay responsive (no `Choreographer: Skipped` frames,
   the status line keeps ticking) and the host's resume should log `start cold` with a small first
   drift instead of a re-seek; (b) toggle talk open/close/open as fast as the peer allows, several
   times, and check `dumpsys audio` ends in `MODE_NORMAL` after the last close and in
   `MODE_IN_COMMUNICATION` while the last open holds; (c) TALK during a voice command; (d) stop the
   service mid-command and check `dumpsys audio` shows `MODE_NORMAL`.
   **Layer 2: logging only, JVM-tested, not yet run on the device.** Three things to read in
   logcat on the same bench run:
   - **`VoiceEngine: talk stats: …`**, one line at every talk stop. Exact format (a bench parser
     reads it; `TalkStatsTest.lineFormatIsWhatTheBenchParses` pins it):
     `talk stats: tx N sent of N captured (N DTX), rx N received, N played, N lost, N late, N FEC,
     N PLC, N keepalives, jitter target N ms`. The counters live in `core/JitterBuffer.kt`
     (`received` incl. late ones, `keepalives`, `fecUsed`, `concealed`, `seqSpan`; `reset` zeroes
     them, `underruns` keeps counting and `late` is its delta since `start`). The arithmetic is in
     `core/TalkStats.kt`: `lost = max(0, seqSpan − received − keepalives)`; keepalive seqs are never
     loss, a duplicate cannot make it negative. Reading it: `PLC` is every concealed frame, including
     up to 3 at the end of each spurt when the sender goes quiet, so it is not a loss count; `lost`
     is. Tests: `JitterBufferTest.perTalkCountersOverAScriptedTalk` (seq wrap 65535 -> 0, one loss
     -> FEC, one PLC slot, a late packet, a keepalive, a new spurt), the reorder and wrap span
     tests, `TalkStatsTest`.
     **Two bugs the tests found, fixed:** (1) `seqSpan` started at the *first* seq seen, not the
     lowest, so a talk whose first packets arrived swapped (or a keepalive just below the first
     audio seq, across the wrap) hid a loss; it is now min..max. (2) A late packet that was the last
     audio seq before a DTX gap made `onlyKeepalivesBefore` fail, so the gap was treated as loss:
     the next spurt was PLC'd up to its old-timeline slot instead of starting fresh at the raised
     target (that raise was lost too). Late seqs are now remembered like keepalives (`lateSeqs`).
     This one is a small voice-path behaviour change; the other 13 jitter tests are unchanged.
   - **`SyncController: nudge done: drift N ms`** after each rate nudge (1 s after the speed reset since D2). Note it contains
     "drift N ms"; if the bench parser counts every "drift N ms" as a check, it must skip lines
     starting `nudge done:` (not renamed, since earlier device logs use it).
   - **`SyncController: trace: …`**, one line a second (a) from every rate nudge until 5 s after
     it ends, (b) for 5 s after every start (warm or cold) and every >1 s re-seek. Exact format:
     `trace: pos <player position> ms, expected <anchor timeline now + trim> ms, err <pos − expected> ms, speed <speed>, phase <start-warm|start-cold|seek|nudge>, t <ms since the trigger>, playing <exo.isPlaying>, whenReady <exo.playWhenReady>, state <exo.playbackState>`
     e.g. `trace: pos 61234 ms, expected 61012 ms, err 222 ms, speed 1.0, phase nudge, t 9000,
     playing true, whenReady true, state 3`. `pos` is ExoPlayer's raw `currentPosition` (nothing
     corrects it; the trim is on `expected`). The word is `err`, never `drift`. The first line of
     each window is the reading at the trigger itself; a new trigger restarts the cadence and keeps
     the later end; `hold`/a new `apply` stop it. The last three keys come from
     `PlayerControls.traceInfo` (empty in the test fake).
     **What it is for**: on the 2026-09-19 device run every speed-UP nudge ended ~+150..+220 ms
     ahead whatever its size and the reading also dropped 140–220 ms between checks with no speed
     change; the hypothesis is that the position *reading* steps (A2DP delay report) rather than a
     rate error. In the trace, a reading step shows as `err` jumping by ~200 ms between two
     consecutive lines while `speed` is 1.0 (at speed s, `err` should move (s − 1) × 1000 ms per
     line); a real overshoot shows as `err` ramping smoothly during the nudge and staying flat after.
     `playing false` next to a jump means the output was not really running.
     **No behaviour change, proven**: `traceLog` is a settable property (null = off); the trace only
     reads. `SyncControllerTest` "the trace changes no speed, seek, play or pause decision" runs one
     script (start, speed-up nudge, slow-down nudge, >1 s re-seek, hold, cold release) with the trace
     on and off and asserts the fake player's full timestamped command list is identical; two more
     tests pin the windows (6 lines 1 s apart after a warm and a cold start, nudge lines from the
     nudge instant to 4–5 s after it ends) and the exact line text.
   **To do on the device**: one ~3-minute music run with AirPods (include a talk open/close for a
   cold start), then `adb logcat -s SyncController VoiceEngine` and read the `trace:` lines around
   each `nudge done:`.
   **D2 bench (2026-09-19, `tools/bench/results/2026-09-19-d2-music/`) -> offline fix, JVM-tested,
   not yet on the device.** The trace answered the layer-2 question: on A2DP (AirPods) the raw
   ExoPlayer position flips between two levels ~180–250 ms apart at speed 1.0, playing, state 3,
   no seek (e.g. pos advanced 789 ms in 1004 ms). The nudge math was right; single raw readings
   on the wrong level made nudges of their own, so 18 landings scattered ±200 ms both ways.
   Likely Media3's AudioTrackPositionTracker switching between the AudioTimestamp and the
   head-position − latency paths (deep-buffer track over A2DP) — not chased. Fix in
   `SyncController` (coordinator's decision):
   - Every drift figure is now a **filtered reading**: 9 samples of position − expected, 250 ms
     apart (2 s), median. Used by the periodic check, the post-start/post-seek/post-nudge checks,
     the re-seek decision, `lastDriftMs` (UI) and the start-lead learning (the first filtered
     reading after a start teaches, as the single read did).
   - **Confirmation**: 80 ms..1 s is acted on only when two consecutive filtered readings (the
     second starts as the first ends, so ~2 s apart) are same sign and within 100 ms; the nudge
     uses the second. A lone one logs `check: median err N ms (spread N ms, n 9), waiting for
     confirmation` (plus ` (previous P ms not confirmed)` when it replaced an unconfirmed one) —
     deliberately no "drift N ms" in it. **>1 s re-seeks on one filtered reading**, at any time:
     a flip moves the median ~250 ms at most, so that error is real.
   - Timings: sampling starts 2 s after play()/re-seek/speed reset (decision at +4 s); periodic
     decisions stay 10 s apart (8 s wait + 2 s sampling); an unconfirmed reading adds 2 s.
     `nudge done: drift N ms` (still one raw read, logging only) moved to 1 s after the speed
     reset. Other log formats unchanged. Decision lines go through `SyncController.log`
     (settable, tests capture it).
   - Tests: `SyncControllerTest` 17 -> 23 (one-sample flips, a 1 s flip, a steady 150 ms offset
     nudged only after confirmation, a flip around a nudge's end not causing an opposite nudge,
     lead learned from the median, >1 s start re-seeks without confirmation). All 6 fail with
     1 sample and no confirmation. Existing tests: only `firstCheckAfter` (+2 s) and the re-seek
     test's post-seek wait (+2 s) changed, for the sampling time.
   **Next device run** (`tools/bench/music_sync.sh`): `check: … waiting for confirmation` lines,
   far fewer `drift N ms: speed …` nudges, and no nudge immediately followed by one of the
   opposite sign; the trace `err` still flips (the reading is unchanged), the medians should not.
   **D1 bench (2026-09-19, `tools/bench/results/2026-09-19-d1-talk{,-2}/`) -> offline fixes, JVM-tested,
   not yet on the device** (105 tests, 0 fail, 2 skipped; was 94):
   - **"8 played, 950 late" (run 1's 20 s talk) = jitter anchor stuck ahead of the stream.** Once
     `nextTs` (advanced one frame per `pull`) got ahead of a steady stream by less than the 3 s
     `RESYNC`, both advanced at 50 frames/s and every later packet was dropped as late until the
     talk ended; DTX gaps did not help (the sender's ts runs through them). The peer sent a clean
     50/s, so the host's output pulled a burst (the talk's `ioConfigChanged` re-route lands ~1.3 s
     after the open) or a stall on the sender side put it there. Not a reset/session problem.
     Fix (`core/JitterBuffer.kt`): packets that keep arriving late for `REANCHOR_AFTER_MS` = 100 ms
     with nothing played in between start a new spurt (target kept, already raised once); a burst
     that arrives at once is still dropped. `reanchors` counter. Tests: `JitterBufferTest`
     `outputBurstAfterRouteSwitch…`, `senderThatLostFrames…` (both fail without the fix: 0 of 100
     played), `aLateBurstAfterANetworkStall…` (old behaviour kept). `reset()` now also clears
     `nextTs`, `emptyPulls`, `raisedThisSpurt`.
   - **Fast open->close->open ran in full (2124 ms to HFP).** New `audio/TalkAudio.kt` replaces the
     two posted blocks in `LinkHost.applyTalk`: open/close only record the wanted session and post
     a `settle` that does the difference. A close + open queued behind an in-flight open do nothing
     (`TalkAudio: re-open before teardown: call route kept (session N)`); a re-open that arrives while
     the teardown is in `voice.stop` skips exitCall + enterCall and restarts the voice (`TalkAudio:
     re-open during teardown: call route kept (session N)`). A1 guarantees kept and tested in
     `TalkAudioTest` (collapse, mid-teardown re-open, 40 random sequences: final state = last request,
     enters = exits, earcon per real teardown; failed enter reports its session and is balanced;
     a carried-over voice fails under the new session; a dead carried voice is restarted).
     Consequence for the bench: a collapsed close has **no** `talk stats`, `back to media mode` or
     second `communication device` line (the voice engine keeps running, so the stats of both talks
     land in the next close's line).
   - **Timing lines** (new, one each; the old lines are unchanged and come first):
     `AudioRouter: enterCall N ms (setMode N, devices N, setCommunicationDevice N)`,
     `AudioRouter: exitCall N ms (clearCommunicationDevice N, setMode N)`,
     `VoiceEngine: start N ms`, `VoiceEngine: stop N ms (join capture N, join playback N)[, still running: voice-capture]`,
     `VoiceEngine: capture up N ms (AudioRecord N, effects+encoder N, startRecording N, first frame N)`,
     `VoiceEngine: playback up N ms (AudioTrack N, play N, first write N)`,
     `VoiceEngine: capture: read N frames in N ms (N expected), device N frames, slowest encode+send N ms, longest read wait N ms`,
     `VoiceEngine: playback: wrote N frames in N ms (N expected), N re-anchors`. Format pinned by
     `StepTimerTest`. What the logs already show: the ~1.2 s `ioConfigChanged` is not ours —
     `logcat_all` has audioserver's `setDevicesRoleForStrategy` (what `setCommunicationDevice`
     triggers, asynchronously) taking 1.0–1.25 s and every other audio binder call in the phone
     queued behind it; `enterCall` itself returns before that. In the fast re-open, `voice.stop`
     took ~500 ms (capture join timed out: the thread was stuck in audioserver) and exitCall ~1.07 s.
   - **Earcon AudioTrack leak (likely cause of the rising open time, unconfirmed).** No earcon ever
     logged `AudioTrack: stop(..)` (every release does), i.e. the LIVE/CLOSED tracks were never
     released: the marker callback reaches Java via a weak ref and nothing held the track.
     `Earcons` now holds each track until its marker (on the main looper) or a fallback release at
     tone length + 1 s. Next run: one small `stop(..)` line per earcon, open times flat.
   - **Capture ~83 %**: encode + send are inline, but cost well under 1 ms of the 20 ms frame, so
     not clearly the cause; the missing frames match the route switch (0 % DTX talks look like the
     record opened on the built-in mic before HFP). AudioRecord buffer raised 80 -> 200 ms as a
     margin; the new `capture:` line (device frame position vs frames read) decides it: device ≈
     read -> the input did not deliver (route), device > read -> the loop overran.

   **D1 bench 4 (2026-09-20, `tools/bench/results/2026-09-20-d1-talk-4/`, AirPods) -> offline fix,
   JVM-tested, not yet on the device** (115 tests, 0 fail, 2 skipped; was 111).
   The TalkAudio collapse worked on the earpiece but *never
   ran* with the AirPods: `Motoparty: talk open (by client)` at 172.998 is 11 ms after the audio
   thread's `exitCall 573 ms (clearCommunicationDevice 572, setMode 0)` returned at 172.987,
   although the client's `talk.open` had reached the phone at ~172.07. Pings were answered in
   11 ms throughout (reader thread, `ControlServer.handle`), so the link was fine — **Main was
   blocked for ~0.9 s**, and the re-open only reached `TalkAudio` once the teardown had fully
   settled. The next `enterCall` then set the device at 173.077 while the system processed our
   `clearCommunicationDevice` at 173.078: a clear and a set crossing inside AudioService.
   What blocked Main (found by reading every Main-side call into AudioManager/AudioTrack/
   MediaPlayer/focus between `talk closed` and the next `talk open`; all fixed):
   - `LinkHost.micAvailable()` read `audioManager.mode` — a binder call into AudioService, which
     our own `exitCall` was holding — on the path of the client's `talk.open`.
   - `Earcons.play(…)` on Main (the two ERROR earcons, the trigger ERROR ones, the volume OK one,
     the announcer's, and the recognizer's LISTEN): building an `AudioTrack` goes through
     audioserver, which is busy for the whole route switch. Also `adjustStreamVolume`, same reason.
     (The live and closed earcons were already off Main.)
   The fixes:
   - **`audio/AudioModeWatch.kt`** (new): `AudioManager.getMode()` cached off Main, refreshed by
     `AudioManager.OnModeChangedListener` on API 31+ and by a 1 s poll below that (minSdk is 29),
     both on a dedicated daemon thread `motoparty-audio-mode` — *not* the audio thread, so the
     value cannot be stuck behind a route switch. `micAvailable()` now reads only local state
     (permission, FGS type, cached mode), so it cannot block. Protocol behaviour is unchanged:
     refusal is still `talk.close{by:"host",reason:"unavailable"}`, nothing broadcast,
     `state.talk` false. The cached mode can be up to ~1 s stale on API<31 during a switch, which
     does not matter: the question is only "cellular call / ringing" (`MODE_IN_CALL`,
     `MODE_RINGTONE`), never our own `MODE_IN_COMMUNICATION`.
   - **Earcons go on the audio thread** (`LinkHost.earcon(kind, call)`), not on a thread of their
     own: ordering against `enterCall`/`exitCall` is then defined, so the closed/error/OK tones
     are still built after the route is back (media mode) and the live/listen ones inside call
     mode — the price is that an earcon requested during a switch is heard after it (≤1.3 s),
     which for a "that did not work" tone is the right trade. `Announcer` takes the player as a
     lambda (`earconPlayer`) so it does not reach for Main either.
   - **New instrument, `core/MainLag.kt`**: every control message is already stamped on the reader
     thread (`ControlServer.Event.Received.atMs`); the host now compares that with the moment it
     dequeues the event and logs, once per occurrence and only from 100 ms up,
     `Motoparty: control message talk.open waited 930 ms for Main`. Pure builder, format pinned by
     `MainLagTest` (the message name comes from `Message.wireType`, pinned against every
     `@SerialName`). Nothing is allocated below the threshold.
   - **Collapse path re-checked against the device ordering.** `TalkAudio.settle()` re-reads
     `wanted` after `voiceStop()` and immediately before `exitCall()`, so the only remaining
     window is a re-open arriving *while `exitCall` is inside the Bluetooth stack* — which cannot
     be called back. In that case the request is still taken at once (`open()` only records it and
     posts a settle; Main never waits) and the route is re-entered exactly once, after the exit
     returned. New test `TalkAudioTest.reopenWhileExitCallIsBlockedReentersTheRouteExactlyOnce`
     (the fake router blocks in `exitCall`): ops are exactly
     `enter, start, stop, exit, earcon, enter, start`, one exit, `depth` 1. With Main unblocked the
     bench's re-open (172.07, i.e. while `voice.stop` was still running) lands in the earlier
     branch and logs `re-open during teardown`, so the close+open pair does not happen at all.
   - **Not changed**: `AudioRouter.enterCall`'s `am.availableCommunicationDevices` (174–523 ms
     because it is called right after `setMode`, while AudioService tears down A2DP). Reading the
     list *before* `setMode` looks safe — the list is built from the connected communication-capable
     output devices, not from the mode, so the BT SCO/BLE device is in it in `MODE_NORMAL` too —
     but that is read off AOSP behaviour, not measured here, and it would not shorten the 0.9–1.7 s
     Bluetooth link setup, which is not ours. If someone wants it: log the list once in
     `MODE_NORMAL` on a bench run first, then move the read.
   **Next device run (`tools/bench/talk_cycles.sh`)**: in the fast re-open with AirPods expect a
   `TalkAudio: re-open during teardown: call route kept (session N)` line, **no**
   `control message … waited … for Main` lines anywhere, and `talk.open` answered in <100 ms in
   the peer's request table (it was 940 ms in this run). Still unverified on the device: the mode
   listener actually firing (`AudioModeWatch` has no logcat line of its own — a refused
   registration logs `AudioModeWatch: mode listener refused`), and that a delayed error earcon is
   still audible where it should be.

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

- **Talk no longer blocks Main** (layer 1). It used to, for ~2.7 s on open *and* close
  (measured: `talk.close` left the host at 20:31:05.675, `AudioRouter: back to media mode` logged
  at 20:31:08.401): `VoiceEngine.stop()` joined two threads and `AudioRouter.exitCall()` waited on
  the Bluetooth stack, all on Main, so the host's own resume started ~1.2 s late and re-seeked.
  The design now:
  - **Talk collapses to the latest request** (`audio/TalkAudio.kt`, D1 fix, see section 3 item 3):
    it still runs on the thread below, but a queued close + open no longer replay.
  - **One serial thread** (`audio/AudioThread.kt`, a single-thread executor named
    `motoparty-audio`) runs every `enterCall`/`exitCall` and `voice.start`/`stop`, plus the live
    and closed earcons that must play after a switch. Posts run to completion one at a time in
    submission order, so open -> close -> open cannot reorder and a close requested while an open
    is in flight waits for it (no cancelling) and still ends in `MODE_NORMAL`. A queued block
    always runs even if its waiter is cancelled, so enter/exit pairs never come apart. `post`
    returns a `Job` that completes when the block has run or failed; a post after `shutdown` is
    dropped and returns a completed job.
  - **Main never calls the audio system** (D1 bench 4 fix, section 3 item 3): no `AudioManager`
    getter, no `AudioTrack`, no `adjustStreamVolume` on Main — they are binder calls into the very
    service our own route switch is holding. The mode is cached by `audio/AudioModeWatch.kt`, and
    every earcon goes through `LinkHost.earcon`, i.e. the audio thread. `core/MainLag.kt` logs
    `control message <t> waited N ms for Main` if a control message ever waits ≥100 ms again.
  - **Main still decides and sends first**: `LinkHost.applyTalk` sends `talk.open`/`talk.close` +
    `state` (and the resume `music.play`) before queueing the audio work.
  - **Failures come back on Main**: a throwing block is reported through `scope.launch` on the
    caller's scope (per-post `onError`, else the shared logger), *before* its job completes. The
    talk-open block's `onError` and the session's `VoiceEngine` `onFailed` both go to
    `LinkHost.onMicFailed`, which closes talk with `talk.close{by:"host",reason:"unavailable"}`
    (broadcast, since `talk.open` already went out) and plays the error earcon if the Pixel
    triggered. PROTOCOL.md "Talk flow" only describes the host's `unavailable` *instead of*
    `talk.open` and the client's *after* it; a host `unavailable` after its own `talk.open` is
    not spelled out (reported to the coordinator, not edited). A client that treats any
    `talk.close` as a close handles it.
  - **Stale callbacks**: `LinkHost.talkSession` is bumped on every open; the open's `onError`, its
    voice `onFailed` and its live-earcon waiter carry the number and are ignored once a newer talk
    opened. Without this, a late failure of talk *n* could close talk *n+1*.
  - **Ref counting**: `AudioRouter.depth` is only touched on the audio thread (and
    `@Synchronized`). Talk posts one enter per open and one exit per close (`TalkController` makes
    them alternate); the recognizer posts one enter and exactly one exit (`routeBack` guard). The
    announcer never enters call mode. `enterCall` does `depth++` as its first expression, so a
    throw anywhere in it is already counted and the caller's exit balances it. `exitCall`'s
    `MODE_NORMAL` is in a `finally`. `LinkHost.stop` ends with `voice.stop(); router.exitAll()`
    on the audio thread, because a recognizer cancelled by `stop` posts its route-back after
    `shutdown` and that post is dropped.
  - **Music over the switch**: after talk (if music was paused for it) and after a voice command,
    the local player is `sync.hold()`-ed and `sync.release(cold = true)` runs when the route-back
    job completes. `SyncController.apply` then starts at `max(anchor, now + coldLead + prepare)`:
    on time if A2DP is back before the anchor, mid-track on the same timeline if not. "Back" means
    `exitCall` returned; any extra Bluetooth lag is what `coldStartLatencyMs` learns. A re-open
    before the teardown finishes just pauses again (release re-applies the paused anchor).
  - Device-only: the real Main stall is gone, the HFP<->A2DP timing, a mic that fails *after*
    open (`AudioRecord did not start`) and TALK pressed during a voice command (the recognizer
    and `VoiceEngine` briefly both want the mic).
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
  `AudioRouter.enterCall/exitCall` block for ~0.5–1.3 s: audio thread only, messages go out first.
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

1. `./gradlew assembleDebug test` (expect 122 tests, 2 skipped), `adb -s 192.168.1.100:5555 install -r
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
