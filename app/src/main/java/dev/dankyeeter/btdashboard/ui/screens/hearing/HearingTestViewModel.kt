package dev.dankyeeter.btdashboard.ui.screens.hearing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.audio.tone.NativeToneGenerator
import dev.dankyeeter.btdashboard.hearing.AbortReason
import dev.dankyeeter.btdashboard.hearing.AncMode
import dev.dankyeeter.btdashboard.hearing.Audiogram
import dev.dankyeeter.btdashboard.hearing.AudiogramRun
import dev.dankyeeter.btdashboard.hearing.CalibrationPresetRepository
import dev.dankyeeter.btdashboard.hearing.HearingGraph
import dev.dankyeeter.btdashboard.hearing.HearingTestConfig
import dev.dankyeeter.btdashboard.hearing.HearingTestState
import dev.dankyeeter.btdashboard.hearing.HughsonWestlakeTestController
import dev.dankyeeter.btdashboard.hearing.PrepareResult
import dev.dankyeeter.btdashboard.hearing.RunReliability
import dev.dankyeeter.btdashboard.hearing.TEST_FREQUENCIES_HZ
import dev.dankyeeter.btdashboard.hearing.fit.DeviceFormFactor
import dev.dankyeeter.btdashboard.hearing.fit.FitCheck
import dev.dankyeeter.btdashboard.hearing.fit.FitCheckResult
import dev.dankyeeter.btdashboard.hearing.level.VolumeGuard
import dev.dankyeeter.btdashboard.hearing.noise.MicAmbientNoiseCheck
import dev.dankyeeter.btdashboard.hearing.protocol.ProtocolConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Where the user currently is in the hearing-test flow. */
enum class HearingPhase { INTRO, FIT_CHECK, TESTING, RESULT, HISTORY }

data class HearingUiState(
    val phase: HearingPhase = HearingPhase.INTRO,
    val formFactor: DeviceFormFactor = DeviceFormFactor.IN_EAR,
    val busy: Boolean = false,
    /** Free-text banner: ambient warning, fit warning, abort reason, errors. */
    val message: String? = null,
    val volumeLockedNotice: Boolean = false,
    val presenting: HearingTestState.Presenting? = null,
    val fitResult: FitCheckResult? = null,
    val fitCheckPassed: Boolean = false,
    val lastRun: AudiogramRun? = null,
    val lastReliability: RunReliability? = null,
    val runs: List<AudiogramRun> = emptyList(),
    val audiogram: Audiogram? = null,
) {
    val fitCheckRequired: Boolean get() = formFactor.fitCheckMandatory && !fitCheckPassed

    /** Fraction 0..1 of the current run, for the progress indicator. */
    val progress: Float
        get() = presenting?.let { (it.frequencyIndex.toFloat() / it.frequencyCount).coerceIn(0f, 1f) } ?: 0f
}

/**
 * Owns one test run at a time: tone generator, controller, volume guard.
 *
 * Everything protocol-shaped lives in :core-hearing; this class is the bridge
 * between that and Compose, plus the run bookkeeping (store, median audiogram).
 */
class HearingTestViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(HearingUiState())
    val state: StateFlow<HearingUiState> = _state.asStateFlow()

    private val store = HearingGraph.audiogramStore
    private val aggregator = HearingGraph.aggregator
    private val ambientCheck = MicAmbientNoiseCheck(application)

    private var toneGenerator: NativeToneGenerator? = null
    private var controller: HughsonWestlakeTestController? = null
    private var volumeGuard: VolumeGuard? = null
    private var runJob: Job? = null

    init {
        viewModelScope.launch {
            store.runs.collect { runs ->
                _state.value = _state.value.copy(
                    runs = runs,
                    audiogram = if (runs.isEmpty()) null else aggregator.aggregate(runs),
                )
            }
        }
        VolumeKeyLock.onBlocked = { showVolumeLockedNotice() }
    }

    val needsMicPermission: Boolean get() = !ambientCheck.hasPermission

    fun setFormFactor(formFactor: DeviceFormFactor) {
        _state.value = _state.value.copy(formFactor = formFactor, fitCheckPassed = false, fitResult = null)
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun showHistory() {
        _state.value = _state.value.copy(phase = HearingPhase.HISTORY, message = null)
    }

    fun backToIntro() {
        _state.value = _state.value.copy(phase = HearingPhase.INTRO, presenting = null, message = null)
    }

    /** Short 125/250 Hz seal probe against the stored baseline. */
    fun startFitCheck() {
        launchRun(
            phase = HearingPhase.FIT_CHECK,
            protocol = FitCheck.PROTOCOL,
            frequencies = FitCheck.FREQUENCIES_HZ,
            runAmbientCheck = false,
        ) { run ->
            val baseline = store.currentFitBaseline()
            val results = listOf(
                Ear.LEFT to FitCheck.evaluate(run.left, baseline, Ear.LEFT),
                Ear.RIGHT to FitCheck.evaluate(run.right, baseline, Ear.RIGHT),
            )
            results.forEach { (_, result) ->
                if (result is FitCheckResult.BaselineStored) store.saveFitBaseline(result.baseline)
            }
            val warning = results.firstOrNull { it.second is FitCheckResult.Warning }
            val message = when {
                warning != null -> {
                    val ear = if (warning.first == Ear.LEFT) "Left" else "Right"
                    "$ear: " + (warning.second as FitCheckResult.Warning).message
                }
                results.any { it.second is FitCheckResult.BaselineStored } ->
                    "Fit baseline stored. From now on this probe compares against it."
                else -> "Fit looks like your baseline. Ready to test."
            }
            _state.value = _state.value.copy(
                phase = HearingPhase.INTRO,
                presenting = null,
                busy = false,
                fitResult = results.first().second,
                fitCheckPassed = warning == null,
                message = message,
            )
        }
    }

    /** The real audiogram run, both ears. */
    fun startTest(runAmbientCheck: Boolean = true) {
        launchRun(
            phase = HearingPhase.TESTING,
            protocol = ProtocolConfig(),
            frequencies = TEST_FREQUENCIES_HZ,
            runAmbientCheck = runAmbientCheck,
        ) { run ->
            store.addRun(run)
            _state.value = _state.value.copy(
                phase = HearingPhase.RESULT,
                presenting = null,
                busy = false,
                lastRun = run,
                lastReliability = controller?.reliability,
                message = controller?.reliability?.takeIf { it.unreliable }?.let {
                    "This run looks unreliable: ${it.summary} Presses on silent catch trials mean the " +
                        "thresholds are probably too good. Consider deleting it and testing again."
                },
            )
        }
    }

    fun onUserResponse() {
        controller?.onUserResponse()
    }

    fun cancelRun() {
        viewModelScope.launch {
            controller?.abort(AbortReason.USER_CANCELLED)
            runJob?.cancel()
            teardown()
            _state.value = _state.value.copy(
                phase = HearingPhase.INTRO,
                presenting = null,
                busy = false,
                message = "Test cancelled.",
            )
        }
    }

    fun deleteRun(id: String) {
        viewModelScope.launch { store.deleteRun(id) }
    }

    fun deleteAllRuns() {
        viewModelScope.launch { store.deleteAllRuns() }
    }

    private fun showVolumeLockedNotice() {
        _state.value = _state.value.copy(volumeLockedNotice = true)
    }

    fun dismissVolumeLockedNotice() {
        _state.value = _state.value.copy(volumeLockedNotice = false)
    }

    private fun launchRun(
        phase: HearingPhase,
        protocol: ProtocolConfig,
        frequencies: List<Int>,
        runAmbientCheck: Boolean,
        onCompleted: suspend (AudiogramRun) -> Unit,
    ) {
        if (runJob?.isActive == true) return
        runJob = viewModelScope.launch {
            _state.value = _state.value.copy(phase = phase, busy = true, message = null)
            val tone = NativeToneGenerator()
            val guard = VolumeGuard(getApplication<Application>())
            val testController = HughsonWestlakeTestController(
                toneGenerator = tone,
                watchdogScope = viewModelScope,
                volumeGuard = guard,
                ambientNoiseCheck = ambientCheck,
                protocol = protocol,
            )
            toneGenerator = tone
            volumeGuard = guard
            controller = testController

            val config = HearingTestConfig(
                ear = null,
                frequenciesHz = frequencies,
                calibrationPresetId = CalibrationPresetRepository.GENERIC_ID,
                ancMode = AncMode.UNKNOWN,
                runAmbientNoiseCheck = runAmbientCheck,
            )

            when (val prepared = testController.prepare(config)) {
                is PrepareResult.Failed -> {
                    teardown()
                    _state.value = _state.value.copy(
                        phase = HearingPhase.INTRO, busy = false, message = prepared.message,
                    )
                    return@launch
                }
                is PrepareResult.Warning ->
                    _state.value = _state.value.copy(message = prepared.message)
                PrepareResult.Ready -> Unit
            }

            VolumeKeyLock.locked = true
            val stateCollector = launch {
                testController.state.collect { testState ->
                    when (testState) {
                        is HearingTestState.Presenting ->
                            _state.value = _state.value.copy(presenting = testState)
                        is HearingTestState.Aborted ->
                            _state.value = _state.value.copy(
                                phase = HearingPhase.INTRO,
                                presenting = null,
                                busy = false,
                                message = describe(testState.reason),
                            )
                        else -> Unit
                    }
                }
            }

            try {
                // start() returns once the run finished or was aborted; the
                // state flow then holds the terminal state.
                testController.start()
                val finalState = testController.state.first()
                if (finalState is HearingTestState.Completed) onCompleted(finalState.run)
            } finally {
                stateCollector.cancel()
                VolumeKeyLock.locked = false
                teardown()
            }
        }
    }

    private fun describe(reason: AbortReason): String = when (reason) {
        AbortReason.USER_CANCELLED -> "Test cancelled."
        AbortReason.VOLUME_CHANGED ->
            "Media volume changed during the run, so every threshold measured so far refers to a " +
                "different loudness. The run was discarded — set your usual volume and start again."
        AbortReason.DEVICE_DISCONNECTED -> "The audio device disconnected. The run was discarded."
        AbortReason.AUDIO_ERROR -> "The audio stream failed. The run was discarded."
    }

    private fun teardown() {
        controller?.release()
        volumeGuard?.release()
        toneGenerator?.close()
        controller = null
        volumeGuard = null
        toneGenerator = null
    }

    override fun onCleared() {
        VolumeKeyLock.locked = false
        VolumeKeyLock.onBlocked = null
        runJob?.cancel()
        teardown()
        super.onCleared()
    }
}
