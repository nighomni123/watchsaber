# Platform decisions — Lightsaber (Wear OS motion-controlled sound instrument)

Status: **Milestones 1–2 delivered and verified** (calibration, ignition, continuous modulation and
retraction running on a physical watch). Screen-off operation is Milestone 4 and remains blocked on
Android 17 background-audio rules — see §7.

Every row is either (a) quoted from a platform doc captured under `docs/research/`, (b) read directly
off the test watch over adb on **2026-10-01**, or (c) read off this machine's SDK/Gradle/Maven state
on the same date. Nothing here is recalled from memory.

## 1. Build matrix (pinned, and why each one is pinned)

| Component | Pin | Source of truth |
|---|---|---|
| Gradle | **9.8.0** | wrapper; AGP 9.3.3 refuses to load below 9.5.0 |
| Android Gradle Plugin | **9.3.3** | required by `androidx.core:core-ktx:1.19.1` and `compose-ui:1.12.x`, which demand AGP ≥ 9.1 |
| Kotlin | **2.2.10** | build JVM is JBR 21 (below) |
| Compose for Wear OS | **1.7.0** | latest stable; 1.8.x is still alpha |
| Compose BOM | **2026.09.00** | aligns `compose-ui` |
| Oboe | **1.11.0** | Google Maven `<release>`; its API uses `AudioStreamErrorCallback` |
| `compileSdk` | **37** | platform `android-37.0` is installed |
| `targetSdk` | **36** | the test watch runs API 36; keeps Android 17 behaviour changes out of scope — see §7 |
| `minSdk` | **34** (Wear OS 4) | baseline until a wider watch matrix exists |
| NDK | **26.1.10909125** | only NDK installed; AGP 9 *prefers* 28.2 but must be pinned to what exists |
| CMake | **3.22.1** | as installed |
| ABI | **armeabi-v7a only** | the watch is a 32-bit userland — see §2 |
| Build JVM | **JBR 21.0.11** | Gradle 9.1 predates JDK 25; the Android Studio JBR is 25.0.2 and is *not* used for the build |
| `sdk.dir` | `/Users/Shared/AndroidSDK` | platforms `android-34`, `android-36`, `android-37.0` |

Two of these pins were forced by real failures, not preference:
- **`-DANDROID_STL=c++_shared` must be passed from Gradle**, not set inside `CMakeLists.txt`. The NDK
  toolchain resolves `ANDROID_STL` before the project body runs, so a `set()` there is too late and
  the link fails with `CXX1212: static STL but library requires a shared STL`.
- **The debug keystore lives in the workspace** (`keystore/debug.keystore`); AGP otherwise tries
  `~/.android`, which is outside this project's write sandbox.

## 2. The test watch — measured over adb

`Samsung SM-R870`, `ro.build.characteristics=watch`, **Android 16 (API 36)**, security patch
`2026-08-05`, display **450×450**.

**It is a 32-bit userland**: `ro.product.cpu.abilist = armeabi-v7a,armeabi`, `ro.zygote = zygote32`.
An arm64-only APK fails to install with `INSTALL_FAILED_NO_MATCHING_ABIS`. This is not a detail: it is
why `ControlFrame` carries `alignas(8)` — on a 32-bit ABI `int64_t` only aligns to 4, and a
`static_assert(alignof(ControlFrame) == 8)` written for arm64 fails the build on the real target.

### Sensors (`dumpsys sensorservice`)

| Sensor | Vendor | Max rate | Notes |
|---|---|---|---|
| `TYPE_GYROSCOPE` — LSM6DSO | STM | **100.00 Hz** | non-wake-up, no batching |
| `TYPE_ACCELEROMETER` — LSM6DSV | STM | **100.00 Hz** | FIFO (300, 300) |
| `TYPE_LINEAR_ACCELERATION` | Samsung Inc. | 100.00 Hz | **Samsung's own**, not the AOSP one |
| `TYPE_ROTATION_VECTOR` | Samsung Electronics | 100.00 Hz | Samsung's own |
| `TYPE_GAME_ROTATION_VECTOR` | Samsung Electronics | 100.00 Hz | |
| `TYPE_GRAVITY` | **AOSP v3** | 100.00 Hz | derived, software |
| `TYPE_LINEAR_ACCELERATION` | **AOSP v3** | 100.00 Hz | a *second* sensor of the same type |
| `TYPE_GYROSCOPE_UNCALIBRATED` | STM | 100.00 Hz | |

