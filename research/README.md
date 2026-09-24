# Research: the fallback, if the AirPods microphone fails the ride

**Nothing in this directory is implemented, and nothing in it has been bought.** These four
documents are one costed plan — *replace the in-ear microphone with one at the mouth* — held in
reserve. They are read **only** when the baseline ride recording (`HANDOFF.md`, "Do now") comes
back unintelligible. Until then they cost a session nothing but the reading, so don't.

The decision they hang on, in one line: the AirPods microphone sits in the ear, where the helmet
measures ~103 dB of wind at 110 km/h against 75–85 dB of speech; a microphone at the chin bar sees
88–94 dB of speech and can be given a furry windscreen. That is ~25–35 dB of signal-to-noise **on
paper**, and nobody has measured any of it on this helmet, this head or this phone.

| file | what it answers | status |
|---|---|---|
| `RESEARCH.md` | Why AirPods cannot give a good microphone and good playback at once (A2DP vs HFP/SCO, LE Audio, HFP super-wideband), and §4.8, the wired-mic idea that started all of this. | The design pass. Its §5.1 "the AirPods mic is a hard requirement" and its §6 "won't-do" for the USB-C mic were both reversed later. |
| `PLAYBACK.md` | What the rider listens through, and the "split config" (USB microphone in, AirPods A2DP out). Its §8 is the free test list — **T1 is the ride recording, and it is live work, not fallback**. | Settled on keeping the AirPods for playback at zero cost. |
| `MIC.md` | The microphone itself: wireless-in-helmet (Hollyland Lark A1, ₪299–350 for two) vs. wired (Sennheiser XS Lav USB-C, ₪312 each), bought in Israel and returnable. §6 is the test list, §3 the overload risk nobody had spotted. | The purchase plan. Recommends wireless, **after a free eraser-block fit test**. |
| `HARDWARE.md` | The older AliExpress parts list for the fully-wired route: sound cards, chin-bar mics, cabling, the breakaway, and §4 on rain (parked). Superseded by `MIC.md` on sourcing; still the only record of the rain findings and the Pixel's "Enable USB" override. | Partly superseded. |
| `MIC-HANDOFF.md` | The prompt that produced `MIC.md`. Kept only so the grading rules and the dead ends it lists are not re-walked. | Spent. |

**How to read the grades** (used throughout): **M** measured by a named third party · **D**
documented · **R** reported, unverified · **I** inferred · **G** guess · **U** unknown. The
distinction is the point; a complete-looking table without it is worth less.
