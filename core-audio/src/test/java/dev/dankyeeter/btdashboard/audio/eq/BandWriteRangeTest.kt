package dev.dankyeeter.btdashboard.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every band of every layout must have a centre frequency to write.
 *
 * The engine looked its cutoff up in the *default* layout's centre list while
 * iterating the *active* layout's band count. From band 10 upward on the 20-
 * and 31-band layouts that indexed out of bounds; the framework wrapper catches
 * RuntimeException and marks the effect dead, so the failure was silent: the
 * sliders moved, the values persisted, and the sound never changed.
 *
 * These tests need no device — they check the indexing contract the engine
 * depends on, which is where the bug actually lived.
 */
class BandWriteRangeTest {

    @Test
    fun `every band index of every layout has a centre`() {
        EqBandLayout.entries.forEach { layout ->
            for (band in 0 until layout.bandCount) {
                val centre = layout.centersHz.getOrNull(band)
                assertNotNull("${layout.id} has no centre for band $band", centre)
            }
        }
    }

    @Test
    fun `the default layout is too short for the wider ones`() {
        // The exact mismatch the engine used to walk into: proof that reading
        // centres from anywhere but the active layout cannot work.
        val default = EqBandLayout.DEFAULT.centersHz
        listOf(EqBandLayout.HALF_OCTAVE_20, EqBandLayout.THIRD_OCTAVE_31).forEach { layout ->
            assertTrue(
                "${layout.id} must be wider than the default for this test to mean anything",
                layout.bandCount > default.size,
            )
            assertEquals(null, default.getOrNull(layout.bandCount - 1))
        }
    }

    @Test
    fun `settings always carry as many gains as the layout has bands`() {
        EqBandLayout.entries.forEach { layout ->
            val settings = EqSettings(layout = layout)
            assertEquals(layout.bandCount, settings.leftGainsDb.size)
            assertEquals(layout.bandCount, settings.rightGainsDb.size)
            assertEquals(layout.bandCount, settings.centersHz.size)
        }
    }

    @Test
    fun `a curve resampled into a layout can be written band for band`() {
        // Walks the exact loop the engine runs, against the exact list it reads.
        val source = EqSettings(
            layout = EqBandLayout.OCTAVE_10,
            leftGainsDb = List(10) { 3f },
            rightGainsDb = List(10) { 3f },
        )
        EqBandLayout.entries.forEach { target ->
            val moved = source.withLayout(target)
            for (band in 0 until moved.layout.bandCount) {
                // Both of these are what writeBand() touches.
                moved.gainsFor(Ear.LEFT)[band]
                moved.layout.centersHz[band]
            }
        }
    }
}
