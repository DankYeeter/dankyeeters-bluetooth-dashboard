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
}
