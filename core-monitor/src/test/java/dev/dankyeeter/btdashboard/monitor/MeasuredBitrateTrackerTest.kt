package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.link.live.BitrateStep
import dev.dankyeeter.btdashboard.monitor.link.live.BitrateStepReason
import dev.dankyeeter.btdashboard.monitor.link.live.MeasuredBitrateTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The filter between a rate that is now readable every poll and a timeline that
 * stays worth reading.
 *
 * The load-bearing case is [`the ABR pendulum writes nothing to the timeline`]:
 * on the device, ABR swung between 492 and 660 kbps for a whole session on a
 * clean link. That swing is 168 kbps — larger than a genuine 330-to-396 ladder
 * move — so no size threshold can separate the two, and the settling rule is
 * what does it.
 */
class MeasuredBitrateTrackerTest {

    private class Feed(private val tracker: MeasuredBitrateTracker = MeasuredBitrateTracker()) {
        var atMs = 0L
        val steps = mutableListOf<BitrateStep>()

        /** One poll at the panel's default two-second cadence. */
        fun read(kbps: Int?, sampleRateHz: Int? = 96_000): BitrateStep? {
            atMs += 2_000L
            return tracker.onReading(atMs, kbps, sampleRateHz).also { it?.let(steps::add) }
        }

        fun readAll(vararg kbps: Int) = kbps.forEach { read(it) }
    }

    /** Settles a link at [kbps] and drops the announcement of that first level. */
    private fun settledAt(kbps: Int): Feed = Feed().apply {
        readAll(kbps, kbps, kbps)
        steps.clear()
    }

    @Test
    fun `a level must hold for three readings before it is reported`() {
        val feed = Feed()

        assertNull(feed.read(396))
        assertNull(feed.read(396))
        val step = requireNotNull(feed.read(396))

        assertEquals(396, step.toKbps)
        assertNull("nothing to compare the first level to", step.fromKbps)
        assertEquals(BitrateStepReason.FIRST_READING, step.reason)
    }

    /**
     * The measurement this whole rule exists for. A rate that flips back before
     * it has settled never establishes a level, so it never reaches the
     * significance test — which on size alone it would pass.
     */
    @Test
    fun `the ABR pendulum writes nothing to the timeline`() {
        val feed = settledAt(492)

        repeat(10) {
            feed.read(660)
            feed.read(492)
        }

        assertEquals(
            "a 492-660 swing must not produce events: ${feed.steps.map { it.toKbps }}",
            emptyList<Int>(),
            feed.steps.map { it.toKbps },
        )
    }

    /**
     * The same 660 the pendulum kept touching, once it stops going back. This is
     * the pair the rule has to separate, and the only difference between them is
     * that this one held.
     */
    @Test
    fun `the same move reported once it settles`() {
        val feed = settledAt(492)

        assertNull(feed.read(660))
        assertNull(feed.read(660))
        val step = requireNotNull(feed.read(660))

        assertEquals(492, step.fromKbps)
        assertEquals(660, step.toKbps)
        assertEquals(BitrateStepReason.QUALITY_CLASS, step.reason)
        assertTrue("660 is above 492", !step.fell)
    }

    /**
     * A settled level that is nonetheless close to the reported one is not a
     * step. 330 to 396 is a real ABR move and only 66 kbps; both sit in the same
     * class, so it is deliberately not written down.
     */
    @Test
    fun `a small same-class move settles without being reported`() {
        val feed = settledAt(330)

        feed.readAll(396, 396, 396, 396, 396)

        assertEquals(emptyList<Int>(), feed.steps.map { it.toKbps })
    }

    /**
     * And the baseline must not creep: 330 to 396 to 460 is three same-class
     * steps that would cross into the next class if each one moved the reported
     * level. The last of them is 130 kbps from the level actually reported, so
     * it fires — once, against the level the timeline really shows.
     */
    @Test
    fun `a drift is measured against the reported level and not the last one`() {
        val feed = settledAt(330)

        feed.readAll(396, 396, 396)
        assertEquals(emptyList<Int>(), feed.steps.map { it.toKbps })

        feed.readAll(460, 460, 460)
        val step = feed.steps.single()
        assertEquals("the baseline is the reported 330, not the settled 396", 330, step.fromKbps)
        assertEquals(460, step.toKbps)
        assertEquals(BitrateStepReason.LARGE_STEP, step.reason)
    }

    /** A link that stays put writes one line and then nothing, however long it is watched. */
    @Test
    fun `a steady link stops writing after it has announced itself`() {
        val feed = Feed()

        repeat(30) { feed.read(492) }

        assertEquals(1, feed.steps.size)
        assertEquals(BitrateStepReason.FIRST_READING, feed.steps.single().reason)
    }

    /**
     * A poll that could not read the rate is a gap in observation, not a change
     * in the link — it must not be reported as a drop to nothing, and it must
     * not reset a level that was already established.
     */
    @Test
    fun `an unreadable poll is a gap and not a step`() {
        val feed = settledAt(660)

        assertNull(feed.read(null))
        assertNull(feed.read(null))
        feed.readAll(660, 660, 660)

        assertEquals(emptyList<Int>(), feed.steps.map { it.toKbps })
    }

    /** The drop a listener notices, all the way down the ladder. */
    @Test
    fun `a fall to the floor is reported as a fall`() {
        val feed = settledAt(990)

        feed.readAll(330, 330, 330)

        val step = feed.steps.single()
        assertEquals(990, step.fromKbps)
        assertEquals(330, step.toKbps)
        assertTrue(step.fell)
        assertEquals(BitrateStepReason.QUALITY_CLASS, step.reason)
    }

    /**
     * The class boundaries follow the sample-rate family, like every other LDAC
     * figure in this module. The ladders are 330/660/990 at 48 and 96 kHz and
     * 303/606/909 at 44.1 and 88.2, so the low-to-mid boundary sits at 495 on
     * one and 454 on the other.
     *
     * 330 to 470 straddles exactly that difference: on the 96 kHz ladder both
     * ends are low-class and the move is reported only because it is large, on
     * the 88.2 kHz ladder it is a genuine class change. Same numbers, different
     * verdict, which is the thing worth pinning.
     */
    @Test
    fun `the quality classes follow the sample rate family`() {
        fun settleThenMove(sampleRateHz: Int): BitrateStep {
            val tracker = MeasuredBitrateTracker()
            repeat(3) { tracker.onReading(1_000L, 330, sampleRateHz) }
            return (1..3).mapNotNull { tracker.onReading(2_000L, 470, sampleRateHz) }.single()
        }

        assertEquals(BitrateStepReason.LARGE_STEP, settleThenMove(96_000).reason)
        assertEquals(BitrateStepReason.QUALITY_CLASS, settleThenMove(88_200).reason)
    }

    @Test
    fun `a reset forgets the level so a new codec does not inherit it`() {
        val tracker = MeasuredBitrateTracker()
        repeat(3) { tracker.onReading(1_000L, 990, 96_000) }
        assertEquals(990, tracker.lastReportedKbps)

        tracker.reset()
        assertNull(tracker.lastReportedKbps)

        // And the next link announces itself rather than being compared to 990.
        val step = (1..3).mapNotNull { tracker.onReading(2_000L, 330, 96_000) }.single()
        assertNull(step.fromKbps)
        assertEquals(BitrateStepReason.FIRST_READING, step.reason)
    }
}
