package dev.dankyeeter.btdashboard.transfer

import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import dev.dankyeeter.btdashboard.audio.eq.EqBands
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.hearing.AncMode
import dev.dankyeeter.btdashboard.hearing.Audiogram
import dev.dankyeeter.btdashboard.hearing.AudiogramRun
import dev.dankyeeter.btdashboard.hearing.CLINICAL_FREQUENCIES_HZ
import dev.dankyeeter.btdashboard.hearing.ClinicalAudiogram
import dev.dankyeeter.btdashboard.hearing.CompensationProfile
import dev.dankyeeter.btdashboard.hearing.DerivedCalibration
import dev.dankyeeter.btdashboard.hearing.TEST_FREQUENCIES_HZ
import dev.dankyeeter.btdashboard.hearing.ThresholdPoint
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip and validation tests for the export/import format.
 *
 * The point of these is that a backup written on the Pixel 8 Pro loads
 * byte-identically on the Pixel 11 Pro — so every test either checks
 * `decode(encode(x)) == x` or checks that a deliberately broken file is
 * refused with a message a human can act on.
 */
class BackupCodecTest {

    // ---- fixtures -------------------------------------------------------------

    /** Matches [BackupCodec]'s reader settings; see [reparse] for why it exists. */
    private val rawJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun points(offset: Double = 0.0): List<ThresholdPoint> =
        TEST_FREQUENCIES_HZ.mapIndexed { i, hz ->
            ThresholdPoint(
                frequencyHz = hz,
                thresholdDb = 10.0 + i * 2.5 + offset,
                responseCount = 2,
                presentationCount = 5,
                converged = i != 3,
            )
        }

    private fun run(id: String = "run-1"): AudiogramRun = AudiogramRun(
        id = id,
        timestampMillis = 1_700_000_000_000L,
        deviceAddressHash = "hash-abc",
        calibrationPresetId = "noble_encore",
        ancMode = AncMode.ANC_ON,
        ambientNoiseDbA = 32.5,
        left = points(),
        right = points(offset = 1.5),
    )

    private fun eq(): EqSettings = EqSettings(
        enabled = true,
        leftGainsDb = List(EqBands.COUNT) { it * 0.5f },
        rightGainsDb = List(EqBands.COUNT) { it * 0.25f },
        preGainDb = -4.5f,
        limiterEnabled = true,
    ).sanitized()

    private fun profile(id: String = "profile-1"): CompensationProfile = CompensationProfile(
        id = id,
        name = "Encore evening",
        createdAtMillis = 1_700_000_500_000L,
        audiogram = Audiogram(runIds = listOf("run-1"), left = points(), right = points(2.0)),
        calibrationPresetId = "noble_encore",
        ancMode = AncMode.TRANSPARENCY,
        intensity = 0.55f,
        partialFactor = 1.0f,
        eq = eq(),
    )

    /** The owner's ENT result: flat 10 dB HL, 15 dB at 125/250 on the right. */
    private fun clinical(): ClinicalAudiogram = ClinicalAudiogram(
        leftDbHl = CLINICAL_FREQUENCIES_HZ.associateWith { 10.0 },
        rightDbHl = CLINICAL_FREQUENCIES_HZ.associateWith { if (it <= 250) 15.0 else 10.0 },
        measuredOn = "2026-08-14",
        source = "ENT practice",
        savedAtMillis = 1_700_000_900_000L,
    )

    /** One headphone's transfer result, warnings and provenance included. */
    private fun derivedCalibration(): DerivedCalibration = DerivedCalibration(
        deviceKey = "hash-abc",
        deviceName = "Noble FoKus Prestige Encore",
        responseDeviationDb = listOf(2.0, 1.0, 0.0, -1.5, -3.0, -1.0, 1.5, -2.0),
        earSpreadDb = 6.5,
        warnings = listOf("The two ears disagree by up to 6.5 dB about the device."),
        createdAtMillis = 1_700_000_800_000L,
        sourceRunIds = listOf("run-1", "run-2"),
    )

