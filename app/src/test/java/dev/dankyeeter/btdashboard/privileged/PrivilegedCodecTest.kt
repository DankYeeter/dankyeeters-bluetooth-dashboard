package dev.dankyeeter.btdashboard.privileged

import dev.dankyeeter.btdashboard.monitor.codec.ChannelMode
import dev.dankyeeter.btdashboard.monitor.codec.CodecDecoding
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.system.devices.BluetoothCodecOptions
import dev.dankyeeter.btdashboard.system.devices.CodecPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The codec arithmetic and the rules about what may be requested.
 *
 * None of this needs a phone, which is the point of keeping it out of
 * [HelperBluetooth]: the reflection there cannot be tested off-device, so
 * everything that *can* be is separated from it.
 */
class PrivilegedCodecTest {

    // ---- masks --------------------------------------------------------------

    @Test
    fun `every sample rate survives a round trip through both directions`() {
        // A2dpCodecMasks writes and CodecDecoding reads. They are separate
        // tables on purpose, and separate tables are exactly the kind of thing
        // that drifts, so the round trip is asserted rather than assumed.
        A2dpCodecMasks.offeredSampleRatesHz.forEach { hz ->
            val mask = A2dpCodecMasks.sampleRateMask(hz)
            assertNotNull("no mask for $hz Hz", mask)
            assertEquals("$hz Hz", hz, CodecDecoding.sampleRate(mask!!))
        }
    }

    @Test
    fun `every bit depth survives a round trip`() {
        A2dpCodecMasks.offeredBitsPerSample.forEach { bits ->
            val mask = A2dpCodecMasks.bitsMask(bits)
            assertNotNull("no mask for $bits bit", mask)
            assertEquals(bits, CodecDecoding.bitsPerSample(mask!!))
        }
    }

    @Test
    fun `every channel mode survives a round trip`() {
        listOf(ChannelModes.MONO, ChannelModes.STEREO, ChannelModes.DUAL).forEach { mode ->
            val mask = A2dpCodecMasks.channelMask(mode)
            assertNotNull(mask)
            assertEquals(mode, ChannelModes.fromChannelMode(CodecDecoding.channelMode(mask!!)))
        }
    }

    @Test
    fun `zero means no preference, not an unknown value`() {
        // AOSP spells "no preference" as SAMPLE_RATE_NONE etc., all zero. A null
        // here would be a refusal, and refusing "leave it alone" would make
        // every partial codec wish unexpressible.
        assertEquals(A2dpCodecMasks.NONE, A2dpCodecMasks.sampleRateMask(0))
        assertEquals(A2dpCodecMasks.NONE, A2dpCodecMasks.bitsMask(0))
        assertEquals(A2dpCodecMasks.NONE, A2dpCodecMasks.channelMask(ChannelModes.UNSPECIFIED))
    }

    @Test
    fun `a value nobody defined is refused rather than rounded`() {
        assertNull(A2dpCodecMasks.sampleRateMask(45_000))
        assertNull(A2dpCodecMasks.bitsMask(12))
        assertNull(A2dpCodecMasks.channelMask(9))
    }

    // ---- what may be requested ---------------------------------------------

    @Test
    fun `codec ids match the ones the reader decodes`() {
        A2dpCodecMasks.writableFamilies.forEach { family ->
            val id = A2dpCodecMasks.codecType(family)!!
            assertEquals(family, CodecDecoding.codecFamily(id))
        }
    }

    @Test
    fun `aptX Adaptive can be read by name but never requested`() {
        // Only the name reads it. The ids this used to assert - 7 first among
        // them - name no codec: a Pixel 11 Pro dump prints type 7 as LHDCv5,
        // so a number in that range is a vendor codec and nothing more.
        assertEquals(CodecFamily.APTX_ADAPTIVE, CodecDecoding.codecFamily("aptX Adaptive", 7))
        assertEquals(CodecFamily.VENDOR, CodecDecoding.codecFamily(7))
        assertNull(A2dpCodecMasks.codecType(CodecFamily.APTX_ADAPTIVE))
        assertFalse(CodecFamily.APTX_ADAPTIVE in A2dpCodecMasks.writableFamilies)
        assertNotNull(A2dpCodecMasks.reject(CodecRequest(CodecFamily.APTX_ADAPTIVE)))
    }

