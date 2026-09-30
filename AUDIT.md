# Audit, 2026-09-30: bugs, latency, music, UI/UX

Seven read-only subagents scanned the tree at `7cedc94` (Android music, Android talk, Android host
lifecycle, Android UI, iOS audio/link/music, iOS UI, protocol conformance). The coordinator
re-read the code behind every item marked **(checked)**; the rest are the agents' traces and are
labelled *traced* (code path followed end to end) or *plausible* (mechanism sound, not observed).
Nothing was run on a device. Baseline at the time: android 326 tests / 0 fail / 5 skipped,
`lintDebug` 0 errors / 23 warnings, peer 260 pass + 1 skipped; `swift test` could not run (no
`swift` on PATH this session).

IDs are stable: implementation reports and commits refer to them. Paths without a prefix are
under `android/app/src/main/java/com/kivan/motoparty/`; iOS paths are under `ios/Sources/`.

## Status

- **Round 1 done 2026-09-30, device-unverified** (coordinator-run: android 361 / 0 fail / 5 skipped,
  lint 0 errors; iOS `swift test` 142 + release build clean; peer 280 + 1 skipped):
  R1–R9, L1–L7, L10, P1, P2, P5, P6, P8, P9, P10, U-D1, U-D2. Spec: PROTOCOL.md "Voice" (spurts,
  shedding, `fixtures/jitter.json`), "Clock" (`500 + rtt/2`, `stepReset` vectors), "Liveness"
  (same-name reconnect). Device checklists: `android/HANDOFF.md` "Audit round 1", `ios/README.md`
  "2026-09-30 audit round". Left from R2: the Ride tab must show `LinkStatus.micOff` (round 3).
- **Round 2 spec is written (2026-09-30), code not yet:** PROTOCOL.md Music flow 3 (M1 lead, M9
  one re-load), Music flow 6 + `music.next` (M5 gapless), `state.queue` items with optional
  `durationMs`/`art`, Discovery 2 and 5 (P4), Talk flow 1 (P7). `fixtures/control/messages.json`
  still needs a `music.next` vector and a queue item with `durationMs`/`art` (add them when the
  three codecs are changed, or their round-trip tests fail).
  **Update, same day:** the codecs and vectors are in (Kotlin `MusicNext`, `QueueItem.durationMs/art`;
  Swift `.musicNext`; Python schema; android 362, swift 142, peer 288 + 1). Round 2 behaviour was
  handed to four agents by file ownership: playback (`MusicController`/`SyncController`/`Player`:
  M1, M5, M7, M9), downloads (`TrackCache`/`TrackServer`/`CollectionDownloads`/`Catalog`: M3, M4,
  M6, M8, P3; its new priorities must still be wired into MusicController/LinkHost call sites by
  the coordinator), host (H6, H7, H8, P3, P11, queue items in `state`), iOS (M2, M5, M10, M11, P3,
  P4, P7, H9). Not yet verified by the coordinator; the Python peer has no `music.next` behaviour yet.
- **Round 2 Android + spec done 2026-09-30, device-unverified** (coordinator-run after integration:
  android 403 / 0 fail / 5 skipped, lint 0 errors): M1, M3–M9, H6–H8, P3, P11 (host side). The
  download priorities, `retain`/`protect`/`preResolve` and the spoken "End of queue" are wired
  (`MusicController.cacheHints`, `LinkHost`). Gapless cancel rule settled in PROTOCOL.md Music
  flow 6: any `music.play` message except the one of the change cancels a pending `music.next`
  (the host's way of taking it back), a `state` on the unchanged anchor does not, and the host
  repeats `music.next` after every `music.play`. P11 is in the spec ("Control channel") and in
  `fixtures/control/framing.json` `fatal` (four invalid-UTF-8 vectors). Device checklist:
  `android/HANDOFF.md` "Audit round 2", `ios/README.md` "2026-09-30 audit round 2".
- **Round 3 done 2026-09-30, device-unverified** (coordinator-run: android 450 / 0 fail / 5 skipped,
  lint 0 errors; `assembleRelease` succeeds, 7.9 MB): UA1, UA3–UA10, UA12–UA14, UA2 (overlay), UA11
  (icons), micOff banner, improvements #1–#12, #14, #15, H5 (R8 release build), H10 (dependency
  bumps), L9 partly, iOS UI rework. Skipped: ambient art gradient (needs `androidx.palette`), marquee
  title, mic level meter, iOS 26 tab accessory, Live Activity, L8 leftovers (JNI critical arrays,
  `DatagramPacket` reuse, `send(buf, len)`). Device checklist: `android/HANDOFF.md` "Audit round 3",
  `ios/README.md` round 3.
- **Tap-to-sound fix 2026-09-30, measured on the Pixel (release build, laptop peer as the
  passenger):** the wait was the WebM → MP4 remux, 5–10 s per song, all of it in the platform
  `MediaExtractor.advance()` (~0.4 ms per packet). `music/Remux.kt` now demuxes with Media3's
  `MatroskaExtractor` and writes through `Mp4Muxer` with sample batching: 50–700 ms per song
  (`download <id>: remuxed in N ms`). The top three search results are now looked up three at a
  time instead of one after another. Enqueue → sound went from 8–16 s to 1.6–1.7 s for a
  pre-resolved result (0.6 s of that is the scheduled start), ~3–4 s for one not yet looked up
  (the YouTube lookup is 1.5–2 s). `RemuxTest` (Robolectric, `opus-3s.webm` fixture) checks packet
  count, bytes, times and `dOps`. Queue Undo is one `state` push (`MusicController.insert`).
  Also seen on the Pixel: gapless change between two cached tracks, end of queue parks paused at 0.
  The MP4 layout changed (samples in chunks): **play one on the iPhone before trusting it**.
  Android 454 tests / 0 fail / 5 skipped.
- **Open:** see `HANDOFF.md` "Do now" for what is in progress (iOS/peer round-2 integration,
  round 3 UI + H5, L8, L9, H10).

## User decisions from this audit (2026-09-30)

