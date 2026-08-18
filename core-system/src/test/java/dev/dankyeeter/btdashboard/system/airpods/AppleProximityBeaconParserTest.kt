package dev.dankyeeter.btdashboard.system.airpods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic-payload tests for the proximity beacon decoder.
 *
 * The payloads are built by [beacon], which mirrors the documented layout
 * field by field. That keeps every test readable as "these bits mean this
 * state" instead of a wall of hex.
 */
class AppleProximityBeaconParserTest {

    // ---- payload builder ------------------------------------------------------

    private fun beacon(
        modelId: Int = 0x2014,
        status: Int = 0x00,
        primaryBattery: Int = 0x0F,
        secondaryBattery: Int = 0x0F,
        chargeFlags: Int = 0x00,
        caseBattery: Int = 0x0F,
        lid: Int = 0x08,
        color: Int = 0x00,
        type: Int = 0x07,
        declaredLength: Int = 0x19,
    ): ByteArray = ByteArray(AppleProximityBeaconParser.PAYLOAD_SIZE).also { b ->
        b[0] = type.toByte()
        b[1] = declaredLength.toByte()
        b[2] = 0x01
        b[3] = (modelId shr 8).toByte()
        b[4] = (modelId and 0xFF).toByte()
        b[5] = status.toByte()
        b[6] = (((primaryBattery and 0x0F) shl 4) or (secondaryBattery and 0x0F)).toByte()
        b[7] = (((chargeFlags and 0x0F) shl 4) or (caseBattery and 0x0F)).toByte()
        b[8] = lid.toByte()
        b[9] = color.toByte()
        // bytes 10..26 stay zero: reserved + the encrypted block we never read
    }

    // ---- framing --------------------------------------------------------------

    @Test
    fun `null payload is rejected`() {
        assertNull(AppleProximityBeaconParser.parse(null))
    }

    @Test
    fun `short payload is rejected`() {
        assertNull(AppleProximityBeaconParser.parse(ByteArray(10) { 0x07 }))
    }

    @Test
    fun `empty payload is rejected`() {
        assertNull(AppleProximityBeaconParser.parse(ByteArray(0)))
    }

    @Test
    fun `non proximity message type is rejected`() {
        // 0x10 is Apple's "nearby info" message — same company id, wrong shape.
        assertNull(AppleProximityBeaconParser.parse(beacon(type = 0x10)))
    }

    @Test
    fun `wrong declared length is rejected`() {
        assertNull(AppleProximityBeaconParser.parse(beacon(declaredLength = 0x0A)))
    }

    @Test
    fun `longer payload than expected is still accepted`() {
        val padded = beacon() + ByteArray(4)
        assertTrue(AppleProximityBeaconParser.parse(padded) != null)
    }

    // ---- model detection ------------------------------------------------------

    @Test
    fun `known model ids map to their model and preset`() {
        val expected = mapOf(
            0x2002 to (AirPodsModel.AIRPODS_1 to null),
            0x200F to (AirPodsModel.AIRPODS_2 to "airpods_2"),
            0x2013 to (AirPodsModel.AIRPODS_3 to "airpods_3"),
            0x2019 to (AirPodsModel.AIRPODS_4 to "airpods_4"),
            0x201B to (AirPodsModel.AIRPODS_4_ANC to "airpods_4_anc"),
            0x200E to (AirPodsModel.AIRPODS_PRO to null),
            0x2014 to (AirPodsModel.AIRPODS_PRO_2 to "airpods_pro_2"),
            0x2024 to (AirPodsModel.AIRPODS_PRO_2_USB_C to "airpods_pro_2"),
            0x2026 to (AirPodsModel.AIRPODS_PRO_3 to "airpods_pro_3"),
            0x200A to (AirPodsModel.AIRPODS_MAX to null),
        )
        expected.forEach { (id, modelAndPreset) ->
            val parsed = AppleProximityBeaconParser.parse(beacon(modelId = id))!!
            assertEquals("model for id ${id.toString(16)}", modelAndPreset.first, parsed.model)
            assertEquals(
                "preset for id ${id.toString(16)}",
                modelAndPreset.second,
                parsed.model.calibrationPresetId,
            )
            assertEquals(id, parsed.rawModelId)
        }
    }

