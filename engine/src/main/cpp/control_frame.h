// The contract between the sensor thread and the real-time audio callback.
//
// This struct is written by Kotlin (see MotionFeatures in the :motion module) and read by the Oboe
// callback in C++. Every field, its order and its width are frozen: changing any of them silently
// reinterprets the control signal as noise. The static_assert below fails the build if a compiler
// ever lays the struct out differently.
#pragma once

#include <cstdint>

namespace lightsaber {

// Mirror of dev.lightsaber.motion.MotionFeatures.
// alignas(8) is load-bearing on 32-bit ABIs: there int64_t only aligns to 4, so without it the
// struct would have a different alignment per ABI. The size is 32 either way (the fields pack with
// no padding), but an explicit alignment keeps the layout identical everywhere.
struct alignas(8) ControlFrame {
  // Sensor-clock nanoseconds of the gyro sample that produced this frame.
  int64_t timestampNs;
  // Bumped on every Ignite. The mixer ignores frames from an older session so a Stop can never be
  // followed by a stale motion frame resurrecting the sound.
  int32_t sessionGeneration;
  // 0..1 normalised, smoothed transverse swing intensity.
  float swingIntensity;
  // -1..1 signed twist, so timbre can tilt either way rather than only brightening.
  float twistSigned;
  // 0..1 short emphasis for sharp translational or angular changes.
  float accelAccent;
  // -1..1 transverse direction, used for a subtle stereo/level tilt. Never presented as Doppler.
  float directionX;
  float directionY;
};

static_assert(sizeof(ControlFrame) == 32, "ControlFrame must stay 32 bytes to match Kotlin");
static_assert(alignof(ControlFrame) == 8, "ControlFrame alignment drifted from the Kotlin side");

}  // namespace lightsaber