    private fun document(): BackupDocument = BackupMapper.buildDocument(
        runs = listOf(run("run-1"), run("run-2")),
        audiogram = Audiogram(listOf("run-1", "run-2"), points(), points(1.0)),
        profiles = listOf(profile()),
        eq = eq(),
        activeProfileId = "profile-1",
        appVersion = "0.2.0",
        nowMillis = 1_700_001_000_000L,
        clinical = clinical(),
        derivedCalibrations = listOf(derivedCalibration()),
    )

    private fun decodeOrFail(raw: String): BackupDocument =
        when (val result = BackupCodec.decode(raw)) {
            is BackupParseResult.Success -> result.document
            is BackupParseResult.Failure -> throw AssertionError("expected success: ${result.message}")
        }

    private fun failureOf(raw: String): String =
        when (val result = BackupCodec.decode(raw)) {
            is BackupParseResult.Failure -> result.message
            is BackupParseResult.Success -> throw AssertionError("expected failure, got success")
        }

    /**
     * Reads a backup file straight into the wire model, skipping
     * [BackupCodec]'s record validation.
     *
     * That validation still measures every curve against a hardcoded ten bands,
     * so a 20- or 31-band export is dropped before it ever reaches the mapper.
     * The layout tests below are about what the *format* and the mapper do with
     * a wide curve, which is the part that silently destroyed it; they would
     * otherwise be testing the ten-band gate instead.
     */
    private fun reparse(raw: String): BackupDocument =
        rawJson.decodeFromString(BackupDocument.serializer(), raw)

    /**
     * A file as the pre-layout build wrote it: gains, and no `layout` key at
     * all. Written out by hand rather than encoded, because the whole point is
     * a field the current encoder always emits.
     */
    private fun legacyJson(gains: List<Float>): String {
        val list = gains.joinToString(",")
        return """
            {
              "schemaVersion": 1,
              "format": "${BackupSchema.FORMAT_ID}",
              "appVersion": "0.1.0",
              "exportedAtMillis": 1700000000000,
              "eq": {
                "enabled": true,
                "leftGainsDb": [$list],
                "rightGainsDb": [$list],
                "preGainDb": -3.0,
                "limiterEnabled": true
              }
            }
        """.trimIndent()
    }

    private fun wideEq(): EqSettings = EqSettings(
        enabled = true,
        layout = EqBandLayout.HALF_OCTAVE_20,
        leftGainsDb = List(EqBandLayout.HALF_OCTAVE_20.bandCount) { -6f + it * 0.5f },
        rightGainsDb = List(EqBandLayout.HALF_OCTAVE_20.bandCount) { 6f - it * 0.5f },
        preGainDb = -6f,
    ).sanitized()

    // ---- round trip -----------------------------------------------------------

    @Test
    fun `document survives an encode-decode round trip unchanged`() {
        val original = document()
        assertEquals(original, decodeOrFail(BackupCodec.encode(original)))
    }

    @Test
    fun `hearing runs round trip through the domain models`() {
        val original = run()
        val restored = BackupMapper.toDomain(
            decodeOrFail(BackupCodec.encode(document())).hearingRuns.first(),
        )
        assertEquals(original, restored)
    }

    @Test
    fun `compensation profiles round trip through the domain models`() {
        val restored = BackupMapper.toDomain(
            decodeOrFail(BackupCodec.encode(document())).profiles.first(),
        )
        assertEquals(profile(), restored)
    }

    @Test
    fun `eq settings round trip through the domain models`() {
        val restored = BackupMapper.toDomain(decodeOrFail(BackupCodec.encode(document())).eq!!)
        assertEquals(eq(), restored)
    }

    @Test
    fun `a null audiogram and no active profile round trip`() {
        val doc = BackupMapper.buildDocument(
            runs = listOf(run()),
            audiogram = null,
            profiles = emptyList(),
            eq = EqSettings.FLAT,
            activeProfileId = null,
            appVersion = "0.2.0",
            nowMillis = 1L,
        )
        val restored = decodeOrFail(BackupCodec.encode(doc))
        assertNull(restored.audiogram)
        assertNull(restored.activeProfileId)
    }

