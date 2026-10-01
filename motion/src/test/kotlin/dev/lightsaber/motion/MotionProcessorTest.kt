package dev.lightsaber.motion

import dev.lightsaber.motion.tools.Trace
import dev.lightsaber.motion.tools.Traces
import dev.lightsaber.motion.tools.replay
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Signal-processing tests for the motion pipeline. Every one of these is a claim the brief makes
 * explicitly, so a failure here is a failure of the product requirement, not of the implementation.
 */
class MotionProcessorTest {

    private val preset = MountPresets.PALM_RIGHT
    private val blade = preset.transform.bladeAxisWatch

    private fun transverseToBlade() = blade.cross(Vec3(0f, 0f, 1f)).normalized()

    // --- Pure twist must not read as a swing -----------------------------------------------

    @Test
    fun `pure twist produces low swing and a distinct twist response`() {
        val trace = Traces.twist(durationS = 2.0)
        val (frames, _) = replay(trace, preset)

        val swingPeak = SyntheticMotion.peakSwing(frames)
        val twistPeak = frames.maxOf { abs(it.twistSigned) }

        assertTrue(swingPeak < 0.05f, "twist leaked into swing intensity: peak $swingPeak (want < 0.05)")
        assertTrue(twistPeak > 0.30f, "twist response too weak: peak $twistPeak (want > 0.30)")
    }

    // --- Transverse rotation must drive the main swing sound ------------------------------

    @Test
    fun `transverse rotation increases swing intensity monotonically with speed`() {
        val config = MotionConfig(deadZoneRadPerSec = 0.05f)
        val axis = transverseToBlade()

        val slowPeak = SyntheticMotion.peakSwing(
            replay(Trace("swing-slow", SyntheticMotion.swingRamp(3.0, 0.2f, 3.5f, axis)), preset, config).first,
        )
        val fastPeak = SyntheticMotion.peakSwing(
            replay(Trace("swing-fast", SyntheticMotion.swingRamp(3.0, 0.5f, 20f, axis)), preset, config).first,
        )

        // 3.5 rad/s sits inside the normalisation range and 20 rad/s is well past full intensity,
        // so the two must be clearly distinguishable rather than both pinned at 1.0.
        assertTrue(slowPeak in 0.15f..0.80f, "a 3.5 rad/s swing mapped to $slowPeak, expected a mid value")
        assertTrue(fastPeak > 0.95f, "a 20 rad/s swing only reached $fastPeak, expected saturation")
        assertTrue(fastPeak > slowPeak + 0.3f, "fast ($fastPeak) is not clearly stronger than slow ($slowPeak)")
    }

    // --- Stationary must be hum only ------------------------------------------------------

    @Test
    fun `stationary noise never produces a swing or an accent`() {
        val (frames, processor) = replay(Traces.stillness(durationS = 4.0), preset)

        val swingPeak = SyntheticMotion.peakSwing(frames)
        val accentPeak = frames.maxOf { it.accelAccent }

        assertTrue(swingPeak < 0.02f, "stationary hand produced swing peaks of $swingPeak; hum must stay alone")
        assertTrue(accentPeak < 0.02f, "stationary hand produced accent peaks of $accentPeak")
        assertEquals(0L, processor.currentCounters().gapResets, "a clean trace should not report gaps")
    }

    // --- Coordinate transforms ------------------------------------------------------------

    @Test
    fun `every shipping mount preset is a proper right-handed rotation`() {
        MountPresets.ALL.forEach { preset ->
            assertEquals(null, MountPresets.validate(preset), "preset ${preset.id} is not a valid rotation")
        }
    }

    @Test
    fun `the same physical rotation reads as twist or swing depending on the mount`() {
        // Rotation about the watch's screen normal (+z). Under the palm preset the blade lies along
        // watch +y, so +z is transverse and this is a swing. Under the back-of-hand preset the blade
        // *is* the screen normal, so the identical rotation is a twist. A transform that was
        // transposed, or a preset applied to the wrong mount, would silently swap these.
        val rotationAboutScreenNormal = Vec3(0f, 0f, 8f)

        val inPalm = MountPresets.PALM_RIGHT.transform.rotationWatchToBlade * rotationAboutScreenNormal
        val onBackOfHand = MountPresets.BACK_OF_HAND.transform.rotationWatchToBlade * rotationAboutScreenNormal

        assertTrue(abs(inPalm.x) < 1e-4f, "PALM_RIGHT should read a screen-normal rotation as swing, got twist=${inPalm.x}")
        assertTrue(abs(inPalm.z) > 7.9f, "PALM_RIGHT lost the transverse magnitude: ${inPalm.z}")

        assertTrue(abs(onBackOfHand.x) > 7.9f, "BACK_OF_HAND should read a screen-normal rotation as twist, got ${onBackOfHand.x}")
        assertTrue(abs(onBackOfHand.z) < 1e-4f, "BACK_OF_HAND should have no transverse component, got ${onBackOfHand.z}")
    }

