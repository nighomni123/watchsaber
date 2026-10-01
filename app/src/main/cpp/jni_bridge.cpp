// JNI surface for the audio engine.
//
// Rules this file obeys so the real-time thread is never endangered:
//   * nothing here is called from the audio callback, only from the session thread
//   * control frames cross the boundary by value, with no allocation and no object handles
//   * counters are read through a flat snapshot rather than by calling back into C++ per field
extern "C" {
#include <jni.h>
}

#include <cstdint>

#include "audio_engine.h"
#include "control_frame.h"

using namespace lightsaber;

namespace {

// Mirrors Mixer::MixCounters as a flat array so one JNI call returns everything the diagnostics
// screen needs. Order must match EngineCounters.SIZE in the Kotlin layer.
constexpr jsize kCounterSlots = 9;

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_lightsaber_app_AudioEngine_nativeOpen(JNIEnv*, jobject, jint generation, jfloat volume,
                                             jint sampleRate, jint frameCount) {
  (void)volume;
  return AudioEngine::instance().open(MixerConfig{}, generation, sampleRate, frameCount)
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_lightsaber_app_AudioEngine_nativeClose(JNIEnv*, jobject) {
  AudioEngine::instance().close();
}

JNIEXPORT void JNICALL
Java_dev_lightsaber_app_AudioEngine_nativeReset(JNIEnv*, jobject) {
  AudioEngine::instance().resetMixer();
}

JNIEXPORT void JNICALL
Java_dev_lightsaber_app_AudioEngine_nativeSetVolume(JNIEnv*, jobject, jfloat volume) {
  AudioEngine::instance().setVolume(volume);
}

JNIEXPORT void JNICALL
Java_dev_lightsaber_app_AudioEngine_nativeLoadAsset(JNIEnv* env, jobject, jint assetId,
                                                   jfloatArray samples) {
  if (samples == nullptr) return;
  const jsize n = env->GetArrayLength(samples);
  if (n <= 0) return;
  jfloat* data = env->GetFloatArrayElements(samples, nullptr);
  if (data == nullptr) return;
  // Copied into native-owned storage before releasing, so the Mixer never points at JNI memory.
  AudioEngine::instance().loadAsset(static_cast<AssetId>(assetId), data, static_cast<size_t>(n));
  env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_dev_lightsaber_app_AudioEngine_nativePushControl(
    JNIEnv*, jobject, jlong timestampNs, jint generation, jfloat swing, jfloat twist, jfloat accent,
    jfloat dirX, jfloat dirY) {
  // Built on the stack and handed straight to the queue: no JNI object creation, no allocation.
  ControlFrame frame{};
  frame.timestampNs = static_cast<int64_t>(timestampNs);
  frame.sessionGeneration = static_cast<int32_t>(generation);
  frame.swingIntensity = static_cast<float>(swing);
  frame.twistSigned = static_cast<float>(twist);
  frame.accelAccent = static_cast<float>(accent);
  frame.directionX = static_cast<float>(dirX);
  frame.directionY = static_cast<float>(dirY);
  AudioEngine::instance().pushControl(frame);
}

JNIEXPORT void JNICALL
Java_dev_lightsaber_app_AudioEngine_nativePushEvent(JNIEnv*, jobject, jint eventOrdinal) {
  AudioEngine::instance().pushEvent(static_cast<SessionEvent>(eventOrdinal));
}

JNIEXPORT jlongArray JNICALL
Java_dev_lightsaber_app_AudioEngine_nativeCounters(JNIEnv* env, jobject) {
  MixCounters& c = AudioEngine::instance().counters();
  const jlong values[kCounterSlots] = {
      static_cast<jlong>(c.framesRendered.load()),
      static_cast<jlong>(c.controlFramesConsumed.load()),
      static_cast<jlong>(c.controlFramesDropped.load()),
      static_cast<jlong>(c.accentTriggers.load()),
      static_cast<jlong>(c.peakAbs.load()),
      static_cast<jlong>(c.clippedSamples.load()),
      static_cast<jlong>(AudioEngine::instance().streamInfo().sampleRate * 1000.0f),
      static_cast<jlong>(AudioEngine::instance().streamInfo().error),
      static_cast<jlong>(AudioEngine::instance().callbacksFired()),
  };
  jlongArray out = env->NewLongArray(kCounterSlots);
  if (out != nullptr) env->SetLongArrayRegion(out, 0, kCounterSlots, values);
  return out;
}

}  // extern "C"