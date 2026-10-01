// Oboe glue. The only part of the engine that knows Android exists.
//
// Everything latency- or correctness-critical lives in mixer.cpp, which knows nothing about Oboe
// and is exercised on the host by the offline renderer. This file opens a stream, hands buffers to
// the mixer, and reports what the platform actually granted — because "request LowLatency" and
// "got LowLatency" are different claims and only the second one can be verified on a device.
#include "audio_engine.h"

#include <cstring>
#include <mutex>

namespace lightsaber {

bool AudioEngine::open(MixerConfig config, int32_t generation, int32_t requestedSampleRate,
                       int32_t requestedFrameCount) {
  std::lock_guard<std::mutex> lock(lifecycleMutex_);
  if (stream_ != nullptr) return true;

  mixer_.setConfig(config);
  mixer_.setSessionGeneration(generation);

  oboe::AudioStreamBuilder builder;
  builder.setDirection(oboe::Direction::Output)
      // OpenSL ES, not AAudio. Measured on the SM-R870: the AAudio path opens, reports
      // "state:started", and then delivers exactly ONE zero-frame callback before stalling
      // permanently (callbacksFired stuck at 1, framesRendered at 0, no Oboe error). The legacy
      // OpenSL path on the same device sustained a continuous callback and rendered normally.
      // The "one callback then silence" failure is a stalled AAudio device, not a mixer bug.
      ->setAudioApi(oboe::AudioApi::OpenSLES)
      // Shared, not Exclusive: the platform log for this route says
      // "EXCLUSIVE sharing mode not supported. Use SHARED."
      ->setSharingMode(oboe::SharingMode::Shared)
      // None, not LowLatency: this route strips the FAST flag and downgrades perfMode 12 -> 10
      // regardless of what is asked for, and asking for it produced a stream that started and
      // then delivered exactly one zero-frame callback before stalling.
      ->setPerformanceMode(oboe::PerformanceMode::None)
      ->setFormat(oboe::AudioFormat::Float)
      ->setChannelCount(kChannelCount)
      ->setDataCallback(this)
      ->setErrorCallback(this)
      ->setUsage(oboe::Usage::Game)
      ->setContentType(oboe::ContentType::Sonification);

  // The platform guidance is to use the recommended native rate and buffer size rather than
  // assume them; both are read by the Kotlin side from AudioManager.getProperty().
  if (requestedSampleRate > 0) builder.setSampleRate(static_cast<int32_t>(requestedSampleRate));
  // Oboe 1.11 has no burst-size setter on the builder; the buffer size is applied to the stream
  // after it opens and before it starts.

  std::shared_ptr<oboe::AudioStream> stream;
  oboe::Result result = builder.openStream(stream);
  if (result != oboe::Result::OK) {
    // Retry once on whatever Oboe now believes is supportable, before giving up: a stream at
    // higher latency still beats no sound at all.
    builder.setSharingMode(oboe::SharingMode::Shared);
    result = builder.openStream(stream);
  }
  if (result != oboe::Result::OK || stream == nullptr) {
    lastError_ = result;
    pendingError_.store(static_cast<int32_t>(result), std::memory_order_release);
    return false;
  }

  stream_ = stream;
  actualSampleRate_ = static_cast<float>(stream->getSampleRate());
  actualChannelCount_ = stream->getChannelCount();
  actualPerformanceMode_ = stream->getPerformanceMode();
  actualSharingMode_ = stream->getSharingMode();
  actualFormat_ = stream->getFormat();
  framesPerBurst_ = stream->getFramesPerBurst();

  // The mixer must run at the rate the stream actually opened at, not the one we asked for.
  mixer_.prepare(actualSampleRate_);
  mixer_.setConfig(config);
  mixer_.setSessionGeneration(generation);

  if (requestedFrameCount > 0) {
    const oboe::Result bufferResult = stream_->setBufferSizeInFrames(static_cast<int32_t>(requestedFrameCount));
    if (bufferResult != oboe::Result::OK) {
      lastError_ = bufferResult;
      pendingError_.store(static_cast<int32_t>(bufferResult), std::memory_order_release);
    }
  }

  // Mark the engine running BEFORE requestStart. The audio thread can invoke onAudioReady the
  // instant the stream starts; if that first callback sees running_ == false and returns Stop, the
  // stream dies permanently while AudioFlinger still reports it as started — which presents as a
  // silent mixer rather than a failed stream.
  running_.store(true, std::memory_order_release);
  result = stream_->requestStart();
  if (result != oboe::Result::OK) {
    running_.store(false, std::memory_order_release);
    stream_->close();
    stream_.reset();
    lastError_ = result;
    pendingError_.store(static_cast<int32_t>(result), std::memory_order_release);
    return false;
  }
  return true;
}

void AudioEngine::close() {
  std::lock_guard<std::mutex> lock(lifecycleMutex_);
  if (stream_ == nullptr) return;
  stream_->requestStop();
  stream_->close();
  stream_.reset();
  running_.store(false, std::memory_order_release);
}

void AudioEngine::resetMixer() { mixer_.reset(); }

void AudioEngine::setVolume(float v) { mixer_.setMasterVolume(v); }

void AudioEngine::pushControl(const ControlFrame& frame) { mixer_.pushControl(frame); }

void AudioEngine::pushEvent(SessionEvent event) { mixer_.pushEvent(event); }

void AudioEngine::loadAsset(AssetId id, const float* samples, size_t frames) {
  const size_t slot = static_cast<size_t>(id);
  if (slot >= assetStorage_.size() || samples == nullptr || frames == 0) return;
  // Stable, owning copy: the Mixer holds this pointer for as long as it is loaded.
  assetStorage_[slot].assign(samples, samples + frames);
  mixer_.loadAsset(id, assetStorage_[slot].data(), frames);
}

int32_t AudioEngine::takePendingError() {
  return pendingError_.exchange(0, std::memory_order_acquire);
}

AudioEngine::StreamInfo AudioEngine::streamInfo() const {
  StreamInfo info;
  info.sampleRate = actualSampleRate_;
  info.channelCount = actualChannelCount_;
  info.framesPerBurst = framesPerBurst_;
  info.performanceMode = static_cast<int32_t>(actualPerformanceMode_);
  info.sharingMode = static_cast<int32_t>(actualSharingMode_);
  info.format = static_cast<int32_t>(actualFormat_);
  info.error = static_cast<int32_t>(lastError_);
  info.running = running_.load(std::memory_order_acquire);
  return info;
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream*, void* audioData,
                                                   int32_t numFrames) {
  // No guard here on purpose. Every earlier version of this function gated rendering on
  // `running_` or on `stream == stream_.get()`, and both produced the same silent failure: the
  // stream stayed alive and reported started, no error was raised, and mixer.render() was never
  // called once. Lifecycle is already owned by close(), which stops the stream before closing it,
  // so a guard here only had a way to fail.
  callbacksFired_.fetch_add(1, std::memory_order_relaxed);
  auto* out = static_cast<float*>(audioData);
  mixer_.render(out, numFrames);
  return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorBeforeClose(oboe::AudioStream*, oboe::Result result) {
  lastError_ = result;
  pendingError_.store(static_cast<int32_t>(result), std::memory_order_release);
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream*, oboe::Result result) {
  lastError_ = result;
  pendingError_.store(static_cast<int32_t>(result), std::memory_order_release);
}

}  // namespace lightsaber