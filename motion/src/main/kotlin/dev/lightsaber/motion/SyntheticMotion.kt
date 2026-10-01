package dev.lightsaber.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Deterministic synthetic motions used by unit tests, the replay harness and the offline renderer.
 *
 * These stand in for the recordings a real watch will produce, so the DSP path can be exercised and
 * asserted on before any hardware exists. They are generated in the **watch** frame; the mounting
 * transform is applied by the processor under test, not baked in here.
 */
object SyntheticMotion {

    const val SAMPLE_RATE_HZ = 100.0

    /** Deterministic value noise so a "noisy stationary" fixture is reproducible across runs. */
    private class Rng(seed: Long) {
        private var s = seed
        fun next(): Float {
            s = s * 6364136223846793005L + 1442695040888963407L
            return ((s ushr 33).toInt() and 0xFFFFFF) / 8388608f - 1f
        }
    }

    /**
     * A wrist or palm rest with the hand completely still: only sensor noise plus gravity.
     * Nothing here should ever produce a swing accent.
     */
    fun stillness(durationS: Double, noiseRadPerSec: Float = 0.02f, seed: Long = 7L): List<SensorSample> {
        val rng = Rng(seed)
        val out = ArrayList<SensorSample>()
        val n = (durationS * SAMPLE_RATE_HZ).toInt()
        for (i in 0 until n) {
            val ts = i * (1e9 / SAMPLE_RATE_HZ).toLong()
            out += SensorSample(SensorKind.GYRO, ts, ts, 3, Vec3(rng.next() * noiseRadPerSec, rng.next() * noiseRadPerSec, rng.next() * noiseRadPerSec))
            out += SensorSample(SensorKind.ACCEL, ts, ts, 3, Vec3(rng.next() * 0.05f, rng.next() * 0.05f, 9.81f + rng.next() * 0.05f))
        }
        return out
    }

    /**
     * Swing about the blade axis' perpendiculars: angular velocity ramped from [fromRadPerSec] to
     * [toRadPerSec] over the duration, then held. Transverse rotation is what should drive the
     * main swing sound.
     */
    fun swingRamp(
        durationS: Double,
        fromRadPerSec: Float,
        toRadPerSec: Float,
        axis: Vec3 = Vec3(0f, 1f, 0f),
        noiseRadPerSec: Float = 0.02f,
        seed: Long = 11L,
    ): List<SensorSample> {
        val rng = Rng(seed)
        val out = ArrayList<SensorSample>()
        val n = (durationS * SAMPLE_RATE_HZ).toInt()
        val a = axis.normalized()
        for (i in 0 until n) {
            val f = i.toFloat() / maxOf(1, n - 1)
            val ts = i * (1e9 / SAMPLE_RATE_HZ).toLong()
            val w = fromRadPerSec + (toRadPerSec - fromRadPerSec) * f
            val v = a * w
            out += SensorSample(SensorKind.GYRO, ts, ts, 3, addNoise(v, rng, noiseRadPerSec))
            out += SensorSample(SensorKind.ACCEL, ts, ts, 3, Vec3(rng.next() * 0.05f, rng.next() * 0.05f, 9.81f + rng.next() * 0.05f))
        }
        return out
    }

    /**
     * Pure twist: rotation **only** about [axis]. With the blade axis passed in, swing intensity
     * must stay near zero while twist responds — a fast twist must not sound like a broad sweep.
     */
    fun twist(durationS: Double, rateRadPerSec: Float, axis: Vec3, noiseRadPerSec: Float = 0.02f, seed: Long = 13L): List<SensorSample> {
        val rng = Rng(seed)
        val out = ArrayList<SensorSample>()
        val n = (durationS * SAMPLE_RATE_HZ).toInt()
        val a = axis.normalized()
        for (i in 0 until n) {
            val ts = i * (1e9 / SAMPLE_RATE_HZ).toLong()
            out += SensorSample(SensorKind.GYRO, ts, ts, 3, addNoise(a * rateRadPerSec, rng, noiseRadPerSec))
            out += SensorSample(SensorKind.ACCEL, ts, ts, 3, Vec3(0f, 0f, 9.81f))
        }
        return out
    }

