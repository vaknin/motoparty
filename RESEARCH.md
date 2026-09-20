# Research notes, 2026-09-20

Findings from a design discussion, gathered by three research agents. **Nothing here has been
implemented** — no code was changed on the day this was written. Sources are linked; claims that
could not be verified are marked **[unverified]** and should be treated as leads, not facts.

Context that shapes everything below: rider on a Pixel 8 with AirPods Pro 2, passenger on an
iPhone with AirPods, phones linked over the Pixel's 2.4 GHz hotspot. User is in **Israel**
(not the EU — this matters for one Apple feature, see §4.5).

---

## 1. The link: hotspot, mobile data, and iOS

### 1.1 What needs internet and what does not

| Thing | Needs coverage? |
|---|---|
| Running the hotspot | No. A hotspot is a local router; an upstream is optional. Proven: the 2026-09-19 bench ran it with home Wi-Fi as upstream. |
| Intercom, clock sync, control channel | No. Phone-to-phone over the LAN. |
| Playing already-cached music | No. |
| Fetching a new track from YouTube | **Yes**, on the Pixel only. The iPhone always gets the file from the Pixel. |
| Keeping the iPhone attached | Effectively yes — see below. |

Mobile data on the Pixel is in the setup checklist mainly for the last row.

### 1.2 iOS behaviour on an internet-less Wi-Fi network