- **U-D1 · The iPhone lock-screen / Control Center Pause works.** Map `pauseCommand` to
  `music.control pause` and `playCommand` to resume-only (never a toggle). Accepted cost: a bud
  that sends "pause" when taken out of the ear now pauses the ride's music. That reverses the
  2026-09-29 "`case .pause: break`" guard in `AppModel.swift:769`.
- **U-D2 · When the Pixel's earbuds drop, the music pauses** instead of moving to the Pixel
  speaker. It is shared music, so pause through `MusicController` (both phones), not only the
  local ExoPlayer (`setHandleAudioBecomingNoisy(false)` at `Player.kt:52` today).
- **U-D3 · No control-link authentication for now** (not publishing the APK). See H11.

## Implementation plan

Three rounds, each fanned out to parallel subagents that **own directories, not topics**, so no
two agents edit the same file in one round. `LinkHost.kt` belongs to the host agent in every
round; the others describe the call they need and the host agent wires it. The coordinator re-runs
`cd android && ./gradlew testDebugUnitTest lintDebug --rerun-tasks`, `cd ios && swift test` +
`xtool dev build -c release`, and `cd tools/peer && .venv/bin/python -m pytest -q` before calling
a round done, and updates `PROTOCOL.md` + `fixtures/` itself where a finding changes the wire.

1. **Round 1: ride-breaking bugs and voice latency.** R1–R9, L1–L4, L6, P1, P2, P5, P6, I1–I5,
   U-D1, U-D2.
2. **Round 2: music.** M1–M11, H6–H8, P3, P4, P7, and gapless (M5, which needs a PROTOCOL addition).
3. **Round 3: UI/UX on both phones.** Everything under "UI bugs" and "UI improvements", plus the
   release build (H5).

Every latency and sync claim needs a device check afterwards; add each to the device checklists
in `android/HANDOFF.md` and `ios/README.md`.

---

## Ride-breaking bugs

**R1 · iOS · high · The locked iPhone gets suspended after a call or Siri** (checked).
`Motoparty/Audio/KeepAlive.swift:18-19` guards on its own `isRunning` flag. An interruption stops
the silent engine without a guaranteed `AVAudioEngineConfigurationChange`, so the flag stays true
and `restoreMediaRoute()` → `keepAlive.start()` (`AppModel.swift:878-898`) returns at once. The
first host pause while locked lets iOS suspend the app: link, volume keys and hold-to-talk die
until the passenger unlocks. Fix: check `engine.isRunning` in `start()`, and/or `keepAlive.stop()`
in `interruptionBegan`.

**R2 · Android · high · After a sticky restart the intercom is silently mic-less, and may
crash-loop** (checked). `LinkService.kt:113-134` catches only `SecurityException`. On a background
START_STICKY restart the microphone FGS type is refused, `Hub.micFgsType=false`, and every talk
press gives the error earcon; every client `talk.open` gets `unavailable` (`LinkHost.kt:1379`).
Nothing in the UI or the notification says so. *Plausible:* the fallback `startForeground` is
unguarded and can throw `ForegroundServiceStartNotAllowedException` (an `IllegalStateException`)
→ crash in `onCreate`, repeated with backoff. Fix: catch `IllegalStateException`; call
`startInForeground()` again on `ACTION_TALK` when `!micFgsType` (a notification action is a
while-in-use exemption); show "Microphone off – tap to restore" in the notification and on Ride.
Process death has never been tested; add it to the device checklist.

**R3 · Android · high · A passenger who joins a solo talk turns the rider's conversation into
commands** (checked; found by two agents). `ClientConnected` (`LinkHost.kt:405-415`) never touches
the first-phrase gate, which was fixed to SOLO at open (`:545-551`). The iPhone gets
`state{talk:true}` and joins; every rider phrase then runs as a command (`next…`, `play…` close
the talk) or produces "Didn't catch that", which `announce` (`:1132`) also sends to the iPhone.
Realistic trigger: a Wi-Fi blip closes the talk, the rider presses talk alone, the iPhone
reconnects. Fix: on connect with a SOLO talk open, re-open the gate as OTHER (or OPENER with the
window spent) and stop the recognizer.

**R4 · Android · high · One ExoPlayer error leaves the Pixel silent for good** (checked: no
`onPlayerError` anywhere). `Player.kt:91-95` listens only for `STATE_ENDED`. After any
`PlaybackException` (plausible triggers: `AudioTrack` start failing during the HFP→A2DP switch, a
cached file evicted then reopened on seek, a decoder error) ExoPlayer is IDLE while the anchor and
UI say playing, and `SyncController.read()` returns null every 10 s. Restarting the same track does
nothing: `load()` returns early on `loadedId` (`:99`) and `play()` never calls `prepare()`. Fix:
`onPlayerError` → log, `loadedId = null` (or `prepare()`), re-apply the anchor cold; after two
failures skip the track and say so in the UI.

**R5 · Android · high · A failed track load kills the queue** (traced). `MusicController.kt:396-399`,
`TrackCache.kt:81-103`. A mid-download signal drop (one 1 MiB chunk past the 20 s read timeout), a
NewPipe extraction failure, or an age/region-locked video ends in "Couldn't load X". `sync.apply(null)`
already ran, so resume and play/pause do nothing; no retry, no skip even when later tracks are
cached; a retry restarts from byte 0 and re-fetches stream info. Fix: per-chunk retry with backoff
resuming at `offset`; on final failure skip to the next playable (prefer cached) track; for "no
network" keep the track and retry when connectivity returns.

**R6 · Android · medium · Rotating the Pixel after Stop restarts the host** (checked).
`MainActivity.kt:43` calls `LinkService.start(this)` unconditionally in `onCreate`; any recreation
(rotation, font scale, dark mode) brings the host back. Fix: `if (savedInstanceState == null)`.

**R7 · iOS · medium · Data race / possible crash when the link drops mid-talk** (traced).
`AppModel.swift:563-564` reads `self?.voice?.sendAudio` on `captureQueue` 50×/s while `linkLost`
(`:285-289`) sets `voice = nil` before `closeTalkLocally()` stops the engine. Fix: capture the
socket instance at talk start; stop the voice engine before tearing the socket down.

