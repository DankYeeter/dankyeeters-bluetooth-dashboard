package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.hearing.fit.DeviceFormFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationPresetsTest {

    private val repo = BundledCalibrationPresets

    @Test
    fun `every preset required by the plan is bundled`() {
        val expected = setOf(
            CalibrationPresetRepository.GENERIC_ID,
            "focal_bathys",
            "noble_encore",
            "sennheiser_momentum4",
            "airpods_pro_3",
            "airpods_pro_2",
            "airpods_4_anc",
            "airpods_4",
            "airpods_3",
            "airpods_2",
        )
        assertEquals(expected, repo.all().map { it.id }.toSet())
    }

    @Test
    fun `ids are unique and lookup works`() {
        val ids = repo.all().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertNotNull(it, repo.byId(it)) }
        assertNull(repo.byId("nope"))
        assertEquals(repo.generic, repo.byIdOrGeneric("nope"))
        assertEquals(repo.generic, repo.byIdOrGeneric(null))
    }

    @Test
    fun `the generic preset exists and corrects nothing`() {
        val generic = repo.byId(CalibrationPresetRepository.GENERIC_ID)!!
        assertTrue(generic.offsetsDb.all { it == 0.0 })
        assertFalse("generic is exact by definition, not an approximation", generic.approximate)
    }

    @Test
    fun `every offset list aligns with the test frequencies`() {
        repo.all().forEach {
            assertEquals(it.id, TEST_FREQUENCIES_HZ.size, it.offsetsDb.size)
        }
    }

    @Test
    fun `measured presets are flagged as approximate and carry provenance`() {
        repo.all().filter { it.id != CalibrationPresetRepository.GENERIC_ID }.forEach {
            assertTrue("${it.id} must be flagged approximate", it.approximate)
            assertTrue("${it.id} needs a data source", it.dataSource.isNotBlank())
            assertTrue("${it.id} needs a rig", it.measurementRig.isNotBlank())
            assertTrue("${it.id} needs a target curve", it.targetCurve.isNotBlank())
            assertTrue("${it.id} needs notes", it.notes.isNotBlank())
            assertTrue("${it.id} must say APPROXIMATE", it.provenanceLine().contains("APPROXIMATE"))
        }
    }

    @Test
    fun `form factor drives the mandatory fit check`() {
        val iems = setOf(
            "noble_encore", "airpods_pro_3", "airpods_pro_2",
            "airpods_4_anc", "airpods_4", "airpods_3", "airpods_2",
        )
        repo.all().forEach {
            val expectIem = it.id in iems
            assertEquals(it.id, expectIem, it.formFactor == DeviceFormFactor.IN_EAR)
            assertEquals(it.id, expectIem, it.requiresFitCheck)
        }
        assertEquals(DeviceFormFactor.OVER_EAR, repo.focalBathys.formFactor)
        assertEquals(DeviceFormFactor.OVER_EAR, repo.sennheiserMomentum4.formFactor)
    }

    @Test
    fun `IEM and over-ear presets never claim the same rig`() {
        val rigs = repo.all()
            .filter { it.id != CalibrationPresetRepository.GENERIC_ID }
            .groupBy { it.formFactor }
            .mapValues { (_, v) -> v.map { it.measurementRig }.toSet() }
        val overEar = rigs[DeviceFormFactor.OVER_EAR].orEmpty()
        val inEar = rigs[DeviceFormFactor.IN_EAR].orEmpty()
        assertTrue("rigs must not be shared across form factors", (overEar intersect inEar).isEmpty())
    }

    @Test
    fun `fromResponseDeviation negates the published deviation`() {
        val preset = CalibrationPreset.fromResponseDeviation(
            id = "x",
            displayName = "x",
            dataSource = "d",
            measurementRig = "r",
            targetCurve = "t",
            formFactor = DeviceFormFactor.IN_EAR,
            responseDeviationDb = listOf(3.0, -2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        )
        assertEquals(listOf(-3.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), preset.offsetsDb)
    }

    @Test
    fun `corrections stay in a plausible shape-correction range`() {
        // These are shape corrections, never absolute levels. Anything beyond
        // +-10 dB would mean the preset is being misused.
        repo.all().forEach { preset ->
            preset.offsetsDb.forEach {
                assertTrue("${preset.id}: offset $it out of plausible range", it in -10.0..10.0)
            }
        }
    }

    @Test
    fun `presets are anchored at 1 kHz so they cannot shift the overall level`() {
        // Index 2 is 1000 Hz. A shape correction must be zero there, otherwise
        // it also moves the pure-tone average and thus the whole prescription.
        assertEquals(1000, TEST_FREQUENCIES_HZ[2])
        repo.all().forEach {
            assertEquals("${it.id} must be anchored at 1 kHz", 0.0, it.offsetsDb[2], 1e-9)
        }
    }

    @Test
    fun `a preset changes the resulting curve compared to generic`() {
        val thresholds = TEST_FREQUENCIES_HZ.map { ThresholdPoint(it, 40.0) }
        val audiogram = Audiogram(listOf("r"), thresholds, thresholds)
        val calc = NalRCompensationCalculator(repo)
        val generic = calc.compute(audiogram, CalibrationPresetRepository.GENERIC_ID, 0.6f, 1f)
        val bathys = calc.compute(audiogram, "focal_bathys", 0.6f, 1f)
        assertTrue(generic.leftGainsDb != bathys.leftGainsDb)
    }
}
