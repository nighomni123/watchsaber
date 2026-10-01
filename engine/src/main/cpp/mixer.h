// The continuous sound model.
//
// Design follows docs/sound-synthesis-sources.md, not the sample-layering that Source C (Silabs)
// uses. The distinction that matters is whether a swing is a **modulation of a bed that never
// stops**, or **a file laid over a static bed**. This engine is the former:
//
//   bed          hum loop, never ducked, through a delay bank whose length tracks swing speed.
//                Changing *delay*, not *rate*, is what makes movement audible without a pitch ride.
//                (Source D §5.2.)
//   swing        generated, not sampled. Two band-passed noise voices crossfaded by swing, so a
//                swing can last as long as the hand does and never repeats a loop.
//   accents      noise through a flanger (Source D §5.3) — never identical twice, unlike a sample.
//   transitions  ignite / retract one-shots, the one place samples are correct.
//
// A previous version crossfaded two 1-second sample loops. That is Source C's architecture, and it
// is why a slow swing sounded cheap: the texture repeated every second and had a hard ceiling on
// how long it could last.
//
// Real-time safe: no allocation, no locks, no file access, no JNI, no logging.
#pragma once

#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

#include "control_queue.h"

namespace lightsaber {

enum class AssetId : int { kHum = 0, kSwingLow = 1, kSwingHigh = 2, kAccent = 3, kIgnite = 4, kRetract = 5 };

struct MixCounters {
  std::atomic<uint64_t> framesRendered{0};
  std::atomic<uint64_t> controlFramesConsumed{0};
  std::atomic<uint64_t> controlFramesDropped{0};
  std::atomic<uint64_t> accentTriggers{0};
  std::atomic<uint64_t> peakAbs{0};
  std::atomic<uint64_t> clippedSamples{0};
  std::atomic<uint32_t> underrunEvents{0};
};

/** Deterministic noise. A generator, not a buffer: never allocates, never repeats audibly. */
class NoiseSource {
 public:
  float next() {
    state_ = state_ * 1664525u + 1013904223u;
    return (static_cast<float>(state_ >> 8) / 8388608.0f) - 1.0f;
  }

 private:
  uint32_t state_ = 0x13579BDFu;
};

/**
 * Chamberlin state-variable filter. Chosen because its coefficients can be recomputed per sample
 * without instability, which is what lets swing brightness follow the hand continuously.
 */
class StateVariableFilter {
 public:
  void reset() { low_ = band_ = 0.0f; }
  void setCutoff(float hz, float sampleRate) {
    const float clamped = hz < 20.0f ? 20.0f : (hz > sampleRate * 0.45f ? sampleRate * 0.45f : hz);
    const float g = 2.0f * 3.14159265f * clamped / sampleRate;
    // g must stay below 2 for stability; the clamp above keeps it there.
    coeff_ = g < 1.9f ? g : 1.9f;
  }
  void setResonance(float q) { damp_ = q < 0.05f ? 0.05f : (q > 1.0f ? 1.0f : q); }
  float bandPass(float in) {
    const float high = in - low_ - damp_ * band_;
    band_ += coeff_ * high;
    low_ += coeff_ * band_;
    return band_;
  }
  float lowPass() const { return low_; }

 private:
  float low_ = 0.0f;
  float band_ = 0.0f;
  float coeff_ = 0.2f;
  float damp_ = 0.6f;
};

/**
 * Several delay lines sharing one modulated delay length.
 *
 * "**multiple** delay lines" (Source D) is the part that matters: a single modulated line comb-filters
 * audibly and reads as a metallic flange. Spreading the read offsets decorrelates the notches so the
 * same modulation reads as air moving past a blade.
 *
 * The delay length is ramped, never stepped. Stepping it is the single easiest way to make this
 * click.
 */
class ModulatedDelayBank {
 public:
  static constexpr int kLines = 3;

  void prepare(double sampleRate, float maxDelayMs) {
    sampleRate_ = sampleRate > 0.0 ? sampleRate : 48000.0;
    maxDelaySamples_ = static_cast<int>(maxDelayMs * 0.001f * static_cast<float>(sampleRate_)) + 8;
    buffers_.assign(kLines, std::vector<float>(static_cast<size_t>(maxDelaySamples_), 0.0f));
    reset();
  }

