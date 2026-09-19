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

System libraries (libopus is loaded with ctypes, PortAudio through `sounddevice`):

| Needed for | Arch package | Notes |
|---|---|---|
| voice (always) | `opus` | `libopus.so.0`, via ctypes |
| mic and speakers | `portaudio` | only loaded without `--no-audio` |
| track metadata, the music part of the tests | `ffmpeg` | optional: without it, duration comes from the MP4 `mvhd` box and the title is the file name |
| `client --play` | `mpv` or `ffmpeg` (ffplay) | optional |

## Client: `uv run motoparty-peer client [options]`

With no `--host`, the client browses `_motoparty._tcp` for 3 s. If nothing answers, it sweeps
port 47800 on every address of its own /24 (64 in parallel, 400 ms connect timeout) and takes
the first address that answers with a valid host `hello`. `--host IP` skips discovery. It
reconnects on its own after link loss (6 s with nothing received) or a `bye`.

| Option | |
|---|---|
| `--host IP`, `--port N` | connect directly (default port 47800) |
| `--tone` | send 440 Hz beeps instead of the mic (400 ms on / 100 ms off; see notes) |
| `--no-audio` | no sound devices at all. Received voice still runs through the jitter buffer and decoder on a 20 ms clock, and the stats get logged |
| `--play` | play music with mpv/ffplay at the scheduled moment (otherwise the schedule is only logged) |
| `--name`, `--lang` | hello name (default hostname), BCP-47 tag for `say` (default `en-US`) |
| `--mic-unavailable` | start with the mic marked dead: answer the host's `talk.open` with `talk.close{by:"client",reason:"unavailable"}` (stdin `unavailable` toggles it) |
| `--cache-dir` | where tracks are downloaded (default `~/.cache/motoparty-peer`) |
| `--input-device`, `--output-device` | PortAudio device index or name |
| `-v` | also print raw ping/pong frames |

Stdin commands:

| Command | Sends |
|---|---|
| `talk` | `talk.open{by:"client"}`, or `talk.close{by:"client",reason:"trigger"}` if talk is open |
| `say <text>` | `command.text{text, lang}` — but the parser runs locally first, and a volume phrase (`louder`, `volume down`, …) is handled here and **not** sent |
| `pause` `resume` `next` `previous` | `music.control{action}` |
| `vol+` `vol-` | nothing: volume is local (the peer has no real volume, so it just logs it) |
| `unavailable` | toggles "my mic is dead": while on, the host's `talk.open` is answered with `talk.close{by:"client",reason:"unavailable"}` and talk never opens locally; `talk` will not ask for talk either |
| `stats` | prints jitter-buffer/voice stats and the clock estimate |
| `raw <json>` | sends any JSON object **unvalidated**, to test how the other side handles bad input — the only way to send a message the codec now rejects, e.g. `raw {"t":"music.control","action":"volumeUp"}` |
| `quit` | `bye{reason:"user"}`, then exits |

A host `talk.close{by:"host",reason:"unavailable"}` in answer to our `talk.open` is logged as
`TALK REFUSED by host: microphone unavailable`; talk never opened, so `state.talk` stays false.

Output: `<<` lines are received messages and `>>` lines are sent ones, as compact JSON.
Pongs print as `pong id=… rtt=…ms offset=…ms | estimate offset=…ms (rtt …ms)`. While talk
is open, a `voice rx:` line every 2 s shows received / played / FEC / PLC / late / underruns,
the jitter-buffer depth and target, plus how many audio packets, DTX skips and keepalives
were sent. On `music.load` the client downloads the file over HTTP and checks it (ftyp box,
plus an ffprobe audio stream when ffprobe is available), then replies `music.ready` or
`music.error`. On `music.play` it logs the host time converted to local time and how far
ahead that is.

Voice: the peer sends a keepalive after 1 s without sending anything. While talk is open
it sends Opus frames (VOIP, 24 kbps, FEC 10 %, DTX, 16 kHz mono, 20 ms) from the mic or
the tone. Received audio is played through the adaptive jitter buffer.

## Host: `uv run motoparty-peer host [--track FILE.m4a]`

Advertises `<name>._motoparty._tcp` with TXT `proto=1 voice=47801 http=47802` and listens on
47800/tcp, 47801/udp and 47802/tcp (`--port/--voice-port/--http-port`, where 0 means any free
port; `--bind`; `--no-mdns`). The hello carries the real ports.

- One client at a time. A new `hello` replaces the old connection, which gets `bye{reason:"replaced"}`.
- Answers `pong` with `t1` taken at frame receipt and `t2` at send. The clock is `CLOCK_MONOTONIC` in ms.
- Talk authority. It opens or closes on client requests and on the stdin `talk` command, and
  broadcasts `talk.*` + `state`. After `mic off` it answers a client's `talk.open` with
  `talk.close{by:"host",reason:"unavailable"}` instead — talk never opens, `state.talk` stays
  false and nothing else is broadcast. It closes with `reason:"silence"` after 10 s
  (`--silence-ms`) with no voice activity from the client, and with `"link"` on link loss.
  Music that was playing is frozen during talk and resumed with `music.play` at
  `now + 1500 ms`.
- Voice: replies to the source of the most recent valid packet from the client's IP. While
  talk is open it **echoes** every audio packet back (own seq, same ts and payload), so one
  phone can hear itself.
- HTTP: `GET`/`HEAD /track/<id>.m4a` for the `--track` file, `Content-Type: audio/mp4`, single
  `Range` → 206, unsatisfiable → 416, anything else → 404. `<id>` is 11 URL-safe characters
  derived from the file's SHA-1.
