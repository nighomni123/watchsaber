package dev.lightsaber.motion

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Tunables for the motion -> control pipeline. Defaults are the brief's starting points
 * (10-20 ms attack, 60-120 ms release); they are configuration, not sensor specifications, and are
 * meant to be tuned by listening against a replay recording.
 */
data class MotionConfig(
    /** Angular speed below which nothing happens. Also raised automatically by calibration noise. */
    val deadZoneRadPerSec: Float = 0.18f,
    /** Angular speed that maps to full swing intensity. */
    val fullIntensityRadPerSec: Float = 9.0f,
    /** Response curve s = u^gamma. gamma < 1 lifts gentle movement, > 1 emphasises hard swings. */
    val responseGamma: Float = 0.75f,
    /** Global trim on top of the dead zone, exposed as the UI sensitivity slider. */
    val sensitivityGain: Float = 1.0f,

    val twistDeadZoneRadPerSec: Float = 0.30f,
    val twistFullIntensityRadPerSec: Float = 7.0f,

    val attackTauS: Float = 0.015f,
    val releaseTauS: Float = 0.090f,
    val accentAttackTauS: Float = 0.005f,
    val accentReleaseTauS: Float = 0.060f,

    /** One-pole time constant for the fallback gravity estimate. */
    val gravityTauS: Float = 0.25f,
    /** An acceleration sample older than this is not combined with a fresh gyro sample. */
    val accelMaxAgeNs: Long = 60_000_000L,
    /** A gravity-sensor sample older than this falls back to the low-pass estimate. */
    val gravityMaxAgeNs: Long = 60_000_000L,

    /**
     * Time constant for the slow mean that tracks out constant linear-acceleration error. Must be
     * far longer than an accent and far shorter than a session, so a slow drift stays removed while
     * a 100 ms tap still registers.
     */
    val accelBiasTauS: Float = 2.0f,

    /** A gap longer than this resets derivative and filter state instead of integrating across it. */
    val gapResetS: Float = 0.25f,
    /** dt used for the first sample and after a reset, i.e. the nominal 100 Hz period. */
    val nominalSampleDtS: Float = 0.010f,

    val linearAccentThresholdMps2: Float = 1.2f,
    val linearAccentFullMps2: Float = 12.0f,
    val angularAccentThresholdRadPerSec2: Float = 25f,
    val angularAccentFullRadPerSec2: Float = 300f,

    /**
     * Fallback gyroscope full scale, used only until [MotionProcessor.setGyroscopeFullScale] supplies
     * the real value. 17.45 rad/s (±1000 dps) is what the SM-R870 reports; anything higher makes
     * saturation detection unreachable.
     */
    val gyroscopeMaxRadPerSec: Float = 17.45f,
) {
    /** Dead zone never sits below the calibrated noise floor — otherwise the hum itself is motion. */
    fun deadZoneWithNoise(noiseRms: Float): Float =
        if (noiseRms.isFinite()) max(deadZoneRadPerSec, noiseRms * 3f) else deadZoneRadPerSec
}

/**
 * One control frame. Field order and width match `ControlFrame` in
 * `engine/src/main/cpp/control_frame.h` byte for byte — the two structs are the contract between
 * the sensor thread and the real-time audio callback, and the sizes are asserted on both sides.
 *
 * @param timestampNs sensor-clock nanoseconds of the gyro sample that produced this frame
 * @param sessionGeneration bumped on every start; the audio side ignores frames from an older one
 */
data class MotionFeatures(
    val timestampNs: Long,
    val sessionGeneration: Int,
    val swingIntensity: Float,
    val twistSigned: Float,
    val accelAccent: Float,
    val directionX: Float,
    val directionY: Float,
)

/** Counters surfaced on the Diagnostics screen. Cheap to maintain, never touched by audio code. */
data class MotionCounters(
    val gyroSamples: Long = 0,
    val accelSamples: Long = 0,
    val gapResets: Long = 0,
    val staleAccelDrops: Long = 0,
    val saturationFlags: Long = 0,
    val largestGapNs: Long = 0,
    val measuredRateHz: Float = 0f,
)

/**
 * The motion pipeline. Deliberately free of Android types so a recorded trace replays through
 * exactly this code in a unit test.
 *
 * Gyroscope samples drive the output; the most recent sufficiently fresh acceleration sample is
 * used for accents. Nothing is ever integrated to a position or a velocity — an IMU drift would
 * make that claim false within seconds.
 */
class MotionProcessor(private val config: MotionConfig = MotionConfig()) {

    private var profile: CalibrationProfile = CalibrationProfile.uncalibrated()
    private var effectiveDeadZone: Float = config.deadZoneRadPerSec
    private var effectiveFullIntensity: Float = config.fullIntensityRadPerSec
    private var gyroscopeFullScale: Float = config.gyroscopeMaxRadPerSec
    private var effectiveTwistDeadZone: Float = config.twistDeadZoneRadPerSec

