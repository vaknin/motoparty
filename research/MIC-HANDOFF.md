# Handoff prompt: sourcing the helmet microphone

Paste everything below the line into a fresh session. It assumes no prior context.

---

# Source the helmet microphone: wireless-in-helmet vs. buying wired, locally

This is a **sourcing task with one real engineering question inside it**. Two directions are open
and need a head-to-head. Prior passes burned effort on a dead channel; §"What is already
established" exists so you do not repeat that.

## The project, in one paragraph

A custom Android/iOS app turns two phones into a motorcycle intercom over a Wi-Fi hotspot: Opus
16 kHz mono, rider's **Pixel 8** on the handlebar, passenger's **iPhone** in a pocket. It is not a
Cardo/Sena product — **do not recommend any Bluetooth helmet intercom unit**. The rider listens
through **AirPods Pro 2** over A2DP; that is decided and measured and is **not** in scope. What is
in scope is the **microphone**: it must sit **at the mouth, inside a full-face helmet**, out of the
turbulence.

Riding regime: motorway, **100–130 km/h**, full-face helmet, **multi-hour** rides, and **the bike
has no charger and no 12 V outlet — nothing can assume mid-ride power**. Shipping to **Haifa,
Israel**; the VAT-free personal-import line is **US$75**.

## Decided — do not reopen

- **Playback is AirPods Pro 2 over A2DP.** Do not research earphones, IEMs, earplugs, helmet
  speakers or hearing protection. The user dislikes Apple and will not buy new Apple hardware.
- **The app runs a "split config"**: digital/USB microphone in, Bluetooth A2DP out, staying in
  `MODE_NORMAL`, using `AudioRecord` with `AudioSource.UNPROCESSED` + `setPreferredDevice()` on a
  **USB audio device**. This was settled from AOSP source. **Consequence for you: the microphone
  must present to Android as a class-compliant USB audio device (UAC). A companion app or a
  proprietary protocol is disqualifying.**
- The 16 kHz Opus wire format and `PROTOCOL.md` are out of scope. Rain is parked.
- **The Pixel 8 has no analogue audio mode.** A passive analogue capsule behind a USB-C plug
  cannot work — this is verified, not a guess.

## The requirement

| | |
|---|---|
| Capsule position | at the mouth, inside the helmet (chin bar) |
| Interface | USB-C, class-compliant (UAC), driver-free, **no companion app** |
| Cable run, if wired | **~1 m, measured by the user** (handlebar to mouth). 1.5 m of cable is ideal, 2 m acceptable, 3 m surplus, 6 m a liability |
| Quantity | **two complete systems** — rider and passenger, on two different phones |
| Power | nothing rechargeable unless it survives a multi-hour ride; the rider already charges a phone and AirPods with no bike power |

## What is already established — use it, do not re-research it

Grades: **M** measured by a named third party · **D** documented (spec, standard, source) ·
**R** relayed from a subagent, unverified · **A** anecdote · **U** unknown.

**Screening rules that work:**
- A spec line reading `Sensitivity … RL=2.2k Ohm VS=3V` (a load resistance and a supply voltage)
  means an **analogue electret — reject**. **D**
- A stated **sample rate / bit depth** means a converter is present. **D** — **but this does not
  distinguish wired from wireless**: 2.4 GHz systems advertise "48 kHz/24-bit" too. That mistake
  cost a wasted candidate.
- One listing selling **3.5 mm / USB-A / Lightning / USB-C as variants**, often hidden under a
  "Color" selector, is one capsule with swappable plugs — **reject**. **D**
- AliExpress's **"AI overview of item"** block is generated from the title and disclaimed on the
  page. Ignore it. **D**

**Dead ends — do not revisit:**
- **AliExpress is the wrong channel for a *wired* USB-C lavalier.** Item pages are captcha-walled
  so only search-card data is readable, and search cards cannot reliably tell wired from wireless
  or state a cable length. Of twelve results on a `48khz` search, **eleven were wireless**. Three
  candidates were offered to the user and all three failed on inspection. **R**
- **Cubilux MLC-12** — correct spec on paper (1.52 m, 48 kHz/24-bit, unidirectional, names the
  Pixel 8) but **~3★ on Amazon and does not ship to Israel**. Dead. **R**
- **MAONO UL11** — correct spec (78 in ≈ 2 m, 192 kHz/24-bit, ships with a foam windscreen) but
  **not on AliExpress**; MAONO's store there carries wireless and gaming mics only. It is an
  Amazon-channel product. **R**
- **BOYA BY-M3** is 6 m; **Comica CVM-V01SP(UC)** exists only in 4.5 m and 6 m for USB-C (the
  2.5 m version is the Lightning model). Both are genuine digital mics, both too long. **D**
- AliExpress listing `1005003021740716` (Saramonic LavMicro U3 series, ₪95.52) is **3.3★ from 3
  reviews**. Note its title is Hebrew and reads "LavMicro U3 *סדרת*", never "U3A" — searching the
  model name does not find it. **R**

**Acoustics already settled — do not redo:**
- Wind inside a full-face helmet is **~103 dB(A) at 110 km/h**, energy concentrated below 500 Hz.
  **M**
