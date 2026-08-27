package dev.dankyeeter.btdashboard.hearing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sign and unit conventions of the compensation chain, as properties rather
 * than as examples.
 *
 * The bug these exist for: the Hughson-Westlake engine stores thresholds as
 * dBFS attenuation (negative, −90…−6) and those raw values went into NAL-R,
 * whose `H_T(f)` is dB HL (positive, 0 = no loss). Every term came out negative,
 * the `>= 0` clamp flattened all of it, and the app prescribed exactly nothing
 * for every realistic measurement. **No test noticed**, because every test fed
 * hand-written positive numbers — the convenient unit — and in that unit the
 * pipeline was right.
 *
 * So the tests below never pick one unit. Each one either sweeps a spread of
 * level conventions and asserts the same answer comes out of all of them, or
 * asserts an algebraic property that has to hold whatever the scale is. A
 * conversion that quietly disappears again cannot survive either kind.
 */
class UnitContractTest {

    private fun point(hz: Int, db: Double, converged: Boolean = true) =
        ThresholdPoint(frequencyHz = hz, thresholdDb = db, converged = converged)

    /**
     * The level conventions this chain has actually been handed, plus the ones
     * it could be handed next.
     *
     * −90…−6 is the engine's dBFS range; 0 is the boundary that hides a sign
     * error; 10…70 is dB HL as an ENT form prints it. A property that holds
     * across this list holds for a unit nobody has thought of yet.
     */
    private val levelConventions =
        listOf(-90.0, -72.0, -60.0, -45.0, -30.0, -6.0, 0.0, 10.0, 40.0, 70.0)

    /** A fixed spread of shapes, each named so a failure says which one broke. */
    private fun shapes(): List<Pair<String, List<Double>>> = listOf(
        "flat" to List(TEST_FREQUENCIES_HZ.size) { 0.0 },
        "gentle high-frequency slope" to TEST_FREQUENCIES_HZ.indices.map { it * 2.0 },
        "steep ski slope" to TEST_FREQUENCIES_HZ.indices.map { it * 7.0 },
        "noise notch at 4 kHz" to listOf(0.0, 0.0, 0.0, 5.0, 18.0, 30.0, 12.0, 6.0),
        "reverse slope" to TEST_FREQUENCIES_HZ.indices.map { (7 - it) * 4.0 },
        "single spike" to listOf(0.0, 0.0, 0.0, 0.0, 25.0, 0.0, 0.0, 0.0),
    )

    private fun audiogramOf(
        shape: List<Double>,
        level: Double,
        earOffset: Double = 0.0,
        converged: (Int) -> Boolean = { true },
    ) = Audiogram(
        runIds = listOf("r"),
        left = TEST_FREQUENCIES_HZ.mapIndexed { i, hz -> point(hz, level + shape[i], converged(i)) },
        right = TEST_FREQUENCIES_HZ.mapIndexed { i, hz ->
            point(hz, level + earOffset + shape[i], converged(i))
        },
    )

    private fun prescribe(audiogram: Audiogram) = NalRCompensationCalculator().computeDetailed(
        audiogram = audiogram,
        calibrationPresetId = CalibrationPresetRepository.GENERIC_ID,
        intensity = 1f,
        partialFactor = DEFAULT_PARTIAL_FACTOR,
    )

    // ---- asRelativeLossHl: the frame conversion itself --------------------------

    /**
     * The output unit is a *loss*, and a loss cannot be negative. This is the
     * single assertion that would have caught the original bug at its source:
     * the raw dBFS values it used to pass through are all negative.
     */
    @Test
    fun `every rebased threshold is a loss, never a negative number`() {
        shapes().forEach { (name, shape) ->
            levelConventions.forEach { level ->
                val rebased = audiogramOf(shape, level, earOffset = 3.0).asRelativeLossHl()
                (rebased.left + rebased.right).forEach {
                    assertTrue(
                        "$name at $level dB: ${it.frequencyHz} Hz came back ${it.thresholdDb}",
                        it.thresholdDb >= 0.0,
                    )
                }
            }
        }
    }