    private var lastGyroNs: Long = Long.MIN_VALUE
    private var lastAccelNs: Long = Long.MIN_VALUE
    private var lastAccelTsNs: Long = Long.MIN_VALUE
    private var lastGravityNs: Long = Long.MIN_VALUE
    private var accelRaw: Vec3 = Vec3.ZERO
    private var gravityLowpass: Vec3 = Vec3.ZERO
    private var gravityLowpassPrimed = false

    private var smoothedSwing = 0f
    private var smoothedTwist = 0f
    private var smoothedAccent = 0f
    private var prevSwingRadPerSec = 0f
    private var angularAccelFiltered = 0f
    private var linearAccelBias = Vec3.ZERO
    private var biasCoefficient = 0.0f
    private var lastFrame: MotionFeatures? = null

    private var sessionGeneration = 0
    private var gyroSamples = 0L
    private var accelSamples = 0L
    private var gapResets = 0L
    private var staleAccelDrops = 0L
    private var saturationFlags = 0L
    private var largestGapNs = 0L
    private var firstGyroNs = Long.MIN_VALUE
    private var lastMeasuredGyroNs = Long.MIN_VALUE

    fun setProfile(profile: CalibrationProfile) {
        this.profile = profile
        effectiveDeadZone = config.deadZoneWithNoise(profile.noiseRms)
        effectiveTwistDeadZone = max(config.twistDeadZoneRadPerSec, profile.noiseRms * 4f)
        val dt = config.nominalSampleDtS
        biasCoefficient = 1f - exp(-dt / max(config.accelBiasTauS, 0.05f))
        resetFilters()
    }

    fun currentProfile(): CalibrationProfile = profile

    fun currentCounters(): MotionCounters {
        val span = if (firstGyroNs != Long.MIN_VALUE && lastMeasuredGyroNs > firstGyroNs) {
            lastMeasuredGyroNs - firstGyroNs
        } else {
            0L
        }
        val rate = if (span > 0) (gyroSamples - 1) * 1e9f / span.toFloat() else 0f
        return MotionCounters(
            gyroSamples = gyroSamples,
            accelSamples = accelSamples,
            gapResets = gapResets,
            staleAccelDrops = staleAccelDrops,
            saturationFlags = saturationFlags,
            largestGapNs = largestGapNs,
            measuredRateHz = rate,
        )
    }

    /** Drops all filter and derivative state. Called on start, on stop, and after a data gap. */
    fun resetFilters() {
        smoothedSwing = 0f
        smoothedTwist = 0f
        smoothedAccent = 0f
        linearAccelBias = Vec3.ZERO
        prevSwingRadPerSec = 0f
        angularAccelFiltered = 0f
        gravityLowpassPrimed = false
        lastGyroNs = Long.MIN_VALUE
        lastAccelNs = Long.MIN_VALUE
        lastAccelTsNs = Long.MIN_VALUE
        lastGravityNs = Long.MIN_VALUE
    }

    fun beginSession(generation: Int) {
        sessionGeneration = generation
        resetFilters()
        // Counters are per session on purpose. Aggregating across the process lifetime made the
        // Diagnostics screen report a lifetime average — 25 Hz instead of the real ~100 Hz — because
        // most of that lifetime was spent with sensors unregistered.
        resetCounters()
    }

    /**
     * Gyroscope full scale, learned from the sensor rather than assumed.
     *
     * The SM-R870 reports `maximumRange = 17.453642 rad/s` (±1000 dps). A hardcoded 34.9 (±2000 dps)
     * made saturation detection unreachable — the threshold could never be hit — and left
     * [fullIntensityRadPerSec] at barely half the usable range, so a hard swing pinned the sensor
     * while still reading as full scale.
     */
    fun setGyroscopeFullScale(maxRadPerSec: Float) {
        if (maxRadPerSec.isFinite() && maxRadPerSec > 1f) {
            gyroscopeFullScale = maxRadPerSec
            effectiveFullIntensity = clampFullIntensity(maxRadPerSec)
        }
    }

    fun gyroscopeFullScale(): Float = gyroscopeFullScale

