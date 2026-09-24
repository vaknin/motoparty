# What the rider listens through

Researched 2026-09-21. **Draft for iteration.** Nothing here has been applied to `HARDWARE.md`,
to `HANDOFF.md` or to any code.

This file answers and **replaces `PLAYBACK-RETHINK.md`**, the brief that reopened `HARDWARE.md` §3.
That file was deleted once this one was complete: it had been answered, it was never committed, and
every one of its framing premises is now wrong — §1 and §2 are *not* closed, ~$20 was *not* a
ceiling, and the 30 dB target was aimed at the wrong band. Its five numbered deliverables map to
§1 (derived requirement + weakest assumption), §6 (ranked recommendation, zero-cost first step),
§7–8 (what would change the answer, and the tests), §5.1–5.3 (listings, prices, confidence) and
§6 (the plain statement that the AirPods already win). Two findings of its own are preserved at the
end of §6.

## Status in one paragraph

The 30 dB requirement was never derived, and deriving it inverts it: **the band the last pass
chased contributes nothing to understanding speech.** The rider's existing AirPods Pro 2 already
meet the hearing-protection half of the real requirement by measurement, and the intelligibility
half is limited not by isolation but by the fact that **the app applies no processing at all to
received speech and cannot even change its volume.** The recommendation is to buy nothing for
playback, and to spend the effort on the playback chain in software — worth an estimated
+10–15 dB where it matters, more than any hardware available. The user's own question about
merging the cables into USB-C leads somewhere better than the current parts list: a **USB-C
lavalier microphone with a built-in ADC**, which is one in-spec digital cable and deletes the sound
card, the soldering, the bias measurement and three of the eight open risks.

## How to read the grades

| grade | meaning |
|---|---|
| **M** | measured by a named third party with a stated method |
| **D** | documented — a published spec, standard, or source code I or a subagent read |
| **C** | claimed by a manufacturer with no stated standard |
| **I** | inferred — my own arithmetic from graded inputs; the reasoning is shown so it can be checked |
| **A** | anecdote |
| **U** | unknown / not established |

Where a subagent gathered something I did not re-open myself, the grade is the source's, but treat
the retrieval as one step removed. **No bot wall, captcha or paywall was circumvented in this
pass.** RTINGS' AirPods measurements and one Applied Acoustics paper are behind walls and were
left unread; they are marked *blocked* below and nothing is quoted from them.

---

## 0. What changed in the brief

Answers given by the user on 2026-09-21, before the research:

- **Budget is an anchor, not a wall.** "If the plan is rock-solid, I can do hundreds (for both
  rider and passenger), but ideally cheaper yet high quality." The Etymotic rejection was about the
  *plan*, not purely the money.
- **No Apple purchases.** This matters below, because the one hardware upgrade the evidence
  supports is AirPods Pro 3. It is recorded and declined, not omitted.
- **First-hand (A):** with AirPods Pro 2 in, the rider's ears do not hurt after a ride; with
  nothing, it "really hurts". This turns out to match the arithmetic in §2 closely.
- **The two-cable design was challenged**, and on seeing where the answer led, the user chose the
  architecture in §5: AirPods for playback, USB-C lavalier for the mic.

---

## 0.5 "We rejected this before — why now?"

A fair challenge, raised by the user on 2026-09-21, and worth answering in the file because a later
session will ask it again.

**What was rejected is a different configuration.** `RESEARCH.md` §4.1, verbatim:

> "**On AirPods, good mic + good playback simultaneously is impossible, and no software change
> fixes it.** A2DP is output-only; any input forces HFP/SCO, a single mono bidirectional channel."

and §4.5's closing note:

> "`.allowBluetoothA2DP` + `.playAndRecord` does **not** give an AirPods mic with A2DP output.
> Apple: with both A2DP and HFP options set, 'the system gives hands-free ports a higher priority
> for routing'. A2DP-only gets you A2DP output plus **the phone's** mic."

**Both statements are still true, and neither is contradicted here.** What they reject is *using
the AirPods' microphone while keeping good playback*. This plan gives the AirPods mic up entirely.

Read §4.5's last sentence again with the new architecture in mind: *A2DP-only gets you A2DP output
plus a non-AirPods mic.* Substitute "the USB microphone" for "the phone's" and **that sentence is
the design**. The constraint that killed the old plan is the mechanism that enables the new one.

What the ninth session actually reversed (`HANDOFF.md:28`) was the *sixth* session's "AirPods-only"
decision — i.e. the decision to use the AirPods **mic**, which sits in the ear, in the turbulence.
That reversal stands and this plan keeps it. And `RESEARCH.md` §4.8 already ranked this exact
configuration first: *"(1) wired helmet mic + AirPods for playback"*. It was never rejected; it was
parked behind the unverified claim that §4 of this document has now settled from AOSP source.

### Will the voice and the music actually sound good?

**Music: unambiguously yes, and this is the largest single win in the plan.** Today
`AudioRouter.enterCall()` (`AudioRouter.kt:69-104`) sets `MODE_IN_COMMUNICATION` and calls
`setCommunicationDevice()`, which drops the AirPods to HFP/SCO for the whole talk. Everything
collapses onto one mono voice channel. That is why `resumeLeadMs` defaults to 1500 ms, why
`MediaCue.kt` had to be written to stop earcons firing into a teardown, and why there is a 1–2 s
hole around every talk. In the split config **the AirPods never leave A2DP**: full stereo AAC,
permanently, no profile switch, no gap, no resume drift, and nothing for `resumeLeadMs` to paper
over.

**Voice: better by an amount nobody has measured.** The wire format stays 16 kHz Opus either way
(`PROTOCOL.md` is out of scope), so the *source* does not improve. What changes is that the last
hop stops re-encoding it through a voice codec:

| today (HFP/SCO) | with the split config |
|---|---|
| mSBC 16 kHz (50 Hz–7 kHz) **or** CVSD 8 kHz (150 Hz–3.4 kHz) — which one is **U**, `RESEARCH.md` §7 q.1 | A2DP AAC, which reproduces a 16 kHz mono stream without further loss |

If the AirPods negotiate mSBC, the gain is modest and the real win is the music. If they fall back
to CVSD, the gain is large — CVSD would be cutting the top off exactly the 1–4 kHz band §1.2 shows
carries 77 % of intelligibility. **This is worth settling and it is free:** record the far end
today and look for content above 3.4 kHz.

**The passenger's voice path barely changes.** Per `RESEARCH.md` §4.4, iOS does not use SCO with
AirPods at all — the call path is AAC-ELD at 24 kHz mono both directions. Their playback is already
fine; what they gain is the music behaviour and the mouth mic.

### So what does still go wrong?

A different and smaller set than before, but not an empty one. Full detail in §2.5 and §4:

1. **The platform noise suppressor is lost** and has to be rewritten in the app. This is now the
   largest piece of real work in the plan — §4.1 covers exactly what goes, what it was worth, and
   what replaces it.
2. **6 h of battery**, no charging in the ear (§2.5).
3. **Latency**: ~100–200 ms worse than SCO. *Accepted by the user on 2026-09-21 as manageable*,
   so it is a design note rather than an open risk.
4. **Conversation Awareness** may or may not duck media in ANC mode — undocumented, ten-second
   test (§2.5, T0).
5. **One thing nobody has verified:** a class-compliant USB mic on a Pixel 8 (§5). *ANC in helmet
   turbulence is closed* — the user has ridden with it.

---

## 1. The derived requirement

### 1.1 Wind at the ear — two independent measurements that agree

The brief's premise was Binnington et al. 1993: ~90 dB(A) at 40 mph rising to ~112 dB(A) at
100 mph, full-face. **M/R.** That gives a level but no spectrum, which is why the last pass had to
guess the shape.

A second measurement supplies the shape. **Brown, C.H. & Gordon, M.S. (2011), "Motorcycle Helmet
Noise and Active Noise Reduction", *The Open Acoustics Journal* 4:14–24** — open access, read in
full. Neumann KU-100 binaural dummy head on a running Kawasaki EX500, on-road, seven speeds to
120 km/h, TrueRTA 1/3-octave analysis, B&K 4230 calibrator, GPS-verified speed. **M.**

- At 120 km/h: **>100 dB in every 1/3-octave band from 50 to 400 Hz**; 108 dB at the 100, 125 and
  200 Hz bands; 118.1 dB total linear.
- The spectrum peaks at ≤200 Hz and rolls off **~10 dB/octave from 250 Hz to 8 kHz**.

Converting that to octave bands and A-weighting gives **104.7 dB(A) at 120 km/h**. Binnington's
fit — `L = 90 + 55.3·log10(v/40 mph)` — gives **104.9 dB(A)** at the same speed. **Two
measurements, two decades apart, different methods, agreeing within 0.2 dB.** That is the
credibility this number lacked.

Design point, **110 km/h**, scaling Brown & Gordon's shape back by 1.8 dB: **I**, from **M**
inputs.

