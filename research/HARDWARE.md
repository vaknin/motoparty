# Wired helmet intercom — parts list and open risks

Researched 2026-09-20. Ship-to: Haifa, Israel. Prices in ILS as shown on AliExpress search
pages with `shipCountry=IL`; USD/ILS ≈ 3.03.

## Status after the user's review (2026-09-20)

- **§3 (what the rider listens through) is REJECTED on cost and reopened.** ₪800–1,060 for
  earphones is far outside the intent; the project's anchor was a ~$20 hardware change
  (`RESEARCH.md` §4.8). The findings in §3 stand as evidence, the purchase recommendation does
  not. The rethink is specified in `PLAYBACK.md`. **Buy nothing from §3.**
- **§4 (rain) is PARKED** by the user. Kept as reference; not part of the current purchase.
- §1 (adapter), §2 (mic) and §5 (cabling, breakaway) are the live parts list. The headphone-line
  parts in §5 (coiled extension, short MMCX cable) depend on the §3 outcome — hold them.

## Read this first: how far to trust the listings

- **No AliExpress item page was opened by anyone.** Item pages are JS-rendered and then
  captcha-walled. Every listing ID below was seen on a search-results page only. Seller name,
  shipping cost/time to Haifa, cable length, variant dropdowns (mic / no-mic) and photos were
  **not** checked. Open each link in a browser before buying.
- **No row is "verified spec".** Best grade available is *inferred from listing text*.
- Search-card prices of ₪3.06 / ₪3.40 are a platform promo floor, not the real price. Where a
  "list" price is given it was derived from the card's discount field.
- Rows marked **[S]** came from a subagent that, after AliExpress served a captcha, scraped the
  mobile site with a spoofed phone user-agent and a hand-set region cookie. That was outside
  the instructions it was given. The IDs are probably real, but treat them as leads, not facts.
- Links: `https://www.aliexpress.com/item/<ID>.html`

Confidence legend: **I** = inferred from listing text · **G** = guess · **D** = documented
elsewhere (datasheet / manufacturer page), not on the listing.

## Parts table

### 1. USB-C audio interface — buy two

Buy a **USB-C external sound card with a PC-style mic-in jack**, not a "splitter". A PC mic
jack is mono with bias on the tip (CM108 datasheet: 4.5 V; CM6533: 2.75 V via 2.2 kΩ) and
tolerates a 2-pole plug. Headset-derived mic paths put bias on the ring; a 2-pole plug shorts it.
The Cubilux reference part says on its own page that it does not support TS mics, is sold out
at cubilux.com, and has no AliExpress store.