    @Test
    fun `a stationary hand reads the same under every shipping mount`() {
        // The mount changes which world motion is "swing", but it must never invent motion: a still
        // hand is still under all four presets.
        MountPresets.ALL.forEach { p ->
            val frames = replay(Traces.stillness(durationS = 2.0), p).first
            assertTrue(
                SyntheticMotion.peakSwing(frames) < 0.02f,
                "preset ${p.id} reported swing ${SyntheticMotion.peakSwing(frames)} on a stationary hand",
            )
        }
    }

    // --- Timestamp robustness --------------------------------------------------------------

    @Test
    fun `irregular timestamps do not destabilise filtering or spike derivatives`() {
        val regular = replay(Traces.sweep(), preset).first
        val jittered = replay(Traces.irregular(), preset).first

        assertTrue(SyntheticMotion.framesAreSane(jittered), "jittered timestamps produced out-of-range controls")

        val regularPeak = SyntheticMotion.peakSwing(regular)
        val jitteredPeak = SyntheticMotion.peakSwing(jittered)
        assertTrue(
            abs(regularPeak - jitteredPeak) < 0.15f,
            "delivery jitter moved peak swing from $regularPeak to $jitteredPeak",
        )
    }

    @Test
    fun `a data gap resets state instead of spiking`() {
        val (frames, processor) = replay(Traces.withGap(), preset)

        assertTrue(SyntheticMotion.framesAreSane(frames), "a gap produced out-of-range controls")
        assertTrue(processor.currentCounters().gapResets >= 1, "the gap was not detected")

        val afterGap = frames.drop(frames.size / 2)
        val spike = afterGap.maxOf { it.accelAccent }
        assertTrue(spike <= 1f, "post-gap accent out of range: $spike")
    }

    // --- Clamping --------------------------------------------------------------------------

    @Test
    fun `no trace ever emits NaN infinity or an out-of-range control`() {
        listOf("session", "stillness", "swing", "twist", "sweep", "irregular", "gap").forEach { name ->
            val trace = when (name) {
                "session" -> Traces.session(preset)
                "stillness" -> Traces.stillness()
                "swing" -> Traces.swing()
                "twist" -> Traces.twist()
                "sweep" -> Traces.sweep()
                "irregular" -> Traces.irregular()
                else -> Traces.withGap()
            }
            val frames = replay(trace, preset).first
            assertTrue(frames.isNotEmpty(), "trace $name produced no frames")
            assertTrue(SyntheticMotion.framesAreSane(frames), "trace $name emitted an invalid control frame")
        }
    }

    // --- Gravity compensation --------------------------------------------------------------

    @Test
    fun `gravity compensated translation ignores orientation change`() {
        val processor = MotionProcessor()
        processor.setProfile(calibratedProfile())
        processor.beginSession(1)

        val stepNs = 10_000_000L
        // The watch is slowly rolled about x while gravity rotates with it. Nothing is translating,
        // so nothing should be reported as linear acceleration.
        var swingSeen = 0f
        var accentSeen = 0f
        for (i in 0 until 400) {
            val ts = i * stepNs
            val roll = i.toFloat() / 400f * 1.2f
            val gravity = Vec3(0f, 9.81f * kotlin.math.sin(roll), 9.81f * kotlin.math.cos(roll))
            // No rotation at all: pure orientation change.
            processor.onGyroSample(ts, Vec3.ZERO)
            processor.onAccelSample(ts, gravity)
            processor.onGravitySample(ts, gravity)
            val frame = processor.onGyroSample(ts + 1, Vec3.ZERO)
            accentSeen = maxOf(accentSeen, frame.accelAccent)
            swingSeen = maxOf(swingSeen, frame.swingIntensity)
        }

        assertTrue(accentSeen < 0.05f, "orientation change produced a false translation accent of $accentSeen")
        assertTrue(swingSeen < 0.02f, "orientation change produced swing of $swingSeen with zero rotation")
    }