    @Test
    fun `unknown model id degrades to UNKNOWN without a preset`() {
        val parsed = AppleProximityBeaconParser.parse(beacon(modelId = 0x2FFF))!!
        assertEquals(AirPodsModel.UNKNOWN, parsed.model)
        assertEquals(0x2FFF, parsed.rawModelId)
        assertNull(parsed.model.calibrationPresetId)
    }

    @Test
    fun `model id is read big-endian across the byte boundary`() {
        val parsed = AppleProximityBeaconParser.parse(beacon(modelId = 0x0120))!!
        // 0x0120 must not be confused with the byte-swapped 0x2001.
        assertEquals(0x0120, parsed.rawModelId)
    }

    // ---- battery --------------------------------------------------------------

    @Test
    fun `battery nibbles are ten percent steps`() {
        val parsed = AppleProximityBeaconParser.parse(
            beacon(
                status = 0x20,           // left is primary
                primaryBattery = 0x08,
                secondaryBattery = 0x05,
                caseBattery = 0x0A,
            ),
        )!!
        assertEquals(80, parsed.leftBatteryPercent)
        assertEquals(50, parsed.rightBatteryPercent)
        assertEquals(100, parsed.caseBatteryPercent)
    }

    @Test
    fun `battery nibble 0x0F means unknown`() {
        val parsed = AppleProximityBeaconParser.parse(
            beacon(primaryBattery = 0x0F, secondaryBattery = 0x0F, caseBattery = 0x0F),
        )!!
        assertNull(parsed.leftBatteryPercent)
        assertNull(parsed.rightBatteryPercent)
        assertNull(parsed.caseBatteryPercent)
        assertFalse(parsed.hasBudBattery)
    }

    @Test
    fun `out of range battery nibbles report unknown instead of a bogus number`() {
        // 11..14 are not valid levels; a dash is better than a made-up percent.
        (11..14).forEach { nibble ->
            val parsed = AppleProximityBeaconParser.parse(beacon(primaryBattery = nibble))!!
            assertNull("nibble $nibble", parsed.rightBatteryPercent)
        }
    }

    @Test
    fun `zero percent is a real level and not treated as missing`() {
        val parsed = AppleProximityBeaconParser.parse(
            beacon(status = 0x20, primaryBattery = 0x00),
        )!!
        assertEquals(0, parsed.leftBatteryPercent)
        assertTrue(parsed.hasBudBattery)
    }

    // ---- primary/secondary side swap ------------------------------------------

    @Test
    fun `right bud is primary when the left-primary bit is clear`() {
        val parsed = AppleProximityBeaconParser.parse(
            beacon(status = 0x00, primaryBattery = 0x09, secondaryBattery = 0x03),
        )!!
        assertEquals(90, parsed.rightBatteryPercent)
        assertEquals(30, parsed.leftBatteryPercent)
    }

    @Test
    fun `left bud is primary when the left-primary bit is set`() {
        val parsed = AppleProximityBeaconParser.parse(
            beacon(status = 0x20, primaryBattery = 0x09, secondaryBattery = 0x03),
        )!!
        assertEquals(90, parsed.leftBatteryPercent)
        assertEquals(30, parsed.rightBatteryPercent)
    }

    @Test
    fun `charging flags follow the same side swap as the battery`() {
        val right = AppleProximityBeaconParser.parse(
            beacon(status = 0x00, chargeFlags = 0x01),
        )!!
        assertTrue(right.rightCharging)
        assertFalse(right.leftCharging)

        val left = AppleProximityBeaconParser.parse(
            beacon(status = 0x20, chargeFlags = 0x01),
        )!!
        assertTrue(left.leftCharging)
        assertFalse(left.rightCharging)
    }

    @Test
    fun `case charging is side independent`() {
        val parsed = AppleProximityBeaconParser.parse(beacon(chargeFlags = 0x04))!!
        assertTrue(parsed.caseCharging)
        assertFalse(parsed.leftCharging)
        assertFalse(parsed.rightCharging)
    }

