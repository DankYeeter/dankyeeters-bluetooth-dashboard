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
import dev.dankyeeter.btdashboard.hearing.AgeReference
import dev.dankyeeter.btdashboard.hearing.AncMode
import dev.dankyeeter.btdashboard.hearing.Audiogram
import dev.dankyeeter.btdashboard.hearing.AudiogramRun
import dev.dankyeeter.btdashboard.hearing.CalibrationPresetRepository
import dev.dankyeeter.btdashboard.hearing.CalibrationTransfer
import dev.dankyeeter.btdashboard.hearing.ClinicalAudiogram
import dev.dankyeeter.btdashboard.hearing.DerivedCalibration
import dev.dankyeeter.btdashboard.hearing.HearingDrift
import dev.dankyeeter.btdashboard.hearing.HearingDriftResult
import dev.dankyeeter.btdashboard.hearing.HearingGraph
import dev.dankyeeter.btdashboard.hearing.HearingTestConfig
import dev.dankyeeter.btdashboard.hearing.HearingTestState
import dev.dankyeeter.btdashboard.hearing.HughsonWestlakeTestController
import dev.dankyeeter.btdashboard.hearing.Iso7029
import dev.dankyeeter.btdashboard.hearing.Iso7029Sex
import dev.dankyeeter.btdashboard.hearing.LowToneArtifact
import dev.dankyeeter.btdashboard.hearing.PrepareResult
import dev.dankyeeter.btdashboard.hearing.RunReliability
import dev.dankyeeter.btdashboard.hearing.SelfTestThresholds
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
import java.time.Year

/** Where the user currently is in the hearing-test flow. */
enum class HearingPhase { INTRO, FIT_CHECK, TESTING, RESULT, HISTORY }

/** Kotlin ships Pair and Triple and stops there; the combine below needs four. */
private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

