package dev.dankyeeter.btdashboard.ui.screens.eq

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.audio.eq.MediaVolumeSource
import dev.dankyeeter.btdashboard.audio.eq.withVolumeTilt
import dev.dankyeeter.btdashboard.hearing.AncMode
import dev.dankyeeter.btdashboard.hearing.AdjustedReference
import dev.dankyeeter.btdashboard.hearing.Audiogram
import dev.dankyeeter.btdashboard.hearing.asRelativeLossHl
import dev.dankyeeter.btdashboard.hearing.CalibrationPreset
import dev.dankyeeter.btdashboard.hearing.CalibrationPresetRepository
import dev.dankyeeter.btdashboard.hearing.ClinicalAudiogram
import dev.dankyeeter.btdashboard.hearing.CompensationProfile
import dev.dankyeeter.btdashboard.hearing.CompensationResult
import dev.dankyeeter.btdashboard.hearing.CompensationSource
import dev.dankyeeter.btdashboard.hearing.DEFAULT_INTENSITY
import dev.dankyeeter.btdashboard.hearing.DEFAULT_PARTIAL_FACTOR
import dev.dankyeeter.btdashboard.hearing.DerivedCalibration
import dev.dankyeeter.btdashboard.hearing.HearingGraph
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceProfile
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
    /** The ENT result, if one was entered on the hearing screen. */
    val clinical: ClinicalAudiogram? = null,
    /** Which thresholds the user picked. Only offered while [clinicalAvailable]. */
    val source: CompensationSource = CompensationSource.MEASURED,
    /**
     * The calibration derived for the headphone that is connected, or null.
     *
     * Held rather than folded into [presetId] on adoption, so that the adoption
     * stays a *rule* ([activePresetId]) instead of a mutation that some later
     * emission can undo — the run collector rewrites [presetId] from whatever
     * the last run was stamped with, and a derived id written into the field
     * would silently lose that race.
     */
    val derivedForDevice: DerivedCalibration? = null,
    /**
     * The listening-preference curve stored for the headphone that is
     * connected, or null.
     *
     * Held for the same reason [derivedForDevice] is, and resolved by the same
     * rule: a preference is a judgement made *through* one pair of headphones,
     * so it belongs to that pair and to no other.
     */
    val preferenceForDevice: PreferenceProfile? = null,
    /**
     * Preference curves belonging to other headphones.
     *
     * Carried rather than dropped so the menu can name them. A curve the user
     * spent ten comparisons a song on, silently absent from the one list that
     * shows curves, reads as data the app lost.
     */
    val otherPreferences: List<PreferenceProfile> = emptyList(),
) {
    /**
     * The preset in force, named for the readout.
     *
     * Resolved through [activePresetId] rather than [presetId] so that the row
     * on screen and the numbers in the curve can never disagree: the clinical
     * source computes with no device correction, and a derived calibration is
     * adopted over the generic one.
     */
    val preset: CalibrationPreset?
        get() = presets.firstOrNull { it.id == activePresetId }

    /** True while the generated profile is active, i.e. the curve is read-only. */
    val adjustedReferenceActive: Boolean
        get() = activeProfileId == AdjustedReference.ID

    /**
     * True while a preference curve is the active preset.
     *
     * Asked of the id's shape rather than of [preferenceForDevice], because
     * what the EQ is playing does not change when the headphone does: unplug
     * the pair a preference curve was selected for and that curve is still the
     * one in the effect, so the row still has to name it.
     */
    val preferenceActive: Boolean
        get() = PreferenceProfile.isPreferenceId(activeProfileId)

    /** The id [preferenceForDevice] is selected under, or null when none is stored. */
    val preferencePresetId: String?
        get() = preferenceForDevice?.let { PreferenceProfile.presetIdFor(it.deviceKey) }

    val clinicalAvailable: Boolean get() = clinical?.isEmpty == false

    /**
     * The source actually in force.
     *
     * A stored choice of [CompensationSource.CLINICAL] falls back to the
     * measured curve when the clinical audiogram has since been deleted. The
     * fallback lives here rather than in the store so that one property answers
     * for both the computation and the label on screen — a screen that said
     * "Clinical audiogram" over a curve built from runs would be the worst of
     * the available failures.
     */
    val effectiveSource: CompensationSource
        get() = if (clinicalAvailable) source else CompensationSource.MEASURED

    /**
     * The thresholds the prescription is computed from right now.
     *
     * See [ClinicalAudiogram.prescriptionThresholdsDbHl] for the unit mapping:
     * an ENT form is already in NAL-R's own units, so the values go in
     * unconverted.
     */
    val activeAudiogram: Audiogram?
        get() = when (effectiveSource) {
            CompensationSource.CLINICAL -> clinical?.toAudiogram()
            // Rebased into the loss frame NAL-R takes — see asRelativeLossHl
            // for why the raw dBFS values used to flatten every prescription.
            CompensationSource.MEASURED -> audiogram?.asRelativeLossHl()
        }

    /**
     * The calibration preset to compute with.
     *
     * Forced to generic — all offsets zero — for the clinical source. The
     * preset undoes a headphone's own frequency response, and a clinical
     * audiogram never went through a headphone: subtracting one there would
     * corrupt a calibrated measurement with a correction for hardware that was
     * not in the path.
     */
    val activePresetId: String
        get() = when (effectiveSource) {
            CompensationSource.CLINICAL -> CalibrationPresetRepository.GENERIC_ID
            CompensationSource.MEASURED -> adoptedPresetId
        }

    /**
     * [presetId], unless a calibration derived for this very headphone can take
     * the place of no calibration at all.
     *
     * The same rule hardware detection follows (see the
     * [dev.dankyeeter.btdashboard.ui.DetectedDeviceRepository] collector):
     * adopted **only** over [CalibrationPresetRepository.GENERIC_ID]. A preset
     * the user chose for this headphone, or the one a run was actually measured
     * with, always wins — silently reinterpreting a measurement through a
     * different correction than the one it was taken with is the failure this
     * whole area is guarding against.
     *
     * Over generic, though, adopting is plainly right: generic means "no
     * correction was available", and one measured on these ears now is.
     */
    private val adoptedPresetId: String
        get() = if (presetId == CalibrationPresetRepository.GENERIC_ID && derivedForDevice != null) {
            DerivedCalibration.presetIdFor(derivedForDevice.deviceKey)
        } else {
            presetId
        }

    /** True when the active source is a clinical audiogram with no loss in it. */
    val clinicalPrescribesNothing: Boolean
        get() = effectiveSource == CompensationSource.CLINICAL &&
            clinical?.prescribesNothing == true

    /**
     * Whether the generated curve has anything to stand on.
     *
     * Three runs, *or* a clinical audiogram. The run threshold exists because a
     * median of one self-test run is not a reference; a calibrated audiogram
     * from a practice already is one, and demanding three headphone runs on top
     * of it would gate the better measurement behind the worse.
     */
    val adjustedReferenceReady: Boolean
        get() = runCount >= AdjustedReference.REQUIRED_RUNS ||
            (effectiveSource == CompensationSource.CLINICAL && activeAudiogram != null)
}

