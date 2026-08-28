package dev.dankyeeter.btdashboard.transfer

import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import dev.dankyeeter.btdashboard.audio.eq.EqBands
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.audio.eq.withVolumeTilt
import dev.dankyeeter.btdashboard.hearing.AncMode
import dev.dankyeeter.btdashboard.hearing.Audiogram
import dev.dankyeeter.btdashboard.hearing.AudiogramRun
import dev.dankyeeter.btdashboard.hearing.CLINICAL_FREQUENCIES_HZ
import dev.dankyeeter.btdashboard.hearing.ClinicalAudiogram
import dev.dankyeeter.btdashboard.hearing.CompensationProfile
import dev.dankyeeter.btdashboard.hearing.DerivedCalibration
import dev.dankyeeter.btdashboard.hearing.TEST_FREQUENCIES_HZ
import dev.dankyeeter.btdashboard.hearing.ThresholdPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Export → import over a spread of app states, rather than over the one state
 * somebody happened to write a fixture for.
 *
 * [BackupCodecTest] already pins the individual repairs this format has needed;
 * what it cannot do is say that the *next* field, layout or record shape will
 * survive, because each of its cases is one hand-built document. The wire format
 * has destroyed user data once already — a 20- or 31-band curve came back as its
 * first ten gains reinterpreted as octave bands, with the import reporting
 * success — and that shape was inside the space these cases walk, one layout
 * away from the fixture that was being tested.
 *
 * The states below are a **fixed, deterministic list**, not random draws: a
 * property test that shuffles finds a failure once and then cannot be asked
 * about it again. Every value here is a function of the case's own index, so a
 * failure names a state that can be reproduced by reading it.
 *
 * Two contracts are checked for every state:
 *
 *  - **identity** — `decode(encode(x))` is `x`, field for field, and the domain
 *    models come back equal to the ones that went in;
 *  - **no warnings** — a file this app wrote is not "half valid". Anything that
 *    would drop a record on the way back in is a bug in the writer.
 *
 * Plus the older shapes, because a backup is a contract with every file the user
 * has ever written: a missing `layout`, a missing `clinicalAudiogram`, a missing
 * `derivedCalibrations`, and a file carrying fields this build has never heard of.
 */
class BackupRoundTripPropertyTest {

    // ---- the synthetic states ---------------------------------------------------

    /** One complete app state, named so a failure says which one broke. */
    private data class AppState(
        val name: String,
        val runs: List<AudiogramRun>,
        val audiogram: Audiogram?,
        val profiles: List<CompensationProfile>,
        val eq: EqSettings,
        val activeProfileId: String?,
        val clinical: ClinicalAudiogram?,
        val derived: List<DerivedCalibration>,
    )

    private fun points(offset: Double, converged: (Int) -> Boolean = { true }): List<ThresholdPoint> =
        TEST_FREQUENCIES_HZ.mapIndexed { i, hz ->
            ThresholdPoint(
                frequencyHz = hz,
                thresholdDb = -72.0 + i * 2.5 + offset,
                responseCount = 2 + i % 3,
                presentationCount = 5 + i,
                converged = converged(i),
            )
        }

    private fun run(
        id: String,
        volumeFraction: Double = 0.7,
        deviceAddressHash: String? = "hash-abc",
        deviceName: String? = "Focal Bathys",
        ancMode: AncMode = AncMode.ANC_ON,
        ambientNoiseDbA: Double? = 32.5,
        converged: (Int) -> Boolean = { true },
    ) = AudiogramRun(
        id = id,
        timestampMillis = 1_700_000_000_000L + id.hashCode().toLong(),
        deviceAddressHash = deviceAddressHash,
        calibrationPresetId = "focal_bathys",
        ancMode = ancMode,
        ambientNoiseDbA = ambientNoiseDbA,
        left = points(0.0, converged),
        right = points(1.5, converged),
        deviceName = deviceName,
        volumeFraction = volumeFraction,
    )

