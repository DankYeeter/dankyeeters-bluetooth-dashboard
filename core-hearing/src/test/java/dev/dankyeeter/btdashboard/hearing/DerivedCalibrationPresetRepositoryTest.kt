package dev.dankyeeter.btdashboard.hearing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decorator that lets a derived calibration be picked and computed with,
 * without any of it reaching the bundled table.
 */
class DerivedCalibrationPresetRepositoryTest {

    private fun calibration(deviceKey: String, name: String? = "Focal Bathys") = DerivedCalibration(
        deviceKey = deviceKey,
        deviceName = name,
        responseDeviationDb = listOf(1.0, 0.5, 0.0, -1.0, -2.5, -1.0, 2.0, -3.0),
        earSpreadDb = 1.0,
        warnings = emptyList(),
        createdAtMillis = 0L,
        sourceRunIds = emptyList(),
    )

    private fun repository() = DerivedCalibrationPresetRepository(BundledCalibrationPresets)

    @Test
    fun `with nothing derived it is the bundled table exactly`() {
        assertEquals(BundledCalibrationPresets.all(), repository().all())
    }

    @Test
    fun `a derivation is served alongside the bundled presets`() {
        val repo = repository()
        repo.setDerived(listOf(calibration("abc")))

        val all = repo.all()

        // Every bundled preset is still there, in its own order, with the
        // generic fallback still at the head where the picker expects it.
        assertTrue(all.containsAll(BundledCalibrationPresets.all()))
        assertEquals(CalibrationPresetRepository.GENERIC_ID, all.first().id)
        assertEquals(BundledCalibrationPresets.all().size + 1, all.size)
        assertEquals("derived_abc", all.last().id)
        assertEquals("Measured — your Focal Bathys", all.last().displayName)
    }

    @Test
    fun `a derived preset is resolvable by id, and the bundled ones still are`() {
        val repo = repository()
        repo.setDerived(listOf(calibration("abc"), calibration("def", name = "AirPods Pro 3")))

        assertNotNull(repo.byId("derived_abc"))
        assertEquals("Measured — your AirPods Pro 3", repo.byId("derived_def")?.displayName)
        assertEquals(BundledCalibrationPresets.focalBathys, repo.byId("focal_bathys"))
        assertNotNull(repo.byId(CalibrationPresetRepository.GENERIC_ID))
        assertNull(repo.byId("derived_nothing"))
    }

    /**
     * Discarding has to actually remove it. A preset the store no longer holds
     * but the repository still serves would keep shaping the EQ after the user
     * pressed Discard.
     */
    @Test
    fun `clearing the derivations takes their presets with them`() {
        val repo = repository()
        repo.setDerived(listOf(calibration("abc")))
        repo.setDerived(emptyList())

        assertNull(repo.byId("derived_abc"))
        assertEquals(BundledCalibrationPresets.all(), repo.all())
    }

    /** A re-derivation replaces the entry rather than adding a second one. */
    @Test
    fun `one device never yields two presets`() {
        val repo = repository()
        repo.setDerived(listOf(calibration("abc", name = "Old name")))
        repo.setDerived(listOf(calibration("abc", name = "New name")))

        assertEquals(1, repo.all().count { it.id == "derived_abc" })
        assertEquals("Measured — your New name", repo.byId("derived_abc")?.displayName)
    }

    /**
     * The bundled table is a constant and has to stay one: the decorator may
     * only ever wrap it.
     */
    @Test
    fun `the bundled table is never touched`() {
        val before = BundledCalibrationPresets.all()
        repository().setDerived(listOf(calibration("abc")))

        assertEquals(before, BundledCalibrationPresets.all())
        assertNull(BundledCalibrationPresets.byId("derived_abc"))
    }
}
