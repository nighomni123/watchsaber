# Lightsaber sound synthesis — source review and integration map

**Status: documentation only.** Nothing in this document has been implemented. It records what four
external builds actually do, what they agree on, where they disagree, and which of it is already
covered by this app's engine.

Written 2026-10-01.

## How to read this

- **Quoted / read-from-code** means the statement is taken directly from a fetched source. Source
  files are cached under `docs/research/` (see §9).
- **Inferred** means the source does not say it, but it follows from what it does say. Every
  inference is marked `[inferred]` and names its basis.
- Where a source could not be retrieved, that is stated as a gap rather than filled in.

A note on scale: all four sources describe an **embedded target with no floating point, a 10- or
12-bit DAC, and a sample rate chosen to fit a CPU budget**. This app runs on a watch SoC with Oboe,
floats, and 48 kHz. Reading those designs as a *recipe* would be a mistake. They are valuable for
three other reasons — the *sound-design argument* they make about why samples fail, the *timing
discipline* they use to drive a continuous signal from a sparse motion sensor, and, in one case, a
working demonstration of the architecture this app exists to avoid.

---

## 1. The four sources at a glance

| # | Source | Platform | Hum | Motion response | One-shots | Core technique |
|---|---|---|---|---|---|---|
| A | [mnigmann/lightsaber](https://github.com/mnigmann/lightsaber) (+ Hackaday, author blog) | ATtiny85 + Arduino Nano | 25 ms loop in program flash, amplitude-modulated | Gyro magnitude → LPF → amplitude | WAV files read from SPI flash at runtime | Dual-MCU split; fixed-rate timer ISR as the audio callback |
| B | [Arduino forum thread](https://forum.arduino.cc/t/lightsaber-sound-synthesis/424537) | Teensy 3.2 / Maple Mini | Synthesised (Teensy), samples (Maple) | IMU + Madgwick AHRS, Doppler intent | — | Audio System Design Tool; the sampled-vs-generated argument |
| C | [Silabs, "Build your own lightsaber (sounds)!"](https://community.silabs.com/s/share/a5U1M000000knw7UAA/build-your-own-lightsaber-sounds-part-1) (Parts 1–5, full text) | EFM32 Wonder Gecko | Recorded `idle1.wav`, **never modulated** | ADXL345 activity interrupt, 500 ms debounce | `swing0.wav` added on top via `add_track()` | **The counter-example**: sample-triggered layering, argued explicitly. See §4.1 |
| D | [Bela / Pure Data](https://blog.bela.io/building-your-own-light-saber/) | Bela + Pd patch | Looped TV-hum sample | Accelerometer integrated → velocity | Noise + flanger on piezo contact mic | Multiple variable-length delay lines = Doppler |

---

## 2. Source A — mnigmann/lightsaber

The most fully documented of the four, and the only one where the actual source code was
retrievable (`lightsaber_tiny.c`, 701 lines). Primary sources: the repo README, the C file itself,
the [Hackaday project](https://hackaday.io/project/195924-lightsaber-prop-with-sound-effects)
(five project logs, 2024), and the author's [blog write-up](https://mnigmann.blogspot.com/2024/05/lightsaber-prop-first-prototype.html)
which walks the interrupt handler step by step.

### 2.1 The problem as stated

The author's opening line is the whole design brief in one sentence:

> "I have never been really impressed with most sound options for sabers for the fact that they
> seem to play a sound file and if you swing longer than the sound file it just sounds cheap."
> — forum thread, post #1 (rebel2234; the same complaint is echoed by the thread's other builders)

This is the load-bearing observation for the entire document. A one-shot file can only represent
one swing. Sabers are swung continuously and for as long as the user likes, so **the sound has to be
a continuously running process that motion modulates** — not an event that motion triggers. Every
other source agrees, including the one that shipped samples (see §3.1).

### 2.2 Architecture — two processors, because of timing

Requirements (Hackaday, verbatim in intent): buzz modulated by motion, one-shots from memory, and
cheap enough for a kids' maker club.

The author first considered a single Arduino Nano doing everything and rejected it:

> "the Nano must be able to continuously write to the DAC 44100 times per second while also
> controlling the LED strip and reading from the memory chip and accelerometer. All of these steps
> would have to be implemented very carefully to allow them all to fit in the allowed time."

The split:

```
  Arduino Nano                          ATtiny85
  ─────────────                         ────────
  accelerometer (I²C)                   timer ISR @ 33 333 Hz  ← the audio callback
  LED strip control                       ├─ read buzz sample from PROGMEM (lpm)
  ↑ runs Arduino C                       ├─ multiply buzz × amp
  │                                      ├─ multiply playback sample × amp
  │  commands over SPI (D10/D12/D13)    ├─ mix, write 16-bit word to MCP4911 DAC
  └───────────────────────────────────►  ├─ read next WAV sample from SST25VF080B
                                         └─ slew amp toward its target
```

Two decisions in that box are worth stealing outright, independent of hardware:

1. **The accelerometer moved to I²C so the Nano's hardware SPI could talk to the ATtiny.**
   The log entry that drove it:

   > "its SPI interface was not working, but its I2C interface was. This led to an obvious design
   > simplification that I missed earlier … This would let me do away with the assembly code that I
   > currently need to communicate with the ATtiny, since the hardware already takes care of the
   > synchronization and timing issues."

   *Transferable principle: on a real-time audio path, never share a bus whose timing you don't
   control with a sensor you do not control.*

2. **The sample rate was deliberately lowered from 44100 to 33333 Hz — "which gives me 32% more
   time per sample."** A conscious CPU-budget trade, not an accident.

### 2.3 The hum — a 25 ms loop in program memory

This is the core trick and it is very simple:

> "I simplified the buzzing sound so that it consists of a 25-millisecond clip that is played on
> repeat continuously. This clip is stored in the program memory of the ATtiny. At the chosen sample
> rate, the clip consists of 833 samples." — Hackaday

`33333 Hz × 0.025 s = 833`. In the code it is `const uint8_t unit[833] PROGMEM`, a single
periodic waveform; the ISR increments an index, wraps it at `unitlen`, and reads the table with
`lpm` (load-from-program-memory, since program flash is not in the data address space).

Two details read straight off the code:

- **DC removal is baked in.** The table is unsigned 8-bit (values 128–220, centred on 128). The ISR
  subtracts a constant, `#define BUZZ_CENTER 174`, before the multiply — so the 833-sample loop
  contributes no net DC offset to the speaker.
- **The loop period is runtime-tunable.** `#define` comment table and `case 0b0011: unitlen =
  control_data & 0x0fff;` — the host can change how long one cycle of the table takes, i.e. retune
  the hum's pitch, without reflashing. `[inferred]` the audible pitch of the hum is
  `sample_rate / unitlen`; with the default 833 that is 33333/833 ≈ 40 Hz, so the hum's fundamental
  sits very low and the loop's own spectral content supplies the upper harmonics. The author notes
  "the specific design of the buzzing sound will likely be modified later."

### 2.4 One-shots — WAV parsed on the chip at runtime

Sound effects live as ordinary WAV files on an external SPI flash (SST25VF080B, 8 Mbit ≈ 1 MB,
which the author estimates at "around 30 seconds" of audio). There is no index, no manifest.

When told to play, the ATtiny reads the file header itself (`prepare_audio()`): 36 bytes, pulling
`sample_rate` at offset 24, RIFF length at 4, `fmt ` length at 16, and then walking the chunk list
looking for `data` (`memcmp(data, "data", 4)`) to find the sample start and length. Playback is
then a flat read-and-advance loop against `addr` and `end_addr`.

A nice micro-optimisation in `prepare_audio()`:

```c
bytes_per_sample = (data[34]>>3)+(data[35]<<5);   // bits_per_sample / 8, as a 16-bit LE field
```

— dividing a 16-bit field by 8 without a 16-bit shift pair.

**Consequence worth noting:** because the chip parses arbitrary WAV headers, effects are authored in
any DAW and dropped in as files. This is the cheapest possible asset pipeline, and it is *not* what
this app does (see §7.2).

### 2.5 The per-sample algorithm — the genuinely interesting part

The blog describes the whole interrupt handler in three steps:

> "1. The next sample in the buzz waveform is read from program memory and the index … is
> incremented. The index is reset to zero when it reaches 833 … 2. Writing to the DAC, reading a
> command, and multiplying the samples by their respective amplitudes are performed more or less
> **simultaneously**. Since the multiplications finish after the DAC write is done, the output from
> the previous cycle is written. After the multiplications are complete, the mixed value is stored
> for the next cycle. 3. The next playback sample is read from the flash chip and stored for the
> next cycle."

Three ideas, all transferable:

**(a) A software pipeline across the sample period.** A write-only DAC transfer and a read-only
command transfer are independent, so they are issued together, and the multiply of sample *n* happens
while sample *n−1* is still going out the wire. The register save/restore at the top and bottom of
the ISR (`push r22…r31` … `out 0x3f, r22` … `pop r31…r23`) exists to make that interleaving safe.
*This is the same discipline as a real audio engine: do not do all the work for a block at the end
of the callback; overlap it.*

**(b) Only one interrupt is ever enabled.**

> "For reliability reasons, this is the only interrupt used. This guarantees that the interrupt runs
> at precise intervals and is not held up by any other interrupts."

*Transferable principle, and it is a safety property, not just a tidiness one: nothing else in the
system may be allowed to delay the audio deadline.*

**(c) Multiply without a multiplier.** The ATtiny has no hardware multiply, so it is a shift-add
loop over the 8 bits of the amplitude:

```asm
sbrc r22, 0      ; if bit 0 of amplitude is set ...
add r29, r23     ;     ... add the sample to the high byte
ror r29           ; shift the result right, carry rotates into the MSB
ror r28
...              ; (repeated for bits 1..7)
```

The author found and fixed a real bug in this, and the write-up is worth reading for the failure
mode alone — the symptom was that playback stayed audible at volume 0 and got *quieter* as volume
went 0→7, repeating every 8 units:

> "the carry bit was set by the addition, then the shift would load it into the MSB of the result
> register. However, the unexpected side effect of this was that if the addition was not executed,
> then the carry bit would retain its value from the previous operation."

The fix was to shift the *sample* right before multiplying and skip the final shift. **The lesson
generalises far past assembly:** a stale state bit in a rounding pipeline produces a bug that is
periodic in the control value, not monotonic — and a control value that gets more *quiet* as you
turn it *up* is the signature.

**(d) Amplitude is slewed, not stepped.** Stepping the amplitude produces clicks. From the log:

> "I noticed a slight staticky noise in the buzz when I moved the accelerometer. I suspect this is
> caused by the signal suddenly changing from one level to another when the amplitude is changed.
> I will likely program the ATtiny to slowly ramp the amplitude from its current value to its new
> value to minimize these effects."

The shipped code does exactly that: every sample, `amp` moves one LSB toward `new_amp`. At
33333 Hz that is a full 0→255 sweep in ≈7.6 ms. `[inferred]` — the comment in the code and the log
entry corroborate each other, but the ramp rate itself is not stated as a number anywhere.

### 2.6 The control protocol

One byte of command, one byte of data, over SPI. From the header comment in the C file:

| Bits | Command |
|---|---|
| `0000 0000 dddd dddd` | Set buzz volume |
| `0001 0000 dddd dddd` | Set playback volume |
| `0010 0000 dddd dddd` | Set audio timer divider (sample rate) |
| `0011 dddd dddd dddd` | Set buzz period |
| `0100 dddd dddd dddd` | WAV segment address, low 12 bits |
| `0101 dddd dddd dddd` | WAV segment address, high 12 bits **and begin playback** |
| `0110 0000 0000 0000` | Stop current WAV segment |
| `0111 0000 0000 0000` | Enter low-power mode |

The address split exists because 24 bits do not fit in one payload byte — playback takes two
commands, low half then high half. `[inferred]` the low-power command works by parking the DAC at
`0x2000` and the timer at `0x3000`-enabled, then entering `sleep` with only a pin-change interrupt
armed; the next command from the host is what wakes it.

The bit-serial handshake is hand-rolled: the host waits for the DAC chip-select to toggle, uses its
falling edge as a frame start, holds a longer first clock pulse to resynchronise to an unsynchronised
second clock domain, and then paces the rest of the transfer with `nop` counts. `[inferred]` this is
a UART-like framing smuggled over an SPI-shaped bus, chosen because the ATtiny's USI cannot generate
its own clock.

### 2.7 Motion → amplitude

The mapping, as documented in the 2024-05-19 log entry:

- ~100 Hz gyro read loop on the Nano.
- Angular velocity about x and y, magnitude computed.
- One-pole low-pass smoothing — "the smoothing step makes sure that the amplitude does not
  fluctuate rapidly due to noise in the measurement".
- Constant **offset of 50** added, because "the constant offset of 50 ensures that even when the
  lightsaber is held still, some sound can still be heard".
- All integer arithmetic; "the divisions are implemented using bitshifts".

Two observations:

- **One axis only.** Rotation about the blade's long axis is invisible to a magnitude, so a
  magnitude-driven model cannot distinguish a twist-in-place from a swing. Sources B and D both
  handle this by adding a second signal; this one does not.
- **The hum never goes silent.** A 50/255 floor means the idle state still has voice. This is a
  product decision about what "still" sounds like, made without comment, and it is worth
  deliberately re-making rather than inheriting by accident.

### 2.8 Output stage

MCP4911 10-bit DAC into a discrete **full-bridge amplifier** (two op-amps, four transistors):
one bridge half gets the signal, the other gets an inverted copy, so the speaker sees twice the
voltage swing.

> "To maximize the loudness of the sound that the speaker could produce, I used a full-bridge
> driver, which drives both terminals of the speaker, but with inverted signals. Thus, the voltage
> across the speaker is twice the amplitude of the input signal, and the produced sound is twice as
> loud."

Second PCB revision added a capacitor to fix LED switching noise coupling into the amplifier — a
real reminder that on a watch the haptic/speaker power domains are the same class of problem.

---

## 3. Source B — the Arduino forum thread

Seven posts, December 2016, between four builders. Low technical density, high decision density.
It is included because it is the only source that states the design argument out loud, from both
sides.

### 3.1 The sampled-vs-generated split

- `racemaniac` (Maple Mini): "in my case it's wav files getting mixed together" — and defends it:
  "it gives you the freedom to take a sample from a saber from a movie/game, and use it for your
  saber".
- `rebel2234` (Teensy 3.2): "Yes, generating sound."
- `racemaniac` on why he hadn't gone further: "For now my saber mostly reacts to rotation, and very
  little to movement **since absolute movement data is hard to come by**".

That last clause is the crux and it recurs in Source D. An IMU gives you angular *velocity*
directly but only gives you translation velocity by **integrating**, which drifts. The choices are
therefore: integrate and accept drift, avoid translation entirely, or restrict yourself to
rotation-derived quantities. This app has already chosen a third path (§7.2).

### 3.2 The generation pipeline that was used

From post #5, the one substantive reply:

> "Alot of what I came up with was inspiration from this video. And then using PJRC's Audio System
> Design Tool to closely match the base sounds as possible then trying to mix them based on IMU
> movements and get a doppler effect. Using PJRC's IMU library NXPMotionSense and MadgwickAHRS from
> Github in various places."

Two things to extract:

- **Graph-based audio programming** (the Teensy Audio System Design Tool) rather than hand-written
  DSP. Every block in a patch is a running process, which is the same mental model as a continuously
  running mixer with per-block parameters.
- **Madgwick AHRS** to get a usable orientation/angular-velocity estimate from a 6-DOF IMU. This is
  the standard answer to the "absolute movement data is hard to come by" problem in the embedded
  world.

### 3.3 The proposal nobody built

Post #6, which is the most interesting post in the thread precisely because it is a design idea
rather than a build log:

> "I wonder if there is the potential to create a **hybrid approach that generates dynamic swing
> sounds on top of a pre-recorded hum**. Or maybe **add Doppler shift dynamically to pre-recorded
> sounds**."

Both halves of that are live questions for this app: a generated layer over a recorded bed (§7.3),
and whether Doppler is worth simulating at all given the pitch artefact it causes (§7.4).

---

## 4. Source C — Silicon Labs, "Build your own lightsaber (sounds)!"

**Retrieved: all five parts, verbatim.** Full transcription with provenance, gaps and constants is
in `docs/research/silabs_lightsaber_sounds.md`; this section is the digest and the mapping.

*Build your own lightsaber (sounds)!* — Parts 1–5, by **Brad Fricker** (`lynchtron`, rendered on the
migrated site as `D720741534`), *The Maker's Guide*, Silicon Labs, November 2016. EFM32 Wonder
Gecko + ADXL345 + MicroSD + TPA2005D1 Class D amp.

The URL given in the brief returns `empty_content`: it is a Salesforce Experience Cloud single-page
app that serves only Aura bootstrap to a plain fetch. **The workaround is `&_escaped_fragment_=`,
which makes Salesforce server-side prerender the record as ordinary HTML.** That retrieved Parts
1, 2, 3 and 5 directly. Part 4 needed its own document ID, recovered from a Wayback CDX crawl
(`a5U1M000000knjvUAA`), and then only responds with `_escaped_fragment_` on the canonical slug — the
plain URL redirects to the community home page. The linked `github.com/SiliconLabs/makersguide` is
dead; the live mirror is `github.com/bfricker/makersguide`, directory `14_lightsaber_sounds/`.

There is no Part 6. A domain-wide Wayback crawl returns exactly nine lightsaber URLs, and the live
series stops at five.

### 4.1 This is the counter-example, and that is why it matters

Every other source in this document arrives at the same architecture by a different route: a short
looped buffer, running continuously, whose parameters motion modulates. Source C is the only one
that ships the alternative — **it plays recorded files and adds them together** — and it argues for
that alternative explicitly and well. Part 5 states the design rule:

> "In order to create a lightsaber sound effects generator, we need to always play a primary sound
> that handles the idle 'humming' sound and then other sounds such as swings and clashes that play on
> top of the first sound. **Since we don't know when these things are going to happen ahead of time,
> we can't simply create static sound effects that cover all possible combination of sound events.
> We have to dynamically blend sounds together at run time.**"

That is the strongest published statement of why the layered continuous model is forced — and notice
it gets there while still calling the layers "sound files". The author is solving the right problem
with the wrong primitive.

**The consequence for the sound:** the hum in Source C is a fixed loop at fixed level, and a swing
is a file that is added on top of it for as long as the file lasts. Nothing about the hum changes.
So a slow swing and a fast swing produce the same hum, and a swing that outlasts the file returns
to an unchanged hum — which is exactly the failure `rebel2234` names in Source B, and exactly what
this app's own `AGENTS.md` rules out in a single line: *"no sample-triggered swing playback."*

**Read against `corr(swing, rms)` (`tools/trace_vs_audio.py`), this source predicts itself.** A
fixed hum plus an on/off swing layer produces a correlation with swing intensity that is much
flatter than a continuously modulated one, with a characteristic step. The app's expected ordering
— **idle < twist < swing** — is the fingerprint of a design that modulates; Source C's is the
fingerprint of one that triggers. That is a genuinely useful way to *hear* the difference between
the two architectures, and it is measurable today with the tooling already in the repo.

### 4.2 Architecture (Parts 3–5)

```
  MicroSD (FatFS)                EFM32 Wonder Gecko                     speaker
  ───────────────                ──────────────────                     ───────
  idle1.wav  ── primary track ─┐
  swing0.wav ── add_track()  ──┤   TIMER0 ─PRS→ DAC ─DMA→ ping-pong     TPA2005D1
                                │   (heartbeat,   request   2 × 512      Class D,
  (up to MAX_TRACKS = 3         │    rate from      sample    int16       differential
   extra tracks)  ─────────────┘    the WAV header)
```

- **The timer is the heartbeat.** `timerTopValue = CMU_ClockFreqGet(cmuClock_TIMER0) /
  wavHeader.frequency` — the sample clock is derived from the file's own sample rate, so the asset
  sets the cadence. Via PRS, `TIMER0` overflow → `dacPRSSELCh0` → the DAC requests the next sample →
  DMA supplies it.
- **Two ping-pong buffers, 512 samples each**, so the DMA engine can drain one while the other is
  filled from SD. "Note that two 'ping pong' buffers are used so that the DMA engine can be working
  on one buffer while the other buffer is loaded from the Micro-SD card."
- **12-bit DAC from 16-bit WAV:** stereo pairs are summed and `tmp >>= 4`.
- **Blending is `add_track()`** — up to three extra files mixed into the primary, each with its own
  file pointer and byte count, summed in `FillBufferFromSDcard`.

### 4.3 Three things worth stealing

**(a) The no-averaging argument for summing layers.**

> "Surprisingly, the only thing that needs to be done to blend sounds is to add two samples from
> different files together … **There is no averaging that needs to be done or anything like that**,
> and it sounds very naturally blended. Most of the audio data is contained in the lower quarter of
> the possible digital range, with peaks that will occasionally jump above the three-quarter mark.
> The adding of sounds together works because the peaks in the audio files don't usually happen at
> the same exact moment in time. When they do, you will have some clipping, but it should work for
> simple sound effects used in this example."

This is a falsifiable claim about headroom: a sum of N decorrelated layers clips only when their
peaks *coincide*, which is rare. This app sums **six** layers and already counts the outcome —
`clippedSamples` in `MixCounters`. So the claim is testable here rather than merely arguable: if
`clippedSamples` is ~0 across real replays, the limiter in the mixer's output stage is defending
against a condition that does not occur. That is a **deletion candidate** (§7.3), not a change to
make now.

**(b) Ping-pong buffering and `ControlQueue` are the same idea.**

Source C decouples "new audio data must exist before the next sample is due" with a hardware
double-buffer; this app decouples "new control data must exist before the next block is due" with a
lock-free SPSC queue. Same invariant, different transport, and both trace back to the same rule the
Oboe checklist gives: *"Consider using non-blocking algorithms to communicate between input and
output callback handlers."* Worth stating explicitly because it is the clearest cross-source
corroboration in this document.

**(c) Motion-triggered sounds need a debounce, and the numbers are not comparable.**

Part 5 polls the ADXL345's activity interrupt and gates it behind a 500 ms timeout
(`adxl_debounce_timer = set_timeout_ms(500)`), with the threshold register set by trial and error
(`ADXL345_REG_THRESH_ACT, 0x90`) at 16 G / 50 Hz. This app's equivalent is
`accentMinIntervalSeconds = 0.11f` with a calibrated threshold on 100 Hz gyro data. **A 4.5×
difference that is not a disagreement** — 0x90 on a 50 Hz, 16 g-range accelerometer is a coarse
hardware threshold with a noisy trigger, not a tuned retrigger guard. The transferable fact is only
that every one of these builds has a retrigger guard; the specific value is meaningless outside its
own signal chain.

### 4.4 What does not transfer

- **The GPIO-as-DAC and PWM-as-DAC theory (Parts 1–2)** is good, clear, and about an 8 MHz M0 with no
  DAC. The transferable part is only the *principle*: *"you don't always need a DAC to create audio.
  The key to getting the tone we wanted, in this case, is external analog filtering."* Its rule of
  thumb — sample rate 10× the tone frequency, PWM switching 10× the sample rate, worked as
  4 kHz → 40 kHz → 400 kHz — is a Nyquist-with-headroom heuristic for a square-wave output stage.
  At 48 kHz with a float mixer it is not a constraint.
- **Letting the sensor wake the MCU.** Part 5 is explicit that the interrupt *pins* are the
  production answer because they "allow your MCU to go into a low power state until that interesting
  event happens". **There is no equivalent on Android.** Sensors are polled at a rate you request,
  the watch's gyro is hardware-capped at 100 Hz, and the app polls continuously. The idle-battery
  cost of that is real and currently unmeasured — it belongs in the Milestone 4 / soak work, not in
  a synthesis change.
- **The sample clock following the file header.** Every source that does this (§2.4, §4.2) can mix
  assets of differing rates. This app's assets are all 48 kHz and resampling never happens. That is
  a deliberate simplification, and it is recorded in every `.provenance.txt`.

### 4.5 Two internal inconsistencies, transcribed as published

Flagged because they are a good model for how to handle a source that contradicts itself: correct
the number in the analysis, leave the transcript alone.

1. Part 3: "it can drive to any of **212** values in-between those two voltages, or 4096 steps" —
   "212" is a mangled superscript 2^12.
2. Part 3's gain resistors (150 kΩ for gain two, 75 kΩ for gain four, 50 kΩ for gain six) do not
   reconcile without the gain equation, which is an image and was not retrievable. Not guessed.

---

## 5. Source D — Bela with Pure Data

December 2016, the most audio-design-forward of the four and the only one built around a
**contact microphone**, which is worth dwelling on because it is the one sensor the other sources
lack and the one that no watch has.

### 5.1 Architecture

A Pure Data patch running on a Bela board, shipped as a stock Bela example
(`examples/10-Instruments/saber`). Bela runs the Pd patch's DSP in a hard real-time audio
callback — the patch's blocks execute once per audio block, on the audio clock, with direct access
to the accelerometer. There is no separate control thread; every parameter is a signal patched
straight into the block that uses it.

The author frames this as the point of the platform:

> "created to show how easy Bela is to use for rapid prototyping and for bringing sound models to
> life."

### 5.2 The swoosh — variable delay lines, not pitch

This is the most directly transferable DSP idea in any of the four sources:

> "The basis of the swooshing sound is a sample of tv hum that is looped inside the patch and passed
> into **multiple delay lines that have a variable amount of delay**. When we move the saber, which
> is detected by the accelerometer, we change the length of the delay. This creates an effect that
> is very similar to the **doppler effect**, a perceptual effect that happens with moving sound
> sources."

Note what this is and is not:

- It starts from a **looped sample of mains hum** — the same "loop a short buffer forever" model as
  Source A's 833-sample table. Two independent builds, same primitive.
- The Doppler comes from **changing delay length**, not playback rate. A modulated delay on a
  sustained loop produces a shifting comb/complex resonance that reads as motion. It does *not*
  require the pitch to ride up and down, which is what makes it usable — a rate change is audible as
  a pitch change, and a pitch that slides with every swing sounds like a cartoon.
- "**multiple** delay lines" — several, not one, which decorrelates the comb filtering so it reads
  as air movement rather than as a metallic flange.

### 5.3 The impact — noise plus flanger, on a contact mic

> "For the impact and unsheathing sound we used a **noise source with a flanger effect** applied
> that was trigger by the piezo input."

The piezo disk is soldered straight to a 3.5 mm jack and plugged into the audio input:

> "Piezo disks generate a voltage when they are deformed … In this case we are using one to detect
> strikes on the cardboard tube and using that (audio) signal to trigger an impact sound."

Two design points:

- **Noise, not a recorded hit.** A noise burst through a flanger is cheap, is never the same twice,
  and cannot run out. A recorded impact sample is instantly identifiable as a sample.
- **The trigger is analogue contact, not a motion threshold.** The impact sound is a direct
  electrical measurement of the tube being struck. No source in this document derives impacts from
  an accelerometer; `rebel2234` in Source B is the only one who gets close, and only via gyro
  magnitude. A watch has no contact mic, so this specific mapping has no portable equivalent —
  but the *sound* it produces (noise → flanger, triggered) is exactly what Source B's post #6 asks
  to be able to do, and it is a different sound from any recorded sample.

### 5.4 Motion → hum

> "In the Pure Data patch we are **integrating these signals** to calculate the overall velocity of
> the tube and using this to drive the electrical hum part of the sound model."

Bela integrates the accelerometer to get velocity and drives the hum with it. This is exactly the
move Source B's `racemaniac` called "hard to come by". It is defensible on Bela — a short patch
process, fresh samples, bounded drift — and it is the design decision most likely to be wrong on a
watch, where the audio engine must run for ten minutes while the sensor stack may stall (§7.4).

---

## 6. Cross-source synthesis — what actually makes a saber sound

Collapsed across all four sources, the sound model is the same four-layer instrument every time. What
differs is not the layering but the question in the second column.

| Layer | Continuous? | Source A | Source B | Source C | Source D | This app |
|---|---|---|---|---|---|---|
| **Bed** — hum, always sounding, never ducked | yes | 833-sample loop, amplitude-modulated | synthesised base | recorded `idle1.wav`, **fixed level, never modulated** | looped TV hum, delay-modulated | `hum.wav` loop, gain + twist-driven LPF |
| **Motion texture** — swing | yes | — (same buzz, amplitude only) | generated, Doppler intent | **recorded file added on top — a layer, not a modulation** | delay-modulated loop | `swing_low` / `swing_high` crossfaded by swing |
| **Accents** — impacts / sharp changes | no | WAV from flash | — | clashes as extra tracks | noise+flanger on contact | `accent.wav` one-shot, threshold + retrigger guard |
| **Transitions** — on / off | no | (WAV from flash) | — | (not addressed) | unsheath = same as impact | `ignite` / `retract` one-shots |

The four-layer structure is universal — even Source C, which calls them "sound files" rather than
processes, ends up with an always-on primary plus layers over it. What separates the designs is
whether the swing is a **modulation of the bed** (A, B, D) or a **file laid over a static bed** (C).
That single distinction is the whole argument of this document, and it is the one line in this
app's `AGENTS.md` that says *"no sample-triggered swing playback."*

Three modulation axes appear in every source that modulates, and the third is the one most builds
skip:

1. **Amplitude** — how loud (A, B, D; **not C**, which never changes the hum's level at all).
2. **Rate / pitch** — how fast (Source B explicitly, this app explicitly, as a bounded 0.88–1.28
   span).
3. **Timbre** — how bright (Source D, via delay-line length; this app, via the twist filter).

Source A is the outlier among the modulating sources: it only ever modulates amplitude. That is
the single clearest technical weakness in the most complete source, and it is why a twist in the
hand is inaudible on that prop. Source C is the outlier in the other direction — it does not
modulate at all, which is why its swing cannot outlast its own sample file.

---

## 7. Integration map for this app

### 7.1 What is already here

Verified by reading the code on 2026-10-01, not recalled:

- `engine/src/main/cpp/mixer.h` — the six layers above, `LoopPlayer` (phase preserved across rate
  changes), `OneShotPlayer` (with retrigger guard), `OnePoleLowPass` for both the swing bus and the
  hum, SPSC `ControlQueue`, and per-block control smoothing (`controlTauSeconds = 0.008`).
- `engine/src/main/cpp/control_frame.h` — the frozen 32-byte contract, `static_assert`ed, carrying
  `swingIntensity`, `twistSigned`, `accelAccent`, `directionX/Y`, `sessionGeneration`.
- `app/src/main/cpp/audio_engine.*` — Oboe, mono, LowLatency + Exclusive *requested* and the granted
  values recorded, one data callback, `std::mutex` used only on the session/lifecycle side.
- `motion/…/MotionProcessor.kt` — Android-free, unit-tested, replayable from a recorded trace.
- `tools/synth_assets.py` — every asset procedurally synthesised, CC0, with a `.provenance.txt`
  beside each file; loops built from integer-cycle frequencies and equal-power crossfaded.
- `engine/host/offline_render.cpp` + `tools/analyze_render.py` + `tools/trace_vs_audio.py` — the
  offline path compiles the **same `mixer.cpp` that ships on the watch**, so a defect found in
  replay is a defect in the shipped engine rather than a test-double artefact.

**The measurement that tests this document's central claim.** Source B's argument (§3.1) is that a
sample-triggered design decorrelates from the motion while a continuously modulated one tracks it.
That is falsifiable, and the repo already has the instrument for it: `trace_vs_audio.py` reports
`corr(swing, rms)` and `corr(swing, zcr)` between a recorded motion trace and a rendered WAV. A
sample-triggered engine would show low correlation — the audio would keep doing the same thing while
the hand does something else. A continuously modulated engine tracks closely, with the expected
ordering **idle < twist < swing** and accents rare. Any change proposed in §7.3 should be judged
against those two numbers on a replay, before anyone listens to a watch.

### 7.2 Where this app deliberately diverges

Four divergences, each of which at least one source argues against, and each of which is a decision
to re-confirm rather than an oversight:

1. **No integration to velocity.** `MotionProcessor` states it outright: *"Nothing is ever integrated
   to a position or a velocity — an IMU drift would make that claim false within seconds."*
   Source D integrates; Source B's `racemaniac` names integration as the hard part. This app uses
   gyro rate, gravity, and freshness only. The cost: no true Doppler, and no direct measure of
   translation speed. The gain: nothing that slowly lies over a ten-minute session.

2. **Direction is not sold as Doppler.** `control_frame.h`: `directionX/Y` is "used for a subtle
   stereo/level tilt. **Never presented as Doppler.**" This is the rejection of Source B's Doppler
   intent and Source D's pitch-riding, on the grounds that a pitch that tracks hand speed is the
   single most cartoonish failure mode available. Sensible; note it as a decision, because it is
   one.

3. **Assets are rendered, not loaded.** No flash, no runtime WAV parsing, no 10-bit DAC, no
   second MCU. Everything is a 48 kHz float buffer generated by a committed script, with licence
   provenance attached. The trade is explicit: the Source A asset pipeline (drop any WAV in) and the
   Source C pipeline (FatFS over MicroSD, sample rate read from the file header) are both far more
   convenient for authoring, and are deliberately given up for unambiguous provenance and a single
   fixed sample rate.

4. **The swing is a crossfade, not an added layer.** Source C's `add_track()` sums a swing file over
   a fixed hum. This app crossfades `swing_low`/`swing_high` **power-complementarily** and drives the
   result from `swingIntensity`, so the swing bus tracks the hand continuously and the hum beneath it
   is untouched. Per §4.1, this is the difference the two architectures make to
   `corr(swing, rms)`, and it is the specific thing `AGENTS.md` forbids.

### 7.3 Candidates worth a decision — none implemented

| Candidate | Source | Why it might earn its place | Cost |
|---|---|---|---|
| **Modulated delay line on the hum** for motion | D §5.2 | The only technique in any source that makes movement audible *without* moving pitch. A short delay (a few ms) modulated by transverse velocity gives the comb-shift that reads as air moving past the blade. Directly serves the brief's "a fast twist in place must not sound identical, and must be audible as its own thing." | One delay line + a smoothing ramp on the delay time. Must be ramped, not stepped, or it will comb-filter audibly. |
| **Generated swing over a recorded/procedural hum** | B §3.3 | The post nobody built. We already have the structure for it — the hum and the swing bus are separate layers — so this is a parameter change, not an architecture change. | Likely zero. Verify by listening to the offline render. |
| **Noise → flanger for accents instead of the recorded `accent.wav`** | D §5.3 | Removes the last sample-derived layer. An accent built from filtered noise is never identical twice, so a fast repeated accent stops sounding like a loop. | A noise source + a modulated delay/filter per accent. Trades a buffer for DSP. |
| **Widen the timbre axis on the swing bus** | A §6 (its gap) | Source A has no timbre axis at all; this app already has one on the hum and on the swing bus. Confirm the swing-bus filter is *audible at the low end* of the range, which is the case the source that lacks this axis gets wrong. | Nothing — a measurement via the offline render. |
| **Re-examine whether the output limiter is load-bearing** | C §4.3(a) | The Silabs author asserts that summing N layers needs no averaging and no protection, *because peak coincidences are rare*. This app sums six layers and already counts the outcome in `clippedSamples`. If that counter is ~0 across real replays, the limiter is defending against a condition that does not occur — and deleting it is a net simplification. **Measure first; do not remove on the strength of a blog post.** | A count in `analyze_render.py` output, then possibly less DSP in the hot path. |

### 7.4 What is deliberately not being adopted

- **The dual-MCU split, external DAC, external SPI flash, USI bit-banging.** A watch SoC is the
  opposite of the constraint that produced all of it. Every one of those exists to survive 16 MHz
  with no multiplier and no FPU.
- **The 33 333 Hz sample rate.** The source chose it for 32% more CPU per sample. This app targets
  48 kHz for Oboe low-latency native rates. Keep the *principle* — know your per-sample budget and
  spend it deliberately — and re-check it against the measured callback load rather than the source's
  number.
- **Doppler as a pitch ride.** Rejected per §7.2. If it is ever revisited, the delay-line form
  (§7.3) is the version to revisit, not the rate-change form.
- **Sample-file hum.** The opening argument of Source B applies verbatim to this app: a recorded
  hum that is louder while you swing and silent when you stop is the "sounds cheap" failure.

### 7.5 Open questions — `TODO(spec)`

1. Is the hum's idle presence a fixed floor (Source A's `+50`), a gain on the twist axis, or
   something tied to session state? This is a product decision with no source consensus — Source A
   picks a constant, this app currently uses `humGain = 0.42`, Bela uses a velocity-driven hum. All
   three are defensible and they sound different.
2. Should accents be able to retrigger over each other? Currently a retrigger guard exists; the
   brief does not say.
3. `directionX/Y` currently drives "a subtle stereo/level tilt" on a **mono** stream
   (`kChannelCount = 1`). Confirm what that actually is, or drop it.
4. The `swingRateMin/Max` span of 0.88–1.28 is a judgement call flagged in the code itself as
   guarding against "a cartoonish pitch ride". Worth listening to the offline render at both ends
   before it is frozen as a constant.
5. **On-device verification is partial.** Milestones 1–2 — capability detection, mount calibration,
   ignition, idle hum, continuous swing/twist modulation, accents, retraction — are verified audible
   on a physical watch (Samsung SM-R870, Android 16). Still open per
   `docs/platform-decisions.md` §7: no on-device latency measurement, no 10-minute underrun soak,
   no profile persistence, no screen-off/foreground service. Any §7.3 candidate is therefore
   decidable on the offline render but **not yet decidable on latency or endurance**.
6. **The 100 Hz control rate is a hardware ceiling, not a choice.** The test watch's gyro (LSM6DSO,
   non-wake-up) tops out at 100.00 Hz, which the platform caps at 200 Hz. Source A's host loop also
   runs at ~100 Hz, so the smoothing strategy in §2.7 is directly comparable — but the brief's
   "evaluate 200 Hz" cannot be run on this hardware at all.

---

## 8. Summary

The four sources agree on the *shape* of the instrument and split on one question: is the swing a
**modulation of a continuously running bed**, or **a file laid over a static one**? Three say
modulate; Source C says add, and argues for it well. The argument for modulating is the strongest
thing in this corpus, stated by three builders independently and never once directly rebutted: a
saber is swung for as long as the user likes, so a sound that only represents one swing is a sound
that runs out. Source C's rebuttal is structural rather than acoustic — it claims a file layered
over a hum *is* dynamic enough — and that claim is the one thing here that is testable rather than
arguable. This app has already taken the other side, in one line of `AGENTS.md`, and §4.1 shows
what that side actually sounds like — and how `corr(swing, rms)` tells the two apart.

The bed is a short loop; the motion response modulates amplitude, rate and timbre; the accents are
transient and preferably not recordings. Every build has a retrigger guard. Nobody agrees on the
number, and the numbers are not comparable across their very different signal chains.

Source A is the best-documented and the most instructive about real-time discipline — one
interrupt, a software pipeline across the sample period, click-free parameter ramps, and a
first-class account of how a rounding bug in a shift-add multiply presents itself as "turning the
volume up makes it quieter". Source D is the best on sound design — variable delay lines for motion,
noise through a flanger for impacts, and a hum that a listener can always hear. Source B is the best
on argument: it states the case against samples, and it leaves behind one good unbuilt idea. Source
C is the control case, and its most useful contribution is a falsifiable claim about headroom that
this app can test with a counter it already maintains.

This app has already absorbed the durable parts — looped procedural assets, a layered mixer,
crossfaded rather than switched, a frozen control contract, smoothed parameters, a retrigger guard,
and a hard refusal to integrate a drifting IMU into a claim about velocity. What it has *not* yet
adopted, and what the sources most clearly point at, is a **modulated delay** as a way to make motion
audible without touching pitch.

### The shortest version

Everything above reduces to one sentence, which is the one all four sources independently arrive at
and which this app has already committed to:

> The sound is a process, not a file. Motion changes the **parameters** of a sound that is always
> running; it does not choose which sound to play.

---

## 9. Provenance and retrieval log

All retrieval via `monid` (`tinyfish /fetch`) on 2026-10-01, per the workspace rule. Raw captures are
in `docs/research/`.

| Source | Retrieved | Cache file |
|---|---|---|
| GitHub repo page | yes | `gh_repo__lightsaber.txt` |
| `lightsaber_tiny.c` (raw, 701 lines) | yes | `gh_raw__lightsaber_tiny.c.txt` |
| `README.md` (raw) | yes | `gh_raw__README.md.txt` |
| Hackaday project 195924 | yes | `extra__195924-lightsaber-prop-with-sound-effects.txt` |
| Author blog, "first prototype" | yes | `extra__lightsaber-prop-first-prototype.html.txt` |
| Arduino forum thread (via Discourse `.json`) | yes | `forum_posts.txt` |
| Bela blog | yes | `bela__building-your-own-light-saber.txt` |
| Silabs community, Parts 1–5 | yes (Parts 1, 2, 3, 5 live; Part 4 via `_escaped_fragment_` on a CDX-recovered doc ID) | `silabs_lightsaber_sounds.md` (full verbatim transcription) |
| Silabs companion source constants | yes — `github.com/bfricker/makersguide`, `14_lightsaber_sounds/` | as above |

The Hackaday page and the author's blog are not in the original four, but they are the primary
sources behind the GitHub repo and contain the design rationale, the measured numbers, and the
walkthrough of the interrupt handler. They are cited as part of Source A.

The forum thread's Discourse JSON needed `\uXXXX` decoding and markdown-unescaping before it was
readable; `forum_posts.txt` is the cleaned render.

The Silabs series was retrieved separately, against a plain fetch that returns `empty_content`. The
route that worked — `&_escaped_fragment_=`, plus document IDs recovered from a Wayback CDX crawl —
is recorded at the top of `silabs_lightsaber_sounds.md` so it can be repeated. The two internal
inconsistencies in that source are transcribed uncorrected there and annotated in §4.5.

---

## 10. Retrieval gotcha worth keeping

Two of the four URLs do not serve their text to a plain fetch, and both fail *quietly* — a
`200 OK` with an empty body, which reads as "the page is empty" rather than "the page is
JavaScript". Worth remembering for any future research pass:

- **Salesforce Experience Cloud** (community.silabs.com) — append `&_escaped_fragment_=` to make
  Salesforce server-side prerender the record. Some canonical slug URLs still need this or they
  redirect to the site home page, so the document ID must be recovered separately.
- **Discourse** (forum.arduino.cc) — append `.json` to the topic path for the structured post
  stream. Beware that the markdown-escaper the fetcher applies turns `\u003c` into `u003c` and
  `\_` into `_`, so the payload needs unescaping before `json.loads` will accept it.

Neither of these is an anti-bot block; both are content-negotiation behaviour.
