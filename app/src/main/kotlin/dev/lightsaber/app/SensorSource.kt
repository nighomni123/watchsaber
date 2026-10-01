package dev.lightsaber.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import dev.lightsaber.motion.MotionProcessor
import dev.lightsaber.motion.SensorKind
import dev.lightsaber.motion.Vec3
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the watch can actually do, decided by asking rather than by assuming.
 *
 * The explicit Full / Reduced / Unavailable outcomes matter: a watch with no gyroscope can still
 * make accent sounds, and telling the user "unavailable" when "reduced" would work is a worse
 * product than saying so plainly.
 */
enum class CapabilityMode { FULL, REDUCED, UNAVAILABLE }

data class SensorDescriptor(
    val name: String,
    val vendor: String,
    val type: Int,
    val resolution: Float,
    val maximumRange: Float,
    val minDelayUs: Int,
    val maxDelayUs: Int,
    val isWakeUp: Boolean,
) {
    /** The rate the hardware will actually deliver, independent of what we ask for. */
    val maxRateHz: Float get() = if (minDelayUs > 0) 1_000_000f / minDelayUs else 0f
}

data class AudioRouteOption(
    val deviceId: Int,
    val type: Int,
    val label: String,
) {
    val isBuiltInSpeaker: Boolean get() = type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    val isBluetooth: Boolean
        get() = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
            type == AudioDeviceInfo.TYPE_BLE_BROADCAST
}

data class Capabilities(
    val mode: CapabilityMode,
    val gyroscope: SensorDescriptor?,
    val accelerometer: SensorDescriptor?,
    val gravity: SensorDescriptor?,
    val routes: List<AudioRouteOption>,
    val notes: List<String> = emptyList(),
)

object CapabilityChecker {

    /**
     * Enumerates everything rather than assuming. Note the deliberate distinction between the
     * hardware gyro/accelerometer and derived sensors: on the SM-R870 the linear-acceleration and
     * rotation-vector sensors are Samsung's own implementations, while gravity is an AOSP-derived
     * sensor — so their latency is not interchangeable and must be benchmarked separately.
     */
    fun probe(context: Context): Capabilities {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val notes = ArrayList<String>()

        fun describe(sensor: Sensor?): SensorDescriptor? = sensor?.let {
            SensorDescriptor(
                name = it.name ?: "unknown",
                vendor = it.vendor ?: "?",
                type = it.type,
                resolution = it.resolution,
                maximumRange = it.maximumRange,
                minDelayUs = it.minDelay,
                maxDelayUs = it.maxDelay,
                isWakeUp = it.isWakeUpSensor,
            )
        }

        val gyro = describe(sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE))
        val accel = describe(sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER))
        val gravity = describe(sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY))

        gyro?.let {
            notes += "gyro ${it.vendor} ${it.name} max ${"%.1f".format(it.maxRateHz)} Hz, " +
                "range ${it.maximumRange} rad/s, resolution ${it.resolution}"
            if (it.maxRateHz < 199f) {
                notes += "hardware gyro caps at ${"%.0f".format(it.maxRateHz)} Hz; 200 Hz is not " +
                    "reachable on this watch regardless of permissions"
            }
            if (!it.isWakeUp) notes += "gyro is non-wake-up: screen-off delivery is not guaranteed"
        }
        gravity?.let { notes += "gravity sensor is derived (${it.vendor} v${it.type}); its latency must be benchmarked, not assumed" }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val routes = ArrayList<AudioRouteOption>()
        for (device in audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_BLE_BROADCAST,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                -> routes += AudioRouteOption(device.id, device.type, device.productName?.toString() ?: "output ${device.type}")
            }
        }
        notes += "audio outputs: ${routes.joinToString { "${it.label}(${it.type})" }.ifEmpty { "none" }}"

        val mode = when {
            gyro != null && accel != null -> CapabilityMode.FULL
            accel != null -> CapabilityMode.REDUCED
            else -> CapabilityMode.UNAVAILABLE
        }
        if (routes.isEmpty()) {
            notes += "no usable audio output found"
        }
        if (mode == CapabilityMode.FULL && routes.none { it.isBuiltInSpeaker }) {
            notes += "no built-in speaker: latency targets only measurable on a connected output"
        }
        return Capabilities(mode, gyro, accel, gravity, routes, notes)
    }
}

