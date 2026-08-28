package dev.dankyeeter.btdashboard.ui.tuning

import dev.dankyeeter.btdashboard.monitor.link.live.LdacQualityMode
import dev.dankyeeter.btdashboard.system.devices.CodecApplyOutcome
import dev.dankyeeter.btdashboard.system.devices.CodecPreference
import dev.dankyeeter.btdashboard.system.devices.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the two sets of bitrate chips share.
 *
 * The Monitoring panel and the Bluetooth tab draw the same four chips for the
 * same headphone. Before this component existed each owned its own ladder and
 * its own "which one is lit" rule, and the second screen was the moment that
 * stopped being survivable: two rules over one link is two answers to a question
 * that has one. Everything here is pure, which is the point — the shared rule is
 * checkable without a phone, a helper or a Compose tree.
 */
class LdacQualityTest {

    private val key = "0123456789abcdef0123456789abcdef"

    private fun profile(preference: CodecPreference? = null) =
        DeviceProfile(deviceKey = key, name = "Bathys", codecPreference = preference)

    // ---- the ladder ---------------------------------------------------------

    @Test
    fun `the chips are the four values AOSP can be asked for`() {
        assertEquals(listOf(1000L, 1001L, 1002L, 1003L), LdacQuality.pinnable)
        // "Never chosen" is a state, not a request, so it cannot be a chip.
        assertTrue(LdacQuality.NONE !in LdacQuality.pinnable)
    }

    @Test
    fun `the chip labels follow the sample-rate family`() {
        // 48/96 kHz runs 990/660/330; 44.1/88.2 runs 909/606/303. One label for
        // both would be off by 8 % on half of all links.
        assertEquals("990 kbps", LdacQuality.chipLabel(LdacQuality.HIGH_QUALITY, 96_000))
        assertEquals("909 kbps", LdacQuality.chipLabel(LdacQuality.HIGH_QUALITY, 44_100))
        // Adaptive has no single rate to name, and says so rather than guessing.
        assertEquals("ABR", LdacQuality.chipLabel(LdacQuality.ADAPTIVE, 96_000))
        // A screen with no live reading gets the 48 kHz family, which is what a
        // phone streams — never a blank or a zero.
        assertEquals("660 kbps", LdacQuality.chipLabel(LdacQuality.STANDARD, null))
    }

    // ---- which chip is lit --------------------------------------------------

    /**
     * The consistency requirement, stated as a test: one stored value, one chip,
     * whichever screen is asking.
     *
     * The Monitoring panel can see the live link and passes its mode; the
     * Bluetooth tab cannot and passes nothing. With something stored they must
     * agree anyway, because the stored wish is what the next connect will ask
     * for and that is what the control promises.
     */
    @Test
    fun `both screens light the same chip for one stored value`() {
        LdacQuality.pinnable.forEach { stored ->
            val onMonitoring = LdacQuality.selected(stored, LdacQualityMode.NOT_PINNED)
            val onBluetoothTab = LdacQuality.selected(stored)

            assertEquals("stored $stored", onMonitoring, onBluetoothTab)
            assertEquals(stored, onBluetoothTab)
        }
    }

    @Test
    fun `a stored wish outranks what the link happens to be doing`() {
        // A link running adaptive while the profile asks for 990 is a story —
        // the pinned row above the chips tells it. The chip stays on the wish.
        assertEquals(
            LdacQuality.HIGH_QUALITY,
            LdacQuality.selected(LdacQuality.HIGH_QUALITY, LdacQualityMode.NOT_PINNED),
        )
    }

    @Test
    fun `with nothing stored the live mode answers`() {
        // Somebody who pinned a rate in Android's own Developer options has not
        // stored anything here, and the panel must not claim they chose ABR.
        assertEquals(
            LdacQuality.STANDARD,
            LdacQuality.selected(LdacQuality.NONE, LdacQualityMode.STANDARD),
        )
    }

    @Test
    fun `with nothing stored and nothing observed the answer is ABR`() {
        // Not a guess: an unpinned LDAC link runs adaptive, which is what the
        // ABR chip means. Lighting nothing would read as a broken control.
        assertEquals(LdacQuality.ADAPTIVE, LdacQuality.selected(LdacQuality.NONE))
        assertEquals(
            LdacQuality.ADAPTIVE,
            LdacQuality.selected(LdacQuality.NONE, LdacQualityMode.UNKNOWN),
        )
    }

    // ---- reading a stored profile -------------------------------------------

    @Test
    fun `a quality on a codec without the knob is ignored rather than shown`() {
        val nonsense = profile(CodecPreference(codec = "AAC", ldacQuality = 1000L))

        assertEquals(LdacQuality.NONE, LdacQuality.storedQuality(nonsense))
    }

    @Test
    fun `no profile and no wish both read as nothing stored`() {
        assertEquals(LdacQuality.NONE, LdacQuality.storedQuality(null))
        assertEquals(LdacQuality.NONE, LdacQuality.storedQuality(profile()))
    }

    // ---- writing a stored profile -------------------------------------------

