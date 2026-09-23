package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.live.LdacQualityMode
import dev.dankyeeter.btdashboard.monitor.link.live.ObservationRun
import dev.dankyeeter.btdashboard.monitor.link.live.RunEnd
import dev.dankyeeter.btdashboard.monitor.link.live.RunLink
import dev.dankyeeter.btdashboard.monitor.optimize.ARM_TARGET_MS
import dev.dankyeeter.btdashboard.monitor.optimize.Arm
import dev.dankyeeter.btdashboard.monitor.optimize.ComparisonResult
import dev.dankyeeter.btdashboard.monitor.optimize.Condition
import dev.dankyeeter.btdashboard.monitor.optimize.ConditionBook
import dev.dankyeeter.btdashboard.monitor.optimize.ConditionValue
import dev.dankyeeter.btdashboard.monitor.optimize.Measure
import dev.dankyeeter.btdashboard.monitor.optimize.NotComparable
import dev.dankyeeter.btdashboard.monitor.optimize.NotComparable.Where
import dev.dankyeeter.btdashboard.monitor.optimize.Verdict
import dev.dankyeeter.btdashboard.monitor.optimize.compare
import dev.dankyeeter.btdashboard.monitor.optimize.smallestDetectableBaseline
import org.junit.Assert.assertEquals
import org.junit.Test

/** The before/after comparison (AD-030, AD-032, AD-035; test cases S3-2 in AD-038). */
class ComparisonTest {

    private val high = RunLink("AC:DE:48:00:37:8F", CodecFamily.LDAC, LdacQualityMode.HIGH_QUALITY, false, 96_000)

    private fun run(
        dropouts: Long,
        observedMs: Long = ARM_TARGET_MS,
        end: RunEnd = RunEnd.TARGET_REACHED,
        link: RunLink = high,
        cadencesMs: Set<Long> = setOf(1_000L),
        uncountedMs: Long = 0L,
    ) = ObservationRun(
        observedMs = observedMs,
        end = end,
        dropouts = dropouts,
        dropoutsUncountedMs = uncountedMs,
        cadencesMs = cadencesMs,
        link = link,
    )

    private val calm: ConditionBook = mapOf(
        Condition.WIFI_RADIO to ConditionValue.Read("on"),
        Condition.WIFI_SCAN_ALWAYS to ConditionValue.Read("off"),
        Condition.USB_POWER to ConditionValue.Read("none"),
        Condition.OTHER_ACL_LINKS to ConditionValue.Read("0"),
        Condition.DISCOVERY_SEEN to ConditionValue.Read("no"),
    )

    private fun ConditionBook.with(condition: Condition, value: String) = this + (condition to ConditionValue.Read(value))

    private fun arm(run: ObservationRun, start: ConditionBook = calm, end: ConditionBook = start) = Arm(run, start, end)

    private fun compared(before: Arm, after: Arm, measure: Measure = Measure.NO_2_4_GHZ_WIFI) =
        compare(before, after, measure) as ComparisonResult.Compared

    private fun verdictOf(a: Long, b: Long) = compared(arm(run(a)), arm(run(b))).verdict

    private fun reasonsOf(before: Arm, after: Arm, measure: Measure = Measure.NO_2_4_GHZ_WIFI) =
        (compare(before, after, measure) as ComparisonResult.NotComparableResult).reasons

    @Test
    fun `five against none is detectable, four against none is not`() {
        val five = compared(arm(run(5)), arm(run(0)))
        assertEquals(Verdict.FEWER_DETECTED, five.verdict)
        assertEquals(1.0 / 32, five.pValue, EXACT)

        val four = compared(arm(run(4)), arm(run(0)))
        assertEquals(Verdict.WITHIN_DETECTION_LIMIT, four.verdict)
        assertEquals(1.0 / 16, four.pValue, EXACT)
    }