    /**
     * The families that exist only to label what was read. Requesting one means
     * sending a codec type, and neither has an id this app is entitled to pick.
     */
    @Test
    fun `vendor families are readable labels, not requestable codecs`() {
        listOf(CodecFamily.LHDC_V5, CodecFamily.VENDOR).forEach { family ->
            assertNull(family.name, A2dpCodecMasks.codecType(family))
            assertFalse(family.name, family in A2dpCodecMasks.writableFamilies)
            assertNotNull(family.name, A2dpCodecMasks.reject(CodecRequest(family)))
        }
    }

    @Test
    fun `unknown is never requestable`() {
        assertNull(A2dpCodecMasks.codecType(CodecFamily.UNKNOWN))
        assertNotNull(A2dpCodecMasks.reject(CodecRequest(CodecFamily.UNKNOWN)))
    }

    @Test
    fun `the two modules agree on which codecs exist`() {
        // core-system validates stored profiles and cannot see CodecFamily;
        // :app owns the AOSP mapping. Two lists, one truth - so if one grows a
        // codec and the other does not, a stored profile would either be
        // dropped on load or refused on send, both silently.
        assertEquals(
            A2dpCodecMasks.writableFamilies.map { it.name }.sorted(),
            BluetoothCodecOptions.codecs.sorted(),
        )
        assertEquals(A2dpCodecMasks.offeredSampleRatesHz, BluetoothCodecOptions.sampleRatesHz)
        assertEquals(A2dpCodecMasks.offeredBitsPerSample, BluetoothCodecOptions.bitsPerSample)
        assertEquals(A2dpCodecMasks.LDAC_QUALITIES, BluetoothCodecOptions.ldacQualities)
    }

    @Test
    fun `a plain codec request with no other opinions is accepted`() {
        assertNull(A2dpCodecMasks.reject(CodecRequest(CodecFamily.LDAC)))
        assertNull(
            A2dpCodecMasks.reject(
                CodecRequest(CodecFamily.LDAC, 96_000, 24, ChannelModes.STEREO, 1000L),
            ),
        )
    }

    @Test
    fun `an LDAC quality anywhere but LDAC is refused`() {
        // Not cosmetic: codecSpecific1 means something different per codec, so
        // carrying 1000 into an SBC config would set a field nobody chose.
        val refusal = A2dpCodecMasks.reject(
            CodecRequest(CodecFamily.SBC, ldacQuality = 1000L),
        )
        assertNotNull(refusal)
        assertTrue(refusal!!.contains("LDAC"))
    }

    @Test
    fun `an invented LDAC quality is refused`() {
        assertNotNull(A2dpCodecMasks.reject(CodecRequest(CodecFamily.LDAC, ldacQuality = 42L)))
    }

    @Test
    fun `the helper validates the raw integers, not a reconstructed request`() {
        // The helper cannot re-derive a CodecRequest from what crossed the
        // Binder without first trusting it, so it checks the numbers instead.
        val ldac = CodecDecoding.SOURCE_CODEC_TYPE_LDAC
        assertNull(A2dpCodecMasks.rejectRaw(ldac, 48_000, 24, ChannelModes.STEREO, 1001L))

        // An id that decodes to a family we refuse to write.
        assertNotNull(A2dpCodecMasks.rejectRaw(7, 0, 0, 0, 0L))
        // An id that decodes to nothing at all.
        assertNotNull(A2dpCodecMasks.rejectRaw(99, 0, 0, 0, 0L))
        assertNotNull(A2dpCodecMasks.rejectRaw(-1, 0, 0, 0, 0L))
        // Values off the tables.
        assertNotNull(A2dpCodecMasks.rejectRaw(ldac, 45_000, 0, 0, 0L))
        assertNotNull(A2dpCodecMasks.rejectRaw(ldac, 0, 12, 0, 0L))
        assertNotNull(A2dpCodecMasks.rejectRaw(ldac, 0, 0, 9, 0L))
    }

    @Test
    fun `the app-side and helper-side checks agree`() {
        A2dpCodecMasks.writableFamilies.forEach { family ->
            val request = CodecRequest(family, 48_000, 16, ChannelModes.STEREO)
            val appSide = A2dpCodecMasks.reject(request)
            val helperSide = A2dpCodecMasks.rejectRaw(
                A2dpCodecMasks.codecType(family)!!,
                request.sampleRateHz,
                request.bitsPerSample,
                request.channelMode,
                request.ldacQuality,
            )
            assertEquals("$family", appSide, helperSide)
        }
    }

