package dev.dankyeeter.btdashboard.ui.screens.eq

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.hearing.AncMode
import dev.dankyeeter.btdashboard.hearing.Audiogram
import dev.dankyeeter.btdashboard.hearing.CalibrationPreset
import dev.dankyeeter.btdashboard.hearing.CalibrationPresetRepository
import dev.dankyeeter.btdashboard.hearing.CompensationProfile
import dev.dankyeeter.btdashboard.hearing.CompensationResult
import dev.dankyeeter.btdashboard.hearing.DEFAULT_INTENSITY
import dev.dankyeeter.btdashboard.hearing.DEFAULT_PARTIAL_FACTOR
import dev.dankyeeter.btdashboard.hearing.HearingGraph
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.ui.DetectedDeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * State of the compensation flow on the EQ screen.
 *
 * @param audiogram the median curve across the stored runs, or null when no
 *   hearing test has been completed yet
 * @param result the live preview for the current preset/intensity selection
 * @param applied true while the EQ currently in the system equals the preview
 */
data class CompensationUiState(
    val audiogram: Audiogram? = null,
    val runCount: Int = 0,
    val presets: List<CalibrationPreset> = emptyList(),
    val presetId: String = CalibrationPresetRepository.GENERIC_ID,
    val intensity: Float = DEFAULT_INTENSITY,
    val result: CompensationResult? = null,
    val profiles: List<CompensationProfile> = emptyList(),
    val activeProfileId: String? = null,
    val applied: Boolean = false,
) {
    val preset: CalibrationPreset?
        get() = presets.firstOrNull { it.id == presetId }
}

class EqViewModel : ViewModel() {

    private val store = SystemGraph.settingsStore
    private val controller = SystemGraph.eqController
    private val audiogramStore = HearingGraph.audiogramStore
    private val profileStore = HearingGraph.profileStore
    private val calculator = HearingGraph.compensationCalculator

    private val _settings = MutableStateFlow(EqSettings.FLAT)
    val settings: StateFlow<EqSettings> = _settings.asStateFlow()

    /**
     * A/B toggle: when true the bands play flat while the edited curve is kept.
     * The pre-gain stays applied in *both* states — COMPENSATION.md section 4
     * requires matched loudness so that "louder" cannot be mistaken for
     * "better".
     */
    private val _bypass = MutableStateFlow(false)
    val bypass: StateFlow<Boolean> = _bypass.asStateFlow()

    private val _compensation = MutableStateFlow(
        CompensationUiState(presets = HearingGraph.presets.all()),
    )
    val compensation: StateFlow<CompensationUiState> = _compensation.asStateFlow()

    val attachmentStatus = controller.status

    init {
        viewModelScope.launch {
            store.settings.collect { loaded ->
                _settings.value = loaded
                _compensation.value = _compensation.value.withAppliedFlag(loaded)
            }
        }
        viewModelScope.launch {
            audiogramStore.runs.collect { runs ->
                val audiogram = if (runs.isEmpty()) null else HearingGraph.aggregator.aggregate(runs)
                // Adopt the preset the test was actually run with; mixing a
                // curve measured through one device with another device's
                // correction would be meaningless.
                val presetId = runs.lastOrNull()?.calibrationPresetId ?: _compensation.value.presetId
                update {
                    it.copy(audiogram = audiogram, runCount = runs.size, presetId = presetId)
                }
            }
        }
        viewModelScope.launch {
            profileStore.profiles.collect { list -> update { it.copy(profiles = list) } }
        }
        viewModelScope.launch {
            // Hardware detection (AirPods beacon) suggests a preset. It is only
            // adopted while nothing better is selected: a preset the user chose,
            // or the one a test run was actually measured with, always wins.
            DetectedDeviceRepository.suggestedPresetId.collect { suggested ->
                if (suggested == null) return@collect
                update {
                    if (it.presetId == CalibrationPresetRepository.GENERIC_ID) {
                        it.copy(presetId = suggested)
                    } else {
                        it
                    }
                }
            }
        }
        viewModelScope.launch {
            store.activeProfileId.collect { id -> update { it.copy(activeProfileId = id) } }
        }
    }

    // ---- manual band editing -------------------------------------------------

    fun setBandGain(ear: Ear, bandIndex: Int, gainDb: Float) {
        compensationDrivesTheEq = false
        val cur = _settings.value
        val updated = when (ear) {
            Ear.LEFT -> cur.copy(leftGainsDb = cur.leftGainsDb.replaceAt(bandIndex, gainDb))
            Ear.RIGHT -> cur.copy(rightGainsDb = cur.rightGainsDb.replaceAt(bandIndex, gainDb))
        }.sanitized()
        _settings.value = updated
        pushLive(updated)
    }

    /** Edits both ears together — the common case before a hearing test exists. */
    fun setLinkedBandGain(bandIndex: Int, gainDb: Float) {
        compensationDrivesTheEq = false
        val cur = _settings.value
        val updated = cur.copy(
            leftGainsDb = cur.leftGainsDb.replaceAt(bandIndex, gainDb),
            rightGainsDb = cur.rightGainsDb.replaceAt(bandIndex, gainDb),
        ).sanitized()
        _settings.value = updated
        pushLive(updated)
    }

