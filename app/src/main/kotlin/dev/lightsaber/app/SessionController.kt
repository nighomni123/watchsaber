package dev.lightsaber.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import dev.lightsaber.motion.CalibrationProfile
import dev.lightsaber.motion.MotionConfig
import dev.lightsaber.motion.MotionProcessor
import java.util.concurrent.atomic.AtomicInteger

/**
 * The single owner of start, stop, interruption and cleanup.
 *
 * OFF -> PREPARING -> IGNITING -> ACTIVE -> RETRACTING -> OFF, with any active state able to fall
 * into INTERRUPTED or ERROR. Every transition is idempotent: Ignite twice, or Stop twice, or a Stop
 * racing an Ignite, all converge on the same state without leaking a sensor registration or an
 * audio focus request.
 */
enum class SessionState { OFF, PREPARING, IGNITING, ACTIVE, RETRACTING, INTERRUPTED, ERROR }

class SessionController(
    private val context: Context,
    private val engine: AudioEngine,
    private val onState: (SessionState) -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val processor = MotionProcessor(MotionConfig())
    private var sensorSource: SensorSource? = null

    private val generation = AtomicInteger(0)

    var state: SessionState = SessionState.OFF
        private set

    var profile: CalibrationProfile = CalibrationProfile.uncalibrated()
        private set

    var lastError: String? = null
        private set

    private var focusRequest: AudioFocusRequest? = null
    private var pendingRetraction = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss: silence immediately and stop. Restarting is an explicit user act.
                Log.w(TAG, "audio focus lost permanently; stopping session")
                stop(playRetraction = false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.w(TAG, "audio focus lost transiently; pausing")
                stop(playRetraction = false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                engine.setVolume(0.25f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (!pendingRetraction) engine.setVolume(0.8f)
            }
        }
    }

    fun motionProcessor(): MotionProcessor = processor

    fun setProfile(profile: CalibrationProfile) {
        this.profile = profile
        processor.setProfile(profile)
    }

    // --- Lifecycle transitions ---------------------------------------------------------

    fun ignite(volume: Float = 0.8f): Boolean {
        if (state == SessionState.ACTIVE || state == SessionState.IGNITING) return true  // idempotent
        if (!profile.isCalibrated) {
            lastError = "Calibrate the mount before igniting."
            setState(SessionState.ERROR)
            return false
        }
        setState(SessionState.PREPARING)

        // Assets first: without them every mixer pointer is null and the engine renders silence,
        // which looks exactly like a mute bug and is not one.
        val assetCount = AssetLoader.loadAll(context, engine)
        if (assetCount == 0) {
            lastError = "No sound assets available — the engine would be silent."
            setState(SessionState.ERROR)
            return false
        }

        val generationId = generation.incrementAndGet()
        Log.i(TAG, "ignite: $assetCount assets, generation $generationId")
        processor.beginSession(generationId)
        engine.reset()
        engine.setVolume(volume)

        if (!requestFocus()) {
            lastError = "Audio focus denied."
            setState(SessionState.ERROR)
            return false
        }

        // The platform's own recommendation: take the recommended native rate and buffer size
        // from AudioManager rather than assuming them.
        val sampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
        val frameCount =
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
        Log.i(TAG, "opening stream: rate=$sampleRate frames=$frameCount")

        if (!engine.open(generationId, volume, sampleRate, frameCount)) {
            abandonFocus()
            lastError = "Could not open an audio stream (oboe error ${engine.counters().errorCode})."
            setState(SessionState.ERROR)
            return false
        }

        val source = SensorSource(context, processor) { frame -> engine.push(frame) }
        if (!source.start()) {
            engine.close()
            abandonFocus()
            lastError = "Could not open the accelerometer."
            setState(SessionState.ERROR)
            return false
        }
        sensorSource = source

        pendingRetraction = false
        engine.pushEvent(AudioEngine.EVENT_IGNITE)
        setState(SessionState.IGNITING)
        // Without this the machine sits in IGNITING forever: nothing else advances it. ACTIVE means
        // "the sound is up and responding", which is true once the ignition one-shot has handed over
        // to the hum underneath it.
        mainHandler.postDelayed({
            if (state == SessionState.IGNITING) setState(SessionState.ACTIVE)
        }, IGNITION_MS)
        return true
    }

    fun stop(playRetraction: Boolean = true) {
        if (state == SessionState.OFF) return  // idempotent

        // Order matters: stop new accents first, then let the tail play, then release everything.
        sensorSource?.let { source ->
            source.stop()
            sensorSource = null
        }

        if (engine.isOpen && playRetraction) {
            pendingRetraction = true
            engine.pushEvent(AudioEngine.EVENT_RETRACT)
            setState(SessionState.RETRACTING)
            releaseAfterRetraction()
        } else {
            finishStop()
        }
    }

    /** Called from the UI when the activity is going away: prompt silence, no cosmetics. */
    fun stopImmediate() {
        sensorSource?.stop()
        sensorSource = null
        if (engine.isOpen) engine.pushEvent(AudioEngine.EVENT_STOP_IMMEDIATE)
        finishStop()
    }

    private fun releaseAfterRetraction() {
        Thread {
            Thread.sleep(RETRACTION_TAIL_MS)
            if (state == SessionState.RETRACTING) finishStop()
        }.apply { isDaemon = true }.start()
    }

    private fun finishStop() {
        engine.close()
        abandonFocus()
        pendingRetraction = false
        setState(SessionState.OFF)
    }

    fun takeNativeError(): Boolean = engine.counters().errorCode != 0L

    fun diagnostics(): SessionDiagnostics {
        val motion = processor.currentCounters()
        val audio = engine.counters()
        return SessionDiagnostics(
            motionRateHz = motion.measuredRateHz,
            gyroSamples = motion.gyroSamples,
            accelSamples = motion.accelSamples,
            gapResets = motion.gapResets,
            staleAccelDrops = motion.staleAccelDrops,
            saturationFlags = motion.saturationFlags,
            controlFramesConsumed = audio.controlFramesConsumed,
            controlFramesDropped = audio.controlFramesDropped,
            accentTriggers = audio.accentTriggers,
            clippedSamples = audio.clippedSamples,
            peakAbs = audio.peakAbs,
            sampleRateHz = audio.sampleRateHz,
            framesRendered = audio.framesRendered,
            errorCode = audio.errorCode,
            callbacksFired = audio.callbacksFired,
        )
    }

    // --- Audio focus -------------------------------------------------------------------

    private fun requestFocus(): Boolean {
        val attributes = AudioAttributes.Builder()
            // Game/sonification, not media: this is an instrument, and it must not be treated as
            // music by the system ducking rules.
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        val result = audioManager.requestAudioFocus(request)
        // AUDIOFOCUS_REQUEST_FAILED means do not play: another app holds focus outright.
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun setState(next: SessionState) {
        if (state == next) return
        state = next
        Log.i(TAG, "state -> $next")
        onState(next)
    }

    private companion object {
        const val TAG = "Lightsaber"
        const val RETRACTION_TAIL_MS = 600L

        /** Matches Mixer's ignition one-shot length, plus a little slack. */
        const val IGNITION_MS = 700L
    }
}

data class SessionDiagnostics(
    val motionRateHz: Float,
    val gyroSamples: Long,
    val accelSamples: Long,
    val gapResets: Long,
    val staleAccelDrops: Long,
    val saturationFlags: Long,
    val controlFramesConsumed: Long,
    val controlFramesDropped: Long,
    val accentTriggers: Long,
    val clippedSamples: Long,
    val peakAbs: Float,
    val sampleRateHz: Float,
    val framesRendered: Long = 0,
    val errorCode: Long = 0,
    val callbacksFired: Long = 0,
)