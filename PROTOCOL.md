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
2. Client browses `_motoparty._tcp`. **A Bonjour result is only a candidate, never the host.**
   mDNS caches keep dead services for up to 75 min (a crashed test, another phone, a
   host that went away), so the client probes *every* result, in parallel, as it appears:
   TCP connect (3 s, name resolution included), then 1 s to receive the host's `hello`.
   The host is the first candidate whose first frame is a `hello` with `role:"host"` and
   the client's `proto`. Only then does the client stop browsing and sweeping and open its
   control connection. A candidate that fails is not probed again for 10 s (1 s when the
   connection was refused: a host that is restarting is not listening yet); the browse
   keeps running meanwhile, so a host that appears later is still found. When several
   answer at once, the one named like the last host this client linked to wins; otherwise
   the first to answer.
3. Fallback (Bonjour over Android SoftAP is not guaranteed): 3 s after discovery starts
   without a host, the client also TCP-connects to port 47800 on every address of its own
   IPv4 /24 (excluding itself), 64 in parallel, 400 ms connect timeout, then 1 s to receive
   the host's `hello`, with the same acceptance rule. It runs alongside the Bonjour probes,
   whatever the browse shows. Android randomises the hotspot subnet, so never hard-code it.
4. A probe sends nothing: it only reads the host's `hello` and closes (a client `hello`
   would replace the host's current client). The host treats such a connection as no
   session: no state change beyond a log line.
5. After link loss the client also probes the address it was last linked to, at once and then
   every second, alongside everything above and exempt from the back-off: a host that
   restarted is usually where it was.
6. Test tools never advertise `_motoparty._tcp` on a real network by default (opt-in only,
   with a short TTL), so a test run can't leave stale hosts on the riders' Wi-Fi.

## Control channel (TCP 47800)

Framing: `u32` big-endian byte length, then that many bytes of UTF-8 JSON (one object).
Maximum frame 64 KiB; a larger length is a protocol error and the connection is closed.

Every message is an object with a string field `t` (the type). Unknown types are ignored;
unknown fields are ignored. Absent optional fields are omitted, never `null`. Every field in
the table is required unless marked optional. A known type with a missing or mistyped
required field, or a value outside the listed set (e.g. an unknown `reason` or `action`), is
dropped and logged; the connection stays up. Invalid JSON or an oversize
frame closes the connection. A frame whose bytes are not valid UTF-8 is treated as invalid
JSON: the connection is closed (no U+FFFD replacement; `fixtures/control/framing.json` `fatal`).
Times are integer milliseconds. `*HostTimeMs` values are on the host's clock (see Clock).

On connect, each side sends `hello` first. Each side checks the other's `hello.proto`: the
host answers any other value with `bye{reason:"proto"}` and closes; no session starts and a
connected client is not replaced. A client that gets `bye{reason:"proto"}`, or reads another
`proto` in the host's `hello`, shows the mismatch and does not connect to that host again
until the user asks (no reconnect loop). The host sends `state` immediately after its
`hello`, and again whenever anything in it changes. If a track is loaded, the host then
re-sends `music.load` for the current and next track, and (if playing) a `music.play` with
the current anchor once the client reports `music.ready`, so a client that joins mid-track
catches up.

| `t`             | Dir   | Fields |
|-----------------|-------|--------|
| `hello`         | both  | `proto`:1, `role`:`"host"`\|`"client"`, `name`: string, `voicePort`: int (host only), `httpPort`: int (host only), `interpret`: bool (host only, optional, absent = `false`: the host interprets unparsed first phrases, see Commands, *Interpretation*) |
| `ping`          | C→H   | `id`: int, `t0`: client clock ms at send |
| `pong`          | H→C   | `id`, `t0` (echoed), `t1`: host clock at receive, `t2`: host clock at send |
| `talk.open`     | both  | `by`: `"host"`\|`"client"`, `mic` (optional, H→C only): `"host"`. C→H is a request; H→C is the decision. See "Host-mic talk" |
| `talk.close`    | both  | `by`, `reason`: `"trigger"`\|`"link"`\|`"unavailable"` |
| `music.load`    | H→C   | `id`: string, `path`: string (e.g. `/track/<id>.m4a`), `title`, `artist`, `album` (optional), `durationMs`: int |
| `music.ready`   | C→H   | `id` — the file is fully cached and decodable |
| `music.error`   | C→H   | `id`, `message` — starting with `"not decodable"` when the file downloaded but will not play (see Tracks) |
| `music.play`    | H→C   | `id`, `positionMs`, `atHostTimeMs` — also used for seek and resync |
| `music.pause`   | H→C   | `id`, `positionMs` |
| `music.next`    | H→C   | `id`, `atHostTimeMs` — the track that follows the current one without a gap, see Music flow 6 |
| `music.stop`    | H→C   | (nothing) |
| `music.control` | C→H   | `action`: `"pause"`\|`"resume"`\|`"next"`\|`"previous"`\|`"repeat"` (button presses on the client; volume is local, see Commands), `mode` (required for `"repeat"`, 2026-10-01): `"off"`\|`"track"`\|`"queue"`, the repeat mode to set. See "Repeat by touch" |
| `command.text`  | C→H   | `text`: the recognised command, the first phrase of a talk the client opened (see Commands), `lang`: BCP-47 tag |
| `music.search`  | C→H   | `id`: int (request id), `kind`: `"songs"`\|`"albums"`\|`"playlists"`\|`"artists"` (`"artists"` 2026-10-02), `query`: string. See Browsing |
| `music.browse`  | C→H   | `id`: int, `ref`: string — the `ref` of an album, playlist or artist result, `kind` (optional, 2026-10-02): `"artist"` for an artist's page; absent = an album or playlist. See Browsing step 2a |
| `music.results` | H→C   | `id` (echoed), `items`: array of result items, `error` (optional): string to show instead of an empty list, `albums` (optional, 2026-10-02): array of result items, an artist page's albums and singles |
| `music.enqueue` | C→H   | `mode`: `"now"`\|`"next"`\|`"end"`, `tracks`: array of tracks, `art` (optional): URL for tracks without their own |
| `music.edit`    | C→H   | `op`: `"jump"`\|`"remove"`\|`"clear"`\|`"move"`, `index` (optional): int, `id` (optional): string, `to` (optional): int, the new index for `"move"` (2026-10-01). See Browsing |
| `music.download` | C→H  | `op`: `"start"`\|`"stop"`, `ref`: string — the `ref` of an album or playlist result, `ids` (required for `"start"`): array of strings, the collection's track ids in order (2026-10-01). See Browsing step 6 |
| `music.downloads` | H→C | `cached`: array of strings, the track ids the host has downloaded; `downloads`: array of `{ref, done, total, failed, running}`, one per collection download (2026-10-01). See Browsing step 6 |
| `announce`      | H→C   | `text`: to be spoken by TTS; `earcon` (optional): `"ok"`\|`"error"`; `ask` (optional, only `true`): the text is a clarifying question, see Commands, "The clarifying question" |
| `state`         | H→C   | see below |
| `bye`           | both  | `reason` (optional). Sender closes the socket after it |

