package dev.dankyeeter.btdashboard.ui.screens.devices

import dev.dankyeeter.btdashboard.system.devices.BluetoothCodecOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The codec picker shows every codec the app can express, but only some of them
 * are usable with the headphone that is connected at that moment.
 *
 * The case that produced this: a profile was set to aptX HD, the Focal Bathys
 * did not offer it in that negotiation, the request went out twice and the
 * stack ignored it — and nothing in the picker had suggested the choice was
 * futile.
 */
class CodecAvailabilityTest {

    @Test
    fun `an unknown offering greys out nothing`() {
        // No helper, or the device is not connected. The app has no evidence
        // about the headphone, so it must not imply any.
        assertTrue(unavailableCodecs(null).isEmpty())
    }

    @Test
    fun `an empty offering also greys out nothing`() {
        // The no-op controller answers with an empty list when it cannot ask.
        // Reading that as "supports nothing" would grey out the entire menu.
        assertTrue(unavailableCodecs(emptyList()).isEmpty())
    }

    @Test
    fun `everything the device does not offer is unavailable`() {
        val offered = listOf("APTX", "AAC", "SBC")

        val unavailable = unavailableCodecs(offered)

        assertTrue("APTX_HD" in unavailable)
        assertTrue("LDAC" in unavailable)
        offered.forEach { assertFalse("$it must stay selectable", it in unavailable) }
    }

    @Test
    fun `every unavailable entry is a codec the app actually offers`() {
        val unavailable = unavailableCodecs(listOf("SBC"))

        assertTrue(unavailable.all { it in BluetoothCodecOptions.codecs })
    }

    /**
     * A connected link always runs on some codec, so the field never claims
     * nothing is set. There is no "Leave alone" entry for this reason: it would
     * name a state Bluetooth cannot be in.
     */
    @Test
    fun `without a stored wish the field shows the live codec`() {
        assertEquals("APTX", codecToShow(preference = null, negotiated = "APTX"))
        assertEquals(CodecOrigin.NEGOTIATED, codecOrigin(preference = null, negotiated = "APTX"))
    }

    @Test
    fun `a stored wish wins over what is running now`() {
        // The field is labelled "on connect", so it has to promise the next
        // connect, not describe the present one.
        assertEquals("LDAC", codecToShow(preference = "LDAC", negotiated = "APTX"))
        assertEquals(CodecOrigin.STORED, codecOrigin(preference = "LDAC", negotiated = "APTX"))
    }

    @Test
    fun `the same codec reads differently depending on where it came from`() {
        // Identical value, different meaning — collapsing these would let the
        // app claim a setting it never made.
        assertEquals(CodecOrigin.STORED, codecOrigin(preference = "AAC", negotiated = "AAC"))
        assertEquals(CodecOrigin.NEGOTIATED, codecOrigin(preference = null, negotiated = "AAC"))
    }

    @Test
    fun `nothing connected names no codec at all`() {
        assertEquals(null, codecToShow(preference = null, negotiated = null))
        assertEquals(CodecOrigin.NONE, codecOrigin(preference = null, negotiated = null))
    }

    /**
     * Connected-but-unreadable is not the same as nothing connected, and saying
     * so was a plain lie on screen: with the privileged helper down the A2DP
     * read throws and the dumpsys fallback is gone, so a Focal Bathys sitting
     * there playing music was labelled "Not connected".
     */
    @Test
    fun `a connected device with an unreadable codec is not called disconnected`() {
        assertEquals(
            CodecOrigin.UNREADABLE,
            codecOrigin(preference = null, negotiated = null, deviceConnected = true),
        )
    }

    @Test
    fun `a readable codec beats the unreadable state`() {
        assertEquals(
            CodecOrigin.NEGOTIATED,
            codecOrigin(preference = null, negotiated = "APTX", deviceConnected = true),
        )
    }

    @Test
    fun `usable entries come first and the rest keep their order`() {
        val options = listOf(
            "a" to "A",
            "b" to "B",
            "c" to "C",
            "d" to "D",
        )

        val ordered = orderByAvailability(options, unavailable = setOf("a", "c"))

        assertEquals(listOf("b", "d", "a", "c"), ordered.map { it.first })
    }

    @Test
    fun `nothing is dropped when reordering`() {
        val options = listOf("a" to "A", "b" to "B", "c" to "C")

        val ordered = orderByAvailability(options, unavailable = setOf("a", "b", "c"))

        // All three are unusable, but a picker that quietly empties itself is
        // worse than one that explains why every row is grey.
        assertEquals(options.size, ordered.size)
        assertEquals(options.toSet(), ordered.toSet())
    }
}