**The headline result: 200 Hz is unreachable on this watch.** The brief asks for 100 Hz and to
"evaluate 200 Hz". The gyroscope's own `maxRate` is 100 Hz, so 200 Hz would be refused by the
*sensor*, not by a permission. Android's separate 200 Hz `registerListener()` ceiling
(`docs/research/sensors-overview.md:440`) is therefore not the binding constraint here — the hardware
is. The app requests 10,000 µs and measures what it actually receives.

Two consequences that would have been easy to get wrong:
- **There are two `TYPE_LINEAR_ACCELERATION` sensors** (Samsung's and AOSP's). Which one
  `getDefaultSensor()` returns decides the accent latency, and the brief's instruction to benchmark
  derived sensors rather than assume their latency is exactly the trap this sets.
- **`TYPE_GRAVITY` exists**, so gravity compensation has a real sensor to use — and the pipeline
  uses it, falling back to a low-passed accelerometer only when it is absent.

### Audio outputs (`dumpsys audio`)

`STREAM_MUSIC` advertises `speaker(2)`, `headset(4)`, `headphone(8)`, `bt_a2dp(80)`,
`bt_a2dp_hp(100)`, `ble_headset(20000000)`, `ble_broadcast(20000002)`, `usb_headset`,
`remote_submix`. Active device: **speaker**. That matches the Wear guidance's device list
(`docs/research/wear-audio.md:18-24`) and justifies preferring the built-in speaker for latency work.

## 3. Sensor access rules (from the platform docs)

Source: `docs/research/sensors-overview.md`, `docs/research/sensors-motion.md`.

- `registerListener()` is capped at 200 Hz; exceeding it requires `HIGH_SAMPLING_RATE_SENSORS` and
  raises `SecurityException` without it (sensors-overview.md:440-449).
- **The app declares no sensor permission at all.** At 100 Hz it is below that ceiling, and a
  gyroscope + accelerometer instrument needs no microphone, location, health or body-sensor access.
  The only declared permission is `VIBRATE`, for confirmation haptics that are never fired during
  capture (vibration contaminates the motion signal).
- Gyroscope units are **rad/s**, positive **counter-clockwise**; `SensorEvent.timestamp` is a
  monotonic nanosecond stamp. All elapsed-time maths derives from it, never from callback arrival.
- Gyroscope and accelerometer on this watch are **non-wake-up**: screen-off delivery is not
  guaranteed, which is an independent reason Milestone 4 must be tested, not assumed.

## 4. Audio engine

Source: `docs/research/audio-latency.md`, `docs/research/wear-audio.md`.

- **Oboe is the platform's own recommendation** for the lowest achievable latency, and it uses AAudio
  from API 27 up. The engine requests `PerformanceMode::LowLatency` and `SharingMode::Exclusive`, and
  **records what it actually got** rather than what it asked for — those are different claims.
- Exclusive sharing falls back to shared when it fails, because a shared stream at higher latency
  beats no sound at all.
- Callback rules from the checklist are honoured directly: no allocation, no locks, no I/O, no JNI,
  and non-blocking communication with the rest of the app. That is what the SPSC queue is for.
- This app is **sonification, not media**. Wear's advice that media apps suppress built-in-speaker
  playback does not apply; the docs explicitly sanction the speaker for "a non-media use case that
  uses sound".

## 5. Lifecycle and focus

Source: `docs/research/fgs-service-types.md`, `docs/research/audio-focus.md`,
`docs/research/wearos7-changes.md`.

- **Wear OS 7 is Android 17, API 37**, whose headline change here is background audio hardening:
  background audio requires a foreground service that is not `SHORT_SERVICE`, and targeting API 37
  requires while-in-use capabilities. This is the whole reason Milestone 4 is deferred.