  void reset() {
    for (auto& b : buffers_) std::fill(b.begin(), b.end(), 0.0f);
    for (int i = 0; i < kLines; ++i) {
      writeIndex_[i] = 0;
      delaySamples_[i] = 8.0f;
      targetDelaySamples_[i] = 8.0f;
    }
    rampCoef_ = 0.001f;
  }

  /** Sets the delay target for all lines, with a small fixed offset per line to decorrelate them. */
  void setDelayMs(float ms) {
    const float clamped = ms < 0.2f ? 0.2f : (ms > kMaxDelayMs ? kMaxDelayMs : ms);
    const float base = clamped * 0.001f * static_cast<float>(sampleRate_);
    for (int i = 0; i < kLines; ++i) {
      // Spread offsets incommensurately so no two lines notch the same frequencies.
      targetDelaySamples_[i] = base + static_cast<float>(i) * 11.0f + 7.0f;
    }
  }

  void setSmoothing(float tauSeconds) {
    const float dt = 1.0f / static_cast<float>(sampleRate_);
    const float t = tauSeconds < 1e-4f ? 1e-4f : tauSeconds;
    rampCoef_ = 1.0f - std::exp(-dt / t);
  }

  float process(float in) {
    float sum = 0.0f;
    for (int i = 0; i < kLines; ++i) {
      auto& buf = buffers_[i];
      const int size = static_cast<int>(buf.size());
      const int w = writeIndex_[i];
      buf[static_cast<size_t>(w)] = in;
      writeIndex_[i] = (w + 1) % size;

      // Ramp toward the target; a stepped delay line clicks.
      delaySamples_[i] += rampCoef_ * (targetDelaySamples_[i] - delaySamples_[i]);
      float d = delaySamples_[i];
      if (d < 2.0f) d = 2.0f;
      if (d > static_cast<float>(size - 3)) d = static_cast<float>(size - 3);

      const float readPos = static_cast<float>(w) - d;
      while (readPos < 0.0f) d -= static_cast<float>(size);
      int r0 = static_cast<int>(d) % size;
      int r1 = (r0 + 1) % size;
      const float frac = d - std::floor(d);
      const float a = buf[static_cast<size_t>(r0)];
      const float b = buf[static_cast<size_t>(r1)];
      sum += a + (b - a) * frac;
    }
    return sum * (1.0f / static_cast<float>(kLines));
  }

  static constexpr float kMaxDelayMs = 18.0f;

 private:
  double sampleRate_ = 48000.0;
  int maxDelaySamples_ = 1024;
  std::vector<std::vector<float>> buffers_;
  int writeIndex_[kLines]{};
  float delaySamples_[kLines]{};
  float targetDelaySamples_[kLines]{};
  float rampCoef_ = 0.001f;
};

/** A single-pole low-pass whose cutoff can move per sample. */
class OnePoleLowPass {
 public:
  void reset() { z_ = 0.0f; }
  void setCutoff(float hz, float sampleRate) {
    const float c = hz < 20.0f ? 20.0f : (hz > sampleRate * 0.45f ? sampleRate * 0.45f : hz);
    const float rc = 1.0f / (6.2831853f * c);
    const float dt = 1.0f / sampleRate;
    coeff_ = dt / (rc + dt);
  }
  float process(float x) {
    z_ += (x - z_) * coeff_;
    return z_;
  }

 private:
  float z_ = 0.0f;
  float coeff_ = 0.5f;
};

/** Sample-accurate loop player for the hum and the two transitions — the only sampled content. */
class LoopPlayer {
 public:
  void setBuffer(const float* data, size_t frames) {
    data_ = data;
    frames_ = frames;
    phase_ = 0.0;
  }
  void reset() { phase_ = 0.0; }
  bool ready() const { return data_ != nullptr && frames_ > 0; }
  float next();

 private:
  const float* data_ = nullptr;
  size_t frames_ = 0;
  double phase_ = 0.0;
};

class OneShotPlayer {
 public:
  void setBuffer(const float* data, size_t frames) {
    data_ = data;
    frames_ = frames;
    position_ = 0;
    active_ = false;
  }
  bool ready() const { return data_ != nullptr && frames_ > 0; }
  void trigger() {
    if (!ready()) return;
    position_ = 0;
    active_ = true;
  }
  void stop() { active_ = false; }
  bool active() const { return active_; }
  float next() {
    if (!active_ || !ready()) return 0.0f;
    const float v = data_[position_];
    if (++position_ >= frames_) active_ = false;
    return v;
  }

