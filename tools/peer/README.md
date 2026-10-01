# motoparty-peer

A desktop peer that speaks the Motoparty wire protocol ([`PROTOCOL.md`](../../PROTOCOL.md)).
It can stand in for either phone:

- `client` plays the iPhone against a host (the Pixel, or `peer host`).
- `host` is a minimal fake Pixel, for testing the iOS client without the Pixel.

It is also the reference implementation of the protocol. The codec, framing, clock estimator,
voice header and command parser all pass the shared vectors in [`fixtures/`](../../fixtures).
Where PROTOCOL.md leaves a choice open, this README says what the peer does (see
[Protocol notes](#protocol-notes)).

## Setup

```sh
cd tools/peer
uv sync            # Python 3.14 venv with sounddevice + zeroconf (+ pytest for dev)
uv run pytest      # unit tests + host/client integration tests on localhost
```

The tests never announce anything on a real network. The Bonjour tests register on
127.0.0.1 only (zeroconf `interfaces=["127.0.0.1"]`) with a 10 s TTL, and the integration
tests run the host with `--no-mdns`. A test service on the riders' Wi-Fi (or the USB tether)
would sit in the phones' mDNS caches and the apps would chase it. The one test that uses the
real LAN is opt-in, and uses the same 10 s TTL:

```sh
MOTOPARTY_LAN_TESTS=1 .venv/bin/python -m pytest -q tests/test_discovery.py
```

System libraries (libopus is loaded with ctypes, PortAudio through `sounddevice`):

| Needed for | Arch package | Notes |
|---|---|---|
| voice (always) | `opus` | `libopus.so.0`, via ctypes |
| mic and speakers | `portaudio` | only loaded without `--no-audio` |
| track metadata, the music part of the tests | `ffmpeg` | optional: without it, duration comes from the MP4 `mvhd` box and the title is the file name |
| `client --play` | `mpv` or `ffmpeg` (ffplay) | optional |

## Client: `uv run motoparty-peer client [options]`

With no `--host`, the client discovers the host as PROTOCOL.md "Discovery" says. It browses
`_motoparty._tcp` and treats every result as a candidate. Each one is probed in parallel as it
appears: a TCP connect (3 s), then 1 s to receive the host's `hello`. A probe sends nothing,
so it never replaces the host's current client. 3 s in, it also sweeps port 47800 on every
address of its own /24 alongside (64 in parallel, 400 ms connect, 1 s for the hello). The
first valid host `hello` wins. If the host it linked to last also answers within 250 ms, that
one wins instead. Only then does it close the probe and do the real handshake (client `hello`)
with the winner. A candidate that fails (including a failed handshake) is not probed again for
10 s. Stale Bonjour candidates are retried after that, and the sweep repeats, until a host
answers. `--host IP` skips discovery. It reconnects on its own after link loss (6 s with
nothing received) or a `bye`, and the backoff table and the last host's name carry over.

| Option | |
|---|---|
| `--host IP`, `--port N` | connect directly (default port 47800) |
| `--no-mdns` | skip the Bonjour browse and start the /24 sweep at once (e.g. to test the sweep on the Pixel's hotspot); not allowed with `--host` |
| `--tone` | send 440 Hz beeps instead of the mic (400 ms on / 100 ms off; see notes) |
| `--no-audio` | no sound devices at all. Received voice still runs through the jitter buffer and decoder on a 20 ms clock, and the stats get logged |
| `--play` | play music with mpv/ffplay at the scheduled moment (otherwise the schedule is only logged) |
| `--name`, `--lang` | hello name (default hostname), BCP-47 tag for `say`/`hear` (default `en-US`) |
| `--mic-unavailable` | start with the mic marked dead: answer the host's `talk.open` with `talk.close{by:"client",reason:"unavailable"}` (stdin `unavailable` toggles it) |
| `--cache-dir` | where tracks are downloaded (default `~/.cache/motoparty-peer`) |
| `--input-device`, `--output-device` | PortAudio device index or name |
| `-v` | also print raw ping/pong frames |

Stdin commands:

| Command | Sends |
|---|---|
| `talk` | `talk.open{by:"client"}`, or `talk.close{by:"client",reason:"trigger"}` if talk is open |
| `hear <phrase>` | a phrase the phone's ASR recognised **in a talk** (PROTOCOL.md "Commands", The first phrase decides). Only in a talk this client opened (`talk.open{by:"client"}`), only its first phrase that is not empty after normalisation, and only if it arrives within 8 s of the talk opening (the peer's stand-in for the live earcon) and parses: then it sends `command.text` with the normalised text (`hey`/`please` kept). Anything else logs `conversation (<why>), not sent`, with no "Didn't catch that"; a volume command is handled locally like `vol+` and, being a command, ends the talk: the client sends `talk.close{by:"client",reason:"trigger"}`. Outside a talk it sends nothing (`hear: no talk open`) |
| `say <text>` | `command.text{text, lang}` as is, no first-phrase gate (the bench's `hotspot_test.sh` uses it) — but the parser runs locally first, and a volume phrase (`louder`, `volume down`, …) is handled here and **not** sent. The host only acts on it as the first `command.text` of a talk the client opened |
| `pause` `resume` `next` `previous` | `music.control{action}` |
| `repeat off\|track\|queue` | `music.control{action:"repeat", mode}`, the repeat button (PROTOCOL.md "Repeat by touch") |
| `vol+` `vol-` | nothing: volume is local (the peer has no real volume, so it just logs it) |
| `unavailable` | toggles "my mic is dead": while on, the host's `talk.open` is answered with `talk.close{by:"client",reason:"unavailable"}` and talk never opens locally; `talk` will not ask for talk either |
| `search <kind> <query>` | `music.search{id, kind, query}` (`songs`/`albums`/`playlists`, empty query allowed); the newest request's results print numbered |
| `browse <n>` | `music.browse` for album/playlist result `n` |
| `download [stop]` | after `browse <n>`: `music.download{op:"start", ref, ids}` with the collection's song refs, or `{op:"stop", ref}`. The host's `music.downloads` prints each collection's progress and which listed songs are cached; a changed `state.busy` prints as `busy: …` |
| `enqueue now\|next\|end <n>\|all` | `music.enqueue` with song result `n` (or all of them); `album` is set after a `browse` of an album |
| `edit jump\|remove <i>` / `edit move <i> <to>` / `edit clear` | `music.edit`; `i` is 0-based into the last `state.queue`, and its `id` is filled in from there; for `move`, `to` is the track's new 0-based index (past the end = the end) |
| `stats` | prints jitter-buffer/voice stats and the clock estimate |
| `raw <json>` | sends any JSON object **unvalidated**, to test how the other side handles bad input — the only way to send a message the codec now rejects, e.g. `raw {"t":"music.control","action":"volumeUp"}` |
| `quit` | `bye{reason:"user"}`, then exits |

A `state` whose `music.repeat` differs from the last one logs `repeat: track|queue|off` (absent
= off).

A host `talk.close{by:"host",reason:"unavailable"}` in answer to our `talk.open` is logged as
`TALK REFUSED by host: microphone unavailable`; talk never opened, so `state.talk` stays false.

A host `talk.open{by, mic:"host"}` opens a **host-mic talk** (PROTOCOL.md "Talk flow", Host-mic
talk), logged as `talk mode: host-mic (receive only)`. The client opens no microphone and sends
no audio, even with `--tone`: keepalives only (`tx: audio=0`). It still plays the voice it
receives. A client that joins mid-talk sees only `state`; a `state{talk:true, mic:"host"}`
opens the talk the same receive-only way. `hear <phrase>` sends no `command.text` in such a talk (`skipped: host-mic talk`),
because the host recognises commands itself. A mic marked unavailable (`--mic-unavailable` /
`unavailable`) is no reason to refuse it, so the client accepts. A `talk.open` without `mic`
behaves as before. (`say` stays ungated, so it can test how a host treats a stray
`command.text`.)

Output: `<<` lines are received messages and `>>` lines are sent ones, as compact JSON.
Pongs print as `pong id=… rtt=…ms offset=…ms | estimate offset=…ms (rtt …ms)`. While talk
is open, a `voice rx:` line every 2 s shows received / played / FEC / PLC / underruns / shed /
re-anchors, the jitter-buffer depth (and its maximum) and target, plus how many audio packets, DTX skips and keepalives
were sent. On `music.load` the client downloads the file over HTTP and checks it (ftyp box,
plus an ffprobe audio stream when ffprobe is available), then replies `music.ready` or
`music.error`. On `music.play` it logs the host time converted to local time and how far
ahead that is.

Voice: the peer sends a keepalive after 1 s without sending anything. While talk is open
it sends Opus frames (VOIP, 24 kbps, FEC 10 %, DTX, 16 kHz mono, 20 ms) from the mic or
the tone. Received audio is played through the adaptive jitter buffer.

## Host: `uv run motoparty-peer host [--track FILE.m4a|DIR ...]`

Advertises `<name>._motoparty._tcp` with TXT `proto=1 voice=47801 http=47802` and listens on
47800/tcp, 47801/udp and 47802/tcp (`--port/--voice-port/--http-port`, where 0 means any free
port; `--bind`; `--no-mdns`). The hello carries the real ports. With `--bind` set to one
address, the Bonjour announcement goes out on that interface only, so `--bind 127.0.0.1`
stays off the Wi-Fi. A probe (a connection that never sends `hello`) gets the host's `hello`
and `state`, then the host logs the close; nothing else changes.

- One client at a time. A new `hello` replaces the old connection, which gets `bye{reason:"replaced"}`.
  If a talk is open and the new client has the same `name` as the replaced one, the talk stays
  open (log: `talk kept: '<name>' reconnected`; the new connection already got
  `state{talk:true}`); a different name closes it with `talk.close{by:"host",reason:"link"}`.
  A connection the host has already seen close is link loss as before, whoever comes next.
- Answers `pong` with `t1` taken at frame receipt and `t2` at send. The clock is `CLOCK_MONOTONIC` in ms.
- Talk authority. It opens or closes on client requests and on the stdin `talk` command, and
  broadcasts `talk.*` + `state`. After `mic off` it answers a client's `talk.open` with
  `talk.close{by:"host",reason:"unavailable"}` instead — talk never opens, `state.talk` stays
  false and nothing else is broadcast. Talk ends on a trigger (either side), or with `"link"`
  on link loss; never on silence.
  With `--host-mic` (or stdin `hostmic on`) it decides talks as **host-mic talks**:
  `talk.open{by, mic:"host"}`, logged `TALK OPEN (by <side>, host-mic)`, and every `state`
  sent while that talk is open carries `mic:"host"` (so a client that joins mid-talk knows). The mode is fixed at the
  open, so toggling `hostmic` changes only the next talk. In such a talk it drops client audio
  packets without echoing them, counts them (`dropped=` in the voice stats line, and
  `host-mic talk: dropped N client audio packets` at the close), and ignores any `command.text`.
  Its `hear` then stands for the ASR on the opener's channel. In a client-opened talk that is
  the passenger's first phrase, acted on as the client's command (e.g. `over` closes with
  `by:"client"`); a volume phrase there is ignored with no announce. A `mic` on a client's
  request is logged and ignored.
  Music that was playing is frozen during talk and resumed with `music.play` at
  `now + 1500 ms`.
- Voice: replies to the source of the most recent valid packet from the client's IP. While
  talk is open it **echoes** every audio packet back (own seq, same ts and payload), so one
  phone can hear itself.
- Tracks: `--track` is repeatable and takes files or directories of `.m4a` (e.g. `--track tracks`).
  `load` plays the first one.
- Browsing: `music.search` matches title/artist/album (case-insensitive substring, empty query
  = all) over those tracks. `albums` are the distinct album tags among the matching songs, with
  `ref` = `al` + 14 chars of the album name's SHA-1; `playlists` is always empty.
  `music.browse` answers an album's tracks or `error` ("Invalid ref" / "Not found").
  `music.enqueue`/`music.edit` (including `move`) change the queue as the spec says and
  broadcast `state`;
  `next`/`previous` walk it. There is no auto-advance at the end of a track.
- HTTP: `GET`/`HEAD /track/<id>.m4a` for each `--track` file, `Content-Type: audio/mp4`, single
  `Range` → 206, unsatisfiable → 416, anything else → 404. `<id>` is 11 URL-safe characters
  derived from the file's SHA-1.
- Commands (PROTOCOL.md "Commands", The first phrase decides). The host acts on a client's
  `command.text` only while a talk is open, the client opened it, and it is the first
  `command.text` of that talk; any other is ignored and logged (`command.text ignored: <why>`)
  with no `announce`. The stdin `hear <phrase>` is the host's own ASR: in a talk the host opened
  with a client connected, only its first non-empty phrase within 8 s of the talk opening is a
  command, and only if it parses (else `conversation (<why>), not acted on`); in a **solo** talk
  (host `talk` with no client connected when it opened) every non-empty phrase is a command, with
  no window. A command goes through the grammar parser.
  Effect on the talk (PROTOCOL.md "Effect on the talk: every command ends it"): every command
  that parses closes the talk it was spoken in, even if it then fails: `talk.close{by:<the side
  that spoke>, reason:"trigger"}` + `state`, then its `announce` if it has one (sent to the
  client, or logged as `would send` solo). The host acts on the paused music first and then
  closes, so the close starts the right thing:
  - `play …` loads/plays the track in place of the one the talk paused; its `music.play` is
    never sooner than 1500 ms after the close. (The fake host has no search: it plays its
    first `--track`, or fails with "Couldn't find <query>" when it has none.)
  - `resume`, `end`, `nowplaying`, `shuffle`: the music that was playing resumes as after any
    talk (`music.play` at `now + 1500 ms`); `resume` also resumes music that was paused before
    the talk.
  - `pause` cancels the resume after the talk. It also covers a `next`/`previous` still loading.
  - `next`/`previous` choose the track (`music.load`), which starts after the close if music
    was playing before the talk and stays paused if it was not.
  - The host's own volume phrase is handled locally (logged) and the host closes the talk
    (`by:"host"`), with no `announce`.
  - Unmatched text ends nothing and gets `{"text":"Didn't catch that","earcon":"error"}` (a
    client's first `command.text`, or a solo phrase), and so does a volume utterance in
    `command.text`: volume is local and should never arrive here.
  Spoken replies (PROTOCOL.md "Spoken replies: only when there is nothing else to hear"): a
  successful `play …`, `resume`, `next`, `previous`, `pause` or `end` has **no** `announce`, in
  a talk or outside one. An `announce` is sent for `nowplaying` ("<title> by <artist>", the
  title alone with an empty artist, for the current track, loading or paused included),
  `shuffle` (shuffles the upcoming queue, the current track stays, sends `state`, then
  "Shuffled"), and failures, all with the error earcon: "Couldn't find <query>", "Nothing to
  resume", "Nothing playing" (`pause` with nothing that would have resumed, or `nowplaying`
  with no current track), "End of queue" (`next`), "Nothing before this" (`previous`),
  "Nothing to shuffle" (fewer than 2 upcoming), "Didn't catch that".
- Voice actions (PROTOCOL.md "Commands", Voice actions). Every command runs as a voice action
  list: the grammar's through `to_actions`, the stub interpreter's (`--interpret-table`, answers
  `{"actions":[…]}`) through `interpretation_actions`, against a snapshot of the window (25
  upcoming, 5 played) taken when the phrase arrived; a position whose track is no longer
  upcoming is skipped. The list closes the talk once, then sends **one** `announce` joining the
  parts with ". " (earcon `error` only when every part failed). Beyond the grammar: `remove`
  (positions or every upcoming track whose normalised artist matches; "Removed …", "Nothing to
  remove"), `move` ("Moved … to next|the end|<n>"), `clear` ("Cleared the queue", "Nothing to
  clear"), `jump` (upcoming as `music.edit jump`; −n plays that played track now, the queue
  stays), `restart`/`seek` (clamped; a paused or talk-frozen track just moves its position),
  `repeat` (`state.music.repeat`; `track` replays the track at its end with no `music.next`,
  `queue` refills an empty queue from the history plus the last track), `tell` and one-level
  `undo` (10 min; "Put back <n> songs", "Undone", "Nothing to undo"). An interpreted volume
  from the client is dropped from the list; from the host it is local.
- A `music.control` with a volume action is not a valid message any more; it is dropped as
  malformed (logged as `dropped invalid frame`) and the connection stays up.
- A client `music.control{action:"repeat", mode}` (2026-10-01) sets the repeat mode like the
  stdin `repeat` (a `state` only when it changes) and leaves the voice undo alone; a `repeat`
  without a `mode` is dropped as malformed.
- Downloads (2026-10-01, PROTOCOL.md "Browsing" step 6): after every client `hello` the host
  sends `music.downloads{cached, downloads}`. A client `music.download{op:"start", ref, ids}`
  "downloads" the ids one at a time (300 ms each, across collections): a library track becomes
  cached, any other valid id fails; invalid ids are skipped, a repeated start of a running
  collection is ignored. `music.download{op:"stop"}` cancels it and drops its progress; cached
  tracks stay. Every step sends `music.downloads`. A `start` without `ids` is malformed.
- `state.busy` (2026-10-01): the fake host's searches are instant, so the "Searching …" line is
  set by hand with the stdin `busy <text>` and cleared with `busy off` (a `state` each time).

Stdin commands: `load` (sends `music.load`, then `music.play` 300 ms ahead once
`music.ready` arrives, or after 8 s / on `music.error`), `play`, `pause`, `stop`, `talk`,
`mic on|off` (bare `mic` toggles; `off` refuses the client's `talk.open` as `unavailable`),
`hostmic on|off` (bare `hostmic` toggles; `on` makes the next talks host-mic talks),
`hear <phrase>` (the host's own ASR, see Commands above), `repeat off|track|queue`
(`state.music.repeat`, as by touch), `busy <text>|off` (`state.busy`), `downloads` (sends
`music.downloads` again), `announce <text>`, `state`, `stats`, `raw <json>`, `quit`.

## Bench recipes

### Against the Pixel (Android host)

The laptop and the Pixel must share a network: the laptop joins the Pixel's hotspot, or both
sit on the home LAN, where the Pixel is `192.168.1.100`. Client mode needs no firewall
changes on the laptop. It only makes outbound connections, and the Pixel's UDP replies
match that conntrack flow.

```sh
cd tools/peer
# 1. Protocol only, headless: hello, pings, state
uv run motoparty-peer client --host 192.168.1.100 --no-audio --tone
#    > talk        -> expect << talk.open{by:"client"} + state{talk:true}, then "voice rx:" lines
#                    Opus from the Pixel shows up as rx/played; the Pixel should hear beeps
#    > talk        -> << talk.close{by:"client",reason:"trigger"}
#    > say pause (no talk open)    -> the Pixel ignores it: no announce
#    > say louder                  -> handled locally, nothing sent (volume is local)
#    > talk, then > say what's the weather
#                                  -> << announce{"text":"Didn't catch that","earcon":"error"};
#                                     a second > say pause in the same talk is ignored
#    > talk, then > hear resume (within 8 s)
#                                  -> >> command.text{"text":"resume"}, << talk.close{by:"client",
#                                     reason:"trigger"} (+ music.play if music was playing);
#                                     no announce ("Resuming" is no longer sent)
#    > talk, then > hear what a view -> "conversation (the first phrase does not parse)";
#                                     a later > hear pause is conversation too
#    > talk, then > hear pause (within 8 s) -> >> command.text{"text":"pause"}, << talk.close{by:
#                                     "client",reason:"trigger"}, no music.play, no announce
#    > talk, then > hear next      -> << talk.close first, then the new track (or << announce
#                                     with the end-of-queue error after the close); no "Next: …"
#    > talk, then > hear louder    -> nothing sent but >> talk.close{by:"client",reason:"trigger"}
#    Pixel-triggered talk, then > hear pause -> "conversation (this phone did not open the talk)"
#    Start a song on the Pixel     -> << music.load, >> music.ready, << music.play (local time logged)
# 2. Real audio: laptop mic <-> Pixel, music through mpv at the scheduled time
uv run motoparty-peer client --host 192.168.1.100 --play
# 3. Discovery as the iPhone does it: Bonjour first, then the /24 sweep
uv run motoparty-peer client --no-audio
```

What to check:

- `offset` should be stable within a few ms, with `rtt` in the single digits on a hotspot.
- `voice rx` should show `underruns` staying low and `target` settling back to 40 ms.
- Pixel-triggered talk should show up as `<< talk.open{by:"host"}`.
- A long pause in talking must not end talk: it ends on a press or a spoken command only.
- `raw {"t":"future.thing"}` must be ignored by the app. `raw {"t":"ping","id":1}` (missing
  `t0`) must not crash it.

Volume is local, and talk can be refused. Two more things to bench on the Pixel:

```sh
# 4. The host must drop a volume music.control and keep the connection (it is malformed now)
#    > raw {"t":"music.control","action":"volumeUp"}   -> the Pixel must NOT change volume,
#      must NOT disconnect; > pause right after must still work
#    > talk, then > raw {"t":"command.text","text":"volume up","lang":"en-US"}
#                                                     -> << announce{"Didn't catch that","error"}
# 5. The Pixel's handling of a client whose mic is dead
uv run motoparty-peer client --host 192.168.1.100 --no-audio --mic-unavailable
#    Trigger talk on the Pixel     -> << talk.open{by:"host"}, >> talk.close{by:"client",
#                                     reason:"unavailable"}; the Pixel must treat that as a
#                                     close request: << talk.close + state{talk:false},
#                                     error earcon on the Pixel, music never interrupted.
#    > unavailable                 -> mic "works" again; the next talk.open opens talk
```

`unavailable` also works mid-session, so one run can do both halves. And when the Pixel
refuses *our* `talk.open` (mic permission revoked, a call in progress), the client prints
`TALK REFUSED by host: microphone unavailable` and stays with `state.talk` false.

### Against the iPhone (fake host on the laptop)

```sh
uv run motoparty-peer host --track ~/Music/some-song.m4a --name "Laptop"
```

The iPhone must reach the laptop, so the laptop firewall (`/etc/nftables.conf`: input policy
drop) has to let the three ports in. These rules are temporary; a reboot or
`nft -f /etc/nftables.conf` removes them:

```sh
sudo sh -c 'nft insert rule inet filter input tcp dport { 47800, 47802 } accept && nft insert rule inet filter input udp dport 47801 accept'
```

Then, from the iPhone app, check each of these:

- It connects via Bonjour. Mute Bonjour with `--no-mdns` to force the sweep.
- Talk: you should hear yourself (the echo), and it should stay open until someone presses
  again or the opener's first phrase is a command.
- Type `load` on the host: the iPhone should download the file, send `music.ready`, and start
  at the logged host time.
- Type `talk` on the host: the iPhone should pause music, and after the second `talk` it
  should resume at +1500 ms.
- Type `mic off`, then trigger talk on the iPhone: it gets
  `talk.close{by:"host",reason:"unavailable"}` and no `state` change. It must play the error
  earcon, leave music playing and not show a talk session. `mic on` restores the normal path.
- With talk open (host `talk`), make the iPhone lose its mic (start a call, revoke the
  permission): it should send `talk.close{by:"client",reason:"unavailable"}`, which the fake
  host treats like any close request — it closes talk and broadcasts `talk.close` + `state`.
- Gapless (PROTOCOL.md "Music flow" 6): start the host with a directory, `--track ~/Music/dir`
  (two or more `.m4a`), type `queue` (every other track goes behind the current one), then
  `load`. The iPhone gets `music.load` for the next track right after the first `music.play`;
  once it answers `music.ready` the host sends `music.next{id, atHostTimeMs}` (the end of the
  current track by its anchor, using the file's own duration), and at that time `state`,
  `music.play{id, 0, sameAnchor}` and the `music.load` of the following track. The iPhone must
  change without a gap and not seek on that `music.play`. `pause`/`play`, `talk` twice and
  `next` cancel the announcement and the host sends it again (after a skip: for the new next
  track). Less than 1.5 s before the end nothing is announced (log: `was not announced,
  loading it (gap)`). After the last track the host sends `music.pause{id, 0}` (parked, not
  `music.stop`); `play` starts it again from 0. A first `music.error` for the current track
  gets its `music.load` again after 2 s.
- Type `hostmic on`, then trigger talk (either side): the iPhone gets `talk.open{…,mic:"host"}`.
  It must open no mic, stay in media mode, pause music and send keepalives only. The host's
  stats show `audio=0` and `dropped=0`, and a nonzero `dropped` means the iPhone is still
  sending audio. It must also send no `command.text`. The fake host sends no voice of its own
  (it only echoes), so the iPhone hears silence.

### Two peers on one laptop

```sh
uv run motoparty-peer host --track song.m4a --no-mdns          # terminal 1
uv run motoparty-peer client --host 127.0.0.1 --tone --play    # terminal 2 (you hear your own beeps echoed)
```

The /24 sweep skips the laptop's own addresses (per spec), so use `--host 127.0.0.1`. To test
Bonjour locally, run the host with `--bind 127.0.0.1` and no `--no-mdns`, which announces on
loopback only. The client browses every interface, so it finds it there.

## Protocol notes

These are the choices this peer makes where PROTOCOL.md is silent or ambiguous. The
Android/iOS implementations should match them, or PROTOCOL.md should say otherwise.

**Codec strictness.** The peer drops and logs, but does not disconnect on:

- a known message with a missing required field;
- a wrong JSON type (`1.0` for an integer, `true` for an integer);
- an unknown enum value;
- an explicit `null`.

Unknown fields are dropped on decode. A client hello's `voicePort`/`httpPort` are dropped,
and a host hello without them is invalid. A `state.mic` on `talk:false` is dropped too (the
message is kept): the spec only says the host sends it while talk is true. `title` and `artist` are required in `music.load`,
`queue` is required in `state`, and all seven non-optional `state.music` fields are required (`art`
and `repeat` are optional; a `repeat` other than `track`/`queue` drops the `state`). A
`music.edit` `move` without an integer `to` ≥ 0 is dropped; `to` on other ops is kept but unused. Only an
oversize length (> 65536; exactly 65536 is allowed) closes the connection.

**Clock.** `hostToLocal`/`localToHost` return floats; nothing rounds. Discarded (rtt < 0)
samples never enter the 8-sample window. A sample whose offset is more than `500 + rtt/2` ms
from the estimate clears the window and starts it over (`ClockEstimator.resets` counts
these). The client keeps the window across reconnects to the same host (same hello `name`)
and clears it when it connects to a different one.

**Voice sender.**

- `seq` and `ts` start at random values.
- `ts` advances 320 per 20 ms frame, including DTX-suppressed frames. On each new talk session
  it jumps forward by the time since the last frame, so a closed period looks like DTX to the
  receiver.
- A keepalive (with the next frame's `ts`) goes out whenever nothing has been sent for 1 s.
  That covers talk closed and long DTX gaps alike.
- The sender skips packets of 2 bytes or less (libopus: "don't transmit"). It **also** skips
  the comfort-noise updates libopus still emits every ~400 ms in DTX (`OPUS_GET_IN_DTX` = 1).
  With that, every audio packet on the wire is a non-DTX frame.

**Voice activity (host stats: `active=`, `silent_for=`).** A received packet counts as activity when the SILK VAD bit is
set. That bit is the top bit of the byte after the TOC, for SILK/hybrid packets with code 0/1;
CELT-only and other packets count as activity. Why:

- Comfort-noise updates are full-size under background noise (40–55 bytes), so packet size
  cannot identify them.
- Real room noise often never triggers DTX at all.

**Jitter buffer** (`motoparty_peer/jitter.py`): a port of the Android reference
(`core/JitterBuffer.kt`), pinned by the shared vectors in `fixtures/jitter.json`
(`tests/test_jitter.py` runs every case).

- **Spurt start.** The first frame of a talk spurt plays once it has waited the target
  (40 ms at first). A spurt starts at the first packet and after every silence gap, also when
  the packet after the gap arrives behind the old spurt's playout clock.
- **Shedding.** At spurt start the oldest frames are dropped until the queue spans no more
  than the target. During a spurt, every 50 frames due: if the smallest depth of that window
  was > target + 120 ms the whole excess is dropped, else if > target + 40 ms one frame. A
  400 ms hard cap applies on every pull. Shed frames are not loss and not underruns.
- **Missing frames.** A `seq` gap is loss: **FEC** from the successor when it is the very
  next frame, otherwise **PLC**. A `ts` jump whose `seq`s in between were all keepalives (or
  audio that came too late) is silence and starts a new spurt. With nothing queued: at most
  3 PLC frames, then silence.
- **Underrun** means a packet arrived after its playout slot (on time up to half a frame
  after it); it is dropped. The target goes up 20 ms (max 200), at most once per spurt, and
  the new target applies from the next spurt.
- **Decay.** After 10 s without an underrun, the target drops 20 ms (min 40).
- **Re-anchor / restart.** Packets late for 100 ms with nothing played: the next late one
  starts a new spurt. A `ts` more than 3 s from the playout clock restarts the buffer.
- Beyond the spec: a duplicate of a queued packet is ignored; a duplicate of one already
  played counts as a late packet (as on Android).

**Talk and music.**

- During talk, `state.music.playing` is false and the anchor is frozen at the talk-open
  position. The resume `music.play` restarts from that position.
- `reason:"unavailable"` is only ever a refusal: the fake host sends it instead of
  `talk.open` (no `state` follows, since nothing changed), and the client sends it in answer
  to a `talk.open` it cannot honour. Music is not touched on either side. A client
  `talk.close{reason:"unavailable"}` while talk is open is treated like any other close
  request (close + broadcast + the usual resume).
- First phrase: the peer has no earcons, so the 8 s window runs from when the talk opened
  locally (the client on the host's `talk.open`). A client that sees a talk only through
  `state{talk:true}` (it joined mid-talk) is not its opener. A phrase after the window spends
  the first phrase. The fake host's talk is solo when no client was connected as it opened.
- Volume: the client parses `say <text>` and `hear <phrase>` itself and swallows a volume result; `vol+`/`vol-`
  send nothing. Only a volume phrase that `hear` accepts as the talk's command closes the talk
  (the client's own `talk.close`); `say` (the raw wire tool) and the buttons leave it open. The fake host answers a volume utterance that still arrives in `command.text`
  with "Didn't catch that", and drops a volume `music.control` as malformed.
- The fake host treats `music.error` like a missed `music.ready`: it plays alone and still
  sends `music.play`.

**Browsing (fake host).**

- An enqueued id that is valid but not one of the host's files is skipped like an invalid one.
- "Nothing playing" means no current track (no `state.music` and no load in flight); a paused
  track counts as current.
- `"now"` and `"jump"` push the old current track onto the `previous` history.
- Play by touch ends a talk: a `mode:"now"` enqueue (as sent; not a `next`/`end` that acts like
  `now` because nothing is playing) or a valid `jump` edit during a talk sends
  `talk.close{by:"client",reason:"trigger"}` + `state` first, like a spoken `play …`; the new
  track's `music.play` is never sooner than 1500 ms after the close, and an earlier spoken
  `pause` no longer holds it back. An enqueue left with no tracks or a stale `jump` leaves the
  talk open.
- The 200 cap applies to the tracks of one enqueue and to the resulting upcoming queue
  (the tail is dropped).
- A `jump`/`remove` without `index` and `id` is decoded fine and then ignored, like a stale one.

**Discovery.**

- "Several answer at once" means within 250 ms of the first valid `hello`. Without a
  preferred name (nothing linked yet), the first answer wins with no wait.
- Bonjour candidates whose 10 s backoff has run out are probed again (checked every 0.5 s)
  while discovery runs. The sweep repeats once a round ends (1 s pause), skipping addresses
  still backed off.
- The probe connection is closed, and the control connection is a new one: the client never
  sends `hello` on a probe. A handshake that fails on the winner backs it off like a failed
  probe.
- A Bonjour result's TXT is only checked against the winner's `hello`, and a mismatch is only
  logged.