The canonical source is Quinn "The Eskimo!" (Apple DTS), ["The iOS Wi-Fi Lifecycle"](https://developer.apple.com/forums/thread/734361):

> "When iOS auto-joins a Wi-Fi network, it evaluates whether the network leads to the wider
> Internet. If not, it immediately turns around and leaves that network."
>
> "iOS doesn't do the viable IP evaluation if you manually join a Wi-Fi network. In that case,
> it'll stay on that network until it leaves for some other reason, like device sleep."
>
> "There are various circumstances where iOS will leave a Wi-Fi network… common examples are:
> Device sleep, which typically happens shortly after screen lock; After 30 minutes of not having
> a Wi-Fi app active."

Three distinct moments, three behaviours:

- **Manual join, no internet** → stays associated. Shows "No Internet Connection", offers to use
  cellular; "Keep Trying Wi-Fi" / "Without Internet" keeps the association.
- **Already associated, internet dies later** → stays associated. Default route moves to cellular.
  **This is the normal ride scenario and it is fine.**
- **Auto-join later, still no internet** → drops off, and may set an internal "not suitable" flag
  that stops it even probing for that SSID. Forgetting the network clears it.
  (Device logs in [forum thread 706649](https://developer.apple.com/forums/thread/706649):
  `auto-join disabled for potentially bypassing captive detection`. Anecdotal but credible.)

Plus the two leave-triggers above: **screen lock/device sleep**, and **30 minutes with no
"Wi-Fi app" active**. A "Wi-Fi app" means one declaring `UIRequiresPersistentWiFi` in Info.plist.

### 1.3 What to do about it

Free, no developer account:

- SSID → (i): **Auto-Join on**, **Private Wi-Fi Address off**, **Low Data Mode on**,
  **Limit IP Address Tracking off**.
- **Connectivity Assist off** for that network (renamed from Wi-Fi Assist in iOS 27, now has a
  per-network override; iOS ≤26: Settings → Cellular → Wi-Fi Assist).
- Set **`UIRequiresPersistentWiFi = true`** in the iOS app — defeats the 30-minute idle leave.
  The app's permanent background audio session should also help.
- An unsigned **`.mobileconfig`** with `AutoJoin = true`, `CaptiveBypass = true`,
  `MACAddressRandomizationEnabled = false` installs from Settings with no MDM and no supervision.
  **[unverified]** whether `CaptiveBypass` also suppresses the broader viability check — Apple only
  documents it for the captive sheet. Worth a five-minute test.
- Recovery when stuck: forget the network and rejoin manually.

Not available / not advisable:

- **`NEHotspotConfiguration`** (one-tap programmatic rejoin) needs the
  `com.apple.developer.networking.HotspotConfiguration` entitlement → **a paid Apple Developer
  membership**. Out, since the project uses a free Apple ID. If that ever changes, use
  `joinOnce = false` (with `true` the network drops 15 s after the app backgrounds).
- **Do not spoof Apple's internet probe** from the Pixel. Quinn: a catch-all HTTP responder makes
  iOS classify the network as *captive*, and cancelling that sheet makes iOS **leave**. It would
  also need root on Android (system-owned dnsmasq), and success would make the hotspot the default
  route, killing the iPhone's real connectivity. Two further obstacles:
  current iOS sends only AAAA and HTTPS-RR DNS queries, no A query
  ([thread 816946](https://developer.apple.com/forums/thread/816946)), and the probe set is
  undocumented.

### 1.4 Socket details worth keeping

- `NWPathMonitor` reports **`.satisfied`** on an internet-less network — cannot be used to detect
  "no internet" ([thread 750492](https://developer.apple.com/forums/thread/750492)).
- Pin peer traffic with `requiredInterfaceType = .wifi` + `prohibitedInterfaceTypes = [.cellular]`.
  **Avoid `requiredLocalEndpoint`**: connections bind to the address, not the interface, so a DHCP
  renewal silently kills them.
- **Multicast entitlement not needed.** Per [TN3179](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy),
  Bonjour/`.local` and outgoing TCP are permitted; only raw broadcast/multicast needs Apple's
  managed entitlement. Our discovery uses Bonjour (`NWBrowser`) plus unicast TCP — fine.
- Trigger the Local Network permission prompt **in the foreground at launch**: a background attempt
  with permission undetermined is denied *silently*, with no alert and no recorded decision.

### 1.5 Long shot

**Wi-Fi Aware** (`WiFiAware` framework, new in iOS 26) is strategically the right answer —
peer-to-peer with no access point and no internet, foreground and background, iPhone 12+,
entitlement needs no Apple approval. But **[unverified]** whether iOS↔Android interop works at
all; Apple frames it around certified accessories. Hardware spike only; don't plan around it.

---

## 2. Triggers: the glove problem

### 2.1 Handlebar Bluetooth remote — the real answer to gloves

Search term that matters: **"bluetooth media button"**. Also:
`motorcycle handlebar bluetooth media remote`, `bluetooth media button remote steering wheel`,
`bluetooth music control button motorcycle waterproof IPX6`.

**Avoid**: `bluetooth remote shutter`, `selfie remote`, `camera shutter ring`, `AB Shutter 3`,
`page turner`. These enumerate as keyboards and send Enter + Volume Up only — useless as media keys.

| Product | Price | Notes |
|---|---|---|
| **Moman BTC1** | ~€25 | BT 5.3, IPX6, handlebar/strap mount, rechargeable. Best-documented cheap option. |
| Generic IPX6 handlebar media buttons | €10–30 | Same few OEM designs rebadged. A lottery, same feature set. |
| **JaxeADV BarButtons** | €149 assembled, €49 kit | Hard-wired (no battery), 22 mm clamp, glove-friendly by design. The **only** product where media-key emission is documented and configurable (manual lists keymap 5, "media version"). Rain-resistant, not sealed. |
| Satechi Media Button | ~€30 | Excellent, CR2016 coin cell lasting ~2 years — but **no weather rating at all**. |

**Ruled out:** Sena RC4 and Cardo remotes — proprietary BLE, pair only with their own headsets,
never appear to Android as HID/media devices.

**How to verify before the return window closes:** pair it, then
`adb shell getevent -lt` and press each button. Want: `KEY_PLAYPAUSE`, `KEY_NEXTSONG`,
`KEY_PREVIOUSSONG`. Reject if you see `KEY_ENTER` or `KEY_VOLUMEUP`.

The mechanism: HID Consumer Page (0x0C) usages `0xCD`/`0xB5`/`0xB6` map to
`KEYCODE_MEDIA_PLAY_PAUSE`/`_NEXT`/`_PREVIOUS`. Keyboard-page `0x28` (Enter) and consumer
`0xE9`/`0xEA` (volume) do not, ever.

### 2.2 Android quirks for these remotes

- **Media keys do reach our MediaSession with a nav app in front** — nav apps don't consume them.
  Path: HID key → focused window → `PhoneFallbackEventHandler` → `MediaSessionLegacyHelper` →
  active session. A full-screen game that swallows all keys would break it.
- Our app must hold the **most recently active** media session, or keys go elsewhere.
- Handle **`KEYCODE_HEADSETHOOK`** too — several remotes emit headset-hook semantics for the centre
  button. (`LinkHost.onMediaKey` already does.)
- **The on-screen keyboard disappears system-wide** while an HID keyboard is connected. Standard
  AOSP behaviour. Fix: Settings → System → Languages & input → Physical keyboard → "Show on-screen
  keyboard". Worth warning about in the app.
- Cheap remotes sleep after ~1 min idle; the **first press after sleep is consumed** waking them.

### 2.3 Consequence for the button design

A remote has five buttons, and the app already maps play/pause → talk and next → voice command
(`Settings.kt:18,20`). **So the remote makes the existing two-zone design glove-friendly with no
code change.** The single-button design below becomes an elegance, not a necessity. Buy the €25
remote and test before building anything.

---

## 3. Speech recognition on already-captured audio

Goal: one button. Press it, the intercom opens; while it is open, recognise a keyword + command
("motoparty, next") from audio **already being captured**, with no second mic session and no second
A2DP↔HFP switch.

### 3.1 The finding: this is cheap, not expensive

**`RecognizerIntent.EXTRA_AUDIO_SOURCE`, public API since API 33**, hands the recognizer a
`ParcelFileDescriptor` instead of letting it open the mic. Combined with `EXTRA_SEGMENTED_SESSION`
it becomes **continuous**. From the javadoc:

> "Optional `android.os.ParcelFileDescriptor` pointing to an already opened audio source for the
> recognizer to use. … If this extra is not set or the recognizer does not support this feature, the
> recognizer will open the mic for audio."
>
> "This can also be used as the string value for `EXTRA_SEGMENTED_SESSION` to enable segmented
> session mode. The audio must be passed in using this extra. The recognition session will end when
> and only when the audio is closed."

Companion extras and their defaults — **already exactly what `VoiceEngine` produces**:
`EXTRA_AUDIO_SOURCE_CHANNEL_COUNT` = 1, `EXTRA_AUDIO_SOURCE_ENCODING` = `ENCODING_PCM_16BIT`,
`EXTRA_AUDIO_SOURCE_SAMPLING_RATE` = 16000. `EXTRA_BIASING_STRINGS` is a free accuracy win for a
fixed command vocabulary.

Works with `createOnDeviceSpeechRecognizer()` — used in production by `jamsch/expo-speech-recognition`
and `openclaw/openclaw`.

**Effort:** a ~40-line PCM tee in `VoiceEngine.captureLoop` next to `encoder.encode(pcm)`, a rewrite
of `Transcriber.kt` from one-shot to a segmented listener (`onSegmentResults` /
`onEndOfSegmentedSession`), and **deleting** the recognizer's `router.enterCall()` at
`LinkHost.kt:414`. About a day, no model files, no tuning.

**And the keyword spotter disappears** — with recognition running for the duration of a talk,
"motoparty" is just a required first word in the existing `CommandParser` grammar.

### 3.2 Traps, if it gets built

1. **Do not close the read end after `startListening()`** — AOSP marshals the PFD after the call
   returns; closing early gives every session `ERROR_CLIENT`.
2. **Feed at real time, never from the capture thread.** Pipe buffer is 64 KiB; a blocked write
   would stall `voice-capture`. Bounded queue, drop on overflow.
3. **End by closing the pipe (EOF), not `stopListening()`** — `stop()` races the on-device
   recognizer and loses the final segment.
4. **Detect silent fallback.** If the recognizer ignores the extras it opens the mic, which under
   `MODE_IN_COMMUNICATION` may be silenced — looks like "recognition never matches". Probe with
   `AudioManager.getActiveRecordingConfigurations()` / `isClientSilenced()`.
5. Gate on API 33 (minSdk is 29; `Transcriber.kt` already gates on 31).

**[unverified]** Whether Google's on-device recognizer honours `EXTRA_AUDIO_SOURCE` on Android 16/17
on a Pixel 8. The javadoc explicitly permits a recognizer to ignore it, and the failure is silent.
Evidence is positive but indirect (Galaxy S23/Android 14, plus two shipping apps).
**Spike first:** ~50 lines, feed a known WAV through a paced pipe, see if text comes back.

### 3.3 Possible live bug in the current code

Android's [Sharing audio input](https://developer.android.com/media/platform/sharing-audio-input)
policy, verbatim:

> "Two ordinary apps can never capture audio at the same time."
>
> "A voice call is active if the audio mode returned by `AudioManager.getMode()` is `MODE_IN_CALL`
> or **`MODE_IN_COMMUNICATION`**. … The call always receives audio. The app can capture audio if it
> is an accessibility service."

`AudioRouter.enterCall()` sets `MODE_IN_COMMUNICATION`, and `LinkHost.kt:414` does exactly that
*for the recognizer* — which runs in a **separate app** (Google's). If the rule bites, **voice
commands silently return nothing, every time**.

Mitigating doubt: the rule is written around an actual call, and it is not certain the framework
applies it when nothing else is capturing. But note that **real spoken commands have never been
tested** — `HANDOFF.md` still lists them under "Waiting on the user". A single spoken command into
the AirPods settles it. If broken, the fix and the §3.1 redesign are the same work.

Also: the recognizer's route switch costs **0.5–1.3 s** per `enterCall` by our own bench notes.
Removing it makes commands noticeably faster either way.

### 3.4 Fallbacks, ranked

1. **ML Kit GenAI Speech Recognition** — `AudioSource.fromPfd()`, documented as "raw, headerless
   16-bit PCM, mono, 16 kHz", exactly our format. But `1.0.0-alpha1`, no SLA, and **not supported on
   devices with an unlocked bootloader**.
2. **Recognition on the passenger's iPhone.** The rider's voice already arrives there over UDP;
   `SFSpeechAudioBufferRecognitionRequest` is buffer-based and Apple imposes no mic-only rule.
   `CommandParser` is already shared. Drawbacks: commands stop working when the passenger's phone is
   absent/asleep/out of range; recognising Opus-decoded audio with DTX holes hurts accuracy;
   +200–400 ms; `SFSpeechRecognitionTask` is limited to ~1 minute **[unverified, widely reported]**.
   Good redundancy, wrong as primary.
3. **Porcupine** keyword spotting on tee'd PCM — needs an account/AccessKey and a custom-trained
   wake word; more friction than §3.1, and still needs a recognizer for the command words.
4. **sherpa-onnx / Vosk** — most work. Correctly last.

**Closed, definitively:** `AlwaysOnHotwordDetector` / `createAlwaysOnHotwordDetector()` has been
`@SystemApi` since Android 12 — not public API, needs to be the preinstalled assistant.

**Also closed:** a second `AudioRecord` or a second mic-owning recognizer (see §3.3). Same-UID dual
`AudioRecord` is **[unverified]** either way, but pointless — the recognizer is a different process.
There is no framework API to fork a mic stream; the supported way is to copy the buffer you already
have.

---

## 4. Bluetooth audio quality

### 4.1 Bottom line

**On AirPods, good mic + good playback simultaneously is impossible, and no software change fixes
it.** A2DP is output-only; any input forces HFP/SCO, a single mono bidirectional channel. AOSP states
A2DP and SCO **cannot be concurrent**
([Audio Managed SCO rearchitecture](https://source.android.com/docs/core/audio/sco-audio-mgmt)).

### 4.2 LE Audio — the fix that doesn't apply

LE Audio replaces SCO with Isochronous Channels and is designed for exactly this. Google:

> "Headsets can maintain high output audio quality when using microphones. Bluetooth classic lowers
> audio quality when using Bluetooth microphones. With BLE Audio, input and output sampling can
> reach 32 kHz."

- **Pixel 8 supports it as a source** (built in since Android 13; Google lists "Pixel 8 series and
  newer" for Auracast). **[partly unverified]** for the exact Android 16/17 build — check
  Settings → Developer options → Bluetooth.
- **AirPods do not support it. Any model, including AirPods 5 (Sept 2026).** Apple has never claimed
  it; specs still list Bluetooth 5.3; SoundGuys measured SBC/AAC only. Apple went proprietary instead
  (a 5 GHz protocol for Vision Pro / Apple silicon). So `TYPE_BLE_HEADSET` will never appear and our
  code will always take the SCO branch.
- Honest ceiling even where it works: "can reach 32 kHz" is a ceiling. BAP mandates 16_2 (16 kHz)
  for conversational contexts; 32_2 is optional. Many headsets still drop to 16 kHz bidirectional for
  a call. **[unverified]** what a Pixel 8 + Pixel Buds Pro 2 actually negotiates for third-party VoIP
  — **test before buying hardware**.
- Earbuds that do work with a Pixel: Pixel Buds Pro / Pro 2, Galaxy Buds 2 Pro / 3 / 3 Pro,
  Sony WF-1000XM5, WH-1000XM6.
- **Our Android code is already written the right way** — `setCommunicationDevice()` with
  `TYPE_BLE_HEADSET` preferred is exactly the documented LE Audio path. It just has nothing to bind to.

**Note:** this would only fix the rider's half. The iPhone/AirPods passenger stays on classic
Bluetooth, and the link is limited by the worse half.

### 4.3 HFP super-wideband — real, shipping, and doesn't help

| Codec | Rate | Bandwidth | Profile |
|---|---|---|---|
| CVSD | 8 kHz | ~150 Hz–3.4 kHz | HFP ≤1.5 |
| mSBC (wideband) | 16 kHz | 50 Hz–7 kHz | HFP 1.6+ |
| LC3-SWB / aptX Voice | 32 kHz | 50 Hz–14 kHz | HFP 1.9 |

The Pixel 8 generation shipped SWB, on by default. But **AirPods show no evidence of LC3-SWB or
aptX Voice** **[unverified — nobody has logged an AirPods↔Android HFP negotiation]**; mSBC wideband
is the strong prior. And even with SWB, playback still goes down the same mono channel: mono 32 kHz
instead of mono 16 kHz. Not music.

No public API or developer setting to request or query SWB. (Developer Options' Bluetooth codec /
sample-rate toggles are **A2DP only**.) Empirical check: record at 32 kHz, look for content above
7 kHz.

### 4.4 iOS may already be giving us 24 kHz — free win

Apple does **not** use SCO/mSBC with AirPods. Since late 2021 the call path is **AAC-ELD at 24 kHz
mono both directions**. Developers report iOS forcing `AVAudioSession.sampleRate` to **24000**
whenever AirPods are connected, regardless of `setPreferredSampleRate()`
([thread 797033](https://developer.apple.com/forums/thread/797033)).

**Action: log `AVAudioSession.sharedInstance().sampleRate` on a real iPhone.** If it reports 24000,
our Opus encoder is downsampling to 16 kHz for nothing — Opus handles 24 kHz natively
(superwideband). Free bandwidth on the iOS half, no hardware change.

**[unverified]** whether that 24 kHz is genuine acoustic bandwidth or partly SBR-reconstructed, and
**[weakly sourced]** whether third-party apps get the same path as FaceTime (2022-vintage reporting,
no Apple documentation; the `sampleRate == 24000` reports are the corroboration).

### 4.5 iOS 26 `bluetoothHighQualityRecording` — available to us (not EU)

New in iOS 26, requires an H2 chip (AirPods 4, **AirPods Pro 2**, Pro 3). Apple's docs, verbatim:

> "An option that indicates to enable high-quality audio for input and output routes. Specifying this
> option enables full-bandwidth audio when the Bluetooth route supports it, such as on certain AirPods
> models. … You can request high-quality recording only when using the `default` audio session mode."
>
> "**Important:** Bluetooth high-quality recording isn't currently supported in the European Union."
>
> "**Note:** This option may increase input latency when enabled and isn't recommended for real-time
> communication usage."

**The user is in Israel, so the EU restriction does not apply.** But it is disqualified from
`.voiceChat` mode and explicitly not recommended for real-time comms, so it is **unusable for a live
intercom**. It would fit a store-and-forward design — except there is no Android equivalent with
AirPods, so the rider's half would still be stuck. Capability detection:
`inputPort.bluetoothMicrophoneExtension?.highQualityRecording.isSupported`.

**[unverified]** the actual sample rate it delivers (Apple never states it) and whether concurrent
playback really stays at A2DP quality.

Also: `.allowBluetoothA2DP` + `.playAndRecord` does **not** give an AirPods mic with A2DP output.
Apple: with both A2DP and HFP options set, "the system gives hands-free ports a higher priority for
routing". A2DP-only gets you A2DP output plus the **phone's** mic.

### 4.6 Reality check: bandwidth is the wrong variable

- Inside a full-face helmet, wind noise is **95–105 dB at 100 km/h**; average 103 dB, best-measured
  helmet (Schuberth C3 Pro) 82 dB. Speech at the mic is maybe 75–85 dB.
- Intelligibility is carried overwhelmingly **below ~4 kHz**. Narrowband→wideband (8→16 kHz) is a
  genuine intelligibility gain — **we already have it**. Wideband→super-wideband is a *quality* axis,
  not an intelligibility one.
- And it collapses in noise: bandwidth-extension listening tests found gains "significantly improve
  the speech quality when no background noise was present", while "the mean quality scores were
  slightly but not significantly increased for noisy speech". That is exactly our regime.

**Priority order for intelligibility: (1) mic position and wind shielding, (2) noise suppression
tuned for wind, (3) codec bandwidth — a distant third.** Optimise input SNR, not sample rate.

### 4.7 What Cardo and Sena actually do

They sidestep Bluetooth entirely: one integrated unit with a **wired boom mic centimetres from the
mouth** and **wired speakers**. No Bluetooth between mic and speakers means no profile arbitration.
That is the whole trick, and it is why they work at motorway speed.

Telling detail: Sena's "Audio Multitasking" (music overlaid under intercom) **disables their HD voice
modes** — "to ensure optimal audio performance". Even purpose-built hardware trades quality for
simultaneity. Neither vendor publishes sample rates **[unverified]**.

### 4.8 The one hardware change that dissolves everything

A **~€20 wired USB-C mic taped into the helmet cheek pad**:

- Mic at 48 kHz, near the mouth, out of the airflow — the Cardo geometry.
- **The Bluetooth link never leaves A2DP.** Music stays full stereo permanently, no profile switch
  ever, no resume drift, no 1–2 s gap.
- Android: `AudioRecord` + `setPreferredDevice(usb)`, stay in `MODE_NORMAL`, never call
  `setCommunicationDevice()`. iOS: `.playAndRecord` with **only** `.allowBluetoothA2DP` +
  `setPreferredInput(usb)` — the configuration a developer got working with Apple's help at a WWDC
  lab ([thread 741513](https://developer.apple.com/forums/thread/741513)).

Cost: a cable from helmet to phone, unplugged at every stop. Less intrusive for the passenger (phone
in a pocket) than for the rider (phone in a handlebar mount).

**Ranked alternatives:** (1) wired helmet mic + AirPods for playback; (2) LE Audio earbuds on the
Pixel — fixes half the link only; (3) buy a Cardo/Sena pair; (4) stay on HFP at 16–24 kHz — honestly
not much worse once wind dominates.

---

## 5. Design decisions

### 5.1 Rejected: store-and-forward "walkie-talkie"

The idea: capture at full quality, send after the speaker stops, play back over A2DP.

**Rejected** once the AirPods mic became a hard requirement. Capture is already constrained to the
call profile (16 kHz Android, ~24 kHz iOS); delaying transmission cannot recover quality that was
never captured. The only way to capture above call quality from AirPods is §4.5, which is barred from
real-time modes and has no Android equivalent. So it buys latency and nothing else.

(The phone's own mic — the only path that *would* give full-quality capture — is dead on a
motorcycle: an unshielded mic port at 100 km/h is mechanical overpressure, not a noise floor you can
denoise, and the speaker is sealed inside a helmet a metre away. Roughly −40 dB SNR.)

### 5.2 Rejected: half-duplex (only the talker opens a mic)

The **mechanism works** — verified on both platforms. A pure listener stays on A2DP with music
intact: stay in `MODE_NORMAL`, never call `setCommunicationDevice()`, open a plain `AudioTrack`.
Android only starts SCO when a stream is patched to a SCO device.

> **Gotcha [unverified, engineering judgement]:** use `USAGE_MEDIA` (+ `CONTENT_TYPE_SPEECH`) for the
> received-voice `AudioTrack`. `USAGE_VOICE_COMMUNICATION` in `MODE_NORMAL` may route to the earpiece
> instead of the Bluetooth sink. Cheap device test, and it gates this whole claim.

**But rejected as a design**, for three reasons:

1. It only helps whoever is silent — the talker still drops to HFP and still loses their music.
2. Both sides thrash the **1–2 s** A2DP↔SCO switch on every conversational turn **[single source:
   Forasoft 2026 — measure it ourselves]**. A four-turn exchange = ~8 s of dropouts. Worse than
   staying in HFP.
3. It removes interruption — the safety-critical case. Passenger saying "car on your left" mid-
   sentence would need: wait for release → press → 1–2 s switch → speak → 250–450 ms latency.
   **3–4 seconds from intent to being heard. Not acceptable.**

And the automatic version (VOX) is what wind breaks. From rider communities: Sena intercom is
"fully active all the time (full duplex) with no VOX" because "it's nearly impossible for the
intercom to distinguish a human voice over the wind and engine noise"; where VOX is used, "if
there's any 'wind' noise, the VOX frequently stays open-mic". **Cardo and Sena are deliberately
full-duplex.** For two people on one bike, half-duplex is the wrong shape.

Latency on its own would have been fine: A2DP adds ~100–200 ms over SCO's ~40 ms, landing near
250–450 ms one-way. Past G.114's 150 ms "transparent" threshold but inside the "acceptable with
awareness" band, and normal for push-to-talk. **Latency isn't what kills it; the switch thrash and
the loss of interruption are.**

### 5.3 Accepted: the session model — which we already have

Quiet by default, both mics open together during a talk, drop back after a long silence. That is
what `PROTOCOL.md` already describes, including broadcasting the trigger so both phones switch
together.

Three cheap improvements fall out:

- **Lengthen the silence timeout.** `TalkController.SILENCE_MS` is **10 s**
  (`link/TalkController.kt:76`). Sena uses a 20-second countdown. At 10 s a natural pause closes the
  channel and the next sentence pays another 1–2 s switch. Start at 20–30 s.
- **Duck instead of pause.** Already implemented and **off by default** (`Settings.duckDuringTalk`,
  `Settings.kt:10`; `MusicController.onTalkOpen(duck)`). Ducking is what Sena does; pausing makes
  every short utterance a jarring silence. Android: `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, ~20 %
  volume, ~150 ms ramp. Mixing happens in the phone's HAL before the A2DP encoder, so there is no
  Bluetooth-specific failure mode here.
- **Pre-roll buffer (new).** Keep a rolling ~2 s of encoded audio on the talker's side and send it
  when the session opens, so words spoken *during* the 1–2 s switch are not lost. The one genuinely
  useful piece of the store-and-forward idea, and it costs almost nothing.

### 5.4 Single-button design — viable, deferred

Press once: intercom opens. While open, recognition runs on the audio already being captured;
"motoparty, next" acts. No second button, no second route switch. Cheap given §3.1 — **but gated on
the §3.1 spike**, and possibly moot given §2.3 (a remote's five buttons make the current two-zone
design fine). Intermediate step costing an afternoon: tap vs. hold on one larger overlay zone.

### 5.5 iOS session hygiene — check, don't assume

If an iOS app holds `.playAndRecord` + `.allowBluetoothHFP` open permanently "so it's ready to
record", **the AirPods sit in HFP for the entire ride** and music is degraded even when nobody has
spoken for an hour ([thread 90503](https://developer.apple.com/forums/thread/90503): "Remove
`AVAudioSessionCategoryOptionAllowBluetooth`. This option forces you into HFP mode").

Reading `Audio/SessionController.swift`, our app looks correct — `.playback` when idle, switching
only for talk/command. But **the iOS app has never run on a device**, so this is unverified rather
than proven.

---

## 6. Action list

Cheap, no hardware, do first:

- [ ] **Speak one real voice command** into the AirPods on the Pixel. Settles §3.3 — whether commands
      work at all today.
- [ ] Spike `EXTRA_AUDIO_SOURCE` + `EXTRA_SEGMENTED_SESSION` on the Pixel 8 (~50 lines, throwaway).
      Settles §3.1 and therefore §5.4.
- [ ] Measure the A2DP↔SCO switch cost on our own Pixel (§5.2 note 2).
- [ ] Device test: `USAGE_VOICE_COMMUNICATION` vs `USAGE_MEDIA` on an `AudioTrack` in `MODE_NORMAL`
      (§5.2 gotcha).
- [ ] Check `AudioManager.getProperty(PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)` on the Pixel 8.
- [ ] Decide on `SILENCE_MS` 10 s → 20–30 s, and on `duckDuringTalk` default (§5.3).

Needs an iPhone:

- [ ] Log `AVAudioSession.sampleRate` with AirPods connected — 16000 or 24000? (§4.4, free win)
- [ ] Confirm the session is `.playback` when idle, not `.playAndRecord` (§5.5).
- [ ] Add `UIRequiresPersistentWiFi` and test the Wi-Fi settings checklist (§1.3).
- [ ] Test an unsigned `.mobileconfig` with `CaptiveBypass` against the internet-less hotspot (§1.3).

Needs a ride:

- [ ] **Spike 4: record the AirPods mic in call mode inside the helmet at speed.** Answers wind
      noise, whether the DTX-based silence close survives, and whether ASR is viable at all. Does not
      need the app — a voice memo on a 20-minute ride would do.

Hardware to consider:

- [ ] ~€25 IPX6 handlebar media button (Moman BTC1 or generic), verified with `getevent -lt` (§2.1).
- [ ] ~€20 wired USB-C helmet mic — the single highest-leverage change in this document (§4.8).

Ask/decide:

- [ ] Is a cable from helmet to phone acceptable? Determines whether §4.8 is on the table.
- [ ] LE Audio earbuds for the rider (§4.2) — only worth it after testing what a Pixel 8 + Pixel Buds
      Pro 2 actually negotiates.

---

## 7. Open questions nobody could verify

1. Whether AirPods negotiate mSBC wideband or fall to CVSD on a Pixel.
2. Whether any AirPods support LC3-SWB or aptX Voice (assumed no).
3. What codec config (16_2 vs 32_2) a Pixel 8 + Pixel Buds Pro 2 negotiates for third-party VoIP.
4. The sample rate `bluetoothHighQualityRecording` actually delivers, and whether playback stays at
   A2DP quality.
5. Whether iOS's 24 kHz AirPods figure is genuine acoustic bandwidth or partly SBR-reconstructed.
6. Exact logcat/dumpsys strings for observing HFP SWB negotiation (probably no public API exists).
7. Whether Google's on-device recognizer honours `EXTRA_AUDIO_SOURCE` on Android 16/17.
8. Whether `USAGE_VOICE_COMMUNICATION` on an `AudioTrack` in `MODE_NORMAL` forces a route change.
9. Same-UID dual `AudioRecord` behaviour (undocumented either way).
10. Cardo's and Sena's actual intercom sample rates.
11. Whether `CaptiveBypass` in a `.mobileconfig` suppresses iOS's internet-viability check, or only
    the captive sheet.
12. iOS↔Android Wi-Fi Aware interop.

---

## 8. Sources

**iOS networking:** [The iOS Wi-Fi Lifecycle](https://developer.apple.com/forums/thread/734361) ·
[Working with a Wi-Fi Accessory](https://developer.apple.com/forums/thread/734344) ·
[Network Interface Techniques](https://developer.apple.com/forums/thread/734359) ·
[TN3179 Local Network Privacy](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy) ·
[TN3111 iOS Wi-Fi API overview](https://developer.apple.com/documentation/technotes/tn3111-ios-wifi-api-overview) ·
[NEHotspotConfiguration](https://developer.apple.com/documentation/networkextension/nehotspotconfigurationmanager) ·
[joinOnce](https://developer.apple.com/documentation/networkextension/nehotspotconfiguration/joinonce) ·
[About Connectivity Assist](https://support.apple.com/en-us/127686) ·
[Use captive Wi-Fi networks](https://support.apple.com/en-us/102554) ·
[Private Wi-Fi addresses](https://support.apple.com/en-us/102509) ·
[Wi-Fi payload / supervision](https://support.apple.com/guide/deployment/wi-fi-settings-dep168e876c9/web) ·
[WiFiAware](https://developer.apple.com/documentation/wifiaware) ·
forum threads [706649](https://developer.apple.com/forums/thread/706649),
[750492](https://developer.apple.com/forums/thread/750492),
[816946](https://developer.apple.com/forums/thread/816946)

**Remotes:** [Moman BTC1](https://momanx.com/products/bluetooth-media-remote-control-moman-btc1) ·
[JaxeADV BarButtons](https://jaxeadv.com/barbuttons/) +
[v5 manual](https://jaxeadv.com/wp-content/uploads/2024/12/BarButtons-v5-manual.pdf) ·
[Satechi Bluetooth Button](https://satechi.com/products/satechi-bluetooth-button-series) ·
[Sena RC4](https://www.sena.com/en-us/product/rc4/) ·
[AB Shutter 3 key codes](https://dev.to/wincentbalin/key-codes-on-ab-shutter-3-2n0h) ·
[Android media buttons](https://developer.android.com/media/legacy/media-buttons) ·
[getevent](https://source.android.com/docs/core/interaction/input/getevent)

**Speech recognition:** [RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent) ·
[AOSP RecognizerIntent.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/RecognizerIntent.java) ·
[Sharing audio input](https://developer.android.com/media/platform/sharing-audio-input) ·
[Concurrent capture](https://source.android.com/docs/core/audio/concurrent) ·
[expo-speech-recognition](https://github.com/jamsch/expo-speech-recognition) ·
[ML Kit GenAI Speech Recognition](https://developers.google.com/ml-kit/genai/speech-recognition/android) ·
[SFSpeechAudioBufferRecognitionRequest](https://developer.apple.com/documentation/speech/sfspeechaudiobufferrecognitionrequest) ·
[Asking permission to use speech recognition](https://developer.apple.com/documentation/Speech/asking-permission-to-use-speech-recognition)

**Bluetooth audio:** [Audio Managed SCO rearchitecture](https://source.android.com/docs/core/audio/sco-audio-mgmt) ·
[LE Audio overview](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview) ·
[BLE audio recording](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-recording) ·
[Audio manager guide](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager) ·
[Manage audio focus](https://developer.android.com/media/optimize/audio-focus) ·
[Oboe Bluetooth TechNote](https://github.com/google/oboe/wiki/TechNote_BluetoothAudio) ·
[bluetoothHighQualityRecording](https://developer.apple.com/documentation/avfaudio/avaudiosession/categoryoptions-swift.struct/bluetoothhighqualityrecording) ·
[allowBluetoothA2DP](https://developer.apple.com/documentation/avfaudio/avaudiosession/categoryoptions-swift.struct/allowbluetootha2dp) ·
[WWDC25 session 251](https://developer.apple.com/videos/play/wwdc2025/251/) ·
Apple forums [90503](https://developer.apple.com/forums/thread/90503),
[741513](https://developer.apple.com/forums/thread/741513),
[797033](https://developer.apple.com/forums/thread/797033) ·
[Macworld: LE Audio and AirPods](https://www.macworld.com/article/797884/bluetooth-le-audio-lc3-codec-airpods.html) ·
[SoundGuys: AirPods Pro 3](https://www.soundguys.com/apple-airpods-pro-3-review-close-to-perfect-144636/) ·
[iDropNews: AAC-ELD in AirPods](https://www.idropnews.com/news/apple-snuck-in-some-big-improvements-to-call-quality-in-airpods-3-and-airpods-pro/178979/) ·
[Vivox: Bluetooth profile comparison](https://docs.vivox.com/v5/general/core/5_19_0/en-us/Core/developer-guide/android/bluetooth-profile-comparison.htm) ·
[Stephen Coyle: AirPods Pro 2 latency](https://stephencoyle.net/airpods-pro-2) ·
[Forasoft: Android audio output switching](https://www.forasoft.com/blog/article/implement-audio-output-switching-on-android-575) ·
[Android Police: super-wideband](https://www.androidpolice.com/super-wideband-bluetooth-speech/)

**Noise, intelligibility, intercoms:** [ISVR: motorcycle helmet noise](https://isvr.co.uk/projects/motorcycle-noise/) ·
[Alpine: helmet noise levels](https://www.alpinehearingprotection.com/blogs/motor-race/do-motorbike-helmets-protect-against-excessive-noise) ·
[ITU-T G.114](https://www.itu.int/rec/dologin_pub.asp?lang=e&id=T-REC-G.114-200305-I%21%21PDF-E) ·
[Quality dimensions of NB/WB/SWB channels](https://www.researchgate.net/publication/279529681_Comparison_of_Transmission_Quality_Dimensions_of_Narrowband_Wideband_and_Super-Wideband_Speech_Channels) ·
[Artificial bandwidth extension evaluation](https://www.researchgate.net/publication/282605008_Subjective_Voice_Quality_Evaluation_of_Artificial_Bandwidth_Extension_Comparing_Different_Audio_Bandwidths_and_Speech_Codecs) ·
[Cardo DMC](https://cardosystems.com/blogs/cardo-blog/dynamic-mesh-communication) ·
[Sena Audio Multitasking](https://community.sena.com/hc/en-us/articles/360021331212-What-is-Audio-Multitasking-) ·
[Sena community: VOX modes](https://community.sena.com/hc/en-us/community/posts/216696766-Please-explain-VOX-modes) ·
[Creatorbeat: motovlogging microphones](https://www.creatorbeat.com/audio/microphones/best-microphone-for-motovlogging/)
