# Plan: full voice control through typed actions (2026-10-01)

Status (2026-10-01): **built and unit-tested (steps 1–6, `c7b224d` spec, `cfc08e4` build); not yet
run on a device; the Swift is written but not compiled.** See "Build notes" at the end for where
the build differs from this plan and what is still open. Follows `LLM-COMMANDS.md` (the interpreter as
it is today). The user's request, verbatim:

> I would like to /handoff to a new agent to see if we can/should add more command types for the
> LLM gemini agents to use, just like we thought about filling the queue, etc.
> perhaps we've been doing it wrong, and our app needs some kind of an API so the agents can
> access it better, e.g., remove queue, empty queue, add to queue, reorder queue, pause, play, etc.
> basically, we want the LLM agents to have a full control.

Decision page (proposed action set, the four architecture choices):
<https://claude.ai/artifact/QzArYYHpHxkBCKn6yhRYBm>

## The problem with today's shape

Gemini answers with one object that `Interpretation.outcome` turns back into a **grammar text**,
which `executeCommand` parses again. So the model can only say what the grammar can say: no
remove, clear, move, jump, seek or repeat, one action per phrase, and only 5 upcoming titles as
context. Touch already does more (jump, remove with undo, clear, enqueue now/next/end) through
`MusicController` directly.

## Decisions (user, 2026-10-01)

| Topic | Decision |
|-------|----------|
| Scope | Queue editing (remove, move, clear, jump, history); several actions per phrase; questions answered aloud; track control (restart, seek, repeat) |
| Answer shape | **One request, one answer holding a list of up to 4 typed actions**, run in order by the host. No function calling, no agent loop. The rider never sees or answers the list. |
| Context | **Numbered window**: current track (position, album, repeat state), up to 25 numbered upcoming tracks with artist, the queue's total length, the last 5 played, and what the last voice command did |
| Spoken answers | **The host composes them from its own data**; Gemini only picks which fact (`tell`). No free-text `say`. |
| Grammar | **Fast path and offline fallback**: the exact short words stay instant; the grammar produces the same typed actions; everything else (and every `play`/`queue`) goes to Gemini as today |
| Destructive actions | **Act at once, then voice undo** ("put it back"). No confirmation question. |
| Undo | **One level**: the last voice-made queue change, for 10 minutes |
| Passenger | **Equal control** (same as touch today) |
| Spoken feedback | A short line **only for remove, clear and move** (plus `tell`, `ask`, failures and `add` as today). Skip, jump, seek, restart, repeat, pause: earcon and the music only |
| Touch parity | Add a **repeat toggle** (Ride tab) and **drag to reorder** (Queue tab). Seek stays voice-only |
| Probe first? | No: build, then measure with the extended `smoke.sh` |

## Design

### 1. One executor: `VoiceAction`

A sealed type in `core/` (pure, serialisable), the single currency for the grammar, the
interpreter and the undo:

| Action | Fields | Notes |
|--------|--------|-------|
| `play` | `kind` song/album/artist/playlist/similar, `query` | `similar` = radio of the current track |
| `add` | as `play`, `where` next/end/instead, `count` 0–50 | today's `queue` |
| `remove` | `at`: positions, **or** `artist` | `artist` removes every upcoming track by that artist, also past the window |
| `move` | `at`, `to` next/end/a position | |
| `clear` | none | |
| `jump` | `at`: a queue position, or −1…−5 for the played list ("the song before last" = −2) | history jump re-inserts that track as current, the queue stays |
| `pause` `resume` `next` `previous` `shuffle` `end` | none | as today |
| `restart` | none | seek to 0 |
| `seek` | `by` ±seconds **or** `to` seconds | clamped to the track |
| `repeat` | `mode` off/track/queue | |
| `volume` | `up`/`down` | still local and ignored from the passenger, as today |
| `tell` | `about` track/album/next/remaining/previous | host composes the line |
| `undo` | none | |
| `ask` | `question`, `fallback`: a list of actions | only alone, only as the first answer of a talk, as today |
| `none` | none | conversation |

`Command` (grammar) maps 1:1 to these, so `CommandParser` stays as it is and gains one
`toActions()`; `fixtures/commands.json` is unchanged.

### 2. The answer schema

`interpret_schema.json` becomes `{"actions": array (maxItems 4) of anyOf[typed objects]}`, each object
with an enum `type`, using only the keywords Google documents for structured output (`anyOf`,
`enum`, `minItems`/`maxItems`, `required`; no `oneOf`/`const`). An empty list or `[{"type":"none"}]`
is conversation. Gemma 4 gets the same schema (it already accepts today's on the Interactions
API; re-check it answers the list shape in the smoke run).

