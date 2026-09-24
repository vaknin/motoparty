# Motoparty

Two-up motorcycle intercom and shared music between a Pixel 8 (rider, host) and an iPhone
(passenger, client), over the Pixel's 2.4 GHz hotspot. Design and rationale:
`~/.claude/plans/motorcycle-communication-application-proud-pixel.md` (it still says 5 GHz:
the Pixel refuses 5 GHz hotspot in the user's country; the 2.4 GHz bench, with Bluetooth on,
is good enough for talk; numbers in `HANDOFF.md`, "Hotspot").

| Path | What |
|------|------|
| `PROTOCOL.md` | Wire protocol, the contract all three implementations follow |
| `fixtures/` | Shared test vectors for the protocol |
| `android/` | Host app (Kotlin, Compose, Gradle) — see `android/README.md` |
| `ios/` | Client app (SwiftPM, built with xtool from Linux) — see `ios/README.md` |
| `tools/peer/` | Python peer: fake client or fake host for bench tests — see `tools/peer/README.md` |
| `tools/bench/` | Device bench scripts (adb + the peer) with a short summary per run — see `tools/bench/README.md` |
| `TELEMETRY.md` | Agreed with the NX500 telemetry node (`~/Work/Honda`): wired handlebar control box, key-on session start |
| `research/` | Hardware research held in reserve, read only if the microphone question reopens — see `research/README.md` |

## Ride setup (once)

- Pixel: hotspot on (2.4 GHz is all the Pixel offers here), "turn off hotspot automatically"
  off, mobile data on.
- iPhone: join the Pixel hotspot once and leave Auto-Join on.
- iPhone app is signed with a free Apple ID and expires after 7 days: re-install before a ride.
