package dev.lightsaber.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import dev.lightsaber.motion.StillnessCapture
import dev.lightsaber.motion.StillnessResult
import dev.lightsaber.motion.Vec3
import kotlin.math.sqrt

/**
 * A real stillness capture, running on the watch's own sensors.
 *
 * This deliberately does **not** reuse [SensorSource]: calibration is a separate user action that
 * happens before a session exists, and it has to feed *measured* samples into [StillnessCapture].
 * The first implementation polled a loop and pushed `Vec3.ZERO`, which both measured nothing and
 * could never satisfy StillnessCapture's minimum sample count — so it rejected every attempt no
 * matter how still the hand was.
 *
 * Progress is reported live. "Too much movement" is only actionable if the user can see the number
 * moving toward (and past) the limit.
 */
class CalibrationCapture(
    private val context: Context,
    private val onProgress: (samples: Int, liveNoiseRadPerSec: Float) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var capture = StillnessCapture()
    private var latestAccel = Vec3(0f, 0f, 9.81f)
    private var accelSum = Vec3.ZERO

    // Running statistics, used only for the live readout. StillnessCapture keeps its own copy.
    private var gyroSum = Vec3.ZERO
    private var gyroSqSum = 0f
    private var count = 0

    val sampleCount: Int get() = count

    fun start(periodUs: Int = SensorSource.SAMPLING_PERIOD_US): Boolean {
        if (accel == null) return false
        capture = StillnessCapture()
        latestAccel = Vec3(0f, 0f, 9.81f)
        accelSum = Vec3.ZERO
        gyroSum = Vec3.ZERO
        gyroSqSum = 0f
        count = 0

        thread = HandlerThread("lightsaber-calibrate").also { it.start() }
        handler = Handler(thread!!.looper)
        // The accelerometer is registered first so a gyro sample always has a current accel
        // partner rather than the initial placeholder.
        sensorManager.registerListener(this, accel, periodUs, 0, handler)
        if (gyro != null) sensorManager.registerListener(this, gyro, periodUs, 0, handler)
        return true
    }

    /** Stops acquisition and returns the result, or null if nothing was captured. */
    fun stop(): StillnessResult? {
        sensorManager.unregisterListener(this)
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        return if (count == 0) null else capture.finish()
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Copy immediately: the framework reuses the event object and its array.
        val v = Vec3(event.values[0], event.values[1], event.values[2])
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                latestAccel = v
                accelSum = accelSum + v
            }
            Sensor.TYPE_GYROSCOPE -> {
                capture.add(v, latestAccel)
                gyroSum = gyroSum + v
                gyroSqSum += v.dot(v)
                count = capture.sampleCount
                onProgress(count, liveNoise())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** RMS of the residual around the running mean. A constant bias must not read as noise. */
    fun liveNoise(): Float {
        if (count < 2) return 0f
        val n = count.toFloat()
        val mean = gyroSum / n
        val variance = (gyroSqSum / n) - mean.dot(mean)
        return if (variance > 0f) sqrt(variance) else 0f
    }

    fun liveBias(): Vec3 =
        if (count == 0) Vec3.ZERO else gyroSum / count.toFloat()

    fun meanAccel(): Vec3 =
        if (count == 0) Vec3.ZERO else accelSum / count.toFloat()
}