    /**
     * The round trip the whole feature rests on: a tap becomes a stored wish,
     * and reading that wish back lights the chip that was tapped.
     */
    @Test
    fun `pinning a rate round-trips through the profile`() {
        listOf(LdacQuality.HIGH_QUALITY, LdacQuality.STANDARD, LdacQuality.CONNECTION_PRIORITY)
            .forEach { quality ->
                val stored = LdacQuality.withQuality(profile(), quality).sanitized()

                assertEquals("LDAC", stored.codecPreference?.codec)
                assertEquals(quality, stored.codecPreference?.ldacQuality)
                // Sanitising is what stands between restored data and a write to
                // the Bluetooth stack, so surviving it is part of the contract.
                assertEquals(quality, LdacQuality.storedQuality(stored))
                assertEquals(quality, LdacQuality.selected(LdacQuality.storedQuality(stored)))
            }
    }

    /**
     * ABR is the resting state, so choosing it *withdraws* the rate instead of
     * storing a request for 1003.
     *
     * Storing 1003 would mean renegotiating the codec on every connect to reach
     * the state an unpinned link is already in — an audible hiccup for nothing.
     * It is the same distinction absolute volume draws between "off" and "hand
     * it back to Android".
     */
    @Test
    fun `choosing ABR withdraws the rate and keeps the codec wish`() {
        val pinned = LdacQuality.withQuality(profile(), LdacQuality.HIGH_QUALITY)

        val withdrawn = LdacQuality.withQuality(pinned, LdacQuality.ADAPTIVE).sanitized()

        assertEquals("LDAC", withdrawn.codecPreference?.codec)
        assertEquals(0L, withdrawn.codecPreference?.ldacQuality)
        assertEquals(LdacQuality.ADAPTIVE, LdacQuality.selected(LdacQuality.storedQuality(withdrawn)))
    }

    @Test
    fun `choosing ABR on a profile that asked for nothing invents no codec wish`() {
        // A profile with every field at "leave alone" must stay that way: a
        // stored LDAC wish would start renegotiating a link nobody asked about.
        val untouched = LdacQuality.withQuality(profile(), LdacQuality.ADAPTIVE)

        assertNull(untouched.codecPreference)
    }

    @Test
    fun `pinning does not disturb the rest of the profile`() {
        val full = profile().copy(mediaVolumePercent = 40, autoApply = false)

        val stored = LdacQuality.withQuality(full, LdacQuality.STANDARD)

        assertEquals(40, stored.mediaVolumePercent)
        assertEquals(false, stored.autoApply)
        assertEquals("Bathys", stored.name)
    }

    @Test
    fun `pinning keeps the sub-settings an existing codec wish carried`() {
        val detailed = profile(
            CodecPreference(codec = "LDAC", sampleRateHz = 96_000, bitsPerSample = 32),
        )

        val stored = LdacQuality.withQuality(detailed, LdacQuality.HIGH_QUALITY).sanitized()

        assertEquals(96_000, stored.codecPreference?.sampleRateHz)
        assertEquals(32, stored.codecPreference?.bitsPerSample)
        assertEquals(1000L, stored.codecPreference?.ldacQuality)
    }
}

/**
 * The sentence the chips leave behind, which is never allowed to be silent and
 * never allowed to merge "saved" with "in force".
 */
class LdacTuningSentenceTest {

    @Test
    fun `a read-back is reported as a read-back`() {
        val text = tuningSentence(
            CodecApplyOutcome.Applied("LDAC · 96 kHz · 32 bit"),
            persisted = true,
        )

        assertTrue(text.contains("Stored for this headphone"))
        assertTrue(text.contains("read back, not just requested"))
    }

    /**
     * Nothing reachable from an app tells a refusal apart from a renegotiation
     * still in flight, so this must not be worded as a failure — and the
     * helper's own detail has to survive into the sentence.
     */
    @Test
    fun `a rate that did not stick is neither claimed nor called a failure`() {
        val text = tuningSentence(
            CodecApplyOutcome.NotObserved("LDAC · 48 kHz", "still adaptive after 2000 ms"),
            persisted = true,
        )

        assertTrue(text.contains("still reads"))
        assertTrue(text.contains("still adaptive after 2000 ms"))
        assertTrue("a request must never read as a result", !text.contains("is now"))
    }

    /**
     * The state the persistence work exists for: no helper, so nothing changed
     * on the link — and the choice is stored anyway and will be asked for again.
     * Saying only "not changed" here would hide the half that did work.
     */
    @Test
    fun `no helper still reports what was stored`() {
        val text = tuningSentence(
            CodecApplyOutcome.Unavailable("the privileged helper is not running"),
            persisted = true,
        )

        assertTrue(text.contains("Stored for this headphone"))
        assertTrue(text.contains("asked for again on every connect"))
        assertTrue(text.contains("not changed on the link right now"))
        assertTrue(text.contains("the privileged helper is not running"))
    }

    @Test
    fun `nothing stored never claims a save`() {
        val text = tuningSentence(
            CodecApplyOutcome.Unavailable("there is no live link to change"),
            persisted = false,
        )

        assertTrue("stored" !in text.lowercase())
        assertTrue(text.contains("was not changed"))
    }
}
