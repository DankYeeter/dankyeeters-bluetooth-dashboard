package dev.dankyeeter.btdashboard.ui.screens.monitor

import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.ObservationRun
import dev.dankyeeter.btdashboard.monitor.link.live.RunEnd
import dev.dankyeeter.btdashboard.monitor.optimize.ARM_TARGET_MS
import dev.dankyeeter.btdashboard.monitor.optimize.Arm
import dev.dankyeeter.btdashboard.monitor.optimize.ComparisonResult
import dev.dankyeeter.btdashboard.monitor.optimize.ConditionBook
import dev.dankyeeter.btdashboard.monitor.optimize.Measure
import dev.dankyeeter.btdashboard.monitor.optimize.Verification
import dev.dankyeeter.btdashboard.monitor.optimize.compare
import dev.dankyeeter.btdashboard.system.devices.CodecApplyOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The phases of one comparison (AD-038 S3-6); exactly one is on screen. */
enum class ComparisonPhase { CHOOSE, PIN_AND_CHECK, ARM_A, INSTRUCT, ARM_B, RESULT }

/** The arm being counted: its run so far, the book read at its start, and whether discovery was seen in it. */
data class RunningArm(val run: ObservationRun, val start: ConditionBook, val discoverySeen: Boolean?)

/** An arm that ended before its target and was started again. */
data class ArmRestart(val armName: String, val end: RunEnd, val observedMs: Long)

/** What the comparison section shows. Nothing of it is stored (AD-037). */
data class ComparisonUi(
    val phase: ComparisonPhase = ComparisonPhase.CHOOSE,
    val measure: Measure? = null,
    /** Set when Compare found the measure's condition already in place; shown on [ComparisonPhase.CHOOSE]. */
    val alreadyApplies: Measure? = null,
    /** Why the pin did not take, already worded; null while pinning or after success. */
    val pinFailure: String? = null,
    val running: RunningArm? = null,
    /** Arm A once it reached its target; kept through every later phase. */
    val armA: Arm? = null,
    /** Arm B once it reached its target. */
    val armB: Arm? = null,
    val restart: ArmRestart? = null,
    /** The book read by the last check before arm B; null before the first check. */
    val readBack: ConditionBook? = null,
    val result: ComparisonResult? = null,
)

/**
 * Runs one before/after comparison for one Monitor screen (AD-030, AD-038 S3-6).
 *
 * Same shape and lifetime as [ObservationRunController]: it lives in the
 * screen's ViewModel, is fed the readings the panel polls anyway, and keeps
 * nothing past the process. Arm A survives a tab switch because the ViewModel
 * does; it does not survive the process ending (AD-037).
 *
 * Its one write is the pin, and that goes through [pin] — `LdacTuning.pin` in
 * the app, which holds the ledger entry before writing (M16).
 */