| octave band | 63 | 125 | 250 | 500 | 1 k | 2 k | 4 k |
|---|---|---|---|---|---|---|---|
| unweighted dB SPL | 107 | 111 | 109 | 99 | 89 | 79 | 69 |
| A-weighted dB | 81 | 95 | 100 | 96 | 89 | 80 | 70 |

Sum = **102.8 dB(A)**, against Binnington's 102.9 at that speed. At 130 km/h, ~107 dB(A).

> **Caveat, and it is the weakest assumption in this document.** Brown & Gordon used a *half*
> helmet. A full-face is quieter above ~500 Hz — Młyński gives 8–9 dB/octave of helmet attenuation
> above 500 Hz, rising to ~30 dB at 8 kHz — and the same below 250 Hz, where a helmet does
> essentially nothing. So the speech-band rows above are **conservative: they overstate the noise
> where it matters.** Every conclusion here gets *better*, not worse, if the real full-face
> spectrum is measured. Test T2 in §8 measures it for this helmet and this head, for free.

### 1.2 Which band actually limits intelligibility

Speech Intelligibility Index octave-band importance weights, ANSI S3.5-1997. **D.**

| band | 250 | 500 | 1 k | 2 k | 4 k |
|---|---|---|---|---|---|
| weight | 0.062 | 0.167 | 0.237 | **0.365** | 0.169 |

**77 % of intelligibility lives at 1–4 kHz. The 2 kHz band alone carries 36 %. Everything below
250 Hz carries zero.**

The brief's inherited requirement — 30 dB of attenuation at 20–250 Hz — was optimising a band that
contributes **nothing** to understanding a voice. That is why the last pass could find no product
that satisfied it: it was the wrong question, not a hard question.

### 1.3 Does the loud bass mask the speech bands anyway?

This is the one way the sub-250 Hz band could still matter for intelligibility, and it has to be
checked rather than assumed. Upward spread of masking, taking a conservative 15 dB/octave upper
skirt from the 125 Hz band at 111 dB: **I.**

| at | masked level from the 125 Hz band | direct in-band noise | dominant |
|---|---|---|---|
| 500 Hz | 111 − 30 = 81 dB | 99 dB | direct, by 18 dB |
| 1 kHz | 111 − 45 = 66 dB | 89 dB | direct, by 23 dB |
| 2 kHz | 111 − 60 = 51 dB | 79 dB | direct, by 28 dB |

Even at a very shallow 10 dB/octave skirt the direct in-band noise still wins at every speech band.

**Conclusion: sub-250 Hz attenuation buys hearing protection, not intelligibility.** The two are
different requirements with different answers, and the brief collapsed them into one.

### 1.4 So there are two requirements

| | target | what sets it |
|---|---|---|
| **Hearing safety** | 103 → ≤85 dB(A), i.e. **18 dB(A)**, and it must land at 250 Hz–1 kHz, because that is where the A-weighted total sits | whatever is in the ear |
| **Intelligibility** | ~+10 dB SNR at 1–4 kHz for hours without effort; +5 dB usable-with-effort | isolation **and** app-side speech level, which trade **1:1** |

The second line is the one the brief never separated out, and it is the useful one. The driver
sits *inside* the seal; the wind is *outside* it. So a dB of isolation and a dB of app-side
speech-band gain are worth **exactly the same**, and one of them is free.

This also settles option B from the brief (foam plugs + helmet speakers) on arithmetic instead of
taste: **the speaker is outside the plug, so the plug attenuates noise and speech equally and buys
0 dB of SNR.** It lowers absolute level and protects hearing. It does not make speech clearer. The
brief suspected this; it is exactly right.

---

## 2. What the AirPods Pro 2 actually deliver

The find that settles the whole question: **Apple publishes a standardised octave-band attenuation
table.** "AirPods Pro Hearing Protection Data Sheet", Rev A, October 2024 — measured to
**ANSI S3.19-1974** on a real-ear human subject panel, with standard deviations, battery flat so
ANC is definitively off. **M** (manufacturer, but a named standard, a real panel and published
SDs — this is a measurement, not marketing).

**No other earbud manufacturer publishes standardised attenuation data at all.** Not Bose, not
Sony, not Technics, not Bowers. That is itself a finding: on this axis there is only one vendor
with evidence.

### 2.1 Passive only

| octave band | 125 | 250 | 500 | 1 k | 2 k | 4 k | 8 k |
|---|---|---|---|---|---|---|---|
| mean attenuation, dB | 11 | 11 | 12 | 15 | 23 | 24 | 28 |
| std dev | 3 | 2 | 2 | 3 | 3 | 2 | 5 |

**NRR 10 dB. CSA Class C.** No 63 Hz band is published. Note the σ of 3 dB at 125 Hz: the passive
figure is **fit-dependent**, which is what makes §6 item 3 worth a phone call.

### 2.2 ANC on

- Apple, same sheet: **25–30 dB** total attenuation (NRS_A, ANSI/ASA S12.68-2007) across 100, 105
  and 110 dB environments. **M.** Two footnotes matter: attenuation is *lower* for noise dominated
  above 2 kHz (so the 25–30 dB is a best case for low-frequency-dominated noise — which is our
  case), and for noise dominated below 500 Hz Apple says to use dB**C**, not dB(A). Our ~103 dB(A)
  inside a helmet is probably ~115 dB(C), which sits **outside the range Apple publishes**. **U**
  beyond 110 dB.
- **National Acoustic Laboratories**, Sydney (Chong-White, Mejia, Edwards) — B&K HATS Type 4128C,
  IEC 60318-4 ear simulator, pink and traffic noise 55–85 dBA, 1/3-octave, per ETSI TS 103 640:
  **~35 dB at 250 Hz**, ~27 dB average, +12 dB more low-frequency attenuation than AirPods Pro 1.
  **M**, named lab. The per-band curve exists only as a figure; 63/125/500/1000 Hz values could not
  be read off it. **This is the main data gap.**
- RTINGS: **blocked** (JS-rendered, membership wall). Nothing quoted.
- HeadphonesAddict, rig not stated: ANC 100 Hz 28.6 dB, 400 Hz 30.2 dB, **1 kHz 14.1 dB**, 4 kHz
  32.8 dB. **C/U** on method — but the 1 kHz dip is where passive is weakest and ANC is fading, so
  a trough there is physically plausible and is carried below as a risk.

### 2.3 Residual at the ear, 110 km/h — the actual answer

**I**, from the **M** inputs above.

| octave band | 125 | 250 | 500 | 1 k | 2 k | 4 k |
|---|---|---|---|---|---|---|
| wind at the ear (§1.1) | 111 | 109 | 99 | 89 | 79 | 69 |
| total attenuation (passive + ANC) | ~32 | ~35 | ~30 | **~20** | ~23 | ~24 |
| residual | 79 | 74 | 69 | **69** | 56 | 45 |

**Total ≈ 72–73 dB(A).** Twelve dB below the 85 dB(A) eight-hour line, at 110 km/h, with hardware
the rider already owns.

**That is the arithmetic behind "my ears don't hurt with the AirPods in, and really hurt
without".** An unprotected 103 dB(A) is roughly a 100× exposure dose versus 73 dB(A). The rider's
anecdote and the measured data are saying the same thing, which is the strongest cross-check in
this document.

**Requirement 1.4-safety is therefore already met, for free.**

### 2.4 Two things that fall out of the table

**The worst band is 1 kHz, and it is in the middle of the speech band.** Passive gives only 15 dB
there and ANC is fading out. If the HeadphonesAddict trough is real, it is worse than the ~20 dB
assumed. **Test T2 measures this directly and it is the single most valuable free measurement
available.**

**The residual is flat at ~69 dB from 500 Hz through 1 kHz and then falls steeply** — 56 dB at
2 kHz, 45 dB at 4 kHz. So the EQ target is *not* "boost the treble". It is: **high-pass below
~300 Hz, hold level through 1 kHz, and 2–4 kHz comes nearly free.** Speech shaped that way at
**~82 dB SPL** gives +10 dB SNR in every band that matters.

And the dose is not binding: noise 72.5 dB(A) plus continuous shaped speech at ~81 dB(A) sums to
**~81.5 dB(A)** — still under the 8-hour limit even if someone talked the whole ride. **The app
has level headroom to spend.** That is a design permission, not just a safety check.

### 2.5 The honest problems with the AirPods

- **Battery.** 6 h claimed with ANC; **5 h 43 m measured** (SoundGuys), 6 h 03 m
  (HeadphonesAddict). **M.** They cannot be charged in the ear. Case reserve ~24 h; 5 min in the
  case ≈ 1 h of listening (**C**, Apple). A fuel stop is a top-up; a long day needs planning. High
  ambient means high volume, which will pull below the rated figure.
