# Motoparty wire protocol, version 1

Source of truth for `android/`, `ios/` and `tools/peer/`. Every encoding rule here has a
test vector in `fixtures/`; all three implementations must pass them. Change this file and
the fixtures first, then the code.

## Roles and ports

- **Host** = the Pixel. Runs the hotspot, advertises, owns the music queue, is the clock
  reference and the single authority for talk and music state.
- **Client** = the iPhone (or `tools/peer` on a laptop). One client at a time; a second
  `hello` replaces the first connection.

| What     | Transport | Port  |
|----------|-----------|-------|
| Control  | TCP       | 47800 |
| Voice    | UDP       | 47801 |
| Tracks   | HTTP/TCP  | 47802 |

Ports are fixed so the fallback sweep needs no discovery data, but the host also puts them
in the Bonjour TXT record and in its `hello`; clients must use the advertised values.

## Discovery

1. Host registers Bonjour service `_motoparty._tcp` on port 47800, instance name = device
   name, TXT: `proto=1`, `voice=47801`, `http=47802`.
2. Client browses `_motoparty._tcp` and connects to the first result.
3. Fallback (Bonjour over Android SoftAP is not guaranteed): after 3 s without a result,
   the client TCP-connects to port 47800 on every address of its own IPv4 /24 (excluding
   itself), 64 in parallel, 400 ms connect timeout, then 1 s to receive the host's `hello`,
   and uses the first one that answers with a valid `hello`. Android randomises the hotspot subnet, so never hard-code it.

## Control channel (TCP 47800)

Framing: `u32` big-endian byte length, then that many bytes of UTF-8 JSON (one object).
Maximum frame 64 KiB; a larger length is a protocol error and the connection is closed.

Every message is an object with a string field `t` (the type). Unknown types are ignored;
unknown fields are ignored. Absent optional fields are omitted, never `null`. Every field in
the table is required unless marked optional. A known type with a missing or mistyped
required field, or a value outside the listed set (e.g. an unknown `reason` or `action`), is
dropped and logged; the connection stays up. Invalid JSON or an oversize
frame closes the connection.
Times are integer milliseconds. `*HostTimeMs` values are on the host's clock (see Clock).

On connect, each side sends `hello` first. The host sends `state` immediately after its
`hello`, and again whenever anything in it changes. If a track is loaded, the host then
re-sends `music.load` for the current and next track, and (if playing) a `music.play` with
the current anchor once the client reports `music.ready`, so a client that joins mid-track
catches up.

| `t`             | Dir   | Fields |
|-----------------|-------|--------|
| `hello`         | both  | `proto`:1, `role`:`"host"`\|`"client"`, `name`: string, `voicePort`: int (host only), `httpPort`: int (host only) |
| `ping`          | C→H   | `id`: int, `t0`: client clock ms at send |
| `pong`          | H→C   | `id`, `t0` (echoed), `t1`: host clock at receive, `t2`: host clock at send |
| `talk.open`     | both  | `by`: `"host"`\|`"client"`. C→H is a request; H→C is the decision |
| `talk.close`    | both  | `by`, `reason`: `"trigger"`\|`"link"`\|`"unavailable"` |
| `music.load`    | H→C   | `id`: string, `path`: string (e.g. `/track/<id>.m4a`), `title`, `artist`, `album` (optional), `durationMs`: int |
| `music.ready`   | C→H   | `id` — the file is fully cached and decodable |
| `music.error`   | C→H   | `id`, `message` — starting with `"not decodable"` when the file downloaded but will not play (see Tracks) |
| `music.play`    | H→C   | `id`, `positionMs`, `atHostTimeMs` — also used for seek and resync |
| `music.pause`   | H→C   | `id`, `positionMs` |
| `music.stop`    | H→C   | (nothing) |
| `music.control` | C→H   | `action`: `"pause"`\|`"resume"`\|`"next"`\|`"previous"` (button presses on the client; volume is local, see Commands) |
| `command.text`  | C→H   | `text`: the recognised command, the first phrase of a talk the client opened (see Commands), `lang`: BCP-47 tag |
| `music.search`  | C→H   | `id`: int (request id), `kind`: `"songs"`\|`"albums"`\|`"playlists"`, `query`: string. See Browsing |
| `music.browse`  | C→H   | `id`: int, `ref`: string — the `ref` of an album or playlist result |
| `music.results` | H→C   | `id` (echoed), `items`: array of result items, `error` (optional): string to show instead of an empty list |
| `music.enqueue` | C→H   | `mode`: `"now"`\|`"next"`\|`"end"`, `tracks`: array of tracks, `art` (optional): URL for tracks without their own |
| `music.edit`    | C→H   | `op`: `"jump"`\|`"remove"`\|`"clear"`, `index` (optional): int, `id` (optional): string |
| `announce`      | H→C   | `text`: to be spoken by TTS; `earcon` (optional): `"ok"`\|`"error"` |
| `state`         | H→C   | see below |
| `bye`           | both  | `reason` (optional). Sender closes the socket after it |