/**
 * @param mediaVolume where the volume-aware tilt reads the listening level
 *   from. Injectable — with a default that is the process-wide monitor — so the
 *   rule "a volume change re-derives the gains" can be tested by moving a
 *   number, rather than by standing up an `AudioManager` and hoping the test
 *   framework delivers a settings notification.
 */
class EqViewModel(
    private val mediaVolume: MediaVolumeSource = SystemGraph.mediaVolume,
) : ViewModel() {

    private val store = SystemGraph.settingsStore
    private val controller = SystemGraph.eqController
    private val audiogramStore = HearingGraph.audiogramStore
    private val profileStore = HearingGraph.profileStore
    private val preferenceStore = HearingGraph.preferenceStore
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
                // The stored curve arrives without its tilt layer — the layer is
                // derived, not persisted — so it is filled in here, before
                // anything reads the settings or compares them against a
                // preview. Sanitised with it, because a tilt without the
                // headroom it costs is not a state this screen may show.
                _settings.value = withTilt(loaded).sanitized()
                _compensation.value = _compensation.value.withAppliedFlag(loaded)
                settingsLoaded = true
                syncGeneratedCurve()
            }
        }
        viewModelScope.launch {
            // The volume moved: re-derive the tilt and push it, without saving.
            // Saving would be wrong twice over — the layer is not persisted, and
            // a DataStore write per volume step is a write nobody asked for.
            mediaVolume.fraction.collect { fraction ->
                val current = _settings.value
                if (!current.volumeAwareTilt) return@collect
                val next = withTilt(current, fraction).sanitized()
                // Quantised gains mean most steps land on the curve that is
                // already playing; comparing keeps those steps silent instead of
                // rewriting every band of a live effect.
                if (next == current) return@collect
                _settings.value = next
                pushLive(effective(next, _bypass.value))
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
            // Not folded into the combine above: the clinical audiogram is not
            // a property of the connected headphone, so it must not be
            // re-resolved when the device changes.
            combine(
                audiogramStore.clinicalAudiogram,
                audiogramStore.compensationSource,
            ) { clinical, source -> clinical to source }
                .collect { (clinical, source) ->
                    update { it.copy(clinical = clinical, source = source) }
                    syncGeneratedCurve()
                }
        }
        viewModelScope.launch {
            // Derived calibrations follow the connected headphone, like the runs
            // above and unlike the clinical audiogram: one is a property of ear
            // plus driver, the other of the ears alone.
            combine(
                audiogramStore.derivedCalibrations,
                MonitorGraph.codecSource.connectedDevicesFlow(),
            ) { derived, devices ->
                val device = devices.firstOrNull { it.isActive } ?: devices.firstOrNull()
                val key = DeviceKey.fromAddress(device?.address)
                derived to derived.firstOrNull { it.deviceKey == key }
            }.collect { (all, forDevice) ->
                // Fed here as well as in HearingGraph.init, and idempotently, for
                // the same ordering reason syncGeneratedCurve() guards against:
                // both collectors hang off the same DataStore and neither
                // promises to arrive first, so reading all() before the graph's
                // own collector had run would leave the new preset out of the
                // list that is about to be shown.
                HearingGraph.presets.setDerived(all)
                update { it.copy(presets = HearingGraph.presets.all(), derivedForDevice = forDevice) }
                syncGeneratedCurve()
            }
        }
        viewModelScope.launch {
            profileStore.profiles.collect { list -> update { it.copy(profiles = list) } }
        }
        viewModelScope.launch {
            // Preference curves follow the connected headphone for the reason
            // [PreferenceProfile] gives: the listener said "more bass" while
            // hearing one pair's own low end, so the answer means nothing on
            // another pair. The rest of the list travels with it, unresolved,
            // so the menu can name what is stored elsewhere instead of hiding it.
            combine(
                preferenceStore.profiles,
                MonitorGraph.codecSource.connectedDevicesFlow(),
            ) { profiles, devices ->
                val device = devices.firstOrNull { it.isActive } ?: devices.firstOrNull()
                val key = DeviceKey.fromAddress(device?.address)
                profiles.firstOrNull { it.deviceKey == key } to
                    profiles.filterNot { it.deviceKey == key }
            }.collect { (forDevice, others) ->
                update { it.copy(preferenceForDevice = forDevice, otherPreferences = others) }
            }
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
     * Boosts act on quiet passages only; loud passages pass as recorded.
     *
     * sanitized() recomputes the headroom on commit: in this mode boosts live
     * in the compressor and are gone again by full scale, so the automatic
     * pre-gain lets go of them — switching this on audibly restores the level
     * a boosted static curve had traded away.
     */
    fun setLoudnessRestoration(enabled: Boolean) {
        val current = _settings.value
        commit(
            current.copy(
                loudnessRestoration = enabled,
                preGainDb = if (current.autoHeadroom) {
                    if (enabled) 0f else EqSettings.headroomFor(current.leftGainsDb, current.rightGainsDb)
                } else {
                    current.preGainDb
                },
            ),
        )
    }

    /**
     * Switches the ISO 226 volume-aware tilt on or off.
     *
     * Nothing else to do: [commit] derives the layer for the volume that is set
     * right now, and switching off writes it back as zeros, so the effect
     * returns to exactly the curve it had before.
     */
    fun setVolumeAwareTilt(enabled: Boolean) =
        commit(_settings.value.copy(volumeAwareTilt = enabled))

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

    // No setter for the calibration preset. It is not a choice made here: the
    // id comes from the run the audiogram was measured with, or from hardware
    // detection, and letting the EQ screen override it would reinterpret a
    // measurement through the wrong headphone's correction.

    fun setIntensity(value: Float) = update { it.copy(intensity = value.coerceIn(0f, 1f)) }

    /**
     * Switches the compensation between the headphone measurement and the
     * clinical audiogram.
     *
     * Persisted rather than kept in the ViewModel: it decides what the EQ does,
     * and a choice that quietly reverted on the next launch would swap the
     * curve underneath the listener. The preview follows immediately; the live
     * EQ follows only where it already did — through [applyCompensationIfActive],
     * so a comparison in progress is not cancelled by a source switch.
     */
    fun setCompensationSource(source: CompensationSource) {
        update { it.copy(source = source) }
        applyCompensationIfActive()
        viewModelScope.launch { audiogramStore.setCompensationSource(source) }
    }

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
     * Switches to the preference curve stored for the connected headphone.
     *
     * Built by [PreferenceProfile.toEqSettings], which is the same call the
     * preference card's own Save makes. That is the point: what a preference
     * profile sounds like — the pool's aggregate, the base curve it was judged
     * on top of, and the hand adjustment that overrides the pool per axis — is
     * decided in one place, so the two ways of reaching it cannot drift into
     * two different curves.
     *
     * Nothing is written back to the profile, so a hand adjustment survives
     * being selected here exactly as it survives being saved: this is a
     * selection, not an edit.
     *
     * Refused when no curve is stored for this headphone. The menu greys those
     * entries, but a rule that only lived in the menu would not be a rule.
     */
    fun selectPreference() {
        val profile = _compensation.value.preferenceForDevice ?: return
        // The curve is a taste setting, not a prescription: nothing about it
        // should be recomputed when the intensity slider moves.
        compensationDrivesTheEq = false
        _bypass.value = false
        commit(profile.toEqSettings(_settings.value))
        viewModelScope.launch {
            store.setActiveProfileId(PreferenceProfile.presetIdFor(profile.deviceKey))
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
        // The new preset becomes the active one here, before anything is
        // written. setActiveProfileId only lands one coroutine later, and
        // commit() does not wait for it: it saves, the settings flow comes
        // straight back, and syncGeneratedCurve() then acts on whatever
        // activeProfileId still says. Coming from the Personal Reference that
        // was still its id, so the generated curve was written back over the
        // flat preset the user had just asked for — a new EQ that arrived
        // already full of someone's hearing correction.
        update { it.copy(activeProfileId = profile.id) }
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
        // Whichever source is in force, with its own preset rule; see
        // [CompensationUiState.activeAudiogram] and [activePresetId].
        val audiogram = next.activeAudiogram
        val result = if (audiogram == null) null else calculator.computeDetailed(
            audiogram = audiogram,
            calibrationPresetId = next.activePresetId,
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

    /**
     * The tilt layer for [value], derived from the media volume.
     *
     * Every path that changes the settings goes through here, which is what
     * keeps the derived layer in step with the layout, the switch and the
     * volume at the same time — a layout change alone would otherwise leave a
     * tilt list of the wrong length behind, and the model refuses that outright.
     */
    private fun withTilt(value: EqSettings, fraction: Float = mediaVolume.fraction.value): EqSettings {
        val tilted = value.withVolumeTilt(fraction)
        if (tilted.tiltGainsDb == value.tiltGainsDb || !tilted.autoHeadroom) return tilted
        // The tilt just changed size, so the headroom it bought has to be
        // recomputed rather than kept. `sanitized()` only ever *deepens* the
        // pre-gain — that is deliberate, so a manually set headroom survives a
        // slider drag — which means turning the volume back up, or switching the
        // feature off, would otherwise leave the music 12 dB down with nothing
        // boosted to justify it. Zeroing here lets sanitized() derive the
        // correct value from the curve that is actually playing; every other
        // path still keeps whatever headroom it had.
        return tilted.copy(preGainDb = 0f)
    }

    private fun commit(value: EqSettings) {
        val clean = withTilt(value).sanitized()
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
        // A copy of the live state with the bands zeroed, not a fresh
        // EqSettings. Built from scratch it carried the *default* layout, so on
        // any other layout every A/B toggle changed the band count — and a band
        // count change forces the controller to release DynamicsProcessing and
        // build a new one, which is an audible dropout in the middle of the very
        // comparison being made, and leaves the EQ dead when the rebuild fails.
        // The same omission dropped autoHeadroom and loudnessRestoration, the
        // two fields the matched-loudness claim below actually rests on.
        // The tilt goes flat with the bands. "Compare with EQ off" promises the
        // music untouched, and a correction that survived the comparison would
        // be on both sides of it.
        if (!bypass) value else value.copy(
            leftGainsDb = List(value.layout.bandCount) { 0f },
            rightGainsDb = List(value.layout.bandCount) { 0f },
            tiltGainsDb = List(value.layout.bandCount) { 0f },
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
