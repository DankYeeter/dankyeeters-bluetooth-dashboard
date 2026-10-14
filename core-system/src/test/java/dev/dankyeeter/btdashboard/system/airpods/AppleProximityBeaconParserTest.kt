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
        }
    }

    @Test
    fun `unknown model id degrades to UNKNOWN without a preset`() {
        val parsed = AppleProximityBeaconParser.parse(beacon(modelId = 0x2FFF))!!
        assertEquals(AirPodsModel.UNKNOWN, parsed.model)
        assertNull(parsed.model.calibrationPresetId)
    }

    @Test
    fun `model id is read big-endian across the byte boundary`() {
        // 0x1420 is 0x2014 (AirPods Pro 2) byte-swapped, so a little-endian
        // read would recognise it.
        assertEquals(
            AirPodsModel.AIRPODS_PRO_2,
            AppleProximityBeaconParser.parse(beacon(modelId = 0x2014))!!.model,
        )
        assertEquals(
            AirPodsModel.UNKNOWN,
            AppleProximityBeaconParser.parse(beacon(modelId = 0x1420))!!.model,
        )
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
            ),
        )!!

        assertEquals(AirPodsModel.AIRPODS_PRO_2, parsed.model)
        assertEquals("airpods_pro_2", parsed.model.calibrationPresetId)
        assertEquals(70, parsed.leftBatteryPercent)
        assertEquals(60, parsed.rightBatteryPercent)
        assertEquals(90, parsed.caseBatteryPercent)
        assertTrue(parsed.caseCharging)
        assertFalse(parsed.leftCharging)
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