Validation in `Interpretation` (pure, vectors in `fixtures/interpret.json`, rewritten):
- unknown `type`, a missing required field, or an out-of-range position drops **that action**;
  if nothing usable is left, it is conversation;
- `ask` with other actions → the other actions run, the question is dropped;
- `none` mixed with real actions → ignored;
- more than 4 → the first 4.

### 3. What Gemini is sent

```json
{"phrase": "drop the next two and play yellow after this",
 "lang": "en-US",
 "playing": {"title": "Porcelain", "artist": "Moby", "album": "Play", "atS": 74, "lengthS": 241},
 "repeat": "off",
 "upNext": ["1. Natural Blues – Moby", "2. Why Does My Heart… – Moby", "3. Yellow – Coldplay"],
 "queueLength": 14,
 "played": ["-1. Teardrop – Massive Attack", "-2. …"],
 "lastVoice": "removed 2: Clocks – Coldplay, Fix You – Coldplay (3 min ago)",
 "asked": null}
```

`INTERPRET_UP_NEXT` becomes 25. The host keeps the **snapshot** (position → track id) it sent;
positions in the answer are resolved against it and executed on ids, so a queue that changed
while Gemini thought cannot make an edit hit the wrong song (the same rule as `music.edit`'s
`id`). An action whose track is gone is skipped and logged. Estimated size about 3,000 tokens with
the bigger prompt (today about 1,750): Gemma's 16,000 tokens/minute then allows about 5 a minute;
lower its `RateGuard` to match.

### 4. Running a list

- Searches in the list start **at once and in parallel**; the actions then apply in order, each
  on the queue as the previous one left it. Positions always refer to the snapshot, not to the
  queue after earlier actions in the same list.
- The talk ends as today if any action is a command (all except `none`; `ask` keeps it open).
  `CommandEffect` decides from the list: the strongest effect wins (`play`/`jump` replace the
  resume; `pause` cancels it).
- **One spoken line per list**, after the switch to media: the destructive parts and `tell`
  ("Removed 2 songs. Moved Yellow to next."), then an `add` reply as today. Failures are named in
  the same line ("Couldn't find X"); the rest of the list still runs.

### 5. Undo

`VoiceUndo` keeps the queue's upcoming ids and the repeat state from just before the last voice
list that changed them, with a timestamp. `undo` within 10 minutes restores that upcoming list
(tracks still known by id; the current track is not touched) and says "Put back 2 songs" / "Undone";
else "Nothing to undo". One level: an undo is not itself undoable. Touch keeps its own remove-undo.

### 6. Repeat and move (protocol)

- `state.music.repeat`: `"off"`/`"track"`/`"queue"` (optional, absent = off). `repeat track`
  restarts the track at its end; `repeat queue` goes back to the first track the queue still
  holds after the last (needs `MusicController` to keep played tracks; check `previous`'s
  history first). Gapless (`music.next`) must announce the same track for repeat-track.
- `music.edit{op:"move", index, id, to}`: `to` is the new upcoming index; same stale-`id` rule.
- Seek needs nothing new: `music.play{positionMs}` already serves as seek and resync.
- Update `PROTOCOL.md`, `fixtures/control/messages.json`, the three codecs (Kotlin, Swift,
  Python peer), the peer's commands (`edit move`, `repeat`).

### 7. Grammar fast path

Unchanged words, unchanged speed. New exact phrases the grammar may take offline (vectors added
to `fixtures/commands.json` only if the user wants them later): none for now. Everything new goes
through Gemini; without signal, the new abilities are simply unavailable (as `queue` naturalness
is today).

## Steps, in order

1. **Spec**: `PROTOCOL.md` "Interpretation" (actions, schema, snapshot rule, list execution,
   one line per list, undo), "Browsing" (`move`), `state.music.repeat`; `fixtures/interpret.json`
   rewritten for lists; `fixtures/control/messages.json`.
2. **Core (Android, pure + tests)**: `VoiceAction`, `Command.toActions()`, `Interpretation` →
   `List<VoiceAction>`, the snapshot, `VoiceUndo`, the reply line composer, `QueueEdits.moved`.
3. **Host**: `MusicController.move/seek/restart/setRepeat/jumpBack`, repeat at the track's end
   and in gapless; `LinkHost`: one `execute(actions, fromClient)` replacing `executeCommand`'s
   switch (the grammar goes through it too), context building, `tell` lines.
4. **Prompt + schema**: rewrite `interpret_prompt.txt` around the action list, keeping every
   current rule (when to ask, conversation bias, name repair, album rules).
5. **UI**: repeat toggle on the Ride tab; drag to reorder in the Queue tab (fits the 1024×576
   logical desktop rule only for the laptop; the Pixel is the target).