- For that milestone: `mediaPlayback` type, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
  `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`, **runtime prerequisites: none**. No health-type service is
  declared or used to bypass anything.
- Audio focus is app-managed; `AUDIOFOCUS_REQUEST_FAILED` means **do not play**. The same
  `AudioFocusRequest` instance must be used to request and to abandon. Permanent loss stops the
  session outright; transient loss pauses; duck lowers volume.
- On interruption the engine prioritises **prompt silence over completing the retraction sound**.

### Still unverified

- The brief states apps targeting Android 15+ must be topmost or in a foreground service to request
  focus. The captured `audio-focus` page does **not** contain that sentence, and I could not re-fetch
  to confirm: the monid wallet fell to **$0.000699** against a **$0.0009/call** price and returned
  `status: BLOCKED`. Treat it as unverified. It is harmless for a visible-activity session and must
  be re-checked before Milestone 4.
- The specific while-in-use capability requirements behind the Android 17 quote are not enumerated
  in the captured page.

## 6. What was verified, and how

**Offline, on the host, against the shipped `mixer.cpp`:**

```
rendered 12.744 s at 48000 Hz
  control frames consumed : 1200 (dropped 0)
  accent triggers        : 4
  peak |sample|          : 0.5150
  clipped samples        : 0
```

`tools/analyze_render.py` on that render:

```
movement lift   : rms x1.25, brightness x2.25
corr(swing,rms) : +0.49
corr(swing,zcr) : +0.72
```

`tools/trace_vs_audio.py` shows the three states are distinct and correctly ordered:

| State | swing | twist | brightness (zero-crossing rate) |
|---|---|---|---|
| idle | 0.000 | 0.000 | ~580–640 Hz (hum only) |
| twisting | 0.000 | 0.726 | ~690–710 Hz |
| swinging | 1.000 | 0.000 | ~1650–1810 Hz |

That ordering is the product requirement: movement is continuously louder and brighter, the hum never
stops underneath it, and twisting is audible even at rest but subtler than swinging.

**15 unit tests** in `:motion` cover the brief's signal-processing list — pure twist, transverse
rotation, stationary noise, preset transforms, irregular timestamps, data gaps, clamping, gravity
compensation, calibration acceptance/rejection, and bias-vs-noise separation.

**On the physical watch:** APK builds, installs and launches on the SM-R870; the capability probe
reports the real sensor ceilings; calibration captures real gyroscope samples and succeeds; ignition
loads assets, opens the stream, and the user confirms audible hum and response to movement.

## 7. Sound architecture — rewritten after device feedback

`docs/sound-synthesis-sources.md` reviews four builds of this instrument. Its central claim is that
they differ on one question: is the swing a **modulation of a bed that never stops**, or **a file
laid over a static bed**? Source C (Silabs) is the counter-example it warns against.

**The first implementation here was, structurally, Source C.** `swing_low` and `swing_high` were
1-second rendered loops, crossfaded power-complementarily by swing intensity. That is a real design —
it is also why the sound was poor:

- A 2.5-second sweep **repeats the 1-second loop twice and a half**. Source B's argument applies
  verbatim: *"swing longer than the sound file and it just sounds cheap."* The swing had a hard
  one-second ceiling on how long it could be.
- Playback rate rode **0.88–1.28** with swing. That is a pitch ride, which the source review calls
  "the single most cartoonish failure mode available".
- There was **no modulated delay**. Motion was therefore only audible as loudness and filter
  cutoff, which is why twisting in place was nearly inaudible.

The sound is now generated rather than sampled, except for the bed and the two transitions:

| Layer | Now | Source |
|---|---|---|
| Bed | hum loop → twist-driven LPF → **3-line modulated delay bank** whose length tracks swing speed | D §5.2 |
| Swing | **generated**: one noise source through two band-pass SVFs, power-complementary crossfade | B §3.3 + the crossfade idea, without the samples |
| Accents | **generated**: noise → flanger, fast attack, exponential decay | D §5.3 |
| Transitions | ignite / retract one-shots — samples are correct here | A |

