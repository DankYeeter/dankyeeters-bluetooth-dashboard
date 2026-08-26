package dev.dankyeeter.btdashboard.ui.screens.eq

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.hearing.AncMode
import dev.dankyeeter.btdashboard.hearing.AdjustedReference
import dev.dankyeeter.btdashboard.hearing.Audiogram
import dev.dankyeeter.btdashboard.hearing.CalibrationPreset
import dev.dankyeeter.btdashboard.hearing.CalibrationPresetRepository
import dev.dankyeeter.btdashboard.hearing.CompensationProfile
import dev.dankyeeter.btdashboard.hearing.CompensationResult
import dev.dankyeeter.btdashboard.hearing.DEFAULT_INTENSITY
import dev.dankyeeter.btdashboard.hearing.DEFAULT_PARTIAL_FACTOR
import dev.dankyeeter.btdashboard.hearing.HearingGraph
import dev.dankyeeter.btdashboard.hearing.store.AudiogramStore
import dev.dankyeeter.btdashboard.monitor.MonitorGraph
import dev.dankyeeter.btdashboard.system.devices.DeviceKey
import kotlinx.coroutines.flow.combine
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

    /** True while the generated profile is active, i.e. the curve is read-only. */
    val adjustedReferenceActive: Boolean
        get() = activeProfileId == AdjustedReference.ID

    /** Whether enough runs exist for the generated curve to stand on anything. */
    val adjustedReferenceReady: Boolean
        get() = runCount >= AdjustedReference.REQUIRED_RUNS

    /** How many more runs are needed; zero once [adjustedReferenceReady]. */
    val runsStillNeeded: Int
        get() = (AdjustedReference.REQUIRED_RUNS - runCount).coerceAtLeast(0)
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
                settingsLoaded = true
                syncGeneratedCurve()
            }
        }
        viewModelScope.launch {
            // The personal curve follows the connected headphone: only runs
            // measured through it feed the median, because a hearing curve is
            // a property of ear plus driver together. Swap headphones and the
            // correction swaps with them.
            combine(
                audiogramStore.runs,
                audiogramStore.selectedRunIds,
                MonitorGraph.codecSource.connectedDevicesFlow(),
            ) { all, ids, devices ->
                val device = devices.firstOrNull { it.isActive } ?: devices.firstOrNull()
                AudiogramStore.selectionOf(all, ids, DeviceKey.fromAddress(device?.address))
            }.collect { runs ->
                val audiogram = if (runs.isEmpty()) null else HearingGraph.aggregator.aggregate(runs)
                // Adopt the preset the test was actually run with; mixing a
                // curve measured through one device with another device's
                // correction would be meaningless.
                val presetId = runs.lastOrNull()?.calibrationPresetId ?: _compensation.value.presetId
                update {
                    it.copy(audiogram = audiogram, runCount = runs.size, presetId = presetId)
                }
                syncGeneratedCurve()
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
            store.activeProfileId.collect { id ->
                update { it.copy(activeProfileId = id) }
                syncGeneratedCurve()
            }
        }
    }

    // ---- manual band editing -------------------------------------------------

    /**
     * Whether the active profile owns its own curve.
     *
     * Checked here rather than only in the UI on purpose. Disabled sliders are
     * a hint to the user; this is the actual rule, and it holds for every other
     * caller — a restored state, a future automation, a stale recomposition
     * arriving after the profile switched.
     */
    private fun curveIsGenerated(): Boolean = _compensation.value.adjustedReferenceActive

    fun setBandGain(ear: Ear, bandIndex: Int, gainDb: Float) {
        if (curveIsGenerated()) return
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
        if (curveIsGenerated()) return
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
     * Turns the automatic headroom on or off, and sets the pre-gain to match.
     *
     * On: the whole signal is lowered by whatever the loudest band was raised,
     * so nothing can overflow - the safe default. Off: the pre-gain goes to
     * zero, so dragging a band upwards is heard as louder rather than as
     * everything else becoming quieter. That is what people expect, and it is
     * also how a track can clip; the limiter stays as the second net.
     */
    fun setAutoHeadroom(enabled: Boolean) {
        val current = _settings.value
        commit(
            current.copy(
                autoHeadroom = enabled,
                preGainDb = if (enabled) {
                    EqSettings.headroomFor(current.leftGainsDb, current.rightGainsDb)
                } else {
                    0f
                },
            ),
        )
    }

    /**
     * Flat vs. compensated at matched loudness: the bands go to 0 dB but the
     * negative pre-gain and the limiter state are carried over unchanged.
     *
     * The loudness match holds only while the automatic headroom is on, which
     * is what produces that negative pre-gain in the first place. With it off
     * a boosted curve really is louder than flat - that is the point of
     * switching it off - and the screen says so rather than pretending the
     * comparison is still level-matched.
     */
    fun setBypass(bypass: Boolean) {
        _bypass.value = bypass
        pushLive(effective(_settings.value, bypass))
    }

    fun resetFlat() {
        compensationDrivesTheEq = false
        _bypass.value = false
        viewModelScope.launch { store.setActiveProfileId(null) }
        val current = _settings.value
        commit(EqSettings(enabled = current.enabled, layout = current.layout))
    }

    /**
     * Changes how many bands the EQ has. The curve is resampled onto the new
     * centres rather than reset, so trying a finer layout is free: nothing the
     * user tuned, and no compensation curve, is lost by looking.
     *
     * Refused while the generated profile is in force, for the same reason its
     * band sliders are: on the octave grid the measured 3 kHz and 6 kHz
     * thresholds reach no band at all ([AdjustedReference.LAYOUT]), so this
     * control would quietly turn a measurement into a worse measurement.
     */
    fun setBandLayout(layout: EqBandLayout) {
        if (curveIsGenerated()) return
        val current = _settings.value
        if (current.layout == layout) return
        commit(current.withLayout(layout))
        persist()
    }

    /** Persists the current curve; call on slider release. */
    fun persist() {
        viewModelScope.launch { store.save(_settings.value) }
    }

    // ---- compensation flow ---------------------------------------------------

    fun selectPreset(id: String) = update { it.copy(presetId = id) }

    fun setIntensity(value: Float) = update { it.copy(intensity = value.coerceIn(0f, 1f)) }

    /**
     * True once the live EQ came from the compensation flow rather than from
     * manual band edits; lets the intensity slider keep the curve live.
     */
    private var compensationDrivesTheEq: Boolean = false

    /** True once the persisted EQ has arrived; see [syncGeneratedCurve]. */
    private var settingsLoaded: Boolean = false

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

    /**
     * Switches to [AdjustedReference].
     *
     * Nothing is read from the profile store, because the generated profile is
     * not kept there: it *is* the current aggregate of the runs. That is what
     * makes it impossible for it to fall behind a re-test — there is no saved
     * copy that could go stale.
     *
     * Refused below the run threshold rather than shown with a thin curve: a
     * median of one run is not a reference.
     */
    fun selectAdjustedReference() {
        if (!_compensation.value.adjustedReferenceReady) return
        // Move the EQ onto the generated curve's own band grid *before*
        // applying, so what gets committed is the curve computed there rather
        // than a ten-band one stretched afterwards.
        useGeneratedLayout()
        applyCompensation()
        viewModelScope.launch { store.setActiveProfileId(AdjustedReference.ID) }
    }

    /**
     * Saves whatever is currently in force under a name.
     *
     * A hearing-test result is stored with its audiogram, so a later re-test
     * cannot change it. Without one, the live curve is saved on its own — a
     * hand-tuned EQ deserves to be nameable and recallable just as much, and
     * requiring a hearing test first made the whole preset feature unreachable
     * for anyone who just wanted to set the sliders.
     */
    fun saveProfile(name: String) {
        val state = _compensation.value
        val result = state.result
        val profile = CompensationProfile(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Preset ${state.profiles.size + 1}" },
            createdAtMillis = System.currentTimeMillis(),
            audiogram = if (result == null) null else state.audiogram,
            calibrationPresetId = state.presetId,
            ancMode = AncMode.UNKNOWN,
            intensity = state.intensity,
            partialFactor = DEFAULT_PARTIAL_FACTOR,
            eq = result?.eq ?: _settings.value,
        )
        viewModelScope.launch {
            profileStore.save(profile)
            store.setActiveProfileId(profile.id)
        }
    }

    /**
     * A new, named, flat EQ — the "Add new EQ" path.
     *
     * Deliberately flat rather than a copy of whatever is playing: a new
     * preset is a fresh sheet of paper, and starting it from the previous
     * curve would bake one preset's taste invisibly into the next. Manual
     * presets are cross-device by design — taste travels with the person,
     * only the measured Personal Reference belongs to a headphone.
     */
    fun createProfile(name: String) {
        val current = _settings.value
        val flat = current.copy(
            leftGainsDb = List(current.layout.bandCount) { 0f },
            rightGainsDb = List(current.layout.bandCount) { 0f },
            enabled = true,
        )
        val profile = CompensationProfile(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "EQ ${_compensation.value.profiles.size + 1}" },
            createdAtMillis = System.currentTimeMillis(),
            audiogram = null,
            calibrationPresetId = _compensation.value.presetId,
            ancMode = AncMode.UNKNOWN,
            intensity = _compensation.value.intensity,
            partialFactor = DEFAULT_PARTIAL_FACTOR,
            eq = flat,
        )
        compensationDrivesTheEq = false
        commit(flat)
        viewModelScope.launch {
            profileStore.save(profile)
            store.setActiveProfileId(profile.id)
        }
    }

    /**
     * Writes the bands as they sound right now back into the active preset.
     *
     * Explicit rather than automatic: a slider being dragged is an experiment,
     * and an experiment that silently rewrites the preset it started from
     * leaves nothing to return to. Only manual presets accept this — the
     * Personal Reference is a measurement, and measurements are not edited.
     */
    fun saveCurrentIntoActive() {
        val state = _compensation.value
        val active = state.profiles.firstOrNull { it.id == state.activeProfileId } ?: return
        if (active.audiogram != null) return
        viewModelScope.launch { profileStore.save(active.copy(eq = _settings.value)) }
    }

    /** Restores a saved profile: its snapshot wins over the current test data. */
    fun loadProfile(profile: CompensationProfile) {
        // A hand-tuned preset carries no audiogram, so nothing should recompute
        // a compensation curve over the top of the gains it restores.
        compensationDrivesTheEq = profile.audiogram != null
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
            layout = layoutFor(next),
        )
        _compensation.value = next.copy(result = result).withAppliedFlag(_settings.value)
    }

    private fun layoutFor(state: CompensationUiState): EqBandLayout =
        compensationLayoutFor(state, _settings.value.layout)

    /** Moves the EQ onto [AdjustedReference.LAYOUT]; no-op once it is there. */
    private fun useGeneratedLayout() {
        val current = _settings.value
        if (current.layout == AdjustedReference.LAYOUT) return
        commit(current.withLayout(AdjustedReference.LAYOUT))
    }

    /**
     * Keeps the generated profile's promise while it is the active one: that it
     * *is* the current aggregate of the runs, with no saved copy in between
     * that could go stale.
     *
     * Two things can break that promise behind the user's back. A run added or
     * deleted elsewhere changes the median, and until now only the preview
     * followed — the EQ kept playing the old curve while the card claimed
     * "median of N runs". And a profile selected by an earlier build sits on the
     * ten-band grid, where two of the eight measured thresholds reach no band;
     * recomputing it here is that migration, and it happens once.
     *
     * Deliberately not routed through [applyCompensation]: this is a
     * correction, not a user action, so it must not silently cancel an A/B
     * comparison or switch the EQ on behind them.
     */
    private fun syncGeneratedCurve() {
        // Ordering guard, not an optimisation. Both flows come off the same
        // DataStore and neither promises to arrive first; writing while
        // _settings still holds the FLAT placeholder would save that placeholder
        // over the curve that is still being read from disk. Every collector
        // calls this, so whichever one arrives last does the work.
        if (!settingsLoaded) return
        if (!_compensation.value.adjustedReferenceActive) return
        if (_compensation.value.result == null) return
        useGeneratedLayout()
        if (_compensation.value.applied) return
        val generated = _compensation.value.result?.eq ?: return
        compensationDrivesTheEq = true
        commit(generated.copy(enabled = _settings.value.enabled))
    }

    private fun CompensationUiState.withAppliedFlag(live: EqSettings): CompensationUiState {
        val preview = result?.eq ?: return copy(applied = false)
        val same = preview.leftGainsDb == live.leftGainsDb &&
            preview.rightGainsDb == live.rightGainsDb
        return copy(applied = same)
    }

    private fun commit(value: EqSettings) {
        val clean = value.sanitized()
        val layoutChanged = clean.layout != _settings.value.layout
        _settings.value = clean
        // A layout change invalidates the cached preview: it was computed for
        // the old centres, so "applied" would compare lists of different sizes.
        if (layoutChanged) update { it }
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

/**
 * Which band grid the prescription is mapped onto: [live] normally, so a
 * 31-band EQ gets a 31-band curve instead of a stretched 10-band one — and
 * [AdjustedReference.LAYOUT] whenever the generated profile is the active one,
 * because there the grid is part of the measurement rather than a preference.
 *
 * A free function so the rule can be tested without an Android graph behind it.
 * It is the whole of the fix for the 3 kHz / 6 kHz blindness on the UI side;
 * the two call sites that switch the EQ's own layout only make the live EQ
 * agree with what this already decided.
 */
internal fun compensationLayoutFor(
    state: CompensationUiState,
    live: EqBandLayout,
): EqBandLayout = if (state.adjustedReferenceActive) AdjustedReference.LAYOUT else live