    /** Raw accelerometer sample, gravity included. Timestamped by the sensor, not by arrival. */
    fun onAccelSample(timestampNs: Long, accelMetersPerSec2: Vec3) {
        if (!accelMetersPerSec2.isFinite()) return
        lastAccelNs = timestampNs
        accelRaw = accelMetersPerSec2
        accelSamples++
        // Fallback gravity estimate. Only trusted when dynamic acceleration is small, because a
        // fast swing contaminates a low-passed accelerometer exactly when we most need it right.
        if (!gravityLowpassPrimed) {
            gravityLowpass = accelMetersPerSec2
            gravityLowpassPrimed = true
        } else {
            val dt = if (lastAccelTsNs != Long.MIN_VALUE && timestampNs > lastAccelTsNs) {
                (timestampNs - lastAccelTsNs) * 1e-9f
            } else {
                config.nominalSampleDtS
            }
            val a = 1f - exp(-dt / config.gravityTauS)
            gravityLowpass = gravityLowpass + (accelMetersPerSec2 - gravityLowpass) * a
        }
        lastAccelTsNs = timestampNs
    }

    /** Optional `TYPE_GRAVITY` sample in watch coordinates. Preferred over the low-pass fallback. */
    fun onGravitySample(timestampNs: Long, gravityMetersPerSec2: Vec3) {
        if (!gravityMetersPerSec2.isFinite()) return
        lastGravityNs = timestampNs
        // Seed the fallback so a later loss of the gravity sensor degrades rather than jumps.
        gravityLowpass = gravityMetersPerSec2
        gravityLowpassPrimed = true
    }

    /**
     * Consumes one gyroscope sample and returns the control frame for it. This is the only method
     * that emits audio-facing output.
     */
    fun onGyroSample(timestampNs: Long, omegaWatch: Vec3): MotionFeatures {
        require(omegaWatch.isFinite()) { "non-finite gyroscope sample" }

        val dt = deriveDt(timestampNs)
        if (dt <= 0f) {
            // Duplicate or out-of-order timestamp: re-emit the previous frame rather than
            // differentiating through zero time, which would divide by ~0.
            lastFrame?.let { return it.copy(timestampNs = timestampNs, sessionGeneration = sessionGeneration) }
            return MotionFeatures(timestampNs, sessionGeneration, 0f, 0f, 0f, 0f, 0f)
        }

        gyroSamples++
        if (firstGyroNs == Long.MIN_VALUE) firstGyroNs = timestampNs
        lastMeasuredGyroNs = timestampNs
        lastGyroNs = timestampNs

        // omega_blade = R_bw (omega_watch - beta)
        val omegaBlade = profile.transform.rotationWatchToBlade * (omegaWatch - profile.gyroBias)

        // Blade axis is +x in blade coordinates, so the twist/swing split is a component pick.
        val twistRad = omegaBlade.x
        val transverse = Vec3(0f, omegaBlade.y, omegaBlade.z)
        val swingRad = transverse.length()

        if (omegaWatch.length() >= gyroscopeFullScale * 0.95f) saturationFlags++

        // Angular acceleration, differentiated on measured time. Discarded across gaps (dt below
        // never reaches here with the previous sample still valid).
        val angularAccel = (swingRad - prevSwingRadPerSec) / dt
        prevSwingRadPerSec = swingRad
        angularAccelFiltered = onePole(
            angularAccelFiltered, angularAccel, dt,
            config.accentAttackTauS, config.accentReleaseTauS,
        )

        val uSwing = normalize(swingRad, effectiveDeadZone, effectiveFullIntensity) *
            config.sensitivityGain
        val shapedSwing = if (uSwing <= 0f) 0f else Math.pow(uSwing.toDouble(), config.responseGamma.toDouble()).toFloat()
        smoothedSwing = onePole(smoothedSwing, clamp01(shapedSwing), dt, config.attackTauS, config.releaseTauS)

        val uTwist = normalize(abs(twistRad), effectiveTwistDeadZone, config.twistFullIntensityRadPerSec) *
            config.sensitivityGain
        val shapedTwist = if (uTwist <= 0f) 0f else uTwist * uTwist
        // Signed so the mixer can tilt timbre in either direction, not just brighten.
        val targetTwist = clampSigned(shapedTwist) * if (twistRad < 0f) -1f else 1f
        smoothedTwist = onePoleSigned(smoothedTwist, targetTwist, dt, config.attackTauS, config.releaseTauS)

        // Accents: gravity-compensated translation plus sharp changes in swing speed.
        //
        // The accent basis is the *deviation from a slow running mean*, not the raw value. This
        // watch exposes both a Samsung and an AOSP linear-acceleration sensor, and a gravity
        // estimate that disagrees with the accelerometer leaves a constant offset — which reads as
        // perpetual light motion and fired 138 accents in one stationary session. A slow mean tracks
        // that bias away while leaving genuine transients intact.
        val linearAccel = currentLinearAccel(timestampNs)
        val accelBias = linearAccelBias + (linearAccel - linearAccelBias) * biasCoefficient
        val linearDeviation = linearAccel - accelBias
        val linearTerm = normalize(
            linearDeviation.length(),
            config.linearAccentThresholdMps2,
            config.linearAccentFullMps2,
        )
        val angularTerm = normalize(abs(angularAccelFiltered), config.angularAccentThresholdRadPerSec2, config.angularAccentFullRadPerSec2)
        val accentTarget = clamp01(linearTerm * 0.6f + angularTerm * 0.4f)
        smoothedAccent = onePole(smoothedAccent, accentTarget, dt, config.accentAttackTauS, config.accentReleaseTauS)

        val dirScale = 1f / max(effectiveFullIntensity, 1e-3f)
        val frame = MotionFeatures(
            timestampNs = timestampNs,
            sessionGeneration = sessionGeneration,
            swingIntensity = clamp01(smoothedSwing),
            twistSigned = clampSigned(smoothedTwist),
            accelAccent = clamp01(smoothedAccent),
            directionX = clampSigned(transverse.y * dirScale),
            directionY = clampSigned(transverse.z * dirScale),
        )
        lastFrame = frame
        return frame
    }

