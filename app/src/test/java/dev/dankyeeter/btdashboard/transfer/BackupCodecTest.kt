package dev.dankyeeter.btdashboard.transfer

import dev.dankyeeter.btdashboard.audio.eq.EqBands
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.hearing.AncMode
import dev.dankyeeter.btdashboard.hearing.Audiogram
import dev.dankyeeter.btdashboard.hearing.AudiogramRun
import dev.dankyeeter.btdashboard.hearing.CompensationProfile
import dev.dankyeeter.btdashboard.hearing.TEST_FREQUENCIES_HZ
import dev.dankyeeter.btdashboard.hearing.ThresholdPoint
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

    private fun document(): BackupDocument = BackupMapper.buildDocument(
        runs = listOf(run("run-1"), run("run-2")),
        audiogram = Audiogram(listOf("run-1", "run-2"), points(), points(1.0)),
        profiles = listOf(profile()),
        eq = eq(),
        activeProfileId = "profile-1",
        appVersion = "0.2.0",
        nowMillis = 1_700_001_000_000L,
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
            document().let { it.copy(hearingRuns = it.hearingRuns + BackupRun("", 1L, null, "generic_uncalibrated")) },
        )
        val result = BackupCodec.decode(raw) as BackupParseResult.Success
        assertEquals(2, result.document.hearingRuns.size)
        assertTrue(result.warnings.any { it.contains("without an id") })
    }

    @Test
    fun `a run with no thresholds is dropped with a warning`() {
        val empty = BackupRun("run-empty", 1L, null, "generic_uncalibrated")
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
}
