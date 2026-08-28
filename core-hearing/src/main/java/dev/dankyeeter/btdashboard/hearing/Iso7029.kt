package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.audio.eq.Ear
import kotlin.math.abs

/**
 * Which column of the ISO 7029 coefficient table applies.
 *
 * The standard tabulates two: it found men and women age differently at the top
 * of the range, by a factor of nearly two at 4 kHz, and collapsing them would
 * throw that away. [UNSPECIFIED] is not a third measured population — it is the
 * plain average of the other two, offered because this app has no business
 * making anybody answer the question to see a reference line. What it costs is
 * documented on [alphaFor].
 */
enum class Iso7029Sex { MALE, FEMALE, UNSPECIFIED }

/**
 * The age-statistical hearing threshold expected of a population, as a *soft*
 * reference for people who have no clinical audiogram.
 *
 * ## What this is, and the one thing it is not
 *
 * ISO 7029 describes how the hearing threshold of an otologically normal
 * population drifts with age. Its model is one line long: the median threshold
 * rises with the **square** of the years since 18,
 *
 *     H(f, Y) = alpha(f, sex) * (Y - 18)^2      [dB, relative to age 18]
 *
 * with one coefficient per audiometric frequency and sex. The quadratic is the
 * whole shape of presbyacusis in this model — barely anything below 1 kHz,
 * steeply rising at 4-8 kHz, and accelerating with age rather than ticking over
 * linearly.
 *
 * It is a statement about a *population*, not about a person. Half of any
 * healthy age group sits worse than this curve and half sits better, and the
 * spread around it is wide — wider, at the ages this app will mostly see, than
 * the differences it would be used to explain. So this curve can say "your
 * self-test looks unusual for your age, worth a look", and it can never say
 * "you have this hearing". Nothing here is a measurement of the user, and
 * nothing here is a diagnosis.
 *
 * ## PROVENANCE WARNING — read before trusting the numbers
 *
 * The coefficients in [MALE_ALPHA] and [FEMALE_ALPHA] are reproduced **from
 * knowledge, not from a copy of the standard**. They are the classic ISO 7029
 * median coefficients (the `alpha_md` column, in dB per year squared) and they
 * reproduce the figures most often quoted from the standard — a 70-year-old
 * male median of about 43 dB at 4 kHz, a female median of about 24 dB at the
 * same point — but they have not been checked against the published table,
 * which is paywalled and was not available here.
 *
 * Treat them as a **clearly-marked approximation of the standard's median
 * model**, correct in structure and in the right neighbourhood numerically.
 * Anybody with access to ISO 7029:2017 Table 1 should check these ten pairs of
 * numbers and correct them in place; nothing else in this file has to change if
 * they are off, because everything else derives from them.
 *
 * ## Why median only, with no interquartile band
 *
 * The standard also parameterises the spread — the percentiles of the
 * distribution around the median, which is the honest way to show "and this is
 * how much people differ". Those parameters could **not** be reproduced here
 * with any confidence at all, and a made-up spread would be worse than no
 * spread: it would put a band on the chart that looks like knowledge and is
 * invention. So this file ships the median only, and says so on screen.
 *
 * The consequence is worth stating plainly, because it is a real limitation:
 * without the spread, "you are 12 dB worse than the median for your age" cannot
 * be turned into "you are in the worst N % of your age group". Only the
 * direction and the size of the gap are available, which is why
 * [gapAgainstAgeReference] is deliberately coarse and its threshold
 * deliberately large.
 *
 * ## This never feeds the equaliser
 *
 * By design, and it is not an oversight to be wired up later. A compensation
 * curve is a per-person correction; deriving one from population statistics
 * would apply the average of a million strangers to one pair of ears and call
 * it personal — exactly the failure mode the rest of this module exists to
 * avoid. There is no path from this file into
 * [NalRCompensationCalculator], no [CompensationSource] entry for it, and no
 * caller in the audio chain. It is a line on a chart and a plausibility check,
 * and a clinical audiogram always outranks it.
 */