    /**
     * Exactly zero at the best converged point, across both ears. "Best" is the
     * anchor of the whole frame: if it drifted off zero the prescription would
     * pick up a constant tilt that belongs to the volume knob, not to the ears.
     */
    @Test
    fun `the best converged point is exactly zero and nothing sits below it`() {
        shapes().forEach { (name, shape) ->
            levelConventions.forEach { level ->
                val original = audiogramOf(shape, level, earOffset = 3.0)
                val best = (original.left + original.right).minOf { it.thresholdDb }
                val rebased = original.asRelativeLossHl()
                val all = rebased.left + rebased.right

                assertEquals("$name at $level dB", 0.0, all.minOf { it.thresholdDb }, 1e-9)
                // And the zero is at the point that was best, not merely somewhere.
                val bestFrequencies = original.left.filter { it.thresholdDb == best }.map { it.frequencyHz }
                bestFrequencies.forEach { hz ->
                    assertEquals(
                        "$name at $level dB, $hz Hz",
                        0.0,
                        rebased.left.first { it.frequencyHz == hz }.thresholdDb,
                        1e-9,
                    )
                }
            }
        }
    }

    /**
     * The property the unit bug violated, stated directly: the frame depends on
     * the *shape* and on nothing else. Two runs of the same ears at two volume
     * settings, or one written in dBFS and one in dB HL, must rebase to the same
     * loss curve — otherwise the level convention leaks into the prescription,
     * which is exactly what happened.
     */
    @Test
    fun `the rebase is invariant to the level convention it is handed`() {
        shapes().forEach { (name, shape) ->
            val reference = audiogramOf(shape, levelConventions.first(), earOffset = 3.0)
                .asRelativeLossHl()
            levelConventions.drop(1).forEach { level ->
                assertEquals(
                    "$name at $level dB",
                    reference,
                    audiogramOf(shape, level, earOffset = 3.0).asRelativeLossHl(),
                )
            }
        }
    }

    /**
     * Applying the conversion twice is applying it once.
     *
     * Not a curiosity: the calculator is reached from several screens and one of
     * them rebasing a curve that was already rebased is a plausible edit. In a
     * frame with a fixed anchor that is a no-op; in one with a per-call reference
     * it would shift the curve every time.
     */
    @Test
    fun `rebasing an already rebased audiogram changes nothing`() {
        shapes().forEach { (name, shape) ->
            val once = audiogramOf(shape, -72.0, earOffset = 4.0).asRelativeLossHl()
            assertEquals(name, once, once.asRelativeLossHl())
        }
    }

    /**
     * The asymmetry is the one thing a per-ear reference would erase, so the
     * global reference is pinned as a property rather than at one frequency: a
     * worse right ear stays worse by the same number of decibels it was worse by.
     */
    @Test
    fun `an ear offset survives the rebase unchanged`() {
        listOf(-8.0, -3.0, 0.0, 3.0, 12.0).forEach { offset ->
            val rebased = audiogramOf(shapes()[1].second, -70.0, earOffset = offset)
                .asRelativeLossHl()
            TEST_FREQUENCIES_HZ.forEach { hz ->
                val l = rebased.left.first { it.frequencyHz == hz }.thresholdDb
                val r = rebased.right.first { it.frequencyHz == hz }.thresholdDb
                // Only where the clamp is not in play: at the anchor itself the
                // better ear is pinned to zero and the difference is truncated.
                if (minOf(l, r) > 1e-9) {
                    assertEquals("offset $offset at $hz Hz", offset, r - l, 1e-9)
                }
            }
        }
    }

    /** No converged point anywhere means no reference, and no reference means no guess. */
    @Test
    fun `an entirely unconverged audiogram is handed back untouched`() {
        val nothing = audiogramOf(shapes()[1].second, -72.0, converged = { false })

        assertEquals(nothing, nothing.asRelativeLossHl())
    }

    // ---- the calculator's own unit contract -------------------------------------

    /**
     * Equal thresholds are no loss, whatever number they are equal *to*.
     *
     * This is the assertion that pins the unit at the calculator's door. A flat
     * curve carries no information about hearing at all — only about the volume
     * it was measured at — so the honest prescription is silence, and it has to
     * be silence at −90 dBFS, at 0, and at 40 dB HL alike.
     */
    @Test
    fun `all-equal thresholds prescribe nothing at every level convention`() {
        levelConventions.forEach { level ->
            val flat = audiogramOf(List(TEST_FREQUENCIES_HZ.size) { 0.0 }, level)
            val result = prescribe(flat.asRelativeLossHl())

            assertTrue(
                "level $level left: ${result.eq.leftGainsDb}",
                result.eq.leftGainsDb.all { it == 0f },
            )
            assertTrue(
                "level $level right: ${result.eq.rightGainsDb}",
                result.eq.rightGainsDb.all { it == 0f },
            )
            // No boost means no headroom to buy, and nothing worth reading out.
            assertEquals("level $level", 0f, result.eq.preGainDb, 1e-6f)
            assertNull("level $level", result.peakBand)
        }
    }

