package dev.lightsaber.motion.tools

import dev.lightsaber.motion.CalibrationProfile
import dev.lightsaber.motion.MotionConfig
import dev.lightsaber.motion.MotionCounters
import dev.lightsaber.motion.MotionFeatures
import dev.lightsaber.motion.MotionProcessor
import dev.lightsaber.motion.MountPreset
import dev.lightsaber.motion.MountPresets
import dev.lightsaber.motion.SensorKind
import dev.lightsaber.motion.SensorLog
import dev.lightsaber.motion.SensorSample
import dev.lightsaber.motion.StillnessCapture
import dev.lightsaber.motion.StillnessResult
import dev.lightsaber.motion.SyntheticMotion
import dev.lightsaber.motion.Vec3
import java.io.File

/**
 * A recorded motion trace plus how to replay it.
 */
data class Trace(val name: String, val samples: List<SensorSample>)

/**
 * The library's traces. Each is a hand-made stand-in for the recordings a real watch will produce.
 */
object Traces {

    /**
     * A full session in the order the brief describes: settle, ignite, idle hum, a slow-to-fast
     * sweep, a return to idle, a twist, a translation accent, then stillness again. This is the
     * trace the offline audio renderer consumes, so it exercises every layer in one pass.
     */
    fun session(preset: MountPreset): Trace {
        val blade = preset.transform.bladeAxisWatch
        // A transverse axis to the blade: swinging about it must read as swing, not twist.
        val transverse = transverseTo(blade)
        val parts = buildList {
            addAll(SyntheticMotion.stillness(1.0))
            addAll(SyntheticMotion.slowToFastSweep(2.5, 14f, transverse))
            addAll(SyntheticMotion.slowToFastSweep(2.5, 14f, transverse)) // return sweep
            addAll(SyntheticMotion.stillness(1.0))
            addAll(SyntheticMotion.twist(1.5, 6f, blade))
            addAll(SyntheticMotion.stillness(1.0))
            addAll(SyntheticMotion.translation(1.0, 9f))
            addAll(SyntheticMotion.stillness(1.5))
        }
        return Trace("session", restamp(parts))
    }

    fun stillness(durationS: Double = 3.0): Trace = Trace("stillness", SyntheticMotion.stillness(durationS))

    fun swing(durationS: Double = 3.0): Trace = Trace(
        "swing",
        SyntheticMotion.swingRamp(durationS, 0.5f, 12f, transverseTo(MountPresets.PALM_RIGHT.transform.bladeAxisWatch)),
    )

    fun twist(durationS: Double = 2.0): Trace = Trace(
        "twist",
        SyntheticMotion.twist(durationS, 6f, MountPresets.PALM_RIGHT.transform.bladeAxisWatch),
    )

    fun sweep(durationS: Double = 4.0): Trace = Trace(
        "sweep",
        SyntheticMotion.slowToFastSweep(durationS, 14f, transverseTo(MountPresets.PALM_RIGHT.transform.bladeAxisWatch)),
    )

    /**
     * Irregular delivery: correct motion, but timestamps jump around the way a real sensor
     * callback does. Filtering must be stable and derivatives must not spike.
     */
    fun irregular(durationS: Double = 3.0): Trace {
        val base = SyntheticMotion.slowToFastSweep(durationS, 12f, transverseTo(MountPresets.PALM_RIGHT.transform.bladeAxisWatch))
        var jitter = 0L
        return Trace("irregular", base.map { s ->
            jitter = ((jitter * 1103515245L + 12345L) and 0xFFFFL) - 32_768L
            s.copy(sensorTimestampNs = s.sensorTimestampNs + jitter)
        })
    }

    /** A half-second hole in the middle of a swing. Nothing may spike across it. */
    fun withGap(durationS: Double = 4.0, gapStartS: Double = 2.0, gapS: Double = 0.5): Trace = Trace(
        "gap",
        SyntheticMotion.swingWithGap(durationS, gapStartS, gapS, 12f, transverseTo(MountPresets.PALM_RIGHT.transform.bladeAxisWatch)),
    )

    private fun transverseTo(axis: Vec3): Vec3 = pickAnyPerpendicular(axis)

    private fun pickAnyPerpendicular(v: Vec3): Vec3 {
        val candidate = if (kotlin.math.abs(v.z) < 0.9f) Vec3(0f, 0f, 1f) else Vec3(1f, 0f, 0f)
        return v.cross(candidate).normalized()
    }

    /**
     * Re-stamps a concatenated trace so timestamps are monotonic and contiguous. The clock advances
     * on the **gyroscope** sample only: accelerometer and gravity readings belong to the same
     * instant as the gyro reading captured with them. Advancing per sample would silently halve
     * every measured rate.
     */
    private fun restamp(samples: List<SensorSample>): List<SensorSample> {
        val stepNs = (1e9 / SyntheticMotion.SAMPLE_RATE_HZ).toLong()
        var next = 0L
        var current = 0L
        return samples.map { s ->
            if (s.kind == SensorKind.GYRO) {
                current = next
                next += stepNs
            }
            s.copy(sensorTimestampNs = current, arrivalTimestampNs = current)
        }
    }
}