    @Test
    fun `encoded output carries the format marker and current schema version`() {
        val raw = BackupCodec.encode(document())
        assertTrue(raw.contains(BackupSchema.FORMAT_ID))
        assertEquals(BackupSchema.CURRENT_VERSION, decodeOrFail(raw).schemaVersion)
    }

    // ---- band layouts ---------------------------------------------------------

    @Test
    fun `a twenty band profile keeps its layout and its exact gains on import`() {
        val original = profile("profile-wide").copy(eq = wideEq())
        val doc = BackupMapper.buildDocument(
            runs = listOf(run()),
            audiogram = null,
            profiles = listOf(original),
            eq = wideEq(),
            activeProfileId = "profile-wide",
            appVersion = "0.3.0",
            nowMillis = 1L,
        )

        val restored = BackupMapper.toDomain(reparse(BackupCodec.encode(doc)).profiles.first())

        assertEquals(EqBandLayout.HALF_OCTAVE_20, restored.eq.layout)
        assertEquals(EqBandLayout.HALF_OCTAVE_20.bandCount, restored.eq.bandCount)
        // Not merely the right length: the same numbers, unresampled.
        assertEquals(original.eq.leftGainsDb, restored.eq.leftGainsDb)
        assertEquals(original.eq.rightGainsDb, restored.eq.rightGainsDb)
        assertEquals(original, restored)
    }

    @Test
    fun `the exported eq carries its layout id`() {
        val raw = BackupCodec.encode(document())
        assertEquals(EqBandLayout.OCTAVE_10.id, reparse(raw).eq!!.layout)
    }

    @Test
    fun `a legacy file with no layout field and ten gains imports as octave bands`() {
        val gains = List(EqBandLayout.OCTAVE_10.bandCount) { it * 0.5f }

        val restored = BackupMapper.toDomain(decodeOrFail(legacyJson(gains)).eq!!)

        assertEquals(EqBandLayout.OCTAVE_10, restored.layout)
        assertEquals(gains, restored.leftGainsDb)
        assertEquals(gains, restored.rightGainsDb)
    }

    @Test
    fun `a legacy file with no layout field and twenty gains infers the wide layout`() {
        val gains = List(EqBandLayout.HALF_OCTAVE_20.bandCount) { -5f + it * 0.5f }

        val restored = BackupMapper.toDomain(reparse(legacyJson(gains)).eq!!)

        assertEquals(EqBandLayout.HALF_OCTAVE_20, restored.layout)
        assertEquals(EqBandLayout.HALF_OCTAVE_20.bandCount, restored.bandCount)
        assertEquals(gains, restored.leftGainsDb)
    }

    @Test
    fun `a curve whose length matches no layout degrades to flat rather than to noise`() {
        val restored = BackupMapper.toDomain(
            BackupEq(enabled = true, leftGainsDb = List(7) { 5f }, rightGainsDb = List(7) { 5f }),
        )
        assertEquals(EqBandLayout.DEFAULT, restored.layout)
        assertTrue(restored.leftGainsDb.all { it == 0f })
    }

    // ---- validation -----------------------------------------------------------

    @Test
    fun `empty input is refused`() {
        assertTrue(failureOf("   ").contains("empty"))
    }

    @Test
    fun `non json input is refused without throwing`() {
        assertTrue(failureOf("this is not json at all").isNotBlank())
    }

    @Test
    fun `a foreign json file is refused by the format marker`() {
        val raw = """{"schemaVersion":1,"format":"some-other-app"}"""
        assertTrue(failureOf(raw).contains("not written by this app"))
    }

    @Test
    fun `a newer schema version is refused with an actionable message`() {
        val raw = BackupCodec.encode(
            document().copy(schemaVersion = BackupSchema.CURRENT_VERSION + 1),
        )
        val message = failureOf(raw)
        assertTrue(message.contains("schema version"))
        assertTrue(message.contains("Update the app"))
    }

