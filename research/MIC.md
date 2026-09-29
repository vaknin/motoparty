# The helmet microphone: wireless-in-helmet vs. wired, bought locally

Researched 2026-09-21. Answers `MIC-HANDOFF.md`. **Bought 2026-09-28: one Lark A1 Duo Mini USB-C
(Ivory, ₪175) — §2.2 route; gates S1–S4 (§6) now run on the real unit.** No code was touched.
Supersedes the wireless paragraph of `PLAYBACK.md` §5.5 (rewritten to point here) and the sourcing
conclusions of `PLAYBACK.md` §5.3–5.4.

**Updated 2026-09-27** after the baseline ride (the user's verdict on the AirPods mic: "sounds
terrible whenever speed is >0") with a second research pass: **one** Lark A1 kit in Stereo mode can mic both riders
(§2.2, rewritten), the A1's max SPL is now documented (§3), the 5 GHz hotspot mitigation does not
exist on this Pixel (§2.5), a fit budget replaces the eraser guess (§5), and the stereo gates are
tests S1–S4 (§6). Five Opus subagents, same rules as below; blocked this time: reddit.com,
dpreview.com, zhihu.com, bhphotovideo.com, sweetwater.com, manuals.plus, help.rode.com,
store.dji.com (region), apps.apple.com (429).

## Status in one paragraph

**Both directions are viable, both can be bought in Israel and returned, and neither is "buy and
forget".** The surprise of this pass is that the wireless route is the *cheap* one: a **Hollyland
Lark A1** kit is ₪175–299 locally (US$59.90 direct) and serves both riders, while the only wired
digital lavalier with a sane cable that Israel actually stocks — the **Sennheiser XS Lav USB-C**,
2 m — is ₪312–414 *each*. The battery question that was expected to decide it does not: the
transmitter runs **9 h** against the AirPods' measured **5 h 43 m**, so the AirPods run out first
either way. What actually decides it is **whether an 8.8 mm transmitter plus a windscreen fits
between your chin bar and your lips**, and that can be tested today with an eraser, for free.
**Recommendation: wireless (Lark A1) as primary, one wired XS Lav as the backup and known-good
control — after the zero-cost fit test.** A new risk applies to *both* routes and was in no earlier
document: **capsule overload at mouth distance** (§3).

*2026-09-27:* the primary is now **one Lark A1 Duo (₪175, Ivory, new)** serving both riders
through the receiver's Stereo mode — rider on one channel, passenger on the other, both into the
Pixel. It halves the cost again, removes HFP from *both* phones (no 1–1.7 s switch at talk start)
and makes the passenger's iPhone connector irrelevant. Its one gate that research cannot close is
whether the Pixel hands the app two distinct channels (§2.2, S4).

*2026-09-28:* **bought** — the Ivory Duo Mini USB-C above, ₪175. Next: the gap measurement (§5) if
not yet done, then S1–S4 and A1–A4 (§6) inside the return window.

*2026-09-28, evening — S1, S2, S3 **pass** on the real unit* (WAVs in `captures/lark-s*.wav`,
gitignored). **S1:** VID:PID `3547:0407`, UAC1, one input only, **48 kHz stereo, S16_LE or
S24_3LE**, `MaxPower 100mA`, plus a HID interface (buttons; the vendor app configures the RX over
it — its manifest has `com.hollyland.usbmic.core.USBHIDService`) and a vendor-class interface; no
output. Enumeration took ~12 `error -71` retries on first plug (loose plug?), fine after. Default
Mono = L and R **bit-identical**. **S2:** Stereo set in the vendor app (Android package
`com.hollyland.larkc1` v3.0.7, "LarkSound"/HollyAudio; firmware updated first), reverb off; then
app force-stopped, RX unplugged, TXs power-cycled, RX into the laptop (no app): **Mic1 → L only,
Mic2 → R only**, 21–47 dB separation, L/R correlation 0.03 — **Stereo persists in the RX** (one
reset, not the second the gate asks for; the user skipped it). **S3:** Pixel stock Camera video →
AAC 48 kHz stereo, Mic1 on L, Mic2 on R, 17–32 dB separation — **the Pixel HAL passes stereo**.
Found on the unit: the app's **Mic Recognition** switch lights TX1 **pink/purple**, TX2 **yellow**
(off, both are blue); **Mic1 = pink = L = rider's helmet** (user will add a sticker). ENC is one
state for the whole kit — a press on either TX toggles both, blue ↔ solid green; **powers on OFF**
(resolves "ENC state at power-on"). App also has EQ (Equalization / Low / Bright), Mic mute,
Schedule power off (**15 min** default — per the ⓘ, only while a TX is **unpaired**; no effect
on a ride),
Indicator light switch. Gain is 5/6 (default). **Levels are hot:** at a hand's width, normal
counting hits full scale (1.00) several times (S1 and S3), so at 2–3 cm in the helmet it will clip —
drop gain to ~3 and run A2. Noise floor −49 dBFS with ENC off, −69 with ENC High. The protective
film on the TX charging pads must be peeled off or the dock does nothing. The user asked why not
ENC High: the answer given was that it is a ride A/B (Off / Low / High on the same stretch), not a
lab call.

*2026-09-28, late — S4 **passes**, on every source.* A debug "USB stereo test" in the app
(`android/…/audio/UsbStereoProbe.kt`; Settings, bottom; not the talk path) recorded 15 s per source
at 48 kHz `CHANNEL_IN_STEREO` 16-bit, `setPreferredDevice(usb)`, no effects, in `MODE_NORMAL`. For
`UNPROCESSED`, `MIC` and `CAMCORDER` alike: routed `usb_device` "USB-Audio - Wireless Microphone",
`getFormat()` 48 kHz **2 ch**, the flinger's input thread 48 kHz 2 ch (left, right), **no
`voip_tx`**, `mode 0` and media out `[bt_a2dp]` before, during and after — and the user heard the AirPods
music play smoothly through all three clips. Pink TX counted first →
L-louder windows, yellow second → R-louder, **15–39 dB apart**, whole-clip correlation −0.01 to
−0.02, 0.3–0.4 % identical samples (on-phone verdict and the laptop's `lr.py` agree). Noise floor
between words −55 to −58 dBFS on all three. The Pixel reports
`PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED = false`, yet `UNPROCESSED` delivered the same clean
stereo — on a USB input no source seems to add processing; **any of the three will do**, `MIC` is
the conservative pick. Peaks hit 1.00 again at gain 5. WAVs `captures/usb-{unprocessed,mic,
camcorder}-20260928-*.wav` (gitignored). **The Pixel side of §2.2 is closed.** Gain then set to 3/6 in the vendor app.

*2026-09-28, late — A2 **passes** at gain 3* (`captures/lark-a2-shout-gain3.wav`, laptop,
S24_3LE, gitignored). RX moved Pixel → laptop and both TXs power-cycled first, so gain 3 **persists**
without the app (normal speech at 2–3 cm now peaks 0.1–0.34, where gain 5 hit 1.00 at a hand's
width). Pink TX two fingers from the mouth corner, yellow across the room. Normal: RMS −32…−40 dBFS,
peaks ≤ 0.34. Raised: −23…−28, peaks ≤ 0.47 (**6.6 dB headroom**). Loud/shouting: −13.5…−22, peaks
0.74–0.999, **one flat top in ~12 s of shouting** (30 samples ≈ 0.6 ms at 36.0 s) plus 2 samples at
38.5 s. **No pumping:** the floor between words and in the silence after the shouts (−66…−68 dBFS)
equals the floor before (−67…−69). Yellow picked the shouts up acoustically ~30 dB below pink.
Silent floor L −68 dBFS vs R −61.5; gain is one setting for both mics (user), so the 6.5 dB is
the room at yellow's spot, not the kit. If wind
adds level at speed, gain 2 is the next step.

*Same evening, user decisions:* the gap measurement (§5 step 0) is **waived** — the user has the
helmet and says the TX with its furry windscreen fits. The 30 min **A3 soak is skipped** as too
long; its question (does 2.4 GHz from the Pixel's hotspot and A2DP, centimetres from the RX, cause
dropouts?) moves to the ride recording, which exercises exactly that configuration.

*Helmet test, gain 3* (`captures/lark-helmet-gain3.wav`, laptop 1 m away, pink TX with fur inside
the chin bar at the mouth corner, visor down, yellow off, ENC off). Normal speech: words at −28…−39
dBFS RMS, peaks 0.1–0.47, **one** clip (19 samples ≈ 0.4 ms at 9.5 s). Raised: −21…−27, peaks ≤
0.52, clean. Loud: −10…−18, peaks 0.6–0.999, clips at 37.0 s (22 samples), 37.5 (2), 42.0 (7) —
≈ 0.6 ms in ~12 s of shouting. **Breath: no pops** — four mouth exhalations peaked 0.05, 25+ dB under
speech; the fur works. Counting while turning the head came out ~10 dB quieter than the first
normal counting (softer voice or the mouth moving off the TX — not known). Silent floor −63…−68 dBFS.
So the helmet behaves like A2 at 2–3 cm: fine at gain 3 indoors, shouting at the edge. **Recommended
for the ride: gain 2** — wind adds its own level at the capsule, clipping happens in the TX before
anything can undo it, and the floor has 40+ dB to spare. **Before the ride:** read the ⓘ of "Schedule
power off" (15 min) — if it powers a TX off after 15 min of silence, it will die mid-ride; and a long
recording on the Pixel (the probe only does 15 s clips).

*EQ for the ride:* the app offers Equalization / Low / Bright; it stays on **Equalization**, taken
to be the default (the app does not say it is flat). Low would lift the band wind lives in; Bright is
a treble lift that the Pixel app can apply in software later, on a capture that was not shaped at the
source. One variable per ride: noise cancelling Off vs High.

*Desk check of the long recording (23:49, `captures/usb-ride-20260928-234945.wav`), gain 2, pink
TX only:* 47.7 s, **0 dropped**, the phone was locked 2.7 s in (`Going to sleep due to power_button`)
and the recording ran on. With yellow off, **R is exact digital zero** (so a TX that is off, and
probably one that loses the link, reads as zeros, not noise). Noise cancelling off → on by a TX
press at ~27 s: the background between words went from −76 to **−92…−94 dBFS** (≈ the 16-bit
floor), speech level unchanged; L carries exact-zero runs up to 82 ms in the on part. So ENC gates
to near-silence between words: **on the ride, listen for clipped word starts with ENC on**, and zeros
are no dropout signature while it is on. Which level (Low or High) the press lands on is not
confirmed. AirPods were not connected (media out `speaker`).

*Mounting in the helmet (2026-09-29):* the rider's helmet is an **Arai Quantic**. The A1 TX has no
clip, only a magnet, and the chin bar lining has no opening to hide the magnet behind, so the mount is
adhesive. First ride: cloth tape on the fabric lining at the corner of the mouth, clear of the fur,
the button and the chin vent's airflow (vent closed), plus a thread tether taped to a second spot.
Then **hook-and-loop tape cut to shape**: [AliExpress 1005008938790125](https://www.aliexpress.com/item/1005008938790125.html),
Black 1M, 20mm, ₪5.97 (Choice, 4.7, 10,000+ sold, 2026-09-29; both halves in the pack; thickness and
glue unstated), in the user's cart. Round dots were rejected: the TX back is 30 × 16.3 mm with
**three charging contacts at the bottom**, leaving ~25 × 15 mm clear, and a cut piece **13 mm wide ×
~18–22 mm** (to the fur's edge) grips ~230–290 mm² against 177 for a 15 mm dot, with no overhang to
snag fur or hair. Fitting: round the corners; clean the TX back with alcohol, press 30 s, wait a day
before riding; hook side on the TX, straight onto the lining if the fabric holds it (half the
thickness of a mated pair), else a slightly larger loop piece on the lining. Never on bare EPS, no
glue. Whether the piece still lets the TX into its charging dock (+~1.5 mm if it's a slot) is
unchecked.

*The RX's HID interface, read on the laptop (2026-09-28, `/sys/class/hidraw/hidraw1/device/
report_descriptor`, no root):* report 3 = Consumer Control **input** (16 bits: mute, volume ±,
play/pause, next/previous and similar — the RX can send media keys); report 6 = vendor page `0xFF52`,
159-byte **feature**; report 8 = vendor page `0xFF53`, 63-byte **input + output** — a command/response
channel; report 5 = vendor page `0xFF12`, 63-byte feature. The vendor app's settings (Stereo, gain,
ENC, mute, EQ) almost certainly travel over report 8, but its command bytes are **undocumented**;
learning them means decompiling `com.hollyland.larkc1` (no decompiler installed yet). `/dev/hidraw1`
is root-only on the laptop; on Android an app reaches it through `UsbManager` after a USB permission
prompt.

## How to read the grades

**M** measured by a named third party · **D** documented (maker spec, manual, regulation, retailer
page actually read) · **R** reported / search-snippet only / relayed without the page being opened ·
**I** inferred, reasoning shown · **G** guess · **U** unknown.

**Method.** Three Opus subagents (wireless hardware; USB/latency/RF; Israeli retail), each told
explicitly not to circumvent bot walls, spoof user-agents, set region cookies, spawn helpers or
write to the repo. All three reported their blocks instead of working around them: `ksp.co.il`,
`bhphotovideo.com`, `help.rode.com`, `fcc.report`, `fccid.io`, `xdaforums.com`, `insta360.com`,
`manuals.plus` → **403, not read**; `j-sale.co.il` (Yugend), `cameracity.co.il`, `halilit.com`,
`video.org.il` → JS interstitial, **not read**; AliExpress item pages **not attempted**. I then
read three Israeli retailer pages myself (§2.1) through a fetch tool that summarises with a small
model — treat those as **D, one step removed; re-check the page before paying**. Where I disagree
with a subagent, it says so.

---

## 1. Head-to-head

| | **A — wireless, TX inside the helmet** (Hollyland Lark A1) | **B — wired USB-C lavalier** (Sennheiser XS Lav USB-C) |
|---|---|---|
| **Cost for two people** | **₪175** (one Duo, Stereo mode, §2.2) – ₪299 (Combo, Zrazi) – ₪350 (2 × Duo USB-C, Ivory); US$59.90 direct, under the $75 line | **₪624** (Askol, pre-order) – **₪828** (Turtle); ~₪666 at Yugend (**R**) |
| **Cable helmet → phone** | none | 2 m over a ~1 m run; **no breakaway** (USB-C retains at 8–20 N, `PLAYBACK.md` §5) |
| **What must be charged** | TX (65 mAh, 9 h) + its case. Receiver is bus-powered | nothing |
| **What breaks / wears** | TX lost or dropped when the helmet comes off; Li-ion cell in a hot helmet (rated to +60 °C, **D**); no IP rating, helmet condensation (**U**); dongle in the phone port | the cable: snag, flap, fatigue at the helmet exit and the plug; a snag loads the phone port |
| **Presents as class-compliant USB audio, no app** | **A1: "UAC功能" on Hollyland's CN download page, and works with no app installed (D, 2026-09-27)**; CN compatibility list includes Pixel 7 and 8 Pro, not the plain 8 | Maker lists **Android 8.1+**, Linux 5.4+ (**D**); sensitivity quoted in dBFS, so an ADC is present (**D/I**) |
| **Native format** | 48 kHz / 24-bit (**D**, A1 manual); Stereo/Mono channel mode, **default Mono** (**D**); descriptor channel count **U** (the only Linux report is a `plughw` mono capture) | **8 / 16 / 32 / 44.1 / 48 kHz** (**D**) — 16 kHz is the app's own rate |
| **Added latency** | ~25 ms (**M**, MAONO T5 by oscilloscope; A1 **U**) — ~10 % on top of 150–300 ms of A2DP, not material | ~0 |
| **RF** | a 2.4 GHz link with its receiver centimetres from the phone's Wi-Fi/BT antenna (§4) | none |
| **Processing you do not control** | TX DSP: ENC (hardware toggle, LED), some AGC/limiting even with ENC off (**I**), lossy radio codec | none — capsule → ADC → USB |
| **Overload at the mouth** | **A1 120 dB SPL** (**D**, manual; leaflet says 128); motovloggers report in-helmet overdrive (**R**) | **hard digital clip at 110 dB SPL** (§3, **I from two D figures**) |
| **Physical fit** | **the deciding unknown** — 30 × 16.3 × 8.8 mm, 8 g + Velcro + fur, in front of the lips | trivial — 10.5 mm capsule, 17 g total |
| **Unverified on a Pixel 8** | enumeration; **two distinct channels reaching the app** (§2.2); Stereo persistence; RF desense; ENC power-on default; fit | enumeration; overload |
| **Count of open unknowns** | five, all testable at home within an hour of unboxing | two, plus one *known* liability (the tether) |

---

## 2. Direction A — wireless, transmitter in the chin bar

### 2.1 The candidate: Hollyland Lark A1

| | | grade |
|---|---|---|
| Transmitter | **30 × 16.3 × 8.8 mm, ~8 g**, flat slab | D (manual) |
| Attachment | **magnet only — the spring clip was deleted on this model.** Two magnets in the box | D / R |
| TX battery | **9 h ENC off / 6.5 h ENC on**, 65 mAh, charge < 1.5 h | D (vendor lab figure; nobody has measured it) |
| Case | 460 mAh, "> 2 charges for 2 mics" → ~5–6 refills of *one* TX; vendor claims 54 h total | D / I |
| Top-up without the case | yes — a USB-C charging dock is documented | D |
| Transmits while charging | | **U** (case powers the TX off, so probably not — I) |
| 3.5 mm lav input on TX | **no** | D |
| Windscreen | 2 furry windscreens in the Combo; Ivory's Duo Mini lists "מגני רוח (פרוות)" — **fur** (the earlier "foam" was the fetch tool's summary, not the page). Official spare fur exists | D |
| Noise cancelling | single click on the TX (or RX) toggles it; solid green LED = on; level Low 10 / Medium 15 / High 20 dB, **default High**, app-only. Hollyland's own FAQ acknowledges voice-level fluctuation with it on (fix: firmware V1.0.2.08) — **ride with it off or Low** | D |
| Gain | 6 levels, default 5, app-only | D |
| Reverb | **must stay off in Stereo** — Hollyland FAQ: reverb turns the channel mode into a fixed spatial two-channel effect, mixing the TXs | D |
| ENC state at power-on | | **U — check the LED on arrival** |
| Receiver | USB-C or Lightning, 34 × 16.3 × 9 mm, 5.9 g, **bus-powered**, USB-C pass-through charging port | D |
| Operating temperature | **−10 to +60 °C** (siblings and DJI stop at +45 °C) | D |
| Water / sweat rating | none stated | U |

**Where to buy it — this route is returnable too, which the handoff did not expect:**

| Retailer | SKU | Price | Notes | grade |
|---|---|---|---|---|
| [Ivory](https://www.ivory.co.il/catalog.php?id=162262) | Lark A1 **Duo Mini** USB-C (SKU `HOLLYLAND-A1-DUO-MINI`): 2 TX + 1 USB-C RX + 2 fur | **₪175**, new, in stock (₪148 in Eilat) | National chain with Haifa-area branches; 1-yr Ivory warranty. **Not** a clearance item — the "מציאון" text is site template, shown only when the title contains it (raw HTML read 2026-09-27). The page lists the contents twice and they disagree: charging **dock + carry bag** vs. charging **case** — probably the "Mini" is the case-less variant (**I**); check TX is 30 × 16 × 9 mm at the counter | D |
| [Zrazi](https://www.zrazi.co.il/product-page/%D7%9E%D7%A2%D7%A8%D7%9B%D7%AA-%D7%9E%D7%99%D7%A7%D7%A8%D7%95%D7%A4%D7%95%D7%9F-%D7%90%D7%9C%D7%97%D7%95%D7%98%D7%99%D7%AA-hollyland-lark-a1-combo) | Lark A1 **Combo**: 2 TX + USB-C RX + Lightning RX + case | **₪299**, in stock, ≤ 5 days | Tel Aviv (Brodetzky 43); 1-yr official-importer warranty. The page's contents list says "1 receiver" — Hollyland's Combo has two; **confirm before ordering** | D⁻ |
| [ABS Online](https://www.absonline.co.il/%D7%9E%D7%A2%D7%A8%D7%9B%D7%AA-%D7%9E%D7%99%D7%A7%D7%A8%D7%95%D7%A4%D7%95%D7%9F-%D7%90%D7%9C%D7%97%D7%95%D7%98%D7%99%D7%AA-hollyland-lark-a1-combo) | Combo (both receivers + 2 furries listed explicitly) | ₪665 | Self-described official importer; more than double Zrazi | D⁻ |
| [Hollyland store](https://store.hollyland.com/products/lark-a1) | Combo | **US$59.90**; extra USB-C RX $14.90, Lightning RX $19.90, TX $19.90 | Combo + USB-C RX = $74.80 — **20 cents under the VAT line**; shipping to Israel **U** | D |
| AliExpress (Hollyland official store) | Combo | ~$69.99 | **R** — a search-engine summary, no page read | R |

### 2.2 One kit for both riders (preferred, 2026-09-27) — or two

**One Duo kit can serve both, if the receiver runs in Stereo.** RX in the Pixel; TX1 in the rider's
helmet, TX2 in the passenger's. The Pixel sends the rider's channel over Opus to the iPhone, which
becomes playback-only (A2DP), and plays the passenger's channel locally to the rider's AirPods
(A2DP, the split config of `PLAYBACK.md` §4). Neither phone uses HFP. **Mono is a failure**: the
passenger would hear their own voice back ~260 ms late.

| claim | grade | source |
|---|---|---|
| A1 has a Stereo / Mono channel mode, **default Mono**, set only in LarkSound (TX and RX on the phone). The RX button does pairing, ENC, TX1/TX2 mute — no channel control | D | manuals EN/DE/FR/JA/KO/PL/CN |
| In Stereo the app shows separate MIC1/MIC2 meters; the CN download page lists "UAC … 支持录制单声道和立体声" | D | moma-faq.com 3151b; hollyland.com.cn/download/lark-a1 |
| Stereo reaches USB hosts: CN compatibility list marks only Huawei P60 "mono only"; DJI Osmo Action 4 / Pocket 3 ✓ stereo | D / I | moma-faq.com a1a1 |
| Reviewers (JP, DE, EN) and users report Mic1 → L, Mic2 → R; none call it fake | D / R | ikusugimoto.com, beyondpixels.de, mashaudio.com, redditrecs |
| **TX1 = L** for the A1 specifically | **I** — documented only for Lark 150 / Max; the app can swap anyway | |
| Stereo setting stored in the RX (survives unplug, other host, no app) | **U**; **I** yes — it is listed working on DJI cameras, which cannot run the app | |
| Siblings are not a guide: Lark M2's V1.0 manual says the mobile RX is mono-only (later tests show stereo); Lark Max / M1 Pro UAC is mono-only | D | moma-faq.com b6f73 |

**The gate research cannot close — does the Pixel give the app two channels?**
- **`VOICE_COMMUNICATION` can never deliver it.** The policy adds `AUDIO_INPUT_FLAG_VOIP_TX`, and
  the Pixel 8's only matching input port (`voip_tx`) is mono (**D**, `AudioPolicyManager.cpp`
  `getInputForDevice` / `getInputProfile`; shiba `audio_policy_configuration.xml`). The app must
  move to `UNPROCESSED` or `MIC` — which `PLAYBACK.md` §4 already requires.
- **Field reports are mixed (R):** a Pixel 7 with a Lark A1 Mini Duo gets true L/R in the stock
  Camera but only the left mic in Hollyland's app and other recorders
  ([thread](https://support.google.com/pixelphone/thread/420511906), text not independently read);
  Pixel 8 Pro and 7 Pro get mono in Open Camera, stereo in the stock camera
  ([thread](https://sourceforge.net/p/opencamera/discussion/general/thread/e29e5ec88f/)). So stereo
  reaches apps on Pixels; which source/config a third-party app needs is **U**. Tests S3–S4.
- Last resort if every source is mono: a userspace UAC driver over `UsbManager` (what USB Audio
  Recorder PRO does, **R**) — high effort.

**What one kit gains over two:** ₪175 instead of ₪350; no HFP on either phone, so the measured
0.9–1.7 s SCO link-up at every talk start is gone (M, project benches) and full duplex is free;
passenger → rider skips Opus, Wi-Fi and the jitter buffer (≈290 ms one-way vs ≈375 ms with a second
kit, **I**); factory pairing makes the cross-pairing worry below moot; the passenger's iPhone
connector stops mattering. Latency per direction ≈260 / ≈290 ms vs ≈195 ms on today's SCO path —
all inside G.114's 400 ms (**I**, stage by stage: link 15–30, USB capture 15–40, Opus 26.5, Wi-Fi
~10, jitter 40, iPhone A2DP ~126 **M** / Pixel A2DP 150–300).

**What it loses:** one plug is a single point of failure for both directions (the app should fall
back to the AirPods/SCO path when the USB input disappears); a lost Stereo setting reverts to Mono;
the passenger's TX reaches the phone through the rider's torso (~19 dB, still ~15–25 dB margin at
1 m, **I**); no spare TX; the Duo Mini may have a dock, not a case (§2.1). At stops with visors
open the rider's mic may pick up the passenger acoustically (**I**).

**If a gate fails:** DJI Mic Mini 2 with the **camera** receiver — Stereo is a double-press on the
RX with a cyan LED, so it can be set and checked with no app (**D**, Mini 2 manual); ₪370–399
(**R**, Zap; official importer Bug / Hashmal Neto). TX 13.5 mm thick vs. 8.8; the RX is a 17.8 g
battery unit, and whether the USB-C phone adapter is in the box is disputed — **confirm before
buying**. Otherwise two kits, below.

#### Two kits

The standard SKU is 2 TX + 1 RX, which serves one phone. Two phones need **two receivers**:

- **Passenger's iPhone is Lightning (14 or earlier):** one **Combo** — it is exactly 2 TX + USB-C
  RX + Lightning RX. ₪299 (Zrazi).
- **Passenger's iPhone is USB-C (15 or later):** **two Duo USB-C kits, ₪350** (Ivory). This is
  better than Combo + a loose receiver: each person gets a complete, factory-paired kit, their own
  case, and **a hot-spare transmitter** — which is also the honest answer to the battery question.

**An unknown the subagents missed (I):** in one Combo box, both transmitters ship paired to *both*
receivers, because the product is designed for one person moving between phones. For us each
receiver must hear **only its own rider** — otherwise the passenger's voice arrives in the rider's
capture stream. Whether the A1 can be re-paired 1 TX ↔ 1 RX, and what two live receivers do with a
shared pair, is **U**. If the receiver delivers TX1 = left / TX2 = right, the app can take one
channel and the problem disappears; if it mixes to mono, it does not. Two separate Duo kits
sidestep the question entirely. **Test A5.**

### 2.3 Battery — the numbers that were asked for first

| item | runtime | grade |
|---|---|---|
| Lark A1 TX, ENC off | **9 h** | D (vendor) |
| Lark A1 TX, ENC on | 6.5 h | D (vendor) |
| DJI Mic Mini TX, for comparison | 11.5 h | D |
| BOYA mini 2 TX | ≥ 6 h, ~4.5 h with NR | D / R |
| **AirPods Pro 2, ANC on** | **5 h 43 m** | **M** (SoundGuys, `PLAYBACK.md` §2.5) |
| Receiver's draw on the phone | MAONO T5: **16 mA** (**M**) ≈ 1.4 % of a Pixel 8 over 4 h; RØDE rated 100 mA ≈ 9 %. A1: **U** | M / D / U |

**The transmitter is not the binding battery; the AirPods are, by three hours.** Any stop long
enough to put the AirPods in their case is long enough to put the TX in its case. Discount the 9 h
by a third for cold, age and vendor optimism and it is still 6 h. What wireless genuinely adds is
**one more thing to remember to charge the night before** — a process cost, not a range limit. With
two Duo kits there is also a spare TX in the pocket.

### 2.4 Latency, and the format the receiver presents

~25 ms measured on a comparable system (**M**, Gough Lui's MAONO Wave T5 bench review, oscilloscope;
AI noise reduction adds ~2 ms). Not material against A2DP's 150–300 ms.

Every receiver for which a real USB descriptor dump exists offers **only 48 kHz / 2-channel /
24-bit**. A subagent called opening `AudioRecord` at 16 kHz mono on such a device "the classic
failure mode" and advised opening 48 kHz stereo and resampling in-app. **I would grade that I, and
I am not convinced**: AudioFlinger's record thread normally does rate and channel conversion
between the HAL stream and the client. It is a five-minute test either way (A1), and the app's
capture path is going to be rewritten for `UNPROCESSED` regardless (`PLAYBACK.md` §4).

*2026-09-27:* both were half right. AudioFlinger's `RecordBufferConverter` does resample and
passes stereo → stereo through (**D**), so 16 kHz opens. But for the one-kit plan the app needs
**both channels**, so open **48 kHz stereo** and split/decimate in-app; Opus is native at 48 kHz.
On a Pixel 8 USB input lives in Google's closed AoC `primary` HAL, not AOSP's `usbaudio` — its
ports are 48 kHz 16-bit mono/stereo, `voip_tx` mono only (**D**, shiba config; whether the
shipping AIDL build uses that XML is **I**).

### 2.5 2.4 GHz coexistence — real, not theoretical, and cheap to defuse

- **What the radios are.** DJI and Insta360 publish GFSK 2 Mbps, 2.400–2.4835 GHz, < 20 dBm —
  Bluetooth-class radios with proprietary protocols (**D**). Hollyland explicitly claims adaptive
  frequency hopping for the Lark line (**D**, vendor claim; FCC filings were blocked, so **no
  filing-grade confirmation exists for any of them**).
- **The makers' own position** (DJI, **D**): these systems are "susceptible to Wi-Fi congestion";
  move nearby Wi-Fi to 5 GHz.
- **Two kits 50 cm apart:** low risk. RØDE documents four co-located kits (**R**, snippet).
- **Helmet and head in the path:** at 1 m a link built for 200 m has ~40–50 dB of margin; body
  loss of 20–40 dB still closes (**I**).
- **The part that is genuinely unusual here (I, not measured by anyone):** the receiver sits in the
  phone's USB-C port, in the *near field* of the phone's own Wi-Fi and Bluetooth transmitter.
  Frequency hopping protects against co-channel collisions; it does not protect a receiver front
  end from being desensitised by a transmitter centimetres away. Nobody has published a test of a
  2.4 GHz lav receiver plugged into a phone that is simultaneously running a hotspot. **U.**
- ~~**Mitigation, free:** force the hotspot to 5 GHz.~~ **Not available on this Pixel** — the
  setting says "not available in your country" (`HANDOFF.md` §Hotspot, measured). The hotspot is
  2.4 GHz, so the RX sits centimetres from a live 2.4 GHz AP *and* A2DP. Hollyland's own advice is
  2–3 m from phones (**D**, vendor blog); nobody has tested a phone-plugged receiver next to the
  phone's own hotspot (**U**). **Test A3 is therefore mandatory, not a formality.**

*Rejected advice:* two subagents independently suggested a short USB-C extension to move the
receiver off the port. `PLAYBACK.md` §5 already established that USB-C receptacle extensions are
outside the Type-C spec and often fail to forward CC. Do not adopt it without testing the specific
cable; a 5.9 g dongle is not much of a lever.

### 2.6 What people who tried it report

- **CanyonChasers** (motorcycle site, 2026, Insta360 Mic *Pro* — a larger TX): tried it inside the
  helmet and **advises against** — "squishes my nose", and proximity to the mouth "overdrives and
  the audio tears into distortion". **R.**
- **motovlog.com**: the dominant practice is a *wired lav in the helmet with the TX in the jacket*;
  close placement overdrives; fur is mandatory at speed. **R.**
- One Lark M2 in-helmet test is reported as working with wind well suppressed. **R, video not
  verified.**
- **None of the compact transmitters has a 3.5 mm lav input** (**D** for A1, DJI Mic Mini, Lark
  M2/M2S). The handoff's fallback — TX on the collar, short lav into the helmet — needs the larger
  bodypack class (DJI Mic 2, RØDE Wireless GO II, Lark Max) at roughly double the money, and it
  gives up the whole point of direction A.

### 2.7 Why the Lark A1 and not the others

| | verdict |
|---|---|
| **DJI Mic Mini** | best documented, 11.5 h, hardware ENC switch, 120 dB max SPL — but the TX is 16 mm thick (the wrong dimension to be generous with), two 1+1 kits are $118–137, and in Israel only the 2 TX + 1 RX box was found (₪695, **R**). **The backup *within* A if the A1 overloads** |
| BOYA mini 2 | only integral spring clip, 5 g — but 4.5–6 h and foam, not fur. ₪299 for 1+1 at Turtle is the older BY-WM3T2, not this |
| Lark M2 / M2S | fine, strictly dearer than the A1; M2S charges only in its case |
| MAONO Wave T5 | the only one with **M**-grade USB descriptors, draw and latency — but no 1+1 SKU, ~$200 for two |
| RØDE Wireless Micro | 135 dB max SPL, but TX charges only in the case, no 1+1 kit, > $200 |
| Insta360 Mic Air | no charging case, $176 for two, short plug fouls phone cases |
| Lark M1 | **disqualified** — analogue receiver, and the Pixel 8 has no analogue audio mode |
| AliExpress generics ($2–15) | no verifiable spec of any kind; likely fixed AGC/ENC with no off switch (**G**) |

No clip on any of them is going to close on 10–15 mm of chin-bar padding (**I** — no maker
publishes a jaw opening). **Plan on adhesive hook-and-loop on the flat back**, which is why the
A1's missing clip costs nothing.

---

## 3. A risk common to both routes: overload at the mouth

This was in no earlier document and it is the one real engineering finding of this pass.

The XS Lav's sensitivity is **−16 dBFS/Pa** and its stated max SPL is **110 dB** (both **D**).
Those are the same fact: 1 Pa is 94 dB SPL, so 0 dBFS lands at 94 + 16 = **110 dB SPL**. It is a
fixed-gain converter, and **110 dB SPL is a hard digital ceiling** — Android exposes no USB input
gain, and no DSP in the app can undo clipping that happened in the plug.

Speech, by ANSI S3.5 vocal effort at 1 m — normal 62, raised 68, loud 75, shout 82 dB SPL (**D**) —
scaled by inverse square to **5 cm** (+26 dB; near-field, so **I**), with a 12 dB crest factor:

| effort | level at 5 cm | peaks | vs. 110 dB ceiling |
|---|---|---|---|
| normal | 88 | 100 | 10 dB clear |
| raised | 94 | 106 | 4 dB clear |
| **loud** | 101 | **113** | **clips by 3 dB** |
| shout | 108 | 120 | clips by 10 dB |

At 2.5 cm everything moves up another 6 dB. Add whatever low-frequency turbulence reaches the
capsule — 108 dB per third-octave at the *ear* at 120 km/h (`PLAYBACK.md` §1.1); the level behind
the chin curtain is **U** — and note that the app's planned 150–200 Hz high-pass acts *after* the
converter, so it cannot rescue a clipped input. Only the **furry windscreen (20–25 dB)** and
**position** act before it.

Mitigating: the rider has ANC earbuds in, which reduces the Lombard reflex, so "raised" is more
likely than "loud". Occasional 3 dB clipping of speech peaks is audible but not unintelligible.

For direction A the A1's ceiling is **120 dB SPL** (**D**, manual; the leaflet says 128 dB), with
"auto-limiting clip protection" (vendor claim). Scaled the same way (**I**, and the inverse-square
law overstates the near field): raised-speech peaks are ~110 dB at 3 cm and ~114 dB at 2 cm; loud
speech reaches ~120 dB at 1–2 cm. So **keep the capsule ≥ 2–3 cm from the lips** and it clears.
The field reports of in-helmet overdrive say the limit in practice is reached. The A1's gain is
adjustable (6 levels, default 5), in the vendor app only, and whether it persists is **U**.

**Consequences:** mount the capsule at the **corner of the mouth, off-axis, as far back on the chin
bar as the helmet allows**, not dead centre; fur is mandatory, not optional; and **the shout test
(A2 / B2) is a purchase gate for whichever microphone is bought.**

---

## 4. Direction B — wired, bought in Israel

### 4.1 The candidate: Sennheiser XS Lav USB-C (P/N 509261)

It is the only wired, digital, USB-C lavalier of acceptable length that Israeli retail actually
stocks. **2.0 m** captive cable, omni, 70 Hz–18 kHz, sample rates 8/16/32/44.1/48 kHz, bit depth
**U**, 17 g, 10.5 mm capsule, foam windscreen + clip + pouch in the box, no right-angle adapter,
2-year maker warranty (all **D** — Sennheiser, Thomann, Askol). Documented OS list: Windows 10+,
macOS 10.15+, **Linux kernel 5.4+, Android 8.1+**, iPadOS 13+ (**D**). Omni is acceptable on
`PLAYBACK.md` §5.2's reasoning. No third-party measurement exists; SoundGuys' review reports a
"technical snag" and publishes no data. **Nobody documents it on a Pixel 8.**

> **Trap:** the **XS Lav *Mobile*** is the 3.5 mm TRRS version, ₪270 in the same shops, and is
> useless on a Pixel 8. The right box says **USB-C**.

| Retailer | Price | Stock | grade |
|---|---|---|---|
| [Askol](https://www.askol.co.il/product/%D7%9E%D7%99%D7%A7%D7%A8%D7%95%D7%A4%D7%95%D7%9F-%D7%93%D7%A9-sennheiser-xs-lav-usb-c/) — self-described official importer | **₪312**, free shipping > ₪350 | pre-order, ~2 left, 3–6 days | D |
| Yugend / j-sale.co.il — **reported Haifa branch, HaHistadrut 20** | ~₪333 + ₪29 | U | **R — snippet only; the site would not load. The phone number relayed for it has an 03 (Tel Aviv) prefix, which is odd for a Haifa branch. Confirm the branch exists before driving there** |
| [Speed of Sound](https://www.speedofsound.co.il/product/sennheiser-xs-lav-usb-c) | ₪310 | **out of stock** | D |
| [Turtle](https://www.turtle.co.il/microphones/laple-microphone) | ₪414 on its own page; ₪339 in a zap snippet — **conflict** | listed | D / R |
| [Thomann](https://www.thomannmusic.com/sennheiser_xs_lav_usb_c.htm) | $52, in stock; shipping to IL shown only at checkout | | D |

If the passenger's iPhone is **Lightning**: Saramonic **LavMicro U1A**, 2 m, omni, **₪162 at
Turtle** (**D** for price and length; that it is a digital MFi part is **I**). The USB-C sibling
**U3A** has no Israeli retailer.

**Not stocked anywhere useful:** KSP (403, snippets show only 3.5 mm/XLR), Ivory (one ₪49 3.5 mm
lav), Bug (wireless only). MAONO UL11: no Israeli listing.

### 4.2 Returnability — read from the regulation itself

*תקנות הגנת הצרכן (ביטול עסקה)*, read on Hebrew Wikisource (**D**):

- 14 days; cancellation fee **5 % or ₪100, whichever is lower**.
- ✅ "פתיחת האריזה המקורית כשלעצמה לא תיחשב שימוש או פגימה בטובין" — *opening the original
  packaging is not in itself use or damage.*
- ⚠️ "חיבור הטובין לחשמל, גז או מים ייחשב … שימוש בטובין" — *connecting the goods to electricity
  counts as use.* This clause is aimed at mains appliances, but a reluctant retailer could point
  at it for a USB device. No ruling found either way — **U**.
- Distance sales: 14 days, buyer pays return shipping; the opened-packaging exclusion covers only
  duplicable media, which a microphone is not (**D**, Kol-Zchut).

**So the clean way to de-risk enumeration is a counter test before paying, not a return after.**
Bring the Pixel 8 with the app's capture dump ready. The same applies to the Lark A1 at Ivory.

### 4.3 Importing instead

Two XS Lavs from Thomann or Amazon are ~$104–120 — **over the US$75 line, so 18 % VAT on the whole
value**, weeks in transit, and a cross-border return. Amazon's free shipping to Israel over $49 is
reported as restored (**R**); B&H was blocked. **Importing the wired part loses on every axis that
motivated direction B.** The only import that makes sense in this document is the Lark A1 Combo at
$59.90, and it is barely cheaper than Zrazi.

### 4.4 Distributors

Sennheiser: **Askol** self-describes as official importer (**D**); Music Basket / audiogroup.co.il
are named in snippets (**R**). Hollyland: **ABS** self-describes as official importer (**D**);
Zrazi and Ivory both state an importer warranty. Saramonic, BOYA, Comica, RØDE: **U** —
cameracity.co.il looks like a Saramonic importer but would not load.

---

## 5. Recommendation

**Step 0 — free, today, before any purchase:**

1. **Measure the gap (2026-09-27, replaces guessing with the eraser).** Stick a ~40 mm ball of
   Blu-Tack / plasticine to the chin-bar lining at the corner of the mouth, put the helmet on,
   close the mouth normally, talk and turn the head, take it off: **the squashed height is the
   gap.** Both helmets. The TX needs, chin bar → lips:

   | layer | mm |
   |---|---|
   | hook-and-loop, mated | ~3 |
   | TX, thin side toward the face | 8.8 (D) |
   | fur over the front face, lightly compressed | ~5–8 (free pile ~10–15) |
   | lip travel when talking | ~5 |
   | **total, fur just brushing** | **~22–25** |
   | **floor, fur pressed on the lips** | **~17** |

   ≥ 25 mm fits cleanly; 17–25 mm fits with fur touching (move toward the corner, or drop the
   breath guard); < 17 mm is dead in front of the mouth. A normal full-face helmet is estimated at
   ~20–35 mm at mouth level (**I/G** — no maker or standard publishes it; wired intercom mics of
   ~10–15 mm with foam fit practically every full-face helmet). The same ≥ 2–3 cm keeps the capsule
   under the 120 dB ceiling (§3). The eraser block still works as a comfort check.
2. ~~Which iPhone does the passenger have?~~ Irrelevant with one kit — nothing plugs into it.
3. ~~Set both phones' hotspot to 5 GHz.~~ Not available on this Pixel (§2.5).

**Primary — A: one Hollyland Lark A1 Duo (Mini) USB-C, Ivory, ₪175, new**, in Stereo mode for both
riders (§2.2). Gates S1–S4 in §6, inside the return window.
- Fallback if Stereo will not persist or the Pixel will not deliver it: DJI Mic Mini 2 (camera RX,
  hardware stereo button), ₪370–399; or two kits:
- Two kits, passenger on USB-C: **2 × Lark A1 Duo USB-C, Ivory, ₪350** — two complete
  factory-paired kits and a spare TX each.
- Two kits, passenger on Lightning: **1 × Lark A1 Combo, Zrazi, ₪299.**
- Plus furry windscreens if the box holds foam, adhesive hook-and-loop dots, and a way to keep the
  TX in the helmet when it comes off (the magnets and a tether loop).

Why primary: it costs half of B for two people; it deletes the tether, which `PLAYBACK.md` §5 named
*the biggest new risk* in the whole plan; the battery turned out not to bind; +60 °C rating; and
every one of its unknowns is answered on the kitchen table in an hour, inside the return window.

**Backup — B: one Sennheiser XS Lav USB-C**, tested at a counter, ₪312–333. Bought **if** A fails
Step 0, A1, A2 or A3 — and worth owning anyway as the **known-good control**: a fixed-gain,
unprocessed, no-RF reference that tells you whether a bad recording is the wireless kit or the
phone. **Backup within A:** DJI Mic Mini, if the A1 fits and links but overloads (120 dB max SPL,
hardware ENC switch).

**Is the honest answer "neither"?** No. Both are workable and both are locally returnable. The
escape hatch if both fail remains `PLAYBACK.md` §5's chest-dongle architecture — a USB sound card
at the collar and a short analogue helmet leg with a real 3.5 mm breakaway — and nothing found here
makes it more attractive than it was.

---

## 6. What would change the answer, and the test that shows it

| # | test | cost | pass | if it fails |
|---|---|---|---|---|
| **0** | Plasticine gap measurement (§5), both helmets | 0 | gap ≥ 17 mm, ideally ≥ 25 | **A is dead → B** |
| **S1** | Laptop, default Mono: `lsusb -v` (VID/PID, `bNrChannels`, any **output** or **HID** interface, `MaxPower`), `arecord --dump-hw-params -D hw:CARD=Microphone`, then `arecord -D hw:CARD=Microphone -c 2 -r 48000 -f S24_3LE mono.wav` — TX1 talks, TX2 talks, clap | 0 | a 2-ch device; both voices on both channels | not UAC → return |
| **S2** | Set Stereo in LarkSound on the Pixel (reverb off, ENC Low/off), force-stop the app, unplug, power-cycle the TXs, replug into the laptop and record again; repeat once more. Compare L/R RMS per segment (numpy) | 0 | TX1 on one channel only, TX2 on the other, every time | reverts to Mono → return; DJI Mic Mini 2 |
| **S3** | Pixel, no code: stock Camera video, TX1 then TX2; pull it, `ffprobe` / split with `ffmpeg`. `adb shell dumpsys media.audio_policy` (engine, USB input, any USB output). adb over Wi-Fi — the port is taken | 0 | L ≠ R in the video | the Pixel HAL downmixes → userspace-driver route or DJI / two kits |
| **S4** | Pixel, the app: debug capture with `UNPROCESSED` (then `MIC`, `CAMCORDER`), 48 kHz, `CHANNEL_IN_STEREO`, `setPreferredDevice(usb)`, no AEC/NS, `MODE_NORMAL`, music playing to the AirPods. Check `getRoutedDevice()`, `getFormat().channelCount == 2`, L/R really differ, mixPort ≠ `voip_tx`, AirPods still on A2DP. **Needs a small code change** (`PLAYBACK.md` §8 T2/T4) | 0 | two distinct channels, A2DP untouched | try the other sources; else as S3 |
| **A1** | Receiver into the Linux laptop: `lsusb -v` (class 1 Audio, `bNrChannels`, `tSamFreq`, **`MaxPower`**), `arecord -l`. Then into the Pixel: `adb shell dumpsys audio`, then the app's capture dump at **16 kHz mono and at 48 kHz stereo** | 0 | a USB input device appears; capture opens | return it; try DJI Mic Mini or go to B. If only 48 k stereo opens, that is an app change, not a hardware failure |
| **A2** | TX at chin-bar distance, ENC **off** (check the LED at power-on), speak normal → raised → loud. Inspect the WAV for flat tops and pumping | 0 | clean at "raised"; AGC not audibly pumping | lower gain in the vendor app once and re-test (does it persist?); else DJI Mic Mini; else B |
| **A3** | RF soak: real config — hotspot up, A2DP to the AirPods, receiver in the phone, TX inside the helmet on your head, 1 m away, 30 min. Once on a **2.4 GHz** hotspot, once on **5 GHz**. Count gaps in the capture WAV | 0 | no dropouts on 2.4 GHz (5 GHz is not available on this Pixel); with one kit, put the passenger's TX behind someone's torso | dropouts = near-field desense is real → **B** |
| **A4** | Battery rundown, ENC off, streaming | 0 | ≥ 6 h | below ~5 h 45 m it becomes the binding battery; the spare TX covers it |
| **A5** | *Two-kit route only.* Both kits live 50 cm apart; with a Combo, confirm each receiver hears **only** its own TX | 0 | no cross-talk, no added dropouts | re-pair 1↔1; if impossible, swap the Combo for two Duos |
| **B1** | At the counter: XS Lav into the Pixel 8, app capture dump, check the routed device | 0 | USB input, audio in the WAV | do not buy; the wired category is then suspect on this phone |
| **B2** | Same shout test as A2 at chin-bar distance, with the foam on | 0 | clean at "raised" | reposition off-axis and further back; if "raised" still clips, B cannot work at the mouth |
| **R** | The ride: 110 km/h, fur on, capture dump, both routes if both are owned | fuel | intelligible far end, no wind clipping | whichever route clips on wind loses; if both do, move the capsule further behind the chin curtain before buying anything else |

Findings that would flip the ranking without a test: a confirmed Lark A1 (or sibling) failure on a
Pixel 8; the A1's max SPL turning out to be ≤ 110 dB; or a 1.5 m wired digital lav appearing in
Israeli retail under ~₪150, which would make B cheap enough to be the default.

## 7. What could not be verified

- **Anything on a Pixel 8.** No source documents any of these devices on that phone. The nearest is
  a RØDE Wireless Micro on a Pixel 10 Pro XL with the stock camera (**R**).
- **Lark A1:** latency; receiver current; USB descriptors (no VID/PID or dump anywhere); whether
  Stereo, ENC and gain persist; ENC power-on default; TX1 = L; whether it transmits while charging;
  windscreen attachment; IP rating. (UAC and max SPL were closed on 2026-09-27.) Charging a Pixel
  9 Pro XL / 10 Pro XL through the RX's pass-through port drops it back to the built-in mic
  (**D**, Hollyland FAQ) — moot, there is no power on the bike.
- **A Lark A1 inside a helmet:** no report found. The nearest is a DJI Mic Mini in an LS2 chin bar
  — clear at low speed, wind noticeable higher up, NR "tinny" (**D**, TOMSTC).
- **The lips-to-chin-bar gap of any helmet:** unpublished by makers and standards; measure it (§5).
- **Every battery figure is a vendor lab number.** No third-party rundown exists for any candidate.
- **Hopping behaviour:** vendor claims only — FCC filings were blocked.
- **In-helmet use of this size class:** the negative report is for a larger TX, the positive one is
  an unverified video. Nobody has measured any of this on a motorcycle.
- **Yugend's Haifa branch, price and stock** — snippet only. **Turtle's price** — two sources
  disagree. **Zrazi's and Ivory's box contents** — read through a summarising fetch; the summaries
  disagree with Hollyland's own contents list in small ways.
- **XS Lav bit depth**, and whether it exposes a UAC gain control.
- KSP, B&H, RØDE support, FCC databases, XDA: blocked, not read, not worked around.

## Sources

**Wireless hardware** — Hollyland Lark A1 [product](https://www.hollyland.com/product/lark-a1) ·
[manual](https://download.hollyland.com/User_manual/LARK%20A1/User%20Manual/LARK%20A1%20-%20User%20Manual%E3%80%90English%E3%80%91.pdf) ·
[store](https://store.hollyland.com/products/lark-a1) ·
[Digital Camera World review](https://www.digitalcameraworld.com/audio/microphones/hollyland-lark-a1-review) ·
Lark M2 [product](https://www.hollyland.com/product/lark-m2) and
[Android compatibility FAQ](https://www.hollyland.com/faq/lark-m2-compatibility-list-for-mobile) ·
[Lark M2S](https://www.hollyland.com/product/lark-m2s) ·
[Lark M1 connection FAQ](https://www.hollyland.com/support/faq/lark-m1/connection-use-2) ·
DJI Mic Mini [specs](https://www.dji.com/mic-mini/specs) and
[manual](https://dl.djicdn.com/downloads/DJI_Mic_Mini/20241120/UM/DJI_Mic_Mini_User_Manual_v1.0_EN.pdf) ·
[BOYA mini 2](https://www.boyamic.com/product/boya-mini2) ·
[MAONO Wave T5](https://www.maono.com/products/wave-t5-wireless-lavalier-microphone) ·
[Insta360 Mic Air specs](https://onlinemanual.insta360.com/micair/en-us/specs/hardware) ·
[RØDE Wireless Micro](https://rode.com/en-us/products/wireless-micro)

**USB, latency, RF** — [Android USB audio](https://source.android.com/docs/core/audio/usb) ·
[Gough Lui, MAONO Wave T5 mega review](https://goughlui.com/2025/03/06/mega-review-maono-wave-t5-wireless-lavalier-microphone/) (the only **M**-grade source in this document) ·
[DJI, avoiding dropouts](https://www.dji.com/media-center/insights/how-to-avoid-dropouts-with-wireless-lapel-microphone) ·
[Pixel hotspot band](https://www.androidpolice.com/android-14-control-wi-fi-hotspot-frequency-band/) ·
[iPhone hotspot band](https://www.idownloadblog.com/2020/11/04/iphone-personal-hotspot-wi-fi-bands-tutorial/) ·
[GrapheneOS USB-audio issue](https://github.com/GrapheneOS/os-issue-tracker/issues/5731)

**One-kit pass (2026-09-27)** — Hollyland CN
[download page](https://www.hollyland.com.cn/download/lark-a1/) ·
moma-faq.com [app guide](https://moma-faq.com/aee7/fb89/1b0a/3151b),
[compatibility list](https://moma-faq.com/aee7/fb89/c2c4/a1a1),
[reverb vs. stereo](https://moma-faq.com/aee7/fb89/1b0a/5845e),
[plug and play](https://moma-faq.com/aee7/fb89/b16c/efcb),
[sibling UAC table](https://moma-faq.com/aee7/2beba/b6f73) ·
[A1 leaflet](https://download.hollyland.com/Leaflet/LARK%20A1%20Leaflet.pdf) ·
[A1 volume-fluctuation FAQ](https://faq.hollyland.com/faq/wireless-microphones/lark-a1/0c7b6) ·
[A1 Pixel charging FAQ](https://www.hollyland.com/support/faq/lark-a1/general-9) ·
[Lark 150 stereo](https://www.hollyland.com/support/faq/lark-150/faq-3) ·
reviews: [ikusugimoto](https://ikusugimoto.com/2025/06/25/hollyland-lark-a1-review/),
[beyondpixels](https://www.beyondpixels.de/hollyland-lark-a1-test-winzige-wunder-fuer-kristallklaren-klang/),
[thephonetalks](https://www.thephonetalks.com/hollyland-lark-a1-review/),
[linuxcompatible](https://www.linuxcompatible.org/compatibility/report/hollyland-lark-a1-wireless-microphone/) ·
Pixel reports: [Pixel 7 + A1](https://support.google.com/pixelphone/thread/420511906),
[Open Camera](https://sourceforge.net/p/opencamera/discussion/general/thread/e29e5ec88f/) ·
AOSP: [shiba audio_policy_configuration.xml](https://android.googlesource.com/device/google/shusky/+/refs/heads/main/audio/shiba/config/audio_policy_configuration.xml),
[AudioPolicyManager.cpp](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/av/services/audiopolicy/managerdefault/AudioPolicyManager.cpp),
[policy.h](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/av/services/audiopolicy/common/include/policy.h) ·
[DJI Mic Mini 2 manual](https://dl.djicdn.com/downloads/MIC_MINI_2/20260421/UM/MINI2_um_en2.pdf) ·
[TOMSTC, DJI Mic Mini in a helmet](https://www.tomstc.co.uk/motorcycle-news/dji-mic-mini-review-for-moto-vlogging) ·
[Hollyland troubleshooting blog](https://store.hollyland.com/blogs/creator-hub/microphone-troubleshooting) ·
[Stephen Coyle, AirPods Pro 2 latency](https://stephencoyle.net/airpods-pro-2)

**In-helmet reports** — [CanyonChasers](https://www.canyonchasers.net/2026/06/insta360-mic-pro-review-the-trick-to-better-motovlog-audio/) ·
[motovlog.com thread](https://motovlog.com/threads/does-anyone-use-a-wireless-helmet-mic-setup.20787/)

**Wired and Israeli retail** — Sennheiser XS Lav USB-C
[maker](https://www.sennheiser.com/en-us/catalog/products/microphones/xs-lav/xs-lav-usb-c-509261) ·
[Thomann](https://www.thomannmusic.com/sennheiser_xs_lav_usb_c.htm) · Askol, Speed of Sound,
Turtle, Yugend, Ivory, Zrazi, ABS (linked in the tables) ·
[Saramonic LavMicro U1A](https://saramonicusa.com/lavmicro-u1a-ultracompact-clip-on-lavalier-microphone-with-lightning-connector-for-apple-iphone-or-ipad-with-a-built-in-6-6-2m-cable/) ·
[cancellation regulations](https://he.wikisource.org/wiki/%D7%AA%D7%A7%D7%A0%D7%95%D7%AA_%D7%94%D7%92%D7%A0%D7%AA_%D7%94%D7%A6%D7%A8%D7%9B%D7%9F_(%D7%91%D7%99%D7%98%D7%95%D7%9C_%D7%A2%D7%A1%D7%A7%D7%94)) ·
[Kol-Zchut, distance sales](https://www.kolzchut.org.il/he/%D7%91%D7%99%D7%98%D7%95%D7%9C_%D7%A2%D7%A1%D7%A7%D7%94_%D7%A9%D7%A0%D7%A2%D7%A9%D7%AA%D7%94_%D7%91%D7%90%D7%99%D7%A0%D7%98%D7%A8%D7%A0%D7%98_%D7%90%D7%95_%D7%91%D7%98%D7%9C%D7%A4%D7%95%D7%9F)