`state`:

```json
{"t":"state","talk":false,
 "music":{"id":"dQw4w9WgXcQ","title":"…","artist":"…","playing":true,"positionMs":1234,
          "atHostTimeMs":987654,"durationMs":213000,"art":"https://…"},
 "queue":[{"id":"…","title":"…","artist":"…"}]}
```

`music` is omitted when nothing is loaded; its `art` (optional) is a cover image URL. `queue` (required, possibly empty) is the upcoming
tracks after the current one. `positionMs`/`atHostTimeMs` form the same anchor as in
`music.play`; while paused (including during talk) `playing` is false and `positionMs` is the
pause position.

### Liveness

The client sends `ping` every 2 s. Either side treats 6 s without any received control frame
as link loss: close, and (client) restart discovery; (host) close talk with reason `"link"`
and keep the music playing locally.

## Clock

The host clock is a monotonic millisecond counter (Android `SystemClock.elapsedRealtime()`).
The client keeps its own monotonic ms clock and estimates `offset = hostClock - clientClock`:

```
t3     = client clock at pong receipt
rtt    = (t3 - t0) - (t2 - t1)
offset = ((t1 - t0) + (t2 - t3)) / 2          (floating point)
```

Keep the last 8 samples; the estimate is the `offset` of the sample with the smallest `rtt`
(ties: most recent). Discard samples with `rtt < 0`; they take no window slot. If a new
sample's offset differs from the current estimate by more than 500 ms (the iOS monotonic
clock stops while the device sleeps), clear the window and start over from that sample. `hostToLocal(h) = h - offset`,
`localToHost(l) = l + offset`. Vectors: `fixtures/clock.json`.

## Voice (UDP 47801)

The host binds 47801. The client sends from any local port to the host's 47801; the host
replies to the source address of the most recent valid packet from the client. The client
sends a keepalive once per second whenever it is not sending audio, so the host always knows
where to send and the radios never idle.

Packet: 8-byte header, all big-endian, then the payload.

| Offset | Size | Field |
|--------|------|-------|
| 0      | 1    | magic `0x4D` (`'M'`) |
| 1      | 1    | kind: `1` = Opus audio, `2` = keepalive (no payload) |
| 2      | 2    | `seq`: u16, +1 per packet sent (audio and keepalive share it), wraps |
| 4      | 4    | `ts`: u32, sample index of the first sample in the frame at 16 kHz, wraps |

Packets with the wrong magic, unknown kind, or shorter than 8 bytes are dropped.
Vectors: `fixtures/voice/*.json`.

Audio: Opus, 16 kHz mono, 20 ms frames (320 samples, so `ts` advances by 320 per frame),
application VOIP, 24 kbps, in-band FEC on with expected loss 10 %, DTX on.