    @Test
    fun `all three charging flags can be set at once`() {
        val parsed = AppleProximityBeaconParser.parse(
            beacon(status = 0x20, chargeFlags = 0x07),
        )!!
        assertTrue(parsed.leftCharging)
        assertTrue(parsed.rightCharging)
        assertTrue(parsed.caseCharging)
    }

    // ---- wear detection -------------------------------------------------------

    @Test
    fun `in-ear flags are reported per side`() {
        val parsed = AppleProximityBeaconParser.parse(
            beacon(status = 0x20 or 0x02),   // left primary, primary in ear
        )!!
        assertTrue(parsed.leftInEar)
        assertFalse(parsed.rightInEar)
        assertFalse(parsed.bothInEar)
    }

    @Test
    fun `both buds in ear sets bothInEar`() {
        val parsed = AppleProximityBeaconParser.parse(beacon(status = 0x02 or 0x08))!!
        assertTrue(parsed.bothInEar)
    }

    @Test
    fun `in-case beats a stale in-ear bit`() {
        val parsed = AppleProximityBeaconParser.parse(
            beacon(status = 0x04 or 0x02 or 0x08),
        )!!
        assertFalse(parsed.leftInEar)
        assertFalse(parsed.rightInEar)
    }

    // ---- lid ------------------------------------------------------------------

    @Test
    fun `lid closed bit is honoured`() {
        assertFalse(AppleProximityBeaconParser.parse(beacon(lid = 0x08))!!.lidOpen)
        assertTrue(AppleProximityBeaconParser.parse(beacon(lid = 0x00))!!.lidOpen)
    }

    @Test
    fun `lid open counter is the low three bits`() {
        val parsed = AppleProximityBeaconParser.parse(beacon(lid = 0x05))!!
        assertEquals(5, parsed.lidOpenCounter)
        assertTrue(parsed.lidOpen)
    }

    // ---- colour ---------------------------------------------------------------

    @Test
    fun `colour byte maps to the housing colour`() {
        assertEquals(AirPodsColor.WHITE, AppleProximityBeaconParser.parse(beacon(color = 0x00))!!.color)
        assertEquals(AirPodsColor.BLACK, AppleProximityBeaconParser.parse(beacon(color = 0x01))!!.color)
        assertEquals(AirPodsColor.GREEN, AppleProximityBeaconParser.parse(beacon(color = 0x0C))!!.color)
        assertEquals(AirPodsColor.UNKNOWN, AppleProximityBeaconParser.parse(beacon(color = 0x7F))!!.color)
    }

    // ---- realistic composite --------------------------------------------------

    @Test
    fun `worn AirPods Pro 2 with a charging case decodes end to end`() {
        val parsed = AppleProximityBeaconParser.parse(
            beacon(
                modelId = 0x2014,
                status = 0x20 or 0x02 or 0x08,  // left primary, both in ear
                primaryBattery = 0x07,
                secondaryBattery = 0x06,
                chargeFlags = 0x04,
                caseBattery = 0x09,
                lid = 0x08,
                color = 0x00,
            ),
        )!!

        assertEquals(AirPodsModel.AIRPODS_PRO_2, parsed.model)
        assertEquals("airpods_pro_2", parsed.model.calibrationPresetId)
        assertEquals(70, parsed.leftBatteryPercent)
        assertEquals(60, parsed.rightBatteryPercent)
        assertEquals(90, parsed.caseBatteryPercent)
        assertTrue(parsed.bothInEar)
        assertTrue(parsed.caseCharging)
        assertFalse(parsed.leftCharging)
        assertFalse(parsed.lidOpen)
        assertEquals(AirPodsColor.WHITE, parsed.color)
    }

    @Test
    fun `random noise never throws`() {
        val rng = java.util.Random(1234)
        repeat(2000) {
            val bytes = ByteArray(rng.nextInt(40)).also(rng::nextBytes)
            AppleProximityBeaconParser.parse(bytes)  // must not throw
        }
    }
}