    /**
     * Translation with almost no rotation: zero angular velocity, a burst of linear acceleration.
     * Only the accent path should react.
     */
    fun translation(durationS: Double, accelMps2: Float, noiseRadPerSec: Float = 0.01f, seed: Long = 17L): List<SensorSample> {
        val rng = Rng(seed)
        val out = ArrayList<SensorSample>()
        val n = (durationS * SAMPLE_RATE_HZ).toInt()
        val attack = maxOf(1, n / 8)
        for (i in 0 until n) {
            val ts = i * (1e9 / SAMPLE_RATE_HZ).toLong()
            val env = if (i < attack) i.toFloat() / attack else
                if (i < n - attack) 1f else (n - i).toFloat() / attack
            out += SensorSample(
                SensorKind.GYRO, ts, ts, 3,
                addNoise(Vec3(rng.next() * noiseRadPerSec, rng.next() * noiseRadPerSec, rng.next() * noiseRadPerSec), rng, 0f),
            )
            out += SensorSample(
                SensorKind.ACCEL, ts, ts, 3,
                Vec3(0f, 0f, 9.81f) + Vec3(accelMps2 * env, 0f, 0f),
            )
        }
        return out
    }

    /**
     * A swing whose sensor delivery simply stops for [gapS] seconds and then resumes, with
     * timestamps that keep advancing across the hole. This is the real shape of a dropped-sensor
     * interval: time passes, no samples arrive.
     */
    fun swingWithGap(
        durationS: Double,
        gapStartS: Double,
        gapS: Double,
        maxRadPerSec: Float = 12f,
        axis: Vec3 = Vec3(0f, 1f, 0f),
    ): List<SensorSample> {
        val rng = Rng(31L)
        val out = ArrayList<SensorSample>()
        val n = (durationS * SAMPLE_RATE_HZ).toInt()
        val a = axis.normalized()
        val stepNs = (1e9 / SAMPLE_RATE_HZ).toLong()
        val gapStartNs = (gapStartS * 1e9).toLong()
        val gapEndNs = ((gapStartS + gapS) * 1e9).toLong()
        for (i in 0 until n) {
            val ts = i * stepNs
            if (ts >= gapStartNs && ts < gapEndNs) continue // delivery stops; the clock does not
            val env = 0.5f - 0.5f * kotlin.math.cos(2.0 * PI * i.toFloat() / n).toFloat()
            out += SensorSample(SensorKind.GYRO, ts, ts, 3, addNoise(a * (maxRadPerSec * env), rng, 0.02f))
            out += SensorSample(SensorKind.ACCEL, ts, ts, 3, Vec3(0f, 0f, 9.81f))
        }
        return out
    }

    /** Slow-to-fast sweep, the test the brief calls for when judging "smooth, no stepping". */
    fun slowToFastSweep(durationS: Double, maxRadPerSec: Float = 14f, axis: Vec3 = Vec3(0f, 1f, 0f)): List<SensorSample> {
        val rng = Rng(23L)
        val out = ArrayList<SensorSample>()
        val n = (durationS * SAMPLE_RATE_HZ).toInt()
        val a = axis.normalized()
        for (i in 0 until n) {
            val phase = i.toFloat() / n
            val env = 0.5f - 0.5f * kotlin.math.cos(2.0 * PI * phase).toFloat()
            val ts = i * (1e9 / SAMPLE_RATE_HZ).toLong()
            out += SensorSample(SensorKind.GYRO, ts, ts, 3, addNoise(a * (maxRadPerSec * env), rng, 0.02f))
            out += SensorSample(SensorKind.ACCEL, ts, ts, 3, Vec3(0f, 0f, 9.81f))
        }
        return out
    }

    /** Interpolates a gravity-sensor reading into a trace at the same timestamps. */
    fun withGravity(
        samples: List<SensorSample>,
        gravity: Vec3 = Vec3(0f, 0f, 9.81f),
    ): List<SensorSample> = samples.flatMap { s ->
        if (s.kind == SensorKind.ACCEL) {
            listOf(s, SensorSample(SensorKind.GRAVITY, s.sensorTimestampNs, s.arrivalTimestampNs, s.accuracy, gravity))
        } else {
            listOf(s)
        }
    }

    private fun addNoise(v: Vec3, rng: Rng, scale: Float): Vec3 =
        if (scale <= 0f) v else v + Vec3(rng.next() * scale, rng.next() * scale, rng.next() * scale)

    /** True when every finite, which a well-formed control trace must satisfy end to end. */
    fun framesAreSane(frames: List<MotionFeatures>): Boolean = frames.all {
        abs(it.swingIntensity) <= 1f && abs(it.twistSigned) <= 1f &&
            it.accelAccent in 0f..1f && abs(it.directionX) <= 1f && abs(it.directionY) <= 1f
    }

    /** Peak absolute swing intensity across a trace — the headline number for "is this responsive". */
    fun peakSwing(frames: List<MotionFeatures>): Float = frames.maxOfOrNull { it.swingIntensity } ?: 0f
}