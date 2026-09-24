# Handlebar controls and key-on start: Motoparty + the NX500 node

Agreed 2026-09-22. **Nothing here is built or bought.** The NX500 telemetry node lives in
`~/Work/Honda` (ESP32-S3 + CAN transceiver polling the engine ECU, BLE to the phone; source of
truth `~/Work/Honda/BASELINE.md`, parts in `~/Work/Honda/HARDWARE.md`). Same rider, same bike,
same Pixel 8 host, so the node's phone side is a module of this host app, not a second app.

## What was agreed

1. **Key-on starts the ride.** The node is powered from the switched 12 V at the diagnostic port,
   so its BLE advertisement appearing means the ignition is on. The host auto-starts the session,
   connects the passenger and resumes music; the node disappearing ends the session cleanly.
2. **A wired handlebar control box**, reported to the phone over the node's BLE link. A Bluetooth
   media remote was considered and rejected: it sleeps, lags on the first press, and is a third
   device on the Pixel's radio.

## Control box

- **Where:** left bar, 22 mm clamp, since the right hand holds the throttle.
- **Controls:**
  - one big momentary button, talk (the one you hit without looking);
  - a smaller momentary button, voice command;
  - a sealed rotary encoder for volume, push to mute. Encoder over potentiometer because the phone
    can change volume on its own, so an absolute knob drifts from reality; relative clicks do not;
  - a toggle switch for talk / music mode (two or three positions, see open questions).
- **Housing:** an aftermarket handlebar switch unit sold for auxiliary lights (sealed box, clamp,
  cable attached). AliExpress: "motorcycle handlebar switch 22mm momentary 4 button",
  "motorcycle handlebar multifunction switch self-reset". Must say momentary / self-reset for the
  talk and command buttons; leave any 12 V LED backlight disconnected. Encoder separately:
  "rotary encoder IP65" / "waterproof rotary encoder module", pins soldered.
- **Cable:** one stranded Cat5e patch cable with one end cut off (eight conductors: one per button
  plus a shared ground, three for the encoder, one or two for the toggle). Routed along the factory
  switchgear harness under the tank to the seat area, zip-tied, with a strain-relief loop at the
  steering head. A sealed GX12/GX16 aviation plug at each end so the box and the node unplug.
  Keep the encoder lines away from the CAN pair.
- **Electrical:** ESP32 internal pull-ups, firmware debounce. A series resistor and small capacitor
  per input only if ghost presses appear. No relays anywhere in the design.

## Node hardware, kept minimal

Per `~/Work/Honda/HARDWARE.md`, with these simplifications:

- Soldering is fine (user confirmed): standard SN65HVD230 board with R2 removed, Cat5e conductors
  soldered to the dev board or a small perfboard, encoder pins soldered.
- Still off the iron: a sealed automotive 12 V to 5 V buck with built-in reverse and surge
  protection (rated 9–36 V in) instead of the discrete diode + TVS chain; an inline blade fuse;
  Wago 221 lever connectors at the OBD2 pigtail so the node unplugs without cutting.
- Power and CAN both come from the one OBD2 pigtail into the Honda 6-pin adapter. Switched 12 V,
  so no sleep logic and no battery drain.

## Software

- **Node firmware (ESP-IDF, in the Honda tree):** the telemetry characteristic plus one "controls"
  characteristic: button down/up events, encoder deltas, toggle state (state, not edge, so the phone
  knows the mode after a reconnect). Latency over BLE with high connection priority is tens of ms.
- **Android host:** a BLE client module (Companion Device Manager for auto-reconnect,
  `CONNECTION_PRIORITY_HIGH`), a new `TriggerSource.HANDLEBAR`, and new trigger kinds for volume,
  mute and mode alongside TALK and MUSIC. Key-on session start hangs off device discovery.
- **iOS client and `PROTOCOL.md`:** untouched.

## Build plan: three evenings, each ending with something working

1. ESP32 on USB at the desk, firmware flashed, BLE visible to the Pixel. No bike.
2. CAN module and switch box on jumper wires, still at the desk. Button presses show in the app.
3. The bike: six wires at the OBD2 pigtail, cable to the bars.

## Open questions

- **Radio coexistence.** The Pixel already runs a 2.4 GHz hotspot plus AirPods. The BLE link adds
  under 400 B/s, but the talk bench only just holds with Bluetooth on. One bench measurement before
  relying on it.
- Toggle semantics: two positions (talk / music) or three (plus off).
- Whether to import `~/Work/Honda` into this repo (as a `telemetry/` directory with its own
  handoff) or keep two folders. Not decided. If imported: after the uncommitted F9b and Stage A
  work is committed, in its own commit, without the `__pycache__`.

## Status

- Agreed direction only. No parts ordered, no firmware, no app code.
- Precondition unchanged: the ride recording in `HANDOFF.md` "Do now" still comes first.
- Next concrete step when wanted: one AliExpress order list for the node plus the control box,
  written against `~/Work/Honda/HARDWARE.md` so nothing is ordered twice.