    /**
     * A curve for [layout] in one of a few deliberately awkward shapes.
     *
     * `sanitized()` is applied because that is what the importer does on the way
     * back in: a state that is not already sanitised could not round trip to
     * itself, and demanding that it did would be testing the wrong contract.
     */
    private fun eqFor(
        layout: EqBandLayout,
        shape: String,
        enabled: Boolean = true,
        loudnessRestoration: Boolean = false,
        autoHeadroom: Boolean = true,
        volumeAwareTilt: Boolean = false,
    ): EqSettings {
        val n = layout.bandCount
        val left = when (shape) {
            "flat" -> List(n) { 0f }
            // Both rails, alternating: the largest possible band-to-band step.
            "rails" -> List(n) { if (it % 2 == 0) EqBands.MAX_GAIN_DB else EqBands.MIN_GAIN_DB }
            "max" -> List(n) { EqBands.MAX_GAIN_DB }
            "min" -> List(n) { EqBands.MIN_GAIN_DB }
            // Quarter-decibel steps, i.e. what a drag on the slider produces.
            else -> List(n) { -6f + it * 0.25f }
        }
        val right = left.asReversed().toList()
        return EqSettings(
            enabled = enabled,
            layout = layout,
            leftGainsDb = left,
            rightGainsDb = right,
            preGainDb = -3.5f,
            limiterEnabled = shape != "flat",
            autoHeadroom = autoHeadroom,
            loudnessRestoration = loudnessRestoration,
            volumeAwareTilt = volumeAwareTilt,
        ).sanitized()
    }

    private fun profile(
        id: String,
        eq: EqSettings,
        audiogram: Audiogram?,
        intensity: Float = 0.6f,
        partialFactor: Float = 1f,
        ancMode: AncMode = AncMode.TRANSPARENCY,
    ) = CompensationProfile(
        id = id,
        name = "Profile $id",
        createdAtMillis = 1_700_000_500_000L,
        audiogram = audiogram,
        calibrationPresetId = "noble_encore",
        ancMode = ancMode,
        intensity = intensity,
        partialFactor = partialFactor,
        eq = eq,
    )

    private fun derived(deviceKey: String, deviceName: String?, warnings: List<String>) =
        DerivedCalibration(
            deviceKey = deviceKey,
            deviceName = deviceName,
            responseDeviationDb = TEST_FREQUENCIES_HZ.mapIndexed { i, _ -> (i - 3) * 0.5 },
            earSpreadDb = 6.5,
            warnings = warnings,
            createdAtMillis = 1_700_000_800_000L,
            sourceRunIds = listOf("run-1", "run-2"),
        )

    private val fullClinical = ClinicalAudiogram(
        leftDbHl = CLINICAL_FREQUENCIES_HZ.associateWith { 10.0 },
        rightDbHl = CLINICAL_FREQUENCIES_HZ.associateWith { if (it <= 250) 15.0 else 10.0 },
        measuredOn = "2026-08-14",
        source = "ENT practice",
        savedAtMillis = 1_700_000_900_000L,
    )

    /**
     * The clinic left most of the form blank, which is the ordinary case — and
     * the one where a "fill the gaps" import would invent thresholds that were
     * never measured. Negative values are in on purpose: hearing better than the
     * young-normal median is printed as −5 or −10 dB HL.
     */
    private val sparseClinical = ClinicalAudiogram(
        leftDbHl = mapOf(1000 to -5.0, 4000 to 45.0),
        rightDbHl = mapOf(8000 to 60.0),
        measuredOn = "",
        source = "hand-entered",
        savedAtMillis = 0L,
    )