object Iso7029 {

    /**
     * The youngest age the model describes, and its zero.
     *
     * The formula measures years *from* 18 because that is where the standard
     * puts the reference population: an otologically normal 18-year-old is the
     * definition of no age-related shift, so the curve is flat zero there by
     * construction and not by measurement.
     */
    const val MIN_AGE_YEARS: Int = 18

    /**
     * The oldest age this app will evaluate.
     *
     * The standard's own tabulation runs to 70, and the 2017 revision extends
     * the model upward to 80. Past 80 the quadratic keeps climbing while the
     * data behind it thins out, so an unclamped call at 95 would print a
     * confident 100 dB curve nobody measured. Clamping is the honest failure:
     * an 85-year-old sees the 80-year-old curve, which is the oldest thing the
     * model can honestly say, and the UI says the age was clamped.
     */
    const val MAX_AGE_YEARS: Int = 80

    /**
     * The frequencies the coefficient table covers.
     *
     * Wider than [TEST_FREQUENCIES_HZ] and almost the same set as
     * [CLINICAL_FREQUENCIES_HZ] — 750 Hz is the one clinical frequency the
     * standard does not tabulate, and it is interpolated like every other
     * off-table point (see [alphaFor]).
     */
    val FREQUENCIES_HZ: List<Int> =
        listOf(125, 250, 500, 1000, 1500, 2000, 3000, 4000, 6000, 8000)

    /**
     * Median coefficients for men, in dB per year squared. See the provenance
     * warning on the object: reproduced from knowledge, not from the standard.
     */
    private val MALE_ALPHA: Map<Int, Double> = mapOf(
        125 to 0.0030,
        250 to 0.0030,
        500 to 0.0035,
        1000 to 0.0040,
        1500 to 0.0055,
        2000 to 0.0070,
        3000 to 0.0115,
        4000 to 0.0160,
        6000 to 0.0180,
        8000 to 0.0220,
    )

    /** Median coefficients for women, same units and same warning. */
    private val FEMALE_ALPHA: Map<Int, Double> = mapOf(
        125 to 0.0030,
        250 to 0.0030,
        500 to 0.0035,
        1000 to 0.0040,
        1500 to 0.0050,
        2000 to 0.0060,
        3000 to 0.0075,
        4000 to 0.0090,
        6000 to 0.0120,
        8000 to 0.0150,
    )

    /**
     * The coefficient at [frequencyHz] for [sex], interpolated when the
     * frequency is not one the standard tabulates.
     *
     * [Iso7029Sex.UNSPECIFIED] averages the two columns. That is a defensible
     * default and a lossy one: at 4 kHz the male and female coefficients differ
     * by nearly a factor of two, so the average describes neither group
     * especially well and sits about 5 dB from each of them at age 70. It is
     * offered because a reference line is not worth demanding a personal
     * detail for, and the screen says which column is in use.
     *
     * Off-table frequencies use the same monotone log-frequency interpolant
     * measured curves use, held at the edges rather than extrapolated: below
     * 125 Hz and above 8 kHz the standard says nothing, and a cubic run past
     * its last knot would invent the part nobody measured.
     */
    fun alphaFor(frequencyHz: Int, sex: Iso7029Sex): Double = when (sex) {
        Iso7029Sex.MALE -> interpolatedAlpha(MALE_ALPHA, frequencyHz)
        Iso7029Sex.FEMALE -> interpolatedAlpha(FEMALE_ALPHA, frequencyHz)
        Iso7029Sex.UNSPECIFIED ->
            (interpolatedAlpha(MALE_ALPHA, frequencyHz) + interpolatedAlpha(FEMALE_ALPHA, frequencyHz)) / 2.0
    }

