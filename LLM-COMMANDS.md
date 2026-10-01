# Plan: spoken commands understood by an LLM (2026-09-30)

Status: **built and in use; superseded for the answer shape by `VOICE-ACTIONS.md` (2026-10-01):**
Gemini now answers with a list of typed voice actions, not one `action` object, and gets a
numbered context window (PROTOCOL.md "Voice actions"). The history below (the race, the latency
root cause, Gemma as the backup) still holds. Owner: the coordinator session. User's request, verbatim:

> can we make it smarter somehow, more LLM'y? e.g., instead of having a fixed set of rules and
> words and sentences, i want it to be more intelligent so instead of telling it play honey by
> movie I want to be able to tell it play something by movie or you know just in general I want it
> to be more conversational

> let's do gemini instead of anthropic. i think gemini gives something like free 500 requests a
> day, check ~/Projects/capture/ and see how they handled it with gemini's api, or we can test the
> local gemini nano you've mentioned. after all of that, create a plan, and /handoff it

## What exists today

- The opener's first phrase in a talk (8 s window) is a command if `CommandParser` parses it
  (fixed grammar, `fixtures/commands.json`); otherwise it is conversation (PROTOCOL.md "Commands").
- Every parsed command ends the talk; a command whose result is the music has no spoken reply
  (both rules set by the user on 2026-09-30, commit `82cd19c`).
- All commands end up on the Android host: the rider's phrase (`LinkHost`, `TalkRecognizer`) and
  the passenger's `command.text`. The iPhone sends a phrase only if its own copy of the grammar
  parses it (`ClientCommand.route`), and handles volume itself.
- `Catalog.search(kind, query)` needs mobile data already; kinds: song, album, artist, playlist.

## Facts gathered

**Cloud Gemini, as `~/Projects/capture` does it** (read `capture/SPEC.md` §6 and
`app/src/main/java/com/kivan/capture/gemini/{GeminiConfig,Gemini,GeminiClient,RateGate}.kt`):
- Raw REST with OkHttp, no SDK: `POST https://generativelanguage.googleapis.com/v1beta/interactions`,
  header `x-goog-api-key`, body with `model`, `system_instruction`, `input`, `generation_config`
  (`thinking_level`, `temperature`), `response_format` (JSON schema), `store: false`. The answer
  text is `steps[]` item `type:"model_output"` → `content[]` item `type:"text"` → `text`.
- Model `gemini-3.5-flash-lite`. Free tier measured there on 2026-09-20: **15 requests/minute,
  500/day** per Cloud project and model (every non-Lite Flash model: 5 and 20). The 429 text names
  its window. `RateGate` spaces requests. `minimal` thinking is accepted by the Lite model.
- The key is `gemini.apiKey` in git-ignored `local.properties`, baked into
  `BuildConfig.GEMINI_API_KEY`. The user's standing decision in capture: stay on the free tier.
- The same Cloud project's quota is shared if the same key is reused: a new key in a **new
  project** keeps Motoparty's 500/day separate from capture's.

**Gemini Nano on the Pixel 8** (only partly checked; two research agents died on API errors):
- Verified on the phone: Pixel 8, Android 17 (`CP3A.260905.009`), `com.google.android.aicore`
  installed (`0.release.prod_aicore_20260820.00_RC08`).
- **Not verified:** whether the ML Kit GenAI Prompt API serves a Pixel 8 (Tensor G3) or only newer
  Pixels, its current artifact/version and stability, whether it runs from a foreground service
  with the screen off, its latency, and its per-app quota. This is step 1 below.

## Design (recommended)

1. **Fast path stays.** A first phrase that the grammar parses exactly (`next`, `pause`, `louder`,
   `over`, `play <query>` …) is handled as today: instant, offline.