- **Sending:** `ts` is the sender's running 16 kHz sample clock. It starts at a random value
  when the voice socket opens and keeps running while talk is closed; a keepalive carries its
  current value. After encoding a frame, the sender checks `OPUS_GET_IN_DTX`; if it is 1 the
  frame (a DTX/comfort-noise update) is **not sent**. So every kind-1 packet on the wire is
  voice activity, and the gap in `ts` is silence.
- **Loss vs. silence:** audio and keepalive packets share `seq`. A `ts` jump with contiguous
  `seq` is silence (play nothing / comfort silence); a `seq` gap is loss. Keepalives are never
  counted as loss or as activity.

Audio flows only while talk is open. Receivers run an adaptive jitter buffer. *Target depth*
is the delay between a frame's arrival and its playout slot for the first frame of a talk
spurt. *Underrun* = a packet arrives after its playout slot has passed (an empty buffer during
silence is not an underrun). Start at 40 ms, raise by 20 ms (max 200 ms, at most once per
talk spurt however many packets were late) after an underrun,
lower by 20 ms (min 40 ms) after 10 s without one; depth changes take effect at the start of
the next talk spurt. A missing frame whose successor has arrived is decoded with FEC from the
successor; otherwise packet-loss concealment.

## Tracks (HTTP 47802)

`GET /track/<id>.m4a` → `200` with `Content-Type: audio/mp4` and the full audio-only MP4 file,
or `206` for a `Range` request. `404` if the host has not cached it (yet). Nothing else is
served. The client downloads the whole file before replying `music.ready`.

The audio is **Opus** (YouTube itag 251, 48 kHz stereo, remuxed from WebM, `dOps` pre-skip 0) or
**AAC-LC** (itag 140). The host serves Opus until a client answers `music.error` with a message
starting `"not decodable"`; it then switches to AAC for the rest of the session and sends
`music.load` for that track once more. Lossless does not exist on YouTube, and the Bluetooth
link re-encodes to AAC or SBC anyway.

## Talk flow

1. Trigger on either side. The client sends `talk.open{by:"client"}`; the host decides.
   Talk is not negotiable: there is no way for a person to decline it, and the host opens
   talk whenever it can. Only when a phone *cannot* open its microphone (a cellular call in
   progress, microphone permission missing, the audio route failed) does it answer with
   `talk.close{by:<itself>, reason:"unavailable"}`: the host instead of `talk.open` for a
   client request; the client right after the host's `talk.open` when the failure is on its
   side, which the host treats as a close request. The phone that asked plays the error earcon.
2. Host broadcasts `talk.open{by}` and `state{talk:true}`. Both sides pause music locally,
   switch the headset to call mode, play the "live" earcon when their mic is open, and start
   sending audio.
3. Ends when either side triggers again (`talk.close{reason:"trigger"}`, client→host as a
   request), when a spoken command ends it (see Commands), or on link loss. Never on silence: the 20 s silence close was removed on
   2026-09-29, because a headset mic that never goes DTX (the AirPods) could never trigger it.
4. Host broadcasts `talk.close` and `state{talk:false}` (except after the host's own
   `"unavailable"` answer in step 1: talk never opened and `state.talk` stayed false; a
   client's `"unavailable"` comes after the host's `talk.open`, so it is broadcast as usual).
   A microphone that fails *after* talk opened ends talk the same way: the host broadcasts
   `talk.close{by:"host", reason:"unavailable"}` + `state{talk:false}`, the client sends it as
   a close request. Both switch back to media mode. If music was playing before
   talk, the host sends `music.play` with `atHostTimeMs = now + resumeLeadMs` (setting,
   default 1500 ms, covering the headset profile switch).

## Music flow

1. Host resolves and caches a track, then sends `music.load`.
2. Client downloads it and replies `music.ready` (or `music.error`).
3. Host sends `music.play{positionMs, atHostTimeMs = now + 300}` once the client is ready
   (on `music.error`, or after 8 s without `music.ready`: the host plays alone and sends `music.play` anyway;
   a late client joins mid-track by computing its position from the anchor).
