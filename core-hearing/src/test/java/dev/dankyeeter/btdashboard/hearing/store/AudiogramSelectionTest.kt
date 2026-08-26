package dev.dankyeeter.btdashboard.hearing.store

import dev.dankyeeter.btdashboard.hearing.AncMode
import dev.dankyeeter.btdashboard.hearing.AudiogramRun
import org.junit.Assert.assertEquals
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
}