data class HearingUiState(
    val phase: HearingPhase = HearingPhase.INTRO,
    val formFactor: DeviceFormFactor = DeviceFormFactor.IN_EAR,
    val busy: Boolean = false,
    /** Free-text banner: ambient warning, fit warning, abort reason, errors. */
    val message: String? = null,
    val volumeLockedNotice: Boolean = false,
    /** Ids of the runs that feed the curve. Empty means "the three newest". */
    val selectedRunIds: Set<String> = emptySet(),
    /**
     * Locks the run to a lower media volume, for ears the normal window
     * cannot measure: every point at the floor means "quieter than I can
     * ask", and the only honest way further down is less analogue gain.
     */
    val quietTest: Boolean = false,
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
    /**
     * The ENT result, if one has been entered. Not per device: it is a property
     * of the ears, so it stays put when the headphones change.
     */
    val clinicalAudiogram: ClinicalAudiogram? = null,
    /**
     * The calibration derived for the headphone that is connected right now, or
     * null. Per device, unlike [clinicalAudiogram] — see [DerivedCalibration].
     */
    val derivedCalibration: DerivedCalibration? = null,
    /**
     * Birth year and optional sex behind the ISO 7029 age reference, or null.
     * A property of the person, so it survives a device change like the
     * clinical audiogram does.
     */
    val ageReference: AgeReference? = null,
    /**
     * The year the screen is being read in, so the age reference stays true as
     * the calendar moves. Held in state rather than read at each use so that a
     * test can put the clock wherever it needs it.
     */
    val currentYear: Int = Year.now().value,
) {
    val fitCheckRequired: Boolean get() = formFactor.fitCheckMandatory && !fitCheckPassed

    /**
     * The runs the transfer would use: exactly the ones behind [audiogram].
     *
     * Asked from the same function the curve asks, rather than filtered again
     * here. A derivation built from a different set of runs than the curve on
     * screen would be a correction for a measurement the user was never shown.
     */
    val runsForCurrentDevice: List<AudiogramRun>
        get() = AudiogramStore.selectionOf(runs, selectedRunIds, currentDeviceKey)

    /**
     * Whether the transfer has both of its halves: a clinical audiogram, and at
     * least one run measured through the headphone that is connected.
     *
     * The device key is required rather than tolerated as null. The result is
     * stored *against* a headphone and only ever applied to that headphone; with
     * nothing connected there is no identity to store it under, and guessing one
     * would attach a device response to whatever connects next.
     */
    val canDeriveCalibration: Boolean
        get() = clinicalAudiogram?.isEmpty == false &&
            currentDeviceKey != null &&
            runsForCurrentDevice.isNotEmpty()

    /**
     * Whether the run just finished should carry the low-tone advisory.
     *
     * Derived here rather than stored, so it can never disagree with the run
     * and the clinical audiogram it is about — both of which can change while
     * the result screen is open. The whole rule lives in [LowToneArtifact];
     * this is only the wiring.
     */
    val lowToneArtifact: LowToneArtifact.Advice?
        get() = lastRun?.let { LowToneArtifact.evaluate(it, clinicalAudiogram) }

    /**
     * The age-typical curve in the chart's deviation frame, or empty when no
     * birth year has been entered. Population statistics, never a measurement —
     * see [Iso7029].
     */
    val ageReferenceCurve: List<Pair<Int, Double>>
        get() = ageReference?.deviationCurve(currentYear).orEmpty()

    /**
     * Ears whose measured *shape* sits well below what the age model expects,
     * from the converged median curve rather than from one run.
     *
     * Suppressed entirely while a clinical audiogram exists, and that is the
     * ranking the whole feature promises: a calibrated measurement of these
     * ears outranks a statistic about a population, so once one is on file the
     * app has no business raising a population-based eyebrow. The clinical
     * comparison and [LowToneArtifact] already answer the same question with
     * better evidence.
     */
    val ageReferenceGaps: List<Pair<Ear, Iso7029.AgeGap>>
        get() {
            if (clinicalAudiogram?.isEmpty == false) return emptyList()
            val reference = ageReference ?: return emptyList()
            val curve = audiogram ?: return emptyList()
            return Iso7029.gapsAgainstAgeReference(curve, reference.ageAt(currentYear), reference.sex)
        }

    /**
     * Whether hearing has moved since the earliest comparable runs. Derived
     * rather than stored so it can never disagree with the runs on screen; the
     * whole rule, and the noise reasoning behind it, lives in [HearingDrift].
     */
    val drift: HearingDriftResult
        get() = HearingDrift.evaluate(runs, currentDeviceKey, currentDeviceName)

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
            // The derivation rides along in the same combine rather than in its
            // own collector: it is keyed to the headphone, so it has to be
            // re-resolved on exactly the emissions that change the device.
            combine(
                store.runs,
                store.selectedRunIds,
                activeDevice,
                store.derivedCalibrations,
            ) { all, ids, device, derived ->
                Quad(all, ids, device, derived)
            }.collect { (all, ids, device, derived) ->
                val key = DeviceKey.fromAddress(device?.address)
                val chosen = AudiogramStore.selectionOf(all, ids, key)
                _state.value = _state.value.copy(
                    runs = all,
                    selectedRunIds = ids,
                    audiogram = if (chosen.isEmpty()) null else aggregator.aggregate(chosen),
                    currentDeviceKey = key,
                    currentDeviceName = device?.name,
                    derivedCalibration = derived.firstOrNull { it.deviceKey == key },
                )
            }
        }
        viewModelScope.launch {
            // A separate collector, because this record does not belong to the
            // headphone: it must survive a device change, which the combine
            // above deliberately does not.
            store.clinicalAudiogram.collect { clinical ->
                _state.value = _state.value.copy(clinicalAudiogram = clinical)
            }
        }
        viewModelScope.launch {
            // Its own collector for the same reason: a birth year belongs to the
            // person and must not be re-resolved when the headphones change.
            store.ageReference.collect { reference ->
                _state.value = _state.value.copy(ageReference = reference)
            }
        }
        VolumeKeyLock.onBlocked = { showVolumeLockedNotice() }
    }

    /**
     * Stores the birth year (and optional sex) for the age reference.
     *
     * An implausible year is refused in words rather than stored and clamped
     * later: a curve drawn for a year in the future is a curve for nobody, and
     * the person who mistyped it would have no way to tell.
     */
    fun saveAgeReference(birthYear: Int, sex: Iso7029Sex) {
        val year = _state.value.currentYear
        if (!AgeReference.isPlausible(birthYear, year)) {
            return message("That birth year cannot be right — it has to be $year or earlier.")
        }
        viewModelScope.launch { store.saveAgeReference(AgeReference(birthYear, sex)) }
    }

    fun clearAgeReference() {
        viewModelScope.launch { store.clearAgeReference() }
    }

    /** Stores the ENT values, or clears them when the editor was emptied. */
    fun saveClinicalAudiogram(audiogram: ClinicalAudiogram) {
        viewModelScope.launch {
            store.saveClinicalAudiogram(audiogram.copy(savedAtMillis = System.currentTimeMillis()))
        }
    }

    fun clearClinicalAudiogram() {
        viewModelScope.launch { store.clearClinicalAudiogram() }
    }

    /**
     * Turns the clinical audiogram and this headphone's own runs into a
     * measured calibration for this headphone. See [CalibrationTransfer] for
     * why the difference between the two is the device.
     *
     * Every refusal below says which half is missing, in words. The alternative
     * — a disabled button — leaves someone who has a clinical audiogram, has
     * run three tests, and still cannot press it with nothing to read; and the
     * two halves fail for genuinely different reasons.
     */
    fun deriveCalibration() {
        val current = _state.value
        val clinical = current.clinicalAudiogram
        if (clinical == null || clinical.isEmpty) {
            return message(
                "Enter your clinical audiogram first. The transfer needs both measurements " +
                    "of the same ears — the clinic's and this app's.",
            )
        }
        val deviceKey = current.currentDeviceKey ?: return message(
            "Connect the headphones you tested with. A derived calibration belongs to one " +
                "headphone, so there has to be one to store it against.",
        )
        val runs = current.runsForCurrentDevice
        if (runs.isEmpty()) {
            return message("No run from this headphone counts yet. Run the hearing test first.")
        }

        val result = CalibrationTransfer.derive(
            clinicLeftHl = clinical.leftDbHl,
            clinicRightHl = clinical.rightDbHl,
            // Converged points only, medianed across the runs — see
            // [SelfTestThresholds] for why a clipped point may not enter here.
            selfLeftDbfs = SelfTestThresholds.medianPerFrequency(runs, Ear.LEFT),
            selfRightDbfs = SelfTestThresholds.medianPerFrequency(runs, Ear.RIGHT),
        ) ?: return message(
            "Not enough overlapping frequencies. The transfer needs at least " +
                "${CalibrationTransfer.MIN_OVERLAP} frequencies measured both at the clinic " +
                "and here, on the same ear. Fill in more of the clinic's form, or re-run the " +
                "test if points came back hollow.",
        )

        val calibration = DerivedCalibration(
            deviceKey = deviceKey,
            deviceName = current.currentDeviceName,
            responseDeviationDb = result.responseDeviationDb,
            earSpreadDb = result.earSpreadDb,
            warnings = result.warnings,
            createdAtMillis = System.currentTimeMillis(),
            sourceRunIds = runs.map { it.id },
        )
        viewModelScope.launch { store.saveDerivedCalibration(calibration) }

        // The warnings are appended, never swallowed and never shown instead of
        // the result: the derivation is stored either way, and the caveats are
        // about how much to trust it rather than about whether it happened.
        message(
            buildString {
                append(
                    "Calibration derived from ${runs.size} run" +
                        (if (runs.size == 1) "" else "s") +
                        " and your clinical audiogram. It is now offered as a preset on the " +
                        "equaliser screen. What it describes is these headphones on your ears, " +
                        "at the fit you had during those runs — not the model in general.",
                )
                result.warnings.forEach { warning ->
                    append("\n\n")
                    append(warning)
                }
            },
        )
    }

    /** Forgets the derivation for the connected headphone. */
    fun discardDerivedCalibration() {
        val deviceKey = _state.value.currentDeviceKey ?: return
        viewModelScope.launch { store.clearDerivedCalibration(deviceKey) }
    }

    private fun message(text: String) {
        _state.value = _state.value.copy(message = text)
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
            testVolumeFraction = if (_state.value.quietTest) {
                QUIET_VOLUME_FRACTION
            } else {
                VolumeGuard.TEST_VOLUME_FRACTION
            },
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

    fun setQuietTest(enabled: Boolean) {
        _state.value = _state.value.copy(quietTest = enabled)
    }

    private fun launchRun(
        phase: HearingPhase,
        protocol: ProtocolConfig,
        frequencies: List<Int>,
        runAmbientCheck: Boolean,
        // The fit check stays at the standard level on purpose: its baseline
        // was recorded there, and a comparison across volumes compares the
        // volume, not the fit.
        testVolumeFraction: Double = VolumeGuard.TEST_VOLUME_FRACTION,
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
                testVolumeFraction = testVolumeFraction,
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

        /**
         * The quiet-test volume. Well under the standard 0.7, comfortably
         * above VolumeGuard's 0.3 too-low gate, and a step large enough that
         * the window genuinely moves rather than wobbles.
         */
        const val QUIET_VOLUME_FRACTION = 0.4
    }
}