    /**
     * Gravity-compensated acceleration, or [Vec3.ZERO] when the newest sample is too old to trust.
     * Combining a fresh gyro reading with an arbitrarily old accelerometer reading is the classic
     * way to invent motion that never happened.
     */
    private fun currentLinearAccel(nowNs: Long): Vec3 {
        if (lastAccelNs == Long.MIN_VALUE || nowNs - lastAccelNs > config.accelMaxAgeNs) {
            if (lastAccelNs != Long.MIN_VALUE) staleAccelDrops++
            return Vec3.ZERO
        }
        // gravityLowpass holds the best available gravity estimate: the gravity sensor's reading
        // when one is fresh, otherwise the low-passed accelerometer fallback.
        val linear = accelRaw - gravityLowpass
        // The platform's linear-acceleration offset belongs to the *linear* signal, not to the
        // gravity estimate. Subtracting it from the gravity term would cancel gravity twice and
        // report a hand at rest as accelerating at 1 g.
        return linear - profile.linearAccelOffset
    }

    /**
     * dt from sensor timestamps. Returns 0 for a duplicate timestamp (caller re-emits) and the
     * nominal period after a gap, having reset the filters so no derivative spans the hole.
     */
    private fun deriveDt(timestampNs: Long): Float {
        if (lastGyroNs == Long.MIN_VALUE) return config.nominalSampleDtS
        val deltaNs = timestampNs - lastGyroNs
        if (deltaNs <= 0L) return 0f
        if (deltaNs > largestGapNs) largestGapNs = deltaNs
        if (deltaNs > (config.gapResetS * 1e9).toLong()) {
            gapResets++
            prevSwingRadPerSec = 0f
            angularAccelFiltered = 0f
            smoothedSwing = 0f
            smoothedTwist = 0f
            smoothedAccent = 0f
            return config.nominalSampleDtS
        }
        return deltaNs * 1e-9f
    }

    /** Zeroes the per-session counters so a reported rate describes the live session only. */
    fun resetCounters() {
        gyroSamples = 0L
        accelSamples = 0L
        gapResets = 0L
        staleAccelDrops = 0L
        saturationFlags = 0L
        largestGapNs = 0L
        firstGyroNs = Long.MIN_VALUE
        lastMeasuredGyroNs = Long.MIN_VALUE
    }

    /**
     * Full intensity is a fraction of full scale, not a constant: too high and a normal swing
     * saturates; too low and the response is compressed into the top of the range.
     */
    private fun clampFullIntensity(fullScale: Float): Float {
        val target = fullScale * 0.55f
        return target.coerceIn(1f, config.fullIntensityRadPerSec.coerceAtLeast(1f))
    }

    private companion object {
        fun clamp01(v: Float) = if (v.isFinite()) v.coerceIn(0f, 1f) else 0f

        fun clampSigned(v: Float) = if (v.isFinite()) v.coerceIn(-1f, 1f) else 0f

        fun normalize(value: Float, lo: Float, hi: Float): Float {
            if (!value.isFinite() || hi <= lo) return 0f
            return clamp01((value - lo) / (hi - lo))
        }

        /**
         * Elapsed-time-aware one-pole: alpha = 1 - exp(-dt/tau). Using this instead of a fixed
         * per-sample alpha is what keeps the response identical at 100 Hz and 200 Hz.
         */
        fun onePole(previous: Float, target: Float, dt: Float, attackTau: Float, releaseTau: Float): Float {
            if (dt <= 0f) return previous
            val tau = if (target > previous) attackTau else releaseTau
            if (tau <= 0f) return target
            val alpha = 1f - exp(-dt / tau)
            return previous + alpha * (target - previous)
        }

        fun onePoleSigned(previous: Float, target: Float, dt: Float, attackTau: Float, releaseTau: Float): Float =
            onePole(previous, target, dt, attackTau, releaseTau)
    }
}