/**
 * Builds a profile the way the wizard does: a dedicated stillness capture, never taken from the
 * motion being measured. Calibrating on the trace itself would fold a twist or a swing into the
 * gyro bias and silently cancel exactly the motion the test is checking.
 */
fun calibrate(preset: MountPreset): CalibrationProfile {
    val capture = StillnessCapture()
    val still = SyntheticMotion.stillness(3.0, noiseRadPerSec = 0.02f, seed = 3L)
    for (s in still) {
        if (s.kind == SensorKind.GYRO) capture.add(s.values, Vec3(0f, 0f, 9.81f))
    }
    return when (val r = capture.finish()) {
        is StillnessResult.Ok -> CalibrationProfile(
            presetId = preset.id,
            handedness = preset.transform.handedness,
            transform = preset.transform,
            gyroBias = r.gyroBias,
            noiseRms = r.noiseRms,
            linearAccelOffset = r.accelOffset,
            sampleCount = capture.sampleCount,
            createdAtEpochMs = 0L,
        )
        is StillnessResult.Rejected -> error("synthetic calibration capture was rejected: rms=${r.noiseRms}")
    }
}

/** Runs a trace through the real processor and returns the control frames it emits. */
fun replay(trace: Trace, preset: MountPreset, config: MotionConfig = MotionConfig()): Pair<List<MotionFeatures>, MotionProcessor> {
    val processor = MotionProcessor(config)
    val profile = calibrate(preset)
    processor.setProfile(profile)
    processor.beginSession(1)

    val frames = ArrayList<MotionFeatures>()
    for (sample in trace.samples) {
        when (sample.kind) {
            SensorKind.GYRO -> frames += processor.onGyroSample(sample.sensorTimestampNs, sample.values)
            SensorKind.ACCEL -> processor.onAccelSample(sample.sensorTimestampNs, sample.values)
            SensorKind.GRAVITY -> processor.onGravitySample(sample.sensorTimestampNs, sample.values)
        }
    }
    return frames to processor
}

/**
 * Minimal JSON writer. The offline renderer parses this with a hand-rolled reader in C++, so the
 * format is frozen: one control frame per line, space separated, prefixed by `#` metadata lines.
 */
fun writeControlFrames(path: File, frames: List<MotionFeatures>, counters: MotionCounters, preset: MountPreset, extra: Map<String, String> = emptyMap()) {
    path.parentFile?.mkdirs()
    path.bufferedWriter().use { w ->
        w.write("# preset ${preset.id}\n")
        w.write("# rate ${counters.measuredRateHz}\n")
        w.write("# gaps ${counters.gapResets}\n")
        w.write("# staleAccelDrops ${counters.staleAccelDrops}\n")
        w.write("# saturation ${counters.saturationFlags}\n")
        for ((k, v) in extra) w.write("# $k $v\n")
        for (f in frames) {
            w.write("${f.timestampNs} ${f.sessionGeneration} ${f.swingIntensity} ${f.twistSigned} ${f.accelAccent} ${f.directionX} ${f.directionY}\n")
        }
    }
}

/**
 * Replay entry point.
 *
 * ```
 * ./gradlew :motion:run --args="replay <trace> <outFile> [presetId]"
 * ./gradlew :motion:run --args="write <trace> <outLog> [presetId]"
 * ```
 *
 * `trace` is one of session, stillness, swing, twist, sweep, irregular, gap.
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("usage: replay <trace> <outFile> [presetId] | write <trace> <outLog> [presetId]")
        println("traces: session, stillness, swing, twist, sweep, irregular, gap")
        return
    }
    val mode = args[0]
    val traceName = args.getOrNull(1) ?: error("missing trace name")
    val outPath = args.getOrNull(2) ?: error("missing output path")
    val preset = MountPresets.byId(args.getOrNull(3) ?: MountPresets.PALM_RIGHT.id) ?: MountPresets.PALM_RIGHT

    val trace = when (traceName) {
        "session" -> Traces.session(preset)
        "stillness" -> Traces.stillness()
        "swing" -> Traces.swing()
        "twist" -> Traces.twist()
        "sweep" -> Traces.sweep()
        "irregular" -> Traces.irregular()
        "gap" -> Traces.withGap()
        else -> error("unknown trace '$traceName'")
    }

    when (mode) {
        "write" -> {
            SensorLog.write(File(outPath), SensorLog.Header(note = traceName), trace.samples.asSequence())
            println("wrote ${trace.samples.size} samples to $outPath")
        }
        "replay" -> {
            val (frames, processor) = replay(trace, preset)
            val counters = processor.currentCounters()
            writeControlFrames(File(outPath), frames, counters, preset, mapOf("trace" to traceName))
            val peak = SyntheticMotion.peakSwing(frames)
            println(
                "trace=$traceName frames=${frames.size} peakSwing=%.3f meanSwing=%.3f rate=%.1fHz gaps=%d".format(
                    peak, frames.map { it.swingIntensity }.average().toFloat(), counters.measuredRateHz, counters.gapResets,
                ),
            )
        }
        else -> error("unknown mode '$mode'")
    }
}