    // ---- observations -------------------------------------------------------

    @Test
    fun `an observation names the codec it read, not the one asked for`() {
        val observation = CodecObservation(
            family = CodecFamily.APTX.name,
            sampleRateHz = 48_000,
            bitsPerSample = 16,
            channelMode = ChannelModes.STEREO,
            matched = false,
            note = "still renegotiating",
        )
        assertEquals(CodecFamily.APTX, observation.codecFamily)
        assertTrue(observation.summary.startsWith("aptX"))
        assertTrue(observation.summary.contains("48000 Hz"))
    }

    @Test
    fun `an unreadable codec degrades to Unknown rather than to a wrong name`() {
        val observation = CodecObservation(family = "")
        assertEquals(CodecFamily.UNKNOWN, observation.codecFamily)
        assertTrue(observation.selectableFamilies.isEmpty())
    }

    @Test
    fun `selectable names that this build does not know are dropped, not guessed`() {
        val observation = CodecObservation(
            family = CodecFamily.LDAC.name,
            selectable = listOf("LDAC", "SBC", "SOMETHING_NEW"),
        )
        assertEquals(listOf(CodecFamily.LDAC, CodecFamily.SBC), observation.selectableFamilies)
    }

    @Test
    fun `channel modes map both ways without losing unknown`() {
        assertEquals(ChannelMode.UNKNOWN, ChannelModes.toChannelMode(ChannelModes.UNSPECIFIED))
        assertEquals(ChannelModes.UNSPECIFIED, ChannelModes.fromChannelMode(ChannelMode.UNKNOWN))
        assertEquals(ChannelMode.DUAL_CHANNEL, ChannelModes.toChannelMode(ChannelModes.DUAL))
    }

    // ---- the stored shape ---------------------------------------------------

    @Test
    fun `a stored preference the registry still recognises stays valid`() {
        assertTrue(CodecPreference("LDAC", 96_000, 24, 2, 1000L).isValid)
        assertTrue(CodecPreference("SBC").isValid)
    }

    @Test
    fun `a stored preference that disagrees with itself is not valid`() {
        assertFalse(CodecPreference("APTX_ADAPTIVE").isValid)
        assertFalse(CodecPreference("SBC", ldacQuality = 1000L).isValid)
        assertFalse(CodecPreference("LDAC", sampleRateHz = 45_000).isValid)
        assertFalse(CodecPreference("LDAC", bitsPerSample = 12).isValid)
        assertFalse(CodecPreference("LDAC", channelMode = 9).isValid)
    }

    // ---- System Default ------------------------------------------------

    @Test
    fun `system default is a valid stored preference`() {
        assertTrue(CodecPreference(BluetoothCodecOptions.SYSTEM_DEFAULT).isValid)
    }

    /**
     * Handing the codec decision back while simultaneously forcing a sample
     * rate would be a contradiction — the profile would say "do not choose"
     * and "choose 96 kHz" at once.
     */
    @Test
    fun `system default refuses to carry sub-settings`() {
        val base = CodecPreference(BluetoothCodecOptions.SYSTEM_DEFAULT)
        assertFalse(base.copy(sampleRateHz = 96_000).isValid)
        assertFalse(base.copy(bitsPerSample = 24).isValid)
        assertFalse(base.copy(channelMode = 2).isValid)
        assertFalse(base.copy(ldacQuality = 1000L).isValid)
    }

    /**
     * The sentinel must never collide with a real AOSP codec type (those are
     * non-negative), and — the part that matters for mixed versions — an old
     * helper that predates it must reject it loudly via rejectRaw instead of
     * pinning whatever codec it mistakes the value for.
     */
    @Test
    fun `the sentinel is not a real codec type and old helpers reject it`() {
        assertTrue(A2dpCodecMasks.SYSTEM_DEFAULT_SENTINEL < 0)
        assertNotNull(
            A2dpCodecMasks.rejectRaw(A2dpCodecMasks.SYSTEM_DEFAULT_SENTINEL, 0, 0, 0, 0L),
        )
    }

    @Test
    fun `system default is not one of the orderable codecs`() {
        // It must stay out of the available/unavailable sorting: giving the
        // decision back is possible on every device, always.
        assertFalse(BluetoothCodecOptions.SYSTEM_DEFAULT in BluetoothCodecOptions.codecs)
    }
}