    private fun interpolatedAlpha(table: Map<Int, Double>, frequencyHz: Int): Double {
        table[frequencyHz]?.let { return it }
        val xs = FREQUENCIES_HZ.map { it.toDouble() }
        val ys = FREQUENCIES_HZ.map { table.getValue(it) }
        return logInterpolateMonotone(xs, ys, frequencyHz.toDouble())
    }

    /**
     * Age in whole years from a birth year, without the clamp.
     *
     * Deliberately crude: a birth *year* is all the app asks for, so the answer
     * is only ever accurate to a year anyway, and asking for a full birth date
     * to sharpen a population statistic would collect a more identifying number
     * for no gain. Callers pass the result through [clampAge].
     */
    fun ageFromBirthYear(birthYear: Int, currentYear: Int): Int = currentYear - birthYear

    /** [MIN_AGE_YEARS]..[MAX_AGE_YEARS]; see [MAX_AGE_YEARS] for why it clamps. */
    fun clampAge(ageYears: Int): Int = ageYears.coerceIn(MIN_AGE_YEARS, MAX_AGE_YEARS)

    /** True when [ageYears] had to be moved to fit the model's range. */
    fun isAgeClamped(ageYears: Int): Boolean = ageYears != clampAge(ageYears)

    /**
     * The expected **median** threshold shift for this age and sex, in dB above
     * the 18-year-old reference, keyed by frequency.
     *
     * Positive is worse, like dB HL and unlike this app's own dBFS thresholds.
     * The values are not dB HL: they are a *shift* relative to the population
     * median at 18, which happens to be 0 dB HL by the definition of dB HL, so
     * the two coincide numerically for an otologically normal population. That
     * coincidence is exactly what makes the curve comparable with a clinical
     * audiogram and not with a self-test — see [deviationCurve] for what the
     * self-test comparison can honestly do instead.
     */
    fun expectedMedianHl(
        ageYears: Int,
        sex: Iso7029Sex,
        frequenciesHz: List<Int> = TEST_FREQUENCIES_HZ,
    ): Map<Int, Double> {
        val years = (clampAge(ageYears) - MIN_AGE_YEARS).toDouble()
        val squared = years * years
        return frequenciesHz.associateWith { hz -> alphaFor(hz, sex) * squared }
    }

    /**
     * The age curve expressed the way [ClinicalAudiogram.deviationCurve]
     * expresses the clinical one: deviation from its own median, positive =
     * more sensitive, in dB.
     *
     * The same reasoning applies and for a stronger reason. The app's own
     * thresholds are uncalibrated dBFS with an unknown offset to dB HL, so an
     * absolute overlay would invent an alignment; and this curve is not even a
     * measurement of one person, so its absolute height is meaningless twice
     * over. Stripping the median throws away only the part that could not be
     * compared and keeps the part that can: the *shape* — how steeply hearing
     * is expected to fall off toward the top of the range at this age.
     *
     * A young listener produces a flat line at zero. That is correct and worth
     * seeing: the model expects nothing measurable at 25, so a self-test with a
     * pronounced high-frequency slope at 25 is not explained by age.
     */
    fun deviationCurve(
        ageYears: Int,
        sex: Iso7029Sex,
        frequenciesHz: List<Int> = TEST_FREQUENCIES_HZ,
    ): List<Pair<Int, Double>> {
        val expected = expectedMedianHl(ageYears, sex, frequenciesHz)
        if (expected.isEmpty()) return emptyList()
        val median = ClinicalAudiogram.medianOf(expected.values.toList())
        return frequenciesHz.map { hz -> hz to (median - expected.getValue(hz)) }
    }