**R8 · iOS · medium · "Headset gone, don't blast the speaker" is undone by the next message**
(traced). `AppModel.swift:904-906` suspends music, but the next `state` or `music.play`
(`:428-431`, `:365` → `startMusicIfPossible` → `SyncedPlayer.play`) restarts it on the pocket
loudspeaker. Fix: a `suspendedForRoute` flag checked in `startMusicIfPossible`.

**R9 · Android · medium · Pause during a load or the `music.ready` wait is lost** (traced; found by
two agents). `MusicController.kt:157` returns when the anchor is null; the start job then calls
`playFrom` and both phones play. Fix: a "paused while starting" flag; `playFrom` parks the track
paused (as it already does for talk); `resume()` clears it.

## Voice latency

Estimated mouth-to-ear today, one direction (hardware figures are estimates):

| Stage | Earbuds (SCO) | Lark host-mic |
|---|---|---|
| Mic + BT/USB uplink | ~20–40 | Lark RF (unmeasured) + USB ~5–20 |
| HAL capture period + AEC/NS | ~10–20 | none |
| 20 ms frame (fixed by decision) | 20 | 20 |
| Opus lookahead (VOIP, 16 kHz) | 6.5 | 6.5 + 1.3 decimator |
| Encode, send, Wi-Fi | ~3–10 | ~3–10 |
| Jitter buffer | ~50 (**up to 400**, L1) | on the iPhone |
| Playback buffer, parked full | ≥60 (L3) | passenger local ≥60, probably more |
| Mixer/HAL output period | ~10–20 | on A2DP |
| Output link | SCO ~20–40 | A2DP 150–250 (decided route) |
| **Total** | **~200–280 ms** (+ up to 350 backlog) | rider→passenger ~250–370; passenger→rider ~250–350 |

**L1 · both · high · The jitter buffer never sheds a backlog inside a spurt** (checked on Android;
the iOS agent found the same in `MotopartyCore/JitterBuffer.swift:191-196, 222-228`). A spurt
starts at the *oldest* queued packet and the only limit is the 400 ms cap (`core/JitterBuffer.kt:165-177`).
The earbud mic never DTXes, so a whole talk is one spurt. Simulated against the compiled class:
steady stream 50 ms; after one 700 ms playout stall (route switch, SCO flap, blocked `write`)
**390 ms for the rest of the talk**; output clock 100 ppm slow → +58 ms after 10 min; 0.1 % slow →
pinned at the cap. A cheap USB receiver against the Pixel can be hundreds of ppm apart. Fix: at
spurt start drop entries with `arrivedMs + target < now` (what PROTOCOL.md already says); during a
spurt, when the minimum depth over ~1 s stays above target + 40 ms, drop one frame, preferring the
quietest (NetEq "accelerate"); add mean/max depth to `talk stats`. Mirror in Swift and the peer.

**L2 · Android · medium · The first 220 ms of an utterance after a pause can be dropped**
(simulated). `JitterBuffer.kt:118-143` vs `:186-190`: a new spurt is only recognised in `pull()`
when its first packet arrives early; if it arrives later than the old spurt's timeline, `insert()`
drops it as an underrun. Bunched Wi-Fi-wake arrival lost 11 frames; a steady +60 ms lost 5 then
re-anchored. The spec says a `ts` jump with contiguous `seq` is a new spurt. Fix: in `insert()`,
when late but `onlyKeepalivesBefore(s)` holds and `ts` jumped past `nextTs`, end the spurt and
queue the packet. Check the Swift buffer for the same.

**L3 · Android · medium · Playback tracks sit at a full buffer** (plausible).
`audio/VoiceEngine.kt:487` writes blocking and pre-fills silence, so the first voice frame waits
behind ≥60 ms. `audio/LarkEngine.kt:281`: the passenger track is a normal mixer track on A2DP
(LOW_LATENCY almost certainly refused), starts only when full, and the non-blocking writer never
lets it drain. Nothing logs the granted performance mode, `bufferSizeInFrames` or `underrunCount`.
Fix: log those at talk stop; then `setBufferSizeInFrames(~2 frames)` on the talk track guarded by
`underrunCount`; hold a target fill with watermarks on the Lark track.

**L4 · Android · medium · Lark playback clicks on partial writes** (traced).
`LarkEngine.kt:220-229`: a non-blocking `write` returning 0 < w < 960 is counted "dropped" but its
head was already written, breaking the waveform. Frequent because the fill sits at full (L3). Fix:
write only when free space ≥ one frame, else drop the whole frame.

**L5 · Android · low-med · Capture threads call `getRoutedDevice()` every 25 frames** (plausible).
`VoiceEngine.kt:349-351` (whole talk on any non-SCO route), `LarkEngine.kt:231` (always). It can
binder into audioserver, measured blocked 1.0–1.25 s during route changes; the 200 ms recorder
buffer would overrun. Fix: poll from the existing `voice-route`/`lark-route` HandlerThread and pass
the result through the existing `AtomicReference`.

**L6 · iOS · medium · Voice processing ducks the app's own earcons and TTS during an own-mic talk**
(documented iOS 17+ behaviour, not seen). `Motoparty/Audio/VoiceEngine.swift:182`. Minimal fix:
`inputNode.voiceProcessingOtherAudioDuckingConfiguration = .init(enableAdvancedDucking: false,
duckingLevel: .min)`. Better: play cues and TTS through an `AVAudioPlayerNode` in the talk engine
(AEC then keeps them out of the mic too).

**L7 · Android · low · Earcon `AudioTrack.release()` runs on Main** (traced). `audio/Earcons.kt:97-109`,
against the "Main never calls the audio system" rule, ~200 ms after the live beep while the route
is still changing; a throw after `build()` leaks the track. Fix: non-Main looper for the listener and
release; try/finally around write/play.

**L8 · both · low · Allocation on audio threads** (traced). Android: ~6–10 small allocations per
20 ms (`audio/Opus.kt:28`, `link/VoiceSocket.kt:74-77`, `JitterBuffer.pull`; JNI
`Get*ArrayElements` copies). iOS: the sink allocates a `[Float]` and `captureQueue.async` per I/O
cycle (`VoiceEngine.swift:268-270`); render allocates in `OpusVoiceDecoder.run` and
`pcm.append/removeFirst` under a lock the network queue also takes (`:476-491`). Fix: preallocated
rings, fixed decode buffers, reused `DatagramPacket`, `GetPrimitiveArrayCritical`.

