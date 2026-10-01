#!/usr/bin/env python3
"""Generate the engine's sound assets procedurally.

Why synthesised rather than sampled: the brief requires original, commissioned or appropriately
licensed audio, and explicitly forbids bundling extracted film or game audio. Every file here is
produced from scratch by this script, so the provenance is unambiguous and the licence is clean.

Each loop is built from frequencies that complete a whole number of cycles over the loop length, so
the wrap-around point is phase-continuous; a short equal-power crossfade is applied on top to remove
the residual discontinuity from the noise components.

Usage:  python3 tools/synth_assets.py <output-dir>
"""
import array
import math
import os
import struct
import sys
import wave

SAMPLE_RATE = 48000
PROVENANCE = "Procedurally synthesised by tools/synth_assets.py in this repository. CC0 / public domain."


def write_wav(path, samples, provenance=PROVENANCE):
    """Write mono 16-bit PCM."""
    peak = max((abs(s) for s in samples), default=0.0)
    if peak > 0:
        scale = 0.92 / peak
        samples = [s * scale for s in samples]
    data = array.array("h", (max(-32767, min(32767, int(s * 32767))) for s in samples))
    # WAV stores 16-bit samples little-endian. Only swap on a big-endian host — swapping
    # unconditionally silently writes every sample byte-reversed.
    if sys.byteorder == "big":
        data.byteswap()
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(data.tobytes())
    meta = path + ".provenance.txt"
    with open(meta, "w") as f:
        f.write(provenance + "\n")
        f.write(f"{SAMPLE_RATE} Hz mono, {len(samples) / SAMPLE_RATE:.3f} s\n")
    print(f"  {os.path.basename(path):<16} {len(samples) / SAMPLE_RATE:6.3f}s")


