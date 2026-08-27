package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.ChannelMode
import dev.dankyeeter.btdashboard.monitor.codec.CodecDecoding
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.codec.CodecStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecDecodingTest {

    private fun load(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("dumps/$name")) {
            "missing test resource dumps/$name"
        }.bufferedReader().use { it.readText() }

    @Test
    fun `known codec ids map to families`() {
        assertEquals(CodecFamily.SBC, CodecDecoding.codecFamily(0))
        assertEquals(CodecFamily.AAC, CodecDecoding.codecFamily(1))
        assertEquals(CodecFamily.LDAC, CodecDecoding.codecFamily(4))
    }

    /**
     * Every `{codecName:X,mCodecType:N}` pair in the **verbatim** Pixel 11
     * capture, checked from both directions.
     *
     * These are the codecs the name-first change must leave exactly where they
     * were. For AOSP's 0..6 the name and the number agree, which is the whole
     * reason preferring the name costs nothing: it only changes an answer where
     * the number was never reliable in the first place.
     */
    @Test
    fun `the verbatim dump's codec blobs decode the same by name and by number`() {
        val blobs = Regex("""codecName:([A-Za-z0-9 ]+),mCodecType:(\d+)""")
            .findAll(load("bt_manager_pixel11_ldac_txqueue.txt"))
            .map { it.groupValues[1] to it.groupValues[2].toInt() }
            .toSet()
        assertEquals(
            setOf("SBC" to 0, "AAC" to 1, "AptX" to 2, "LDAC" to 4, "Opus" to 6),
            blobs,
        )
        blobs.forEach { (name, type) ->
            val byName = CodecDecoding.codecFamilyFromName(name)
            assertNotEquals("$name must be named at all", CodecFamily.UNKNOWN, byName)
            assertEquals("$name by type $type", byName, CodecDecoding.codecFamily(type))
            assertEquals("$name with type $type", byName, CodecDecoding.codecFamily(name, type))
        }
    }

    /**
     * aptX HD, the one label in the group the fixtures do not carry.
     *
     * `bt_manager_pixel11_ldac_txqueue.txt` lists only what that headphone
     * negotiated and offered, so type 3 has to be pinned on its own rather than
     * left to a capture that has never printed it.
     */
    @Test
    fun `aptX HD keeps type 3 and its spelling`() {
        assertEquals(CodecFamily.APTX_HD, CodecDecoding.codecFamily(3))
        assertEquals(CodecFamily.APTX_HD, CodecDecoding.codecFamily("AptX-HD", 3))
        assertEquals("aptX HD", CodecFamily.APTX_HD.label(3))
    }

    /**
     * The line this fix comes from, read off the owner's Pixel 11 Pro with
     * `dumpsys bluetooth_manager`: `{codecName:LHDCv5,mCodecType:7,...}`.
     *
     * No fixture carries it — every capture in `dumps/` predates an LHDC link —
     * so the pair stands here as the datum it is.
     */
    @Test
    fun `LHDCv5 on codec type 7 is decided by its name`() {
        assertEquals(CodecFamily.LHDC_V5, CodecDecoding.codecFamily("LHDCv5", 7))
        assertEquals(CodecFamily.LHDC_V5, CodecDecoding.codecFamilyFromName("LHDCv5"))
        assertEquals("LHDC v5", CodecFamily.LHDC_V5.label(7))
    }

    /**
     * The bug, stated as a test: a bare number in the vendor range must not
     * come back with a brand on it.
     */
    @Test
    fun `a vendor codec type with no name is never guessed into a brand`() {
        val family = CodecDecoding.codecFamily(codecName = null, rawType = 7)
        assertNotEquals(CodecFamily.APTX_ADAPTIVE, family)
        assertEquals(CodecFamily.VENDOR, family)
        assertEquals("Vendor codec (type 7)", family.label(7))
        assertEquals(
            "Vendor codec (type 7) · 96 kHz",
            CodecStatus(family, rawCodecType = 7, sampleRateHz = 96_000).badge,
        )
    }

    /**
     * The ids this app used to claim for aptX Adaptive — 7, 8, 9 and 10 — kept
     * as one list so the reason they went is not lost: type 7 turned out to be
     * LHDCv5 on a shipping build, which makes every member of the set a guess
     * rather than just that one wrong.
     */
    @Test
    fun `no numeric id names aptX Adaptive`() {
        listOf(7, 8, 9, 10).forEach { type ->
            assertEquals("type $type", CodecFamily.VENDOR, CodecDecoding.codecFamily(type))
        }
        // The name still names it. That path was always the right one.
        assertEquals(CodecFamily.APTX_ADAPTIVE, CodecDecoding.codecFamily("aptX Adaptive", 8))
    }

    @Test
    fun `unknown and null codec ids degrade instead of throwing`() {
        assertEquals(CodecFamily.UNKNOWN, CodecDecoding.codecFamily(null))
        // Negative ids are markers, not codecs, so they name nothing at all.
        assertEquals(CodecFamily.UNKNOWN, CodecDecoding.codecFamily(-2))
        // A number nobody has ever seen is still a number the user can look up.
        assertEquals(CodecFamily.VENDOR, CodecDecoding.codecFamily(9999))
        assertEquals("Vendor codec (type 9999)", CodecFamily.VENDOR.label(9999))
        // With no id to print it degrades rather than showing a naked noun.
        assertEquals("Vendor codec", CodecFamily.VENDOR.label(null))
    }

    @Test
    fun `codec names from text sources are matched loosely`() {
        assertEquals(CodecFamily.APTX_ADAPTIVE, CodecDecoding.codecFamilyFromName("aptX Adaptive"))
        assertEquals(CodecFamily.APTX_HD, CodecDecoding.codecFamilyFromName("APTX_HD"))
        assertEquals(CodecFamily.APTX, CodecDecoding.codecFamilyFromName("aptx"))
        assertEquals(CodecFamily.LDAC, CodecDecoding.codecFamilyFromName(" ldac "))
        assertEquals(CodecFamily.UNKNOWN, CodecDecoding.codecFamilyFromName(null))
    }

    /**
     * A name that matches nothing must not swallow the type that came with it.
     *
     * This is the shape every call site used to be written in by hand, and the
     * reason it is one function now: `name?.let(::codecFamilyFromName)` returns
     * UNKNOWN rather than null for an unrecognised name, so the elvis fallback
     * to the number silently never ran.
     */
    @Test
    fun `an unrecognised name falls through to the number`() {
        assertEquals(CodecFamily.LDAC, CodecDecoding.codecFamily("UNKNOWN CODEC(4)", 4))
        assertEquals(CodecFamily.VENDOR, CodecDecoding.codecFamily("UNKNOWN CODEC(7)", 7))
        assertEquals(CodecFamily.UNKNOWN, CodecDecoding.codecFamily("nonsense", null))
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