6. **Peer + iOS**: codecs, `edit move`, `repeat` in the peer and its fake host; Swift for the
   codec, the queue's drag-to-reorder and the repeat display (**written, not built**: no iPhone).
7. **Measure**: `tools/interpret/phrases.tsv` grows from 53 to about 90 (the new actions, lists,
   undo, history, `tell`, and traps: road talk that sounds like "clear", "remove"); `smoke.sh`
   checks lists. One run on 3.5, then 3.1 (about 180 requests, spaced ≥ 4.2 s; check the quota
   with one request first). Gemma on its own: a subset of about 30.
8. **Pixel**: a scripted peer run of the new phrases (remove, move, clear + undo, jump back,
   seek, repeat, a two-action list, `tell`), logcat for latency.
9. Docs: `LLM-COMMANDS.md` pointer, `HANDOFF.md`; commit and push.

## Risks to watch

- **Accuracy drop from a longer prompt and a list schema**, especially on 3.1 and Gemma. The
  smoke run decides; if a model gets much worse, it leaves the race (not the plan).
- **Latency** from about 1,250 more input tokens: measured last night as small next to Google's
  queue, but re-check p50 in step 7.
- **Misheard destructive edits**: covered by undo and the prompt's conversation bias; the phrase
  set gets road-talk traps.
- **Quota**: unchanged per phrase (one request); the smoke runs cost about 200 of the day's 500
  on each Flash-Lite.

## Build notes (2026-10-01)

Built by four parallel subagents against the committed spec (PROTOCOL.md "Voice actions"), checked
by the coordinator: Android 540 tests / 0 failed / 7 skipped, lint clean, release APK builds;
peer 544 passed / 1 skipped (ffmpeg). Swift written, **not compiled** (no iPhone).

Where the build differs from the plan, or settles what it left open:
- **Repeat track has no gapless.** The track restarts the ordinary way at its end (a short
  gap); announcing the same id in `music.next` was judged too risky for the clients.
- **Ask fallback stays `kind`/`query`** (one `play`), not a nested action list: smaller schema,
  same behaviour as before.
- **`move.to` is one integer**: 1 = next, past the end = the end (no `next`/`end` words).
- `played` comes from the History store (the current track dropped), so it survives a `play`.
- A list with `play` or `jump` saves no undo, and forgets the one there was. Touch move and
  touch repeat do not clear the voice undo.
- Wordings the spec left to the host: "That song is gone from the queue", "Put back 1 song",
  `tell remaining` counts the rest of the current track too.
- Prompt choices: a song already upcoming asked for "next" becomes `move`, not a second copy;
  "put it back" with no `lastVoice` is conversation; "go back one" stays `previous`.
- `smoke.sh` contexts: empty, a `title – artist`, or `@queue` (a 12-track sample queue with 3
  played and a lastVoice). `SMOKE_PHRASES` / `SMOKE_SPACING_S` pick another phrase file and
  spacing (Gemma: 13 s).

Known gaps: Android drag-to-reorder does not auto-scroll at the screen's edge; on iOS a dragged
row may snap back until the host's `state` arrives; `repeat` as a Swift identifier is the
likeliest compile error when the iPhone is back. The peer's fake host restarts a `repeat queue`
from history rather than from the queue's first track (test scaffolding only).

Still to do: step 7's measurements (below, as they come in) and step 8, the Pixel run (the user
said no phone tests for now).

## Measurements (2026-10-01, `tools/interpret/smoke.sh`, new prompt and schema, 93 phrases)

| Model | As expected | Refused (503 "high demand") | Wrong of those answered | p50 / p95 of answered |
|-------|-------------|-----------------------------|-------------------------|-----------------------|
| `gemini-3.1-flash-lite` (07:50) | 81/93 | 8 | 4/85 | 3.2 s / 4.5 s |
| `gemma-4-26b-a4b-it`, every 3rd phrase, 13 s apart (07:50) | 24/31 | 4 | 3/27 | 1.3 s / 1.7 s |

- 3.1's p50 was 3.6 s with the old, smaller prompt (2026-09-30 night): the bigger prompt costs no
  visible latency next to Google's queue.
- 3.1's wrong answers: two are replies to a question ("any" → `play artist moby` instead of
  `play album moby`; road talk "watch out for that truck" → an album), which 3.1 is not asked in
  the app (`NO_REPLIES`); "play that song by queen" played instead of asking; "play something like
  this instead" → `add instead similar` rather than `play similar` (much the same result).
- Gemma's: "play fix you next" added a second copy instead of moving the upcoming one; "remove
  all the queen songs and add some u2" did only the remove; **"put it back in the tank bag" →
  `undo`** (a road-talk trap). Gemma answers only as the backup.
