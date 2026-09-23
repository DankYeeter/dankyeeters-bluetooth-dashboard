package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.live.A2dpTxStats
import dev.dankyeeter.btdashboard.monitor.link.live.LdacStackState
import dev.dankyeeter.btdashboard.monitor.link.live.LdacState
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LiveCodecSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LiveDeviceSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.ObservationRun
import dev.dankyeeter.btdashboard.monitor.link.live.RunEnd
import dev.dankyeeter.btdashboard.monitor.link.live.StepDwell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The observation run's arithmetic (`UI_SPEC.md` T-039). */
class ObservationRunTest {

    private fun reading(
        timestampMs: Long,
        kbps: Int?,
        playing: Boolean = true,
        connected: Boolean = true,
        address: String = "AC:DE:48:00:37:8F",
        family: CodecFamily = CodecFamily.LDAC,
        codecSpecific1: Long = 0L,
        dropoutTotal: Long? = null,
    ) = LinkLiveSnapshot(
        timestampMs = timestampMs,
        device = LiveDeviceSnapshot(address = address, isConnected = connected, isPlaying = playing),
        codec = LiveCodecSnapshot(family = family, sampleRateHz = 96_000, codecSpecific1 = codecSpecific1),
        ldac = if (family == CodecFamily.LDAC) {
            LdacState.from(
                codecSpecific1,
                96_000,
                kbps?.let { LdacStackState(qualityMode = if (codecSpecific1 == 0L) "ABR" else "MID", transmissionKbps = it) },
            )
        } else {
            null
        },
        tx = A2dpTxStats(dropoutCount = dropoutTotal),
    )

    /** Feeds [rates] at a fixed [intervalMs] cadence starting at [fromMs]. */
    private fun ObservationRun.fed(rates: List<Int>, intervalMs: Long = 1_000L, fromMs: Long = 1_000L) =
        rates.foldIndexed(this) { i, run, kbps -> run.plus(reading(fromMs + i * intervalMs, kbps), intervalMs) }

    private fun started() = ObservationRun.startedAfter(null)

    /** AK-T039-1: only readings after the tap count, and the window does not slide. */
    @Test
    fun `a run counts from the first reading after its start and never lets go of one`() {
        val run = ObservationRun.startedAfter(5_000L)
            .plus(reading(5_000L, 330), 1_000L)
            .fed(List(600) { 660 }, fromMs = 6_000L)

        assertEquals("the reading at the tap is not part of the run", 660, run.lowestKbps)
        assertEquals(600, run.readings)
        assertEquals("ten minutes stay ten minutes: nothing slides out", 599_000L, run.observedMs)
    }

    /** Paused polls, one a second, from [fromMs] through [toMs]. */
    private fun ObservationRun.paused(fromMs: Long, toMs: Long) =
        (fromMs..toMs step 1_000L).fold(this) { run, ms -> run.plus(reading(ms, null, playing = false), 1_000L) }

    /**
     * AK-T039-3: `{T}` is the covered time, never now minus start. Also the
     * boundary of the pause rule: a pause of exactly [ObservationRun.RUN_GAP_MAX_MS],
     * first paused poll to last, does not end the run.
     */
    @Test
    fun `a pause of exactly the gap maximum is a gap, not observed time`() {
        val run = started().fed(List(61) { 660 })
            .paused(62_000L, 62_000L + ObservationRun.RUN_GAP_MAX_MS)
            .fed(List(61) { 660 }, fromMs = 183_000L)

        assertNull(run.end)
        assertEquals(120_000L, run.observedMs)
        assertEquals(1, run.gapCount)
        assertEquals(122_000L, run.gapMs)
    }

    /** One paused poll past [ObservationRun.RUN_GAP_MAX_MS] ends the run, figures kept. */
    @Test
    fun `a pause one poll longer than the gap maximum ends the run`() {
        val run = started().fed(List(61) { 660 })
            .paused(62_000L, 62_000L + ObservationRun.RUN_GAP_MAX_MS + 1_000L)

        assertEquals(RunEnd.PAUSED, run.end)
        assertEquals(60_000L, run.observedMs)
    }

    /** A playing poll ends the pause; the next pause counts from zero. */
    @Test
    fun `a pause interrupted by a playing poll does not end the run`() {
        val run = started().fed(List(61) { 660 })
            .paused(62_000L, 161_000L)
            .fed(listOf(660), fromMs = 162_000L)
            .paused(163_000L, 262_000L)
            .fed(List(10) { 660 }, fromMs = 263_000L)

        assertNull("200 s paused in two pauses of 100 s", run.end)
        assertEquals(69_000L, run.observedMs)
    }