`state`:

```json
{"t":"state","talk":false,
 "music":{"id":"dQw4w9WgXcQ","title":"…","artist":"…","playing":true,"positionMs":1234,
          "atHostTimeMs":987654,"durationMs":213000,"art":"https://…","repeat":"track"},
 "queue":[{"id":"…","title":"…","artist":"…"}]}
```

`mic` (optional, `"host"`) is present only while `talk` is true and the open talk is a host-mic
talk (see "Host-mic talk"), so a client that learns of the talk only from `state` (it joined
mid-talk) opens it the same way. A receiver ignores `mic` on `state{talk:false}` (the message is kept). `music` is omitted when nothing is loaded; its `art` (optional) is a cover image URL, and its `repeat` (optional, 2026-10-01) is `"track"` (the current track starts again when it ends) or `"queue"` (after the last track the queue starts again from the first track it still holds); absent means off, and the host never sends `"off"`. Any other value drops the `state`. `queue` (required, possibly empty) is the upcoming
tracks after the current one; an item is `{id, title, artist}` plus optional `durationMs` and `art` (2026-09-30; the host leaves `art` out of every item when `state` would pass 48 KiB). `positionMs`/`atHostTimeMs` form the same anchor as in
`music.play`; while paused (including during talk) `playing` is false and `positionMs` is the
pause position. `busy` (optional string, 2026-10-01) is present only while the host is searching
for a voice command's `play` or `add` (Commands, *Queueing by voice*): short text for the
screen, such as `Searching song "moby"`, without a trailing ellipsis. The client shows it
under now playing with a spinner (with "…" added), also when nothing is loaded, and stops
showing it on the first `state` without it. The host's own status line shows the same text.

**Repeat by touch** (2026-10-01). The client's repeat button sends
`music.control{action:"repeat", mode}` with the mode it wants, not a toggle: the button cycles
off → queue → track → off from the mode in the last `state` (absent = off), so a press that
crosses a change from the other phone sets what the rider saw next, not a second step. The
host sets the mode exactly like its own repeat button (a mode it already has changes nothing)
and sends `state`. `mode` is required with `"repeat"`: a `"repeat"` without it, or a `mode`
outside the set with any action, is malformed and dropped. `mode` is `"off"` here although
`state` never carries `"off"`. Other actions ignore a valid `mode`. A touch repeat is not a
voice list, so it neither saves nor clears the voice undo (Commands, *Voice actions*).

### Liveness

The client sends `ping` every 2 s. Either side treats 6 s without any received control frame
as link loss: close, and (client) restart discovery; (host) close talk with reason `"link"`
and keep the music playing locally.

A client often notices a dead socket before the host does and is back within a second. When
a client `hello` replaces a connection whose client had the same `name`, the host does
**not** close an open talk: the talk carries on, `state{talk:true}` on the new connection is
what the client acts on, and voice follows the source address of its next valid packet. A
`hello` with a different `name` replaces the client and closes the talk with `"link"`.

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
sample's offset differs from the current estimate by more than `500 + rtt / 2` ms, `rtt`
being the new sample's (the iOS monotonic clock stops while the device sleeps; a slow pong
alone can be off by half its round trip and must not throw a good window away), clear the
window and start over from that sample. A reconnect to the same host keeps the window. A host with another `name` starts a fresh one. `hostToLocal(h) = h - offset`,
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

Audio flows only while talk is open. In a host-mic talk (see Talk flow) only the host sends
audio; the client sends keepalives only, and a host that still receives client audio drops it. Receivers run an adaptive jitter buffer. *Target depth*
is the delay between a frame's arrival and its playout slot for the first frame of a talk
spurt. *Underrun* = a packet arrives after its playout slot has passed (an empty buffer during
silence is not an underrun). Start at 40 ms, raise by 20 ms (max 200 ms, at most once per
talk spurt however many packets were late) after an underrun,
lower by 20 ms (min 40 ms) after 10 s without one; depth changes take effect at the start of
the next talk spurt. A missing frame whose successor has arrived is decoded with FEC from the
successor; otherwise packet-loss concealment.

A *talk spurt* starts at the first audio packet and again after every silence gap. A packet
that arrives after the playout clock passed its slot is **not** an underrun but the first
packet of a new spurt when all of these hold: nothing is queued, its `ts` is at least two
frames past the last played frame, and every `seq` between the last played packet and it was
a keepalive (or an audio packet that came too late to play).

**Shedding a backlog.** An earbud microphone never goes silent, so a whole talk can be one
spurt, and a playout stall (a route switch, a blocked audio write) or an output clock slower
than the sender's would otherwise be heard as extra delay until the talk ends. A receiver
therefore drops queued frames, oldest first:

1. *At spurt start*, until the queue spans no more than the target
   (`newest ts − oldest ts ≤ target`), then starts at the oldest that is left.
2. *During a spurt*, it notes the queue depth (frames queued × 20 ms) each time a frame is
   due and keeps the smallest over every 50 frames due (1 s; the window restarts at spurt
   start). At the end of a window, with `min` that smallest depth: if `min > target + 120 ms`
   it drops `(min − target) / 20 ms` frames; else if `min > target + 40 ms` it drops one.

Shed frames are neither loss nor underruns: no FEC, no concealment, no target change.

Three more rules keep a receiver from getting stuck, and the vectors pin them too. A packet
counts as on time up to half a frame after its slot. When packets have kept arriving late for
100 ms with nothing played in between, the playout clock is ahead of the stream for good: the
next late packet starts a new spurt instead of being dropped (*re-anchor*; the target keeps
the raise the first late packet gave it). A packet whose `ts` is more than 3 s from the
playout point restarts the buffer at the current target. Whatever else happens, the queue
never holds more than 400 ms: the oldest frames go first (shed). With nothing queued
mid-spurt a receiver conceals at most 3 frames, then plays silence.
Vectors: `fixtures/jitter.json`.

