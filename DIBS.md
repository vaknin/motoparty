# Talking to dibs while riding

dibs is the user's coding assistant; its phone app runs on the same Pixel. This file is the whole
contract between the two apps and the rules of the rider's side. Research behind it:
`~/Tasks/2026-10-09-research-how-motoparty-and-honda-and/REPORT.md`.

State (2026-10-09): **the motorcycle side is built and unit-tested, not run on the phone. The dibs
side (the ride service below) does not exist yet**, so the link reports "dibs isn't on this phone"
and nothing is sent. The dibs app is moving from Tether's package to its own (`com.kivan.dibs`);
the service comes after that move lands.

## Saying it

- A phrase whose first word is **"dibs"** (after "hey", "okay" or "ok") is for dibs. It is checked
  before the grammar and the interpreter, so a message to dibs never goes to Gemini
  (`core/DibsPhrase.kt`). The first-phrase gate lets it through with smart commands off too.
- What is sent is the rest of the phrase **as recognised** (case and punctuation kept, first letter
  upper-case): "dibs, in motoparty, make the lyrics toggle remember its setting" sends "In
  motoparty, make the lyrics toggle remember its setting".
- **Read-back, then a cancel window.** The rider hears "To dibs: <message>" in their own headset.
  After it ends, **3 s of silence sends it** (`DIBS_CANCEL_MS`). In that window:
  - "cancel", "no", "never mind", "scrap that", "don't send" (the whole phrase) drop it ("Dropped.");
  - a new "dibs …" replaces it and is read back in turn (the fix for a mishearing);
  - anything else, the read-back heard back by the Lark included, is ignored.
  Then the talk closes (every command ends its talk), the message goes, and an OK beep says dibs
  took it. Closing the talk by hand in the window does not drop it: silence means send.
- "dibs, quiet" / "dibs, talk" stay on this phone: nothing from dibs is spoken until "talk", and
  "talk" says how many lines came meanwhile ("they're in the app").
- No link (not installed, not allowed yet, gone): the rider hears why at once, nothing waits.
- Everything else ("dibs, what's new?", "dibs, what's running?", "dibs, idea: …") is a plain
  message: dibs's brain answers plain speech, so there is no command list here.

## Only the rider

- dibs treats the user's words as the user's authority, so **only the rider's channel counts**:
  a phrase recognised on the passenger's channel, or a `command.text` from the client, that starts
  with "dibs" is logged and dropped (`submitCommand`, `onPhrase`).
- **The passenger never hears dibs.** Everything dibs says is spoken on this phone only
  (`speakDibs`), never through `announce`, which also goes on the wire.

## What dibs says

`core/RideSpeech.kt`, fed by the link's lines:
- **Never over a talk, a phone call or a read-back**, nor in the 4 s after a talk while its music
  comes back; queued behind any reply being said, never cutting it off.
- A **question** is said as soon as the phone is free: "dibs asks: <line> It waits in the app."
  Answering by voice is not built: dibs counts a tap as the user's word today, and changing that is
  the user's call (research item 5).
- Anything else waits for **the next song to start** (2.5 s in, after a spoken `play`'s own
  "Playing …"), or for the music to stop, and never more than 5 minutes. What waited comes together, questions first, at most three lines ("And 2 more in the
  app.").
- Every line is **one short sentence**: code, links and paths stripped, at most 25 words.
- dibs speaks English (`en-US`) whatever the recogniser's language.
- The music is not paused for a line: like every other spoken reply here, it plays over the music
  on the media route. Pausing for it is for after a ride shows whether it's needed.

## The link

The dibs app exports a bound service; this app binds to it while its own service runs
(`voicecmd/DibsLink.kt`). One `Messenger` each way; every message's `replyTo` is this app's.

- Intent: action **`com.kivan.dibs.action.RIDE`**, package `com.kivan.dibs`, else `com.kivan.tether`
  (while dibs still lives inside Tether). This app declares the action in `<queries>`.
- Version 1. `Message.what` and the `data` Bundle keys:

| what | Way | data | Meaning |
|---|---|---|---|
| 1 HELLO | → dibs | `version` int, `riding` true | Bound: a ride is on. dibs answers HELLO_OK. |
| 2 SAY | → dibs | `uid` "ride-<uuid>", `text` | The rider's message, as heard and read back. dibs answers SAID. |
| 3 BYE | → dibs | `riding` false | The ride is over; we unbind next. |
| 101 HELLO_OK | ← dibs | `ok` bool, `why` string? | `ok` false: this app is not allowed yet. |
| 102 SAID | ← dibs | `uid`, `ok` bool, `why` string? | dibs took the message (queued counts as taken) or refused it. |
| 103 LINE | ← dibs | `id`, `speak`, `question` bool | A new dibs chat line's spoken form; sent once per line, resending is harmless (we skip seen ids). |

- We wait at most 5 s for SAID; no answer is "Couldn't reach dibs".
- **We check dibs too.** The first time a dibs package is bound, its signing certificates'
  SHA-256 is kept; a later package of that name signed by another key is refused (an app that
  only took the name would get the rider's words and speak in the helmet).
- A missing dibs app is looked for again every minute, so one installed mid-ride is found.

Known limit: the recogniser splits a phrase at a pause. If the rider pauses mid-message, the rest
lands in the cancel window and is ignored, so only the first part goes; the read-back says so,
and a new "dibs …" with the whole message replaces it.

### What the dibs side must do (not built)

1. **Check the caller.** `Binder.getCallingUid()` must be `com.kivan.motoparty`, and its signing
   certificate's SHA-256 must be the one the user allowed. The first time, dibs shows its own
   card ("Let the motorcycle app talk to dibs?") and answers HELLO_OK `ok=false, why="ask"` until
   the user taps Allow; it keeps the digest and refuses any other. The two apps are not signed
   with the same key, so a `signature` permission does not work.
2. **SAY** becomes the user's `say` on the dibs channel exactly as the phone's own mic sends it,
   with `spoken: true` and a new **`riding: true`**. The server then has the brain answer in one
   short spoken sentence, no code, nothing that needs a screen (anything that does goes to To
   look over), and treats the user as away from screens until BYE.
3. **LINE**: every new dibs line of the chat (and every new question) goes out with its `speak`
   field (the read-aloud work), `question` true for a question card.
4. **HELLO/BYE** set and clear the riding state on the server; a dropped binding is BYE after a
   minute.

## Starting a talk

**Only the app's own Talk button** (the Ride screen, the floating TALK button), as for every
other command: the user's call, 2026-10-09. No handlebar box button, no Lark button (the
transmitter sits inside the helmet), no wake word. A talk whose first phrase starts with "dibs"
is the whole way in: the link to dibs is bound while the app's service runs, and binding starts
dibs's ride service if it isn't running.
