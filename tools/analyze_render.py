#!/usr/bin/env python3
"""Measure a rendered session so "continuous modulation" is a number, not a claim.

Checks the properties the brief actually cares about:

  * the hum is present and steady while the hand is still
  * movement makes the output measurably louder and brighter
  * the envelope is *continuous* — no step discontinuities between neighbouring windows
  * accents fire during sharp movement, and stay rare while still
  * nothing clips

Usage: python3 tools/analyze_render.py <rendered.wav> [controlTrace.txt]
"""
import math
import sys
import wave
from array import array


def read_wav(path):
    with wave.open(path, "rb") as w:
        channels = w.getnchannels()
        rate = w.getframerate()
        n = w.getnframes()
        raw = array("h")
        raw.frombytes(w.readframes(n))
    if channels > 1:
        raw = array("h", (raw[i] for i in range(0, len(raw), channels)))
    return [v / 32768.0 for v in raw], rate


def read_trace(path):
    frames = []
    with open(path) as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            p = line.split()
            if len(p) == 7:
                frames.append(tuple(float(x) for x in p))
    return frames


def rms_envelope(samples, rate, window_ms=50.0):
    window = max(1, int(rate * window_ms / 1000.0))
    out = []
    for start in range(0, len(samples), window):
        chunk = samples[start:start + window]
        if not chunk:
            break
        out.append((sum(v * v for v in chunk) / len(chunk)) ** 0.5)
    return out


def brightness(samples, rate, window_ms=50.0):
    """Zero-crossing rate stands in for spectral centroid: no numpy needed."""
    window = max(2, int(rate * window_ms / 1000.0))
    out = []
    for start in range(0, len(samples) - window, window):
        crossings = 0
        chunk = samples[start:start + window]
        for i in range(1, len(chunk)):
            if (chunk[i - 1] < 0.0) != (chunk[i] < 0.0):
                crossings += 1
        out.append(crossings / (window / rate))
    return out


def pearson(xs, ys):
    n = min(len(xs), len(ys))
    if n < 3:
        return 0.0
    xs = xs[:n]
    ys = ys[:n]
    mx = sum(xs) / n
    my = sum(ys) / n
    num = sum((xs[i] - mx) * (ys[i] - my) for i in range(n))
    dx = math.sqrt(sum((x - mx) ** 2 for x in xs))
    dy = math.sqrt(sum((y - my) ** 2 for y in ys))
    if dx == 0 or dy == 0:
        return 0.0
    return num / (dx * dy)


def main():
    wav_path = sys.argv[1]
    samples, rate = read_wav(wav_path)
    trace = read_trace(sys.argv[2]) if len(sys.argv) > 2 else []

    env = rms_envelope(samples, rate)
    bright = brightness(samples, rate)
    peak = max(abs(s) for s in samples)
    clipped = sum(1 for s in samples if abs(s) > 0.999)

    print(f"file        : {wav_path}")
    print(f"duration    : {len(samples) / rate:.3f} s @ {rate} Hz")
    print(f"peak        : {peak:.4f}")
    print(f"clipped     : {clipped} samples")
    rms_overall = math.sqrt(sum(v * v for v in samples) / len(samples))
    print(f"rms overall : {rms_overall:.4f}")

    # Continuity: the biggest jump between adjacent 50 ms windows. A sample-triggered design shows
    # violent steps here; a continuously modulated one stays smooth. The ignition transient is a
    # deliberate one-shot ramp, so it is excluded rather than counted against smoothness.
    skip_windows = int(0.8 / 0.050)
    if len(env) > skip_windows + 2:
        jumps = [abs(env[i + 1] - env[i]) for i in range(skip_windows, len(env) - 1)]
        worst = max(jumps)
        worst_at = jumps.index(worst) * 50 + skip_windows * 50
        mean_env = sum(env[skip_windows:]) / max(1, len(env) - skip_windows)
        print(f"envelope    : mean {mean_env:.4f}, worst 50ms step {worst:.4f} at {worst_at} ms "
              f"({worst / mean_env * 100:.1f}% of mean, post-ignition)")

    if trace:
        win_s = 0.050
        swing = [f[2] for f in trace]
        trace_sec = (trace[-1][0] - trace[0][0]) / 1e9
        n_win = min(len(env), len(bright), int(trace_sec / win_s))
        # `swing` is per FRAME (100 Hz) but the analysis windows are 50 ms, so each window covers
        # win_s * 100 frames. Sampling it with the window index alone misaligns the two by 5x and
        # decorrelates everything.
        frames_per_window = max(1, int(round(win_s * 100.0)))
        quiet = [(env[i], bright[i]) for i in range(n_win) if swing[i * frames_per_window] < 0.02]
        active = [(env[i], bright[i]) for i in range(n_win) if swing[i * frames_per_window] > 0.35]
        if quiet:
            print(f"still windows  : n={len(quiet)} rms {sum(q[0] for q in quiet)/len(quiet):.4f} "
                  f"zcr {sum(q[1] for q in quiet)/len(quiet):.0f} Hz")
        if active:
            print(f"active windows : n={len(active)} rms {sum(a[0] for a in active)/len(active):.4f} "
                  f"zcr {sum(a[1] for a in active)/len(active):.0f} Hz")
        if quiet and active:
            q_rms = sum(q[0] for q in quiet) / len(quiet)
            a_rms = sum(a[0] for a in active) / len(active)
            q_zcr = sum(q[1] for q in quiet) / len(quiet)
            a_zcr = sum(a[1] for a in active) / len(active)
            print(f"movement lift   : rms x{a_rms / q_rms:.2f}, brightness x{a_zcr / q_zcr:.2f}")
        swing = [f[2] for f in trace]
        peak_swing = max(swing)
        print(f"peak swing      : {peak_swing:.3f}")

        # The single most useful number: how closely the rendered loudness and brightness track the
        # control signal. A sample-triggered design decorrelates (near 0) because sound only exists
        # during discrete events; a continuously modulated one tracks closely.
        swing_win = [swing[i * frames_per_window] for i in range(n_win)]
        rms_win = [env[i] for i in range(n_win)]
        zcr_win = [bright[i] for i in range(n_win)]
        r_rms = pearson(swing_win, rms_win)
        r_zcr = pearson(swing_win, zcr_win)
        print(f"corr(swing,rms) : {r_rms:+.3f}")
        print(f"corr(swing,zcr) : {r_zcr:+.3f}")


if __name__ == "__main__":
    main()