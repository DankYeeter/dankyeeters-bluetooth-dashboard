package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.optimize.ConditionValue
import dev.dankyeeter.btdashboard.monitor.optimize.Fallback
import dev.dankyeeter.btdashboard.monitor.optimize.Measure
import dev.dankyeeter.btdashboard.monitor.optimize.Verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The catalogue of AK-12 as literals (AD-037). */
class MeasuresTest {

    @Test
    fun `the catalogue holds the six measures of R-010 part 1 in its order`() {
        assertEquals(listOf("A2", "A1", "A3", "A4", "A5", "A7"), Measure.entries.map { it.r010 })
        val refuted = setOf("A6") + (1..10).map { "C$it" }
        assertTrue(Measure.entries.none { it.r010 in refuted })
    }

    @Test
    fun `the bitrate lowerers are kept apart from the measures`() {
        assertEquals(4, Fallback.entries.size)
        assertTrue(Fallback.entries.map { it.name }.intersect(Measure.entries.map { it.name }.toSet()).isEmpty())
    }

    @Test
    fun `a reading verifies, contradicts or can not say`() {
        val cases = listOf(
            Triple(Measure.NO_2_4_GHZ_WIFI, "off", Verification.VERIFIED),
            Triple(Measure.NO_2_4_GHZ_WIFI, "on", Verification.NOT_VERIFIABLE),
            Triple(Measure.NO_SECOND_DEVICE, "0", Verification.VERIFIED),
            Triple(Measure.NO_SECOND_DEVICE, "1", Verification.CONTRADICTED),
            Triple(Measure.USB_CABLE_OFF, "none", Verification.VERIFIED),
            Triple(Measure.USB_CABLE_OFF, "usb", Verification.CONTRADICTED),
            Triple(Measure.BODY_OUT_OF_PATH, "none", Verification.NOT_VERIFIABLE),
        )
        cases.forEach { (measure, value, expected) ->
            assertEquals("$measure $value", expected, measure.verify(ConditionValue.Read(value)))
        }
        assertEquals(Verification.NOT_VERIFIABLE, Measure.USB_CABLE_OFF.verify(ConditionValue.Unreadable("no battery intent")))
    }
}