    /**
     * The spread. Every band layout crossed with the gain shapes that sit at the
     * edges of the format, plus the record-level variations that have each cost
     * a repair before: a profile with no measurement behind it, runs measured at
     * different volumes and on different devices, unconverged points, a sparse
     * clinical form, derived calibrations present and absent.
     */
    private fun states(): List<AppState> = buildList {
        EqBandLayout.entries.forEachIndexed { layoutIndex, layout ->
            listOf("flat", "rails", "max", "min", "steps").forEach { shape ->
                val eq = eqFor(
                    layout = layout,
                    shape = shape,
                    enabled = shape != "flat",
                    // Restoration changes what `sanitized` does to the headroom,
                    // so it has to appear on both sides of the round trip.
                    loudnessRestoration = shape == "max",
                    autoHeadroom = shape != "rails",
                    // The switch travels; the tilt gains it produces do not —
                    // they are derived from the volume at the moment the EQ is
                    // applied. Both halves of that are checked below.
                    volumeAwareTilt = shape == "steps" || shape == "min",
                )
                add(
                    AppState(
                        name = "${layout.id}/$shape",
                        runs = listOf(run("run-$layoutIndex-a")),
                        audiogram = Audiogram(listOf("run-$layoutIndex-a"), points(0.0), points(1.5)),
                        profiles = listOf(profile("p-${layout.id}-$shape", eq, null)),
                        eq = eq,
                        activeProfileId = "p-${layout.id}-$shape",
                        clinical = null,
                        derived = emptyList(),
                    ),
                )
            }
        }

        val wide = eqFor(EqBandLayout.HALF_OCTAVE_20, "steps")
        val narrow = eqFor(EqBandLayout.OCTAVE_10, "rails")

        add(
            AppState(
                name = "profile with an audiogram behind it",
                runs = listOf(run("run-m1"), run("run-m2")),
                audiogram = Audiogram(listOf("run-m1", "run-m2"), points(0.0), points(2.0)),
                profiles = listOf(
                    profile("p-measured", wide, Audiogram(listOf("run-m1"), points(0.0), points(2.0))),
                    profile("p-manual", narrow, null, intensity = 0f, partialFactor = 0f),
                ),
                eq = wide,
                activeProfileId = "p-measured",
                clinical = fullClinical,
                derived = listOf(derived("hash-abc", "Focal Bathys", emptyList())),
            ),
        )

        add(
            AppState(
                name = "runs at different volumes and devices",
                runs = listOf(
                    run("run-v40", volumeFraction = 0.4, deviceAddressHash = "hash-a", deviceName = "Bathys"),
                    run("run-v70", volumeFraction = 0.7, deviceAddressHash = "hash-b", deviceName = "Encore"),
                    run(
                        "run-v100",
                        volumeFraction = 1.0,
                        deviceAddressHash = null,
                        deviceName = null,
                        ancMode = AncMode.UNKNOWN,
                        ambientNoiseDbA = null,
                    ),
                ),
                audiogram = null,
                profiles = emptyList(),
                eq = narrow,
                activeProfileId = null,
                clinical = sparseClinical,
                derived = listOf(
                    derived("hash-a", "Bathys", listOf("The two ears disagree by up to 6.5 dB.")),
                    derived("hash-b", null, emptyList()),
                ),
            ),
        )

        add(
            AppState(
                name = "unconverged points at both ends of the range",
                runs = listOf(run("run-edges", converged = { it != 0 && it != TEST_FREQUENCIES_HZ.lastIndex })),
                audiogram = Audiogram(
                    runIds = listOf("run-edges"),
                    left = points(0.0) { it != 0 },
                    right = points(0.0) { it != TEST_FREQUENCIES_HZ.lastIndex },
                ),
                profiles = emptyList(),
                eq = eqFor(EqBandLayout.THIRD_OCTAVE_31, "steps"),
                activeProfileId = null,
                clinical = null,
                derived = emptyList(),
            ),
        )

        add(
            AppState(
                name = "nothing but a flat eq",
                runs = emptyList(),
                audiogram = null,
                profiles = emptyList(),
                eq = EqSettings.FLAT,
                activeProfileId = null,
                clinical = null,
                derived = emptyList(),
            ),
        )
    }

    // ---- helpers ----------------------------------------------------------------

