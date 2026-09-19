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
| `core/CommandParser.kt` | done per the latest spec (per code point, U+2019 -> `'`, keep L*/M*/N*) |
| `link/TalkController.kt` | done (pure state machine, 10 s silence close, link loss) |
| `link/ControlServer.kt` | done. One writer coroutine per connection (socket writes on main threw NetworkOnMainThreadException), pong answered on the reader thread, second hello replaces client (old one gets `bye`), 6 s liveness watchdog |
| `link/VoiceSocket.kt` | done. UDP 47801, peer = source of last valid packet from the control client's IP, running 16 kHz `ts` clock from a random start (`currentTs()`), keepalive every 1 s idle carrying current ts, shared seq |
| `link/Discovery.kt` | done (NSD `_motoparty._tcp`, TXT proto/voice/http) |
| `audio/opus_jni.c` + `cpp/CMakeLists.txt` + `audio/Opus.kt` | done. libopus 1.5.2 static, VOIP/24 kbps/FEC 10 %/DTX/complexity 8 |
| `core/TalkStats.kt` | done. Pure: the `talk stats` loss arithmetic and the exact line format (layer 2) |
| `audio/VoiceEngine.kt` | done. Logs one `talk stats:` line per talk at `stop` (built by `TalkStats`). Frames with `OPUS_GET_IN_DTX == 1` are not sent; every sent/received kind-1 packet = activity. `start`/`stop` belong on the audio thread. Each `start(onFailed)` is a session (an `AtomicReference`, not a `running` flag): a loop that outlives `stop`'s 500 ms join cannot carry on under the next `start`, and only the first failure of a still-current session reports, to that session's `onFailed` |
| `audio/AudioRouter.kt` | done (ref-counted MODE_IN_COMMUNICATION + setCommunicationDevice, prefers BLE headset > SCO > wired > USB). Audio-thread only; `selectedDevice` is published from there instead of queried from Main. `enterCall` counts itself before it can throw, so every caller pairs it with `exitCall` regardless; `exitCall` sets `MODE_NORMAL` in a `finally`; `exitAll` is the shutdown backstop |
| `audio/AudioThread.kt` | done, reviewed, unit-tested (`AudioThreadTest`). The single serial thread every route change and voice start/stop runs on (section 4, "Talk no longer blocks Main"). Not yet run on the device |
| `audio/Earcons.kt` | done (generated tones: LIVE, CLOSED, OK, ERROR, LISTEN) |
| `music/Catalog.kt`, `OkHttpDownloader.kt` | done (NewPipeExtractor v0.26.5; YT Music songs/albums/playlists, artist = top 20 songs; falls back to plain YouTube search; resolves progressive M4A, itag 140 preferred) |
| `music/TrackCache.kt` | done (1 GiB LRU by mtime, 1 MiB Range chunks, UA chosen by the URL's `c=` client, shared in-flight downloads, prefetch) |
| `music/TrackServer.kt` | done (hand-rolled HTTP, GET/HEAD `/track/<id>.m4a`, single Range, 404 otherwise) |
| `music/Player.kt` | done (ExoPlayer, no auto audio focus, MediaSession with `onMediaButtonEvent`, `speed`) |
| `music/SyncController.kt` | done. Prepared start with a learned start-up latency lead, check 2 s after start then every 10 s, 80 ms–1 s corrected by speed nudge (≤5 %), >1 s re-seek. The lead is now learned with a damped 1/4 step (`LEARN_DIVISOR`), not a mean of two, which was ringing on A2DP. `PlayerControls` was split out of `Player` so all of this is unit-tested. Two learned leads now: `startLatencyMs` (warm) and `coldStartLatencyMs` (a start into a route that was just rebuilt — the resume after talk or a voice command); `hold`/`release` are nesting. Layer 2 logging: `nudge done:` and the `trace:` lines (section 3 item 3); logging only. Since D2: every drift figure is a 9-sample/2 s median, and 80 ms..1 s is corrected only after a second, agreeing reading (section 3 item 3, "D2 bench") |
| `music/MusicController.kt` | done (queue, load → ready ≤8 s / music.error → play at now+300, pause/resume, next/previous, talk pause + resume at now+resumeLeadMs, duck mode, mid-track join on client connect, prefetch + music.load of next) |
| `voicecmd/Transcriber.kt` | done (on-device recognizer when available, falls back to default on language/client errors) |
| `voicecmd/Announcer.kt` | done (TTS, USAGE_ASSISTANT, earcon first) |
| `LinkHost.kt` | done: all wiring and protocol decisions, main thread, `guarded{}` around event loops. Talk and recognizer audio work goes through `AudioThread`; `talkSession` drops late callbacks of an earlier talk |
| `LinkService.kt` | done (FGS types microphone\|mediaPlayback\|connectedDevice, drops microphone if SecurityException; wake + Wi-Fi low-latency locks; notification actions Talk/Command/Stop) |
| `overlay/OverlayService.kt` + `overlay/OverlayPlacement.kt` | done. The position is stored as a fraction of the free travel and clamped on restore, on every layout, on rotation and during the drag itself; the landscape bug is fixed in code (device check still open). `OverlayPlacement` is pure and unit-tested |
| `trigger/Trigger.kt` | done |
| `ui/MainScreen.kt`, `MainActivity.kt` | done (status, permissions, TALK/COMMAND, now playing/queue, search, settings, log). Still not seen rendered: the one screenshot attempt caught another app in the foreground |
| `Settings.kt`, `Hub.kt`, `MotopartyApp.kt` | done |
| `tools/fetch_opus.sh` | done |

Tests (`app/src/test/...`), 111 in all by the last run (the list may lag), 2 skipped: `CodecTest` (14), `ClockEstimatorTest` (1),
`VoicePacketTest` (3), `CommandParserTest` (2), `JitterBufferTest` (17), `TalkStatsTest` (4),
`TalkControllerTest` (8), `ControlServerTest` (7, real loopback sockets), `TrackServerTest` (5),
`SyncControllerTest` (23),
`OverlayPlacementTest` (8), `AudioThreadTest` (6: order and one-at-a-time, failure to the shared
handler on the caller's scope, per-post handler, failure delivered before the job completes,
cancelled waiter does not cancel the work, post after shutdown), `CatalogNetworkTest` (2, skipped
unless `-Pnetwork`). Every fixture section is consumed by a
test (`messages`/`valid`/`invalid`/`unknown`/`malformed`/`fatal`/`steps`/`conversions`/`cases`);
none is silently skipped.

## 2. Verified, and how

- `./gradlew assembleDebug test` -> BUILD OK, 111 tests, 109 pass, 2 skipped (network), 0 fail
  (after the D2 filtered-drift fix, 2026-09-19).
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

1. `./gradlew assembleDebug test` (expect 111 tests, 2 skipped), `adb -s 192.168.1.100:5555 install -r
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
