package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import dev.dankyeeter.btdashboard.audio.eq.EqBands
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import kotlin.math.abs

/**
 * The compensation pipeline of COMPENSATION.md section 3, implemented step for
 * step. Read that document before changing anything in here.
 *
 * Per ear, fully independently:
 *
 * 1. thresholds come in already median-aggregated across runs (Worker B);
 * 2. **device correction** — subtract the preset's correction to get `H_T(f)`;
 * 3. **target gain** — NAL-R `IG(f)` (see [NalR]);
 * 4. **intensity** — `G(f) = s * IG(f)`, `s = intensity * partialFactor`;
 * 5. **safety clamps** — per-band cap [MAX_BAND_GAIN_DB], then slope smoothing;
 * 6. **band mapping** — log-frequency interpolation onto the 10 EQ centres,
 *    edge value x [EDGE_BAND_FACTOR] outside the measured 250–8000 Hz range;
 * 7. **headroom** — pre-gain = `-max(G)` across *both* ears.
 *
 * Steps 5 and 6 are interleaved as the spec's own wording requires: the +12 dB
 * cap applies to the prescribed gains, the moving average is defined "on the
 * band gains" and therefore runs after the mapping.
 */
class NalRCompensationCalculator(
    private val presets: CalibrationPresetRepository = BundledCalibrationPresets,
) : CompensationCalculator {

    override fun compute(
        audiogram: Audiogram,
        calibrationPresetId: String,
        intensity: Float,
        partialFactor: Float,
    ): EqSettings = compute(audiogram, calibrationPresetId, intensity, partialFactor, EqBandLayout.DEFAULT)

    fun compute(
        audiogram: Audiogram,
        calibrationPresetId: String,
        intensity: Float,
        partialFactor: Float,
        layout: EqBandLayout,
    ): EqSettings = computeDetailed(audiogram, calibrationPresetId, intensity, partialFactor, layout).eq

    /**
     * Same computation as [compute] but keeps the intermediate values the EQ
     * screen shows (PTA, insertion gain before scaling, per-ear band gains).
     */
    fun computeDetailed(
        audiogram: Audiogram,
        calibrationPresetId: String,
        intensity: Float,
        partialFactor: Float,
        layout: EqBandLayout = EqBandLayout.DEFAULT,
    ): CompensationResult {
        val preset = presets.byId(calibrationPresetId)
            ?: presets.byId(CalibrationPresetRepository.GENERIC_ID)
            ?: BundledCalibrationPresets.generic
        val strength = (intensity * partialFactor).coerceIn(0f, 1f).toDouble()

        val left = computeEar(audiogram.points(Ear.LEFT), preset, strength, layout)
        val right = computeEar(audiogram.points(Ear.RIGHT), preset, strength, layout)

        val peak = (left.bandGainsDb + right.bandGainsDb).maxOrNull() ?: 0.0
        val preGain = -peak.coerceAtLeast(0.0)

        val eq = EqSettings(
            enabled = false, // the caller owns the master switch
            layout = layout,
            leftGainsDb = left.bandGainsDb.map { it.toFloat() },
            rightGainsDb = right.bandGainsDb.map { it.toFloat() },
            preGainDb = preGain.toFloat(),
            limiterEnabled = true,
        ).sanitized()

        return CompensationResult(
            eq = eq,
            preset = preset,
            effectiveStrength = strength,
            left = left,
            right = right,
        )
    }

    private fun computeEar(
        points: List<ThresholdPoint>,
        preset: CalibrationPreset,
        strength: Double,
        layout: EqBandLayout,
    ): EarCompensation {
        // Step 2: device correction -> H_T(f) at the audiometric frequencies.
        val corrected: List<Double> = TEST_FREQUENCIES_HZ.mapIndexed { i, hz ->
            thresholdAt(points, hz) - preset.offsetsDb[i]
        }
        val byFrequency = TEST_FREQUENCIES_HZ.zip(corrected).toMap()

        // Step 3: NAL-R.
        val pta = NalR.pureToneAverage { hz -> byFrequency.getValue(hz) }
        val insertionGain = TEST_FREQUENCIES_HZ.mapIndexed { i, hz ->
            NalR.insertionGainDb(hz.toDouble(), corrected[i], pta)
        }

        // Step 4 + per-band cap of step 5.
        val scaled = insertionGain.map { (strength * it).coerceIn(0.0, MAX_BAND_GAIN_DB) }

        // Step 6: map onto whichever band layout is active.
        val mapped = mapToBands(scaled, layout)

        // Rest of step 5: 3-point moving average, then the explicit 6 dB/octave
        // ceiling the spec names. The limiter only ever lowers a band.
        val bands = limitSlope(movingAverage3(mapped), layout)

        return EarCompensation(
            correctedThresholdsDb = corrected,
            ptaDb = pta,
            insertionGainDb = insertionGain,
            bandGainsDb = bands,
        )
    }

    /**
     * Threshold at [frequencyHz]. Missing points are interpolated on log
     * frequency from the ones that exist (edge-held); an entirely empty ear
     * yields 0 dB, i.e. no compensation rather than a crash.
     */
    private fun thresholdAt(points: List<ThresholdPoint>, frequencyHz: Int): Double {
        points.firstOrNull { it.frequencyHz == frequencyHz }?.let { return it.thresholdDb }
        if (points.isEmpty()) return 0.0
        val sorted = points.sortedBy { it.frequencyHz }
        return logInterpolateMonotone(
            xs = sorted.map { it.frequencyHz.toDouble() },
            ys = sorted.map { it.thresholdDb },
            x = frequencyHz.toDouble(),
        )
    }

    /**
     * Step 6. Inside 250–8000 Hz: monotone log-frequency interpolation of the
     * prescribed gains. Outside: the edge value scaled by [EDGE_BAND_FACTOR] —
     * there is no audiometric data down there or up there, so we never
     * extrapolate a full gain.
     *
     * The interpolator is [logInterpolateMonotone] rather than a straight line
     * between test frequencies. At the ten octave bands the two agree exactly,
     * because those centres *are* test frequencies and nothing is interpolated;
     * the difference shows at 20 and 31 bands, where the notch region falls
     * between measured points.
     */
    private fun mapToBands(
        gainsAtTestFrequencies: List<Double>,
        layout: EqBandLayout,
    ): List<Double> {
        val xs = TEST_FREQUENCIES_HZ.map { it.toDouble() }
        val low = xs.first()
        val high = xs.last()
        return layout.centersHz.map { centre ->
            val f = centre.toDouble()
            when {
                f < low -> gainsAtTestFrequencies.first() * EDGE_BAND_FACTOR
                f > high -> gainsAtTestFrequencies.last() * EDGE_BAND_FACTOR
                else -> logInterpolateMonotone(xs, gainsAtTestFrequencies, f)
            }
        }
    }

    companion object {
        /** Per-band cap from COMPENSATION.md step 5. */
        const val MAX_BAND_GAIN_DB: Double = 12.0

        /** Scaling for bands outside the measured range (step 6). */
        const val EDGE_BAND_FACTOR: Double = 0.5

        /**
         * Inter-band slope ceiling.
         *
         * **This is a house heuristic, not a clinical constant.** COMPENSATION.md
         * lists it beside the +12 dB cap, which makes it read like a prescribed
         * limit; it is not. No published work validates any particular
         * inter-band gain-slope ceiling, so there is nothing to cite here and
         * the number cannot be defended by appeal to the literature
         * (RESEARCH_COMPENSATION.md section 6).
         *
         * What can honestly be said is that two facts bracket it. NAL-R itself
         * only ever prescribes 0.31 x the audiogram's own slope, so even a
         * 20 dB/octave ski-slope loss asks for about 6 dB/octave of gain — the
         * cap sits right at the edge of what the prescription can legitimately
         * want, and it will shave a genuinely steep mild loss a little. And
         * smoothness is worth something in its own right: response ripples
         * beyond about +/-5 dB measurably lower rated sound quality even for
         * normal-hearing listeners.
         *
         * If the matched-loudness A/B ever shows the cap flattening a notch
         * correction that was really measured, ~8 dB/octave is still inside
         * NAL-R's own slope logic (a 25 dB/octave audiogram). Move it for that
         * reason, with the listening test behind it — not because a curve looks
         * better on the preview chart.
         */
        const val MAX_SLOPE_DB_PER_OCTAVE: Double = 6.0

        /**
         * Threshold above which a band is flagged as a **possible** cochlear
         * dead region — a region with no functioning inner hair cells, where a
         * tone is heard (if at all) by spreading onto neighbouring places, and
         * where amplification adds level and distortion rather than timbre.
         *
         * Vinay & Moore 2007 found a dead region present at >= 59 % of the
         * frequencies whose threshold exceeded 70 dB HL. That is a prevalence
         * figure and nothing more. The test that actually decides the question,
         * TEN(HL), needs calibrated presentation levels no consumer headphone
         * can produce, so this app can never report that a band *is* dead — and
         * just as importantly can never report that it is not. The flag exists
         * only to decide when the UI is obliged to say "cannot check"; see
         * RESEARCH_COMPENSATION.md sections 5 and 8 for the wording that is and
         * is not allowed.
         *
         * Below the line there is deliberately no logic at all: for a mild or
         * moderate loss a false alarm costs more than the warning is worth.
         */
        const val DEAD_REGION_FLAG_DB: Double = 70.0

        /** 3-point moving average; the ends average over the two values there. */
        fun movingAverage3(values: List<Double>): List<Double> = values.indices.map { i ->
            val window = ((i - 1)..(i + 1)).filter { it in values.indices }
            window.sumOf { values[it] } / window.size
        }

        /**
         * Enforces [MAX_SLOPE_DB_PER_OCTAVE] between neighbouring bands by
         * pulling the *louder* band down — never by boosting, so this can only
         * make the curve safer.
         *
         * The ceiling is per *octave*, not per band. On a third-octave layout
         * neighbours are a third of an octave apart, so the step allowed
         * between them is a third as large; applying the octave figure verbatim
         * would let a 31-band curve rise three times as steeply as a 10-band
         * one from the same audiogram.
         */
        fun limitSlope(
            values: List<Double>,
            layout: EqBandLayout = EqBandLayout.DEFAULT,
        ): List<Double> {
            val steps = allowedSteps(layout, values.size)
            val out = values.toMutableList()
            for (i in 1 until out.size) {
                out[i] = minOf(out[i], out[i - 1] + steps[i - 1])
            }
            for (i in out.size - 2 downTo 0) {
                out[i] = minOf(out[i], out[i + 1] + steps[i])
            }
            return out
        }

        /**
         * The dB step allowed between each adjacent pair.
         *
         * Computed per pair, not from an average: ISO band centres are rounded
         * nominal values, so 63→125 Hz is 0.9885 octaves while 4000→8000 Hz is
         * exactly 1.0. Averaging those would apply a slightly wrong ceiling to
         * every pair instead of the right one to each.
         */
        private fun allowedSteps(layout: EqBandLayout, bandCount: Int): List<Double> {
            val centres = layout.centersHz
            if (centres.size != bandCount || bandCount < 2) {
                return List(maxOf(bandCount - 1, 0)) { MAX_SLOPE_DB_PER_OCTAVE }
            }
            return (1 until bandCount).map { i ->
                val octaves = kotlin.math.ln(centres[i] / centres[i - 1].toDouble()) /
                    kotlin.math.ln(2.0)
                MAX_SLOPE_DB_PER_OCTAVE * octaves
            }
        }

        /** Largest absolute inter-band step; for tests and the UI's warnings. */
        fun maxSlopeDbPerOctave(values: List<Double>): Double =
            (1 until values.size).maxOfOrNull { abs(values[it] - values[it - 1]) } ?: 0.0
    }
}