    /** AK-T039-7: the threshold recomputes the share and moves nothing else. */
    @Test
    fun `a new threshold changes the share and nothing else`() {
        val run = started().fed(List(60) { 660 } + List(61) { 990 })

        assertEquals(100, run.atOrAbovePercent(660))
        assertEquals(50, run.atOrAbovePercent(990))
        // The figures that are not the share do not take a threshold at all.
        assertEquals(120_000L, run.observedMs)
        assertEquals(121, run.readings)
        assertEquals(660, run.lowestKbps)
        assertEquals(listOf(StepDwell(990, 60_000L), StepDwell(660, 59_000L)), run.stepDwell)
    }

    /** AK-T039-8: steps need both ends within tolerance, the share needs both ends at or above. */
    @Test
    fun `an interval is assigned by both of its ends`() {
        val swing = started().fed(listOf(660, 990, 660))
        assertEquals("660 to 990 to 660 is moving", 2_000L, swing.movingMs)
        assertEquals(emptyList<StepDwell>(), swing.stepDwell)
        assertEquals("and at or above 660", 100, swing.atOrAbovePercent(660))

        val rise = started().fed(listOf(492, 660))
        assertEquals("492 to 660 is moving", 1_000L, rise.movingMs)
        assertEquals("and not at or above 660", 0, rise.atOrAbovePercent(660))

        val wobble = started().fed(listOf(660, 668, 660))
        assertEquals("within the tolerance is one step", listOf(StepDwell(660, 2_000L)), wobble.stepDwell)
        assertEquals(0L, wobble.movingMs)
    }

    /** AK-T039-9: each end has its own trigger, and the figures stay as they were. */
    @Test
    fun `each end is recognised and freezes the figures`() {
        val running = started().fed(List(150) { 660 })
        val next = 151_000L
        val cases = mapOf(
            RunEnd.STOPPED to running.stopped(),
            RunEnd.DISCONNECTED to running.plus(reading(next, 660, connected = false), 1_000L),
            RunEnd.QUALITY_CHANGED to running.plus(reading(next, 660, codecSpecific1 = 1001L), 1_000L),
            RunEnd.CODEC_CHANGED to running.plus(reading(next, null, family = CodecFamily.AAC), 1_000L),
            RunEnd.READING_GAP to running.plus(reading(next + ObservationRun.RUN_GAP_MAX_MS, 660), 1_000L),
            RunEnd.PAUSED to running.paused(next, next + ObservationRun.RUN_GAP_MAX_MS + 1_000L),
            RunEnd.RATE_UNREADABLE to running.plus(reading(next, null), 1_000L),
            RunEnd.TARGET_REACHED to ObservationRun.startedAfter(null, targetMs = running.observedMs)
                .fed(List(150) { 660 }),
        )
        assertEquals(RunEnd.entries.toSet(), cases.keys)
        cases.forEach { (reason, ended) ->
            assertEquals(reason, ended.end)
            val later = ended.fed(List(10) { 330 }, fromMs = next + 1_000L)
            assertEquals("$reason: figures frozen", running.observedMs, later.observedMs)
            assertEquals("$reason: figures frozen", 660, later.lowestKbps)
        }
    }

    /** AK-T039-9: pinned to pinned is a quality change as well, not only leaving ABR. */
    @Test
    fun `a change from one pinned step to another ends the run`() {
        // codecSpecific1 1000 is HIGH, 1001 is MID; both are pinned.
        val pinnedHigh = (1..3).fold(started()) { run, s ->
            run.plus(reading(s * 1_000L, 990, codecSpecific1 = 1000L), 1_000L)
        }
        assertNull(pinnedHigh.end)

        val toMid = pinnedHigh.plus(reading(4_000L, 660, codecSpecific1 = 1001L), 1_000L)
        assertEquals(RunEnd.QUALITY_CHANGED, toMid.end)
    }

    /** A different headphone is a different pairing, and the run belongs to one. */
    @Test
    fun `another device becoming the link ends the run`() {
        val run = started().fed(List(3) { 660 })
            .plus(reading(4_000L, 660, address = "00:11:22:33:44:55"), 1_000L)
        assertEquals(RunEnd.DISCONNECTED, run.end)
    }