internal class ComparisonController(
    private val scope: CoroutineScope,
    /** Pins the top LDAC step for the link shown at [LinkLiveSnapshot.device]. */
    private val pin: suspend (shownAddress: String?) -> CodecApplyOutcome,
    /** Reads the condition book (AD-035) against one reading. */
    private val readBook: (snapshot: LinkLiveSnapshot, discoverySeen: Boolean?) -> ConditionBook,
) {
    private val _ui = MutableStateFlow(ComparisonUi())
    val ui: StateFlow<ComparisonUi> = _ui.asStateFlow()

    /** The newest reading seen: arms start strictly after it, and the pin is asked for its device. */
    private var newest: LinkLiveSnapshot? = null

    fun onReading(snapshot: LinkLiveSnapshot, expectedIntervalMs: Long) {
        if (snapshot.timestampMs <= (newest?.timestampMs ?: Long.MIN_VALUE)) return
        newest = snapshot
        _ui.update { it.advanced(snapshot, expectedIntervalMs) }
    }

    fun onMeasure(measure: Measure) {
        _ui.update { if (it.phase == ComparisonPhase.CHOOSE) it.copy(measure = measure, alreadyApplies = null) else it }
    }

    /** "Compare", and "Try again" after a failed pin. */
    fun onCompare() {
        val state = _ui.value
        val measure = state.measure ?: return
        val snapshot = newest ?: return
        if (state.phase != ComparisonPhase.CHOOSE && state.pinFailure == null) return
        if (measure.verifies(readBook(snapshot, snapshot.pairing?.discovering)) == Verification.VERIFIED) {
            _ui.value = ComparisonUi(measure = measure, alreadyApplies = measure)
            return
        }
        _ui.value = ComparisonUi(phase = ComparisonPhase.PIN_AND_CHECK, measure = measure)
        scope.launch {
            val outcome = pin(snapshot.device?.address)
            _ui.update { current ->
                // Cancelled while the pin was in flight: the answer is no longer asked for.
                if (current.phase != ComparisonPhase.PIN_AND_CHECK || current.pinFailure != null) return@update current
                when (outcome) {
                    is CodecApplyOutcome.Applied -> current.copy(
                        phase = ComparisonPhase.ARM_A,
                        running = armFrom(newest ?: snapshot, ARM_TARGET_MS),
                    )
                    is CodecApplyOutcome.NotObserved ->
                        current.copy(pinFailure = "the link still reads ${outcome.observed}: ${outcome.detail}")
                    is CodecApplyOutcome.Unavailable -> current.copy(pinFailure = outcome.reason)
                }
            }
        }
    }

    /** "Start Arm B" and "Check again": reads the book; a contradicted measure does not start arm B. */
    fun onCheck() {
        val state = _ui.value
        val measure = state.measure ?: return
        val armA = state.armA ?: return
        val snapshot = newest ?: return
        if (state.phase != ComparisonPhase.INSTRUCT) return
        val book = readBook(snapshot, snapshot.pairing?.discovering)
        _ui.value = if (measure.verifies(book) == Verification.CONTRADICTED) {
            state.copy(readBack = book)
        } else {
            state.copy(
                phase = ComparisonPhase.ARM_B,
                readBack = book,
                restart = null,
                running = RunningArm(ObservationRun.startedAfter(snapshot.timestampMs, armA.run.observedMs), book, null),
            )
        }
    }

    /** "Cancel" and "New comparison": back to the catalog, the chosen measure kept. */
    fun onReset() {
        _ui.update { ComparisonUi(measure = it.measure) }
    }

    private fun ComparisonUi.advanced(snapshot: LinkLiveSnapshot, expectedIntervalMs: Long): ComparisonUi {
        val arm = running ?: return this
        // The reading after an arm reached its target moves on; the target line stood for one reading.
        if (phase == ComparisonPhase.ARM_A && armA != null) {
            return copy(phase = ComparisonPhase.INSTRUCT, running = null, restart = null)
        }
        if (phase == ComparisonPhase.ARM_B && armA != null && armB != null && measure != null) {
            return copy(phase = ComparisonPhase.RESULT, running = null, result = compare(armA, armB, measure))
        }
        val discovering = snapshot.pairing?.discovering
        val seen = if (discovering == true) true else arm.discoverySeen ?: discovering
        val run = arm.run.plus(snapshot, expectedIntervalMs)
        val counted = arm.copy(run = run, discoverySeen = seen)
        val end = run.end ?: return copy(running = counted)
        if (end == RunEnd.TARGET_REACHED) {
            val finished = Arm(run, arm.start, readBook(snapshot, seen))
            return if (phase == ComparisonPhase.ARM_A) {
                copy(running = counted, armA = finished)
            } else {
                copy(running = counted, armB = finished)
            }
        }
        // Only this arm is counted again; arm A stays as it was.
        val armName = if (phase == ComparisonPhase.ARM_A) "A" else "B"
        return copy(
            running = armFrom(snapshot, run.targetMs ?: ARM_TARGET_MS),
            restart = ArmRestart(armName, end, run.observedMs),
        )
    }

    private fun armFrom(snapshot: LinkLiveSnapshot, targetMs: Long) = RunningArm(
        run = ObservationRun.startedAfter(snapshot.timestampMs, targetMs),
        start = readBook(snapshot, snapshot.pairing?.discovering),
        discoverySeen = null,
    )
}

/** This measure's verification against [book]. */
internal fun Measure.verifies(book: ConditionBook): Verification = verify(condition?.let(book::get))
