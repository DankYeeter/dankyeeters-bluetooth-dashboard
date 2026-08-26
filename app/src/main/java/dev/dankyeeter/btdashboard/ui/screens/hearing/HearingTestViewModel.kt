package dev.dankyeeter.btdashboard.ui.screens.hearing

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
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
import dev.dankyeeter.btdashboard.hearing.store.AudiogramStore
import dev.dankyeeter.btdashboard.monitor.MonitorGraph
import dev.dankyeeter.btdashboard.monitor.codec.BtAudioDevice
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.devices.DeviceKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    /** Ids of the runs that feed the curve. Empty means "the three newest". */
    val selectedRunIds: Set<String> = emptySet(),
    val presenting: HearingTestState.Presenting? = null,
    val fitResult: FitCheckResult? = null,
    val fitCheckPassed: Boolean = false,
    val lastRun: AudiogramRun? = null,
    val lastReliability: RunReliability? = null,
    val runs: List<AudiogramRun> = emptyList(),
    val audiogram: Audiogram? = null,
    /** Key of the headphone currently active, null when nothing is connected. */
    val currentDeviceKey: String? = null,
    val currentDeviceName: String? = null,
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
    private val deviceProfiles = SystemGraph.deviceProfiles

    private var toneGenerator: NativeToneGenerator? = null
    private var controller: HughsonWestlakeTestController? = null
    private var volumeGuard: VolumeGuard? = null
    private var focusRequest: AudioFocusRequest? = null
    private var runJob: Job? = null

    /** The active headphone, hashed the same way device profiles are keyed. */
    private val activeDevice: StateFlow<BtAudioDevice?> =
        MonitorGraph.codecSource.connectedDevicesFlow()
            .map { devices -> devices.firstOrNull { it.isActive } ?: devices.firstOrNull() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            // All runs are shown; only the chosen ones are averaged - and only
            // ones measured through the connected headphone count, because a
            // hearing curve is a property of ear plus driver together.
            combine(store.runs, store.selectedRunIds, activeDevice) { all, ids, device ->
                Triple(all, ids, device)
            }.collect { (all, ids, device) ->
                val key = DeviceKey.fromAddress(device?.address)
                val chosen = AudiogramStore.selectionOf(all, ids, key)
                _state.value = _state.value.copy(
                    runs = all,
                    selectedRunIds = ids,
                    audiogram = if (chosen.isEmpty()) null else aggregator.aggregate(chosen),
                    currentDeviceKey = key,
                    currentDeviceName = device?.name,
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
            // Stamped here rather than in the controller: the controller is
            // pure audio and pure protocol, and which headphone was on the
            // head is something only this layer knows.
            val device = activeDevice.value
            store.addRun(
                run.copy(
                    deviceAddressHash = DeviceKey.fromAddress(device?.address),
                    deviceName = device?.name,
                ),
            )
            _state.value = _state.value.copy(
                phase = HearingPhase.RESULT,
                presenting = null,
                busy = false,
                lastRun = run,
                lastReliability = controller?.reliability,
                // No advice to delete it here: the result screen carries the
                // two buttons, and telling someone to delete a run on a screen
                // with no delete button is the whole complaint.
                message = controller?.reliability?.takeIf { it.unreliable }?.let {
                    "This run looks unreliable: ${it.summary} Presses during the silent checks " +
                        "mean the thresholds came out better than they are."
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

    fun setRunSelected(id: String, selected: Boolean) {
        viewModelScope.launch { store.setRunSelected(id, selected) }
    }

    fun deleteRun(id: String) {
        viewModelScope.launch { store.deleteRun(id) }
    }

    fun deleteAllRuns() {
        viewModelScope.launch { store.deleteAllRuns() }
    }

    private var noticeJob: Job? = null

    /**
     * Says that the volume is locked, then takes itself back.
     *
     * It used to sit there until the user pressed OK - a confirmation for
     * something nobody asked to do and nothing to decide. The message exists so
     * a press on the volume key is not mistaken for a broken phone; once it has
     * been read it is in the way, and it is in the way during a test where the
     * next tone is already on its path.
     */
    private fun showVolumeLockedNotice() {
        _state.value = _state.value.copy(volumeLockedNotice = true)
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _state.value = _state.value.copy(volumeLockedNotice = false)
        }
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
            // Before the first tone, not after: whatever is playing has to be
            // gone before anything is measured.
            grabAudioFocus()
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
                calibrationPresetId = calibrationPresetId(),
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

    /**
     * The preset this run is measured through, not the one it wishes it had.
     *
     * A run stores the preset id because the id is what turns raw output levels
     * into dB HL - the same button press means a different threshold on a
     * headphone with a different sensitivity curve. Stamping every run as
     * generic while the user had chosen a preset for this headphone in its
     * profile made the stored number a claim about a measurement that never
     * happened, and nothing downstream could tell the difference afterwards.
     *
     * Generic when there is no device, no profile, or no preset chosen in it:
     * that is honestly what was used.
     */
    private suspend fun calibrationPresetId(): String {
        val key = DeviceKey.fromAddress(activeDevice.value?.address)
            ?: return CalibrationPresetRepository.GENERIC_ID
        return deviceProfiles.profileFor(key)?.calibrationPresetId
            ?: CalibrationPresetRepository.GENERIC_ID
    }

    private fun describe(reason: AbortReason): String = when (reason) {
        AbortReason.USER_CANCELLED -> "Test cancelled."
        // Short, because the screen now offers "Start again" underneath it: the
        // sentence explaining why a threshold measured at another loudness is
        // worthless was doing the work a button does in one tap.
        AbortReason.VOLUME_CHANGED ->
            "Media volume changed, so the run was discarded. Set your usual volume and start again."
        AbortReason.DEVICE_DISCONNECTED -> "The audio device disconnected. The run was discarded."
        AbortReason.AUDIO_ERROR -> "The audio stream failed. The run was discarded."
    }

    private fun teardown() {
        controller?.release()
        volumeGuard?.release()
        toneGenerator?.close()
        releaseAudioFocus()
        controller = null
        volumeGuard = null
        toneGenerator = null
    }

    /**
     * Asks every other player to stop, and means it.
     *
     * A hearing test measures the quietest tone a person can still hear. Music
     * underneath does not merely disturb that - it decides the result, and the
     * result looks perfectly plausible afterwards. Nobody remembers, an hour
     * later, that something was playing.
     *
     * TRANSIENT_EXCLUSIVE rather than a plain gain: it tells the system that
     * ducking is not good enough here and the other app must fall silent
     * entirely.
     */
    private fun grabAudioFocus() {
        val audio = getApplication<Application>().getSystemService(AudioManager::class.java)
            ?: return
        val request = AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            // Required by the builder. Nothing to do on a change: the test owns
            // the output for its duration, and an interruption is handled by
            // the run being cancelled, not by lowering our own tone.
            .setOnAudioFocusChangeListener { }
            .build()
        focusRequest = request
        val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, "audio focus for the test granted=$granted")
    }

    private fun releaseAudioFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        runCatching {
            getApplication<Application>().getSystemService(AudioManager::class.java)
                ?.abandonAudioFocusRequest(request)
        }.onFailure { Log.w(TAG, "could not hand audio focus back", it) }
    }

    override fun onCleared() {
        VolumeKeyLock.locked = false
        VolumeKeyLock.onBlocked = null
        runJob?.cancel()
        teardown()
        super.onCleared()
    }

    private companion object {
        const val TAG = "HearingTest"

        /** Long enough to read one short sentence, short enough not to nag. */
        const val NOTICE_MS = 2_500L
    }
}