    @Test
    fun `each verdict is reached`() {
        assertEquals(Verdict.MORE_DETECTED, verdictOf(0, 5))
        assertEquals(Verdict.NO_BASELINE_EVENTS, verdictOf(0, 0))
        assertEquals(Verdict.NO_BASELINE_EVENTS, verdictOf(0, 4))
        assertEquals(Verdict.WITHIN_DETECTION_LIMIT, verdictOf(10, 10))
    }

    /** 0.5^1900 underflows a Double; the log-space sum must not. Reference: Python `fractions`, exact sum. */
    @Test
    fun `large counts stay finite`() {
        val result = compared(arm(run(1000)), arm(run(900)))
        assertEquals(Verdict.FEWER_DETECTED, result.verdict)
        assertEquals(0.011554422554450536, result.pValue, CLOSE)
    }

    /** Arm B overshoots by one interval; the test weighs the counts by the observed times: (900/1805)^5. */
    @Test
    fun `unequal durations weigh the expected share`() {
        val result = compared(arm(run(5, observedMs = 900_000L)), arm(run(0, observedMs = 905_000L)))
        assertEquals(Verdict.FEWER_DETECTED, result.verdict)
        assertEquals(0.030819565807101917, result.pValue, CLOSE)
    }

    @Test
    fun `the detection limit is computed, not entered`() {
        assertEquals(5, smallestDetectableBaseline())
        assertEquals(4, smallestDetectableBaseline(0.1))
    }

    @Test
    fun `each structural mismatch makes the arms incomparable`() {
        assertEquals(listOf(NotComparable.ArmAIncomplete), reasonsOf(arm(run(5, end = RunEnd.STOPPED)), arm(run(0))))
        val adaptive = high.copy(mode = null, adaptive = true)
        assertEquals(listOf(NotComparable.LinkDiffers), reasonsOf(arm(run(5)), arm(run(0, link = adaptive))))
        assertEquals(
            listOf(NotComparable.CadenceDiffers),
            reasonsOf(arm(run(5)), arm(run(0, cadencesMs = setOf(2_000L)))),
        )
        assertEquals(listOf(NotComparable.DropoutsUncounted), reasonsOf(arm(run(5, uncountedMs = 1L)), arm(run(0))))
    }

    @Test
    fun `a stable condition may change only between the arms, and only the measure's own`() {
        val wifiOff = calm.with(Condition.WIFI_RADIO, "off")
        assertEquals(
            listOf(NotComparable.ConditionChanged(Condition.WIFI_RADIO, Where.IN_ARM_A)),
            reasonsOf(arm(run(5), start = calm, end = wifiOff), arm(run(0), start = wifiOff)),
        )
        val usb = calm.with(Condition.USB_POWER, "usb")
        assertEquals(
            listOf(NotComparable.ConditionChanged(Condition.USB_POWER, Where.BETWEEN_ARMS)),
            reasonsOf(arm(run(5)), arm(run(0), start = usb)),
        )
        compared(arm(run(5)), arm(run(0), start = wifiOff))
    }

    @Test
    fun `what was not read never blocks and is named`() {
        val unreadable = calm + (Condition.WIFI_SCAN_ALWAYS to ConditionValue.Unreadable("not set on this phone"))
        assertEquals(listOf(Condition.WIFI_SCAN_ALWAYS), compared(arm(run(5), start = unreadable), arm(run(0))).notChecked)

        val discovery = calm.with(Condition.DISCOVERY_SEEN, "yes")
        compared(arm(run(5)), arm(run(0), end = discovery))
    }

    @Test
    fun `a contradicted measure at the start of arm B makes the arms incomparable`() {
        val secondDevice = calm.with(Condition.OTHER_ACL_LINKS, "1")
        assertEquals(
            listOf(NotComparable.MeasureNotInEffect(Condition.OTHER_ACL_LINKS)),
            reasonsOf(arm(run(5), start = secondDevice), arm(run(0), start = secondDevice), Measure.NO_SECOND_DEVICE),
        )
    }

    private companion object {
        const val EXACT = 1e-15

        /** Log-space rounding over up to 1901 terms, against exact references. */
        const val CLOSE = 1e-12
    }
}
