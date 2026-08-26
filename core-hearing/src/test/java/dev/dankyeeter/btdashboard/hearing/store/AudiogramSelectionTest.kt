package dev.dankyeeter.btdashboard.hearing.store

import dev.dankyeeter.btdashboard.hearing.AncMode
import dev.dankyeeter.btdashboard.hearing.AudiogramRun
import dev.dankyeeter.btdashboard.hearing.level.VolumeGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule that decides which runs the equaliser corrects for.
 *
 * Worth its own test because it is the one place where "nothing chosen" has to
 * mean something sensible: a fresh install, or a selection emptied by deleting
 * the runs it named, must still produce a curve rather than nothing at all.
 */
class AudiogramSelectionTest {

    private fun run(id: String, at: Long) = AudiogramRun(
        id = id,
        timestampMillis = at,
        deviceAddressHash = null,
        calibrationPresetId = "generic",
        ancMode = AncMode.UNKNOWN,
        ambientNoiseDbA = null,
        left = emptyList(),
        right = emptyList(),
    )

    private val all = listOf(run("a", 1), run("b", 2), run("c", 3), run("d", 4))

    @Test
    fun `nothing chosen means the three newest`() {
        assertEquals(
            listOf("b", "c", "d"),
            AudiogramStore.selectionOf(all, emptySet()).map { it.id },
        )
    }

    @Test
    fun `an explicit choice wins over recency`() {
        assertEquals(
            listOf("a", "c"),
            AudiogramStore.selectionOf(all, setOf("a", "c")).map { it.id },
        )
    }

    @Test
    fun `ids that no longer exist are ignored`() {
        assertEquals(
            listOf("b"),
            AudiogramStore.selectionOf(all, setOf("b", "gone")).map { it.id },
        )
    }

    /** A stored set from an older build must not be able to smuggle in a fourth. */
    @Test
    fun `never more than three`() {
        assertEquals(3, AudiogramStore.selectionOf(all, all.map { it.id }.toSet()).size)
    }

    /**
     * The cap lives here and nowhere else.
     *
     * [AudiogramStore.setRunSelected] deliberately stores whatever it is given:
     * it cannot see which device or test level is current, so a cap there
     * counted ids that no screen ever showed in a curve and jammed the switch.
     * A stored set of any size therefore has to come out at three.
     */
    @Test
    fun `a stored set larger than three still yields three`() {
        val chosen = all.map { it.id }.toSet() + setOf("gone1", "gone2", "gone3")

        assertEquals(
            listOf("b", "c", "d"),
            AudiogramStore.selectionOf(all, chosen).map { it.id },
        )
    }

    /**
     * Deleting a run frees the slot its id was holding.
     *
     * The store prunes the id on delete; this is the half of that the curve
     * sees. A set naming three runs of which one is gone leaves room for a
     * fourth run to be chosen and actually counted - which is precisely what
     * the old raw-set cap made impossible.
     */
    @Test
    fun `deleting a selected run frees its slot`() {
        val chosen = setOf("a", "b", "c")
        val afterDelete = all.filterNot { it.id == "a" }

        assertEquals(
            listOf("b", "c"),
            AudiogramStore.selectionOf(afterDelete, chosen - "a").map { it.id },
        )
        assertEquals(
            listOf("b", "c", "d"),
            AudiogramStore.selectionOf(afterDelete, chosen - "a" + "d").map { it.id },
        )
    }

    @Test
    fun `an empty history yields nothing rather than failing`() {
        assertEquals(
            emptyList<String>(),
            AudiogramStore.selectionOf(emptyList(), setOf("a")).map { it.id },
        )
    }
    // ---- device binding ----------------------------------------------------

    private fun runOn(id: String, at: Long, device: String?) =
        run(id, at).copy(deviceAddressHash = device)

    @Test
    fun `only runs from the connected device count`() {
        val mixed = listOf(runOn("f1", 1, "focal"), runOn("n1", 2, "noble"), runOn("f2", 3, "focal"))

        assertEquals(
            listOf("f1", "f2"),
            AudiogramStore.selectionOf(mixed, emptySet(), deviceKey = "focal").map { it.id },
        )
    }

    /** Legacy runs carry no device; locking them out would strand data nobody can re-attribute. */
    @Test
    fun `a run without a recorded device stays usable everywhere`() {
        val mixed = listOf(runOn("old", 1, null), runOn("n1", 2, "noble"))

        assertEquals(
            listOf("old"),
            AudiogramStore.selectionOf(mixed, emptySet(), deviceKey = "focal").map { it.id },
        )
    }

    @Test
    fun `choosing a foreign run explicitly still cannot smuggle it in`() {
        val mixed = listOf(runOn("f1", 1, "focal"), runOn("n1", 2, "noble"))

        assertEquals(
            listOf("f1"),
            AudiogramStore.selectionOf(mixed, setOf("n1"), deviceKey = "focal").map { it.id },
        )
    }
    // ---- test volume -------------------------------------------------------

    @Test
    fun `runs at different test volumes never share a median`() {
        val mixed = listOf(
            run("loud1", 1),
            run("loud2", 2),
            run("quiet1", 3).copy(volumeFraction = 0.4),
        )

        // The newest run decides which window is current.
        assertEquals(
            listOf("quiet1"),
            AudiogramStore.selectionOf(mixed, emptySet(), deviceKey = null).map { it.id },
        )
    }

    @Test
    fun `going back to the standard level brings the old runs back`() {
        val mixed = listOf(
            run("loud1", 1),
            run("quiet1", 2).copy(volumeFraction = 0.4),
            run("loud2", 3),
        )

        assertEquals(
            listOf("loud1", "loud2"),
            AudiogramStore.selectionOf(mixed, emptySet(), deviceKey = null).map { it.id },
        )
    }

    /** What the history screen fades out has to be what the selection benches. */
    @Test
    fun `the current level is the newest run's, per device`() {
        val mixed = listOf(
            runOn("f1", 1, "focal"),
            runOn("n1", 2, "noble").copy(volumeFraction = 0.4),
        )

        assertEquals(
            VolumeGuard.TEST_VOLUME_FRACTION,
            AudiogramStore.currentVolumeFor(mixed, "focal")!!,
            EXACT,
        )
        assertEquals(0.4, AudiogramStore.currentVolumeFor(mixed, "noble")!!, EXACT)
        assertNull(AudiogramStore.currentVolumeFor(emptyList(), "focal"))
    }

    /**
     * Two levels that differ only in float noise are one level.
     *
     * One ULP apart is a different Double and the same volume. Exact equality
     * would bench the older run and quietly shrink the curve to one, which is
     * the kind of failure nobody reports because nothing looks broken.
     */
    @Test
    fun `a hair of rounding does not bench a run`() {
        val nudged = Math.nextUp(VolumeGuard.TEST_VOLUME_FRACTION)
        assertNotEquals(VolumeGuard.TEST_VOLUME_FRACTION, nudged, 0.0)

        val mixed = listOf(
            run("a", 1).copy(volumeFraction = VolumeGuard.TEST_VOLUME_FRACTION),
            run("b", 2).copy(volumeFraction = nudged),
        )

        assertEquals(
            listOf("a", "b"),
            AudiogramStore.selectionOf(mixed, emptySet(), deviceKey = null).map { it.id },
        )
    }

    private companion object {
        /** These assertions are about which value came back, not about arithmetic. */
        const val EXACT = 0.0
    }
}
