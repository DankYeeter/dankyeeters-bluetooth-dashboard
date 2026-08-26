package dev.dankyeeter.btdashboard.hearing.store

import dev.dankyeeter.btdashboard.hearing.DerivedCalibration
import dev.dankyeeter.btdashboard.hearing.TEST_FREQUENCIES_HZ
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored form of a derivation, which is the one record in this module that
 * cannot be re-measured at home: it took an appointment at a practice. So the
 * round trip is pinned rather than assumed, including the awkward strings — the
 * warnings are English prose with em dashes and quotes in them, and prose is
 * where a hand-written encoder goes wrong.
 */
class DerivedCalibrationJsonTest {

    private fun calibration(
        deviceKey: String = "a1b2c3",
        deviceName: String? = "Noble FoKus \"Prestige\" Encore",
        warnings: List<String> = listOf(
            "One band came out 14.5 dB from flat — unusually large.\nCheck the seal.",
            "Backslash \\ and tab\there.",
        ),
    ) = DerivedCalibration(
        deviceKey = deviceKey,
        deviceName = deviceName,
        responseDeviationDb = listOf(2.0, 1.0, 0.0, -1.5, -3.0, -1.0, 1.5, -2.0),
        earSpreadDb = 6.5,
        warnings = warnings,
        createdAtMillis = 1_756_000_000_000L,
        sourceRunIds = listOf("run-1", "run-2", "run-3"),
    )

    @Test
    fun `a derivation survives the round trip unchanged`() {
        val original = listOf(calibration())

        assertEquals(original, DerivedCalibrationJson.parse(DerivedCalibrationJson.encode(original)))
    }

    /**
     * The timestamp goes through JSON as a number and comes back as one. A
     * millisecond epoch is far inside the 2^53 a double represents exactly, so
     * this must be equality and not a tolerance.
     */
    @Test
    fun `the timestamp comes back to the millisecond`() {
        val parsed = DerivedCalibrationJson.parse(
            DerivedCalibrationJson.encode(listOf(calibration())),
        )

        assertEquals(1_756_000_000_000L, parsed.single().createdAtMillis)
    }

    @Test
    fun `several devices are kept apart`() {
        val both = listOf(calibration(deviceKey = "aaa"), calibration(deviceKey = "bbb"))

        val parsed = DerivedCalibrationJson.parse(DerivedCalibrationJson.encode(both))

        assertEquals(listOf("aaa", "bbb"), parsed.map { it.deviceKey })
    }

    @Test
    fun `a missing name stays missing rather than becoming a string`() {
        val parsed = DerivedCalibrationJson.parse(
            DerivedCalibrationJson.encode(listOf(calibration(deviceName = null))),
        )

        assertEquals(null, parsed.single().deviceName)
    }

    @Test
    fun `empty lists round-trip as empty lists`() {
        val parsed = DerivedCalibrationJson.parse(
            DerivedCalibrationJson.encode(listOf(calibration(warnings = emptyList()))),
        )

        assertTrue(parsed.single().warnings.isEmpty())
        assertEquals(emptyList<DerivedCalibration>(), DerivedCalibrationJson.parse("[]"))
    }

    /**
     * Same defensive shape as every other parser in the store: unreadable input
     * degrades to "nothing stored", never to a half-read record and never to a
     * crash on a screen that was only trying to draw a button.
     */
    @Test
    fun `garbage degrades to nothing rather than throwing`() {
        listOf(null, "", "   ", "not json", "{\"deviceKey\":\"x\"}", "[{", "[1,2,3]").forEach { raw ->
            assertTrue("for input $raw", DerivedCalibrationJson.parse(raw).isEmpty())
        }
    }

    /**
     * A deviation list of the wrong length is dropped, not padded.
     * `CalibrationPreset` requires the alignment in its constructor, so a padded
     * record would throw somewhere far away from here — and a padded record is
     * in any case a device response invented at the frequencies it filled in.
     */
    @Test
    fun `a misaligned deviation list is refused`() {
        val short = "[{\"deviceKey\":\"x\",\"responseDeviationDb\":[1.0,2.0]," +
            "\"earSpreadDb\":0.0,\"warnings\":[],\"createdAtMillis\":0,\"sourceRunIds\":[]}]"

        assertTrue(DerivedCalibrationJson.parse(short).isEmpty())
        // ...while the right length goes through.
        val full = TEST_FREQUENCIES_HZ.joinToString(",") { "0.0" }
        val ok = "[{\"deviceKey\":\"x\",\"responseDeviationDb\":[$full]," +
            "\"earSpreadDb\":0.0,\"warnings\":[],\"createdAtMillis\":0,\"sourceRunIds\":[]}]"
        assertEquals(1, DerivedCalibrationJson.parse(ok).size)
    }

    /** One broken entry must not cost the readable ones beside it. */
    @Test
    fun `a bad row is dropped on its own`() {
        val good = DerivedCalibrationJson.encode(listOf(calibration(deviceKey = "keep")))
        val mixed = good.dropLast(1) + ",{\"deviceKey\":\"\"},{\"nothing\":true}]"

        val parsed = DerivedCalibrationJson.parse(mixed)

        assertEquals(listOf("keep"), parsed.map { it.deviceKey })
    }
}
