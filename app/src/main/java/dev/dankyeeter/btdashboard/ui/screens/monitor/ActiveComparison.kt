package dev.dankyeeter.btdashboard.ui.screens.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The process-wide handle on the running comparison (AD-039 point 2, AD-040).
 *
 * The comparison itself stays in the Monitor's ViewModel (AD-037); the LDAC
 * lock, the way back and the measurement service reach it through here. Null
 * in [ComparisonPhase.CHOOSE] and [ComparisonPhase.RESULT], after a failed pin,
 * and once the ViewModel is cleared. Nothing of it is stored.
 */
object ActiveComparison {

    /** The running comparison: its phase, the wall-clock time it entered it, and how to end it. */
    data class Held(val phase: ComparisonPhase, val phaseSinceMs: Long, val end: (ComparisonEnd) -> Unit)

    private val _held = MutableStateFlow<Held?>(null)
    val held: StateFlow<Held?> = _held.asStateFlow()

    /** Only [ComparisonController], in step with its own phase change. */
    internal fun set(held: Held?) {
        _held.value = held
    }

    /** Ends the running comparison without a result; does nothing when none runs. */
    fun end(cause: ComparisonEnd) {
        _held.value?.end?.invoke(cause)
    }
}

/**
 * Whether the LDAC chips are locked in [phase]: while an arm counts and between
 * the two arms. [ComparisonPhase.PIN_AND_CHECK] is free — it is the comparison's
 * own pin.
 */
internal fun locksLdac(phase: ComparisonPhase?): Boolean =
    phase == ComparisonPhase.ARM_A || phase == ComparisonPhase.INSTRUCT || phase == ComparisonPhase.ARM_B
