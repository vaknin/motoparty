# recognizer-pfd — throwaway spike

**This whole directory is throwaway.** It is a separate Gradle project with its own
applicationId (`com.kivan.motoparty.spike`), no dependencies, and no connection to `android/`.
Delete it once the three questions below are answered and the answers are written into
`../../research/RESEARCH.md`.

It exists to settle three device questions from `../../research/RESEARCH.md` in one install, with no
microphone involved and nothing to tap on the phone.

## The questions

| test | question | from |
|---|---|---|
| `pfd` | Does Google's recognizer actually read audio from a `ParcelFileDescriptor` passed as `RecognizerIntent.EXTRA_AUDIO_SOURCE`, or does it silently ignore the extra and open the mic? | §3.1, §3.2 |
| `usage` | In `MODE_NORMAL`, does an `AudioTrack` with `USAGE_VOICE_COMMUNICATION` route to the earpiece instead of the connected Bluetooth sink, while `USAGE_MEDIA` stays on the sink? | §5.2 gotcha |
| `props` | `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED`, output sample rate / frames-per-buffer, SDK level, recognizer availability. | §6 |

`pfd` is the one that matters: it decides whether the "one button, recognise from audio we are
already capturing" design in §3.1 is buildable at all. The failure mode it hunts is *silent* —
the javadoc explicitly allows a recognizer to ignore the extra and open the mic instead, and
when it does, recognition simply never matches and nothing says why.

## What it does, and what you will hear

- `pfd` — **nothing audible.** Test audio comes from `TextToSpeech.synthesizeToFile`, which
  writes a WAV and never plays it: two phrases, "motoparty next" and, after 1.5 s of silence,
  "play album abbey road". The WAV is parsed, resampled to 16 kHz mono PCM16, padded with 0.5 s
  of silence at both ends, and fed into a pipe from a dedicated thread at real-time pace
  (20 ms / 640-byte chunks on a monotonic clock). The session is ended by closing the write end
  (EOF), never by `stopListening()`. No microphone is opened by this app at any point.
- `usage` — **two quiet 1 s 440 Hz tones** (amplitude 0.2) in whatever is connected, about a
  second apart. First `USAGE_MEDIA`, then `USAGE_VOICE_COMMUNICATION`, both with
  `CONTENT_TYPE_SPEECH`. Connect the headphones you care about before running it.
- `props` — nothing audible, nothing recorded.

The audio mode is read and **never** written; `setCommunicationDevice()` is never called;
nothing changes Wi-Fi, Bluetooth or volume.

## Running it

```sh
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
PHONE=192.168.1.100:5555 ./run.sh pfd
```

`run.sh` installs, optionally grants `RECORD_AUDIO` (`GRANT=1`), clears logcat, starts the
activity with the test extra, waits, dumps `logcat -s Spike:V` into
`results/<timestamp>-<test>/spike.txt`, and prints the verdict lines. It injects no taps and
no key events. `UNINSTALL=1` removes the app afterwards.

Every test is also a button in the app, and every test is startable by intent:

```sh
adb shell am start -n com.kivan.motoparty.spike/.MainActivity --es test pfd
adb shell am start -n com.kivan.motoparty.spike/.MainActivity --es test pfd --es recognizer default
adb shell am start -n com.kivan.motoparty.spike/.MainActivity --es test usage
adb shell am start -n com.kivan.motoparty.spike/.MainActivity --es test props
adb shell am start -n com.kivan.motoparty.spike/.MainActivity --es test all
```

`--es recognizer default` swaps `createOnDeviceSpeechRecognizer()` for `createSpeechRecognizer()`,
so a negative on-device result can be checked against the default (possibly cloud) recognizer.

### RECORD_AUDIO

`SpeechRecognizer`'s javadoc says the calling application must hold `RECORD_AUDIO`. Whether that
is enforced when the audio comes from a pipe instead of the mic is exactly the kind of thing this
spike is for, so the permission is **declared in the manifest but never requested at runtime**,
and `run.sh` grants it only under `GRANT=1`. Run it both ways:

- ungranted run succeeds → the real app can recognise without holding the mic permission;
- ungranted run gives `ERROR_INSUFFICIENT_PERMISSIONS` and the granted run works → the
  permission is required, but (see the mic line in the verdict) that still does not mean a mic
  was opened.

## Reading the verdicts

Each test prints one summary line to logcat under tag `Spike`.

```
Spike: PFD PIPE: audio=5700 ms, wrote=182400/182400 bytes, maxWriteBlock=3 ms, eofAt=5712 ms, drained=true
Spike: PFD CALLBACKS: onReadyForSpeech=true, onBeginningOfSpeech=true, segments=2, onEndOfSegmentedSession=true, micOpened=false
Spike: PFD VERDICT: motoparty next | play album abbey road
```

- **`PFD VERDICT: <text>`** — it works. The recognizer read our pipe. §3.1 is confirmed and the
  design is buildable.
- **`PFD VERDICT: mic opened instead`** — `RecordingWatch` saw an active recording appear during
  the session. This app records nothing, so that recording is the recognizer's. The extra was
  ignored: §3.1 is dead on this device and §3.4's fallbacks apply.
- **`PFD VERDICT: error N ERROR_...`** — read the callback log above it.
  `ERROR_INSUFFICIENT_PERMISSIONS` → retry with `GRANT=1`. `ERROR_CLIENT` on every run would
  suggest the read end is being closed too early (it is not, deliberately — but that is the
  classic cause).
- **`PFD VERDICT: no result; pipe was never drained`** — decisive on its own. The pipe buffer is
  64 KiB ≈ 2 s of this audio; if writes start blocking, nobody is reading the descriptor, which
  means the recognizer ignored it. Two numbers say so in the `PFD PIPE` line: `maxWriteBlock`
  (a write that blocked and then returned) and `wrote=X/Y` short of the total (a write still
  blocked when the test gave up).
- **`segments=N` / `onEndOfSegmentedSession=true`** — `EXTRA_SEGMENTED_SESSION` works, which is
  what makes recognition *continuous* for the length of a talk rather than one-shot. A run with
  text but `segments=0` means the audio source worked and segmentation did not.

```
Spike: USAGE VERDICT: media -> BLUETOOTH_A2DP "…", voice_communication -> BUILTIN_EARPIECE
```
Two different device types here confirms the §5.2 gotcha (use `USAGE_MEDIA` for received voice).
The same type for both means the gotcha was unfounded on this device.

```
Spike: PROPS VERDICT: unprocessed=true, sdk=37, onDeviceRecognizer=true
```

## Caveats

- `pfd` is gated on API 33 (`EXTRA_AUDIO_SOURCE`, `EXTRA_SEGMENTED_SESSION`,
  `EXTRA_BIASING_STRINGS` are all API 33); on anything older the verdict says "skipped".
  `minSdk` here is 29 only to match the real app.
- A `mic opened instead` verdict is trustworthy in the positive direction (something recorded,
  and it was not us). `micOpened=false` is slightly weaker: a recording shorter than the 200 ms
  poll that also produced no callback would be missed. Both mechanisms are used to narrow that.
- The `usage` result depends on what is connected. Run it with the actual helmet headset, not
  the phone speaker, or it answers nothing.