## Tracks (HTTP 47802)

`GET /track/<id>.m4a` → `200` with `Content-Type: audio/mp4` and the full audio-only MP4 file,
or `206` for a `Range` request. `404` if the host has not cached it (yet). The only other
path served is `/lyrics/<id>.json` (see Lyrics below). The client downloads the whole file before replying `music.ready`.

The audio is **Opus** (YouTube itag 251, 48 kHz stereo, remuxed from WebM, `dOps` pre-skip 0) or
**AAC-LC** (itag 140). The host serves Opus until a client answers `music.error` with a message
starting `"not decodable"`; it then switches to AAC for the rest of the session and sends
`music.load` for that track once more. Lossless does not exist on YouTube, and the Bluetooth
link re-encodes to AAC or SBC anyway.

### Lyrics (2026-10-02)

`GET /lyrics/<id>.json` on the same server, `<id>` a valid track id (else `404`). Answers:

- `200`, `Content-Type: application/json; charset=utf-8`, body
  `{"id":"<id>","source":"lrclib","lines":[{"ms":int,"text":str,"words":[{"ms":int,"text":str}]}]}`.
  `lines` is sorted by `ms` (track position, ms); a line with empty `text` and no `words` is an
  instrumental break. Word times are computed by the host (rules below) so both phones light
  up the same word at the same moment.
- `404`: the host looked and there are no synced lyrics for this track, or it does not know
  the track (no title and artist to look up). Final for this `music.load`.
- `503`: a lookup is running, or the host is offline and has not looked yet. The client tries
  again after 5 s, at most 6 retries per id (7 requests, about 30 s), then gives up until the id
  is loaded again. Any other status, or a `200` body it cannot decode, is not retried.

Lyrics are **shown only on request**: each phone has its own toggle, off by default, and the
client fetches lyrics only while its toggle is on (for the current track and the one its last
`music.load` prefetched). Nothing about lyrics goes over the control channel. The host looks
lyrics up whenever it caches a track (including collection downloads), whatever its own
toggle, so they are there without coverage. Source: LRCLIB synced lyrics (`/api/search`),
best candidate by timing plausibility, then by duration closest to the track's.

Each phone keeps its own lyrics offset per track (±0.2 s steps, like its output-latency trim):
the position used for lyrics is `positionMs - offsetMs`, so a negative offset shows them
sooner.

**LRC to lines** (host, and the peer's fake host): split the LRC on line breaks (`\n`,
`\r\n`). Each stamp `[m:ss]` or `[m:ss.f]` (regex `\[(\d+):(\d+)(?:\.(\d+))?]`, ASCII digits, any number
of them from the very first character of the line; a stamp after leading whitespace does not count) gives `ms = m*60000 + ss*1000 + f`, where `f` is the fraction's
digits padded with zeros or cut to three (`.5` = 500, `.12` = 120, `.123` = 123). The text is
whatever follows the last stamp, trimmed of spaces and tabs. A source line with several stamps
gives one line per stamp; a source line with no stamp (`[ar:…]` tags, plain text) is dropped.
Lines are sorted by `ms`, stable (equal times keep source order).

**Words**: the text split on runs of spaces and tabs (U+0020, U+0009; nothing else
separates). A word's length is its count of Unicode code points (not UTF-16 units, not
grapheme clusters); `c_i` is the summed length of the words before word `i` and `total` that
of all the line's words. Word `i` starts at `line.ms + 75 * c_i`, unless the line has a next
line and `line.ms + 75 * total > next.ms` (it would run into the next line); then it starts at
`line.ms + floor(c_i * (next.ms - line.ms) / total)`, in integers.

**Timeline** (both phones): at lyrics position `t`, the current line is the last line with
`ms <= t` (none before the first line); the sung words are those of the current line with
`ms <= t`.

## Talk flow

1. Trigger on either side. The client sends `talk.open{by:"client"}`; the host decides.
   A second trigger on the client before the decision arrives sends
   `talk.close{by:"client", reason:"trigger"}` (the host, having opened the talk, closes it),
   and a request unanswered for 5 s is dropped by the client.
   Talk is not negotiable: there is no way for a person to decline it, and the host opens
   talk whenever it can. Only when a phone *cannot* open its microphone (a cellular call in
   progress, microphone permission missing, the audio route failed) does it answer with
   `talk.close{by:<itself>, reason:"unavailable"}`: the host instead of `talk.open` for a
   client request; the client right after the host's `talk.open` when the failure is on its
   side, which the host treats as a close request. The phone that asked plays the error earcon.
2. Host broadcasts `talk.open{by}` and `state{talk:true}`. Both sides pause music locally,
   switch the headset to call mode, play the "live" earcon when their mic is open, and start
   sending audio (a host-mic talk differs, see "Host-mic talk" below).
3. Ends when either side triggers again (`talk.close{reason:"trigger"}`, client→host as a
   request), when a spoken command ends it (see Commands), when a track is played by touch (see
   Browsing), or on link loss. Never on silence: the 20 s silence close was removed on
   2026-09-29, because a headset mic that never goes DTX (the AirPods) could never trigger it.
4. Host broadcasts `talk.close` and `state{talk:false}` (except after the host's own
   `"unavailable"` answer in step 1: talk never opened and `state.talk` stayed false; a
   client's `"unavailable"` comes after the host's `talk.open`, so it is broadcast as usual).
   A microphone that fails *after* talk opened ends talk the same way: the host broadcasts
   `talk.close{by:"host", reason:"unavailable"}` + `state{talk:false}`, the client sends it as
   a close request. Both switch back to media mode. If music was playing before
   talk, the host sends `music.play` with `atHostTimeMs = now + resumeLeadMs` (setting,
   default 1500 ms, covering the headset profile switch).

### Host-mic talk

Since 2026-09-29 the host may capture **both riders' voices itself**: one two-channel receiver
(Hollyland Lark A1 in Stereo mode) on the host phone, the rider's microphone on the left channel
and the passenger's on the right. The host says so in its decision: `talk.open{by, mic:"host"}`.
Without `mic` (the host has no such receiver, or its setting is off) every phone uses its own
headset microphone as above. The host decides per talk, at the open; the mode is fixed until that
talk closes. `mic` exists only on the host's `talk.open` and in `state` (while that talk is
open): a client never sends it, and a host ignores it on a request. Its only value is `"host"`; another value drops the message like any
value outside its set.

