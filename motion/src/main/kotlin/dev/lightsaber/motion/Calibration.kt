package dev.lightsaber.motion

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The blade frame, expressed relative to the watch frame.
 *
 * The watch's screen normal is deliberately **not** assumed to be the blade axis — a palm cradle
 * puts the screen anywhere from face-out to face-in and rotated, so the transform has to come from
 * a mounting preset that was verified against a real mount.
 *
 * Columns of [rotationWatchToBlade] are, expressed in watch coordinates:
 *  - column 0: the blade axis (`b`), the direction the imaginary blade extends from the grip
 *  - column 1: first transverse axis, perpendicular to the blade
 *  - column 2: second transverse axis, completing a right-handed frame
 *
 * In blade coordinates the blade axis is therefore exactly `Vec3(1, 0, 0)` and every twist/swing
 * split reduces to a component pick.
 */
data class MountTransform(
    val rotationWatchToBlade: Mat3,
    val handedness: Handedness = Handedness.RIGHT,
) {
    init {
        require(rotationWatchToBlade.isFinite()) { "mount transform contains non-finite elements" }
    }

    /** Unit blade axis in *watch* coordinates, for diagnostics and the guided-movement check. */
    val bladeAxisWatch: Vec3
        get() = Vec3(
            rotationWatchToBlade.m[0],
            rotationWatchToBlade.m[3],
            rotationWatchToBlade.m[6],
        ).normalized()

    companion object {
        /** No rotation: the watch frame is used as-is. Only valid for test fixtures. */
        val IDENTITY = MountTransform(Mat3.IDENTITY)
    }
}

enum class Handedness { LEFT, RIGHT }

/**
 * A mounting preset pairs a mechanically-defined transform with the orientation the user was told
 * to hold. First release ships a small number of verified presets rather than promising automatic
 * calibration for an arbitrary strap angle.
 */
data class MountPreset(
    val id: String,
    val label: String,
    val instruction: String,
    val transform: MountTransform,
)

/**
 * Everything calibration discovers, persisted per mount.
 *
 * [gyroBias] is the residual gyroscope bias estimated during the stillness capture — the same
 * subtraction the brief's transform equation calls `beta`. [noiseRms] is the stationary angular
 * velocity noise magnitude, used both for the stillness validity check and to size the dead zone.
 */
data class CalibrationProfile(
    val presetId: String,
    val handedness: Handedness,
    val transform: MountTransform,
    val gyroBias: Vec3,
    val noiseRms: Float,
    val linearAccelOffset: Vec3,
    val sampleCount: Int,
    val createdAtEpochMs: Long,
) {
    companion object {
        /**
         * A profile with no calibration at all. The app refuses to ignite with this one — an
         * uncalibrated frame would make swing and twist meaningless, which is worse than no sound.
         */
        fun uncalibrated() = CalibrationProfile(
            presetId = UNCALIBRATED,
            handedness = Handedness.RIGHT,
            transform = MountTransform.IDENTITY,
            gyroBias = Vec3.ZERO,
            noiseRms = Float.NaN,
            linearAccelOffset = Vec3.ZERO,
            sampleCount = 0,
            createdAtEpochMs = 0L,
        )

        const val UNCALIBRATED = "__uncalibrated__"
    }

    val isCalibrated: Boolean get() = presetId != UNCALIBRATED && sampleCount > 0
}

/** Result of the stillness-capture step. */
sealed interface StillnessResult {
    /** Motion variance stayed inside tolerance; carry these numbers into the profile. */
    data class Ok(val gyroBias: Vec3, val accelOffset: Vec3, val noiseRms: Float) : StillnessResult

    /** Reject the calibration: the hand was not still. */
    data class Rejected(val noiseRms: Float, val tolerance: Float) : StillnessResult
}

/**
 * Accumulates a 2-3 second stillness capture and produces the bias/noise estimate, or rejects it.
 *
 * Holds no Android types on purpose: the wizard is driven by recorded samples in unit tests exactly
 * as it is driven by live sensor events on the watch.
 */
class StillnessCapture(
    private val toleranceRadPerSec: Float = DEFAULT_TOLERANCE_RAD_PER_SEC,
    private val minSamples: Int = 120,
) {
    private var n = 0
    private var sumGyro = Vec3.ZERO
    private var sumGyroSq = 0f
    private var sumAccel = Vec3.ZERO

    fun reset() {
        n = 0
        sumGyro = Vec3.ZERO
        sumGyroSq = 0f
        sumAccel = Vec3.ZERO
    }

    val sampleCount: Int get() = n

    fun add(gyroRadPerSec: Vec3, accelMetersPerSec2: Vec3) {
        require(gyroRadPerSec.isFinite() && accelMetersPerSec2.isFinite()) {
            "calibration rejected a non-finite sample"
        }
        sumGyro = sumGyro + gyroRadPerSec
        sumGyroSq += gyroRadPerSec.dot(gyroRadPerSec)
        sumAccel = sumAccel + accelMetersPerSec2
        n++
    }

    /**
     * Closes the capture. Noise is measured as the RMS of the *residual after removing the mean*,
     * so a constant bias does not masquerade as noise and blow the tolerance.
     */
    fun finish(): StillnessResult {
        if (n < minSamples) return StillnessResult.Rejected(Float.NaN, toleranceRadPerSec)
        val meanGyro = sumGyro / n.toFloat()
        // E[x^2] - E[x]^2, summed over axes and averaged over samples.
        val variance = (sumGyroSq / n) - (meanGyro.dot(meanGyro))
        val rms = if (variance > 0f) sqrt(variance) else 0f
        return if (rms > toleranceRadPerSec) {
            StillnessResult.Rejected(rms, toleranceRadPerSec)
        } else {
            // The accelerometer offset is what is left once gravity is removed. Taking the mean
            // straight through would record ~9.81 m/s^2 as "offset" and break every later reading.
            val meanAccel = sumAccel / n.toFloat()
            val gravity = meanAccel.normalized() * STANDARD_GRAVITY_MPS2
            StillnessResult.Ok(meanGyro, meanAccel - gravity, rms)
        }
    }

    companion object {
        /**
         * Stationary hand noise on a wrist/palm-mounted watch is well under 0.05 rad/s; 0.15 rad/s
         * is roughly a 1.5 deg/frame jitter at 100 Hz and is a firm "hold still" line.
         */
        const val DEFAULT_TOLERANCE_RAD_PER_SEC = 0.15f

        const val STANDARD_GRAVITY_MPS2 = 9.81f
    }
}