    /**
     * The whole pipeline, not just the rebase, depends on shape alone. Same
     * curve at ten different levels, ten identical EQ settings.
     */
    @Test
    fun `the prescription is invariant to the level convention`() {
        shapes().forEach { (name, shape) ->
            val reference = prescribe(audiogramOf(shape, levelConventions.first()).asRelativeLossHl()).eq
            levelConventions.drop(1).forEach { level ->
                assertEquals(
                    "$name at $level dB",
                    reference,
                    prescribe(audiogramOf(shape, level).asRelativeLossHl()).eq,
                )
            }
        }
    }

    /**
     * Why the rebase cannot be dropped again, written as the symptom it had.
     *
     * Raw dBFS thresholds do not make the calculator fail; they make it return
     * an all-zero curve, which is indistinguishable from "your hearing is fine"
     * and was shipped as exactly that. The output is pinned here so that anyone
     * removing `asRelativeLossHl` from a call site sees a test naming the
     * silence rather than a plausible-looking curve.
     */
    @Test
    fun `raw dBFS thresholds prescribe silence, which is why the rebase exists`() {
        val raw = audiogramOf(shapes()[1].second, -70.0)

        val withoutRebase = prescribe(raw)
        val withRebase = prescribe(raw.asRelativeLossHl())

        assertTrue(withoutRebase.eq.leftGainsDb.all { it == 0f })
        assertNull(withoutRebase.peakBand)
        // And the same measurement, correctly framed, is not silent at all.
        assertTrue(withRebase.eq.leftGainsDb.any { it > 0f })
    }

    /**
     * A sign flip in the measured curve is **visible**, not plausible.
     *
     * NAL-R prescribes most where the loss is greatest, so mirroring the
     * audiogram has to move the loudest band to the other end of the spectrum.
     * If it did not, a threshold list handed over with the wrong sign would
     * produce a curve that looks like a correction and corrects the wrong half.
     */
    @Test
    fun `a sign-flipped audiogram tilts the prescription the other way`() {
        val sloped = shapes()[1].second
        val rising = prescribe(audiogramOf(sloped, -70.0).asRelativeLossHl())
        val flipped = prescribe(audiogramOf(sloped.map { -it }, -70.0).asRelativeLossHl())

        val risingPeak = rising.peakBand!!
        val flippedPeak = flipped.peakBand!!
        // A high-frequency loss is lifted at the top, its mirror at the bottom —
        // which also means the two can never be the same curve.
        assertTrue(
            "rising peaked at ${risingPeak.centerHz}, flipped at ${flippedPeak.centerHz}",
            flippedPeak.centerHz < risingPeak.centerHz,
        )
    }

    // ---- CalibrationTransfer: the other place two units meet ---------------------

    private fun flatClinic(hl: Double) = TEST_FREQUENCIES_HZ.associateWith { hl }

    /** Self-test thresholds produced by hearing [clinicHl] through [deviceDb]. */
    private fun selfTest(
        clinicHl: Map<Int, Double>,
        deviceDb: Map<Int, Double>,
        globalOffset: Double,
    ) = clinicHl.mapValues { (hz, hl) -> hl - (deviceDb[hz] ?: 0.0) + globalOffset }

    private val device = mapOf(
        250 to 3.0, 500 to 1.5, 1000 to 0.0, 2000 to 0.0,
        3000 to -1.0, 4000 to -2.0, 6000 to -1.0, 8000 to -0.5,
    )

    /**
     * The offset between the two scales is unknown and unknowable, so the
     * derivation has to discard it. Already covered for one shared offset; what
     * is new here is that the two ears may carry *different* offsets — which is
     * the realistic case, because the two runs are separate measurements and the
     * media volume is the same but the seal is not.
     */
    @Test
    fun `derive is invariant to a per-ear global offset`() {
        val clinic = flatClinic(10.0)
        val reference = CalibrationTransfer.derive(
            clinicLeftHl = clinic,
            clinicRightHl = clinic,
            selfLeftDbfs = selfTest(clinic, device, globalOffset = 0.0),
            selfRightDbfs = selfTest(clinic, device, globalOffset = 0.0),
        )!!

        listOf(
            -80.0 to -80.0,
            -55.0 to -55.0,
            -55.0 to -30.0,
            -30.0 to -55.0,
            0.0 to -90.0,
            120.0 to -7.0,
        ).forEach { (leftOffset, rightOffset) ->
            val shifted = CalibrationTransfer.derive(
                clinicLeftHl = clinic,
                clinicRightHl = clinic,
                selfLeftDbfs = selfTest(clinic, device, leftOffset),
                selfRightDbfs = selfTest(clinic, device, rightOffset),
            )!!

            assertEquals(
                "offsets $leftOffset / $rightOffset",
                reference.responseDeviationDb,
                shifted.responseDeviationDb,
            )
            // The offsets are not hearing, so they must not show up as a
            // disagreement between the ears either.
            assertEquals("offsets $leftOffset / $rightOffset", 0.0, shifted.earSpreadDb, 1e-9)
        }
    }