In a host-mic talk:
- **Host.** Captures both channels. The rider's channel is encoded and sent as the usual voice
  (16 kHz mono Opus, unchanged). The passenger's channel is played locally into the host's own
  headset and is never sent. No one hears their own voice.
- **Client.** Opens **no microphone** and does not need microphone permission. It pauses music
  and plays the voice it receives, but keeps the headset in **media mode** (no call mode, no
  headset profile switch). It plays the live earcon when its voice playback is ready. It sends
  keepalives, never audio. It answers `talk.close{reason:"unavailable"}` only if it cannot play
  (its audio route failed), not over microphone permission or a cellular call's microphone.
- **Host headset.** Also stays in media mode. With no profile switch on either side, the
  host may shorten `resumeLeadMs` after such a talk.
- If the receiver is unplugged or fails during the talk, the host ends it with
  `talk.close{by:"host", reason:"unavailable"}`; the next talk opens without `mic`.

## Music flow

1. Host resolves and caches a track, then sends `music.load`.
2. Client downloads it and replies `music.ready` (or `music.error`).
3. Host sends `music.play{positionMs, atHostTimeMs = now + lead}` once the client is ready.
   `lead` is long enough for the host itself to be audible *at* the anchor: its own measured
   output start delay plus preparation, never under 300 ms (2026-09-30; a fixed 300 ms made
   the host skip the first 250–750 ms of every track and resume). A client that cannot be
   ready by `atHostTimeMs` starts late at the position the anchor gives.
   On a `music.error` for the current track the host sends its `music.load` once more after
   2 s; on a second error, or after 8 s without `music.ready`, the host plays alone and sends
   `music.play` anyway; a late client joins mid-track by computing its position from the anchor.
4. Drift: each side independently compares its player position with the anchor
   (`expected = positionMs + (hostNow - atHostTimeMs)`). Every play or seek on Bluetooth
   (A2DP) restarts the output with 350–700 ms of fresh lag (measured on the Pixel 8), so
   re-seeking to fix small drift oscillates. Instead: start playback early by the measured
   output delay, correct 80 ms–1 s of drift by changing the playback rate by up to ±2 %
   until it is back under 80 ms, and re-seek only above 1 s. Each phone also applies its own output-latency trim
   (setting, ms) to account for Bluetooth delay.
5. Pause/stop are immediate on receipt. The host sends `music.load` for the next queue
   item as soon as the current one starts, so the client prefetches it.
6. **Gapless** (2026-09-30). When the next track is ready on both phones (the client
   answered `music.ready` for it) and the current one is playing, the host sends
   `music.next{id, atHostTimeMs}`: track `id` starts at position 0 at that host time, which
   is when the current track ends by its anchor. Each phone queues the track behind the
   current one in its own player, so the change has no gap locally, and from then on
   `{id, positionMs:0, atHostTimeMs}` is the anchor drift is measured against. At the change
   the host sends `state` and the usual `music.play{id, 0, atHostTimeMs}` with that same
   anchor; a client already playing `id` on that anchor must not seek or restart for it.
   A pending `music.next` is cancelled by: any `music.play` message other than the one of the
   change (the pending track on its announced anchor) — so also one that repeats the current
   track's anchor unchanged, which is how the host takes a `music.next` back; the client does
   not seek for that one — and by `music.pause`, `music.stop`, a talk, a `music.next` naming
   another track, and a `state` whose `music` is absent, is not playing, or names a track that
   is neither the current nor the pending one. A `state` that names the current track playing
   on its unchanged anchor does **not** cancel (the host
   sends one on every queue edit). The host sends `music.next` again once playback continues
   and after every `music.play` it sends while one is announced. Once the host has sent the
   `music.play` of the change, a `music.next` that reaches a client which has not changed over
   yet names the track *after* the pending one: the client applies it after its own change,
   never in place of the pending track. A client that ignores `music.next` still
   works: it starts the track on the `music.play`, with a gap.

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
2a. **Artists** (2026-10-02). For `kind:"artists"` a result item is `{ref, title, artist, art}`:
   `ref` is the artist's YouTube channel id (`UC…`), `title` the artist's name, `artist` empty,
   `art` the artist's picture (optional). The client opens the artist's page with
   `music.browse{id, ref, kind:"artist"}`; the host answers `music.results{id, items, albums}`:
   `items` are the artist's top songs (song items as for `kind:"songs"`, at most 20), `albums`
   the artist's albums and singles (album items as for `kind:"albums"`, at most 50, `ref`s for an
   ordinary `music.browse`), either possibly empty. One reply holds both, so the page is one
   request. A `music.browse` `kind` other than `"artist"` is malformed. A host older than this
   ignores `kind` and answers with an `error` (a channel id is not a playlist); a client older
   than this ignores `albums`. Size: as step 5, the host drops `art` first, then trailing
   `albums`, then trailing `items`.