/** Everything the compensation produced for one ear. All lists are in dB. */
data class EarCompensation(
    /** `H_T(f)` at [TEST_FREQUENCIES_HZ] after the device correction. */
    val correctedThresholdsDb: List<Double>,
    val ptaDb: Double,
    /** NAL-R `IG(f)` before the intensity slider. */
    val insertionGainDb: List<Double>,
    /** Final gains at the active layout's band centres. */
    val bandGainsDb: List<Double>,
) {
    /**
     * Test frequencies whose device-corrected threshold crosses
     * [NalRCompensationCalculator.DEAD_REGION_FLAG_DB].
     *
     * The device-corrected value is the right input: the raw threshold still
     * contains the headphone's own response, and the published prevalence
     * figure is about the ear. Read the constant before putting any of these
     * numbers on screen — this list is a reason to say "cannot check", never a
     * finding.
     */
    val possibleDeadRegionFrequenciesHz: List<Int>
        get() = TEST_FREQUENCIES_HZ.filterIndexed { i, _ ->
            (correctedThresholdsDb.getOrNull(i) ?: 0.0) >
                NalRCompensationCalculator.DEAD_REGION_FLAG_DB
        }
}

/**
 * The most-boosted band of a [CompensationResult]: what the strength slider is
 * doing right now, as a dB figure at a frequency rather than a percentage.
 */
