#!/usr/bin/env python3
"""Print a time-aligned table of control values and rendered loudness.

Used to check that the audio actually follows the control trace rather than drifting from it.

Usage: python3 tools/trace_vs_audio.py <controlTrace.txt> <rendered.wav> [bucketMs]
"""
import math
import sys
import wave
from array import array


def read_trace(path):
    out = []
    with open(path) as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            p = line.split()
            if len(p) == 7:
                out.append([float(x) for x in p])
    return out


def main():
    trace = read_trace(sys.argv[1])
    with wave.open(sys.argv[2], "rb") as w:
        rate = w.getframerate()
        n = w.getnframes()
        raw = array("h")
        raw.frombytes(w.readframes(n))
    samples = [v / 32768.0 for v in raw]

    bucket_ms = float(sys.argv[3]) if len(sys.argv) > 3 else 250.0
    win = int(rate * bucket_ms / 1000.0)
    frames_per_bucket = int(100 * bucket_ms / 1000.0)  # trace is generated at 100 Hz

    t0 = trace[0][0]
    trace_sec = (trace[-1][0] - t0) / 1e9
    n_buckets = min(int(trace_sec / (bucket_ms / 1000.0)), len(samples) // win)

    print(f"{'time':>7} {'swing':>7} {'twist':>7} {'accent':>7} {'rms':>8} {'zcr Hz':>8}")
    prev_acc = 0.0
    for b in range(n_buckets):
        f0 = b * frames_per_bucket
        f1 = min(len(trace), f0 + frames_per_bucket)
        if f1 <= f0:
            break
        swing = sum(f[2] for f in trace[f0:f1]) / (f1 - f0)
        twist = sum(abs(f[3]) for f in trace[f0:f1]) / (f1 - f0)
        accent = sum(f[4] for f in trace[f0:f1]) / (f1 - f0)
        chunk = samples[b * win:(b + 1) * win]
        if not chunk:
            break
        rms = math.sqrt(sum(v * v for v in chunk) / len(chunk))
        zc = sum(1 for i in range(1, len(chunk)) if (chunk[i - 1] < 0) != (chunk[i] < 0))
        zcr = zc / (len(chunk) / rate)
        marker = "  <-- accent" if accent > prev_acc + 0.05 else ""
        print(f"{b * bucket_ms / 1000:7.2f} {swing:7.3f} {twist:7.3f} {accent:7.3f} {rms:8.4f} {zcr:8.0f}{marker}")
        prev_acc = accent


if __name__ == "__main__":
    main()