    @Test
    fun `a zero schema version is refused`() {
        assertTrue(failureOf(BackupCodec.encode(document().copy(schemaVersion = 0))).isNotBlank())
    }

    @Test
    fun `an empty backup is refused as having nothing importable`() {
        val raw = BackupCodec.encode(
            BackupDocument(hearingRuns = emptyList(), profiles = emptyList(), eq = null),
        )
        assertTrue(failureOf(raw).contains("nothing importable"))
    }

    @Test
    fun `unknown fields from a future build do not break the import`() {
        val raw = BackupCodec.encode(document())
            .replaceFirst("{", """{"someFutureField": 42,""")
        assertNotNull(decodeOrFail(raw).eq)
    }

    // ---- partial damage: drop the record, keep the file ------------------------

    @Test
    fun `a run without an id is dropped with a warning`() {
        val raw = BackupCodec.encode(
            document().let { it.copy(hearingRuns = it.hearingRuns + BackupRun("", 1L, null, calibrationPresetId = "generic_uncalibrated")) },
        )
        val result = BackupCodec.decode(raw) as BackupParseResult.Success
        assertEquals(2, result.document.hearingRuns.size)
        assertTrue(result.warnings.any { it.contains("without an id") })
    }

    @Test
    fun `a run with no thresholds is dropped with a warning`() {
        val empty = BackupRun("run-empty", 1L, null, calibrationPresetId = "generic_uncalibrated")
        val raw = BackupCodec.encode(document().let { it.copy(hearingRuns = it.hearingRuns + empty) })
        val result = BackupCodec.decode(raw) as BackupParseResult.Success
        assertTrue(result.document.hearingRuns.none { it.id == "run-empty" })
        assertTrue(result.warnings.any { it.contains("no thresholds") })
    }

    @Test
    fun `a profile with a malformed eq curve is dropped with a warning`() {
        val broken = BackupMapper.toBackup(profile("profile-broken"))
            .let { it.copy(eq = it.eq.copy(leftGainsDb = listOf(1f, 2f))) }
        val raw = BackupCodec.encode(document().let { it.copy(profiles = it.profiles + broken) })
        val result = BackupCodec.decode(raw) as BackupParseResult.Success
        assertTrue(result.document.profiles.none { it.id == "profile-broken" })
        assertTrue(result.warnings.any { it.contains("malformed") })
    }

    @Test
    fun `a malformed top level eq curve is ignored but the rest imports`() {
        val raw = BackupCodec.encode(
            document().let { it.copy(eq = it.eq!!.copy(rightGainsDb = emptyList())) },
        )
        val result = BackupCodec.decode(raw) as BackupParseResult.Success
        assertNull(result.document.eq)
        assertEquals(2, result.document.hearingRuns.size)
        assertTrue(result.warnings.any { it.contains("EQ curve") })
    }

    @Test
    fun `a dangling active profile id is dropped`() {
        val raw = BackupCodec.encode(document().copy(activeProfileId = "does-not-exist"))
        val result = BackupCodec.decode(raw) as BackupParseResult.Success
        assertNull(result.document.activeProfileId)
        assertTrue(result.warnings.any { it.contains("active profile") })
    }

    @Test
    fun `a clean file produces no warnings`() {
        val result = BackupCodec.decode(BackupCodec.encode(document())) as BackupParseResult.Success
        assertTrue(result.warnings.toString(), result.warnings.isEmpty())
    }

    // ---- hardening: an edited file must not reach the audio chain unchecked ----