    /**
     * How far one measured ear's *shape* falls short of the age-typical shape,
     * or null when there is nothing worth saying.
     *
     * Shape only, and the sign convention deserves a sentence. Both curves are
     * put into the deviation frame first (their own median at zero, positive =
     * more sensitive), so the comparison is blind to the level offset nobody
     * can measure. A positive gap at a frequency then means: *relative to its
     * own average, this ear falls off more there than the age model says a
     * median person of this age does.*
     *
     * Only converged points take part. A hollow point sits at the test's own
     * floor or ceiling, and letting one into a comparison would compare the
     * measurement's limits against a population statistic.
     *
     * **The two low frequencies cannot trigger this.** They still set the ear's
     * own average, because that average is the ear's average — but a raised
     * 250 or 500 Hz in a headphone self-test is, by this project's own finding,
     * usually a leaking seal or a bass-heavy room rather than hearing (see
     * [LowToneArtifact]). Letting the known artifact raise an age advisory would
     * report a loose eartip as a hearing observation, and there is already a
     * notice that explains it correctly.
     *
     * The threshold is deliberately large — see [NOTABLE_GAP_DB] — and at least
     * [MIN_GAP_FREQUENCIES] frequencies have to show it. This is a nudge to
     * check the fit or see a professional, and it must not fire on the ordinary
     * noise of a self-administered test.
     */
    fun gapAgainstAgeReference(
        points: List<ThresholdPoint>,
        ageYears: Int,
        sex: Iso7029Sex,
    ): AgeGap? {
        val converged = points.filter { it.converged }
        if (converged.size < MIN_GAP_FREQUENCIES + 1) return null
        val frequencies = converged.map { it.frequencyHz }.sorted()
        val measuredMedian = ClinicalAudiogram.medianOf(converged.map { it.thresholdDb })
        // Positive = more sensitive than this ear's own average, matching the
        // chart and the clinical overlay. Larger dBFS is a worse threshold, so
        // the subtraction runs this way round.
        val measuredDeviation = converged.associate { it.frequencyHz to (measuredMedian - it.thresholdDb) }
        val expectedDeviation = deviationCurve(ageYears, sex, frequencies).toMap()

        val gaps = frequencies.mapNotNull { hz ->
            if (hz in LowToneArtifact.LOW_FREQUENCIES_HZ) return@mapNotNull null
            val measured = measuredDeviation[hz] ?: return@mapNotNull null
            val expected = expectedDeviation[hz] ?: return@mapNotNull null
            // The frequency has to be a weak spot for this ear *at all* before
            // it can be a bigger weak spot than age explains. Without this the
            // arithmetic reports the opposite of the truth for anyone hearing
            // better than their age group: an 80-year-old with a flat curve has
            // a measured deviation of zero everywhere while the age model's
            // deviation is strongly positive at the bottom of the range, so the
            // subtraction alone would announce a problem at 1 kHz to somebody
            // whose hearing is remarkable for their age.
            if (measured >= 0.0) return@mapNotNull null
            // Expected minus measured: positive when the ear sits below what
            // the age model predicts for it, i.e. worse than typical.
            val gap = expected - measured
            if (gap >= NOTABLE_GAP_DB) hz to gap else null
        }
        if (gaps.size < MIN_GAP_FREQUENCIES) return null
        return AgeGap(
            frequenciesHz = gaps.map { it.first },
            largestGapDb = gaps.maxOf { it.second },
        )
    }

    /**
     * The same check over both ears of an aggregated curve, for the screen.
     *
     * Reported per ear because a one-sided finding is the interesting one and
     * pooling would hide it. Nothing is returned for an ear that has nothing to
     * report, so an empty list is the ordinary case.
     */
    fun gapsAgainstAgeReference(
        audiogram: Audiogram,
        ageYears: Int,
        sex: Iso7029Sex,
    ): List<Pair<Ear, AgeGap>> = Ear.entries.mapNotNull { ear ->
        gapAgainstAgeReference(audiogram.points(ear), ageYears, sex)?.let { ear to it }
    }