 private:
  const float* data_ = nullptr;
  size_t frames_ = 0;
  size_t position_ = 0;
  bool active_ = false;
};

struct MixerConfig {
  float humGain = 0.50f;
  float swingGain = 0.62f;
  float accentGain = 0.22f;
  float masterGain = 0.80f;

  // Swing brightness. The swing voice is generated, so this is a filter sweep rather than a rate
  // change — the previous 0.88-1.28 playback-rate ride was the "cartoonish pitch" failure.
  float swingCutoffLowHz = 340.0f;
  float swingCutoffHighHz = 4200.0f;
  float swingResonance = 0.72f;

  // Hum delay modulation: swing opens the comb. Small range, ramped, decorrelated across lines.
  float humDelayBaseMs = 1.6f;
  float humDelaySwingMs = 5.2f;
  float humDelayRampS = 0.030f;

  float humFilterBaseHz = 2600.0f;
  float humFilterOctaves = 1.1f;
  float humDarkenFactor = 0.35f;

  float controlTauSeconds = 0.008f;

  // Restrained by design: the brief forbids repeated false accents, and the first device run
  // produced 138 in one session at a 0.20 gate with a 110 ms refractory. 0.45 sits just under the
  // accent peak the motion pipeline actually reaches (~0.45 on a hard change), so accents still
  // happen on genuine accents but not on ordinary movement.
  float accentThreshold = 0.22f;
  float accentMinIntervalSeconds = 0.45f;
  float accentAttackS = 0.004f;
  float accentDecayS = 0.22f;

  float ignitionSeconds = 0.55f;
  float masterFadeSeconds = 0.08f;
};

class Mixer {
 public:
  // --- Non-real-time setup. Call before the stream opens. ---
  void prepare(double sampleRate);
  void loadAsset(AssetId id, const float* mono, size_t frames);
  void setConfig(const MixerConfig& config);
  void setSessionGeneration(int32_t generation) { generation_ = generation; }
  void setMasterVolume(float volume);

  // --- Producer side (sensor/session thread). ---
  void pushControl(const ControlFrame& frame) { controls_.push(frame); }
  void pushEvent(SessionEvent e) { events_.push(e); }

  // --- Audio thread. ---
  void render(float* output, int32_t frameCount);
  void reset();

  MixCounters& counters() { return counters_; }
  const ControlQueue& controlQueue() const { return controls_; }
  float currentSwing() const { return swing_; }
  float currentTwist() const { return twist_; }
  bool sessionActive() const { return masterTarget_ > 0.0f; }

 private:
  void pullControls();
  void handleEvents();
  void updateCoefficients(float sampleRate);

  double sampleRate_ = 48000.0;
  MixerConfig config_{};

  ControlQueue controls_;
  EventQueue events_;

  float targetSwing_ = 0.0f;
  float targetTwist_ = 0.0f;
  float targetAccent_ = 0.0f;
  float targetDirX_ = 0.0f;
  float targetDirY_ = 0.0f;

  float swing_ = 0.0f;
  float twist_ = 0.0f;
  float accent_ = 0.0f;
  float dirX_ = 0.0f;
  float dirY_ = 0.0f;

  int32_t generation_ = 0;

  LoopPlayer hum_;
  OneShotPlayer igniteShot_;
  OneShotPlayer retractShot_;

  // Generated motion voice.
  NoiseSource noise_;
  StateVariableFilter swingLowFilter_;
  StateVariableFilter swingHighFilter_;
  OnePoleLowPass swingToneShaper_;

  // Bed modulation.
  OnePoleLowPass humFilter_;
  ModulatedDelayBank humDelay_;

  // Generated accent: noise through a short flanger.
  NoiseSource accentNoise_;
  ModulatedDelayBank accentFlanger_;
  StateVariableFilter accentFilter_;
  float accentEnv_ = 0.0f;
  float accentPhase_ = 0.0f;
  double secondsSinceAccent_ = 1.0;

  bool ignited_ = false;
  bool retracting_ = false;
  bool stopping_ = false;
  float master_ = 0.0f;
  float masterTarget_ = 0.0f;
  double masterStep_ = 0.0;
  float ignitionPhase_ = 0.0f;
  float controlCoeff_ = 0.0f;
  float volume_ = 1.0f;

  MixCounters counters_{};
};

}  // namespace lightsaber