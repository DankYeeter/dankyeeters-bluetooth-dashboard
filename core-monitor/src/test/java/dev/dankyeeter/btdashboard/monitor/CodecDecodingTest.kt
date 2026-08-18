package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.ChannelMode
import dev.dankyeeter.btdashboard.monitor.codec.CodecDecoding
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.codec.CodecStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecDecodingTest {

    @Test
    fun `known codec ids map to families`() {
        assertEquals(CodecFamily.SBC, CodecDecoding.codecFamily(0))
        assertEquals(CodecFamily.AAC, CodecDecoding.codecFamily(1))
        assertEquals(CodecFamily.LDAC, CodecDecoding.codecFamily(4))
    }

    @Test
    fun `vendor aptX Adaptive ids are recognised`() {
        assertEquals(CodecFamily.APTX_ADAPTIVE, CodecDecoding.codecFamily(8))
    }

    @Test
    fun `unknown and null codec ids degrade instead of throwing`() {
        assertEquals(CodecFamily.UNKNOWN, CodecDecoding.codecFamily(9999))
        assertEquals(CodecFamily.UNKNOWN, CodecDecoding.codecFamily(null))
    }

    @Test
    fun `codec names from text sources are matched loosely`() {
        assertEquals(CodecFamily.APTX_ADAPTIVE, CodecDecoding.codecFamilyFromName("aptX Adaptive"))
        assertEquals(CodecFamily.APTX_HD, CodecDecoding.codecFamilyFromName("APTX_HD"))
        assertEquals(CodecFamily.APTX, CodecDecoding.codecFamilyFromName("aptx"))
        assertEquals(CodecFamily.LDAC, CodecDecoding.codecFamilyFromName(" ldac "))
        assertEquals(CodecFamily.UNKNOWN, CodecDecoding.codecFamilyFromName(null))
    }

    @Test
    fun `single-bit masks decode to their value`() {
        assertEquals(44_100, CodecDecoding.sampleRate(0x1))
        assertEquals(96_000, CodecDecoding.sampleRate(0x8))
        assertEquals(24, CodecDecoding.bitsPerSample(0x2))
        assertEquals(ChannelMode.STEREO, CodecDecoding.channelMode(0x2))
    }

    @Test
    fun `ambiguous capability masks return unknown rather than a guess`() {
        // An OEM handing us a capability mask in a "selected" config must not
        // produce a confident 192 kHz badge.
        assertNull(CodecDecoding.sampleRate(0x3))
        assertNull(CodecDecoding.sampleRate(0))
        assertNull(CodecDecoding.bitsPerSample(0x7))
        assertEquals(ChannelMode.UNKNOWN, CodecDecoding.channelMode(0x3))
    }

    @Test
    fun `capability masks expand to a list`() {
        assertEquals(listOf(44_100, 48_000, 96_000), CodecDecoding.supportedSampleRates(0xB))
    }

    @Test
    fun `ldac quality index maps to bitrate`() {
        assertEquals(909, CodecDecoding.ldacBitrateKbps(1000L))
        assertEquals(303, CodecDecoding.ldacBitrateKbps(1002L))
        assertNull(CodecDecoding.ldacBitrateKbps(1003L))
    }

    @Test
    fun `badge renders only the fields we actually know`() {
        val status = CodecStatus(
            family = CodecFamily.LDAC,
            sampleRateHz = 96_000,
            bitsPerSample = 24,
            bitrateKbps = 606,
        )
        assertEquals("LDAC · 96 kHz · 24 bit · 606 kbps", status.badge)
        assertEquals("Unknown", CodecStatus(CodecFamily.UNKNOWN).badge)
        assertEquals("AAC · 44.1 kHz", CodecStatus(CodecFamily.AAC, sampleRateHz = 44_100).badge)
    }
}