2. **LLM path for everything else.** A non-empty first phrase that does not parse is sent to an
   `Interpreter` on the host with a little context (current track, next few queue titles, the last
   two interpreted commands). It returns one structured action, validated against a schema:
   `{action: play|queue|pause|resume|next|previous|volumeUp|volumeDown|shuffle|nowplaying|end|none,
   kind?: song|album|artist|playlist, query?: string}`. `none` = conversation: nothing happens and
   the talk stays open. Anything else is executed through the existing `executeCommand` path, so
   the talk-ending and no-spoken-reply rules hold unchanged.
   - "play something by movie" → `{play, artist, "Moby"}` (the prompt tells it the text is noisy
     speech recognition of music requests, and to repair names).
   - "something mellower", "the live version" use the context. Requests the catalog cannot serve
     map to the nearest search; no free-form chat, no spoken answers beyond today's.
3. **Budget and failure.** Hard timeout ~2.5 s (then: treat as conversation, log it, no error
   sound); `RateGate`-style spacing; a 429/daily-quota or no network → same fallback. While
   waiting, the talk stays open and nothing is spoken; consider a soft "thinking" earcon only if a
   device test shows the wait is confusing.
4. **Two back ends behind one interface:** `CloudGemini` (capture's request shape, `minimal`
   thinking, temperature 0, schema output) and, if step 1 says it works, `NanoGemini` (ML Kit
   Prompt API). Order of preference is decided by step 1's measurements: Nano first if it answers
   in under ~1 s with the screen off, else cloud first and Nano as the no-signal fallback, else
   cloud only.
5. **iPhone.** The client cannot call the LLM (no key there, by choice). Protocol change (fits
   `proto:1`): the client sends its first phrase as `command.text` when its own grammar parses it
   **or** when the host's `hello` advertises `interpret:true`; the host decides command vs
   conversation. Volume stays local when the client's grammar parses it; an LLM-interpreted
   volume phrase from the passenger is ignored (their keys do it). Update `PROTOCOL.md`,
   `fixtures/control/messages.json`, all three codecs, `ClientCommand.route`, the peer.
6. **Privacy note for the user:** free-tier prompts may be used by Google to improve its models;
   only the first phrase of a talk the opener started is ever sent, never conversation after it.
   (Check the current terms in step 1 and tell the user plainly.)

## Steps, in order

1. **Spike (half a day, throwaway code in a scratch module or a debug screen):**
   a. Nano: current docs for the ML Kit GenAI Prompt API (devices, version on Google Maven, limits,
      background use), then a minimal call on the Pixel from a foreground service with the screen
      off: 20 sample phrases, record latency and answers. Report feasible / not.
   b. Cloud: `curl` the Interactions endpoint with the draft prompt + schema (copy
      `capture/tools/gemini_smoke.sh`) on the same 20 phrases over mobile data: latency (p50/p95),
      answers, confirm 15/min and 500/day still hold for the Lite model and check the newest Lite
      model id. The user must create the API key (new Cloud project) and put it in
      `android/local.properties` as `gemini.apiKey`; ask them when the spike starts.
   c. Write the numbers into this file and pick the back-end order.
2. **Spec:** `PROTOCOL.md` "Commands" (interpretation, `interpret` in `hello`, client rule,
   timeout, fallback), fixtures: a new `fixtures/interpret.json` with phrase + context → expected
   action for the **prompt-independent** parts (schema validation, mapping an action to a
   `Command`, fallback on bad JSON), not model output.
3. **Android:** `voicecmd/Interpreter.kt` (interface + pure mapping + tests with a fake back end),
   `CloudGemini.kt` (OkHttp, as capture), optional `NanoGemini.kt`, prompt and schema in
   `res/raw/`, `BuildConfig.GEMINI_API_KEY` from `local.properties`, wiring in
   `LinkHost` where the first phrase is judged (keep `FirstPhraseGate` spent semantics: one phrase,
   one decision), a Settings switch "Smart commands" (on when a key is present), log line per call
   (`interpret: "<phrase>" → play artist "Moby" in 640 ms (cloud)`), R8 check.
4. **Peer + iOS:** the `interpret` hello flag and the client rule; peer fake host gets a stub
   interpreter (table-driven) so the flow is testable without a key.