**L9 · time-to-talk.** Earbuds: press → live beep ≈ 2.1–2.5 s (enterCall 199–563 ms; wait for
`bt_sco` as communication device ~1.6–1.9 s; recorder open; first frame +60–170 ms; MicLive ≥200 ms;
earcon track built on demand). (a) *Traced:* pre-build the live earcon (static mode, data written)
while capture waits, so firing is only `play()`. (b) *Device test:* open the recorder right after
`enterCall` with `setPreferredDevice(<SCO input>)` instead of waiting for the late report (could
save 184–530 ms); F9c showed a recorder migrating builtin→SCO stays near-silent, so check
`capture routed to bt_sco` comes first and ~0 DTX. Lark: drop the 150 ms `PREROLL_MS`
(`Earcons.kt:77-81`) in Lark talks, where A2DP is already streaming the passenger track (~150 ms).
Also: the Lark local monitor could read 5–10 ms chunks separately from the 20 ms Opus framing (~−15 ms).

**L10 · iOS · low · Talk-session preferences leak into media mode** (plausible).
`SessionController.swift:71-72`: 16 kHz preferred rate and 10 ms IO buffer are never reset for
`.media`/`.listen`. Fix: reset to 48 kHz and default duration on media activation.

## Link and protocol

**P1 · both · medium · A fast reconnect briefly opens a stale talk, then kills it** (plausible).
`hello` + `state` go out at TCP accept, before the client's hello (`link/ControlServer.kt:137-138`);
the new hello replaces a connection the host still thinks is alive (`:189-199`) and
`LinkHost.kt:406-410` closes the talk with `link`. The iPhone has already applied `state{talk:true}`
and opened HFP + mic + live beep: a 2–3 s HFP bounce, two earcons, talk lost. Fix: when a client
with the same name replaces its own connection, keep the talk and re-learn the voice peer.

**P2 · Android · low-med · Connect-time `state` is built off Main** (traced; found by two agents).
`ControlServer.Connection.start()` on `Dispatchers.IO` calls `stateNow()` → `LinkHost.state()`
(`:378-385`), reading Main-only `talk.isOpen`, `queue`/`index`, `sync.anchor`, `talkMic`. A torn
queue/index can reach the client. Fix: a `@Volatile` snapshot published in `pushState`, or build
hello+state on Main.

**P3 · both · medium · Control and track traffic are unmarked** (plausible, unmeasured). Voice is
EF on both sides (`link/VoiceSocket.kt:37`, `Motoparty/Link/VoiceSocket.swift:78`); control TCP
(`ControlServer.kt:133`, `ControlClient.swift:40-43`) and track HTTP (`music/TrackServer.kt:52`,
`Motoparty/Music/TrackCache.swift:29`) are best effort, so `talk.open`/`state`/`pong` queue behind
a multi-MB track download on the hotspot. Fix: control `trafficClass = 0xA0` (CS5) / iOS
`serviceClass = .signaling` or `.responsiveData`; tracks `trafficClass = 0x20` (CS1) / iOS
`networkServiceType = .background`.

**P4 · iOS/spec · medium · Rejoin after a host restart takes ≥10 s** (logic traced).
`MotopartyCore/HostSelection.swift:22, 37-44` keeps the 10 s failure backoff across discovery runs;
rediscovery waits a fixed 1 s (`AppModel.swift:301`). A probe that hits port 47800 before the host
listens gets ECONNREFUSED and both `bonjour:` and `ip:` candidates back off 10 s. Fix (spec change):
probe the last host's address at once on link loss; exempt the preferred host from backoff, or
~1 s backoff for "refused".

**P5 · iOS · low-med · One slow pong can throw away a good clock estimate** (traced).
`MotopartyCore/ClockSync.swift:33` resets on a >500 ms step regardless of the sample's RTT; a
>1 s RTT pong (TCP retransmit) can alone exceed it, and a `music.play` in the next 2 s is then off
by up to rtt/2, corrected only by the ±2 % rate over 25–50 s. Fix: reset only when |Δ| > 500 + rtt/2,
or when two samples agree. Also `clock.reset()` on every reconnect (`AppModel.swift:265`) discards
a good window for the same host. Add a fixture for the step reset (none today).

**P6 · iOS · low · A stale `state{talk:true}` reopens a talk the client just closed** (traced).
`interruptionBegan` (`AppModel.swift:878-886`) and `mediaServicesReset` (`:914-924`) send
`unavailable` without setting `micUnavailable`; an in-flight `state{talk:true}` → `apply` (`:402`)
→ `openTalkLocally` (`:525`) reopens into the phone call, fails, sends a second close. Fix: set
`micUnavailable = true` in both paths.

**P7 · iOS · low · A second press can't cancel a pending talk request** (traced).
`AppModel.swift:494-500` sends another `talk.open` while the first is in flight (the host ignores
it, `link/TalkController.kt:28`); `talkRequested` has no timeout, so TALK can stay "TALK…" forever.
Fix: when `talkRequested`, send `talk.close`; add a ~5 s timeout. Optional spec change: a talk id,
so a crossing client close cannot end a newer talk.

**P8 · iOS · low · Voice keepalive cadence misses the spec** (traced). iOS ticks every 1 s and sends
when ≥1 s since the last audio (`VoiceSocket.swift:35, 110-112`), so a gap can reach ~2 s; the host
ticks every 250 ms. Fix: 250 ms tick. *Plausible:* 1 Hz traffic may not keep the iPhone's Wi-Fi out
of power save, costing up to ~100 ms on the first talk message after idle; measure pong RTT with 2 s
vs 50 ms pings.

**P9 · Android · low · Client swap race in `ControlServer.close()`** (traced, rare).
`ControlServer.kt:237-241` checks `active === this` then sets null, not atomic with B's
`active = this` (`:192-193`); B ends up connected but ignored. Fix: `AtomicReference.compareAndSet`
or one lock around the swap and its events.