    /** AK-T039-16: switching cadence mid-run changes the samples, not the answer. */
    @Test
    fun `a cadence change does not distort share or dwell`() {
        // 60 s at 990, then 60 s at 660, then 60 s at 990 — the same timeline twice.
        val levelAt = { ms: Long -> if (ms in 60_000L until 120_000L) 660 else 990 }
        val steady = (0L..180_000L step 1_000L).fold(started()) { run, ms ->
            run.plus(reading(ms, levelAt(ms)), 1_000L)
        }
        // Mixed: 1 s in the first minute, 5 s afterwards.
        val mixed = ((0L..60_000L step 1_000L) + (65_000L..180_000L step 5_000L)).fold(started()) { run, ms ->
            val interval = if (ms <= 60_000L) 1_000L else 5_000L
            run.plus(reading(ms, levelAt(ms)), interval)
        }

        assertEquals(steady.observedMs, mixed.observedMs)
        assertEquals(steady.atOrAbovePercent(990), mixed.atOrAbovePercent(990))
        assertEquals(steady.atOrAbovePercent(660), mixed.atOrAbovePercent(660))
        // Dwell differs only where a step change falls inside one 5 s interval:
        // that interval is moving as a whole. The resolution of the sampling,
        // not a bias of the count.
        val steadyDwell = steady.stepDwell.associate { it.kbps to it.ms }
        mixed.stepDwell.forEach { step ->
            val difference = kotlin.math.abs(steadyDwell.getValue(step.kbps) - step.ms)
            assertTrue("${step.kbps}: $difference ms apart", difference < 5_000L)
        }
        assertEquals(steadyDwell.keys, mixed.stepDwell.map { it.kbps }.toSet())
    }

    /** The minimums are an and: 30 readings at 1 s are not enough time. */
    @Test
    fun `figures wait for both minimums`() {
        assertTrue(started().fed(List(30) { 660 }).isCollecting)
        assertFalse(started().fed(List(30) { 660 }, intervalMs = 5_000L).isCollecting)
        assertFalse(started().fed(List(121) { 660 }).isCollecting)
    }

    /** Past the level bound, new rates still count as observed time — in no step. */
    @Test
    fun `rates past the level bound count in the observed time only`() {
        val rates = (0 until ObservationRun.RUN_MAX_LEVELS + 1).map { 300 + it * 20 }
        val run = started().fed(rates.flatMap { listOf(it, it) })

        assertTrue(run.levelLimitReached)
        assertEquals(run.observedMs, run.stepDwell.sumOf { it.ms } + run.movingMs + run.unseparatedMs)
        // 760 -> 780 and 780 -> 780: the two intervals touching the refused rate.
        assertEquals(2_000L, run.unseparatedMs)
    }

    /** Pinned 990 at a 1 s cadence, each reading carrying the stack's absolute dropout counter. */
    private fun ObservationRun.withDropouts(vararg totals: Long?) =
        totals.foldIndexed(this) { i, run, total ->
            run.plus(reading(i * 1_000L, 990, codecSpecific1 = 1000L, dropoutTotal = total), 1_000L)
        }

    /** S3-2: dropouts are the increases of the absolute counter over covered intervals. */
    @Test
    fun `dropouts sum the counter's increases over covered intervals`() {
        val run = started().withDropouts(10L, 12L, 15L)

        assertEquals(5L, run.dropouts)
        assertEquals(0L, run.dropoutsUncountedMs)
        assertEquals(2_000L, run.observedMs)
        assertEquals(setOf(1_000L), run.cadencesMs)
    }

    /** S3-2: a missing counter at either end is no zero; the interval is uncounted. */
    @Test
    fun `a missing counter leaves its intervals uncounted`() {
        val run = started().withDropouts(10L, null, 15L)

        assertEquals(0L, run.dropouts)
        assertEquals(2_000L, run.dropoutsUncountedMs)
    }

    /** S3-2: a counter running backwards (stack restart) says nothing about the interval. */
    @Test
    fun `a falling counter leaves its interval uncounted`() {
        val run = started().withDropouts(10L, 15L, 3L)

        assertEquals(5L, run.dropouts)
        assertEquals(1_000L, run.dropoutsUncountedMs)
    }

    /** S3-2: the run ends as soon as it has observed its target, and not a reading earlier. */
    @Test
    fun `a run ends on reaching its target`() {
        val reached = ObservationRun.startedAfter(null, targetMs = 2_000L).withDropouts(10L, 12L, 15L)
        assertEquals(RunEnd.TARGET_REACHED, reached.end)
        val later = reached.plus(reading(3_000L, 990, codecSpecific1 = 1000L, dropoutTotal = 20L), 1_000L)
        assertEquals(reached, later)

        val short = ObservationRun.startedAfter(null, targetMs = 2_001L).withDropouts(10L, 12L, 15L)
        assertNull(short.end)
    }
}