- `command.text` goes through the grammar parser and is answered with `announce`.
  `play …` announces "Playing <title> by <artist>" and loads/plays the track. Unmatched text
  gets `{"text":"Didn't catch that","earcon":"error"}`, and so does a volume utterance:
  volume is local and should never arrive here.
- A `music.control` with a volume action is not a valid message any more; it is dropped as
  malformed (logged as `dropped invalid frame`) and the connection stays up.

Stdin commands: `load` (sends `music.load`, then `music.play` 300 ms ahead once
`music.ready` arrives, or after 8 s / on `music.error`), `play`, `pause`, `stop`, `talk`,
`mic on|off` (bare `mic` toggles; `off` refuses the client's `talk.open` as `unavailable`),
`announce <text>`, `state`, `stats`, `raw <json>`, `quit`.

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
#    > say play album abbey road   -> << announce{…}
#    > say what's the weather      -> << announce{"text":"Didn't catch that","earcon":"error"}
#    > say louder                  -> handled locally, nothing sent (volume is local)
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
- A 10 s pause in talking should end talk with `reason:"silence"`.
- `raw {"t":"future.thing"}` must be ignored by the app. `raw {"t":"ping","id":1}` (missing
  `t0`) must not crash it.

Volume is local, and talk can be refused. Two more things to bench on the Pixel:

```sh
# 4. The host must drop a volume music.control and keep the connection (it is malformed now)
#    > raw {"t":"music.control","action":"volumeUp"}   -> the Pixel must NOT change volume,
#      must NOT disconnect; > pause right after must still work
#    > raw {"t":"command.text","text":"volume up","lang":"en-US"}
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
- Talk: you should hear yourself (the echo), and it should end on 10 s of silence.
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

### Two peers on one laptop

```sh
uv run motoparty-peer host --track song.m4a --no-mdns          # terminal 1
uv run motoparty-peer client --host 127.0.0.1 --tone --play    # terminal 2 (you hear your own beeps echoed)
```

The /24 sweep skips the laptop's own addresses (per spec), so use `--host 127.0.0.1`, or let
Bonjour find the local host by leaving out `--no-mdns`.

## Protocol notes

These are the choices this peer makes where PROTOCOL.md is silent or ambiguous. The
Android/iOS implementations should match them, or PROTOCOL.md should say otherwise.

**Codec strictness.** The peer drops and logs, but does not disconnect on:

- a known message with a missing required field;
- a wrong JSON type (`1.0` for an integer, `true` for an integer);
- an unknown enum value;
- an explicit `null`.

Unknown fields are dropped on decode. A client hello's `voicePort`/`httpPort` are dropped,
and a host hello without them is invalid. `title` and `artist` are required in `music.load`,
`queue` is required in `state`, and all seven `state.music` fields are required. Only an
oversize length (> 65536; exactly 65536 is allowed) closes the connection.

**Clock.** `hostToLocal`/`localToHost` return floats; nothing rounds. Discarded (rtt < 0)
samples never enter the 8-sample window.

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

**Silence detection (host).** A received packet counts as activity when the SILK VAD bit is
set. That bit is the top bit of the byte after the TOC, for SILK/hybrid packets with code 0/1;
CELT-only and other packets count as activity. Why:

- Comfort-noise updates are full-size under background noise (40–55 bytes), so packet size
  cannot identify them.
- Real room noise often never triggers DTX at all.

**Jitter buffer** (`motoparty_peer/jitter.py` has the full model and its tests):

- **Depth** is the buffered audio ahead of the playout point, including the frame about to
  play.
- **Start of playout.** Each talk spurt starts playing once 40 ms (the target) is buffered,
  or once its first packet has waited the target.
- **Missing frames.** When a frame is missing but a later packet is buffered, the peer counts
  the lost seqs; keepalive seqs don't count. The frame right before the later packet is
  recovered with **FEC** from it, other lost frames get **PLC**, and a gap with no lost seqs
  is DTX and plays as **silence**.
- **Underrun** means a packet arrived after its playout slot. The target goes up 20 ms
  (max 200). If the late packet directly follows the last played frame, playout re-anchors on
  it; otherwise the extra 20 ms is inserted at the next silence.
- **Decay.** After 10 s without an underrun, the target drops 20 ms (min 40).
- **Trim.** Once a second, if the minimum depth over that second was ≥ target + 20 ms, one
  frame is dropped.

**Talk and music.**

- During talk, `state.music.playing` is false and the anchor is frozen at the talk-open
  position. The resume `music.play` restarts from that position.
- `reason:"unavailable"` is only ever a refusal: the fake host sends it instead of
  `talk.open` (no `state` follows, since nothing changed), and the client sends it in answer
  to a `talk.open` it cannot honour. Music is not touched on either side. A client
  `talk.close{reason:"unavailable"}` while talk is open is treated like any other close
  request (close + broadcast + the usual resume).
- Volume: the client parses `say <text>` itself and swallows a volume result; `vol+`/`vol-`
  send nothing. The fake host answers a volume utterance that still arrives in `command.text`
  with "Didn't catch that", and drops a volume `music.control` as malformed.
- The fake host treats `music.error` like a missed `music.ready`: it plays alone and still
  sends `music.play`.

**Discovery.** During the sweep, a candidate that accepts the TCP connection gets 1 s to
send its hello; the spec's 400 ms covers only the connect. The connection that wins the
sweep is kept as the control connection.
