package dev.lightsaber.app

import dev.lightsaber.motion.MotionFeatures

/**
 * Thin Kotlin side of the JNI bridge.
 *
 * Every control frame crosses into native code **by value**: the frame is unboxed into a C++
 * stack struct and pushed onto a lock-free queue. No object handles, no allocation, nothing the
 * audio callback could ever be blocked on.
 */
class AudioEngine {

    companion object {
        init {
            System.loadLibrary("lightsaber_engine")
        }

        /** Mirrors SessionEvent in control_queue.h. */
        const val EVENT_IGNITE = 1
        const val EVENT_RETRACT = 2
        const val EVENT_STOP_IMMEDIATE = 3
    }

    private external fun nativeOpen(
        generation: Int,
        volume: Float,
        sampleRate: Int,
        frameCount: Int,
    ): Boolean
    private external fun nativeClose()
    private external fun nativeReset()
    private external fun nativeSetVolume(volume: Float)
    private external fun nativePushControl(
        timestampNs: Long,
        generation: Int,
        swing: Float,
        twist: Float,
        accent: Float,
        dirX: Float,
        dirY: Float,
    )
    private external fun nativePushEvent(eventOrdinal: Int)
    private external fun nativeCounters(): LongArray
    private external fun nativeLoadAsset(assetId: Int, samples: FloatArray)

    @Volatile
    var isOpen: Boolean = false
        private set

    fun open(
        generation: Int,
        volume: Float = 0.8f,
        sampleRate: Int = 0,
        frameCount: Int = 0,
    ): Boolean {
        val ok = nativeOpen(generation, volume, sampleRate, frameCount)
        isOpen = ok
        return ok
    }

    fun close() {
        nativeClose()
        isOpen = false
    }

    fun reset() = nativeReset()

    fun setVolume(volume: Float) = nativeSetVolume(volume.coerceIn(0f, 1f))

    fun push(features: MotionFeatures) {
        nativePushControl(
            features.timestampNs,
            features.sessionGeneration,
            features.swingIntensity,
            features.twistSigned,
            features.accelAccent,
            features.directionX,
            features.directionY,
        )
    }

    fun pushEvent(ordinal: Int) = nativePushEvent(ordinal)

    /**
     * Copies an asset into native-owned memory and points the mixer at it.
     *
     * The copy is what makes this safe: the Mixer stores a raw pointer, so the FloatArray handed
     * over from Kotlin must not be the one it keeps. Native owns the buffer for the process
     * lifetime, which also keeps it valid across stream close/reopen without re-decoding.
     */
    fun loadNativeAsset(assetId: Int, samples: FloatArray) {
        if (samples.isEmpty()) return
        nativeLoadAsset(assetId, samples)
    }

    /** Flat snapshot so diagnostics cost one JNI crossing rather than eight. */
    fun counters(): EngineCounters {
        val v = nativeCounters()
        return EngineCounters(
            framesRendered = v[0],
            controlFramesConsumed = v[1],
            controlFramesDropped = v[2],
            accentTriggers = v[3],
            peakAbsMilli = v[4],
            clippedSamples = v[5],
            sampleRateMilli = v[6],
            errorCode = v[7],
            callbacksFired = v[8],
        )
    }
}

data class EngineCounters(
    val framesRendered: Long,
    val controlFramesConsumed: Long,
    val controlFramesDropped: Long,
    val accentTriggers: Long,
    val peakAbsMilli: Long,
    val clippedSamples: Long,
    val sampleRateMilli: Long,
    val errorCode: Long,
    val callbacksFired: Long = 0,
) {
    val sampleRateHz: Float get() = sampleRateMilli / 1000f
    val peakAbs: Float get() = peakAbsMilli / 1000f
}