3. The client sends `music.enqueue{mode, tracks}`. A track is `{id, title, artist,
   durationMs}` plus optional `album` and `art`, built from song results (`id` = the item's
   `ref`, `album` = the collection's title when browsing one). `mode`: `"now"` replaces the
   queue with these tracks and starts the first; `"next"` inserts them right after the current
   track; `"end"` appends them. With nothing playing, `"next"` and `"end"` act like `"now"`.
   The host skips tracks with an invalid `id` and ignores an enqueue left with none. It never
   holds more than 200 upcoming tracks (so `state` stays well under 64 KiB): tracks past that
   are dropped, from the end of the queue. The top-level `art` covers tracks without their own (an album's cover).
   **Play by touch ends a talk** (2026-09-30): a `mode:"now"` enqueue, a `"jump"` edit, or the
   host's own touch equivalents, while a talk is open, end the talk exactly like a spoken
   `play …`: the host closes it (`talk.close{by: <the side that touched>, reason:"trigger"}`)
   and the music starts after the headset is back in media mode. `"next"`, `"end"`, `"remove"`
   and `"clear"` leave the talk open.
4. The client sends `music.edit{op, index, id}` to change the upcoming queue: `"jump"` plays
   `state.queue[index]` now (the tracks before it stay behind the current one, so `previous`
   still reaches them), `"remove"` drops it, `"move"` (2026-10-01, drag to reorder) takes it out
   and puts it back so that it is `state.queue[to]` afterwards (`to` past the end = the end),
   `"clear"` drops every upcoming track and needs neither field. For `"jump"`, `"remove"` and
   `"move"`, `index` and `id` are required and `id` must equal `state.queue[index].id`; otherwise
   the queue changed under the client and the host ignores the edit. `"move"` also requires `to`
   (an int ≥ 0); without it, or with a negative one, the message is dropped as malformed. Every
   change reaches the client as a new `state`.
5. Size: a `music.results` or `music.enqueue` frame must stay under 64 KiB. The sender drops
   per-item `art` first, then trailing items. Hosts cap a collection at 200 tracks.
6. **Downloads** (2026-10-01): a whole album or playlist into the host's track cache, for
   riding through patchy coverage, and the "downloaded" marks on song rows. The client sends
   `music.download{op:"start", ref, ids}` with the collection's `ref` and the `ref`s of its
   `music.browse` results, in order (the host does not browse it again). The host downloads
   them exactly as for its own Download button: one track at a time across every collection,
   after any track the queue needs, and already-cached tracks count as done at once. It skips
   invalid ids and keeps at most 200; a `ref` that is not a valid id, or a start left with no
   ids, is ignored, and so is a start for a collection already downloading. `"stop"` (`ids` not
   needed, ignored) stops that collection's download, the track in flight included, and forgets
   its progress; what was downloaded stays cached. A `"start"` without `ids` is malformed and
   dropped. The host sends `music.downloads{cached, downloads}` right after a client's `hello`
   and again whenever either list changes. `cached` (required, possibly empty, any order) is
   every track id in the host's active track cache: those play without coverage, and both
   phones mark them "downloaded" wherever a song row shows that id. `downloads` (required,
   possibly empty) has one item per collection downloaded since the host started, started by
   either phone and keyed by its `ref`: `done` (tracks handled so far, failures included),
   `total`, `failed` (tracks that could not be downloaded) and `running` (false once
   `done` = `total`); a stopped one is left out. A frame that would not fit 64 KiB drops
   trailing `cached` ids. These are not in `state`: `state` is re-sent on every play, pause and
   queue change, its 64 KiB is sized for 200 queue items, and `cached` alone can be several KiB.
   The client's button, as on the host: "Downloaded" when every song is in `cached` (or the
   download finished with no failures), "Downloading done/total · Stop" while running (a tap
   sends `"stop"`), "Retry · saved/total" after failures (a tap starts it again), otherwise
   "Download".

## Commands

Commands are spoken **inside a talk** (since 2026-09-29; there is no separate command mode,
command trigger or wake word). While a talk is open, the phone that opened it runs on-device ASR
on its own talk microphone — the same capture that feeds the encoder, taken before the encoder so
DTX does not cut it — one result per phrase. **In a host-mic talk** the host does it for both:
it runs ASR on the opener's channel (left when the host opened the talk, right when the client
did), keeps the window from its own live earcon, and acts on the passenger's first phrase as if
it had arrived as `command.text` (the client's role for "Effect on the talk"); the client never
recognises. A volume phrase on the passenger's channel is ignored with no `announce` (the
passenger's volume keys already do it). The client's one command for the talk is then spent,
and a `command.text` that still arrives during a host-mic talk is ignored and logged, with no
`announce`.

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

**Effect on the talk: every command ends it** (2026-09-30; before, only `play`, `resume` and
`end` did, so a spoken `next` chose a track that did not play until the talk was ended by hand).
A talk whose first phrase is a command was opened to give that command. The host closes the talk
(`talk.close{by: <the side that spoke>, reason:"trigger"}`) as soon as the command parses; the
music starts after the headset is back in media mode, like any resume after talk, and an
`announce`, if the command has one (below), is sent and spoken after that switch. It closes
the talk even if the command then fails (no search result, nothing to resume, nothing queued):
the error is announced after the switch and music that was playing before the talk resumes.
- `play …`: the new track starts instead of the one the talk paused.
- `resume`: the paused track resumes.
- `next` / `previous`: choose the track before the close; the close resumes it if music was
  playing before the talk, and music that was paused before the talk stays paused on the new track.
- `pause`: cancels the resume after the talk; the music stays paused.
- `nowplaying`, `shuffle`, `queue …`: music that was playing resumes; the reply is spoken after
  the switch.
- `end`: exactly like a press (music that was playing resumes).
- Volume (below) is local: the phone that heard it changes its **media** volume and ends the
  talk itself, the client with an ordinary `talk.close{by:"client", reason:"trigger"}`.
- An unparsed phrase is not a command and ends nothing (in a solo talk it gets "Didn't catch
  that" and the talk stays open; a parsed command ends a solo talk like any other). A volume
  utterance that still arrives in `command.text` counts as unparsed. The ignored volume phrase on the passenger's channel of a
  host-mic talk (above) also leaves the talk open.

**Spoken replies: only when there is nothing else to hear** (2026-09-30). A command that
succeeds and whose result is the music itself has **no `announce`**: `play …`, `resume`, `next`,
`previous` and `pause` just do it (the closing earcon and the music are the acknowledgement;
"Playing <title> by <artist>" is no longer sent or spoken). `end` has none either. An `announce`
is sent for failures (earcon `error`: "Couldn't find <query>", "No coverage", "Search failed",
"Nothing to resume", "End of queue", "Nothing to play", "Nothing to shuffle", "Nothing playing",
"Didn't catch that"; the exact wording is the host's) and for the two commands that have
nothing else to show for themselves: `nowplaying` (the track), `shuffle` ("Shuffled") and
`queue …` (what was added, see "Queueing by voice"). The
same holds for a command outside a talk (typed on the host's Ride screen).

Volume is local: `volume up/down` changes the volume of the phone it was spoken on (or whose
button was pressed) and is never sent. The client therefore runs the same parser on its own
utterances first, handles a volume result itself (earcon `ok`, no `announce`; in a talk it then closes the talk), and sends
`command.text` for everything else. A host that still receives a volume utterance in
`command.text` answers "Didn't catch that".

```
play [song|album|artist|playlist] <query>
pause | stop | resume | continue
next | skip | previous | back
volume up | louder | volume down | quieter
over | end talk | hang up                       (action "end": close the talk)
what's playing | whats playing | what is playing | what song is this   (action "nowplaying")
shuffle                                         (action "shuffle")
queue [next|instead] [<count>] [song|album|artist|playlist] <query>   (action "queue")
queue [next|instead] [<count>] similar
```

`nowplaying` (2026-09-30) announces the current track, `"<title> by <artist>"` (just the title when
the artist is empty), or "Nothing playing"; `shuffle` shuffles the upcoming queue (the current
track stays) and announces "Shuffled", or "Nothing to shuffle" with fewer than two upcoming
tracks. "Nothing playing" and "Nothing to shuffle" carry earcon `error`, the others `ok`. Both
end the talk like every command and are spoken after the switch.

Normalisation before matching, per Unicode code point (not grapheme cluster): lowercase;
map `’` (U+2019) to `'`; replace every code point that is not in a Unicode letter (L*),
mark (M*) or number (N*) category, not `'` and not whitespace with a space; collapse
whitespace; trim. Then drop leading
"hey"/"please" words and one trailing "please". `play [the] <kind> <query>` with kind in
song/album/artist/playlist; without a kind, `play <query>` searches songs; an empty query is
not a command. The other phrases must match exactly ("next song" is not `next`). Anything
else → `announce{text:"Didn't catch that", earcon:"error"}`. Vectors: `fixtures/commands.json`.

### Queueing by voice (2026-09-30)

`queue …` adds music to the queue instead of replacing it: what `music.enqueue` does by touch
("Browsing"), by voice. The plain grammar is terse; with interpretation on, the natural forms
("add the rest of this album to the queue", "play Porcelain next", "for the next three songs
play more of this artist", "choose similar music for the rest of the queue") are turned into it.

- **Parsing.** After `queue`: an optional place, `next` (right after the current track) or
  `instead` (the upcoming queue is cleared first, then the tracks are appended); without one the
  tracks go to the end. Then an optional **count**: a word of one or two ASCII digits with a
  value 1–`QUEUE_MAX_COUNT` = 50, and only when the word after it is a kind or `similar` (so
  `queue 3 doors down` is a song search). Then either the single word `similar`, or a kind
  (default `song`) and a query; an empty query is not a command. Vectors: `fixtures/commands.json`
  (`expect` has `where`: `"end"`/`"next"`/`"instead"`, `kind` (a play kind or `"similar"`),
  `query` unless similar, and `count` as a decimal string when given).
- **The source.** `song`: the top hit. `artist`: the artist's top songs. `album`, `playlist`: the
  top collection's tracks, in order (an album query that is an artist's name picks one of that
  artist's albums, as for `play album`; and when that album does not hold the current track
  but the query names the current track's artist, the host uses the artist's album that does
  hold it, if it finds one: the interpreter names albums from its own knowledge and can be
  wrong). `similar`: music like the **current track** (YouTube's
  radio for it); with nothing loaded it fails with "Nothing playing".
- **What is added.** From the source, in order: if it is an album that contains the current
  track (the same id, or the same normalised title), only the tracks **after** it ("the rest of
  this album"); then the current track and tracks already upcoming are left out (for `instead`
  only the current track, since the rest is replaced); then at most `count` tracks, or all of
  them (`similar`: `QUEUE_SIMILAR` = 20) without one.
- **Effect.** Like every command it ends the talk, and music that was playing resumes. The reply
  is spoken after the switch, earcon `ok`: "Added <title> by <artist>" for one track, "Added <n>
  songs" for more; with `next`, "Next: <title> by <artist>" / "Next: <n> songs". Failures
  (earcon `error`): the search ones of `play`, "Nothing playing" (`similar`), and "Nothing to
  add" when nothing is left to add. With **no track loaded** the tracks simply start playing, as
  for `music.enqueue`, and there is no reply.
- With interpretation on, a `queue …` the grammar parses is also given to the interpreter, like
  `play …`, and executed as spoken if that does not settle it.

### Interpretation (2026-09-30)

The grammar above is the fast path: a phrase it parses is a command at once, offline, exactly as
before — **except `play …`**, which an interpreting host also gives to the interpreter (so
"play something by movie" becomes Moby instead of a search for a song of that name, and a vague
"play an album by Moby" can be asked about). A `play …` the interpreter does not settle
(conversation, a timeout, any failure, a question that may not be asked and has no fallback) is
executed as spoken. A host with **smart commands** on (a setting; it needs a Gemini API key in the build)
also understands first phrases the grammar does not parse ("put on something by Moby", "I don't
like this one"), by asking a language model what the speaker wanted. Only the host does this;
the client has no key and never calls a model.

**The host says so in `hello`:** `interpret:true` (sent again when the setting changes; absent
or `false` = the rules above, unchanged).

**The gate with interpretation on.** The opener's first phrase (same definition, same window)
is always a **candidate**: if it parses it is a command as above; if it does not, its normalised
text is still passed on, to be interpreted. Either way the first phrase is spent. Vectors: the
cases with `"interpret": true` in `fixtures/first_phrase.json` (`expect` is then the candidate
text, parsed or not).
- The client, connected to a host whose `hello` has `interpret:true`, sends its first phrase as
  `command.text` whether or not its own grammar parses it. A phrase its grammar parses as volume
  stays local, as before. So the first phrase of a conversation the passenger opens now goes to
  the host; later phrases never do.
- The host's own rider's first phrase, and the passenger's in a host-mic talk, are handled the
  same way on the host.
- In a solo talk, and for a command typed on the host, an unparsed phrase is interpreted too.

**What the host does with an unparsed candidate.** It asks the interpreter, giving it the
phrase and a **context window**, and waits at most **`INTERPRET_TIMEOUT_MS` = 6000 ms**. The
talk stays open and nothing is said while it waits. The answer is a list of up to
`MAX_ACTIONS` = 4 typed **voice actions** (next section), run in order by the host; it is
conversation when the list is empty or nothing in it is usable.

Conversation, a timeout, an HTTP or network error, a rate limit and an unreadable answer are all
the same: nothing happens, the talk stays open, no `announce`, no error earcon (in a solo talk and
for a typed command: "Didn't catch that", as for any unparsed phrase). Also:
- The answer is acted on only if the talk it was spoken in is still open when it arrives;
  otherwise it is dropped and logged.
- An interpreted volume action from the passenger (their `command.text`, or their channel in a
  host-mic talk) is ignored with no `announce`, like a spoken one on that channel; the rest of the
  list runs.
- With interpretation on, a `command.text` that neither parses nor interprets gets no "Didn't
  catch that" (it is the passenger's conversation). The host-enforced rule is unchanged: one
  `command.text` per talk, only from the opener.
- When the model's rate limit is hit, the host stops asking for a while (it keeps the grammar);
  it does not queue phrases.

### Voice actions (2026-10-01)

One currency for the grammar, the interpreter and the undo: whatever was said becomes a list of
voice actions, which one executor runs. The grammar's commands map one to one (so the grammar
stays the instant, offline path), and the interpreter can say more than the grammar can.

**The context window** (the interpreter's input, one JSON object; the phrase travels in it as
data, never as loose prompt text):

```json
{"phrase": "drop the next two and play yellow after this", "lang": "en-US",
 "playing": {"title": "Porcelain", "artist": "Moby", "album": "Play", "atS": 74, "lengthS": 241},
 "repeat": "off",
 "upNext": ["1. Natural Blues – Moby", "2. Why Does My Heart Feel So Bad? – Moby", "3. Yellow – Coldplay"],
 "queueLength": 14,
 "played": ["-1. Teardrop – Massive Attack", "-2. Angel – Massive Attack"],
 "lastVoice": "removed Clocks – Coldplay (3 min ago)"}
```

- `playing`: the current track, or `null`; `album` only when known; `atS` and `lengthS` are the
  position and length in whole seconds (`lengthS` absent when unknown).
- `repeat`: `"off"`, `"track"` or `"queue"` (see `state.music.repeat`).
- `upNext`: the first `INTERPRET_UP_NEXT` = 25 upcoming tracks, numbered from 1
  (`"<n>. <title> – <artist>"`, just the title when the artist is empty); `queueLength` is the
  number of upcoming tracks, which may be more.
- `played`: up to `INTERPRET_PLAYED` = 5 tracks played before the current one, newest first,
  numbered from −1 (−1 = the track before this one).
- `lastVoice`: a short summary of what the last voice action list changed and how long ago, or
  `null` when there was none in the last `UNDO_MS` (the wording is the host's).
- `asked`: present only for the reply to a question ("The clarifying question").

The host keeps the **snapshot** it sent: which track id each `upNext` and `played` number stood
for. Positions in the answer are resolved against it and executed on those ids, so a queue that
changed while the model was thinking cannot make an edit hit the wrong song (the rule of
`music.edit`'s `id`). A track that is no longer upcoming is skipped and logged.

**The answer** is `{"actions": [ … ]}` (held to `interpret_schema.json`, which uses only `anyOf`,
`enum`, `required` and `maxItems`). Each action is an object with a `type`:

| `type` | fields | meaning |
|--------|--------|---------|
| `play` | `kind`: `song`\|`album`\|`artist`\|`playlist`\|`similar`, `query` | replace the queue, as the grammar's `play`; `similar` = music like the current track, no query |
| `add` | `kind`, `query` as for `play`; `where`: `next`\|`end`\|`instead`; `count`: 0–50 | the grammar's `queue` ("Queueing by voice"); `count` 0 = none said |
| `remove` | `at`: upcoming positions, or `artist` | drop those tracks; `artist` drops every upcoming track by that artist, also past the window |
| `move` | `at`: upcoming positions, `to`: a position | take them out and put them back, in their order, so the first is at `to` (1 = next; past the end = the end) |
| `clear` | | drop every upcoming track |
| `jump` | `at`: an upcoming position, or −1…−5 for `played` | play that track now. Upcoming: as `music.edit jump`. Played: the track is put back right after the current one and played; the queue stays |
| `pause` `resume` `next` `previous` `shuffle` `end` | | as the grammar's words |
| `restart` | | the current track from its start |
| `seek` | `by`: seconds (±), or `to`: seconds | move in the current track, clamped to it |
| `repeat` | `mode`: `off`\|`track`\|`queue` | `state.music.repeat` |
| `volumeUp` `volumeDown` | | local, as the grammar's |
| `tell` | `about`: `track`\|`album`\|`next`\|`remaining`\|`previous` | the host says that fact, in its own words from its own data |
| `undo` | | "Voice undo", below |
| `ask` | `question`; fallback in `kind`/`query` | "The clarifying question" |
| `none` | | conversation |

**Validation** (pure; vectors in `fixtures/interpret.json`, with the window's sizes):
- An action with an unknown `type`, a missing or mistyped required field, or a value outside its
  set is dropped; so is the rest of an answer that is not `{"actions": [ … ]}`.
- `play`/`add`/the `ask` fallback: `query` is normalised as in "Commands"; empty after that (and
  `kind` not `similar`) drops the action; an unknown `kind` is `song`. `add.where` other than
  `next`/`instead` is `end`; `count` counts only as an integer 1–50.
- `remove`: a non-empty `at` wins over `artist`; `artist` must be non-empty after normalisation.
  `remove` and `move`: every position must be 1…`upNext` size (duplicates count once), else the
  whole action is dropped. `move.to` must be an integer ≥ 1. `jump.at` must be 1…`upNext` size or
  −1…−`played` size.
- `seek`: a non-zero integer `by` wins; else an integer `to` ≥ 0; else dropped.
- `repeat.mode` and `tell.about` must be in their sets.
- `none` next to other actions is ignored. More than `MAX_ACTIONS` usable actions: the first four.
- `ask` counts only alone: next to other actions it is dropped and they run. Its question is
  whitespace-collapsed and trimmed and must be 1–`ASK_MAX_CHARS` = 80 code points, else the `ask`
  becomes its fallback (or nothing).

**The grammar's commands as actions:** `play <kind> <query>` → `play`; `queue …` → `add` (`similar`
→ kind `similar`); `pause`, `resume`, `next`, `previous`, `shuffle`, `over` (→ `end`) and volume
→ the same; `what's playing` → `tell track`. An unparsed phrase is the empty list.

**Running a list.**
- Searches in the list (`play`, `add`, `play similar`) start at once and in parallel. The actions
  then apply in order, each on the queue as the previous one left it (positions always refer to
  the snapshot). Actions before the first search apply at once, before the talk closes, exactly
  as the grammar's commands always have; the rest wait for their search and for the headset to be
  back in media mode.
- **Effect on the talk**: a list with any action ends the talk, as a command does (`ask` keeps it
  open; a list of only `end` has no reply). The strongest effect wins: `play` and `jump` replace
  the music the talk paused, `pause` cancels its resume; otherwise music that was playing resumes.
- A failed action is named in the reply; the rest of the list still runs.
- **One spoken line per list**, after the switch to media, made of the parts that need one, in
  order: `remove` ("Removed <title> by <artist>", "Removed <n> songs"), `clear` ("Cleared the
  queue"), `move` ("Moved <title> to next" / "to the end" / "to <n>", "Moved <n> songs …"), `tell`,
  `undo`, `add` (as "Queueing by voice"), `nowplaying`'s and `shuffle`'s replies, and every
  failure ("Couldn't find …", "Nothing to remove", "Nothing to undo", …; the wording is the
  host's). `next`, `previous`, `jump`, `seek`, `restart`, `repeat`, `pause`, `resume` and a
  successful `play` have none: the earcon and the music say it. The earcon is `ok`, or `error`
  when every part is a failure.
- `tell`: `track` "<title> by <artist>"; `album` "From <album>" (or "Album unknown"); `next`
  "Next: <title> by <artist>" (or "Nothing after this"); `remaining` "<n> songs left, about <m>
  minutes"; `previous` "Before this: <title> by <artist>" (or "Nothing before this").

**Voice undo.** Before a list that changes the upcoming queue or the repeat mode (`add`,
`remove`, `move`, `clear`, `shuffle`, `repeat`), the host keeps the upcoming track ids and the
repeat mode as they were. `undo` within **`UNDO_MS` = 600000 ms** (10 minutes) puts them back (the
current track stays; it is left out of the restored list if it is in it) and says "Put back <n>
songs" when tracks came back, else "Undone"; otherwise "Nothing to undo". One level: an undo
cannot be undone, and a `play` or `jump` (a new queue or a new current track) forgets it. Touch
keeps its own remove-undo; a touch repeat or move (either phone) neither saves nor forgets it.

Vectors: `fixtures/interpret.json` (the model's answer and the window's sizes → actions,
question or conversation; this is the mapping only, not what a model says).

### The clarifying question (2026-09-30)

The one deliberate exception to "every command ends the talk" and to "no spoken reply": when a
first phrase is a music request that lacks the one detail needed to act on it ("play an album by
Moby", "put some music on"), the interpreter may answer `{"action":"ask", "question": …}`,
optionally with a **fallback** in `kind`/`query` (mapped like a `play`; absent, empty or kind `similar` = none).
The host then asks the question aloud, the talk stays open, and the **next phrase of the same
side** is the reply. The model is told to ask rarely: a request with a reasonable reading
("something by Moby") is simply played.

- **The question** is the answer's `question` with whitespace collapsed and trimmed; it must be
  1–`ASK_MAX_CHARS` = 80 code points. An `ask` without a usable question is its fallback as a
  command, or conversation when it has none.
- **Asking.** The host sends `announce{text: <question>, ask: true}` (no earcon) and speaks it
  itself **in the talk** (on the call route; in a host-mic talk on the media route, like every
  reply there). At most **one question per talk**: a later `ask` in the same talk (a solo talk
  has many phrases) is its fallback, or conversation. An `ask` for a command typed outside a
  talk is likewise its fallback, or "Didn't catch that".
- **The reply window.** The side that was asked gets one more phrase: the first phrase that is
  not empty after normalisation and whose result arrives within **`ANSWER_MS` = 10000 ms** of
  the question (the host: of sending the `announce`; the client: of receiving it). The gate that
  had spent its first phrase is open again for exactly that phrase; it is passed on as it is,
  parsed or not. A phone that had stopped recognising starts again. Vectors: the cases with
  `askAtMs` in `fixtures/first_phrase.json` (the question arrives at that time, before any
  phrase with the same or a later `atMs`).
  - A client that receives `announce{ask:true}` while a talk it opened is open (and is not a
    host-mic talk) sends that phrase as a second `command.text`, **without** running its own
    parser on it (a volume phrase is sent too). Otherwise `ask` changes nothing for it: the
    text is spoken like any `announce`.
  - The host accepts that second `command.text` only while its question to the client is
    unanswered; the rule "one `command.text` per talk" holds in every other case.
  - In a solo talk every phrase is a command anyway; the first one after the question is the
    reply.
- **The reply is always interpreted**, never parsed by the grammar, with the first request and
  the question as context (`asked: {phrase, question}` in the interpreter's input). The model is
  told to settle it without asking again, and that a reply which leaves the choice open ("any",
  "I don't care, just play any album", "you pick") means *choose*: after a question about an
  album it answers `play album <artist>`, which the host's catalog turns into one of that
  artist's albums (when the query is an artist's name and not an album title, a random one of
  the artist's top `ANY_ALBUM_TOP` = 5 album results); otherwise `play artist <artist>` or a
  playlist of hits.
  The host then does one of:

  | the reply's answer | the host |
  |--------------------|----------|
  | an action list | runs it (it ends the talk; an interpreted volume from the passenger is ignored as above) |
  | conversation (`none`: "never mind", or talk to the other rider) | nothing; the talk stays open (solo: "Didn't catch that") |
  | `ask` again | that answer's fallback, else the first answer's fallback, else nothing |
  | a timeout or any other failure | the first answer's fallback, else nothing |

- **No reply.** If no reply has arrived `ANSWER_MS` + `ANSWER_GRACE_MS` (2000 ms) after the
  question, the host executes the first answer's fallback (which ends the talk); with no
  fallback nothing happens and the talk stays open. A reply that arrives after that is ignored.
- A question whose talk closed meanwhile is dropped with everything that belongs to it.

## Test vectors

| File | Covers |
|------|--------|
| `fixtures/control/messages.json` | every message type round-trips |
| `fixtures/control/framing.json`  | length prefix, UTF-8, oversize rejection, unknown types/fields |
| `fixtures/clock.json`            | offset estimator incl. negative RTT, ties, window eviction, the step reset |
| `fixtures/jitter.json`           | jitter buffer: spurts, underruns and target changes, FEC/conceal, shedding, re-anchor, wrap-around |
| `fixtures/voice/header.json`     | UDP header encode/decode and rejection |
| `fixtures/commands.json`         | command parser, including `queue …` |
| `fixtures/first_phrase.json`     | the first-phrase gate: command text or conversation per phrase, with and without interpretation, and the reply to a question |
| `fixtures/interpret.json`        | an interpreter answer → voice actions, question or conversation |
| `fixtures/lyrics.json`           | LRC → lines and word times (stamps, tags, repeats, compression, code points), and the timeline |