Three details carried over deliberately:

- **Multiple delay lines, not one.** A single modulated line comb-filters audibly and reads as a
  metallic flange; the three lines use incommensurate offsets so the notches never align.
- **The delay is ramped, never stepped.** Stepping a delay line clicks.
- **The Doppler comes from delay length, not rate.** This is what removes the pitch ride entirely:
  swing now sweeps a filter (340 Hz → ~3.2 kHz) and opens a comb, but never changes pitch.

Measured on the offline render after the change:

```
corr(swing, rms) : +0.678   (was +0.49)
corr(swing, zcr) : +0.974   (was +0.72)
movement lift    : rms x1.33, brightness x20.34   (was x1.25 / x2.25)
accent triggers  : 1        clipped samples: 0   peak 0.808
```

Ordering is preserved and separated: idle 540–620 Hz < twist ~710 Hz < swing ~15,400 Hz.

`tools/synth_assets.py` still renders `swing_low`, `swing_high` and `accent`, but the mixer accepts
and ignores those three slots. They are kept only so the asset provenance stays unbroken; they can
be deleted once nothing references them.

## 8. Defects found by the first device run

Telemetry from the SM-R870 exposed three things that no offline test could have found.

**1. The gyroscope full scale was wrong by 2x.** The sensor reports
`maximumRange = 17.453642 rad/s` (±1000 dps); the config hardcoded `34.9` (±2000 dps), a value taken
from a different part. Consequences:

- Saturation detection was **dead code** — the threshold could never be reached, so
  `saturationFlags` was structurally always 0 and the brief's "flag readings near the sensor's
  maximum range" silently did nothing.
- `fullIntensityRadPerSec = 9.0` was only 51% of full scale, so a hard swing pinned the sensor while
  still reading as normalised 1.0.

Fixed by reading `Sensor.getMaximumRange()` at session start. Full intensity is now 55% of full
scale, bounded by the configured value.

**2. Accents fired 138 times in one stationary session.** The first cause was thresholds too low
(gate 0.20, 110 ms refractory), but raising them only hid the real problem. The watch exposes both a
Samsung and an AOSP `TYPE_LINEAR_ACCELERATION`, and a gravity estimate that disagrees with the
accelerometer leaves a **constant offset**, which reads as perpetual light motion. Fixed at the root:
the accent basis is now the **deviation from a slow running mean** (τ = 2 s) of the
gravity-compensated acceleration, so bias is tracked away and only genuine transients survive. Gate
and refractory were also tightened (0.22 / 450 ms).

**3. `motion 25.1 Hz` was a reporting artifact.** Counters were never reset between sessions, so the
rate was averaged across the whole process lifetime — mostly idle time with sensors unregistered.
6626 samples ÷ 264 s ≈ 25.1 Hz, which matched exactly. Counters now reset in `beginSession`, so the
Diagnostics screen reports the live session.

Two cosmetic items remain open: the `LIGHTSABER` title is a few dp too close to the TimeText, and
the player record shows `CONTENT_TYPE_UNKNOWN` even though the builder sets `Sonification` and the
focus request correctly reports it.

## 9. Known gaps — stated, not hidden

- **The calibration profile is not persisted.** The brief requires persisting the mounting
  transform, noise estimates, handedness and sensitivity. Today it lives in the ViewModel, so it is
  lost on process death and on reinstall. Recalibrating after every restart is the visible symptom.
- **No on-device latency measurement yet.** The brief's <50 ms target needs an external microphone and
  a shared timebase; none has been run. The engine reports stream sample rate, peak and dropped-frame
  counters, which is a prerequisite, not the measurement.
- **10-minute underrun soak not yet run**, and 100 vs 200 Hz cannot be compared on this hardware at
  all — the gyro tops out at 100 Hz.
- **Screen-off / foreground service (Milestone 4) not implemented.**
- **Two adb transports for one watch** (the classic Samsung duplicate), and the wireless port rotated
  twice within minutes (`35507` → refused, `46393` → refused). Always pin a serial and expect to
  re-resolve.