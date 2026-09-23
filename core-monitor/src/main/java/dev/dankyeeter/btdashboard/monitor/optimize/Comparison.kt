package dev.dankyeeter.btdashboard.monitor.optimize

import dev.dankyeeter.btdashboard.monitor.link.live.ObservationRun
import dev.dankyeeter.btdashboard.monitor.link.live.RunEnd
import kotlin.math.exp
import kotlin.math.ln

/** Observed time of each arm: 15 min (user decision F2). */
const val ARM_TARGET_MS = 15 * 60_000L

/**
 * A convention, not a measurement. Each direction is tested one-sided at this
 * level, so the chance of a false finding in *either* direction is up to 2α.
 */
const val DETECTION_ALPHA = 0.05

/** One arm: its run and the conditions read at its start and its end. */
data class Arm(val run: ObservationRun, val start: ConditionBook, val end: ConditionBook)

enum class Verdict { FEWER_DETECTED, MORE_DETECTED, WITHIN_DETECTION_LIMIT, NO_BASELINE_EVENTS }

/** Why two arms say nothing about each other. Each has its own sentence on screen. */
sealed interface NotComparable {
    data object ArmAIncomplete : NotComparable
    data object ArmBIncomplete : NotComparable
    data object LinkDiffers : NotComparable
    data object CadenceDiffers : NotComparable
    data object DropoutsUncounted : NotComparable
    data class ConditionChanged(val condition: Condition, val where: Where) : NotComparable

    /** The measure's condition read as contradicted at the start of arm B. */
    data class MeasureNotInEffect(val condition: Condition) : NotComparable
    enum class Where { IN_ARM_A, IN_ARM_B, BETWEEN_ARMS }
}

sealed interface ComparisonResult {
    data class NotComparableResult(val reasons: List<NotComparable>) : ComparisonResult
    data class Compared(
        val before: Arm,
        val after: Arm,
        val measure: Measure,
        val verdict: Verdict,
        /** The one-sided p-value in the direction the counts lean. */
        val pValue: Double,
        /** The measure's condition as read at the start of arm B. */
        val verification: Verification,
        /** Conditions not read in at least one book; named in the frame. */
        val notChecked: List<Condition>,
    ) : ComparisonResult
}

/**
 * Compares the dropout counts of [before] and [after] with an exact conditional
 * binomial test (AD-032). Counts are weighed with their windows; no rate is
 * formed.
 *
 * With a and b the two counts and t the observed times, b ~ Bin(a + b, tB / (tA + tB))
 * under "no difference". Weighing by the times makes the test exact for arm B's
 * overshoot of at most one interval.
 */
fun compare(before: Arm, after: Arm, measure: Measure): ComparisonResult {
    val reasons = notComparable(before, after, measure)
    if (reasons.isNotEmpty()) return ComparisonResult.NotComparableResult(reasons)

    val a = before.run.dropouts
    val b = after.run.dropouts
    val p0 = after.run.observedMs.toDouble() / (before.run.observedMs + after.run.observedMs)
    val lower = binomialTail(b, a + b, p0, upper = false)
    val upper = binomialTail(b, a + b, p0, upper = true)
    val verdict = when {
        upper <= DETECTION_ALPHA -> Verdict.MORE_DETECTED
        a == 0L -> Verdict.NO_BASELINE_EVENTS
        lower <= DETECTION_ALPHA -> Verdict.FEWER_DETECTED
        else -> Verdict.WITHIN_DETECTION_LIMIT
    }
    val books = listOf(before.start, before.end, after.start, after.end)
    return ComparisonResult.Compared(
        before = before,
        after = after,
        measure = measure,
        verdict = verdict,
        pValue = minOf(lower, upper),
        verification = measure.verify(measure.condition?.let { after.start[it] }),
        notChecked = Condition.entries.filter { c -> books.any { it[c] !is ConditionValue.Read } },
    )
}

/** The smallest count before at which none after is detectable, with equal durations. */
fun smallestDetectableBaseline(alpha: Double = DETECTION_ALPHA): Int {
    require(alpha > 0.0) { "alpha must be positive, was $alpha" }
    return generateSequence(0) { it + 1 }.first { binomialTail(0, it.toLong(), EQUAL_SHARE, upper = false) <= alpha }
}

private const val EQUAL_SHARE = 0.5

private fun notComparable(before: Arm, after: Arm, measure: Measure): List<NotComparable> = buildList {
    if (before.run.end != RunEnd.TARGET_REACHED) add(NotComparable.ArmAIncomplete)
    if (after.run.end != RunEnd.TARGET_REACHED) add(NotComparable.ArmBIncomplete)
    if (before.run.link != after.run.link) add(NotComparable.LinkDiffers)
    if (before.run.cadencesMs.size != 1 || before.run.cadencesMs != after.run.cadencesMs) {
        add(NotComparable.CadenceDiffers)
    }
    if (before.run.dropoutsUncountedMs > 0 || after.run.dropoutsUncountedMs > 0) {
        add(NotComparable.DropoutsUncounted)
    }
    Condition.entries.filter { it.stable }.forEach { c ->
        if (changed(before.start[c], before.end[c])) {
            add(NotComparable.ConditionChanged(c, NotComparable.Where.IN_ARM_A))
        }
        if (changed(after.start[c], after.end[c])) {
            add(NotComparable.ConditionChanged(c, NotComparable.Where.IN_ARM_B))
        }
        if (c != measure.condition && changed(before.end[c], after.start[c])) {
            add(NotComparable.ConditionChanged(c, NotComparable.Where.BETWEEN_ARMS))
        }
    }
    val condition = measure.condition
    if (condition != null && measure.verify(after.start[condition]) == Verification.CONTRADICTED) {
        add(NotComparable.MeasureNotInEffect(condition))
    }
}

/** Both read and different. What was not read never counts as a change. */
private fun changed(from: ConditionValue?, to: ConditionValue?): Boolean =
    from is ConditionValue.Read && to is ConditionValue.Read && from != to

/**
 * P(X <= k), or P(X >= k) when [upper], for X ~ Bin(n, p). Summed in log space:
 * 0.5^n underflows a Double for n > 1074.
 */
private fun binomialTail(k: Long, n: Long, p: Double, upper: Boolean): Double {
    val logOdds = ln(p) - ln(1 - p)
    val logTerms = DoubleArray(n.toInt() + 1)
    logTerms[0] = n * ln(1 - p)
    for (i in 1..n.toInt()) logTerms[i] = logTerms[i - 1] + ln((n - i + 1).toDouble() / i) + logOdds
    val tail = if (upper) logTerms.drop(k.toInt()) else logTerms.take(k.toInt() + 1)
    val peak = tail.max()
    return minOf(1.0, exp(peak) * tail.sumOf { exp(it - peak) })
}