- **A furry "dead cat" windscreen gives ~20–25 dB of wind-noise reduction; foam gives ~5–8 dB.**
  This is the highest-value accessory in the whole project and belongs in any cart. **D**
- **Polar pattern matters less than expected**: a true cardioid gains ~4.8 dB of random-energy
  efficiency over an omni in a diffuse field. **D** Capsule *position* and the windscreen are the
  large terms; the pattern is a preference, not a requirement.

## The two directions to compare

### A. Wireless 2.4 GHz lavalier, transmitter clipped inside the helmet

The idea: clip the **transmitter itself** at the chin bar, so its built-in capsule sits at the
mouth and there is **no cable at all** between helmet and phone. This removes the tether, the snag
risk, and the fact that USB-C has no breakaway (it pulls out at 8–20 N, so a snag yanks the phone
rather than releasing).

An earlier pass dismissed wireless on the assumption that the transmitter must sit on the chest,
in the airstream. **That assumption is wrong if the unit is small enough to live in the helmet**,
which is why this needs a real evaluation. Answer, in priority order:

1. **Transmitter dimensions and weight in grams**, and **what the clip actually is** (magnetic
   clips may not clamp through chin-bar padding). Candidates: BOYA Mini / Mini 2, Hollyland Lark
   M1/M2, Insta360 Luna / Mic Air, MAONO Wave T5, DJI Mic Mini, SYNCO, plus cheap generics.
2. **Battery hours for the transmitter, and whether the charging case recharges it and by how
   much.** With no power on the bike and multi-hour rides, this is likely the deciding number.
   The user's position: *"not sure — tell me the numbers first."*
3. **CRITICAL: does the USB-C receiver enumerate as a class-compliant USB audio device on a Pixel
   8, with no companion app?** Anything requiring a vendor app is disqualified. A 3.5 mm receiver
   output is useless — see "the Pixel 8 has no analogue audio mode".
4. **Latency in ms.** The playback path already spends 150–300 ms on Bluetooth.
5. **2.4 GHz coexistence.** The intercom itself runs over a **phone Wi-Fi hotspot**, possibly on
   2.4 GHz, carrying ~24 kbps of Opus. Do these systems hop frequencies? Is the interference risk
   real or theoretical? Be honest.
6. **Wind on the transmitter's own capsule** — does a furry windscreen exist that fits the TX
   *body*, not a lavalier capsule?
7. **Does the transmitter have a 3.5 mm external lavalier input?** That is the fallback: TX on the
   collar, short lav lead into the helmet.
8. **A "2-pack" is normally 2 TX + 1 RX and does NOT serve two people on two phones.** State what
   a correct two-person purchase is and what it costs.

### B. Wired USB-C lavalier bought from an Israeli retailer

The point is **returnability**: the only real unknown is "does it enumerate on the Pixel 8", and a
local purchase you can return de-risks that directly. Paying more than AliExpress is acceptable.

- Search **in Hebrew as well as English**: `מיקרופון דש USB-C`, `מיקרופון דש טייפ סי`,
  `מיקרופון לאבלייר USB C`. Brands: Saramonic, BOYA, Comica, MAONO, RODE, Sennheiser.
- **`zap.co.il` is the best starting point** for "who in Israel sells X". Also KSP, Ivory, Bug,
  photo/video specialists, Haifa camera shops.
- Name the **Israeli distributor** for Saramonic / BOYA / Comica if one exists.
- **Does Amazon.com ship any suitable model to Israel**, and at what cost? (Amazon has a
  Ship-to-Israel programme with an import-fees deposit.) Cubilux is already ruled out — skip it.
- Report price in ILS, cable length, polar pattern, stated sample rate, stock, and return policy.

## Deliverable

Write findings to a **new `.md` file in the repo root**. Do not touch code or `PROTOCOL.md`.

1. A **head-to-head** of A vs B: cost for two people, what breaks, what has to be charged, and
   what is unverified in each.
2. A **recommendation**, with primary and backup, links, and prices.
3. **What would change the answer, and the test that would show it.**
4. Say plainly if the honest answer is "neither is good; do X instead".
5. Update `PLAYBACK.md` §5.5, which still contains the original dismissal of wireless based on the
   chest-mounted assumption named above.

## Rules

- **Do not circumvent bot walls, captchas, paywalls or proof-of-work challenges**, and do not
  spoof user-agents or set region cookies — not you, not any subagent. If a site blocks you,
  record "blocked, could not read" and move on. An earlier subagent on this project did this
  unprompted and its data is now permanently distrusted. **Tell every subagent explicitly.**
- **Run subagents on Opus. Not Fable.** Tell them not to spawn their own helpers and not to write
  files into the repo.
- AliExpress item pages are JS-rendered and captcha-walled; expect **search-results-level data at
  best and label it so**. The user can open a page in a browser — ask them when a decision hinges
  on it, rather than guessing.
- **Grade every claim**: measured / documented / reported / inferred / guess / unknown. Distinguish
  them every time. Being honest about what you could not verify is worth more than a
  complete-looking table.
- Prices in ILS where the page shows them (USD/ILS ≈ 3.03). Flag if a two-person purchase crosses
  the **US$75** VAT-free line.