/**
 * Sensor acquisition on a dedicated [HandlerThread].
 *
 * Contract enforced here:
 *  * elapsed time comes from [SensorEvent.timestamp], never from callback arrival
 *  * values are copied immediately — the framework reuses the event object
 *  * callbacks do nothing but copy and hand off; no I/O, no UI, no allocation-heavy work
 *  * registration is released on every exit path
 */
class SensorSource(
    private val context: Context,
    private val processor: MotionProcessor,
    private val onFrame: (dev.lightsaber.motion.MotionFeatures) -> Unit,
) : SensorEventListener {

    companion object {
        /**
         * 10,000 us = 100 Hz, with zero report latency so the platform does not batch.
         *
         * On the SM_R870 the gyroscope's own maximum rate is 100 Hz, so this request is already at
         * the hardware ceiling; 200 Hz would be refused by the sensor, not by a permission.
         */
        const val SAMPLING_PERIOD_US = 10_000
        const val REPORT_LATENCY_US = 0
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

    @Volatile
    var isRunning: Boolean = false
        private set

    val listeners = CopyOnWriteArrayList<(String, Long) -> Unit>()

    fun start(): Boolean {
        if (isRunning) return true
        if (accel == null) return false
        // Feed the real full-scale range to the processor. Assuming ±2000 dps on a watch that
        // reports ±1000 dps makes saturation detection unreachable and leaves the normalisation
        // range at half the sensor's actual span.
        gyro?.let {
            processor.setGyroscopeFullScale(it.maximumRange)
        }
        thread = HandlerThread("lightsaber-sensors").also { it.start() }
        handler = Handler(thread!!.looper)

        val registered = sensorManager.registerListener(
            this,
            accel,
            SAMPLING_PERIOD_US,
            REPORT_LATENCY_US,
            handler,
        )
        if (gyro != null) {
            sensorManager.registerListener(this, gyro, SAMPLING_PERIOD_US, REPORT_LATENCY_US, handler)
        }
        if (gravity != null) {
            // Gravity is optional by design: it improves accents but the pipeline has a low-pass
            // fallback and must not depend on a derived sensor being present.
            sensorManager.registerListener(this, gravity, SAMPLING_PERIOD_US, REPORT_LATENCY_US, handler)
        }
        isRunning = true
        return registered
    }

    fun stop() {
        if (!isRunning) return
        sensorManager.unregisterListener(this)
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        isRunning = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Copy immediately: the SensorEvent and its values array are reused by the framework.
        val values = Vec3(event.values[0], event.values[1], event.values[2])
        val ts = event.timestamp
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val frame = processor.onGyroSample(ts, values)
                onFrame(frame)
                listeners.forEach { it("gyro", ts) }
            }
            Sensor.TYPE_ACCELEROMETER -> processor.onAccelSample(ts, values)
            Sensor.TYPE_GRAVITY -> processor.onGravitySample(ts, values)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        listeners.forEach { it("accuracy:${sensor?.type}:$accuracy", SystemClock.elapsedRealtimeNanos()) }
    }

    /** Used by the calibration wizard to accumulate a stillness capture. */
    fun feedCalibration(sample: dev.lightsaber.motion.SensorSample) {
        when (sample.kind) {
            SensorKind.GYRO -> processor.onGyroSample(sample.sensorTimestampNs, sample.values)
            SensorKind.ACCEL -> processor.onAccelSample(sample.sensorTimestampNs, sample.values)
            SensorKind.GRAVITY -> processor.onGravitySample(sample.sensorTimestampNs, sample.values)
        }
    }
}