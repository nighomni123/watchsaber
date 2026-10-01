// Offline renderer: drives the production Mixer with a recorded control trace and writes a WAV.
//
// This is the harness the brief asks for under "deterministic replay ... where practical, render the
// audio engine offline to a WAV file". It compiles the *same* mixer.cpp that ships on the watch, so
// a defect found here is a defect in the shipped engine, not in a test double.
//
// Usage:
//   offline_render <controlTrace.txt> <assetDir> <out.wav> [igniteAtSec] [retractAtSec]
//
// The trace format is the one :motion writes: '#' metadata lines, then one space-separated control
// frame per line: timestampNs generation swing twist accent dirX dirY.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "../src/main/cpp/mixer.h"
#include "wav.h"

using namespace lightsaber;

namespace {

struct TraceFrame {
  int64_t timestampNs;
  int32_t generation;
  float swing;
  float twist;
  float accent;
  float dirX;
  float dirY;
};

bool loadTrace(const char* path, std::vector<TraceFrame>& out, std::string& error) {
  FILE* f = std::fopen(path, "r");
  if (f == nullptr) {
    error = std::string("cannot open trace ") + path;
    return false;
  }
  char line[512];
  while (std::fgets(line, sizeof(line), f) != nullptr) {
    if (line[0] == '#' || line[0] == '\n' || line[0] == '\r') continue;
    TraceFrame t{};
    const int n = std::sscanf(line, "%lld %d %f %f %f %f %f",
                              reinterpret_cast<long long*>(&t.timestampNs), &t.generation, &t.swing,
                              &t.twist, &t.accent, &t.dirX, &t.dirY);
    if (n == 7) out.push_back(t);
  }
  std::fclose(f);
  if (out.empty()) {
    error = "trace contained no control frames";
    return false;
  }
  return true;
}

bool loadAsset(const std::string& dir, const char* name, host::Wav& wav, std::string& error) {
  return host::readWav(dir + "/" + name, wav, error);
}

}  // namespace