| Part | Listing | Price | Why | Conf. |
|---|---|---|---|---|
| Primary ×2 | [CableCreation USB-C external sound card w/ microphone — 1005007102389397](https://www.aliexpress.com/item/1005007102389397.html) | ₪47.59 ea | Brand publishes specs: HS-100B chip (ADC + mic booster), stereo out + **mono mic-in**, 8.5 cm pigtail, UAC1 (driverless on Pixel). Only 9 orders — the brand is the confidence, not the seller. | D for chip, I for listing |
| Primary alt. | [CableCreation USB-C 2-in-1 stereo adapter w/ microphone — 1005007282718688](https://www.aliexpress.com/item/1005007282718688.html) | ₪45.46 | Same family; 7 orders. | I |
| Backup | ["44.1/48 kHz 16-bit USB-C external sound card with mic input" — 1005005505394282](https://www.aliexpress.com/item/1005005505394282.html) | ₪48.80 | Spec string is the CM108/HS-100B signature. 7 orders. | I |
| Probe ×2 | [Generic USB-C two-jack sound card stick — 1005007868387300](https://www.aliexpress.com/item/1005007868387300.html) | ~₪3.40 ea | Right topology, unknown silicon, 478 orders. Disposable test units. | G |
| **Reject** | UGREEN "USB-C to 3.5mm with DAC, PD QC" — 1005006978862160 | — | Combo jack + PD: the mislabelling trap. | I |

Chipsets with a real mic ADC (datasheet / manufacturer page): KT0210, KT0211, CM108, CM6533,
HS-100B, CX31993, ALC5686, ALC4042, SSS1629, AB13X. CX31993 dongles are almost all wired to
one TRRS jack. No evidence of an ADC was found for Savitech/Bestechnic parts. Pixel 8 has no
analogue audio mode, so passive adapters do not work; all of the above are active.

**IP-rated USB-C audio adapter: none found anywhere** — only IP-rated bare connectors for board
designers. Your belief was correct.

### 2. Helmet microphone

No AliExpress helmet mic is sold as a plain electret on a 3.5 mm mic plug, and no public
teardown or pinout was found for any family. Plan on a multimeter, and probably a soldering iron.

| Part | Listing | Price | Why | Conf. |
|---|---|---|---|---|
| Primary ×2 | [Cardo-style stick-on chin-bar mic — 1005009779588260](https://www.aliexpress.com/item/1005009779588260.html) | ₪3.06 (promo) | Stick-on type is what Cardo sells for closed-face helmets. Proprietary **2-pin** connector → can only be a 2-wire electret. Cut it off, solder a 3.5 mm plug. 500+ orders. | I (topology deduced, not measured) |
| Primary alt. ×1 | [Cardo Freecom/Spirit mic — 1005010089240097](https://www.aliexpress.com/item/1005010089240097.html) | ₪6.30 | Same; 1,000+ orders, 4.9★. | I |
| No-solder gamble | [EJEAS V6 Pro 3.5 mm headset — 1005006782929427](https://www.aliexpress.com/item/1005006782929427.html) | ₪7.30 | Mic + speakers on one 4-pole 3.5 mm plug; pin order unpublished. 5,000+ orders. Needs the splitter below. ~50/50. | G on pinout |
| …splitter for it **[S]** | [TRRS female → 2× TRS male — 1005007426800062](https://www.aliexpress.com/item/1005007426800062.html) | ~₪11.66 list | 1,000+ orders, title states direction and poles. CTIA vs OMTP not stated. | I |
| **Control mic** | [3.5 mm gooseneck PC mic — 1005008634061170](https://www.aliexpress.com/item/1005008634061170.html) | ₪3.40 | **Replaces the BOYA lavalier in the brief.** Mic on tip by PC convention — the only candidate whose pinout needs no measurement. 427 orders. | pinout by convention; rest I |
| 3.5 mm solder plugs | none retrieved — search `3.5mm mono plug solder` / `3.5mm TRS plug solder` | ~₪5 | For re-terminating the Cardo mics. | — |

Correction to the brief: a **TRRS lavalier (BOYA BY-M1 class) will not work** in a 3-pole mic
jack — the jack's sleeve bridges the plug's ring-2 (ground) and sleeve (mic) and shorts the mic.
Ignore the AliExpress "wiki" page giving an EJEAS pinout ("Sleeve = Power"): it is generated filler.

Cable lengths were not retrievable for any mic; assume 25–40 cm.

### 3. In-ear monitors — REJECTED ON COST, reopened in `PLAYBACK.md`

The evidence below is kept; the purchase steps in the table are **not** the plan.

**The ≥30 dB broadband requirement cannot be met by any passive IEM, by measurement.**

- Best measured low-frequency figure found: Etymotic ER4XR, RTINGS, silicone tips —
  **21.6 dB at 20–250 Hz**, 32.8 dB "overall". The overall number is an unweighted average to
  20 kHz and is carried by ~48 dB of treble. (Values recovered from archived snapshots; RTINGS
  now paywalls them. Not re-checked.)
- Shure SE215 measured 15.1 dB bass / 24.6 dB overall against a "37 dB" claim.
  Tin HiFi T2: 2.8 dB bass. Moondrop Aria: 2.1 dB.
- Etymotic's "35–42 dB" cites no standard. It looks like an honest broadband average, not a
  wind-band figure. The one >30 dB low-frequency result (ER2XR + foam) is a single-person
  self-test.
- Helmets give essentially nothing below 250 Hz (Młyński et al. 2009), so the IEM does all the
  wind-band work. Realistic target: **15–22 dB** there.
- **No chi-fi IEM has any published isolation measurement.** Do not buy one on an isolation claim.
- AliExpress "Etymotic ER2XR" (1005009832444947, ₪361) is priced below the lowest authorised
  price for a model that is sold out officially; 24 sales. AliExpress SE215s at ₪50–94 are fakes.

**Fit conflict:** several riders report the ER2/3/4 barrel is too long and catches on helmet
don/doff; they prefer the shorter, unmeasured, fixed-cable MK5. Over-ear memory-wire cables
(SE215) are reported to fold down and pull out. Consensus shape: bullet body, cable hanging down.
(Reddit-only evidence, and read by a subagent that bypassed a bot wall to do it.)

| Step | What | Price | Why |
|---|---|---|---|
| 0 — fit test | Etymotic ER20/ER20XS earplugs, locally (Music World) | cheap | Same stem and tips as the earphones. Tells you whether a deep-insert stem survives *your* helmet before spending ₪800+. |
| 1 — if it fits | Etymotic **ER3XR**, etymotic.com (ships to IL, flat $45) | $249.99 + $45 + 18 % VAT ≈ **₪1,060** landed | In stock; sealed BA like the measured ER4XR; XR bass lift; MMCX (Etymotic-keyed); no mic. ER2XR is **sold out**. |
| 1b — budget | Etymotic **ER2SE** + a bass shelf in the app | $179.99 + $45 + VAT ≈ **₪805** | In stock; you own the audio chain, so the XR tuning is a filter. |
| 2 — if it does not fit | Shure SE215-K, local (Next-Pro ₪349 + ₪39; HeadSound ₪439) | **₪388–439** | VAT paid, 2-yr importer warranty, cheaper than importing. Accept ~15 dB in the wind band and the over-ear cable problem. |
| Tips | Comply 100-series / Etymotic triple-flange from a named seller | ~₪55 | Etymotic nozzle is ~3 mm; AliExpress tips rarely state core size. No AliExpress tip listing was retrieved. |

Returns on etymotic.com are at your cost even for warranty. No Israeli Etymotic dealer exists.

### 4. Rain — PARKED by the user (reference only)

**A bare phone on the bars with a plug in the port cannot be made reliably rainproof.** The plug
leaves the port cavity open; nothing you can stick to a phone seals it.

Two facts make it tolerable:

1. **There is an override.** AOSP's `UsbContaminantActivity` gives the "USB port disabled"
   dialog an **"Enable USB"** button that calls `enableContaminantDetection(false)`, and
   `UsbPortManager` re-arms detection only when the accessory is unplugged. I checked both files
   myself. So a trigger costs a roadside tap, and it holds until the dongle is pulled.
   *This is AOSP source, not a test on a Pixel 8* — confirm at home with a drop of water on the plug.
   There is no adb/settings/developer-option way to pre-disable it.
2. Haifa rain is Nov–Mar; sealed-pouch overheating is a summer problem. The two rigs never overlap.

Corrections to the brief: the Pixel 8 port is on the bottom edge, so in portrait it already
faces down — a right-angle adapter buys strain relief, not water shedding (it helps only in
landscape). Self-fusing tape bonds only to itself and cannot seal plug-to-phone; wrapped there it
funnels water toward the port.

| Rank | Setup | Parts | Price | Conf. |
|---|---|---|---|---|
| 1 — winter | Phone **and dongle** inside a sealed handlebar pouch; only analogue 3.5 mm leaves, through a downward PG7 gland with a drip loop | Pouch for **7.0"+ phones** (Pixel 8 150.5 mm + dongle + bend) — **no listing retrieved**; search `motorcycle phone bag waterproof`, ROCKBROS / WILD MAN | est. ₪40–70 | G |
| | | [PG7 IP68 gland, 10-pack — 1005002282004542](https://www.aliexpress.com/item/1005002282004542.html) (single: 1005005089962171, ₪1.79, 3,000+ orders) | ₪13.91 | I |
| | | [USB-C M→F extension, "4K@60" — 1005007615203710](https://www.aliexpress.com/item/1005007615203710.html) (backup 1005012118717336) | ₪3.30 | I — full wiring implied by the video claim |
| | | ePTFE vent patch against fogging — no listing; search `waterproof breathable vent M5 IP67` | ~₪10 | — |
| 2 — summer | Bare mount as now + **vibration damper** (handlebar vibration can permanently damage camera OIS) — no listing retrieved | est. ₪50 | — |
| | Optional strain relief: [USB-C 40 Gbps 90° adapter — 1005007335007568](https://www.aliexpress.com/item/1005007335007568.html) (backup 1005006840793080) | ₪3.06 | I — buy a 10/40 Gbps-rated one so all 24 pins are wired; check bend axis in photos; test both flips |
| 3 — always | Know the "Enable USB" tap. Keep the AirPods as the degraded mode. | — | — |

Two cables through one PG7 will not seal — sleeve both in one 6 mm tube and pot the ends, or
use two glands. **Rejected:** magnetic USB-C pogo adapters (corrosion, flaky data, and each
vibration dropout re-arms liquid detection); rain ponchos as the primary defence (Quad Lock's
own leaves the port area open; riders report soaked phones and ~25 % touch success).

### 5. Cabling and breakaway — all rows [S]

**No magnetic 3.5 mm breakaway exists on AliExpress** (six phrasings); Western ones are stereo-only.
The breakaway is a **plain straight plug/socket junction pulled axially**. A jack datasheet
(CUI SJ1-352XN) specifies 2.9–29.4 N withdrawal — a 10:1 spread — so **measure yours with a
luggage scale** and wear it in. A 29 N joint is not a safety device. Never a right-angle plug at
the breakaway; right-angle only at the dongle. The mic line is the critical one: the mic is
fixed to the helmet, the IEMs pull out of ears at a few newtons.

**Two cables, not one TRRS line.** The subagent's estimate (its own arithmetic, unsourced): a
shared 1.5 m ground puts the headphone signal only ~4–10 dB below your voice in the mic line —
a hard-wired echo — and the single line needs more junctions, with the mic on the outermost contact.

```
helmet: mic (25–40 cm lead) ─┐         IEMs on a SHORT 50 cm cable ─┐
                             │  ~40 cm pigtails                     │
══════ breakaway plane: sternum, inside jacket, straight M/F, sockets DOWN ══════
                             │  ~1.2 m bike side                    │
        shielded M→F ext ────┘         coiled M→F ext ──────────────┘
        right-angle plugs at the dongle only → [MIC IN] [HP OUT] → Pixel 8
```

Total run ≈ **1.6 m** per line (80 cm bar-to-chin seated, +35 cm standing, +20 cm shoulder
check, ×1.2 routing and drip loop).

| Part | Listing | Price (list) | Why | Conf. |
|---|---|---|---|---|
| Breakaway / short ext ×2 | [3.5 mm 3-pin/4-pin M–F, 0.3 m / 1 m — 1005008448830813](https://www.aliexpress.com/item/1005008448830813.html) (backup 1005003232102582, 10,772 orders) | ~₪6.24 ea | Straight plugs; 3- and 4-pole options. | I |
| Mic-line ext | [Bochara "OFC Shielded" M→F — 32858946163](https://www.aliexpress.com/item/32858946163.html) (backup 1005012768334644) | ~₪22.67 | One of only two listings in the category that claim a shield. Shortest is 1.8 m. 43 orders. "Nylon braided" is not shielding. | I |
| Headphone ext | [Coiled 3/4-pole M→F — 1005006997479477](https://www.aliexpress.com/item/1005006997479477.html) (backup 1005009695603602) | ~₪15.23 | Coil manages slack; 2,054 orders. Keep the mic line straight. | I |
| Short IEM cable | [50 cm MMCX → 3.5 mm — 1005009802723384](https://www.aliexpress.com/item/1005009802723384.html) (backup 1005004330620360) | ~₪24.38 | A 1.2 m stock cable is 3× too long for a 40 cm pigtail. **No short 0.78 mm 2-pin cable exists** — a reason to prefer MMCX IEMs. Etymotic's MMCX is keyed; generic cables may not seat. | I |
| Ferrites | [25-pc clip-on kit — 1005007612508540](https://www.aliexpress.com/item/1005007612508540.html) | ₪6.13 | Both ends of the mic line. | I |
| Heat-shrink | [3:1 adhesive-lined, by the metre — 1005012221724686](https://www.aliexpress.com/item/1005012221724686.html) (kit: 1005010547703575) | ~₪14.20 | Need 3.2 / 4.8 mm; kits skew large. Permanent junctions only. | I |
| Silicone tape | [25 mm × 1.5 m self-fusing — 1005007009632980](https://www.aliexpress.com/item/1005007009632980.html) (backup 1005007258675065) | ~₪18.22 | Over the shrink and on gland nuts. Not on the phone. 5,000+ orders. | I |
| Dielectric grease | [DC-4A — 1005012394158424](https://www.aliexpress.com/item/1005012394158424.html) (backup 1005012666578653) | ₪3.40 | Thin film in the breakaway socket; also lowers pull-out force. | I |

The breakaway junction is **not sealed** — it has to separate. Socket down, inside the jacket, greased.

## Cost

| Group | ≈ ILS | Status |
|---|---|---|
| Adapters: 2 × CableCreation + 2 probes | 102 | live |
| Mics: 3 × Cardo-style, EJEAS, gooseneck, splitter, plugs | 40 | live |
| Cabling and sealing — mic line, breakaway, ferrites, shrink, tape, grease | 77 | live |
| Cabling — headphone line (coiled ext, short MMCX cable) | 40 | hold for §3 |
| **Live AliExpress total** | **≈ ₪220 (~$72)** | soft; many card prices are promo floors |
| Rain: glands, USB-C ext, angle adapter, pouch, vent, damper (last three estimated) | 150 | parked |
| IEMs: ₪388 (SE215 local) · ₪805 (ER2SE) · ₪1,060 (ER3XR); tips ~₪55 | — | **rejected on cost** |

The adapters are ~half of the live total. One CableCreation unit plus the ₪3 probes instead of
two would bring it to ≈ ₪170; the "buy two" rule came from the brief, not from a finding.

**Customs:** the personal-import exemption is **US$75** again (raised to $150, then $130, both
revoked; last on 2 June 2026). Above it, 18 % VAT applies to the **whole** value, and AliExpress
collects it at checkout. Shipping does not count toward the threshold if itemised. The live cart (~$72) sits
right at the line and real prices will be above the promo floors, so **split it into two orders**
(e.g. adapters in one, everything else in the other).
This has flipped three times in a year — re-check before ordering.

## Open risks, ranked

1. **IEM isolation is ~15–22 dB in the wind band, not 30+.**
   *Falsified if* a motorway run with the ER-series + triple-flange/foam leaves intercom speech
   clearly intelligible at a comfortable level. If not, no better passive IEM exists to buy; the
   remaining levers are mic-side (so the far end sends cleaner speech), app-side EQ/compression,
   and helmet wind management.
2. **The deep-insert barrel may not survive your helmet.**
   *Falsified by* the ER20 earplug fit test — ten don/doff cycles, plug still seated.
3. **Mic pinout and bias.** Assumes the Cardo-style mic is a 2-wire electret and the adapter
   biases the tip. *Falsified if* the mic cable has 3+ conductors, or the adapter's mic jack shows
   no DC on the tip with a capture stream open.
4. **Rain.** Assumes "Enable USB" exists on the Pixel 8 build and holds while plugged.
   *Falsified if* a wet plug at home gives no override button, or the port re-disables without an unplug.
5. **Adapter listing is not what it says.** 7–9 orders. *Falsified on arrival* if it enumerates
   output-only. That is what the probes and the backup are for.
6. **Breakaway force too high.** *Falsified by* the luggage scale: > ~15 N after wear-in.
7. **Ignition noise on 1.2 m of unbalanced mic line.** *Falsified by* recording with the engine
   at 4–6k rpm, shielded vs unshielded extension.
8. **Pouch too short for phone + dongle.** Measure the plugged-in length before ordering.

## Arrival-day test order

1. **Adapter alone → Pixel 8.** Does Android show a USB audio device with input *and* output?
   If output-only: wrong part. Stop.
2. **Bias.** Meter on DC volts, black on the mic jack sleeve, red on tip, **with the app
   recording** (some adapters only power the mic with a stream open). Expect ~2–4.5 V. Check the
   ring too. No voltage anywhere → no electret will work on this adapter.
3. **Gooseneck control mic** into the mic jack; record in the app. This proves adapter, bias
   and capture chain. Every later failure is then the mic's fault.
4. **IEMs** into the headphone jack; confirm Android routes *input from the adapter*, not the
   phone's own mic, and that both directions run at once.
5. **Cardo-style mic, before cutting:** diode-test across the two pins — conducts one way, open
   the other. Strip the cable: **2 conductors** confirms; 3+ falsifies. The conductor continuous
   with the capsule can is ground → sleeve; the other → tip.
6. Re-terminate, plug in, A/B against the gooseneck recording.
7. **EJEAS headset** (if bought): count plug rings (3 rings = 4-pole). Find the two ~16–32 Ω
   pairs (speakers) and their common contact (ground); the contact that is diode-like against
   ground is the mic. Mic on sleeve + ground on ring-2 = CTIA, the splitter works.
8. **Extensions, one at a time**, re-recording after each, so a bad cable is identified.
9. **Breakaway pull test** with a luggage scale, both lines, after ~50 mate cycles.
10. **Wet-plug test at home:** confirm the "Enable USB" button and that it holds.
11. **Engine-running recording**, then a **motorway run**, recording both ends.

## Where the plan looks wrong

- **The 30 dB isolation requirement is unreachable**, and the Etymotic figure it was anchored
  on is an unstandardised broadband average. Buying Western gets the best available, not the spec.
  The only products with *standardised* measured attenuation are NRR-rated earplug-earphones
  (e.g. ISOtunes IT-10: NRR 29, no mic, 3.5 mm, $35) — but ISOtunes does not ship
  internationally, output is capped at 85 dB, and they are not on AliExpress.
- **A "splitter" is the wrong adapter class.** A USB-C sound card with a PC mic jack is the
  better electrical match for a hand-terminated electret.
- **The boom mic is the wrong type for a full-face helmet** — the stick-on chin-bar mic is
  what the reference vendor itself sells for closed-face helmets.
- **The lavalier control item would not have worked** in a 3-pole mic jack.
- **A right-angle USB-C adapter does not help with water** on a portrait-mounted Pixel 8.
- **Mount the phone and dongle inside a sealed pouch in winter.** That removes the liquid-detect
  failure instead of betting against it.
- **ER2XR is sold out** and the ~$90 reference price is stale ($179.99 now).
