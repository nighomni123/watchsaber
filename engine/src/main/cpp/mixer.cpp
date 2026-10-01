#include "mixer.h"

#include <algorithm>
#include <cmath>

namespace lightsaber {
namespace {

constexpr float kPi = 3.14159265f;

// Coefficients refresh every N samples: ~375 Hz at a 48 kHz, comfortably above the ~100 Hz control rate
// and far below anything that costs a pow() per sample.
constexpr int kControlBlock = 128;

inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

// tanh-shaped soft clip: a safeguard at the end of the chain, not a mixing strategy.
inline float softClip(float x) {
  const float t = clampf(x, -1.5f, 1.5f);
  return t * (27.0f + t * t) / (27.0f + 9.0f * t * t);
}

}  // namespace

float LoopPlayer::next() {
  if (!ready()) return 0.0f;
  const size_t idx = static_cast<size_t>(phase_);
  const size_t idxNext = (idx + 1) % frames_;
  const float frac = static_cast<float>(phase_ - static_cast<double>(idx));
  const float a = data_[idx];
  const float b = data_[idxNext];
  phase_ += 1.0;  // the hum is the one bed that must never change rate
  if (phase_ >= static_cast<double>(frames_)) phase_ -= static_cast<double>(frames_);
  return a + (b - a) * frac;
}

void Mixer::prepare(double sampleRate) {
  sampleRate_ = sampleRate > 0.0 ? sampleRate : 48000.0;
  const float sr = static_cast<float>(sampleRate_);
  const float dt = 1.0f / sr;

  controlCoeff_ = 1.0f - std::exp(-dt / std::max(config_.controlTauSeconds, 1e-4f));
  masterStep_ = dt / std::max(config_.masterFadeSeconds, 1e-4f);

  // Generous headroom on the delay buffers; the bank clamps its own read length.
  humDelay_.prepare(sampleRate_, ModulatedDelayBank::kMaxDelayMs + 2.0f);
  humDelay_.setSmoothing(config_.humDelayRampS);
  accentFlanger_.prepare(sampleRate_, 8.0f);
  accentFlanger_.setSmoothing(0.020f);

  swingLowFilter_.setResonance(config_.swingResonance);
  swingHighFilter_.setResonance(config_.swingResonance);
  accentFilter_.setResonance(0.45f);

  reset();
}

void Mixer::setConfig(const MixerConfig& config) {
  config_ = config;
  const float sr = static_cast<float>(sampleRate_ > 0.0 ? sampleRate_ : 48000.0);
  const float dt = 1.0f / sr;
  controlCoeff_ = 1.0f - std::exp(-dt / std::max(config_.controlTauSeconds, 1e-4f));
  masterStep_ = dt / std::max(config_.masterFadeSeconds, 1e-4f);
  humDelay_.setSmoothing(config_.humDelayRampS);
  swingLowFilter_.setResonance(config_.swingResonance);
  swingHighFilter_.setResonance(config_.swingResonance);
}

void Mixer::setMasterVolume(float volume) { volume_ = clampf(volume, 0.0f, 1.0f); }

void Mixer::loadAsset(AssetId id, const float* mono, size_t frames) {
  // Only the bed and the two transitions are sampled now; the swing and accent voices are
  // generated, so kSwingLow / kSwingHigh / kAccent are accepted and ignored on purpose.
  switch (id) {
    case AssetId::kHum:
      hum_.setBuffer(mono, frames);
      break;
    case AssetId::kIgnite:
      igniteShot_.setBuffer(mono, frames);
      break;
    case AssetId::kRetract:
      retractShot_.setBuffer(mono, frames);
      break;
    default:
      break;
  }
}

void Mixer::reset() {
  controls_.clear();
  events_.clear();
  targetSwing_ = targetTwist_ = targetAccent_ = targetDirX_ = targetDirY_ = 0.0f;
  swing_ = twist_ = accent_ = dirX_ = dirY_ = 0.0f;
  hum_.reset();
  igniteShot_.stop();
  retractShot_.stop();
  swingLowFilter_.reset();
  swingHighFilter_.reset();
  swingToneShaper_.reset();
  humFilter_.reset();
  humDelay_.reset();
  accentFlanger_.reset();
  accentFilter_.reset();
  accentEnv_ = 0.0f;
  accentPhase_ = 0.0f;
  ignited_ = false;
  retracting_ = false;
  stopping_ = false;
  master_ = 0.0f;
  masterTarget_ = 0.0f;
  ignitionPhase_ = 0.0f;
  secondsSinceAccent_ = 1.0;
}

void Mixer::handleEvents() {
  SessionEvent e;
  while (events_.pop(e)) {
    switch (e) {
      case SessionEvent::kIgnite:
        ignited_ = true;
        retracting_ = false;
        stopping_ = false;
        igniteShot_.trigger();
        ignitionPhase_ = 0.0f;
        masterTarget_ = 1.0f;
        break;
      case SessionEvent::kRetract:
        retracting_ = true;
        stopping_ = false;
        retractShot_.trigger();
        break;
      case SessionEvent::kStopImmediate:
        stopping_ = true;
        retracting_ = false;
        masterTarget_ = 0.0f;
        break;
    }
  }
}

void Mixer::pullControls() {
  ControlFrame frame;
  while (controls_.pop(frame)) {
    counters_.controlFramesConsumed.fetch_add(1, std::memory_order_relaxed);
    if (frame.sessionGeneration != generation_) continue;  // stale session, drop it
    targetSwing_ = clampf(frame.swingIntensity, 0.0f, 1.0f);
    targetTwist_ = clampf(frame.twistSigned, -1.0f, 1.0f);
    targetAccent_ = clampf(frame.accelAccent, 0.0f, 1.0f);
    targetDirX_ = clampf(frame.directionX, -1.0f, 1.0f);
    targetDirY_ = clampf(frame.directionY, -1.0f, 1.0f);
  }
}

void Mixer::updateCoefficients(float sampleRate) {
  const float presence = clampf(swing_, 0.0f, 1.0f);
  const float twistMag = std::fabs(twist_);

  const float humCutoff = config_.humFilterBaseHz *
                          std::pow(2.0f, twistMag * config_.humFilterOctaves) *
                          (twist_ < 0.0f ? config_.humDarkenFactor : 1.0f);
  humFilter_.setCutoff(humCutoff, sampleRate);

  // Source D §5.2: the delay length carries the Doppler, so the pitch never has to.
  humDelay_.setDelayMs(config_.humDelayBaseMs + config_.humDelaySwingMs * presence);

  // ~340 Hz at rest to ~3.2 kHz at full swing, across the two band-pass voices.
  const float cutoff = config_.swingCutoffLowHz * std::pow(2.0f, presence * 2.4f);
  swingLowFilter_.setCutoff(cutoff * 0.75f, sampleRate);
  swingHighFilter_.setCutoff(cutoff * 2.6f, sampleRate);
}

void Mixer::render(float* output, int32_t frameCount) {
  if (output == nullptr || frameCount <= 0) return;

  handleEvents();

  const float sr = static_cast<float>(sampleRate_);
  updateCoefficients(sr);
  const float dt = 1.0f / sr;
  float peak = 0.0f;
  uint64_t clipped = 0;

  for (int32_t i = 0; i < frameCount; ++i) {
    pullControls();

    // In-callback interpolation: a 100 Hz control rate must not reach the speaker as a staircase.
    swing_ += controlCoeff_ * (targetSwing_ - swing_);
    twist_ += controlCoeff_ * (targetTwist_ - twist_);
    accent_ += controlCoeff_ * (targetAccent_ - accent_);
    dirX_ += controlCoeff_ * (targetDirX_ - dirX_);
    dirY_ += controlCoeff_ * (targetDirY_ - dirY_);

    const float presence = clampf(swing_, 0.0f, 1.0f);

    // Filter coefficients are recomputed on a control block, NOT per sample. Calling std::pow
    // three times per sample meant ~144,000 pow() calls per second at 48 kHz, which pegged a watch
    // core at 100% and produced an ANR rather than audio. The control rate is ~100 Hz, so the
    // coefficients are smooth enough to be far coarser than the sample rate.
    if (i % kControlBlock == 0) updateCoefficients(sr);

    // --- Bed: hum, twisted, with swing opening the comb ---------------------------------
    float bed = humFilter_.process(hum_.next());
    // Dry/wet crossfade: at rest the bed is undelayed and pure, under movement the comb opens.
    const float wet = presence * 0.85f;
    bed = bed * (1.0f - wet * 0.45f) + humDelay_.process(bed) * wet * 0.55f;
    bed *= config_.humGain;

    // --- Swing: generated, so a swing can outlast any loop -----------------------------
    const float n = noise_.next();
    const float lowVoice = swingLowFilter_.bandPass(n);
    const float highVoice = swingHighFilter_.bandPass(n);
    // Power-complementary across two *generated* voices: constant summed power, no correlated
    // loops to beat against each other.
    const float angle = presence * (kPi * 0.5f);
    const float swingBus = (lowVoice * std::cos(angle) + highVoice * std::sin(angle)) *
                           swingToneShaper_.process(1.0f);
    const float tilt = 1.0f + 0.10f * (dirX_ * 0.6f + dirY_ * 0.4f) * presence;
    float swingSignal = swingBus * config_.swingGain * presence * tilt;

    // --- Accents: noise through a flanger, never identical twice ------------------------
    secondsSinceAccent_ += static_cast<double>(dt);
    if (accent_ >= config_.accentThreshold && accentEnv_ <= 1e-4f &&
        secondsSinceAccent_ >= static_cast<double>(config_.accentMinIntervalSeconds)) {
      accentPhase_ = 0.0f;
      secondsSinceAccent_ = 0.0;
      counters_.accentTriggers.fetch_add(1, std::memory_order_relaxed);
    }
    float accentSignal = 0.0f;
    if (accent_ >= config_.accentThreshold * 0.6f) {
      // Flanger sweep: the delay ramps up and back, which is what makes it read as a rush of air.
      accentPhase_ += dt / std::max(config_.accentDecayS, 0.01f);
      const float sweep = accentPhase_ < 1.0f ? accentPhase_ : 0.0f;
      if (i % kControlBlock == 0) {
        accentFlanger_.setDelayMs(1.0f + 4.5f * (1.0f - std::fabs(sweep * 2.0f - 1.0f)));
        accentFilter_.setCutoff(1200.0f + 3600.0f * sweep, sr);
      }
      const float burst = accentFilter_.bandPass(accentNoise_.next());
      // Attack fast, decay exponential — an accent is a transient, not a layer.
      accentEnv_ += (sweep > 0.0f ? 1.0f : 0.0f) * dt / std::max(config_.accentAttackS, 1e-4f) -
                    accentEnv_ * (dt / std::max(config_.accentDecayS, 0.01f));
      accentSignal = accentFlanger_.process(burst) * clampf(accentEnv_, 0.0f, 1.0f) *
                      config_.accentGain;
    } else {
      accentEnv_ = 0.0f;
    }

    float mix = bed + swingSignal + accentSignal;

    if (igniteShot_.active()) {
      ignitionPhase_ += dt;
      const float fade = clampf(1.0f - ignitionPhase_ / config_.ignitionSeconds, 0.0f, 1.0f);
      mix += igniteShot_.next() * 0.55f * fade;
    }
    if (retractShot_.active()) {
      mix += retractShot_.next() * 0.45f;
      if (!retractShot_.active()) retracting_ = false;
    }

    if (master_ < masterTarget_) {
      master_ = std::min(master_ + static_cast<float>(masterStep_), masterTarget_);
    } else if (master_ > masterTarget_) {
      master_ = std::max(master_ - static_cast<float>(masterStep_), masterTarget_);
    }

    float out = softClip(mix * master_ * config_.masterGain * volume_);
    const float absOut = std::fabs(out);
    if (absOut > peak) peak = absOut;
    if (absOut > 0.999f) ++clipped;
    output[i] = out;
  }

  counters_.framesRendered.fetch_add(static_cast<uint64_t>(frameCount), std::memory_order_relaxed);
  counters_.controlFramesDropped.store(controls_.droppedCount(), std::memory_order_relaxed);
  const uint32_t peakMilli = static_cast<uint32_t>(peak * 1000.0f);
  if (peakMilli > counters_.peakAbs.load(std::memory_order_relaxed)) {
    counters_.peakAbs.store(peakMilli, std::memory_order_relaxed);
  }
  if (clipped > 0) counters_.clippedSamples.fetch_add(clipped, std::memory_order_relaxed);
}

}  // namespace lightsaber