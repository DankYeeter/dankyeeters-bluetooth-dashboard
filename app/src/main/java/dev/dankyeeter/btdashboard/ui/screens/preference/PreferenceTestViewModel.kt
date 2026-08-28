package dev.dankyeeter.btdashboard.ui.screens.preference

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.hearing.HearingGraph
import dev.dankyeeter.btdashboard.hearing.preference.FinalCheck
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceAggregate
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceAudition
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceCandidate
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceChoice
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceEngine
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceLabelSource
import dev.dankyeeter.btdashboard.hearing.preference.PreferencePool
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceProfile
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceRun
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceRunResult
import dev.dankyeeter.btdashboard.monitor.MonitorGraph
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.attach.PlayingApps
import dev.dankyeeter.btdashboard.system.devices.DeviceKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/** Where the listener is in the preference flow. */
enum class PreferencePhase {
    /** The card on Sound Profiling. */
    IDLE,

    /** A song-run in progress: ten A/B comparisons. */
    RUNNING,

    /** One song finished: its answer, its label, and what to do next. */
    RUN_RESULT,

    /** The pool's curve against flat, once, blind. */
    FINAL_CHECK,

    /** The pool, the curve, the sliders, save or discard. */
    RESULT,
}

/** Which of the two curves is playing right now. */
enum class AbSlot { A, B }

/**
 * Everything the preference screens draw, and the rules they draw it by.
 *
 * The rules live here rather than in the composables so they can be tested
 * without a device, a DataStore or an audio effect — the same reason
 * `CompensationUiState` carries the preset-adoption rule.
 */
data class PreferenceUiState(
    val phase: PreferencePhase = PreferencePhase.IDLE,
    val deviceKey: String? = null,
    val deviceName: String? = null,
    /** The profile on disk for the connected headphone, or null. */
    val stored: PreferenceProfile? = null,
    /** The working copy while a test is in progress; null outside one. */
    val draft: PreferenceProfile? = null,
    /** Profiles belonging to other headphones — shown, never editable. */
    val otherProfiles: List<PreferenceProfile> = emptyList(),
    val trial: PreferenceEngine.Step.Compare? = null,
    val playing: AbSlot = AbSlot.A,
    val runResult: PreferenceRunResult? = null,
    val runLabel: TrackLabel = TrackLabel("", PreferenceLabelSource.NONE),
    val typedLabel: String = "",
    val message: String? = null,
    val confirmingCancel: Boolean = false,
    /** A finished run waiting on the "your adjustment will go" question. */
    val pendingRun: PreferenceRun? = null,
    /** Whether answering that question ends the test or starts another song. */
    val nextIsFinish: Boolean = false,
    val saved: Boolean = false,
) {
    /** The profile the screens read: the working copy while one exists. */
    val active: PreferenceProfile? get() = draft ?: stored

    val aggregate: PreferenceAggregate get() = active?.aggregate ?: PreferenceAggregate.EMPTY

    val candidate: PreferenceCandidate get() = active?.candidate ?: PreferenceCandidate.NEUTRAL

    val runs: List<PreferenceRun> get() = active?.runs.orEmpty()

    /**
     * Whether a test can be started at all.
     *
     * A headphone has to be connected, for the reason
     * [PreferenceProfile] gives: the answer is a judgement made *through* a
     * device, and with nothing connected there is no identity to store it
     * against. Refused before the first comparison rather than after the tenth
     * — ten judgements followed by "this cannot be saved" is cruel.
     */
    val canStart: Boolean get() = deviceKey != null

    /**
     * True while there is something unsaved worth losing.
     *
     * A draft that differs from what is stored is not enough on its own: the
     * very first thing [PreferenceTestViewModel.start] does is create an empty
     * draft, and treating that as unsaved work would mean cancelling the first
     * comparison of the first song dropped the listener onto a result screen
     * with an empty pool on it.
     */
    val dirty: Boolean
        get() = draft != null && draft != stored && (draft.runs.isNotEmpty() || draft.handAdjusted)

    /** The pool is full; a further song would push the oldest out. */
    val poolFull: Boolean get() = runs.size >= PreferencePool.MAX_RUNS

    val progress: Float
        get() = trial?.let { (it.index.toFloat() / it.total).coerceIn(0f, 1f) } ?: 0f
}