    /**
     * How far below the age-typical shape a frequency has to sit before the app
     * says anything, in dB.
     *
     * Large on purpose, and larger than it would be if this were a measurement
     * against a measurement. Three things are stacked up here, all of them
     * uncertain in the same direction:
     *
     *  1. This app's own test-retest spread. The project's own research puts
     *     consumer hearing tests at 8-17 dB RMSD against clinical audiometry,
     *     with a systematic 5-8 dB overestimation at 500-2000 Hz.
     *  2. The population spread around the ISO median, which is wide and which
     *     this file cannot even quantify (no percentile parameters — see the
     *     object KDoc).
     *  3. The coefficients themselves, reproduced rather than transcribed.
     *
     * 15 dB at two frequencies is therefore not a statistical statement; it is
     * the point past which the boring explanations have all been given a
     * generous benefit of the doubt and the observation is still there.
     */
    const val NOTABLE_GAP_DB: Double = 15.0

    /**
     * How many frequencies have to show it.
     *
     * One frequency is a point, and a single point that far out is more likely
     * a lapse of attention during one ascending run than a finding. Two is the
     * smallest number that is a pattern.
     */
    const val MIN_GAP_FREQUENCIES: Int = 2

    /**
     * One ear's shape sitting below the age-typical shape.
     *
     * Carries the frequencies and the size, and no verdict: what it means is a
     * sentence on screen, not a field in here.
     */
    data class AgeGap(
        val frequenciesHz: List<Int>,
        val largestGapDb: Double,
    ) {
        /** True when the gap is large enough that the wording should not hedge. */
        val pronounced: Boolean get() = abs(largestGapDb) >= NOTABLE_GAP_DB * 2
    }
}

/**
 * The user's stated birth year and, optionally, sex — the whole input the age
 * reference needs.
 *
 * A birth year rather than an age, because an age silently rots: stored once at
 * 41 it would still say 41 in ten years, and the curve would quietly stop
 * matching the person. A year is also the coarsest form of the fact that still
 * answers the question, which is the right amount of personal data to keep for
 * drawing a reference line. It never leaves the phone; there is no INTERNET
 * permission.
 *
 * @param birthYear four-digit year as the user typed it. Validity is
 *   [isPlausible]'s business, not the constructor's — a store that threw on a
 *   nonsense year would turn a typo into a crash on the next launch.
 */
data class AgeReference(
    val birthYear: Int,
    val sex: Iso7029Sex = Iso7029Sex.UNSPECIFIED,
) {

    /**
     * Age in years at [currentYear], clamped into the model's range.
     *
     * See [Iso7029.MAX_AGE_YEARS] for why clamping rather than refusing.
     */
    fun ageAt(currentYear: Int): Int =
        Iso7029.clampAge(Iso7029.ageFromBirthYear(birthYear, currentYear))

    /** True when the stated year had to be pulled into the model's range. */
    fun isClampedAt(currentYear: Int): Boolean =
        Iso7029.isAgeClamped(Iso7029.ageFromBirthYear(birthYear, currentYear))

    /** The expected median curve for this person, in dB above the age-18 reference. */
    fun expectedMedianHl(
        currentYear: Int,
        frequenciesHz: List<Int> = TEST_FREQUENCIES_HZ,
    ): Map<Int, Double> = Iso7029.expectedMedianHl(ageAt(currentYear), sex, frequenciesHz)

    /** The same curve in the chart's deviation frame. See [Iso7029.deviationCurve]. */
    fun deviationCurve(
        currentYear: Int,
        frequenciesHz: List<Int> = TEST_FREQUENCIES_HZ,
    ): List<Pair<Int, Double>> = Iso7029.deviationCurve(ageAt(currentYear), sex, frequenciesHz)

    companion object {
        /**
         * Whether a typed year is worth storing at all.
         *
         * The upper bound is the current year: a birth year in the future is a
         * typo, not a person. The lower bound is generous rather than tight —
         * the model clamps at 80 anyway, so an implausibly old year costs
         * nothing but a clamped curve, while a tight bound would reject a
         * genuine centenarian to no purpose.
         */
        fun isPlausible(birthYear: Int, currentYear: Int): Boolean =
            birthYear in (currentYear - MAX_PLAUSIBLE_AGE)..currentYear

        private const val MAX_PLAUSIBLE_AGE = 120
    }
}
