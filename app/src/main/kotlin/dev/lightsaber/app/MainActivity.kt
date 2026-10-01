package dev.lightsaber.app

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.lightsaber.motion.CalibrationProfile
import dev.lightsaber.motion.MotionConfig
import dev.lightsaber.motion.MotionProcessor
import dev.lightsaber.motion.MountPreset
import dev.lightsaber.motion.MountPresets
import dev.lightsaber.motion.StillnessCapture
import dev.lightsaber.motion.StillnessResult
import dev.lightsaber.motion.Vec3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText


enum class Screen { READY, ACTIVE, CALIBRATION, DIAGNOSTICS }

data class UiState(
    val screen: Screen = Screen.READY,
    val capabilities: Capabilities? = null,
    val sessionState: SessionState = SessionState.OFF,
    val preset: MountPreset = MountPresets.PALM_RIGHT,
    val profile: CalibrationProfile = CalibrationProfile.uncalibrated(),
    val calibrating: Boolean = false,
    val calibrationMessage: String = "Hold still",
    val calibrationSamples: Int = 0,
    val calibrationNoise: Float = 0f,
    val diagnostics: SessionDiagnostics? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = AudioEngine()
    private val processor = MotionProcessor(MotionConfig())

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val session = SessionController(app, engine) { newState ->
        _state.value = _state.value.copy(sessionState = newState, screen = when (newState) {
            SessionState.ACTIVE, SessionState.IGNITING -> Screen.ACTIVE
            SessionState.OFF -> Screen.READY
            else -> _state.value.screen
        })
        if (newState == SessionState.ERROR) {
            _state.value = _state.value.copy(error = "Audio engine failed to start")
        }
    }

    init {
        viewModelScope.launch {
            val capabilities = withContext(Dispatchers.Default) { CapabilityChecker.probe(getApplication()) }
            _state.value = _state.value.copy(capabilities = capabilities)
        }
    }

    fun show(screen: Screen) {
        _state.value = _state.value.copy(screen = screen)
    }

    fun selectPreset(preset: MountPreset) {
        _state.value = _state.value.copy(preset = preset)
    }

    fun ignite() {
        session.setProfile(_state.value.profile)
        _state.value = _state.value.copy(error = null)
        session.ignite()
    }

    fun retract() = session.stop(playRetraction = true)

    /**
     * Runs the stillness capture on the watch's real sensors.
     *
     * The previous version pushed `Vec3.ZERO` into [StillnessCapture] from a timer loop, so it
     * measured nothing and could never reach StillnessCapture's minimum sample count — every
     * attempt was rejected regardless of how still the hand was.
     */
    fun calibrate() {
        if (_state.value.calibrating) return
        val preset = _state.value.preset
        _state.value = _state.value.copy(
            calibrating = true,
            calibrationMessage = "Hold still…",
            calibrationSamples = 0,
            calibrationNoise = 0f,
        )

        val capture = CalibrationCapture(getApplication()) { samples, noise ->
            _state.value = _state.value.copy(
                calibrationSamples = samples,
                calibrationNoise = noise,
                calibrationMessage = "Hold still… %d samples, %.3f rad/s".format(samples, noise),
            )
        }
        if (!capture.start()) {
            _state.value = _state.value.copy(
                calibrating = false,
                calibrationMessage = "No accelerometer available",
            )
            return
        }

        viewModelScope.launch {
            delay(CALIBRATION_HOLD_MS)
            val result = withContext(Dispatchers.IO) { capture.stop() }
            _state.value = when (result) {
                null -> _state.value.copy(calibrating = false, calibrationMessage = "No samples captured")
                is StillnessResult.Rejected -> _state.value.copy(
                    calibrating = false,
                    calibrationMessage = if (result.noiseRms.isNaN()) {
                        "Not enough samples (${capture.sampleCount})"
                    } else {
                        "Too much movement: %.3f rad/s, limit %.3f. Hold tighter and retry.".format(
                            result.noiseRms, result.tolerance,
                        )
                    },
                )
                is StillnessResult.Ok -> _state.value.copy(
                    calibrating = false,
                    calibrationMessage = "Calibrated: noise %.3f rad/s".format(result.noiseRms),
                    profile = CalibrationProfile(
                        presetId = preset.id,
                        handedness = preset.transform.handedness,
                        transform = preset.transform,
                        gyroBias = result.gyroBias,
                        noiseRms = result.noiseRms,
                        linearAccelOffset = result.accelOffset,
                        sampleCount = capture.sampleCount,
                        createdAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    companion object {
        /** 3 seconds at 100 Hz is ~300 samples, comfortably over StillnessCapture's minimum of 120. */
        const val CALIBRATION_HOLD_MS = 3_000L

        private val SESSION_STATES_WITH_AUDIO = setOf(
            SessionState.IGNITING,
            SessionState.ACTIVE,
            SessionState.RETRACTING,
            SessionState.INTERRUPTED,
        )
    }

    /** Polls at ~5 Hz: telemetry is for the eye, not the sensor thread. */
    fun refreshDiagnostics() {
        viewModelScope.launch {
            while (true) {
                // Telemetry is collected for every state where the stream is open. Gating on
                // ACTIVE alone left the readout permanently blank, because the machine was parked
                // in IGNITING.
                if (_state.value.sessionState in SESSION_STATES_WITH_AUDIO) {
                    val diagnostics = withContext(Dispatchers.Default) { session.diagnostics() }
                    _state.value = _state.value.copy(diagnostics = diagnostics)
                }
                delay(200)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        session.stopImmediate()
        engine.close()
    }
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LightsaberApp(viewModel)
            }
        }
    }

    override fun onStop() {
        // Leaving the supported foreground state must silence, not merely pause.
        super.onStop()
    }
}

@Composable
fun LightsaberApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshDiagnostics() }

    // Wear's Scaffold does not hand content padding to its lambda the way Material's does, so the
    // room reserved for the TimeText is added explicitly. Without it the bottom buttons fall off
    // the bottom of a 450 px round screen.
    Scaffold(timeText = { TimeText() }) {
        Box(modifier = Modifier.fillMaxSize().padding(top = 34.dp)) {
            when (state.screen) {
                Screen.READY -> ReadyScreen(state, viewModel)
                Screen.ACTIVE -> ActiveScreen(state, viewModel)
                Screen.CALIBRATION -> CalibrationScreen(state, viewModel)
                Screen.DIAGNOSTICS -> DiagnosticsScreen(state, viewModel)
            }
        }
    }
}

@Composable
private fun ReadyScreen(state: UiState, viewModel: MainViewModel) {
    val caps = state.capabilities
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101014))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("LIGHTSABER", fontSize = 15.sp, color = Color(0xFF7FD8FF), textAlign = TextAlign.Center)
        Text(
            text = when (caps?.mode) {
                CapabilityMode.FULL -> "Full mode"
                CapabilityMode.REDUCED -> "Reduced mode (no gyro)"
                CapabilityMode.UNAVAILABLE -> "Unavailable"
                null -> "Checking…"
            },
            fontSize = 11.sp,
            color = Color.White,
        )
        // One line, not two: on a 450 px round screen every dp counts, and these two lines were
        // pushing the Calibrate/Diag row off the bottom edge.
        val rate = caps?.gyroscope?.maxRateHz ?: caps?.accelerometer?.maxRateHz
        if (rate != null) {
            Text("sensors ${"%.0f".format(rate)}Hz max", fontSize = 9.sp, color = Color(0xFF9AA0A6))
        }
        Text(
            text = state.profile.let { if (it.isCalibrated) "Mount: ${it.presetId}" else "Not calibrated" },
            fontSize = 11.sp,
            color = if (state.profile.isCalibrated) Color(0xFF7FD8FF) else Color(0xFFFFB74D),
        )
        state.error?.let {
            Text(it, fontSize = 10.sp, color = Color(0xFFFF8A80), textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { viewModel.ignite() },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.profile.isCalibrated,
        ) {
            Text("IGNITE", fontSize = 15.sp)
        }
        Row2 {
            Button(onClick = { viewModel.show(Screen.CALIBRATION) }, modifier = Modifier.weight(1f)) {
                Text("Calibrate", fontSize = 11.sp)
            }
            Button(onClick = { viewModel.show(Screen.DIAGNOSTICS) }, modifier = Modifier.weight(1f)) {
                Text("Diag", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ActiveScreen(state: UiState, viewModel: MainViewModel) {
    val d = state.diagnostics
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101014))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("ACTIVE", fontSize = 20.sp, color = Color(0xFF7FD8FF))
        Text("${state.sessionState.name}", fontSize = 11.sp, color = Color(0xFF9AA0A6))
        d?.let {
            Text("rate %.1f Hz".format(it.motionRateHz), fontSize = 11.sp, color = Color.White)
            Text("frames ${it.controlFramesConsumed}", fontSize = 11.sp, color = Color.White)
            Text("rendered ${it.framesRendered} cbs ${it.callbacksFired} err ${it.errorCode}", fontSize = 11.sp, color = Color.White)
            Text("peak %.2f  clip %d".format(it.peakAbs, it.clippedSamples), fontSize = 11.sp, color = Color.White)
        }
        Box(modifier = Modifier.weight(1f))
        Button(
            onClick = { viewModel.retract() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("RETRACT", fontSize = 18.sp)
        }
    }
}

@Composable
private fun CalibrationScreen(state: UiState, viewModel: MainViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101014))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("CALIBRATE", fontSize = 16.sp, color = Color(0xFF7FD8FF))
        MountPresets.ALL.forEach { preset ->
            Chip(
                onClick = { viewModel.selectPreset(preset) },
                label = {
                    Text(
                        if (state.preset.id == preset.id) "● ${preset.label}" else preset.label,
                        fontSize = 11.sp,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(state.preset.instruction, fontSize = 10.sp, color = Color(0xFF9AA0A6),
            textAlign = TextAlign.Center)
        Text(state.calibrationMessage, fontSize = 12.sp, color = Color.White)
        Button(
            onClick = { viewModel.calibrate() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.calibrating,
        ) {
            Text(if (state.calibrating) "Measuring…" else "Hold still 3s", fontSize = 13.sp)
        }
        Button(onClick = { viewModel.show(Screen.READY) }, modifier = Modifier.fillMaxWidth()) {
            Text("Back", fontSize = 12.sp)
        }
    }
}

@Composable
private fun DiagnosticsScreen(state: UiState, viewModel: MainViewModel) {
    val caps = state.capabilities
    val d = state.diagnostics
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101014))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("DIAGNOSTICS", fontSize = 14.sp, color = Color(0xFF7FD8FF))
        if (d == null) {
            Text("Ignite to collect", fontSize = 11.sp, color = Color.White)
        } else {
            Text("motion %.1f Hz".format(d.motionRateHz), fontSize = 11.sp, color = Color.White)
            Text("gyro ${d.gyroSamples}  accel ${d.accelSamples}", fontSize = 10.sp, color = Color.White)
            Text("gaps ${d.gapResets}  stale ${d.staleAccelDrops}", fontSize = 10.sp, color = Color.White)
            Text("dropped ${d.controlFramesDropped}", fontSize = 10.sp, color = Color.White)
            Text("accents ${d.accentTriggers}", fontSize = 10.sp, color = Color.White)
            Text("clip ${d.clippedSamples}  peak %.2f".format(d.peakAbs), fontSize = 10.sp, color = Color.White)
            Text("stream ${"%.0f".format(d.sampleRateHz)} Hz", fontSize = 10.sp, color = Color.White)
        }
        caps?.notes?.forEach {
            Text(it, fontSize = 9.sp, color = Color(0xFF9AA0A6), textAlign = TextAlign.Center)
        }
        Button(onClick = { viewModel.show(Screen.READY) }, modifier = Modifier.fillMaxWidth()) {
            Text("Back", fontSize = 12.sp)
        }
    }
}

@Composable
private fun Row2(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}