int main(int argc, char** argv) {
  if (argc < 4) {
    std::printf("usage: %s <controlTrace.txt> <assetDir> <out.wav> [igniteAtSec] [retractAtSec]\n", argv[0]);
    return 2;
  }
  const std::string tracePath = argv[1];
  const std::string assetDir = argv[2];
  const std::string outPath = argv[3];
  const double igniteAtSec = argc > 4 ? std::atof(argv[4]) : 0.0;
  const double retractAtSec = argc > 5 ? std::atof(argv[5]) : -1.0;

  std::string error;
  std::vector<TraceFrame> trace;
  if (!loadTrace(tracePath.c_str(), trace, error)) {
    std::fprintf(stderr, "error: %s\n", error.c_str());
    return 1;
  }

  const int sampleRate = 48000;
  const int blockFrames = 192;  // 4 ms — a typical AAudio callback burst

  host::Wav hum, swingLow, swingHigh, accent, ignite, retract;
  if (!loadAsset(assetDir, "hum.wav", hum, error) ||
      !loadAsset(assetDir, "swing_low.wav", swingLow, error) ||
      !loadAsset(assetDir, "swing_high.wav", swingHigh, error) ||
      !loadAsset(assetDir, "accent.wav", accent, error) ||
      !loadAsset(assetDir, "ignite.wav", ignite, error) ||
      !loadAsset(assetDir, "retract.wav", retract, error)) {
    std::fprintf(stderr, "error: %s\n", error.c_str());
    return 1;
  }

  Mixer mixer;
  mixer.prepare(sampleRate);
  mixer.setSessionGeneration(trace.front().generation);
  mixer.loadAsset(AssetId::kHum, hum.samples.data(), hum.samples.size());
  mixer.loadAsset(AssetId::kSwingLow, swingLow.samples.data(), swingLow.samples.size());
  mixer.loadAsset(AssetId::kSwingHigh, swingHigh.samples.data(), swingHigh.samples.size());
  mixer.loadAsset(AssetId::kAccent, accent.samples.data(), accent.samples.size());
  mixer.loadAsset(AssetId::kIgnite, ignite.samples.data(), ignite.samples.size());
  mixer.loadAsset(AssetId::kRetract, retract.samples.data(), retract.samples.size());

  const int64_t t0 = trace.front().timestampNs;
  const int64_t t1 = trace.back().timestampNs;
  const double totalSec = (t1 - t0) / 1e9 + 0.5;  // a little tail for the fade out
  const int64_t totalFrames = static_cast<int64_t>(totalSec * sampleRate);

  std::vector<float> output;
  output.reserve(static_cast<size_t>(totalFrames) + blockFrames);

  std::vector<float> block(blockFrames);
  size_t nextFrame = 0;
  bool ignited = false;
  bool retracted = false;
  int64_t igniteFrame = static_cast<int64_t>(igniteAtSec * sampleRate);
  int64_t retractFrame =
      retractAtSec >= 0.0 ? static_cast<int64_t>(retractAtSec * sampleRate) : totalFrames;

  int64_t rendered = 0;
  while (rendered < totalFrames) {
    const int64_t blockStartNs = t0 + (rendered * 1e9) / sampleRate;
    const int64_t blockEndNs = t0 + ((rendered + blockFrames) * 1e9) / sampleRate;

    // Session transitions land on the same timeline the sensor trace uses.
    if (!ignited && rendered >= igniteFrame) {
      mixer.pushEvent(SessionEvent::kIgnite);
      ignited = true;
    }
    if (!retracted && rendered >= retractFrame && retractFrame < totalFrames) {
      mixer.pushEvent(SessionEvent::kRetract);
      retracted = true;
      retractFrame = totalFrames;
    }

    // Feed every control frame whose timestamp falls inside this block, exactly as the sensor
    // thread would push them. The queue and the in-callback interpolation are therefore exercised
    // on the same path as on-device.
    while (nextFrame < trace.size() && trace[nextFrame].timestampNs < blockEndNs) {
      ControlFrame f{};
      f.timestampNs = trace[nextFrame].timestampNs;
      f.sessionGeneration = trace[nextFrame].generation;
      f.swingIntensity = trace[nextFrame].swing;
      f.twistSigned = trace[nextFrame].twist;
      f.accelAccent = trace[nextFrame].accent;
      f.directionX = trace[nextFrame].dirX;
      f.directionY = trace[nextFrame].dirY;
      if (f.timestampNs >= blockStartNs) mixer.pushControl(f);
      ++nextFrame;
    }

    mixer.render(block.data(), blockFrames);
    output.insert(output.end(), block.begin(), block.end());
    rendered += blockFrames;
  }

  if (retracted) mixer.pushEvent(SessionEvent::kStopImmediate);
  const int tail = sampleRate / 4;
  for (int i = 0; i < tail; i += blockFrames) {
    mixer.render(block.data(), blockFrames);
    output.insert(output.end(), block.begin(), block.end());
  }

  if (!host::writeWav(outPath, output, sampleRate, error)) {
    std::fprintf(stderr, "error: %s\n", error.c_str());
    return 1;
  }

  const auto& c = mixer.counters();
  std::printf("rendered %.3f s at %d Hz\n", output.size() / static_cast<double>(sampleRate), sampleRate);
  std::printf("  control frames consumed : %llu (dropped %llu)\n",
              static_cast<unsigned long long>(c.controlFramesConsumed.load()),
              static_cast<unsigned long long>(c.controlFramesDropped.load()));
  std::printf("  accent triggers        : %llu\n",
              static_cast<unsigned long long>(c.accentTriggers.load()));
  std::printf("  peak |sample|          : %.4f\n", c.peakAbs.load() / 1000.0f);
  std::printf("  clipped samples        : %llu\n",
              static_cast<unsigned long long>(c.clippedSamples.load()));
  return 0;
}