    @Test
    fun `out of range imported gains are clamped by the mapper`() {
        val hostile = BackupEq(
            enabled = true,
            leftGainsDb = List(EqBands.COUNT) { 99f },
            rightGainsDb = List(EqBands.COUNT) { -99f },
            preGainDb = 40f,
            limiterEnabled = true,
        )
        val settings = BackupMapper.toDomain(hostile)
        assertTrue(settings.leftGainsDb.all { it <= EqBands.MAX_GAIN_DB })
        assertTrue(settings.rightGainsDb.all { it >= EqBands.MIN_GAIN_DB })
        // Headroom must end up negative enough to cover the boosted bands.
        assertTrue(settings.preGainDb <= -EqBands.MAX_GAIN_DB)
    }

    @Test
    fun `an unknown anc mode degrades to UNKNOWN`() {
        val restored = BackupMapper.toDomain(
            BackupMapper.toBackup(run()).copy(ancMode = "SOMETHING_NEW"),
        )
        assertEquals(AncMode.UNKNOWN, restored.ancMode)
    }

    @Test
    fun `an out of range intensity is clamped on import`() {
        val restored = BackupMapper.toDomain(
            BackupMapper.toBackup(profile()).copy(intensity = 4f, partialFactor = -1f),
        )
        assertEquals(1f, restored.intensity, 0f)
        assertEquals(0f, restored.partialFactor, 0f)
    }

    // ---- clinical audiogram ---------------------------------------------------

    @Test
    fun `the clinical audiogram survives the round trip`() {
        // The most valuable record in the file: runs can be re-measured in
        // twenty minutes, this needs another appointment.
        val restored = decodeOrFail(BackupCodec.encode(document())).clinicalAudiogram
        assertNotNull(restored)
        assertEquals(clinical(), BackupMapper.toDomain(restored!!))
    }

    @Test
    fun `a file without a clinical audiogram still loads`() {
        val legacy = document().copy(clinicalAudiogram = null)
        assertNull(decodeOrFail(BackupCodec.encode(legacy)).clinicalAudiogram)
    }

    @Test
    fun `an untested frequency stays absent rather than coming back as zero`() {
        val sparse = ClinicalAudiogram(leftDbHl = mapOf(1000 to 30.0, 4000 to 45.0))
        val restored = BackupMapper.toDomain(BackupMapper.toBackup(sparse))
        assertEquals(setOf(1000, 4000), restored.leftDbHl.keys)
        assertNull(restored.leftDbHl[750])
        assertTrue(restored.rightDbHl.isEmpty())
    }

    @Test
    fun `a frequency key that is not a number is dropped, not fatal`() {
        val broken = BackupMapper.toBackup(clinical())
            .copy(leftDbHl = mapOf("1000" to 20.0, "not-a-frequency" to 30.0))
        val restored = BackupMapper.toDomain(broken)
        assertEquals(mapOf(1000 to 20.0), restored.leftDbHl)
    }

    // ---- derived calibrations -------------------------------------------------

    /**
     * Worth as much as the clinical audiogram it was built from: re-deriving
     * needs that appointment *plus* the runs *plus* the same headphone worn the
     * same way, and the runs are pruned at twenty.
     */
    @Test
    fun `derived calibrations survive the round trip`() {
        val restored = decodeOrFail(BackupCodec.encode(document())).derivedCalibrations

        assertEquals(1, restored.size)
        assertEquals(derivedCalibration(), BackupMapper.toDomain(restored.single()))
    }

    @Test
    fun `a file written before derived calibrations existed still loads`() {
        val legacy = document().copy(derivedCalibrations = emptyList())

        assertTrue(decodeOrFail(BackupCodec.encode(legacy)).derivedCalibrations.isEmpty())
    }

    /**
     * A deviation list that does not align with the test frequencies is refused
     * rather than padded: `CalibrationPreset` requires the alignment in its
     * constructor, and padding one would invent a device response at the
     * frequencies it filled in.
     */
    @Test
    fun `a misaligned record is refused instead of padded`() {
        val broken = BackupMapper.toBackup(derivedCalibration())
            .copy(responseDeviationDb = listOf(1.0, 2.0))

        assertNull(BackupMapper.toDomain(broken))
        assertNull(BackupMapper.toDomain(broken.copy(deviceKey = "")))
    }
}
