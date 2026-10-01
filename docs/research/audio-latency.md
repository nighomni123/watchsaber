Effective in 2026, to align with our trunk stable development model and ensure platform stability for the ecosystem, we will publish source code to AOSP in Q2 and Q4. For building and contributing to AOSP, use `android-latest-release`. The `android-latest-release` manifest branch will always reference the most recent release pushed to AOSP. For more information, see Changes to AOSP.

# Audio latency for app developers Stay organized with collections Save and categorize content based on your preferences.

- On this page
- Oboe
- Implementation checklist
 - android-ndk
 - Report bugs on Oboe
 - Videos

For the lowest possible audio latency, we recommend you use Oboe.

## Oboe

Oboe uses native audio and is built on AAudio for Android 8.1 (API level 27) and higher and OpenSL ES. To achieve the lowest possible latency for a device, set `setPerformanceMode(oboe::PerformanceMode::LowLatency)` and `setSharingMode(oboe::SharingMode::Exclusive)`. For more information about Oboe, see the Getting started guide, `README.md`, and Full guide. See the Soundboard app for a sample app using Oboe.

If you use `AudioTrack` to create output audio streams in Java for latency-sensitive apps, use the `PERFORMANCE_MODE_LOW_LATENCY` mode.

## Implementation checklist

To use Android native audio:

1. Download and install the Android NDK.
2. Follow the Getting started guide for Oboe.
3. Call `setPerformanceMode(oboe::PerformanceMode::LowLatency)` and `setSharingMode(oboe::SharingMode::Exclusive)` on the Oboe builder.
4. Use the recommended native buffer size and sample rate returned by `android.media.AudioManager.getProperty(java.lang.String)`.

5. Keep your callback handlers short, without bursty CPU usage or unbounded blocking. Avoid priority inversion.

6. Consider using non-blocking algorithms to communicate between input and output callback handlers, and between the callback handlers and the rest of your application.

### android-ndk

If you have questions about how to use Android native audio, join the discussion group android-ndk.

### Report bugs on Oboe

If you have issues using Oboe, file a bug.

### Videos

- Getting started with Oboe
- Low Latency Audio - Because Your Ears Are Worth It (Android Dev Summit 2018)
- Winning on Android - How to optimize an Android audio app (Android Developer Challenge 2018)
- High Performance Audio on Android (Google I/O 2013)
- Building great multimedia experiences on Android (Google I/O 2014)
- Audio latency: buffer sizes (100 Days of Google Dev)

Was this helpful?

Content and code samples on this page are subject to the licenses described in the Content License. Java and OpenJDK are trademarks or registered trademarks of Oracle and/or its affiliates.

Last updated 2026-07-31 UTC.