    private fun documentOf(state: AppState): BackupDocument = BackupMapper.buildDocument(
        runs = state.runs,
        audiogram = state.audiogram,
        profiles = state.profiles,
        eq = state.eq,
        activeProfileId = state.activeProfileId,
        appVersion = "0.3.0",
        nowMillis = 1_700_001_000_000L,
        clinical = state.clinical,
        derivedCalibrations = state.derived,
    )

    private fun successOf(raw: String, name: String): BackupParseResult.Success =
        when (val result = BackupCodec.decode(raw)) {
            is BackupParseResult.Success -> result
            is BackupParseResult.Failure -> throw AssertionError("$name: ${result.message}")
        }

    // ---- identity ---------------------------------------------------------------

    @Test
    fun `every synthetic state survives the file round trip unchanged`() {
        states().forEach { state ->
            val document = documentOf(state)
            val restored = successOf(BackupCodec.encode(document), state.name)

            assertEquals(state.name, document, restored.document)
            assertTrue("${state.name}: ${restored.warnings}", restored.warnings.isEmpty())
        }
    }

    /**
     * The wire model coming back equal is necessary but not sufficient: the bug
     * that destroyed curves lived in the *mapper*, one call further in, and a
     * document that matched itself byte for byte still produced a different
     * `EqSettings`. So every record is mapped back to its domain model and
     * compared against the object that went in.
     */
    @Test
    fun `every record maps back to the domain object it came from`() {
        states().forEach { state ->
            val restored = successOf(BackupCodec.encode(documentOf(state)), state.name).document

            assertEquals("${state.name}: runs", state.runs, restored.hearingRuns.map(BackupMapper::toDomain))
            assertEquals(
                "${state.name}: profiles",
                state.profiles,
                restored.profiles.map(BackupMapper::toDomain),
            )
            assertEquals("${state.name}: eq", state.eq, BackupMapper.toDomain(restored.eq!!))
            assertEquals(
                "${state.name}: audiogram",
                state.audiogram,
                restored.audiogram?.let(BackupMapper::toDomain),
            )
            assertEquals(
                "${state.name}: clinical",
                state.clinical?.takeUnless { it.isEmpty },
                restored.clinicalAudiogram?.let(BackupMapper::toDomain),
            )
            assertEquals(
                "${state.name}: derived",
                state.derived,
                restored.derivedCalibrations.mapNotNull(BackupMapper::toDomain),
            )
            assertEquals("${state.name}: active", state.activeProfileId, restored.activeProfileId)
        }
    }

    /**
     * The band count is the thing the format got wrong once, so it is asserted
     * on its own rather than only as part of an object comparison: a curve that
     * comes back at the wrong resolution is a different curve even when every
     * other field matches.
     */
    @Test
    fun `no curve changes its band count or its gains across the round trip`() {
        states().forEach { state ->
            val restored = successOf(BackupCodec.encode(documentOf(state)), state.name).document
            val eq = BackupMapper.toDomain(restored.eq!!)

            assertEquals("${state.name}: layout", state.eq.layout, eq.layout)
            assertEquals("${state.name}: band count", state.eq.bandCount, eq.bandCount)
            assertEquals("${state.name}: left", state.eq.leftGainsDb, eq.leftGainsDb)
            assertEquals("${state.name}: right", state.eq.rightGainsDb, eq.rightGainsDb)

            state.profiles.zip(restored.profiles.map(BackupMapper::toDomain)).forEach { (before, after) ->
                assertEquals("${state.name}: ${before.id} layout", before.eq.layout, after.eq.layout)
                assertEquals("${state.name}: ${before.id} left", before.eq.leftGainsDb, after.eq.leftGainsDb)
                assertEquals("${state.name}: ${before.id} right", before.eq.rightGainsDb, after.eq.rightGainsDb)
            }
        }
    }