def crossfade_loop(samples, fade_samples):
    """Equal-power crossfade the tail into the head so the loop point is inaudible."""
    n = len(samples)
    fade = min(fade_samples, n // 4)
    if fade <= 0:
        return samples
    out = list(samples[: n - fade])
    for i in range(fade):
        t = i / fade
        a = math.cos(t * math.pi / 2)
        b = math.sin(t * math.pi / 2)
        out[i] = out[i] * b + samples[n - fade + i] * a
    return out


def lowpass(samples, cutoff, sample_rate=SAMPLE_RATE):
    rc = 1.0 / (2 * math.pi * cutoff)
    dt = 1.0 / sample_rate
    alpha = dt / (rc + dt)
    z = 0.0
    out = []
    for s in samples:
        z += alpha * (s - z)
        out.append(z)
    return out


def make_hum(duration=2.0, base=55.0):
    """A steady, slightly alive drone with an air band above the harmonic stack.

    The air band matters: twist is expressed by opening and closing a filter over this loop, and a
    hum with nothing above ~1.8 kHz cannot be made brighter by any cutoff, so twisting would be
    silently inaudible. 55 Hz over 2 s is exactly 110 cycles, so the loop stays phase-continuous.
    """
    n = int(duration * SAMPLE_RATE)
    body = []
    for i in range(n):
        t = i / SAMPLE_RATE
        v = (
            1.00 * math.sin(2 * math.pi * base * t)
            + 0.50 * math.sin(2 * math.pi * base * 2 * t)
            + 0.28 * math.sin(2 * math.pi * base * 3 * t)
            + 0.12 * math.sin(2 * math.pi * base * 4 * t)
            + 0.05 * math.sin(2 * math.pi * base * 6 * t)
        )
        # 0.5 Hz breathing = exactly one cycle over the loop, so it stays loopable.
        v *= 0.88 + 0.12 * math.sin(2 * math.pi * 0.5 * t)
        body.append(v * 0.25)

    # Deterministic noise, band-limited to the 2.5-9 kHz "hiss" of a powered blade.
    seed = 4242
    air = []
    for i in range(n):
        seed = (1103515245 * seed + 12345) & 0x7FFFFFFF
        air.append((seed / 0x3FFFFFFF) - 1.0)
    air = lowpass(air, 9000.0)
    # One-pole high-pass at roughly 2.5 kHz, applied as out = in - lowpass.
    rc = 1.0 / (2 * math.pi * 2500.0)
    dt = 1.0 / SAMPLE_RATE
    alpha = dt / (rc + dt)
    z = 0.0
    band = []
    for s in air:
        z += alpha * (s - z)
        band.append(s - z)

    mixed = []
    for i in range(n):
        t = i / SAMPLE_RATE
        breathe = 0.80 + 0.20 * math.sin(2 * math.pi * 0.5 * t)
        mixed.append(body[i] + 0.08 * band[i] * breathe)

    return crossfade_loop(lowpass(mixed, 11000.0), 480)


def make_swing_loop(duration, fundamental, noise_level, tone_level, brightness):
    n = int(duration * SAMPLE_RATE)
    # Deterministic LCG so the asset is byte-identical on every machine.
    seed = 12345
    noise = []
    for _ in range(n):
        seed = (1103515245 * seed + 12345) & 0x7FFFFFFF
        noise.append((seed / 0x3FFFFFFF) - 1.0)
    noise = lowpass(noise, brightness)

    out = []
    for i in range(n):
        t = i / SAMPLE_RATE
        v = tone_level * math.sin(2 * math.pi * fundamental * t)
        v += tone_level * 0.35 * math.sin(2 * math.pi * fundamental * 2 * t)
        v += noise_level * noise[i]
        out.append(v)
    return crossfade_loop(out, 240)


def make_accent(duration=0.10):
    n = int(duration * SAMPLE_RATE)
    out = []
    phase = 0.0
    for i in range(n):
        t = i / SAMPLE_RATE
        u = t / duration
        freq = 1400.0 * math.pow(300.0 / 1400.0, u)
        phase += 2 * math.pi * freq / SAMPLE_RATE
        env = math.pow(1.0 - u, 2.5)
        out.append(math.sin(phase) * env + 0.25 * math.sin(phase * 2.01) * env)
    return out


def make_ignite(duration=0.55):
    """Rising sweep with a noise swell: the hum takes over underneath it."""
    n = int(duration * SAMPLE_RATE)
    seed = 999
    out = []
    phase = 0.0
    for i in range(n):
        t = i / SAMPLE_RATE
        u = t / duration
        freq = 120.0 * math.pow(900.0 / 120.0, u)
        phase += 2 * math.pi * freq / SAMPLE_RATE
        seed = (1103515245 * seed + 12345) & 0x7FFFFFFF
        noise = (seed / 0x3FFFFFFF) - 1.0
        env = math.sin(math.pi * u) ** 0.7
        out.append(
            (math.sin(phase) + 0.4 * math.sin(phase * 2.0) + 0.35 * noise) * env * 0.5
        )
    return lowpass(out, 6000.0)


def make_retract(duration=0.45):
    n = int(duration * SAMPLE_RATE)
    out = []
    phase = 0.0
    for i in range(n):
        t = i / SAMPLE_RATE
        u = t / duration
        freq = 900.0 * math.pow(110.0 / 900.0, u)
        phase += 2 * math.pi * freq / SAMPLE_RATE
        env = math.pow(1.0 - u, 1.6)
        out.append((math.sin(phase) + 0.3 * math.sin(phase * 1.5)) * env * 0.5)
    return lowpass(out, 4000.0)


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "engine/assets"
    os.makedirs(out_dir, exist_ok=True)
    print(f"writing assets to {out_dir}")
    write_wav(os.path.join(out_dir, "hum.wav"), make_hum())
    write_wav(os.path.join(out_dir, "swing_low.wav"), make_swing_loop(1.0, 180.0, 0.30, 0.55, 2200.0))
    write_wav(os.path.join(out_dir, "swing_high.wav"), make_swing_loop(1.0, 520.0, 0.62, 0.45, 9000.0))
    write_wav(os.path.join(out_dir, "accent.wav"), make_accent())
    write_wav(os.path.join(out_dir, "ignite.wav"), make_ignite())
    write_wav(os.path.join(out_dir, "retract.wav"), make_retract())


if __name__ == "__main__":
    main()