**P10 · Android · low · `bye` on Stop never reaches the client.** `hostScope.cancel()`
(`LinkService.kt:95`) right after `control.stop()` cancels the writer's drain-then-close
(`ControlServer.kt:228-230`); the client sees a bare EOF.

**P11 · conformance gaps.** Invalid UTF-8 in a frame: Kotlin replaces with U+FFFD and processes
(`core/Codec.kt:172`), Swift's JSONSerialization rejects and closes (`MotopartyCore/Messages.swift:342`);
spec silent: pick one, add a fixture. The host never checks the client `hello.proto`
(`ControlServer.kt:189-190`); spec silent. Rules with unit tests but no shared vector: clock
>500 ms step reset; `state{talk:false, mic:"host"}` kept; `hello` with wrong proto; client
`talk.open` carrying `mic`; invalid UTF-8, zero-length frame, exactly-64 KiB frame; jitter-buffer
adaptation; Browsing (200-track cap, `next`/`end` as `now`, stale edit ignored); the mid-track-join
sequence. Everything else matches spec ↔ Kotlin ↔ Swift, including TCP_NODELAY on both ends.

## Music

**M1 · Android · high · The Pixel clips 250–750 ms off every track start and every resume**
(checked). `MusicController.kt:470` `START_LEAD_MS = 300` puts the anchor at now+300;
`music/SyncController.kt:145` starts at `max(anchor, now + lead + PREPARE_MS)` with lead ≥300
(350–700 once learned on A2DP) and `PREPARE_MS = 250`, so `startAt` is always past the anchor and
the player seeks to `expectedAt(startAt)`. Default lead: Pixel starts at 250 ms; learned 600 ms →
550 ms. The iPhone may play the intro, so the two phones hear different openings; plain resume
jumps forward the same amount. Fix: anchor at `now + max(startLatencyMs, cold?coldStartLatencyMs)
+ PREPARE_MS + margin`, exposed from `SyncController`; update PROTOCOL.md step 3 ("now + 300");
check iOS does not clamp the same way.

**M2 · iOS · medium · `outputLatency` is probably counted twice** (reasoned from Apple's documented
semantics, not measured; answers the open HANDOFF question). `AppModel.swift:100-103`,
`Music/SyncedPlayer.swift:170-173`, `MusicAnchor.targetPlayerPositionMs`. AVPlayer's item timeline
is the *heard* timeline (that is how it lip-syncs video over BT/AirPlay), so `setRate(1, time:T,
atHostTime:H)` already means "T is audible at H". Adding `outputLatency` makes the iPhone early by
it (typically 100–250 ms on A2DP), and `DriftController` can't see it because it measures against
the same target. Test: log `outputLatency`, drop the term (trim only), compare with a click track.
Related: right after a talk closes `outputLatency` may still read the HFP/transitional route →
a step error and 10–20 s of rate correction after every own-mic talk.

**M3 · Android · medium · Tap → first sound is fully serial** (order traced, timings estimated).
`MusicController.kt:371-392`, `TrackCache.kt:72-119`: stream lookup (~1–3 s) → whole file in
sequential 1 MiB ranges → remux → `music.load` → iPhone pulls the whole file (~5 MB, 1–2 s on
2.4 GHz) → `music.ready` → 300 ms lead (+ M1). 5–15 s on LTE. Fix: 3–4 parallel ranges; resolve
stream URLs for the top search results and upcoming tracks before they are tapped (they stay valid
for hours); longer term, start the host from the partial AAC (itag 140) while the Opus downloads.
iOS: consider sending `ready` once the file is progressively playable.

**M4 · Android · medium · Downloads have no priority and are never cancelled** (traced).
`TrackCache.ensure` runs in the host scope's `async`, so cancelling a start/prefetch job doesn't stop
it; rapid next-next-next leaves several full downloads competing. The old prefetch keeps fetching
the old next 3 until the new track starts (`prefetchNext` only after `playFrom`, `:393`).
`enqueue`/`shuffle`/`remove` (`:95, 116, 125`) prefetch while the current track is still loading,
against the "never races the current one" comment, and can get the next track's `music.load` to the
iPhone first. `CollectionDownloads.kt:415`'s one-at-a-time lock only covers album downloads. Fix:
one scheduler with priorities (current > next > other prefetch > album), cancel superseded
downloads but keep their bytes, no prefetch while the current track loads.

**M5 · both · medium · Track changes are not gapless** (mechanism traced, gap estimated).
`Player.kt:93` → `onTrackEnded` → `startCurrent`: pause, load, prepare, new anchor at now+300, host
starting at now+lead+250 → ~0.6–1 s of silence plus M1's clip on both phones. iOS: new
`AVPlayerItem` per track, so a >250 ms preroll slips the start too. Fix: once the next track is
ready on both phones, send a scheduled `music.play{next, 0, atHostTimeMs = end of current}` ahead of
time (PROTOCOL addition) and queue the item in ExoPlayer / a second prerolled AVPlayer or
`AVQueuePlayer` on iOS.

**M6 · Android · low-med · Disk scans on Main every second** (traced; found by two agents).
`LinkHost.kt:1344` `caches.active.sizeBytes()` lists and stats every cached file from
`refreshStatus` (1 Hz, every `pushState`, every control event); `refreshCached` → `ids()` on Main
after each download (`:1307`). Same thread times `play()`. Fix: running size and id set inside
`TrackCache`, updated on download/eviction.

**M7 · Android · low · End of queue wipes everything** (traced). `MusicController.kt:137` →
`stop()` (`:210-219`): album end or "next" on the last track clears queue and now-playing; previous
and resume have nothing left. Fix: park the last track paused.

**M8 · Android · low · Eviction ignores what is playing** (plausible). `TrackCache.kt:126-136` is
LRU by mtime, touched only in `ensure`. A big album Download (esp. the 256 MB AAC cache) can evict
the current or announced-next track → iPhone GET 404 (ignored), a re-seek reopens a deleted file →
R4. Fix: pass `evict` a keep-set (current + next 3).

**M9 · Android · low · A failed iPhone download is never retried** (traced).
`MusicController.kt:246-265`: only "not decodable" triggers `resend`; any other `music.error` on the
current track leaves the host playing alone for the rest of it. Fix: resend once after ~2 s for any
error on the current track (the mid-track-join path exists).