/**
 * Runs the preference test: one song-run at a time, over whatever the listener
 * is already playing.
 *
 * ## What it does to the sound, and what it deliberately does not
 *
 * The test plays over the listener's own music through the system EQ, with their
 * normal layers **still active underneath** — compensation curve, manual preset,
 * whatever is in force — and the candidate shelf riding on top. That is the
 * choice with the most consequences in this file, so: the question being asked
 * is "which of these do you prefer", and the only version of that question worth
 * answering is the one asked about the sound they actually listen to. Stripping
 * their correction away to test against a flat baseline would measure a
 * preference for a sound they never hear, and the shelf that came out would be
 * an offset from the wrong starting point.
 *
 * The cost is that the answer is only meaningful next to that baseline, which is
 * why the baseline is captured once per pool and stored with it — see
 * [PreferenceProfile].
 *
 * Candidates are switched through the same live path the EQ screen's A/B bypass
 * uses ([dev.dankyeeter.btdashboard.system.attach.EqController.update]), which
 * re-uses the existing attachment and rewrites the band gains in place rather
 * than rebuilding the effect. Rebuilding is an audible dropout, and a dropout in
 * the middle of a comparison is a difference the listener will hear and cannot
 * unhear.
 */
class PreferenceTestViewModel(application: Application) : AndroidViewModel(application),
    PreferenceTestActions {

    private val settingsStore = SystemGraph.settingsStore
    private val controller = SystemGraph.eqController
    private val store = HearingGraph.preferenceStore

    private val _state = MutableStateFlow(PreferenceUiState())
    val state: StateFlow<PreferenceUiState> = _state.asStateFlow()

    private var engine: PreferenceEngine? = null

    /**
     * The live EQ exactly as it stood when the test started.
     *
     * Kept so that cancelling, discarding, or the screen going away puts the
     * listener's sound back byte for byte. Anything less would leave a test
     * candidate playing after a test nobody finished.
     */
    private var restoreTo: EqSettings? = null

    /** The finished pool's curve against flat, while the final check runs. */
    private var finalCheckAIsYours: Boolean = true

    init {
        viewModelScope.launch {
            combine(
                store.profiles,
                MonitorGraph.codecSource.connectedDevicesFlow(),
            ) { profiles, devices ->
                val device = devices.firstOrNull { it.isActive } ?: devices.firstOrNull()
                Triple(profiles, DeviceKey.fromAddress(device?.address), device?.name)
            }.collect { (profiles, key, name) ->
                _state.value = _state.value.copy(
                    deviceKey = key,
                    deviceName = name,
                    stored = profiles.firstOrNull { it.deviceKey == key },
                    otherProfiles = profiles.filterNot { it.deviceKey == key },
                )
            }
        }
    }

    // ---- starting ------------------------------------------------------------

    override fun start() {
        val current = _state.value
        val key = current.deviceKey ?: return message(
            "Connect the headphones you want to tune. A preference curve belongs to one " +
                "headphone, so there has to be one to store it against.",
        )
        viewModelScope.launch {
            val live = settingsStore.current()
            if (restoreTo == null) restoreTo = live
            val draft = current.draft ?: current.stored ?: freshProfile(key, current.deviceName, live)
            engine = PreferenceEngine(
                carryOver = PreferencePool.carryOverPairs(draft.runs),
                // Later songs start where the pool already stands, so the run
                // spends its ten comparisons refining an answer instead of
                // re-deriving one it already has.
                startingEstimate = draft.aggregate.candidate,
            )
            _state.value = _state.value.copy(
                phase = PreferencePhase.RUNNING,
                draft = draft,
                runResult = null,
                typedLabel = "",
                message = null,
                saved = false,
            )
            advance()
        }
    }

    private fun freshProfile(key: String, name: String?, live: EqSettings) = PreferenceProfile(
        deviceKey = key,
        deviceName = name,
        layout = live.layout,
        baseLeftDb = live.leftGainsDb,
        baseRightDb = live.rightGainsDb,
        createdAtMillis = System.currentTimeMillis(),
        updatedAtMillis = System.currentTimeMillis(),
    )

    // ---- one comparison ------------------------------------------------------

    override fun play(slot: AbSlot) {
        val trial = _state.value.trial ?: return
        _state.value = _state.value.copy(playing = slot)
        audition(if (slot == AbSlot.A) trial.a else trial.b)
    }

    override fun confirm() {
        record(if (_state.value.playing == AbSlot.A) PreferenceChoice.A else PreferenceChoice.B)
    }

    override fun noDifference() = record(PreferenceChoice.NO_DIFFERENCE)

    private fun record(choice: PreferenceChoice) {
        val active = engine ?: return
        if (_state.value.trial == null) return
        active.record(choice)
        advance()
    }

    private fun advance() {
        when (val step = engine?.next()) {
            is PreferenceEngine.Step.Compare -> {
                // Every trial opens on A. Not on whatever was playing: carrying
                // the previous trial's side over would mean the first thing the
                // listener hears is already one of the two answers.
                _state.value = _state.value.copy(trial = step, playing = AbSlot.A)
                audition(step.a)
            }
            is PreferenceEngine.Step.Finished -> finishRun(step.result)
            null -> Unit
        }
    }

    private fun finishRun(result: PreferenceRunResult) {
        engine = null
        val label = PreferenceTrackLabel.resolve(
            appNames = playingAppNames(),
            timeLabel = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()),
        )
        _state.value = _state.value.copy(
            phase = PreferencePhase.RUN_RESULT,
            trial = null,
            runResult = result,
            runLabel = label,
            typedLabel = "",
        )
        // Back to the pool's own curve while the listener reads the result, so
        // the last comparison's candidate is not left playing.
        applyPreview()
    }

    // ---- the run's result ----------------------------------------------------

    override fun setRunLabel(text: String) {
        _state.value = _state.value.copy(typedLabel = text)
    }

    override fun addAnotherSong() {
        commitRun(thenFinish = false)
    }

    override fun finish() {
        commitRun(thenFinish = true)
    }

    /**
     * Folds the finished run into the draft pool.
     *
     * A hand-adjusted curve is never overwritten silently: the adjustment is the
     * listener's explicit last word, and a new song quietly replacing it is the
     * dirty-rule failure the clinical editor exists to prevent. So the run waits
     * in [PreferenceUiState.pendingRun] and the screen asks.
     */
    private fun commitRun(thenFinish: Boolean) {
        val current = _state.value
        val result = current.runResult ?: return
        val draft = current.draft ?: return
        val label = PreferenceTrackLabel.manual(current.typedLabel, current.runLabel)
        val run = PreferenceRun(
            id = UUID.randomUUID().toString(),
            label = label.text,
            labelSource = label.source,
            createdAtMillis = System.currentTimeMillis(),
            candidate = result.candidate,
            consistency = result.consistency,
            trials = result.trials,
        )
        if (draft.handAdjusted) {
            _state.value = current.copy(pendingRun = run, nextIsFinish = thenFinish)
            return
        }
        applyRun(run, keepAdjustment = false, thenFinish = thenFinish)
    }

    override fun keepAdjustment() {
        val run = _state.value.pendingRun ?: return
        applyRun(run, keepAdjustment = true, thenFinish = _state.value.nextIsFinish)
    }

    override fun useNewMeasurement() {
        val run = _state.value.pendingRun ?: return
        applyRun(run, keepAdjustment = false, thenFinish = _state.value.nextIsFinish)
    }

    private fun applyRun(run: PreferenceRun, keepAdjustment: Boolean, thenFinish: Boolean) {
        val draft = _state.value.draft ?: return
        val updated = draft.withRun(run, System.currentTimeMillis()).let {
            if (keepAdjustment) it else it.copy(manualBassDb = null, manualTrebleDb = null)
        }
        _state.value = _state.value.copy(draft = updated, pendingRun = null, runResult = null)
        applyPreview()
        if (thenFinish) beginFinalCheck() else start()
    }

    // ---- the blind check -----------------------------------------------------

    /**
     * One comparison of the pool's curve against flat, at matched loudness.
     *
     * Skipped when the answer is already "no preference": asking somebody to
     * choose between flat and something within a decibel of flat is asking them
     * to guess, and the guess would then be reported as a verdict.
     */
    private fun beginFinalCheck() {
        val draft = _state.value.draft ?: return
        if (draft.aggregate.neutral) {
            _state.value = _state.value.copy(phase = PreferencePhase.RESULT)
            applyPreview()
            return
        }
        finalCheckAIsYours = kotlin.random.Random.nextBoolean()
        _state.value = _state.value.copy(phase = PreferencePhase.FINAL_CHECK, playing = AbSlot.A)
        audition(if (finalCheckAIsYours) draft.candidate else PreferenceCandidate.NEUTRAL)
    }

    override fun playFinalCheck(slot: AbSlot) {
        val draft = _state.value.draft ?: return
        val yours = (slot == AbSlot.A) == finalCheckAIsYours
        _state.value = _state.value.copy(playing = slot)
        audition(if (yours) draft.candidate else PreferenceCandidate.NEUTRAL)
    }

    override fun answerFinalCheck(slot: AbSlot?) {
        val draft = _state.value.draft ?: return
        val outcome = when {
            slot == null -> FinalCheck.NO_DIFFERENCE
            (slot == AbSlot.A) == finalCheckAIsYours -> FinalCheck.YOURS_WON
            else -> FinalCheck.FLAT_WON
        }
        _state.value = _state.value.copy(
            phase = PreferencePhase.RESULT,
            draft = draft.copy(finalCheck = outcome),
        )
        applyPreview()
    }

    override fun skipFinalCheck() {
        val draft = _state.value.draft ?: return
        _state.value = _state.value.copy(
            phase = PreferencePhase.RESULT,
            draft = draft.copy(finalCheck = FinalCheck.NOT_RUN),
        )
        applyPreview()
    }

    // ---- the result ----------------------------------------------------------

    override fun setBassDb(db: Float) = adjust { it.copy(manualBassDb = db) }

    override fun setTrebleDb(db: Float) = adjust { it.copy(manualTrebleDb = db) }

    private fun adjust(transform: (PreferenceProfile) -> PreferenceProfile) {
        val draft = _state.value.draft ?: _state.value.stored ?: return
        _state.value = _state.value.copy(
            draft = transform(draft).copy(updatedAtMillis = System.currentTimeMillis()),
            saved = false,
        )
        applyPreview()
    }

    override fun clearAdjustment() =
        adjust { it.copy(manualBassDb = null, manualTrebleDb = null) }

    override fun removeRun(id: String) {
        val draft = _state.value.draft ?: _state.value.stored ?: return
        _state.value = _state.value.copy(
            draft = draft.withoutRun(id, System.currentTimeMillis()),
            saved = false,
        )
        applyPreview()
    }

    /**
     * Opens the stored curve to look at or edit. Does not touch the sound: a
     * screen that changed the EQ merely by being opened would be a surprise.
     */
    override fun openResult() {
        val existing = _state.value.stored ?: return
        viewModelScope.launch {
            // Read before the phase moves, so a slider dragged on the first
            // frame has something to restore against — the preview refuses to
            // act without it.
            if (restoreTo == null) restoreTo = settingsStore.current()
            _state.value = _state.value.copy(
                phase = PreferencePhase.RESULT,
                draft = existing,
                saved = true,
            )
        }
    }

    /**
     * Stores the profile and puts it into the EQ in the same act.
     *
     * The adoption idiom the derived calibration uses: a result that has to be
     * gone and applied from somewhere else is a result most people will never
     * hear. Saved first, applied second, so a write that fails cannot leave the
     * EQ describing a profile that is not on disk.
     */
    override fun save() {
        val draft = _state.value.draft ?: return
        viewModelScope.launch {
            store.save(draft)
            val applied = draft.toEqSettings(settingsStore.current())
            settingsStore.save(applied)
            controller.update(applied)
            restoreTo = applied
            _state.value = _state.value.copy(
                phase = PreferencePhase.IDLE,
                saved = true,
                draft = null,
                message = null,
            )
        }
    }

    override fun discard() {
        _state.value = _state.value.copy(phase = PreferencePhase.IDLE, draft = null, runResult = null)
        restore()
    }

    override fun deleteProfile() {
        val key = _state.value.deviceKey ?: return
        viewModelScope.launch {
            store.delete(key)
            _state.value = _state.value.copy(phase = PreferencePhase.IDLE, draft = null)
            restore()
        }
    }

    // ---- cancelling ----------------------------------------------------------

    override fun requestCancel() {
        // Nothing at stake before the first answer, so no question is asked —
        // the same asymmetry the clinical editor's dismissal guard makes.
        val answered = _state.value.trial?.index ?: 0
        if (answered == 0 && !_state.value.dirty) {
            confirmCancel()
        } else {
            _state.value = _state.value.copy(confirmingCancel = true)
        }
    }

    override fun confirmCancel() {
        engine = null
        // Songs already finished are not lost with the one being abandoned, so
        // a pool that has anything in it lands on its result screen rather than
        // back at the card. An empty one leaves no trace at all.
        val keep = _state.value.dirty
        _state.value = _state.value.copy(
            phase = if (keep) PreferencePhase.RESULT else PreferencePhase.IDLE,
            draft = if (keep) _state.value.draft else null,
            trial = null,
            runResult = null,
            confirmingCancel = false,
        )
        if (keep) applyPreview() else restore()
    }

    override fun dismissDialog() {
        _state.value = _state.value.copy(confirmingCancel = false, pendingRun = null)
    }

    override fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    // ---- the live EQ ---------------------------------------------------------

    /** One candidate, loudness-matched, on top of the pool's own base curve. */
    private fun audition(candidate: PreferenceCandidate) {
        val draft = _state.value.draft ?: return
        val base = restoreTo ?: return
        controller.update(
            PreferenceAudition.settingsFor(
                current = base,
                candidate = candidate,
                layout = draft.layout,
                baseLeftDb = draft.baseLeftDb,
                baseRightDb = draft.baseRightDb,
            ),
        )
    }

    /** The pool's own answer, live but not persisted — the preview between runs. */
    private fun applyPreview() {
        val draft = _state.value.draft ?: return
        val base = restoreTo ?: return
        controller.update(draft.toEqSettings(base))
    }

    /** Puts the listener's sound back exactly as it was before the test. */
    private fun restore() {
        val original = restoreTo ?: return
        restoreTo = null
        controller.update(original)
    }

    private fun message(text: String) {
        _state.value = _state.value.copy(message = text)
    }

    /**
     * The names of the apps playing right now, for the run's label.
     *
     * Resolved here rather than deeper for the reason `EqScreen` gives for doing
     * the same thing: turning a uid into a name needs a PackageManager, and the
     * only use for the name is to put words on a screen.
     */
    private fun playingAppNames(): List<String> {
        val packages = getApplication<Application>().packageManager
        return PlayingApps.uids.value.mapNotNull { uid ->
            runCatching {
                packages.getPackagesForUid(uid)?.firstNotNullOfOrNull { name ->
                    packages.getApplicationLabel(packages.getApplicationInfo(name, 0)).toString()
                }
            }.getOrNull()
        }.distinct().sorted()
    }

    override fun onCleared() {
        // A screen that goes away mid-test must not leave a candidate playing.
        if (_state.value.phase != PreferencePhase.IDLE) restore()
        super.onCleared()
    }
}
