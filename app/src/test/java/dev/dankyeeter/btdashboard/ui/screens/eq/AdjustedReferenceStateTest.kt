package dev.dankyeeter.btdashboard.ui.screens.eq

import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import dev.dankyeeter.btdashboard.hearing.AdjustedReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether the generated profile exists and whether the
 * band sliders may move.
 *
 * Worth pinning even though it is three getters: both of them gate a promise
 * the UI makes out loud. "Median of N runs" must not appear over a single run,
 * and a slider must not look editable on a curve that refuses edits.
 */
class AdjustedReferenceStateTest {

    private fun state(runs: Int, activeId: String? = null) = CompensationUiState(
        runCount = runs,
        activeProfileId = activeId,
    )

    @Test
    fun `it takes three runs before the generated profile is offered`() {
        assertFalse(state(0).adjustedReferenceReady)
        assertFalse(state(1).adjustedReferenceReady)
        assertFalse(state(2).adjustedReferenceReady)
        assertTrue(state(AdjustedReference.REQUIRED_RUNS).adjustedReferenceReady)
        assertTrue(state(7).adjustedReferenceReady)
    }

    @Test
    fun `it counts down the runs that are still missing`() {
        assertEquals(3, state(0).runsStillNeeded)
        assertEquals(1, state(2).runsStillNeeded)
        assertEquals(0, state(3).runsStillNeeded)
        // Never negative: the UI prints this number in a sentence.
        assertEquals(0, state(9).runsStillNeeded)
    }

    @Test
    fun `the curve is read-only only while the generated profile is the active one`() {
        assertTrue(state(3, AdjustedReference.ID).adjustedReferenceActive)
        assertFalse(state(3, "my_bass_boost").adjustedReferenceActive)
        assertFalse(state(3, null).adjustedReferenceActive)
    }

    @Test
    fun `having enough runs does not by itself lock the sliders`() {
        // Three runs make the profile available; they do not select it. A user
        // who took the test and then went back to a hand-tuned preset must
        // still be able to move a band.
        val ready = state(5, "my_bass_boost")
        assertTrue(ready.adjustedReferenceReady)
        assertFalse(ready.adjustedReferenceActive)
    }

    // ---- band grid -----------------------------------------------------------

    @Test
    fun `the generated profile brings its own band grid`() {
        // Whatever the EQ is set to, the generated curve is computed on the
        // layout that can represent the 3 kHz and 6 kHz measurements. On the
        // ten-band grid those two thresholds reach no band at all — proven in
        // core-hearing's AdjustedReferenceLayoutTest.
        EqBandLayout.entries.forEach { live ->
            assertEquals(
                AdjustedReference.LAYOUT,
                compensationLayoutFor(state(3, AdjustedReference.ID), live),
            )
        }
    }

    @Test
    fun `every other profile keeps the layout the user picked`() {
        EqBandLayout.entries.forEach { live ->
            assertEquals(live, compensationLayoutFor(state(3, "my_bass_boost"), live))
            assertEquals(live, compensationLayoutFor(state(3, null), live))
            // Including before any hearing test exists at all.
            assertEquals(live, compensationLayoutFor(state(0), live))
        }
    }
}