5. **Device run on the Pixel** (laptop peer as passenger first, then the user on the bike): the
   20 phrases spoken, latency from phrase end to `talk.close`, behaviour with mobile data off.
6. Docs (`AUDIT.md` is closed; use `HANDOFF.md` "Do now" and `android/HANDOFF.md`), report.

## Open decisions for the user (ask at the right step, don't block the spike on them)

- Create the Gemini API key in a new project (needed at step 1b).
- If Nano works but is slower/weaker than cloud: cloud first with Nano as offline fallback, or
  Nano only (no key, nothing leaves the phone)?
- "More conversational": stay silent as now (recommended), or allow a short spoken clarifying
  question ("Which Moby album?") that keeps the talk open for one more phrase?

## Progress (2026-09-30, second session)

**Step 1a, Gemini Nano: not usable, by Google's own documentation (not run on the phone).**
- The ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt:1.0.0-beta4`, beta) lists Pixel 9,
  10 and 11 phones; the Pixel 8 is not on the list (developers.google.com/ml-kit/genai).
- Independently of the phone: "GenAI API inference is permitted only when the app is the top
  foreground application"; from a foreground service it returns `BACKGROUND_USE_BLOCKED`. Motoparty
  listens from a service with the screen off or another app (maps) in front, so Nano would not
  answer on a ride even on a newer Pixel. There is also a per-app quota (`BUSY`,
  `PER_APP_BATTERY_USE_QUOTA_EXCEEDED`).
- Decision: **cloud only.** No `NanoGemini` back end; the `Interpreter` interface stays so one can
  be added if Google lifts the foreground rule.

**Step 1b, cloud: run on 2026-09-30 from the laptop over Wi-Fi (not mobile data), two passes of
`tools/interpret/smoke.sh`, with capture's key (the user said to reuse it, so the 500/day is
shared with capture): every one of the 27 phrases got the intended answer (first pass read by
eye, a script bug mis-scored empty fields; second pass 26/27 scored, the miss being one request
that hung 20 s on the network). Latency p50 about 0.97–0.99 s, p95 1.3–1.9 s. `minimal` thinking
and the schema output are accepted. Back end: **cloud only** (1c).**
- `gemini-3.5-flash-lite` is still the newest stable Flash-Lite (ai.google.dev models page). The
  rate-limit page no longer prints numbers (they are per project, shown in AI Studio; daily quota
  resets at midnight Pacific); 15/min and 500/day are capture's measurement of 2026-09-20.
- Terms (effective 2026-03-23): on the free tier Google uses what is sent "to provide, improve,
  and develop Google products and services and machine learning technologies" and "human
  reviewers may read, annotate, and process" it. Paid tier: not used for that. In the EEA, UK and
  Switzerland the paid-tier data rules apply to the free quota too.
- Draft prompt and schema: `android/app/src/main/res/raw/interpret_prompt.txt`,
  `interpret_schema.json`. Spike: `tools/interpret/smoke.sh` runs the 27 phrases of
  `tools/interpret/phrases.tsv` (19 requests, 7 conversations, 1 Hebrew) and prints answers and
  p50/p95 latency. It needs `gemini.apiKey` in `android/local.properties`.

**Changes to the design above.**
- No `queue` action: the grammar has no "add to queue" command; the model's answer maps onto the
  existing commands only (`fixtures/interpret.json`), so every rule about talks and replies holds.
- Timeout 3000 ms, not 2.5 s.
- With smart commands on, the **first phrase of every talk** goes to Google, including the first
  sentence of a plain conversation (that is how the model gets to decide). Nothing after it does.
- Typed commands on the host are interpreted too (lets it be tried without speaking).

**Steps 2–4: built (2026-09-30), uncommitted.** The background agents all died on API 529
errors, so the coordinator wrote all three components itself.
- Android: `core/Interpretation.kt` (answer → command text), `voicecmd/Interpreter.kt` (+
  `RateGuard`), `voicecmd/CloudGemini.kt`, `FirstPhraseGate.open(role, interpret)`,
  `Hello.interpret`, `Settings.smartCommands` + the "Smart commands" switch (Voice group),
  `BuildConfig.GEMINI_API_KEY` from `local.properties`, `LinkHost.submitCommand` (every command
  source goes through it; re-sends `hello` when the switch changes). 486 tests / 0 fail /
  5 skipped, lint 0 errors, `assembleRelease` OK.
- iOS: `Hello.interpret`, `FirstPhraseGate(interpret:)`, `AppModel.hostInterprets` (from the
  latest `hello`, reset on link loss). `swift test` 224, release build clean, **not installed**.
- Peer: `hello.interpret`, gate flag, `interpretation_command_text`, client rule, fake host with
  a table stub (`--interpret-table`, `Host.interpret_table`). 394 passed + 1 skipped.

**Step 5, on the Pixel (release build installed, laptop peer as the passenger, phrases typed
with `hear`, not spoken):** `hello` carries `interpret:true`; "put on something by movie" →
talk closed 1.2 s after the phrase, Moby queued and playing; "are you cold back there" →
conversation in 774 ms, talk stayed open, nothing announced; "i don't like this one skip it" →
`next` in 993 ms, talk closed.

**Not done / device-unverified:** spoken phrases through the real recogniser (rider and
passenger), a ride on mobile data, the Settings switch looked at on screen and toggled (the
`hello` re-send), the iPhone, behaviour with no signal (unit-level only: a failure is
conversation). The key in `android/local.properties` is capture's, so the 500/day is shared;
a separate project key would split it. Open decision still with the user: a spoken clarifying
question (kept silent for now).

## Progress (2026-09-30, third session): the clarifying question

User's decision: "a short clarifying question is okay, e.g., which album, but make sure it can
handle ambiguity, e.g., 'i dont care, just play any album' as a response from the rider should
play a random one or a top-rated one by the artist".

- Spec: PROTOCOL.md "### The clarifying question" (answer action `ask` with `question` and an
  optional fallback in `kind`/`query`; `announce{ask:true}`; reply window `ANSWER_MS` 10 s, host
  waits 2 s more, then plays the fallback; one question per talk; the reply is always
  interpreted with `asked:{phrase,question}`; "any / I don't care" → `play album <artist>`, and
  `Catalog.anyAlbum` picks a random one of that artist's top 5 album results).
- Found on the way: the grammar parsed "play something by movie" and "play an album by moby" as
  `play song …`, so they never reached the model. With smart commands on, every `play …` now
  goes to the interpreter too; if it does not settle it, it is played as spoken.
- Built, uncommitted: Android (core, LinkHost, Catalog, tests), peer (host, client, tests), iOS
  (gate `.reply`, `Announce.ask`, `AppModel.awaitReply`). Prompt + schema updated; smoke script
  has a two-turn mode; 16 new lines in `tools/interpret/phrases.tsv`: all as intended on Gemini
  (one ambiguous test line replaced afterwards, not re-run), p50 about 1.05 s.
- Test state: see the handoff prompt of this session. Nothing of this was run on a device.

Next request from the user (not started): voice queueing, see the handoff prompt.

## Progress (2026-09-30, fourth session): question verified, voice queueing built

**Clarifying question, verified.** Android 491 tests (only the two known timing flakes fail,
pass on re-run), lint, `assembleRelease`; peer 422 passed; `swift test` 225; iOS release build;
`tools/interpret/smoke.sh` 43/43, p50 984 ms. On the Pixel (release build, laptop peer as the
passenger, phrases typed with `hear`): "play an album by moby" → question, talk stays open →
"i dont care just play any album" → `playing album Play by Moby`; no reply → fallback (songs by
Moby) after 12 s; "never mind" → nothing, talk open; "play something by movie" → Moby. One run
right after the 43-request smoke got no answer from Gemini (most likely the shared 15/min
limit; the log had rolled) and the phrase was played as spoken. Not done: typing on the host's
Ride screen (phone was locked), real speech, mobile data, iPhone.

**Voice queueing (user's request), built, uncommitted, NOT yet verified end to end.**
- Spec: PROTOCOL.md "### Queueing by voice": `queue [next|instead] [<count>] <kind> <query>` /
  `… similar`; interpreter action `queue` with `where`, `count`, kind `similar`. Ends the talk,
  music resumes, spoken "Added …" / "Next: …". "Rest of this album" = album tracks after the
  current one. Similar = YouTube's radio for the current track (`Catalog.similar`, checked live).
- Fixtures: `commands.json` 65 cases, `interpret.json` 57 (+ `queueMaxCount`).
- Android: `Command.Queue`, `music/VoiceQueue.kt` (+ test), `LinkHost.executeCommand`, album in
  the interpreter input. Last run: 496 tests, only the known `TrackSchedulerTest` flake failed.
  **Not run since: lint, `assembleRelease`.**
- Peer: 461 passed. iOS: parser + chips, `swift test` 225 passed; **release build not re-run.**
- Prompt/schema: `count` is now a required field (0 = none), because the model never sent an
  optional one. Spot checks on Gemini gave the intended answers for the user's four examples.
  `tools/interpret/phrases.tsv` has 10 new lines; line "add the rest of this album…" expects
  `queue album play moby` but the model says "play – moby": make it a glob (`queue album play*moby`).
  **Full smoke (53 requests) not re-run**: check the 43 old lines did not regress (every answer
  now carries `count`).
- Next: lint + release build, iOS release build, full smoke, install on the Pixel and try the
  four phrases with the peer (`hear …` while a track plays; log lines `queued …`), then update
  `HANDOFF.md` and report to the user. Nothing is committed (commit only when the user asks).

## Progress (2026-09-30, fifth session): queueing checked off-device, committed

- Android: 496 tests / 0 failures / 6 skipped, lint 17 warnings and no errors, `assembleRelease`
  OK (APK built after the prompt change below, not installed).
- Full smoke, first run 51/53. Two changes followed:
  - "choose similar music for the rest of the queue" came back without `instead`; the prompt now
    says `instead` whenever the rider speaks of the rest of the queue (that phrase is an example).
  - "more from this band" now answers `queue artist …` (it used to be `play`): "more of / more
    from" adds to the queue and does not interrupt the track. The prompt's play example was
    changed to "play this band"; `phrases.tsv` expects the queue answer.
  - `phrases.tsv`: two expectations made globs (`queue album play*moby`,
    `queue next song yellow*coldplay`).
  - Two more prompt refinements after re-runs: "play … after this one" is `next`; `instead` only
    for "the rest of the queue" (not "the rest of this album"); "play the album / this album /
    the whole album" is always `play` (it had flipped to `queue` once).
  - Final prompt: 53/53 (45 in the full run, the 8 that timed out on a flaky laptop connection
    answered as expected on retry). Gemini answers were sometimes slow (9–18 s) or timed out
    during this session; the app then executes the phrase as spoken.
  - Weak spot: the album query for "the rest of this album" varies without an `album` in the
    input ("play moby", "play moby porcelain", "play 18 moby"). Judge it on the Pixel.
- `TrackSchedulerTest` "a stream is resolved once…" no longer asserts the order of the parallel
  pre-resolves (that was the flake).
- Latency with the longer prompt and `count`: p50 about 1.3 s (was about 1.0 s).
- iOS release build: skipped on the user's word (no iPhone for some days). Swift code may still
  be written; do not build or install.
- **Still open: the Pixel run.** With the release APK installed and the laptop peer as
  passenger: get a track playing, then one talk each for "add the rest of this album to the
  queue", "as the next song play natural blues", "for the next three songs play more of this
  artist", "choose similar music for the rest of the queue". Expect `talk.close`, `music.play`
  resuming the same track, `announce` "Added …" / "Next: …", a new `state` queue, and logcat
  `interpret: …` / `queued …`. Judge whether "the rest of this album" finds the right album.

### Pixel attempts, 2026-09-30 evening (queueing still unverified on a device)

Three runs with the release build and the laptop peer: every Gemini request from the phone hit
the 3 s limit (`interpret: … failed: timeout`), 9 of 9. From the laptop the same phrases took
1–14 s at the same time (earlier the same day: about 1.2 s), and one slow answer was also wrong.
So Gemini's free tier was slow that evening; nothing in the app points elsewhere (YouTube
lookups from the same HTTP client were fast). Not ruled out: something specific to the phone's
path (Private DNS is set to a hostname; no IPv6 route). Retry another time; start from a real
track with `hear play porcelain by moby`, because a failed interpretation plays the words as
spoken ("play something by movie" found a 6-second clip). Open design question for the user:
3 s may be too short a limit when Gemini is slow, but a longer one means a longer silence.

Also that evening (user request): the "live" beep at talk start is now a setting, off by default
(`Settings.liveBeep`, "Beep when the mic is live"). The wait before a talk is live is the
microphone / headset call route coming up (0.5–1.9 s measured with no headset) and is unchanged.

### Later that evening: queueing verified on the Pixel (with a temporary 20 s limit)

- A timed-out call now logs its steps (`timeout (dns 194 [ipv4], tls 409, connection 418, sent
  424)`): the phone's network was fine, Gemini simply answered late (5–13 s from the phone and
  from the laptop alike; not the phone being in a pocket, the screen was on).
- With `INTERPRET_TIMEOUT_MS` at 20 s in a test build: "as the next song play natural blues" →
  `Next: Natural Blues by Moby`; "add the rest of this album" → Gemini said album "play 18
  moby" (wrong: Porcelain is on *Play*). Fixed: a queued album that does not hold the playing
  track, when the query names that track's artist, is replaced by the artist's album that does
  (`Catalog.albumContaining`, `VoiceQueue.wantsCurrentAlbum`, PROTOCOL.md "Queueing by voice").
  On the Pixel: `queue: using album Play by Moby` → the 15 tracks after Porcelain. The limit is
  back at 3 s in the committed build.
- The daily quota (500, shared with capture) ran out; Google's 429 says "Please retry in 32s"
  (a rolling window). The app now holds for Google's retry time instead of an hour.
- Not seen on the device yet: "for the next three songs …" and "choose similar music …" (quota).
  Open for the user: keep the 3 s limit (Gemini can be much slower on some evenings)?


### Root cause of the slow answers (2026-09-30, 23:20–23:27 local)

- The wait is Google's model queue, not the request or the phone. In every slow call TLS was up in
  about 0.1 s and the time to the first response byte was the whole wait (laptop, curl).
- 40 interleaved requests on `gemini-3.1-flash-lite` (separate free quota; 3.5 Flash-Lite's daily
  500 was used up after one 1.2 s probe). Four request shapes: Interactions + schema (the app's),
  no schema, `generateContent` + schema, and a 17-token prompt instead of 1,750. p50 2.9 / 2.7 /
  2.9 / 2.2 s, maxima 4.4–6.3 s, 3 × HTTP 503. Every shape was slow and uneven, so the schema, the
  prompt's length and the API are not the cause. Back-to-back calls ranged from 1.0 to 6.3 s, and
  the "movie" phrase was not slower than "moby".
- Google's docs (checked tonight): Standard inference is "seconds to minutes" with no free-tier
  SLO. `service_tier: "priority"` ("seconds", not sheddable, works on the Interactions API) needs
  a Tier 2 paid project ($100 paid + 3 days). A forum thread from 2026-09-28 reports repeated 503s
  on `gemini-3.5-flash-lite`. The daily quota resets at midnight Pacific (10:00 Israel time).
- Not tested: whether a paid Standard key is faster than the free tier (it needs billing: about
  $0.0006 a request, about $9 a month at 500 a day).
- Side finding: `temperature` is deprecated for Gemini 3.5+, and Google says to remove it
  (<https://ai.google.dev/gemini-api/docs/latest-model#sampling-parameter-deprecation>). This is not
  related to the latency.

### The fix (user's choice, 2026-09-30 night): race two models, 6 s limit, no temperature

- `FirstAnswer` asks `gemini-3.5-flash-lite` and `gemini-3.1-flash-lite` at once and acts on the
  first answer. Each model has its own free quota and rate guard; a failure (503, 429, timeout)
  on one waits for the other. The log line ends in `via 3.5-flash-lite` / `via 3.1-flash-lite`, and
  a double failure names both reasons.
- A reply to a clarifying question goes to 3.5 only (`CloudGemini.NO_REPLIES`). 3.1 got 49/53 on
  `smoke.sh` (two of the misses were Google's "503 … high demand"). The other two were question
  replies: "any" gave `play artist moby` 3 of 3 times, and "watch out for that truck" gave
  `play album moby` 2 of 3 times. 3.1's p50 was 3.6 s that night.
- `INTERPRET_TIMEOUT_MS` is 6000 (PROTOCOL.md, `fixtures/interpret.json`, peer). Nothing is said
  while the host waits (the user declined a spoken "one moment").
- `temperature` was removed from the request and from `smoke.sh`. Not re-checked on 3.5 yet: its
  quota was used up; the daily reset is at 10:00 Israel time. Run the full `smoke.sh` once then.
- On the Pixel (release build, laptop peer as passenger): "play porcelain by moby" → Porcelain,
  "play something by movie" → Moby, "who sings this" → "Porcelain by Moby". Time from phrase to
  action: 1.7 / 2.9 / 3.4 s. Which model won is unknown: wireless debugging dropped, so there is
  no logcat.
- Second Pixel run, 23:58 (with logcat). 3.5's quota was still used up, so 3.1 answered every one:
  "play porcelain by moby" → `play song porcelain moby` in 4.7 s; "for the next three songs play
  more of this artist" → `queue next 3 artist moby` in 5.2 s, "Next: 3 songs"; "choose similar
  music for the rest of the queue" → `queue instead similar` in 3.9 s, "Added 20 songs"; "play
  something by movie" → Moby in 2.9 s; "what song is this" → "Porcelain by Moby". Four of the five
  would have failed under the old 3 s limit. **Queueing is now verified on the device.**

### Other models checked, and Gemma 4 as the backup (2026-10-01, 00:10–00:40)

- Free limits (third-party measurements; Google no longer publishes them): 3.5 / 3.6 / 3.7 /
  3.8 Flash about 20 a day; Omni 1.1 Flash none; Gemma 4 about 1,000–1,500 a day plus 16,000
  input tokens a minute; both Flash-Lites 500. `gemini-flash-lite-latest` is 3.5 Flash-Lite
  and shares its quota. 3.7 / 3.8 Flash reject `thinking_level: minimal` and were 3–20 s on `low`.
- `gemma-4-26b-a4b-it` on `smoke.sh`: 30 of the 33 phrases it answered were right, with p50 1.2 s.
  Of the 53, 9 were refused with 503 "high demand" and 11 hit its per-minute token limit. Its
  misses: no clarifying question ("play that song by queen" → plays Queen), `play` for "add
  some cold play to the queue", and the artist's songs for "any" in reply to "Which Moby album?".
- So it is the backup, not a racer (`CloudGemini.BACKUP_MODEL`, `FirstAnswer.backup`). It is
  asked when both Flash-Lites have refused, or when neither has answered after 4 s
  (`BACKUP_AFTER_MS`). Nothing is waited for past 6 s, and replies to a question never go to it.
  Its rate guard allows 8 a minute (16,000 tokens / about 1,750 per request). Each Flash-Lite
  call now times out at 5.9 s, so its own timeout, with its phases, is logged before the
  overall 6 s limit.
- Pixel, 00:35: five phrases, all right, all won by 3.1 in 2.3–4.6 s (3.5's quota still used
  up). The backup did not have to answer, so it is only covered by `FirstAnswerTest` so far.
