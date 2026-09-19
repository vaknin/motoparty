# Motoparty

Two-up motorcycle intercom and shared music between a Pixel 8 (rider, host) and an iPhone
(passenger, client), over the Pixel's 5 GHz hotspot. Design and rationale:
`~/.claude/plans/motorcycle-communication-application-proud-pixel.md`.

| Path | What |
|------|------|
| `PROTOCOL.md` | Wire protocol, the contract all three implementations follow |
| `fixtures/` | Shared test vectors for the protocol |
| `android/` | Host app (Kotlin, Compose, Gradle) — see `android/README.md` |
| `ios/` | Client app (SwiftPM, built with xtool from Linux) — see `ios/README.md` |
| `tools/peer/` | Python peer: fake client or fake host for bench tests — see `tools/peer/README.md` |

## Ride setup (once)

- Pixel: hotspot on the 5 GHz band, "turn off hotspot automatically" off, mobile data on.
- iPhone: join the Pixel hotspot once and leave Auto-Join on.
- iPhone app is signed with a free Apple ID and expires after 7 days: re-install before a ride.