    @Test
    fun `translation without rotation reaches only the accent path`() {
        val trace = Trace("translation", SyntheticMotion.translation(1.5, 9f))
        val (frames, _) = replay(trace, preset)

        val accentPeak = frames.maxOf { it.accelAccent }
        val swingPeak = SyntheticMotion.peakSwing(frames)

        // No gravity sensor in this trace, so the accent is measured through the low-pass fallback,
        // which lags a fast translation and necessarily under-reports its peak. The assertion pins
        // "the accent path responds", not an exact level.
        assertTrue(accentPeak > 0.20f, "a 9 m/s2 translation produced an accent peak of only $accentPeak")
        assertTrue(swingPeak < 0.1f, "translation with no rotation should not read as swing, got $swingPeak")
    }

    @Test
    fun `the gravity sensor gives a sharper accent than the low-pass fallback`() {
        val raw = Trace("translation", SyntheticMotion.translation(1.5, 9f))
        val withGravity = Trace("translation-g", SyntheticMotion.withGravity(raw.samples))

        val fallbackPeak = replay(raw, preset).first.maxOf { it.accelAccent }
        val gravityPeak = replay(withGravity, preset).first.maxOf { it.accelAccent }

        assertTrue(gravityPeak > fallbackPeak, "gravity compensation ($gravityPeak) was no better than the fallback ($fallbackPeak)")
        assertTrue(gravityPeak > 0.3f, "with a gravity sensor the accent should reach a clear peak, got $gravityPeak")
    }

    // --- Calibration -----------------------------------------------------------------------

    @Test
    fun `stillness capture accepts a still hand and rejects a moving one`() {
        val still = StillnessCapture()
        repeat(200) { still.add(Vec3.ZERO, Vec3(0f, 0f, 9.81f)) }
        val ok = still.finish()
        assertTrue(ok is StillnessResult.Ok, "a perfectly still hand was rejected")

        val moving = StillnessCapture()
        repeat(200) { i ->
            val w = 0.5f * kotlin.math.sin(i * 0.4f)
            moving.add(Vec3(0f, w, 0f), Vec3(0f, 0f, 9.81f))
        }
        val rejected = moving.finish()
        assertTrue(rejected is StillnessResult.Rejected, "a clearly moving hand was accepted as still")
    }

    @Test
    fun `steady gyro bias is measured as bias not as noise`() {
        val capture = StillnessCapture()
        val bias = Vec3(0.02f, -0.03f, 0.01f)
        repeat(200) { i ->
            val jitter = ((i % 5) - 2) * 0.001f
            capture.add(bias + Vec3(jitter, jitter, jitter), Vec3(0f, 0f, 9.81f))
        }
        val result = capture.finish()
        assertTrue(result is StillnessResult.Ok, "a steady-bias sensor was rejected")
        result as StillnessResult.Ok
        assertTrue(approx(result.gyroBias.x, bias.x, 0.01f), "bias x was ${result.gyroBias}, expected ~${bias.x}")
        assertTrue(result.noiseRms < 0.01f, "steady bias was misreported as noise: ${result.noiseRms}")
    }

    @Test
    fun `the dead zone never drops below three times the calibrated noise`() {
        val config = MotionConfig(deadZoneRadPerSec = 0.05f)
        assertTrue(approx(config.deadZoneWithNoise(0.02f), 0.06f, 1e-4f), "noise floor was not applied")
        assertTrue(approx(config.deadZoneWithNoise(0.001f), 0.05f, 1e-4f), "configured dead zone was overridden")
        assertTrue(config.deadZoneWithNoise(Float.NaN) > 0f, "an uncalibrated profile must still yield a usable dead zone")
    }

    private fun calibratedProfile() = CalibrationProfile(
        presetId = preset.id,
        handedness = preset.transform.handedness,
        transform = preset.transform,
        gyroBias = Vec3.ZERO,
        noiseRms = 0.02f,
        // A correct calibration of a still hand yields a near-zero linear-accel offset: the mean raw
// reading is gravity, and only the residual after removing gravity is an offset.
linearAccelOffset = Vec3.ZERO,
        sampleCount = 200,
        createdAtEpochMs = 0L,
    )
}