data class PeakBand(
    /** Centre of the band carrying the largest gain, in Hz. */
    val centerHz: Float,
    /** That gain, in dB. Always positive — see [CompensationResult.peakBand]. */
    val gainDb: Double,
)

/** Result of [NalRCompensationCalculator.computeDetailed]. */
data class CompensationResult(
    val eq: EqSettings,
    val preset: CalibrationPreset,
    /** `intensity * partialFactor`, clamped to 0..1. */
    val effectiveStrength: Double,
    val left: EarCompensation,
    val right: EarCompensation,
) {
    /** Per-band left − right difference, for the asymmetry read-out. */
    val bandDifferenceDb: List<Double>
        get() = left.bandGainsDb.zip(right.bandGainsDb) { l, r -> l - r }

    /** True while either ear's PTA suggests referring the user to a professional. */
    val severeLossWarning: Boolean
        get() = left.ptaDb > SEVERE_LOSS_PTA_DB || right.ptaDb > SEVERE_LOSS_PTA_DB

    /**
     * Every frequency either ear flags under
     * [NalRCompensationCalculator.DEAD_REGION_FLAG_DB], ascending. Empty in the
     * ordinary case, which is the case this app is built for.
     *
     * Merged across ears on purpose: the warning it drives is about what the
     * app cannot determine, and that is equally true whichever ear produced the
     * threshold.
     */
    val possibleDeadRegionFrequenciesHz: List<Int>
        get() = (left.possibleDeadRegionFrequenciesHz + right.possibleDeadRegionFrequenciesHz)
            .distinct()
            .sorted()

    /**
     * The loudest band across both ears, or null when the curve lifts nothing
     * worth naming.
     *
     * Read off the *computed* curve rather than derived from the strength
     * percentage, because the two part company wherever it matters: at the
     * 12 dB cap, at the slope limiter, and outside the measured range where
     * gains are held at half weight. A read-out that only restates the slider
     * position would be decoration; this one can be checked against the band
     * list below it.
     *
     * Null rather than a "+0.0 dB" is deliberate — at strength 0, or for an ear
     * the prescription asks nothing of, the honest UI says nothing rather than
     * announcing that nothing happens with a number.
     */
    val peakBand: PeakBand?
        get() {
            val centres = eq.centersHz
            // Per band, whichever ear is louder: "up to" is a statement about
            // the curve as a whole, and the pre-gain is computed the same way.
            val loudest = left.bandGainsDb.zip(right.bandGainsDb) { l, r -> maxOf(l, r) }
            val index = loudest.indices.maxByOrNull { loudest[it] } ?: return null
            if (index >= centres.size || loudest[index] < READ_OUT_FLOOR_DB) return null
            return PeakBand(centerHz = centres[index], gainDb = loudest[index])
        }

    companion object {
        /** PLAN/COMPENSATION.md: above this the app shows a "see a professional" notice. */
        const val SEVERE_LOSS_PTA_DB: Double = 60.0

        /**
         * Below this the peak read-out stays silent. Set so that anything shown
         * rounds to at least +0.1 dB: a line reading "lifts up to +0.0 dB" is
         * an unfinished-looking way of saying "off".
         */
        private const val READ_OUT_FLOOR_DB: Double = 0.05
    }
}