    fun setEnabled(enabled: Boolean) = commit(_settings.value.copy(enabled = enabled))

    fun setLimiterEnabled(enabled: Boolean) = commit(_settings.value.copy(limiterEnabled = enabled))

    /**
     * Flat vs. compensated at matched loudness: the bands go to 0 dB but the
     * negative pre-gain and the limiter state are carried over unchanged.
     */
    fun setBypass(bypass: Boolean) {
        _bypass.value = bypass
        pushLive(effective(_settings.value, bypass))
    }

    fun resetFlat() {
        compensationDrivesTheEq = false
        _bypass.value = false
        viewModelScope.launch { store.setActiveProfileId(null) }
        commit(EqSettings.FLAT.copy(enabled = _settings.value.enabled))
    }

    /** Persists the current curve; call on slider release. */
    fun persist() {
        viewModelScope.launch { store.save(_settings.value) }
    }

    // ---- compensation flow ---------------------------------------------------

    fun selectPreset(id: String) = update { it.copy(presetId = id) }

    fun setIntensity(value: Float) = update { it.copy(intensity = value.coerceIn(0f, 1f)) }

    fun resetIntensity() = setIntensity(DEFAULT_INTENSITY)

    /**
     * True once the live EQ came from the compensation flow rather than from
     * manual band edits; lets the intensity slider keep the curve live.
     */
    private var compensationDrivesTheEq: Boolean = false

    /** Writes the previewed curve into the live EQ and persists it. */
    fun applyCompensation() {
        val result = _compensation.value.result ?: return
        _bypass.value = false
        compensationDrivesTheEq = true
        commit(result.eq.copy(enabled = true))
    }

    /**
     * Called when the intensity slider is released: while the compensation is
     * the live curve, keep it live instead of silently drifting out of sync.
     * Does nothing when the user is editing bands by hand.
     */
    fun applyCompensationIfActive() {
        if (compensationDrivesTheEq) applyCompensation()
    }

    fun saveProfile(name: String) {
        val state = _compensation.value
        val audiogram = state.audiogram ?: return
        val result = state.result ?: return
        val profile = CompensationProfile(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Profile ${state.profiles.size + 1}" },
            createdAtMillis = System.currentTimeMillis(),
            audiogram = audiogram,
            calibrationPresetId = state.presetId,
            ancMode = AncMode.UNKNOWN,
            intensity = state.intensity,
            partialFactor = DEFAULT_PARTIAL_FACTOR,
            eq = result.eq,
        )
        viewModelScope.launch {
            profileStore.save(profile)
            store.setActiveProfileId(profile.id)
        }
    }

    /** Restores a saved profile: its snapshot wins over the current test data. */
    fun loadProfile(profile: CompensationProfile) {
        compensationDrivesTheEq = true
        _bypass.value = false
        update {
            it.copy(
                audiogram = profile.audiogram,
                presetId = profile.calibrationPresetId,
                intensity = profile.intensity,
            )
        }
        commit(profile.eq.copy(enabled = true))
        viewModelScope.launch { store.setActiveProfileId(profile.id) }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            profileStore.delete(id)
            if (_compensation.value.activeProfileId == id) store.setActiveProfileId(null)
        }
    }

    // ---- internals -----------------------------------------------------------

    /** Applies [transform], then recomputes the preview and the applied flag. */
    private fun update(transform: (CompensationUiState) -> CompensationUiState) {
        val next = transform(_compensation.value)
        val audiogram = next.audiogram
        val result = if (audiogram == null) null else calculator.computeDetailed(
            audiogram = audiogram,
            calibrationPresetId = next.presetId,
            intensity = next.intensity,
            partialFactor = DEFAULT_PARTIAL_FACTOR,
        )
        _compensation.value = next.copy(result = result).withAppliedFlag(_settings.value)
    }

    private fun CompensationUiState.withAppliedFlag(live: EqSettings): CompensationUiState {
        val preview = result?.eq ?: return copy(applied = false)
        val same = preview.leftGainsDb == live.leftGainsDb &&
            preview.rightGainsDb == live.rightGainsDb
        return copy(applied = same)
    }

    private fun commit(value: EqSettings) {
        val clean = value.sanitized()
        _settings.value = clean
        _compensation.value = _compensation.value.withAppliedFlag(clean)
        pushLive(effective(clean, _bypass.value))
        viewModelScope.launch { store.save(clean) }
    }

    private fun effective(value: EqSettings, bypass: Boolean): EqSettings =
        if (!bypass) value else EqSettings(
            enabled = value.enabled,
            preGainDb = value.preGainDb,          // matched loudness
            limiterEnabled = value.limiterEnabled,
        )

    private fun pushLive(value: EqSettings) {
        // update() re-uses the existing attachment and falls back to a full
        // attach only when nothing is attached yet — cheap enough for drags.
        if (value.enabled) controller.update(value) else controller.deactivate()
    }
}

private fun List<Float>.replaceAt(index: Int, value: Float): List<Float> =
    toMutableList().also { it[index] = value }