**M10 · iOS · low-med · The "end" earcon is probably lost in the HFP→A2DP switch** (plausible).
`AppModel.swift:677-679` plays the 190 ms cue right after `activate(.media)`, while the buds' A2DP
stream is still coming up (~1 s on the bench). Fix: play it while still on HFP and switch after, or
pre-roll silence as Android does.

**M11 · iOS · low-med · A media-services reset doesn't rebuild the players** (Apple docs).
`AppModel.swift:914-929`, `SyncedPlayer.swift:18` (`let player`), `EarconPlayer.swift:9` (cached),
`Announcer`. Apple says dispose and recreate every player after a reset; as written music and cues
stay dead. Answers one of `ios/README.md`'s open questions.

**Minor (music):** `TrackCache.ensure` check-then-`computeIfAbsent` race can duplicate a download
(wasteful only); `TrackCache.prefetch` is dead code; a failed download leaves its `.part` until
next start (harmless).

## Android host and build

**H1–H4:** see R2, R3, R6, P2 above.

**H5 · build · medium · The Pixel runs a debug build; release has R8 off.** `HANDOFF.md` "Pixel
debug", `app/build.gradle.kts:41` `isMinifyEnabled = false`. Debuggable ART + unshrunk Compose is
visibly less smooth. Fix: locally signed release build with R8, keep rules for
NewPipeExtractor/Rhino and kotlinx.serialization; baseline profile optional.

**H6 · medium · A stale notification can remain after Stop** (code traced, platform ordering
plausible). `LinkService.onDestroy` (`:91-99`) → `host.stop()` resets `Hub.status`
(`LinkHost.kt:368`); the `distinctUntilChangedBy` collector (`:54-58`) is still alive until
`super.onDestroy()`, runs inline on `Main.immediate` and `notify()`s after the system removed the
FGS notification → a leftover "Waiting for passenger" whose Talk button restarts the host. Fix:
cancel collectors first, or `nm.cancel(NOTIFICATION_ID)` last, or `repost` checks `host != null`.

**H7 · medium · While the USB probe runs the rider can't end a passenger's talk** (traced, debug
feature). `onTrigger` (`LinkHost.kt:859-863`) rejects every press while `usbProbe.isRunning`;
`onClientTalkOpen` (`:464-471`) never checks the probe. Fix: block only opens; refuse or stop the
probe on a client `talk.open`.

**H8 · low · The overlay redraws every second** over the navigation app: it collects all of
`Hub.status` (`overlay/OverlayService.kt:92, 270-278`) and rebuilds a `GradientDrawable`. Fix:
`distinctUntilChangedBy { talkOpen to clientName }`.

**H9 · iOS · low · Every link flap disarms and re-arms the volume keys** (traced).
`AppModel.swift:44-48, 300`: `.searching`/`.connecting` disarm, rewriting the system volume → two
audible jumps and HUD flashes per hotspot hiccup. Fix: stay armed for ~10 s during reconnect.

**H10 · dependencies** (checked against repository metadata 2026-09-30): AGP 9.4.0 → 9.4.1; Kotlin
2.4.10 → 2.4.20; Compose BOM 2026.08.00 → 2026.09.00; core-ktx 1.19.0 → 1.19.1; Gradle 9.6.1 →
9.8.0. Media3, OkHttp, coroutines, lifecycle, activity-compose current. Lint warnings harmless
(`InlinedApi` with `minSdk 29`, could be 34; one `DataExtractionRules`; `UseKtx` hints). Re-check
versions before bumping.

**H11 · security · parked (U-D3).** Ports 47800–47802 are unauthenticated; any LAN device sending
`hello{role:client}` replaces the iPhone and receives the rider's voice. Limited to hotspot members.

**Checked and fine:** FGS types/permissions (`connectedDevice` prerequisite declared), partial wake
lock and deep Doze (bench-verified), Settings/History stores (all writers on Main), NSD retry,
half-open TCP (6 s watchdog), client drop mid-talk/mid-song, queue index arithmetic, partial files
never served, range parsing / 416, hold/release pairing, atomic rename after remux, SupervisorJob
host scope, TalkAudio collapse, stale-failure session guards, PcmTee/PcmDump never block, FEC frame
size, bounded route-thread joins, recognizer EOF, iOS capture-queue ordering at talk begin, Opus
render always 320 samples, ControlClient/Discovery reconnect, bounded iOS buffers. Known, not new:
`WIFI_MODE_FULL_LOW_LATENCY` only works foreground + screen on (the screen-off RTT tail in
`android/HANDOFF.md` §2).

---

## UI bugs

### Android

- **UA1** · TALK label white on `#FF7A2F` ≈ 2.6:1 (`ui/RideTab.kt:331`, `ui/Theme.kt:4`); dark
  `onPrimary` ≈ 7:1.
- **UA2** · Overlay doesn't match the app: green/blue/grey and "TALKING"
  (`overlay/OverlayService.kt:270-278`) vs orange/red "TALK/END TALK" in the app and "Talk/End talk"
  in the notification, although `Theme.kt` says they should read alike.
- **UA3** · `lastAnnounce` (`LinkHost.kt:1142`) never cleared; `RideTab.kt:251` shows stale
  "Playing album …" under later songs (checked).
- **UA4** · Whole app recomposes at 1 Hz: `LinkHost.kt:338-340` rewrites `LinkStatus` every second
  (`positionMs`, `lastPingAgeMs`, UDP counters) and every `Hub.log` line prepends to `log`;
  `MainScreen` collects the whole unstable object (List/Set/Map), so search, queue and settings
  recompose too. `RideTab.kt:232` computes the fraction in composition; progress jumps per second.
- **UA5** · Queue keys `"$i/${t.id}"` (`ui/QueueTab.kt:60`): removing row 0 re-keys every row.
- **UA6** · Stop is a 40 dp outlined button top-right, no confirmation (`RideTab.kt:156`); one glove
  brush stops both phones.
- **UA7** · "Duck music during talk" still a live switch (`ui/SettingsTab.kt:94`) though
  `duckDuringTalk` stays off by decision. Hide it.