- **Conversation Awareness ducks media volume when it hears you speak** — which on an intercom is
  constantly. **Whether it is active in Noise Cancellation mode is U:** Apple's own pages
  ([104979](https://support.apple.com/en-us/104979), [listening modes](https://support.apple.com/guide/airpods/adjust-noise-control-settings-dev6d977ff21/web))
  describe the feature and never state a listening-mode restriction, and it appears as its **own
  Control Center toggle** rather than as a sub-setting of Adaptive Audio — weak evidence that it is
  not mode-gated. The user's position is that running pure ANC makes it moot; that is plausible and
  undocumented either way. **Downgraded from "must fix" to a ten-second check** (ANC on, play
  music, talk, watch for ducking). If it does duck, it is configurable only from an iPhone and the
  passenger has one.
- ~~**Wind on the feedforward microphones**~~ — **settled 2026-09-21 by the user's own riding
  experience: ANC in helmet turbulence works well.** The concern came from cyclist and runner
  reports of an added roar on AirPods Pro 2 versus gen 1 (stem vortices; ANC off removes it, so it
  is mic-driven — **A**, large and consistent). It does not transfer, for the reason the research
  predicted: measurements in the hearing-device ANC literature find wind degrades **feedforward**
  ANC while **feedback ANC is unaffected** (**M/R**), AirPods Pro use hybrid FF+FB, and inside a
  closed helmet the bud is not in the airstream — the shell is. The two motorcycle-specific reports
  found (MotoFomo, ModernVespa) were also positive. **Closed; no test needed.**
- **Latency is the one genuine cost of this plan.** AAC/SBC only. Apple has never enabled LE
  Audio/LC3 for third-party hosts and Auracast is still absent across their line as of 2026
  (**D/R**), so there is no low-latency path and no bidirectional LE Audio escape. Budget
  **150–300 ms one-way, variable**. No measurement of AirPods Pro 2 on a Pixel 8 exists publicly
  (**U**); the nearest data is SoundGuys' 2019 WALT rig (2,800 points: AAC 369 ms average across
  phones, best phone Pixel 3 XL 244 ms average across codecs — **M**, but 2019 and pre-dating
  Android's 2021 audio-stack work) and Stephen Coyle's 126 ms for AirPods Pro 2 on **iOS**
  (**M**, wrong platform). Google's own Oboe note: most Bluetooth latency is headset buffering,
  and *"A2DP has no real low-latency mode."* **D.**
  The app cannot even ask how bad it is — `AudioManager.getOutputLatency` and
  `AudioTrack.getLatency` are hidden non-SDK APIs (**D**), and whether `AudioTrack.getTimestamp()`
  on a Pixel 8 reflects the AirPods' AVDTP-reported delay is **U**. This is exactly what the
  existing `latencyTrimMs` setting is for: it has to be measured by hand.

  *Note the direction:* today's HFP/SCO path is lower-latency than A2DP. Moving to the split config
  makes mouth-to-ear **worse** by roughly 100–200 ms while making everything else better. That
  trade is a conversation-feel question, not an intelligibility one, and T5 is where it gets judged.
- **Riders report the AirPods microphone does not work inside a helmet at all** (MotoFomo: neither
  stationary nor moving). **A** — and it is *supporting* evidence for the whole project direction,
  since the wired mouth mic is precisely the fix.

### 2.6 What could not be falsified

A research pass was aimed specifically at beating the AirPods at any price. It failed on acoustics
and succeeded only on practicalities.

- **Custom-moulded IEMs lose by 12–18 dB where it matters.** Elacin ER25's EN 352-2 sheet gives
  **L = 20 dB** (**M**). And a 63-worker, 9-site, 3-manufacturer F-MIRE field study (*Applied
  Acoustics*) found custom moulds over-declared by 3–5 dB at high frequencies and **8–10 dB at low
  and medium frequencies**, concluding verbatim that they are *"unsuited to attenuate low
  frequencies."* **M.** Ultimate Ear's motorcyclist SoundEar range is CE EN 352-2 Cat III at
  **31 dB SNR** (**M**) — but SNR is an A-weighted composite dominated by mid and high frequencies.
  Compare L against L, not SNR against SNR; that distinction decides the whole question.
- **No competing ANC earbud publishes standardised data**, so none can be compared on this axis at
  all. Review-lab relative scores put Bose QC Ultra, Sony WF-1000XM5 and AirPods Pro 2 within a few
  dB of each other.
- **The strongest potential falsifier — ANC failing in a moving airstream — turns out to be a
  feedforward-only failure mode**, as in §2.5. The one directly relevant paper (*Applied Acoustics*
  2024, "Effect of wind noise on active noise control headphones") is **blocked** behind a
  paywall and was not read; only its abstract-level direction is known.
- **Nobody has measured ANC earbuds inside a full-face helmet at motorway speed.** That study does
  not exist. **U** — and T2/T3 are the cheapest way to produce the local answer.

### 2.7 The upgrade that exists, and is declined

For the record, so this is not re-proposed: **AirPods Pro 3** publish an EN 352 data sheet whose
values were calculated from **ANSI/ASA S12.42-2010 and S12.6-2016 measurements by Michael &
Associates, Inc.**, an accredited independent hearing-protector lab — the best-sourced number
found anywhere in this research. **M.**

- Passive, EN 352-2:2020+A1:2024: **SNR 20.3, H 24.8, M 17.1, L 13.1**; octave means 125 Hz 10.9,
  250 10.2, 500 14.2, 1 k **17.9**, 2 k 26.8, 4 k 31.7, 8 k 31.8.
- Active, EN 352-5:2020, noise-cancellation mode: **SNR 29, H 27, M 34, L 38.**
- 8 h battery instead of 6. Sold in Israel at ~₪750–825 (Ivory, KSP, iDigital) — **local purchase,
  no import exemption problem for either person.**

Treating the EN 352-5 L/M/H descriptors as band attenuations is a stretch (**I**, and flagged as
such), but on that reading the Pro 3 residual works out ~5 dB lower overall and **~10 dB lower at
the critical 1 kHz band** — the exact weakness identified in §2.4.

**Declined by the user on 2026-09-21: no Apple purchases.** Recorded with its evidence so that a
future session does not spend research effort rediscovering it. Also note the small print: Apple's
EN 352 certification applies to a configuration that **caps media playback at 82 dBA**, and that
toggle is not offered in Israel — so the certified condition is not the use condition anyway.

---

## 3. The app's free lever, which is larger than any purchase

**The app applies zero DSP to received speech.** `VoiceEngine.playbackLoop`
(`android/app/src/main/java/com/kivan/motoparty/audio/VoiceEngine.kt:396-412`) runs
Opus-decode → `track.write(pcm, 0, FRAME)` with nothing in between: no gain, no filter, no
limiter, no `OPUS_SET_GAIN` on the decoder. A repo-wide search for `setVolume|gain|limiter|
highpass|compress|loudness|Equalizer|DynamicsProcessing|audiofx` finds nothing on the playback
path; the only `audiofx` uses are AEC and NS on **capture**.

Worse: the one volume command the app has (`LinkHost.kt:700-714`, the spoken "louder/quieter")
calls `adjustStreamVolume(STREAM_MUSIC, …)`. **It cannot change how loud the far end is.** In a
103 dB(A) environment, the app has no way at all to make the other person louder. That is a gap,
not a tuning opportunity.

Estimated headroom — **I**, my own arithmetic, unmeasured:

| lever | gain at 1–4 kHz | why |
|---|---|---|
| High-pass at ~250–300 Hz | **+6 to +9 dB** | most of speech's *energy* sits below 500 Hz but carries ~0 SII weight; removing it frees that much level at the same loudness and the same dose |
| Level held flat through 1 kHz, gentle lift above | **+3 to +6 dB** | matches the residual-noise shape measured in §2.4 — this is the shape the wind dictates, not a generic "presence boost" |
| Compression ~3:1 plus a feed-forward peak limiter | **+6 to +8 dB** | speech crest factor is 12–18 dB; compressing to 8–10 dB raises the **average** at an unchanged peak ceiling |
| A real voice-volume control | — | absent today; without it, none of the above is reachable by the rider mid-ride |

They overlap, so conservatively **+10 to +15 dB of effective speech-band SNR, for zero money** —
against the ~5 dB that a full hardware generation would buy. And §2.4 showed the dose budget has
room to spend.

Where it goes, following this codebase's own conventions: a new pure
`android/.../audio/VoiceDsp.kt` — no Android imports, unit-testable like `MediaCue.kt`,
`DeviceRoster.kt` and `Wav.kt` — called in place between `VoiceEngine.kt:403` and `:406`, over the
320-sample `ShortArray`, with no allocation on the frame path (the rule `PcmDump` already follows)
and a feed-forward limiter only, so it adds **zero** latency to a path that is already spending
150–300 ms on Bluetooth. New keys in `Settings.kt:8-29` plus `load()`/`update()`, one row in
`ui/MainScreen.kt:184-201`. iOS is nearly free: `VoiceEngine.swift` already runs an `AVAudioEngine`,
so an `AVAudioUnitEQ` and a dynamics processor drop straight into the graph.

---

## 4. The split config is settled in AOSP source, ahead of the bench

`HANDOFF.md` carries Stage B "risk 2" — *does `AudioRecord` on USB work while A2DP plays?* — as
`RESEARCH.md` §4.8's **unverified** claim, and calls it the pivotal test. It can be answered from
AOSP's default audio policy engine,
`frameworks/av/services/audiopolicy/enginedefault/src/Engine.cpp`, `getDevicesForInputSource()`.
**D — source code, read directly.**

- For `AUDIO_SOURCE_MIC` and `UNPROCESSED`, the **only** way the output influences input selection
  is a test for an SCO *output* device. A2DP is not SCO, so in `MODE_NORMAL` that branch never
  runs. **There is no code path by which selecting a USB input drags the output to USB.** And
  `AUDIO_DEVICE_IN_USB_DEVICE` already outranks the built-in mic *before* `setPreferredDevice` is
  called at all.
- For `AUDIO_SOURCE_VOICE_COMMUNICATION` — **which is exactly what the app uses today**,
  `VoiceEngine.kt:188` — input device selection **is** derived from the communication *output*
  device, and in the `SPEAKER` branch the built-in mic **outranks USB**.

**So the split config works — but only after the app stops using `VOICE_COMMUNICATION` as its
capture source.** That consequence is recorded nowhere in `RESEARCH.md` §4.8 or `HANDOFF.md`, and
it converts the pivotal Stage B test into a confirmation.

> *Caveat:* this is `enginedefault`. A vendor may ship `engineconfigurable` instead. Pixel is
> believed to use the default engine; not verified. **U.**

**And there is a price the plan has not costed.** Platform AEC is engaged by the
`VOICE_COMMUNICATION` preset and references only the voice-communication output path; an output
with `USAGE_VOICE_COMMUNICATION` routes through `STRATEGY_PHONE`, which does not reach A2DP.
**Platform AEC and NS are mutually exclusive with A2DP output on Android.** Moving to the split
config therefore *loses* the `AcousticEchoCanceler` and `NoiseSuppressor` attached at
`VoiceEngine.kt:193-194`. **R**, from a well-argued issue report plus inference from the AOSP
strategy routing.

### 4.1 What "losing the platform noise suppressor" actually means

**What is there today.** `VoiceEngine.kt:187-194` stacks three things, all free:

1. `MediaRecorder.AudioSource.VOICE_COMMUNICATION` — not merely a device hint. It selects a HAL
   input path with the vendor's voice DSP chain attached: on a Pixel 8 that is Google's own capture
   processing — echo cancellation, noise suppression, gain control — the same chain a phone call
   uses.
2. `AcousticEchoCanceler.create(record.audioSessionId)` — an explicit AEC effect on top.
3. `NoiseSuppressor.create(record.audioSessionId)` — an explicit NS effect on top.

(`AutomaticGainControl` is deliberately *not* used; the code says so.)

**Why it goes away — two independent reasons, both from §4.** The source `VOICE_COMMUNICATION`
picks its input device from the communication *output* device, and in the `SPEAKER` branch the
built-in mic outranks USB — so it cannot be relied on to give us the USB capsule. And the platform
AEC references only the voice-communication output path, which routes through `STRATEGY_PHONE` and
never reaches A2DP — so even if the device were forced, the processing would have no valid
reference signal. The split config therefore has to use `AudioSource.UNPROCESSED` (which means
literally no processing — that is its purpose) or `MIC` (vendor-dependent, guarantees nothing).

**How much does it matter? The two halves are very different.**

**AEC — losing it is close to free.** Today's echo path is strong: far-end voice comes out of the
AirPods and straight into the AirPods microphone, in the same ear. AEC earns its keep there. In the
new design the mic is at the mouth and the earbuds are sealed, so the coupling collapses; and with
150–300 ms of *variable* A2DP delay a platform AEC would not converge anyway. This loss is mostly
theoretical.

**NS — this is the real one, and it cuts both ways.** Against: the whole project is speech in
103 dB(A) of wind, and something was cleaning the capture. For:

- **Platform NS was never designed for this noise.** It is tuned for a phone held at the face in
  street or office noise — moderate level, broadband, roughly stationary. Nobody has evaluated it
  against 111 dB at 125 Hz. In extreme noise, spectral-subtraction NS characteristically produces
  musical noise and chops speech onsets, so it may be doing harm rather than good.
- **Nobody has ever measured what it does here.** It is a black box that has never been A/B'd.
  `captureDump` (Stage A) was built precisely so that it could be.
- **The new microphone changes the problem.** A cardioid capsule at the mouth behind the chin
  curtain starts from a far better input SNR than an in-ear omni sitting in the turbulence. The far
  end may simply need much less cleanup.

**What has to be written, in value-per-line order:**

| # | item | effort | why |
|---|---|---|---|
| 1 | **Steep high-pass on capture, ~150–200 Hz** | ~15 lines, pure Kotlin | The big one. §1.1: wind is 111 dB at 125 Hz and 89 dB at 1 kHz; §1.2: nothing below 250 Hz carries any SII weight. A 4th-order high-pass removes the overwhelming majority of the *energy* and costs no intelligibility — and stops the Opus encoder spending its 24 kbps on rumble |
| 2 | **Compressor + limiter on capture** | small | A fixed mouth-to-mic distance makes this easy, and better-behaved than the platform AGC the app already declines to use |
| 3 | **Real spectral noise suppression**, if 1 and 2 are not enough | a dependency | See below |
| 4 | The playback-side chain of §3 | — | Same file, same tests |

For item 3 the natural fit is **SpeexDSP's preprocessor**: BSD-licensed, from Xiph — the same house
as Opus — fully fixed-point, and it drops into the existing C JNI layer beside `opus_jni.c`.
Checked against the Speex manual (**D**):

- `speex_preprocess_state_init(frame_size, sampling_rate)` takes **any** frame size and rate, so
  `320 @ 16000` — exactly `VoicePacket.FRAME_SAMPLES` and `SAMPLE_RATE` — is a direct fit, with no
  resampling and no reframing.
- `speex_preprocess_run(state, audio_frame)` is **in place**, "used both as input and output" —
  the same convention `VoiceDsp.kt` would use over the existing `ShortArray`.
- Options: `SET_DENOISE`, `SET_AGC`, `SET_VAD`, `SET_DEREVERB`, `SET_NOISE_SUPPRESS` (the
  suppression floor in dB — the knob worth tuning for wind), plus the echo-suppression setters.

**The 34 ms delay figure that circulates for Speex is the *codec*, not the preprocessor** — the
manual's only latency discussion for this library concerns echo cancellation (a 2-frame delay on
the alternative API). **Still to check before committing:** the manual does not *state* the
preprocessor's delay, and an FFT-based spectral-gain stage with internal overlap-add can cost up
to one frame (20 ms). Read `libspeexdsp/preprocess.c` to settle it — 20 ms on capture is
acceptable, more is not.

RNNoise is the better-quality alternative but is locked to 48 kHz / 480-sample frames, so it would
need 16→48→16 resampling; note it as an upgrade path, not a starting point.

Also pending from `RESEARCH.md` §6, and now on the critical path:
`AudioManager.getProperty(PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)` on the Pixel 8. If
`UNPROCESSED` is not supported, `MIC` is the fallback and may carry some vendor processing.

**The reframe.** The split config does not so much remove a capability as remove a black box and
hand over the knobs — on a chain that has never been measured, for a noise environment it was never
designed for. Combined with §3, `audio/VoiceDsp.kt` stops being nice-to-have polish and becomes the
centre of the project. Unlike the platform chain, it is testable: `captureDump` already exists to
A/B it.

**Sequencing risk, and the mitigation.** If the split config ships before the capture DSP is
written, capture could be *worse* than today for a while. The app already contains both routes, so
keep the HFP path selectable and A/B the two on the same ride rather than switching wholesale.

Two smaller notes: `setPreferredDevice` is documented as a **preference, not a guarantee** — the
javadoc says the result is "not guaranteed to correspond to the actual device being used", so
`getRoutedDevice()` must be checked after `startRecording()`. **D.** The app already has that
machinery at `VoiceEngine.kt:220-249`.

**iOS is confirmed independently and the current code has the wrong flag.** Apple Developer Forums
thread 741513, answered from a **WWDC '24 lab appointment with Apple engineers**:
`.playAndRecord` + **`.allowBluetoothA2DP` only** — dropping `.allowBluetooth`, which is the same
option as `.allowBluetoothHFP` — plus `setPreferredInput(usb)` gives USB capture with AirPods A2DP
output. With `.allowBluetooth` set, the output collapses to the speaker. **R**, but from the
strongest kind of source available for an undocumented behaviour.
`ios/Sources/Motoparty/Audio/SessionController.swift:56` and `:60` currently set
`.allowBluetoothHFP` — precisely the option that has to go. One adjacent thread (737904) reports a
timing dependency: configure the session only once audio is actually flowing, or it drops A2DP. **A.**

---

## 5. One cable — the answer to "can't an adapter merge them into USB-C?"

Three parts.

**1. With this plan there is no second cable.** Playback stays wireless. The `HARDWARE.md` §5
headphone-line rows — coiled extension ₪15.23, short MMCX cable ₪24.38 — are **cancelled, not
merged**.

**2. Merging into one 4-pole TRRS line is the wrong merge**, and §5 already rejected it: a shared
ground over ~1.5 m puts the headphone signal only ~4–10 dB below the mic signal — a hard-wired
echo, plus more junctions with the mic on the outermost contact.

**3. The right answer is to delete the analogue run entirely.** A **USB-C lavalier microphone with
a built-in class-compliant ADC and a long captive cable** is a single in-spec digital cable from
capsule to phone. The category is real, crowded and cheap (~$15–40): Cubilux MLC-12, $18.14,
48 kHz/24-bit ADC, unidirectional, listing explicitly names Pixel 8 and Pixel 8 Pro; Saramonic
LavMicro U3A, 2 m captive cable plus a right-angle adapter, marketed as class-compliant for
Android. **D** for the specs, **C** for the Pixel-8 compatibility claims. *(Western retail, quoted
as reference points — sourcing is AliExpress, so the criteria below are what matters, not these
two listings.)*

### What this deletes from the current parts list

The USB-C sound card (§1, ₪47.59 ×2 plus probes); the Cardo-style stick-on mic and its
re-termination (§2); the soldering iron; the multimeter bias measurement; the tip-versus-ring bias
gamble; the TRRS/CTIA pinout gamble on the EJEAS route; the "mic input only enumerates with
something in the headphone jack" quirk; the shielded mic extension (₪22.67); the ferrites (₪6.13);
and open risks **3** (mic pinout and bias), **5** (adapter is not what the listing says) and **7**
(ignition noise on 1.2 m of unbalanced mic line — a digital run cannot pick it up). Live cart drops
from ≈₪220 to roughly **₪70–140 for two**.

### What it costs, stated plainly

- **The 3.5 mm breakaway is gone, and this is the biggest new risk.** USB-C extraction force is
  **8–20 N** per the Type-C spec, maintained across 10,000 cycles (**D**). A snag transmits far
  more than that, and the failure mode is a yanked phone or a damaged port, **not** a clean
  disconnect. **Magnetic USB-C is rejected**: no staggered pin sequencing, no mid-plate, EMI off
  exposed pogo pins, and isochronous USB audio has no retransmission, so an intermittent contact is
  an audible dropout rather than a recoverable error (**R**, multiple independent sources, no
  independent lab testing found). The answer is a **mechanical strain relief anchored to the
  jacket that takes the load before the connector does**. If a true breakaway is judged essential,
  the fallback is the chest-dongle architecture: one USB-C cable to a sound card at the collar, and
  a short analogue helmet leg carrying the 3.5 mm breakaway.
- **Do not use a USB-C extension cable.** The Type-C spec says receptacle adapters are *"explicitly
  not defined or allowed"*, and in practice they often fail to forward CC (**D**). A captive-cable
  lavalier avoids the question entirely. If a straight cable is ever needed, USB-IF-**certified**
  C-to-C USB 2.0 cables exist at 2 m and even 4 m, and USB 2.0 allows 5 m passive — a UAC1 device
  runs at full speed on D+/D− only, so none of the high-speed signal-integrity objections apply.
- **Unverified:** no report was found of a class-compliant USB audio device working on a Pixel 8
  specifically (**U**). Google's own lossless-USB-audio work for the Pixel 8 is supporting evidence
  that the path is well exercised, nothing more.

### Spec criteria for the AliExpress search

- USB-C plug with the **ADC in the plug** — *not* a TRRS lavalier plus a USB-C adapter, which
  reintroduces the whole bias problem this is meant to delete.
- **The best filter is the sensitivity line**, because sellers rarely state a sample rate but
  almost always fill this one in:

  | spec line reads | verdict |
  |---|---|
  | `Sensitivity … RL=2.2k Ohm VS=3V` (a load resistance and a supply voltage) | **analogue electret — reject.** It is asking to be biased; there is nothing digital in it |
  | `48 kHz / 24 bit`, `16-bit/48 kHz` | digital — a converter is in the plug |
  | `Plug socket: 3.5mm` | reject, whichever variant thumbnail is selected |

- **An indirect filter that is stronger than the spec copy.** `HARDWARE.md` §1 established that the
  **Pixel 8 has no analogue audio mode** — which is why passive adapters fail on it. It follows
  that **any USB-C microphone that genuinely works on a modern Android phone, or on a laptop's
  USB-C port, must contain a chip.** So reviews naming a phone model and saying it works are real
  evidence of an ADC. Read the reviews for the device, not the star count.
- **Corollary worth stating plainly: a passive capsule on a USB-C plug is not a cheap gamble on a
  Pixel 8, it is a guaranteed dud.** The "buy two and test" strategy has this floor.
- **A second positive tell: a headphone jack on the plug body.** A headphone *output* requires a
  DAC, which means a real USB audio chip, which means an ADC is present too. It is a tiny USB
  sound card with a capsule attached — the right construction.
- **The matching negative tell, and it looks similar:** one listing offering 3.5 mm / USB-A /
  Lightning / USB-C as *variants*, often hidden under a "Color" selector. That is one capsule with
  swappable plugs. Distinguishing the two takes one click on the variant thumbnails.
- "Class compliant" / "driver-free" / "plug and play, no app"; Android or Pixel named.
- Ignore AliExpress's **"AI overview of item"** block entirely — it is generated from the title and
  is disclaimed on the page itself. Same trap as the generated EJEAS pinout "wiki" in
  `HARDWARE.md` §2. If the generator cannot find an ADC claim, the listing does not make one.
- **Captive cable ~1.5 m; 2 m acceptable; 3 m is surplus and 6 m is a liability.**
  `HARDWARE.md` §5 computed a 1.6 m run (80 cm seated, +35 cm standing, +20 cm shoulder check,
  ×1.2 routing). **The user measured it on 2026-09-21: handlebar to lips is ~1 m** — the standing
  allowance does not apply to motorway riding. Routing and a drip loop off a 1 m base is ~1.2 m,
  and **the remaining slack is not padding, it is the safety mechanism**: with no breakaway (§5,
  USB-C pulls out at 8–20 N) the cable must never go taut on a head turn, because the connector
  is not what should give first. 1.5 m over a 1 m run is ~50 % slack, which is right. Beyond ~2 m
  the excess becomes a snag and flap hazard rather than protection.
- **Unidirectional / cardioid preferred over omnidirectional** — but see §5.1: this turns out to be
  worth less than it first appears, and should not drive the purchase.
- Buy two, plus a cheap spare: at this price the spare costs less than a second shipment.

### 5.1 Sourcing — AliExpress does not have this part

A sourcing pass ran on 2026-09-21. **Method, stated because the last pass's subagent misbehaved:**
no item page was opened, no captcha was met and nothing was bypassed. AliExpress geo-redirected to
`he.aliexpress.com` on its own and served Hebrew pages with ILS pricing, unprompted. Every row is
**search-results-level only**. Prices moved between fetches minutes apart (one item quoted ₪69 /
₪80 / ₪97 / ₪109), and ₪3-ish prices are new-buyer teasers — **treat every price as a snapshot,
not a quote**. Roughly 70 % of results for any sensible query are 2.4 GHz *wireless* transmitters.

**The sourcing was run against a 1.6 m cable floor. The user's own measurement — ~1 m of run —
removes that floor, and it inverts the result.** What looked like a category with nothing in it is
actually a category where the *cheap* options fit and the expensive ones are over-long. The
original finding is kept below for the record; the ranking is the corrected one.

| Role | Item | Price (ILS, snapshot) | Cable | Pattern | Grade |
|---|---|---|---|---|---|
| **Primary** | 1.5 m metal USB-C mic — [1005008221367147](https://www.aliexpress.com/item/1005008221367147.html) | ₪15.10 (~$5) | **1.5 m — the right length**, ~50 % slack over a 1 m run | **cardioid** — the only one found on AliExpress | **I.** USB-C-only title, not a multi-connector variant listing, which is the good sign. But **no ADC or plug-and-play wording at all** — that is what the ₪15 buys you an answer to |
| **Backup / control** | Saramonic LavMicro U3 series — [1005003021740716](https://www.aliexpress.com/item/1005003021740716.html) | ₪95.52 (~$31.5), 20 sold | **not stated in search** — family is U3A 2 m / U3B 6 m, and *which variant ships is unknown*. U3A fits; U3B is too long | omni | **I** for the listing; **M/D** for the U3A/U3B specs (brand page + B&H: 16-bit/48 kHz). Its value is as a *known-good control*: if the ₪15 mic misbehaves, this says whether the fault is the mic or the phone |
| **Demoted** | "Saramonic" 6 m Type-C lav — [1005006783963768](https://www.aliexpress.com/item/1005006783963768.html) | ₪31.93 (~$10.5), free-shipping badge, 30 sold | **6 m — 6× the run.** Now a liability, not a feature | not stated | **I.** 6 m of analogue lav into a Pixel is impossible without an ADC, so *something* converts — but at a quarter of the genuine U3B's price this is near-certainly a clone |
| **Reject** | 1.5 m clip-on, USB-A / 3.5 mm / Type-C variants — [1005011654025025](https://www.aliexpress.com/item/1005011654025025.html) | ₪3–7, 4,000+ sold | 1.5 m | unstated | **RED FLAG.** One capsule sold with three swappable plugs is the textbook TRRS-plus-adapter construction this design exists to avoid |
| **Reject** | SYNCO Lav-S6E — 1005012597714773 | ₪27.65 | 6 m | — | Looks perfect in search; it is a **3.5 mm** mic. Not a candidate |
| Reference, **not** AliExpress | **Cubilux MLC-17** (Amazon `B0CR12KD55`, cubilux.com) | **$21.09** | **3.05 m — now surplus**, was the headline feature | **cardioid** ✓ | **VERIFIED SPEC** — brand page read directly: "built-in 48 kHz/24-bit ADC", plug-and-play, and it **names Google Pixel 8**. The family has shorter variants (MLC-4 / MLC-10 / MLC-12 — the MLC-12 was priced at $18.14 with the same 48 kHz/24-bit ADC and a unidirectional capsule); **their cable lengths were never established** (§10 q.6). One of them is probably the better part |

**No AliExpress listing named Pixel, "UAC" or "USB Audio Class"** — their spec ceiling is
"Android / Type-C / plug and play". Every Cubilux page names the Pixel 8 explicitly.

**Captive cable, firmly.** Every verifiable candidate is captive. The one detachable claim (BOYA
BY-M3) could not be confirmed. On a bike, a mated micro-connector inside the chin bar is a
fretting-corrosion and intermittent-dropout site, and it is the failure mode that fails *silently
mid-ride*. Buy a third whole mic as the spare instead.

**No lavalier offers a right-angle plug.** Saramonic bundles an SR-C2005 90° adapter with the U3B;
otherwise a separate ₪3–7 USB-C 90° adapter does it.

### 5.2 Reconsidering the cardioid requirement — it should not drive the purchase

The sourcing ran into a dilemma (cardioid *or* long cable, not both), which is worth resolving
rather than paying to escape. Three terms, and the polar pattern is the smallest:

1. **Position dominates everything.** Moving the capsule from the ear to ~3 cm from the mouth is
   worth far more than any pattern — this is `RESEARCH.md` §4.6's whole argument, and it is an
   inverse-square effect, not a few dB.
2. **The dominant wind term at the capsule is local turbulence, not a sound field** — and
   directionality does nothing about local pressure fluctuation. **A foam windscreen does**, and it
   is the cheap, high-leverage item. Lavaliers normally ship with a foam muff; use it, and consider
   a second one over it. *This belongs on the parts list and was not on it.*
3. **Only then the pattern.** A true cardioid gains ~4.8 dB of random-energy efficiency over an omni
   in a *diffuse* field (**D**, standard microphone theory). Real and worth having — but ~5 dB, and
   only against the diffuse component.

**So: cardioid is a preference, not a requirement, and not worth leaving AliExpress or paying a
premium for.** Behind the chin curtain with a foam muff, a 2 m omni is a sound choice. The ₪15
cardioid is cheap enough to A/B against it anyway, which is the honest way to settle it.

### 5.3 The cart

**Buy branded, from AliExpress, by model number.** The detail is in §5.4; the reasoning is that
cheap generics almost never state a sample rate, so §5's best filter cannot be applied to them at
all — and the brands that do state one sell on AliExpress directly, so there is no trade-off to
make. An earlier draft of this section recommended buying 2–3 unlabelled generics and letting the
phone sort them out; that was the right call only while no branded part on AliExpress had been
identified.

| # | item | search for | qty | note |
|---|---|---|---|---|
| 1 | **[1005012980930887](https://www.aliexpress.com/item/1005012980930887.html)** — states `48KHz/16bit` | `usb c lavalier microphone 48khz` | 2 + spare | §5.4. **Check cable length and polar pattern on the item page first** — neither is known |
| 2 | ₪15 cardioid — [1005008221367147](https://www.aliexpress.com/item/1005008221367147.html) | — | 1 | Only to A/B cardioid against omni (§5.2). ~$5 to settle a ~5 dB question |
| 3 | Furry windscreen ("dead cat") for lavalier | `lavalier microphone furry windscreen` | 1 pack | **20–25 dB** of wind-noise reduction vs ~5–8 dB for foam — the highest-value accessory on this list |
| 4 | Foam windscreens, spares | `lavalier microphone foam windscreen 10pcs` | 1 pack | Lower profile than fur; won't rub the chin bar. The UL11 ships with one |
| 5 | 3M adhesive hook-and-loop dots | `3M adhesive hook loop dots 20mm` | 1 pack | Mounts the capsule inside the chin bar |
| 6 | Reusable velcro cable ties | `reusable velcro cable ties 150mm` | 1 pack | Manages the ~1 m of slack without letting it flap |

Roughly $45–60 in one order, under the $75 VAT line. **The UL11's AliExpress price was not
established** — Western retail is ~$20 — so confirm before assuming the total.

**Not on the list any more, and worth noticing:** no USB-C sound card, no soldering iron, no
multimeter, no ferrites, no heat-shrink, no shielded cable, no 3.5 mm breakaway coupler. The USB-C
lavalier deleted all of it (§5.1).

**The one item that cannot be bought: the strain relief** that replaces the breakaway given up in
§5. A loop of cable through something anchored to the jacket, so a snag loads the jacket and not
the connector. Item 6 plus a clothing clip does it; the principle matters more than the part.

### 5.4 The branded recommendation: MAONO UL11 (on AliExpress)

**Cubilux is dead.** Checked by the user on 2026-09-21: the MLC-12 is rated ~3★ on Amazon and
**does not ship to Israel**. That closes §10 q.5 negatively and removes the whole Cubilux family
from consideration, MLC-4 and MLC-17 included.

The resolution is better than the thing it replaces: **Saramonic, BOYA, MAONO and Comica all
operate official AliExpress stores**, so a published spec sheet and AliExpress sourcing are not in
conflict. There was never a need to leave AliExpress — only a need to search by brand and model
rather than by description.

**A retraction, recorded because it was an inference stated as fact.** A draft of this section
recommended the **MAONO UL11** (2 m, 192 kHz/24-bit, foam windscreen included) on the reasoning
that MAONO operates an AliExpress store. The store is real; **the UL11 is not in it.** Its
AliExpress catalogue is wireless and gaming microphones. The wired USB-C lavaliers from MAONO,
Cubilux and BOYA appear to be Amazon-channel products. "Brand has a store, therefore the model is
on it" does not hold.

**The technique that does work: search AliExpress by the spec, not the description.**

```
usb c lavalier microphone 48khz
type c lapel microphone 48KHz 16bit
usb c microphone 48khz 24bit wired
```

Searching `48khz` selects for listings that state a sample rate, which *is* the digital-versus-
analogue test (§5). Searching by description — "cardioid USB-C lavalier" — returns the unlabelled
generic field where nobody states anything, which is why the first sourcing pass wrongly concluded
the category was empty. **Caveat: most `48khz` hits are wireless systems** (Hollyland, OBSBOT,
Insta360, BOYA Mini). Of twelve results on one such page, exactly one was a wired USB-C lavalier.

Note also that AliExpress auto-translation renders Hebrew *דש* (lapel) as **"dash"** — "USB Type-C
Dash Microphone" is a lapel mic, not a feature.

**Both candidates from that search failed when the user opened them**, and the pattern is the
finding:

- **1005012980930887** (₪61.46, card read "USB Type-C Dash Microphone, 48KHz/16bit") — **is a
  wireless system.** The search card was misread; "48 kHz/24-bit" is standard marketing on 2.4 GHz
  lavalier systems, so that string does *not* by itself indicate a wired mic.
- **1005003021740716** (Saramonic LavMicro U3 series, ₪95.52) — **3.3★ from 3 reviews, 20 sold.**
  A real brand, but no usable social proof.

### 5.4.1 Conclusion: AliExpress is the wrong channel for a *wired* USB-C lavalier

Three candidates were offered and all three failed on inspection. That is not bad luck, it is the
category:

- **Item pages are captcha-walled**, so only search-card data is available — and search cards
  cannot reliably distinguish wired from wireless, state a cable length, or state a polar pattern.
- **The volume on AliExpress is overwhelmingly wireless.** Of twelve results on a `48khz` search,
  eleven were 2.4 GHz systems. Wired USB-C lavaliers there are low-sales, unlabelled or mislabelled.
- **The branded wired parts are not on AliExpress at all.** MAONO's store carries wireless and
  gaming mics; Cubilux has no store; BOYA's wired BY-M3 is 6 m. These appear to be Amazon-channel
  products.

**Do not spend more effort searching AliExpress for this part.** Two directions replace it —
**researched and compared head-to-head in `MIC.md`**, which supersedes the cart in §5.3: **(a)** buy a wired USB-C lavalier from an **Israeli retailer** —
costs more, but no import, fast, and *returnable*, which directly de-risks the only real unknown
("does it enumerate on the Pixel 8"); **(b)** **wireless with the transmitter inside the helmet**
(§5.5, revised).

**Ruled out on length, not on electronics** (both are genuine digital USB-C mics, just far too much
cable for a 1 m run): **BOYA BY-M3** at 6 m, and **Comica CVM-V01SP(UC)**, which exists only in
4.5 m and 6.0 m for USB-C — the 2.5 m version is the **Lightning** model.

**Neither primary nor backup is cardioid**, which is acceptable on §5.2's reasoning: the pattern is
worth ~5 dB against the diffuse component, while position and the windscreen are the large terms,
and the UL11 ships with a windscreen. The only cardioid found on AliExpress remains the unverified
₪15 [1005008221367147](https://www.aliexpress.com/item/1005008221367147.html), which is cheap
enough to buy alongside and A/B.

### 5.5 Why not wireless, analogue, or a Sena mic?

Asked by the user on 2026-09-21. Recorded so it is not re-litigated.

**Wireless (2.4 GHz lavalier TX/RX) — the original dismissal was wrong, and is withdrawn.**
An earlier version of this paragraph rejected wireless because "the capsule is inside the
transmitter body, so it clips to the chest — outside the helmet, in the airstream". **That assumed
the transmitter has to live on the chest. It does not:** the current compact class is ~8 g and
~9 mm thick (Hollyland Lark A1: 30 × 16.3 × 8.8 mm), small enough to Velcro *inside the chin bar*,
which puts its built-in capsule at the mouth with no cable at all. The proper evaluation that
paragraph asked for is **`MIC.md`** (2026-09-21). Its findings, in brief:

- **The "third battery" objection does not bind.** The TX runs 9 h (**D**, vendor) against the
  AirPods' measured 5 h 43 m (§2.5) — the AirPods run out first. The real cost is one more thing to
  charge the night before.
- **The 2.4 GHz contention risk is real but cheaply defused:** force the hotspot to 5 GHz. What
  remains untested by anyone is near-field desensitisation of a receiver plugged into the phone
  that is itself transmitting (`MIC.md` §2.5, test A3).
- **It is the cheap route, not the expensive one:** ₪299–350 for both riders, bought in Israel and
  returnable, against ₪624+ for two wired Sennheiser XS Lav USB-C.
- **It deletes the tether** — the no-breakaway risk §5 calls the biggest new risk in this plan.
- **The "plug a wired lav into the transmitter" fallback does not exist** in this size class: none
  of the compact transmitters has a 3.5 mm input.
- **What now decides it is physical fit in the chin bar and capsule overload at mouth distance**
  (`MIC.md` §3 — a risk that applies to the wired route too: the XS Lav clips hard at 110 dB SPL).

`MIC.md` recommends wireless as primary and one wired XS Lav as backup and known-good control,
gated on a zero-cost fit test.

**A "simple" analogue mic — on a Pixel 8 this is the *complex* option, not the simple one.**
The Pixel 8 has no analogue audio mode (`HARDWARE.md` §1, verified), so an analogue capsule needs
an ADC regardless; the only question is where it lives. Putting it in a separate dongle is exactly
`HARDWARE.md` §1 + §2, and costs: a USB-C sound card (₪47 ×2), a multimeter to determine whether
bias sits on tip or ring (adapter-dependent), a 2-pole vs 3-pole plug problem, probably a soldering
iron (no AliExpress helmet mic ships as a plain electret on a 3.5 mm plug), and the "mic only
enumerates with something in the headphone jack" quirk. **The USB-C lavalier is the same ADC moved
into the plug and pre-wired, for less than the dongle alone.**

**A Sena/Cardo microphone** is an analogue electret on a proprietary connector with no published
pinout: cut it off, meter it, solder a 3.5 mm plug — *and still buy the sound card*. Worst of both.

**A whole Sena/Cardo unit would work**, and that should be said plainly. `RESEARCH.md` §4.7:
wired boom mic and wired speakers in one unit, no Bluetooth between mic and speaker, which is the
entire trick; §4.8 ranked buying a pair third. The objections are commercial, not technical —
$300–600 for two, and it replaces the app (191 tests, music sync between bikes, voice commands).
If the goal were only "talk to my passenger", Sena was always the answer. **The app is the
project**, and that is a different goal.

---

## 6. The ranked answer

1. **Keep the AirPods Pro 2 for playback and wire only the mic, as a USB-C lavalier. Zero cost for
   playback.** ANC is the only technology that moves the 50–400 Hz band; Apple is the only vendor
   with certified data; §2.3 puts the rider at ~72 dB(A) at 110 km/h; and their own ears already
   agree with the arithmetic.
2. **Build the playback DSP (§3).** Worth more than any purchase available, and after §4 it is also
   the replacement for the platform noise suppressor the split config gives up.
3. **Phone Ear Protect (Netanya / Ramat Gan) about custom-moulded "SLEEVES" for existing AirPods.**
   Local, not Apple, and the only genuinely *additive* idea found — it stacks a custom seal on top
   of ANC instead of choosing between them, and it targets the weakest number on Apple's own sheet
   (passive 125 Hz = 11 dB, σ = 3, i.e. fit-dependent). Price unpublished; effect on AirPods
   attenuation **U**; worth asking whether the mould disturbs the vent geometry the ANC relies on.
4. *Declined:* AirPods Pro 3 (§2.7). *Rejected on arithmetic:* custom moulds as a replacement
   (§2.6), foam plugs plus helmet speakers (§1.4 — 0 dB of SNR), any chi-fi IEM (no published
   isolation measurement exists for any of them).

**Plainly, as the brief demanded: the AirPods are already the best option at this price and at any
price, for this job. The money the user is willing to spend should not be spent here.** The one
hardware purchase this document endorses is a ~$20 microphone, which is where `RESEARCH.md` §4.8
put the anchor in the first place.

**Two findings that argue against chasing more isolation than §1.4 asks for**, carried over from
the brief so they are not lost with it:

- **Earplugs *improve* a rider's detection of traffic warning sounds at ≥40 mph** (Binnington 1993,
  **M/R**) — because at those speeds the wind, not the warning, is what masks. So the safety
  instinct that less isolation means more awareness is backwards in this regime.
- **But riders also report over-isolation costing them engine feedback** (**A**). Combined with
  §1.4 — where 18 dB(A) is the target and the AirPods already deliver ~30 — there is no case for
  buying *more* attenuation. The remaining work is all on the speech side.

## 7. What would change the answer

| finding | how it shows up | what it would change |
|---|---|---|
| The 1 kHz trough is much deeper than 20 dB | T2 | makes the speech band the binding constraint; pushes §3's EQ harder and reopens §2.7 |
| ~~ANC pumps or roars in helmet turbulence~~ | ~~T3~~ | **closed 2026-09-21** — the user has ridden with it and reports ANC in the helmet is good |
| The full-face speech-band spectrum is ~10 dB louder than §1.1 | T2 | shrinks every SNR margin here by 10 dB |
| `getRoutedDevice()` does not return the USB port with A2DP active | T4 | kills the split config; back to HFP, and the mic gain is lost |
| 150–300 ms proves unbearable in conversation | T5 | the whole architecture reverts to HFP/SCO, where `RESEARCH.md` §4.8 already judged it "honestly not much worse once wind dominates" |

## 8. The experiment, cheapest first — all of it before spending anything

| # | test | cost | what it settles |
|---|---|---|---|
| **T0** | ANC on, play music through the AirPods on the Pixel, talk for ten seconds. Does the music duck? Only if it does, borrow the passenger's iPhone and turn Conversation Awareness off | 0 | settles §2.5's one undocumented behaviour, at the cost of ten seconds |
| **T1** | The baseline ride recording already planned (`HANDOFF.md` step 2): `captureDump` on, ~110 km/h, keep the WAV | 0 | whether today's path is merely poor or actually unusable |
| **T2** | **Ear-position spectrum.** A mic at ear position inside the helmet at 110 km/h, recorded with and without an AirPod in the ear | 0 | §1.1's spectrum for *this* helmet and head, and the AirPods' real in-situ attenuation including the 1 kHz trough. The single highest-value free measurement available |
| ~~T3~~ | ~~ANC on / off / transparency in helmet turbulence~~ | — | **closed 2026-09-21 by the user's riding experience — ANC in the helmet is good.** Not re-tested |
| **T4** | Bench: `UNPROCESSED` + `setPreferredDevice(usb)` in `MODE_NORMAL` with A2DP playing; check `getRoutedDevice()` | 0 | confirms §4 on real hardware; replaces Stage B risk 2 |
| **T5** | Build the §3 DSP, A/B it on the same route, and measure mouth-to-ear latency by hand | 0 | how much of the gap software closes, and whether A2DP latency is liveable |
| **T6** | Only if T2 or T3 fail: revisit hardware, starting from §6 item 3 | — | — |

**T2 needs one small code change that does not exist yet.** `captureDump` records through
`AudioSource.VOICE_COMMUNICATION` (`VoiceEngine.kt:187-194`) with AEC, NS and platform AGC
attached — which destroys a spectrum measurement. A raw `AudioSource.UNPROCESSED` capture mode is a
prerequisite. Conveniently it is the **same change §4 requires anyway**, so the two land together
and T4 validates both.

There is also no WAV analysis tooling anywhere in `tools/` — a short Python/numpy octave-band
script is the other prerequisite, and is throwaway data work, so Python is the right choice.

## 9. What this does to `HARDWARE.md` — proposed, not applied

Nothing below has been written. It is here so the consequences are visible while we iterate.

- **Status block:** §3 is resolved — buy nothing; the rider keeps the AirPods Pro 2. §1 and §2 are
  superseded by §5 of this document, by the user's decision.
- **§1 (adapter) and §2 (mic):** superseded by a single USB-C lavalier with a built-in ADC.
- **§5 (cabling):** the headphone-line rows are cancelled; the shielded extension and ferrites are
  deleted; the breakaway section needs rewriting around USB-C's 8–20 N retention and a mechanical
  strain relief, with the chest-dongle fallback recorded.
- **Open risks:** 3, 5 and 7 are struck. A new top risk replaces them: *USB-C has no breakaway, and
  8–20 N of retention means a snag yanks the phone rather than releasing.*
- **Cost table:** ≈₪220 → **≈₪125 (~$41)** — two ₪15 cardioids plus a spare, and one ₪95 Saramonic
  as the known-good control (§5.3). One order, under the $75 VAT line. Add a **foam windscreen**
  row (§5.2): never on the list, costs cents, and addresses the dominant wind term at the capsule.
- **Cable run:** §5's 1.6 m computation is superseded by the user's measurement of **~1 m**; the
  standing-on-the-pegs allowance does not apply to motorway riding. Target 1.5 m of cable, and
  record *why* — the slack is the substitute for the breakaway USB-C does not have.
- **Where the plan looks wrong:** add that the 30 dB requirement was not merely unreachable, it was
  aimed at the wrong band.

## 10. Open questions

1. ~~Which AirPods does the passenger have?~~ **Answered 2026-09-21: they have noise-cancelling
   AirPods**, confirmed by the user. ANC implies a sealed Pro-type bud, so §2's passive + ANC
   numbers apply to that end too and the intercom is viable on both sides. The exact generation is
   still unconfirmed, which only affects whether §2.7's Pro 3 figures or §2.1's Pro 2 figures are
   the right ones for them.
2. **Do the AirPods negotiate mSBC or fall back to CVSD on the Pixel today?**
   (`RESEARCH.md` §7 q.1, never settled.) It sets how much of §0.5's voice improvement is real.
   Free to test: record the far end on the current build and look for content above 3.4 kHz.
3. Does the Pixel 8 ship AOSP's `enginedefault` audio policy engine, or a vendor
   `engineconfigurable`? (§4's guarantee is for the former.)
4. Does a class-compliant USB-C lavalier enumerate and capture on a Pixel 8 at all? No report
   found either way. §5.3's ₪47 test pair is the cheapest way to find out.
5. ~~Does Amazon or cubilux.com ship to Israel?~~ **Answered 2026-09-21, negatively: it does not
   ship to Israel, and the MLC-12 is rated ~3★ on Amazon.** The whole Cubilux family is out. See
   §5.4 — the replacement (MAONO UL11) is a better part *and* is on AliExpress.
6. ~~What cable length do the shorter Cubilux lavaliers have?~~ **Answered 2026-09-21: MLC-12 and
   MLC-4 are both 5 ft ≈ 1.52 m** — the right length. See §5.4; MLC-12 is the recommended part.
7. Which Saramonic variant does [1005003021740716](https://www.aliexpress.com/item/1005003021740716.html)
   actually ship — U3A (2 m) or U3B (6 m)? Not visible from search results; the item page would
   say. U3A fits a 1 m run; U3B does not.
8. What does an Ear Protect SLEEVES mould cost, and does it disturb the AirPods' vent geometry?
9. Actual mouth-to-ear latency on the real pair. Nothing published covers it, and no API exposes it.

---

## Sources

**Acoustics and hearing protection**
- Brown & Gordon 2011, *Motorcycle Helmet Noise and Active Noise Reduction*, Open Acoustics Journal 4:14–24 — https://benthamopen.com/contents/pdf/TOACOJ/TOACOJ-4-14.pdf
- Apple, *AirPods Pro Hearing Protection Data Sheet*, Rev A, Oct 2024 — https://www.apple.com/airpods-pro/pdf/Hearing_Protection_data_sheet_October_2024.pdf
- Apple, *EN 352 Hearing Protection Data Sheet* (AirPods Pro 3), Sept 2025 — https://regulatoryinfo.apple.com/cwt/api/ext/file?fileId=hearingProtection%2FHP_Datasheet_EN_352_GB_English_1757070523746.pdf
- National Acoustic Laboratories, *Evaluating AirPods Pro 2 for Hearing Protection and Listening* — https://canadianaudiologist.ca/evaluating-apple-airpods-pro-2-hearing-protection-and-listening/
- *Real-world attenuation of custom-moulded earplugs: F-MIRE*, Applied Acoustics — https://www.sciencedirect.com/science/article/abs/pii/S0003682X12000266
- *Effect of wind noise on active noise control headphones*, Applied Acoustics 2024 — **blocked, could not read** — https://www.sciencedirect.com/science/article/abs/pii/S0003682X24001026
- Elacin ER EN 352-2 product sheet — https://www.elacin.com/media/2mhd5x2t/elacin-b2b-en-productsheet-02-er.pdf
- Ultimate Ear motorcyclist SoundEar — https://ultimateear.com/product/soundear-motorcycle-custom-ear-plug/
- Ear Protect, Israel — https://www.earprotect.co.il/motorcycle/ · SLEEVES for AirPods — https://www.earprotect.co.il/אטמים-מתאימים-לאוזניות-sleeves/
- RTINGS AirPods Pro 2 — **blocked/paywalled, not read** — https://www.rtings.com/headphones/reviews/apple/airpods-pro-2nd-generation-truly-wireless

**Platform**
- AOSP audio policy `Engine.cpp` — https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/audiopolicy/enginedefault/src/Engine.cpp
- AOSP `AudioRecord.java` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/AudioRecord.java
- Android CDD 5.6 audio latency — https://android.googlesource.com/platform/compatibility/cdd/+/refs/heads/master/5_multimedia/5_6_audio-latency.md
- Google Oboe wiki, Bluetooth audio — https://github.com/google/oboe/wiki/TechNote_BluetoothAudio
- Apple Developer Forums 741513 (the WWDC-lab answer) — https://developer.apple.com/forums/thread/741513 · adjacent: 737904, 784318
- react-native-audio-api #1254 (platform AEC needs a voice-communication output) — https://github.com/software-mansion/react-native-audio-api/issues/1254
- SoundGuys, *Android's Bluetooth latency needs a serious overhaul* (WALT rig, 2,800 points) — https://www.soundguys.com/android-bluetooth-latency-22732/
- Stephen Coyle, AirPods Pro 2 audio latency (iOS) — https://stephencoyle.net/airpods-pro-2

**Hardware**
- USB Type-C Cable and Connector Specification R2.5 — https://usb.org/document-library/usb-type-cr-cable-and-connector-specification-release-25
- Hackaday, *All About USB-C: Illegal Adapters* — https://hackaday.com/2022/12/27/all-about-usb-c-illegal-adapters/
- Cubilux MLC-12 USB-C lavalier — https://www.cubilux.com/products/usb-c-lavalier-microphone-1
- Saramonic LavMicro U3A — https://saramonicusa.com/lavmicro-u3a-ultracompact-clip-on-lavalier-microphone-with-usb-c-for-android-mobile-devices-computers-with-6-6-2m-cable-right-angle-adapter/

**Anecdote**
- MotoFomo, *Using AirPods Pro on Motorcycles* — https://motofomo.com/airpods-pro-on-motorcycles/
- ModernVespa, reducing helmet noise with AirPods Pro II — https://modernvespa.com/forum/topic181906
- MacRumors / Apple Discussions / TrainerRoad wind-noise threads — https://forums.macrumors.com/threads/airpods-pro-2-wind-noise-while-cycling-worse-than-1st-gen.2363452/ · https://discussions.apple.com/thread/255266352 · https://www.trainerroad.com/forum/t/do-not-use-airpods-pro-2-for-anc-with-fan-wind-noise/76283
