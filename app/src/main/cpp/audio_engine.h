// Oboe-facing engine. The implementation lives in audio_engine.cpp; the class is declared here so
// the JNI bridge can reach the singleton without duplicating the interface list.
#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

#include "mixer.h"

namespace lightsaber {

constexpr int32_t kChannelCount = 1;  // mono: a watch speaker is mono, and mono halves the work

// Oboe 1.11 names the error interface AudioStreamErrorCallback; the older oboe::ErrorCallback no
// longer exists, and passing `this` to setErrorCallback fails to convert without this base class.
class AudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
 public:
  static AudioEngine& instance() {
    static AudioEngine engine;
    return engine;
  }

  /** What the platform actually granted, which is not the same as what was requested. */
  struct StreamInfo {
    float sampleRate = 0.f;
    int32_t channelCount = 0;
    int32_t framesPerBurst = 0;
    int32_t performanceMode = -1;
    int32_t sharingMode = -1;
    int32_t format = -1;
    int32_t error = 0;
    bool running = false;
  };

  // --- Session thread only. ---

  bool open(MixerConfig config, int32_t generation, int32_t requestedSampleRate,
             int32_t requestedFrameCount);
  void close();
  void resetMixer();
  void setVolume(float volume);
  void pushControl(const ControlFrame& frame);
  void pushEvent(SessionEvent event);
  /** Takes ownership of a copy so the Mixer can hold a raw pointer for the process lifetime. */
  void loadAsset(AssetId id, const float* samples, size_t frames);
  MixCounters& counters() { return mixer_.counters(); }
  uint64_t callbacksFired() const { return callbacksFired_.load(std::memory_order_relaxed); }
  StreamInfo streamInfo() const;
  int32_t takePendingError();

 private:
  AudioEngine() = default;

  // --- Real-time audio thread. No locks, no allocation, no logging, no JNI. ---
  oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                        int32_t numFrames) override;
  void onErrorBeforeClose(oboe::AudioStream* stream, oboe::Result result) override;
  void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result result) override;

  std::mutex lifecycleMutex_;
  std::shared_ptr<oboe::AudioStream> stream_;
  Mixer mixer_;
  // Owns the sample buffers the Mixer points at. Indexed by AssetId.
  std::vector<std::vector<float>> assetStorage_{6};
  std::atomic<bool> running_{false};
  std::atomic<uint64_t> callbacksFired_{0};
  std::atomic<int32_t> pendingError_{0};

  float actualSampleRate_ = 0.f;
  int32_t actualChannelCount_ = 0;
  int32_t framesPerBurst_ = 0;
  oboe::PerformanceMode actualPerformanceMode_ = oboe::PerformanceMode::None;
  oboe::SharingMode actualSharingMode_ = oboe::SharingMode::Shared;
  oboe::AudioFormat actualFormat_ = oboe::AudioFormat::Invalid;
  oboe::Result lastError_ = oboe::Result::OK;
};

}  // namespace lightsaber