package dev.dankyeeter.btdashboard.system.airpods

/**
 * Byte-level decoder for Apple's BLE "proximity pairing" manufacturer payload.
 *
 * Pure Kotlin, no Android types: the scanner hands the raw manufacturer-data
 * array in, everything else here is testable on the JVM.
 *
 * ## Payload layout (manufacturer data for company id 0x004C, Apple)
 *
 * ```
 * off  size  meaning
 *  0    1    0x07  proximity-pairing message type
 *  1    1    0x19  length of the remainder (25 bytes)
 *  2    1    status/prefix byte (0x01 when paired to some device)
 *  3    2    device model id, big-endian (e.g. 0x2014 = AirPods Pro 2)
 *  5    1    status bits: primary-bud side, in-ear flags, in-case flag
 *  6    1    battery nibbles: high = primary bud, low = secondary bud
 *  7    1    high nibble = charging flags, low nibble = case battery
 *  8    1    lid: high bits = open/closed state, low nibble = open counter
 *  9    1    housing colour
 * 10    1    reserved (0x00)
 * 11   16    encrypted payload — Apple-only, never decoded here
 * ```
 *
 * Battery nibbles carry 0..10 in units of 10 %, and 0x0F ("no data"); anything
 * else is treated as unknown rather than clamped, because a bogus number on a
 * battery card is worse than an honest dash.
 *
 * ## Side assignment
 *
 * The buds do not advertise "left" and "right" — they advertise *primary* and
 * *secondary*, and which physical bud is primary changes at runtime (whichever
 * one currently owns the link). Bit 0x20 of the status byte tells us that the
 * left bud is the primary one; we swap the nibbles accordingly so the rest of
 * the app can think in left/right.
 *
 * Reimplemented from the published format description. No GPL code was copied.
 */
object AppleProximityBeaconParser {

    /** Bluetooth SIG company identifier for Apple, Inc. */
    const val APPLE_COMPANY_ID: Int = 0x004C

    /** First byte of the payloads we care about. */
    private const val TYPE_PROXIMITY_PAIRING = 0x07

    /** Declared length of the remainder for the proximity message. */
    private const val EXPECTED_REMAINDER = 0x19

    /** Total bytes we need: 2 header + 25 remainder. */
    const val PAYLOAD_SIZE: Int = 2 + EXPECTED_REMAINDER

    private const val BATTERY_UNKNOWN_NIBBLE = 0x0F

    // Status bit masks (offset 5).
    private const val STATUS_PRIMARY_IN_EAR = 0x02
    private const val STATUS_IN_CASE = 0x04
    private const val STATUS_SECONDARY_IN_EAR = 0x08
    private const val STATUS_LEFT_IS_PRIMARY = 0x20

    // Charging bit masks (high nibble of offset 7).
    private const val CHARGE_PRIMARY = 0x01
    private const val CHARGE_SECONDARY = 0x02
    private const val CHARGE_CASE = 0x04

    // Lid byte (offset 8).
    private const val LID_CLOSED = 0x08
    private const val LID_COUNTER_MASK = 0x07

    /**
     * Decodes one advertisement.
     *
     * @param data manufacturer-specific data for [APPLE_COMPANY_ID], with the
     *   company id already stripped (which is what Android's
     *   `ScanRecord.getManufacturerSpecificData(0x004C)` returns).
     * @return the decoded beacon, or null when the payload is not a
     *   proximity-pairing message or is too short. Never throws: a malformed
     *   advertisement from any random device must not be able to crash a scan
     *   callback.
     */
    fun parse(data: ByteArray?): AirPodsBeacon? {
        if (data == null || data.size < PAYLOAD_SIZE) return null
        if (data.u8(0) != TYPE_PROXIMITY_PAIRING) return null
        if (data.u8(1) != EXPECTED_REMAINDER) return null

        val rawModelId = (data.u8(3) shl 8) or data.u8(4)
        val status = data.u8(5)
        val batteryByte = data.u8(6)
        val chargeAndCase = data.u8(7)
        val lidByte = data.u8(8)

        val leftIsPrimary = status and STATUS_LEFT_IS_PRIMARY != 0

        val primaryBattery = battery(batteryByte shr 4)
        val secondaryBattery = battery(batteryByte and 0x0F)

        val chargeFlags = chargeAndCase shr 4
        val primaryCharging = chargeFlags and CHARGE_PRIMARY != 0
        val secondaryCharging = chargeFlags and CHARGE_SECONDARY != 0

        val primaryInEar = status and STATUS_PRIMARY_IN_EAR != 0
        val secondaryInEar = status and STATUS_SECONDARY_IN_EAR != 0

        // Sitting in the case beats any stale in-ear bit — a bud in the case is
        // by definition not being worn.
        val inCase = status and STATUS_IN_CASE != 0

        return AirPodsBeacon(
            model = AirPodsModel.fromId(rawModelId),
            rawModelId = rawModelId,
            leftBatteryPercent = if (leftIsPrimary) primaryBattery else secondaryBattery,
            rightBatteryPercent = if (leftIsPrimary) secondaryBattery else primaryBattery,
            caseBatteryPercent = battery(chargeAndCase and 0x0F),
            leftCharging = if (leftIsPrimary) primaryCharging else secondaryCharging,
            rightCharging = if (leftIsPrimary) secondaryCharging else primaryCharging,
            caseCharging = chargeFlags and CHARGE_CASE != 0,
            leftInEar = !inCase && (if (leftIsPrimary) primaryInEar else secondaryInEar),
            rightInEar = !inCase && (if (leftIsPrimary) secondaryInEar else primaryInEar),
            lidOpen = lidByte and LID_CLOSED == 0,
            lidOpenCounter = lidByte and LID_COUNTER_MASK,
            color = AirPodsColor.fromByte(data.u8(9)),
        )
    }

    /** Nibble → percent, with Apple's 0x0F "no data" and out-of-range guard. */
    private fun battery(nibble: Int): Int? = when {
        nibble == BATTERY_UNKNOWN_NIBBLE -> null
        nibble in 0..10 -> nibble * 10
        else -> null
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF
}
