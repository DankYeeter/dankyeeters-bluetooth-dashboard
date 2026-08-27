package dev.dankyeeter.btdashboard.ui.screens.monitor

import dev.dankyeeter.btdashboard.monitor.codec.BtAudioDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The raw/display boundary for the live tuning control.
 *
 * This is a regression test with a device behind it. Tapping "990 kbps" on the
 * owner's phone answered *"XX:XX:XX:XX:37:8F is not a Bluetooth address"*: the
 * panel was handing the codec call the same string it renders, and on a user
 * build `dumpsys` redacts that string. Two rules come out of it, and both are
 * pinned here because either one alone is still a bug:
 *
 *  - what goes **down** to the controller must be the real address;
 *  - what comes **up** to the screen must not be.
 */
class MonitorTuningAddressTest {

    private val raw = "AC:DE:48:00:37:8F"
    private val redacted = "XX:XX:XX:XX:37:8F"

    private fun connected(vararg addresses: String) =
        addresses.map { BtAudioDevice(address = it, name = "Bathys") }

    @Test
    fun `the redacted address resolves to the profile's real one`() {
        val resolved = rawAddressFor(redacted, connected(raw))

        assertEquals(raw, resolved)
        // The point of the whole exercise: nothing that reaches the helper may
        // still be wearing the dump's redaction.
        assertFalse("the tuning call must not receive a masked address", resolved!!.contains("XX"))
    }

    @Test
    fun `an already raw address is passed through`() {
        // Userdebug builds do not redact, and the join must be a no-op there
        // rather than a second lookup that could fail.
        assertEquals(raw, rawAddressFor(raw, connected(raw)))
    }

    @Test
    fun `the right device is picked out of several`() {
        val other = "AC:DE:48:00:11:22"

        assertEquals(raw, rawAddressFor(redacted, connected(other, raw)))
    }

    @Test
    fun `nothing on the profile means no address rather than the masked one`() {
        // Never fall back to the shown string: that is exactly the call that
        // produced "is not a Bluetooth address", dressed up as a helper fault.
        assertNull(rawAddressFor(redacted, connected()))
        assertNull(rawAddressFor(redacted, connected("AC:DE:48:00:11:22")))
    }

    @Test
    fun `no live device means no call`() {
        assertNull(rawAddressFor(null, connected(raw)))
        assertNull(rawAddressFor("", connected(raw)))
    }

    @Test
    fun `masking keeps the two octets the platform prints and is idempotent`() {
        assertEquals(redacted, maskAddress(raw))
        assertEquals(redacted, maskAddress(maskAddress(raw)))
        // Not an address: left alone rather than mangled into one.
        assertEquals("Bathys", maskAddress("Bathys"))
    }

    @Test
    fun `a message from below cannot carry a real address to the screen`() {
        val fromHelper = "codec on $raw was not observed after 2000 ms"

        val shown = redactAddresses(fromHelper)

        assertFalse("a raw address reached a UI string", shown.contains(raw, ignoreCase = true))
        assertTrue(shown.contains(redacted))
        // The sentence still has to be worth reading afterwards.
        assertTrue(shown.contains("was not observed after 2000 ms"))
    }

    @Test
    fun `redaction leaves ordinary text alone`() {
        val plain = "LDAC is now LDAC · 96 kHz · 32 bit — read back, not just requested."

        assertEquals(plain, redactAddresses(plain))
    }
}