    /** The same, for the clinical side: an offset on the *form* also cancels. */
    @Test
    fun `derive is invariant to a global offset on the clinic sheet`() {
        val base = flatClinic(10.0)
        val reference = CalibrationTransfer.derive(
            base, base,
            selfTest(base, device, -55.0), selfTest(base, device, -55.0),
        )!!

        listOf(-10.0, 0.0, 25.0, 60.0).forEach { shift ->
            val clinic = base.mapValues { it.value + shift }
            val shifted = CalibrationTransfer.derive(
                clinic, clinic,
                // The self-test follows the ears, so it moves with the clinic.
                selfTest(clinic, device, -55.0), selfTest(clinic, device, -55.0),
            )!!
            assertEquals("clinic shift $shift", reference.responseDeviationDb, shifted.responseDeviationDb)
        }
    }

    /**
     * **A sign flip on the self-test side is not detectable, and this test says
     * why rather than pretending otherwise.**
     *
     * Write `D(f) = self(f) − HL(f)`; the derivation returns `mean(D) − D(f)`.
     * Feeding `−self` instead gives `D'(f) = −self(f) − HL(f)`, and
     *
     *     dev'(f) + dev(f) = 2·(HL(f) − mean(HL))
     *
     * so against a flat clinic sheet the flipped input returns **exactly the
     * negated device curve** — a perfectly well-formed response shape, the same
     * magnitude, no warning triggered, pointing the wrong way. There is no
     * quantity inside `derive` that could tell the two apart: both are legal
     * response curves and the function is only ever shown differences.
     *
     * That is why the convention has to be enforced by the *caller* — the
     * self-test map comes from converged [ThresholdPoint]s in the engine's own
     * dBFS, and nothing downstream re-derives its sign. The identity is pinned
     * here so that a future change which breaks the symmetry (and could
     * therefore detect a flip) is a deliberate, visible one.
     */
    @Test
    fun `a sign-flipped self test returns the mirrored curve, undetectably`() {
        val clinic = flatClinic(10.0)
        val self = selfTest(clinic, device, globalOffset = -55.0)

        val honest = CalibrationTransfer.derive(clinic, clinic, self, self)!!
        val flipped = CalibrationTransfer.derive(
            clinic, clinic,
            self.mapValues { -it.value }, self.mapValues { -it.value },
        )!!

        honest.responseDeviationDb.forEachIndexed { i, value ->
            assertEquals("at ${TEST_FREQUENCIES_HZ[i]} Hz", -value, flipped.responseDeviationDb[i], 1e-9)
        }
        // Nothing in the result flags it: same spread, same silence.
        assertEquals(honest.earSpreadDb, flipped.earSpreadDb, 1e-9)
        assertEquals(honest.warnings, flipped.warnings)
    }

    /**
     * The convention the derivation *does* fix, and the one the whole sign
     * discipline rests on: a device that plays a band louder is heard at a lower
     * dBFS threshold there, and comes back with a **positive** deviation — the
     * `responseDeviationDb` convention of `CalibrationPreset.fromResponseDeviation`.
     * Get this backwards and every derived preset corrects in the wrong
     * direction while looking entirely reasonable.
     */
    @Test
    fun `a band the device plays louder comes back positive`() {
        val clinic = flatClinic(10.0)
        val loudBass = TEST_FREQUENCIES_HZ.associateWith { 0.0 } + (250 to 6.0)

        val result = CalibrationTransfer.derive(
            clinic, clinic,
            selfTest(clinic, loudBass, -55.0), selfTest(clinic, loudBass, -55.0),
        )!!

        assertTrue(
            "250 Hz came back ${result.responseDeviationDb.first()}",
            result.responseDeviationDb.first() > 0.0,
        )
        // And the preset built from it corrects downwards, i.e. raises H_T.
        val preset = CalibrationPreset.fromResponseDeviation(
            id = "derived", displayName = "d", dataSource = "s", measurementRig = "r",
            targetCurve = "t", formFactor = BundledCalibrationPresets.generic.formFactor,
            responseDeviationDb = result.responseDeviationDb,
        )
        assertTrue(preset.offsetsDb.first() < 0.0)
    }
}
