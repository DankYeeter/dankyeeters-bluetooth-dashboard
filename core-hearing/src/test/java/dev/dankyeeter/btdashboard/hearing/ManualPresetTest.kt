package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A preset the user dialled in by hand has no audiogram behind it. It still
 * has to survive naming, storage and recall like any other — requiring a
 * hearing test first put the entire preset feature out of reach for anyone
 * who only wanted to move the sliders.
 */
class ManualPresetTest {

    private fun manual(name: String, gains: List<Float>) = CompensationProfile(
        id = "manual-1",
        name = name,
        createdAtMillis = 1_000L,
        audiogram = null,
        calibrationPresetId = CalibrationPresetRepository.GENERIC_ID,
        ancMode = AncMode.UNKNOWN,
        eq = EqSettings(
            layout = EqBandLayout.OCTAVE_10,
            leftGainsDb = gains,
            rightGainsDb = gains,
        ),
    )

    @Test
    fun `a manual preset needs no audiogram`() {
        val profile = manual("Bass boost", List(10) { if (it < 3) 6f else 0f })

        assertNull(profile.audiogram)
        assertEquals("Bass boost", profile.name)
        assertEquals(6f, profile.eq.leftGainsDb.first(), 1e-4f)
    }

    @Test
    fun `a measured preset still keeps its audiogram`() {
        val measured = manual("From test", List(10) { 0f }).copy(
            audiogram = Audiogram(runIds = listOf("run-1"), left = emptyList(), right = emptyList()),
        )

        assertNotNull(measured.audiogram)
        assertEquals(listOf("run-1"), measured.audiogram?.runIds)
    }

    @Test
    fun `a manual preset carries its band layout`() {
        val wide = CompensationProfile(
            id = "manual-2",
            name = "Fine",
            createdAtMillis = 2_000L,
            audiogram = null,
            calibrationPresetId = CalibrationPresetRepository.GENERIC_ID,
            ancMode = AncMode.UNKNOWN,
            eq = EqSettings(
                layout = EqBandLayout.THIRD_OCTAVE_31,
                leftGainsDb = List(31) { 1f },
                rightGainsDb = List(31) { 1f },
            ),
        )

        assertEquals(EqBandLayout.THIRD_OCTAVE_31, wide.eq.layout)
        assertEquals(31, wide.eq.leftGainsDb.size)
    }
}