    /**
     * A sparse clinical form must stay sparse. The frequency the practice did
     * not test has to come back absent, never as 0 dB HL — which would read as
     * *perfect hearing at that frequency* and is the worst possible default for
     * the one absolute measurement in the whole app.
     */
    @Test
    fun `an untested clinical frequency never returns as a value`() {
        val state = states().first { it.clinical === sparseClinical }
        val restored = successOf(BackupCodec.encode(documentOf(state)), state.name).document

        val clinical = BackupMapper.toDomain(restored.clinicalAudiogram!!)
        assertEquals(setOf(1000, 4000), clinical.leftDbHl.keys)
        assertEquals(setOf(8000), clinical.rightDbHl.keys)
        CLINICAL_FREQUENCIES_HZ.filterNot { it == 1000 || it == 4000 }.forEach {
            assertNull("left $it Hz", clinical.leftDbHl[it])
        }
    }

    // ---- historic shapes --------------------------------------------------------

    /**
     * A backup as an older build wrote it: only the keys that build knew about.
     *
     * Written by hand rather than by removing keys from the encoder's output,
     * because the whole question is what happens to a key the encoder now always
     * emits. [withLayout], [withClinical] and [withDerived] each add one of the
     * fields that arrived later, so every historic combination can be produced.
     */
    private fun historicJson(
        gains: List<Float>,
        withLayout: String? = null,
        withClinical: Boolean = false,
        withDerived: Boolean = false,
        extraTopLevel: String = "",
        extraNested: String = "",
    ): String {
        val list = gains.joinToString(",")
        val layoutKey = withLayout?.let { """"layout": "$it",""" }.orEmpty()
        val clinical = if (withClinical) {
            """"clinicalAudiogram": {"leftDbHl": {"1000": 20.0}, "measuredOn": "2026-01-01"},"""
        } else {
            ""
        }
        val derived = if (withDerived) {
            """"derivedCalibrations": [{"deviceKey": "hash-abc",
                "responseDeviationDb": [${TEST_FREQUENCIES_HZ.joinToString(",") { "0.5" }}]}],"""
        } else {
            ""
        }
        return """
            {
              $extraTopLevel
              "schemaVersion": 1,
              "format": "${BackupSchema.FORMAT_ID}",
              "appVersion": "0.1.0",
              "exportedAtMillis": 1700000000000,
              $clinical
              $derived
              "eq": {
                $extraNested
                "enabled": true,
                $layoutKey
                "leftGainsDb": [$list],
                "rightGainsDb": [$list],
                "preGainDb": -3.0,
                "limiterEnabled": true
              }
            }
        """.trimIndent()
    }

    /**
     * The one that actually happened: files written before `layout` existed, at
     * all three band counts. The count is an unambiguous fallback because the
     * three layouts have three different counts, and it must stay one.
     */
    @Test
    fun `a file with no layout field infers the layout from its band count`() {
        EqBandLayout.entries.forEach { layout ->
            val gains = List(layout.bandCount) { -5f + it * 0.5f }
            val restored = BackupMapper.toDomain(
                successOf(historicJson(gains), "no layout, ${layout.id}").document.eq!!,
            )

            assertEquals(layout.id, layout, restored.layout)
            assertEquals(layout.id, layout.bandCount, restored.bandCount)
            // Not merely the right length: the same numbers, unresampled.
            assertEquals(layout.id, gains, restored.leftGainsDb)
            assertEquals(layout.id, gains, restored.rightGainsDb)
        }
    }

    /**
     * The volume-aware tilt: the switch survives, the derived layer does not
     * travel, and a file that predates the field reads as "off".
     *
     * The second half is the interesting one. The tilt gains are a function of
     * the media volume at the moment the EQ is applied, so writing them into a
     * backup would restore a correction for a volume the phone is not at — on a
     * phone whose volume curve may not even be the same one. A file must
     * therefore come back with the switch and with zeros.
     */
    @Test
    fun `the volume-aware tilt travels as a switch and never as a curve`() {
        listOf(false, true).forEach { on ->
            val eq = eqFor(EqBandLayout.OCTAVE_10, "steps", volumeAwareTilt = on)
                .withVolumeTilt(0.1f)
            val restored = BackupMapper.toDomain(BackupMapper.toBackup(eq))

            assertEquals("switch, on=$on", on, restored.volumeAwareTilt)
            assertEquals("gains, on=$on", List(eq.bandCount) { 0f }, restored.tiltGainsDb)
        }

        // And a file written before the field existed: absent means off, which
        // is what "no version bump" requires of every field added here.
        val historic = successOf(
            historicJson(List(EqBandLayout.OCTAVE_10.bandCount) { 1f }),
            "no tilt field",
        ).document
        assertFalse(historic.eq!!.volumeAwareTilt)
        assertFalse(BackupMapper.toDomain(historic.eq!!).volumeAwareTilt)
    }