4. Drift: each side independently compares its player position with the anchor
   (`expected = positionMs + (hostNow - atHostTimeMs)`). Every play or seek on Bluetooth
   (A2DP) restarts the output with 350–700 ms of fresh lag (measured on the Pixel 8), so
   re-seeking to fix small drift oscillates. Instead: start playback early by the measured
   output delay, correct 80 ms–1 s of drift by changing the playback rate by up to ±2 %
   until it is back under 80 ms, and re-seek only above 1 s. Each phone also applies its own output-latency trim
   (setting, ms) to account for Bluetooth delay.
5. Pause/stop are immediate on receipt. The host sends `music.load` for the next queue
   item as soon as the current one starts, so the client prefetches it.

## Browsing

The client can search and queue music by touch; the host does the searching (it has the
catalog and the internet) and stays the only authority over the queue. None of this changes
the Music flow: an enqueued track is loaded, readied and played exactly as before.

1. The client sends `music.search{id, kind, query}` with a fresh `id` (any increasing int).
   The host answers `music.results{id, items}` for that `id`, or `music.results{id, items:[],
   error}` when the search failed (`error` is short text for the screen, e.g. "No coverage").
   The client shows only the results of its newest request and ignores older `id`s.
2. A result item is `{ref, title, artist}` plus optional `durationMs` (songs), `count`
   (albums and playlists: number of tracks, when known) and `art` (a cover image URL the
   client fetches itself, over the host's hotspot). For `kind:"songs"` the `ref` is the
   track's YouTube id; for `"albums"` and `"playlists"` it is a YouTube playlist id, which the
   client passes to `music.browse{id, ref}`. The host answers that with `music.results` whose
   items are the collection's songs, in order. `artist` may be empty (a playlist's owner can
   be unknown). Only YouTube ids travel: `[A-Za-z0-9_-]`, 1–64 characters; the host answers a
   `music.browse` with any other `ref` with an `error`.