- **UA8** · Back from Search/Queue/Settings exits the app; the only `BackHandler` is the album view
  (`ui/SearchTab.kt:65`).
- **UA9** · Playing song not highlighted in album/playlist lists (`SearchTab.kt:288`,
  `highlighted = false`).
- **UA10** · Stale results under new query text (`SearchTab.kt:115`, `shown` ignores `r.query`);
  result lists at `:142, :145` have no keys.
- **UA11** · Notification small icon and launcher icon are `android.R.drawable.ic_btn_speak_now`
  (`LinkService.kt:160`, manifest); no `res/`, no adaptive/monochrome icon; channel named "Link".
- **UA12** · Queue badge is error-red (`ui/MainScreen.kt:99`); use `containerColor = primary`.
- **UA13** · Unsaved speech-language edit lost on rotation (`SettingsTab.kt:63`, `remember`).
- **UA14** · "Allow" does nothing after a permanent denial (`MainActivity.kt:77-83`); open
  `ACTION_APPLICATION_DETAILS_SETTINGS` when `!shouldShowRequestPermissionRationale && !granted`.

### iOS

- **UI1** · `problem` is set at `AppModel.swift:165,178,181,197,318,513,646` and never cleared; the
  red line on Ride (`UI/RideView.swift:269`) stays until the app is killed. `lastHeard` (`:724`) and
  `lastAnnouncement` (`:380`) never clear either.
- **UI2** · Lock screen / Control Center: Pause ignored and `playCommand` toggles
  (`Music/NowPlaying.swift:155`, `AppModel.swift:766-769`), so a bud re-inserted while playing pauses
  both phones. Resolved by U-D1.
- **UI3** · Queue rows identified by row number; swipe-delete slides out, snaps back, then vanishes
  on the next `state` (`UI/QueueView.swift:59,80`). Use `"\(i)/\(item.id)"` and a pending-removal set.
- **UI4** · Every tab redraws every second: `refreshStats` (`AppModel.swift:931`) assigns `rttMs`,
  `audioRoute` unconditionally; `history.sawCurrent` (`:422`) mutates a `@Published` struct on every
  `state`; every view reads the model through `@EnvironmentObject`, rebuilding up to 200 queue rows.