    @Test
    fun `a file with no clinical audiogram and no derived calibrations still loads`() {
        val restored = successOf(
            historicJson(List(EqBandLayout.OCTAVE_10.bandCount) { 1f }),
            "bare file",
        ).document

        assertNull(restored.clinicalAudiogram)
        assertTrue(restored.derivedCalibrations.isEmpty())
        // The fields that were there all along still arrive.
        assertEquals(1, restored.schemaVersion)
        assertEquals("0.1.0", restored.appVersion)
        assertTrue(restored.eq!!.enabled)
    }

    /** Every historic combination of the three later fields, not just the extremes. */
    @Test
    fun `every combination of the later fields loads`() {
        val gains = List(EqBandLayout.HALF_OCTAVE_20.bandCount) { it * 0.25f }
        listOf(null, EqBandLayout.HALF_OCTAVE_20.id).forEach { layout ->
            listOf(false, true).forEach { clinical ->
                listOf(false, true).forEach { derived ->
                    val name = "layout=$layout clinical=$clinical derived=$derived"
                    val document = successOf(
                        historicJson(gains, layout, clinical, derived),
                        name,
                    ).document

                    assertEquals(name, clinical, document.clinicalAudiogram != null)
                    assertEquals(name, if (derived) 1 else 0, document.derivedCalibrations.size)
                    // The curve is read at its own resolution either way: with the
                    // id because it says so, without it because the count does.
                    val eq = BackupMapper.toDomain(document.eq!!)
                    assertEquals(name, EqBandLayout.HALF_OCTAVE_20, eq.layout)
                    assertEquals(name, gains, eq.leftGainsDb)
                }
            }
        }
    }

    /**
     * The forward half of the same contract: **a field this build has never seen
     * must be ignored, not fatal**, at every level of the document.
     *
     * This is the rule that lets the next field be added without a schema bump,
     * and it is only ever exercised by a file from a *newer* build — which is
     * exactly the file nobody has to hand while writing the code. Hence a
     * synthetic one, with unknown keys at the top level, inside `eq`, and inside
     * every nested record.
     */
    @Test
    fun `unknown fields anywhere in the file are ignored rather than fatal`() {
        val gains = List(EqBandLayout.OCTAVE_10.bandCount) { 2f }
        val document = successOf(
            historicJson(
                gains = gains,
                withLayout = EqBandLayout.OCTAVE_10.id,
                withClinical = true,
                withDerived = true,
                extraTopLevel = """"aFieldFromTheFuture": {"nested": [1, 2, 3]},""",
                extraNested = """"perBandQ": [0.7, 0.7], "tiltDb": 1.5,""",
            ),
            "unknown fields",
        ).document

        assertEquals(gains, BackupMapper.toDomain(document.eq!!).leftGainsDb)
        assertEquals(mapOf(1000 to 20.0), BackupMapper.toDomain(document.clinicalAudiogram!!).leftDbHl)
        assertEquals(1, document.derivedCalibrations.size)
    }

    /**
     * And the same for a file this build wrote, with one unknown key spliced in:
     * proves the rule holds for the full document rather than only for the
     * hand-written minimal one above.
     */
    @Test
    fun `an unknown field spliced into a full export changes nothing else`() {
        states().forEach { state ->
            val document = documentOf(state)
            val raw = BackupCodec.encode(document)
                .replaceFirst("{", """{"someFutureField": {"a": [1, 2]},""")

            assertEquals(state.name, document, successOf(raw, state.name).document)
        }
    }
}