3. The client sends `music.enqueue{mode, tracks}`. A track is `{id, title, artist,
   durationMs}` plus optional `album` and `art`, built from song results (`id` = the item's
   `ref`, `album` = the collection's title when browsing one). `mode`: `"now"` replaces the
   queue with these tracks and starts the first; `"next"` inserts them right after the current
   track; `"end"` appends them. With nothing playing, `"next"` and `"end"` act like `"now"`.
   The host skips tracks with an invalid `id` and ignores an enqueue left with none. It never
   holds more than 200 upcoming tracks (so `state` stays well under 64 KiB): tracks past that
   are dropped, from the end of the queue. The top-level `art` covers tracks without their own (an album's cover).
4. The client sends `music.edit{op, index, id}` to change the upcoming queue: `"jump"` plays
   `state.queue[index]` now (the tracks before it stay behind the current one, so `previous`
   still reaches them), `"remove"` drops it, `"clear"` drops every upcoming track and needs
   neither field. For `"jump"` and `"remove"`, `index` and `id` are required and `id` must equal
   `state.queue[index].id`; otherwise the queue changed under the client and the host ignores
   the edit. Every change reaches the client as a new `state`.
5. Size: a `music.results` or `music.enqueue` frame must stay under 64 KiB. The sender drops
   per-item `art` first, then trailing items. Hosts cap a collection at 200 tracks.

## Commands

Commands are spoken **inside a talk** (since 2026-09-29; there is no separate command mode,
command trigger or wake word). While a talk is open, the phone that opened it runs on-device ASR
on its own talk microphone — the same capture that feeds the encoder, taken before the encoder so
DTX does not cut it — one result per phrase.

**The first phrase decides** (2026-09-29; replaced the "Moto party" wake word). The *opener* is
the side named in the host's `talk.open{by}`. Only the opener's phone produces commands, and only
from its **first phrase**: the first recognised phrase that is not empty after normalisation
(below) and whose result arrives within **`FIRST_PHRASE_MS` = 8000 ms of that phone's live
earcon** (about 5 s to start speaking plus the phrase itself; ASR reports a phrase when it ends).
If that phrase parses under the grammar (anything but "Didn't catch that"), it is a command and its
normalised text is the command text. Otherwise it is conversation, and so is every later phrase
and every phrase after the window: conversation is never sent and never acted on, and an
unparsed first phrase gets no "Didn't catch that". Once the first phrase is spent or the window
has passed, a phone may stop recognising for the rest of the talk. The non-opener's phone never
produces commands and need not recognise at all.

**Solo talk.** When the host's talk has no client, every non-empty phrase on the host is a
command, with no window, and an unparsed one gets "Didn't catch that".

The client sends its command as `command.text` (volume excepted, below). **The host enforces the
rule:** it acts on `command.text` only while a talk is open, the client opened it, and it is the
first `command.text` of that talk; any other is ignored and logged, with no `announce`. The window
is the client's to keep. The host parses the text (grammar below), acts, and sends `announce` to
the client and speaks it itself. Vectors: `fixtures/first_phrase.json` (one phone's gate: its
role, the phrases with their arrival time after the live earcon, and per phrase the command text,
or `null` for conversation).

**Effect on the talk.** `play …` and `resume` end the talk: the host closes it
(`talk.close{by: <the side that spoke>, reason:"trigger"}`) as soon as the command parses, and
the music starts after the headset is back in media mode, like any resume after talk; its
`announce` is spoken after that switch too. They close the talk even if they then fail (no
search result, nothing to resume): the error is announced after the switch and music that was
playing before the talk resumes. `end` ends the talk exactly like a press (music that was
playing resumes) and has no `announce`: the closing earcon is its acknowledgement.
`pause`, `next`, `previous` and the volume commands leave the talk open: music is paused during
a talk anyway, so `next`/`previous` choose what resumes after it and `pause` cancels that
resume (and stays cancelled through a later `next`/`previous`). Their `announce` is spoken in the talk.

Volume is local: `volume up/down` changes the volume of the phone it was spoken on (or whose
button was pressed) and is never sent. The client therefore runs the same parser on its own
utterances first, handles a volume result itself (earcon `ok`, no `announce`), and sends
`command.text` for everything else. A host that still receives a volume utterance in
`command.text` answers "Didn't catch that".

```
play [song|album|artist|playlist] <query>
pause | stop | resume | continue
next | skip | previous | back
volume up | louder | volume down | quieter
over | end talk | hang up                       (action "end": close the talk)
```

Normalisation before matching, per Unicode code point (not grapheme cluster): lowercase;
map `’` (U+2019) to `'`; replace every code point that is not in a Unicode letter (L*),
mark (M*) or number (N*) category, not `'` and not whitespace with a space; collapse
whitespace; trim. Then drop leading
"hey"/"please" words and one trailing "please". `play [the] <kind> <query>` with kind in
song/album/artist/playlist; without a kind, `play <query>` searches songs; an empty query is
not a command. The other phrases must match exactly ("next song" is not `next`). Anything
else → `announce{text:"Didn't catch that", earcon:"error"}`. Vectors: `fixtures/commands.json`.

## Test vectors

| File | Covers |
|------|--------|
| `fixtures/control/messages.json` | every message type round-trips |
| `fixtures/control/framing.json`  | length prefix, UTF-8, oversize rejection, unknown types/fields |
| `fixtures/clock.json`            | offset estimator incl. negative RTT, ties, window eviction |
| `fixtures/voice/header.json`     | UDP header encode/decode and rejection |
| `fixtures/commands.json`         | command parser |
| `fixtures/first_phrase.json`     | the first-phrase gate: command text or conversation per phrase |