- **UI5** · Ride tab can overflow: a plain `VStack` (`RideView.swift:132-157`), ~710 pt content vs
  ~665 pt available on an iPhone 15 with the status lines showing; worse with larger Dynamic Type.
  (Answers the README's "does Ride fit" question.)
- **UI6** · Lock-screen Now Playing stale: `updateNowPlaying` not called on `musicLoad` or on
  `musicPlay` for an uncached track (`:360, :468-473`); elapsed time from the local player, not the
  host anchor; no artwork ever set (`NowPlaying.swift:177`).
- **UI7** · "TALK…" has no timeout (`:495`, see P7); white on `.yellow` poor contrast
  (`RideView.swift:182`).
- **UI8** · `downloading` holds one title (`:444-449`): with current + next downloading it can show
  the wrong one or vanish early, and duplicates the "Loading…" status line.
- **UI9** · Empty album/playlist shows a blank section (`UI/SearchView.swift:260`); search error has
  no retry (`:64`).
- **UI10** · "Recently played" includes the current track unmarked; tapping restarts it and ends a
  talk.
- **UI11** · Stale docs: `NowPlaying.swift:145` comment and README Ride notes still mention removed
  headset-button settings.

## UI improvements (both apps; ranked)

1. **Talk as three visible states: opening, live, closing.** The 1–1.5 s route switch is invisible
   today. Android: add `talkLive` to `LinkStatus` (set where `fireLive` runs); opening = pulsing ring
   (`rememberInfiniteTransition`) + "Connecting…", live = red + "LIVE" pill (+ level meter), show
   "Heard: play money" from the recogniser; `HapticFeedbackType.Confirm` on press and on live (only
   the overlay has haptics now). iOS: `.symbolEffect(.pulse)`, "Talking · rider's mic" for host-mic
   talks (expose `talkMode`), `Text(timerInterval:)`, `.contentTransition(.symbolEffect(.replace))`,
   `.sensoryFeedback(.impact(weight: .heavy), trigger: talkOpen)`. Overlay, notification action and
   in-app button use identical colours and words (UA2).
2. **A fixed, non-scrolling Ride screen with TALK in the thumb zone.** Android: `Column` without
   `verticalScroll`: status chip, warning, now playing in `weight(1f)`, TALK 160–180 dp full width at
   the bottom. Command list stays (decision) but compact: one line `Say: play · pause · next ·
   louder …` opening a `ModalBottomSheet`, auto-expanded while the first-phrase window is open.
   Stop moves off Ride (status chip top-left; Stop in Settings or long-press + confirm; host off →
   one full-width "Start Motoparty" in place of TALK). Landscape (handlebar mount): two panes, TALK
   full height on the right. iOS: `ScrollView`/`ViewThatFits`, commands as chips with `<song>`
   dimmed in a `DisclosureGroup` remembered via `@AppStorage`, RTT out of the connection pill.
3. **Brand and identity on iOS.** `.tint` = `#FF7A2F` on the root (Play is default blue today),
   `.preferredColorScheme(.dark)` to match Android, `iconPath:` in `xtool.yml` (xtool 1.19.2
   supports a PNG; no asset catalogs). Android: adaptive + monochrome launcher icon, monochrome
   notification icon, channel "Ride status".
4. **Mini-player on every non-Ride tab.** Android: 64 dp above the `NavigationBar` (art, title, 56 dp
   play/pause, thin progress; tap → Ride). iOS 26: `.tabViewBottomAccessory` behind `#available`,
   with a talk chip; iOS 17–18: `.safeAreaInset(edge: .bottom)`.
5. **Redraw only what changed.** Android: publish the sync anchor (position + wall time + playing)
   instead of `positionMs` and interpolate in the progress composable (`withFrameMillis` or a
   250 ms `produceState`); split `Hub` into UI state + `diagnostics` + `log` flows; `@Immutable` /
   `ImmutableList`; `fontFeatureSettings = "tnum"` on times. iOS: move `AppModel` to `@Observable`
   (`@State` + `@Environment(AppModel.self)`; the Swift 6.3.3 toolchain ships the macros), or at
   least compare-before-assign and a separate `LinkStats` object only Settings reads; progress via
   `ProgressView(timerInterval:)`.
6. **Media notification on the Pixel.** `MediaStyleNotificationHelper.MediaStyle(session)` with the
   existing `MediaSession` in `Player.kt`: art, progress, prev/play/next in the shade and on the lock
   screen, plus Talk and Stop actions.
7. **Now Playing polish.** Bigger art (200–240 dp / pt, 20 corner radius), crossfade on track change
   (`AnimatedContent`), ambient gradient from the art (Coil `allowHardware(false)` + `androidx.palette`,
   ~25 % alpha); title `headlineMedium` Bold (marquee), artist `titleMedium`; 72/96/72 dp transport.
   iOS: blurred art behind the card, `.contentTransition(.numericText())` on times, plain large
   transport glyphs. Lock screen (iOS): `MPMediaItemArtwork`, host-anchored position, update on every
   load and play, `MPNowPlayingInfoPropertyMediaType`, "Talking with <rider>" as subtitle during a talk.
8. **Settings split.** Android: user-facing (floating button, Lark mic, swap L/R, language dropdown
   instead of free-text `en-US`), "Tuning" (resume lead, latency trim), Developer behind
   `BuildConfig.DEBUG` or tap-version-7-times (Diagnostics, WAV dump, USB probe, long recording).
   Stepper (`SettingsTab.kt:132`): 56 dp `FilledTonalIconButton`s, auto-repeat, tabular value. iOS:
   "Music sync offset" instead of "Latency trim" with a footer; RTT/drift/route/reconnect in a
   Diagnostics `DisclosureGroup`; languages by name via `Locale.localizedString(forIdentifier:)`.
9. **Glove-sized touch targets.** ⋮ (`SearchTab.kt:211`) and ✕ (`QueueTab.kt`) 56–64 dp, 72 dp rows,
   `FilterChip` 48 dp, permission "Allow" as `FilledTonalButton`. iOS: a visible 44 pt ✕ per queue
   row (swipes are hard with gloves).
10. **Confirmations.** Snackbar/toast "Playing next: Time", "Added 12 songs"; queue Undo after ✕;
    iOS `.sensoryFeedback(.success)` on Play/Add/jump/remove.
11. **Queue polish.** Stable per-entry keys + `Modifier.animateItem()`; play/pause on the now-playing
    row; centred empty-state Search button. iOS: "Up next · N" header, Now playing/Paused labels,
    Search button in the empty state (needs a `TabView` selection binding); art and duration per
    row need optional `art`/`durationMs` on `state.queue` items (fits `proto:1`; 200 × ~110 B ≈ 22 KiB
    of the 64 KiB budget).
12. **Images.** Android lists: `AsyncImage` with placeholder/error + `crossfade(true)` instead of
    `SubcomposeAsyncImage` (`ui/Common.kt:49`). iOS: bigger `URLCache.shared`, rewrite
    googleusercontent `=w544-h544` → `=w144-h144` for 48 pt rows (art comes over the Pixel's mobile
    data), one `NSCache` loader shared by `Artwork` and `NowPlaying`, fade-in, art
    `.accessibilityHidden(true)`.
13. **Native search on iOS.** `.searchable(placement: .navigationBarDrawer(displayMode: .always))`,
    `.searchScopes`, `.onSubmit(of: .search)`, recent searches as `.searchSuggestions`, iOS 26
    `Tab(role: .search)`; replaces the custom `.bar` header.
14. **Sunlight type and contrast (Android).** `onSurfaceVariant` `#9BA1AA` → ~`#B8BEC7`; keep the
    fixed brand scheme (no dynamic colour: it would break the state colours). Optional "Keep screen on
    while riding" setting (`LocalView.current.keepScreenOn` in a `DisposableEffect` on Ride while the
    host runs).
15. **Errors, permissions, wording.** Dismissible banners that clear on the next success. iOS
    permissions card with "Open Settings". One name for the link state ("Passenger connected" vs
    "Linked to X"); "Ready — open Motoparty on the iPhone" instead of "Visible as Pixel-8…";
    "Downloading song…" instead of "Loading…"; iOS "Looking for Pixel…" instead of "host", "TALK…" →
    "Connecting…"; Search empty state without the literal "⋮"; clear `busy`/`lastAnnounce` after ~6 s.
16. **Accessibility (iOS).** One VoiceOver label for the connection pill; "More options for <title>"
    on ⋯; TALK hint about hold-volume-up; `BigButton` `.largeTitle.weight(.heavy)` capped with
    `.dynamicTypeSize(...(.accessibility2))`; a pressed-state `ButtonStyle` for `.plain` rows.
17. **Live Activity / Dynamic Island (iOS): feasible, not now.** A display-only activity looks
    possible (ActivityKit/WidgetKit in the SDK, xtool knows `.appex`, `NSSupportsLiveActivities`;
    start in foreground only, update from background via the audio mode). Costs an App ID and
    probably one of the free account's 3 app slots; an interactive Talk button needs App Intents
    metadata Xcode generates and the SDK lacks. Ask the user after the device run.

## Cross-platform consistency

Theme (always-dark orange vs system + blue); command list (chips vs dense footnote with literal
`<song>`); queue (✕, art, duration, "Up next · N", Now playing/Paused vs swipe-only, no art, Clear in
toolbar); Ride (Android "Up next: X +N", quoted announcement, "Paused for talk — plays when the talk
ends" vs iOS "Host: …", "Heard: …", RTT in the pill); search (now-playing highlight, wording
"Nothing found for “q”"/"No results", "Try again when there is signal", "Songs, albums,
artists"/"Search YouTube Music"); album screen empty state; latency trim per output device vs one
global value; diagnostics folded vs flat; TALK button (no "requested" state vs "TALK…"; icon above
22 sp vs side by side 34 pt); queue badge "99+" vs raw count; speech language free text vs picker of
raw codes. Align on Android's choices unless an item above says otherwise.
