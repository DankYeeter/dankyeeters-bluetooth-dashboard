package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.audio.eq.Ear

/**
 * The air-conduction frequencies of a standard ENT audiogram form, in Hz.
 *
 * Wider and finer than [TEST_FREQUENCIES_HZ]: a clinic measures 125 Hz and the
 * inter-octaves 750/1500 Hz that this app's own protocol skips, and it does so
 * with a calibrated audiometer where those extra points cost seconds rather
 * than the minutes they would cost here. Every entry is optional — practices
 * routinely leave 125 Hz and the inter-octaves blank — so the model stores a
 * sparse map rather than a fixed-length list.
 */
val CLINICAL_FREQUENCIES_HZ: List<Int> =
    listOf(125, 250, 500, 750, 1000, 1500, 2000, 3000, 4000, 6000, 8000)

/**
 * A hearing test carried out at an ENT practice, in calibrated **dB HL**.
 *
 * ## dB HL versus this app's numbers
 *
 * A clinical audiogram is measured on an audiometer whose output is calibrated
 * against a reference coupler (ISO 389), so the numbers on the form are
 * *absolute*: 0 dB HL is the median threshold of young normal-hearing adults at
 * that frequency, a higher number means a quieter tone could not be heard, and
 * everything up to 20 dB HL is read as normal hearing. Two audiograms from two
 * different practices can be compared point for point.
 *
 * Everything this app measures itself is the opposite kind of number.
 * [ThresholdPoint.thresholdDb] is digital attenuation in dBFS, played through a
 * Bluetooth headphone of unknown sensitivity at whatever the media volume
 * happened to be. There is no fixed relationship between that scale and dB HL —
 * the offset between them depends on the headphone, the phone, the codec and
 * the volume step, none of which the app can measure. So the app's own curve is
 * honest about **shape** and says nothing at all about level.
 *
 * That is what makes this class worth having. It is the one absolute reference
 * in the whole system: the only place where "how much" is a real number rather
 * than a relative one. It is deliberately *not* stored per device — an
 * audiogram is a property of a pair of ears, and the ears do not change when
 * the headphones do.
 *
 * ## What it is used for
 *
 * 1. As an overlay on the audiogram chart, converted into the chart's own
 *    relative space so the two *shapes* can be compared (see [deviationCurve]).
 * 2. As a sanity check on a self-test: raised low tones in a headphone test
 *    next to flat-normal lows at the clinic is the seal/room-noise signature,
 *    not a hearing finding (see [LowToneArtifact]).
 * 3. As an alternative input to the compensation, where it is the *better*
 *    input because NAL-R's natural argument is dB HL (see
 *    [prescriptionThresholdsDbHl]).
 *
 * @param leftDbHl thresholds in dB HL keyed by frequency; a missing key means
 *   the practice did not measure that frequency, never "0 dB HL".
 * @param measuredOn the date as printed on the form, free text. Deliberately
 *   not an epoch: practices print dates in whatever format they like, and
 *   parsing one into a timestamp would invent a precision the app never uses —
 *   nothing here computes with the date, it is only ever shown.
 * @param source free-text label for where it came from ("ENT practice", a
 *   name, a date-stamped report number). Provenance, shown next to the values.
 * @param savedAtMillis when the values were entered into the app.
 */
data class ClinicalAudiogram(
    val leftDbHl: Map<Int, Double> = emptyMap(),
    val rightDbHl: Map<Int, Double> = emptyMap(),
    val measuredOn: String = "",
    val source: String = "",
    val savedAtMillis: Long = 0L,
) {

    fun valuesFor(ear: Ear): Map<Int, Double> = when (ear) {
        Ear.LEFT -> leftDbHl
        Ear.RIGHT -> rightDbHl
    }

    /** True while no frequency has been filled in on either side. */
    val isEmpty: Boolean get() = leftDbHl.isEmpty() && rightDbHl.isEmpty()

    /** Every recorded value, both ears, in no particular order. */
    private fun allValues(): List<Double> = leftDbHl.values + rightDbHl.values

    /**
     * Median of every recorded value across **both** ears, or null when empty.
     *
     * One median for the pair rather than one per ear, because it is the zero
     * of the chart overlay and the chart uses a single reference line for both
     * curves. A per-ear median would silently subtract each ear's own offset
     * and hide exactly the left/right asymmetry the overlay exists to show.
     */
    fun medianDbHl(): Double? = allValues().takeIf { it.isNotEmpty() }?.let(::medianOf)

    /**
     * True when every recorded value is inside the clinical definition of
     * normal hearing.
     *
     * Not a diagnosis and not this app's opinion: <= [NORMAL_LIMIT_DB] dB HL is
     * where audiology draws the line, and it is drawn on the calibrated form,
     * which is the only place it *can* be drawn.
     */
    val withinNormalLimits: Boolean
        get() = !isEmpty && allValues().all { it <= NORMAL_LIMIT_DB }

    /**
     * The clinical curve expressed the way [AudiogramChart] draws everything
     * else: deviation from the median, positive = more sensitive than the
     * median, in dB.
     *
     * **Why relative and not absolute.** The cross-calibration between dB HL
     * and the app's dBFS thresholds is unknown — it would take a measurement
     * microphone in an artificial ear to establish, which is exactly the
     * equipment this app does not assume. Plotting the two on one absolute axis
     * would therefore be a made-up alignment, and the offset it invented would
     * look like a finding. Both curves are stripped of their own median instead,
     * which throws away only the part that is not comparable and keeps the part
     * that is: the shape.
     *
     * Sign is flipped relative to the raw values so that the two curves agree
     * on which way is "better". In dB HL a larger number is worse; the chart's
     * positive direction is *more sensitive*, so this returns `median - value`.
     *
     * Values are resampled onto [frequenciesHz] by monotone log-frequency
     * interpolation, the same interpolant measured curves use, and held at the
     * edges rather than extrapolated.
     */
    fun deviationCurve(ear: Ear, frequenciesHz: List<Int> = TEST_FREQUENCIES_HZ): List<Pair<Int, Double>> {
        val median = medianDbHl() ?: return emptyList()
        val recorded = valuesFor(ear).entries.sortedBy { it.key }
        if (recorded.isEmpty()) return emptyList()
        val xs = recorded.map { it.key.toDouble() }
        val ys = recorded.map { it.value }
        return frequenciesHz.map { hz ->
            hz to (median - logInterpolateMonotone(xs, ys, hz.toDouble()))
        }
    }

    /**
     * Whether the clinic found normal, unremarkable hearing at [frequenciesHz].
     *
     * Two conditions, because both halves matter for the artifact advisory:
     * the values there are inside normal limits *and* they do not stand out
     * from the rest of the audiogram by more than [FLAT_SPREAD_DB]. A genuine
     * low-frequency loss can still be inside 20 dB HL while clearly being a
     * dip, and that case must not be waved away as a bad seal.
     *
     * Unrecorded frequencies answer false: the clinic cannot vouch for a point
     * it never measured.
     */
    fun isNormalAndFlatAt(frequenciesHz: List<Int>): Boolean {
        if (isEmpty) return false
        val median = medianDbHl() ?: return false
        return frequenciesHz.all { hz ->
            val values = listOfNotNull(leftDbHl[hz], rightDbHl[hz])
            values.isNotEmpty() && values.all {
                it <= NORMAL_LIMIT_DB && it - median <= FLAT_SPREAD_DB
            }
        }
    }

    /**
     * True when NAL-R has nothing to prescribe from this audiogram.
     *
     * See [prescriptionThresholdsDbHl] for why an entirely normal audiogram is
     * answered with silence rather than with a small curve.
     */
    val prescribesNothing: Boolean get() = isEmpty || withinNormalLimits

    /**
     * The thresholds to hand [NalRCompensationCalculator], per ear, in the
     * frame that calculator expects.
     *
     * ## The unit mapping, which is the whole point of this function
     *
     * [NalR.insertionGainDb] implements `IG(f) = 0.15*PTA + 0.31*H_T(f) + C(f)`
     * verbatim from COMPENSATION.md, and COMPENSATION.md step 3.2 names its
     * argument: `H_T(f)` "in dB HL". So the rule's natural input is **absolute
     * dB HL, positive, 0 = no loss** — precisely what an ENT form prints. No
     * conversion is needed and none is applied: the numbers go in as they are
     * written on the form, interpolated onto [TEST_FREQUENCIES_HZ] on a log
     * frequency axis because those are the frequencies the calculator asks
     * about.
     *
     * ## Why the device calibration preset is not applied to these
     *
     * The preset exists to undo the headphone's own frequency response, which
     * is what turns a level this app played into an approximation of dB HL.
     * A clinical audiogram never went through a headphone, so subtracting a
     * headphone's response from it would corrupt a calibrated measurement with
     * a correction for hardware that was not in the path. Callers must pass
     * [CalibrationPresetRepository.GENERIC_ID], whose offsets are all zero.
     *
     * ## Why a normal audiogram returns zeros
     *
     * NAL-R is a prescription for hearing *loss*, fitted on impaired ears. Fed
     * a threshold of 10 dB HL it still returns two to five decibels — but look
     * at where those come from: `0.31 * 10` plus a share of the PTA, shaped by
     * `C(f)`, and `C(f)` is a population constant describing the speech
     * spectrum, identical for every listener. After the pipeline's headroom
     * pre-gain removes the common level, all that is left of a flat normal
     * audiogram is that constant tilt — a few dB of bass cut that says nothing
     * whatever about these ears. Applying it would be inventing a correction,
     * which is the one thing this app's compensation is not allowed to do.
     *
     * So an audiogram entirely inside [NORMAL_LIMIT_DB] prescribes zero, and
     * the UI says so in words. Yes, that is a step at the 20 dB line rather
     * than a ramp: it is the same step clinical practice makes when it declines
     * to fit an aid to a normal audiogram, and a defensible step is better than
     * a smooth curve derived from a rule used outside its domain. Any ear with
     * a real loss goes in unmodified and at full strength — nothing is scaled
     * down, so a genuine loss is never under-prescribed by this gate.
     */
    fun prescriptionThresholdsDbHl(
        ear: Ear,
        frequenciesHz: List<Int> = TEST_FREQUENCIES_HZ,
    ): List<Double> {
        if (prescribesNothing) return List(frequenciesHz.size) { 0.0 }
        val recorded = valuesFor(ear).entries.sortedBy { it.key }
        if (recorded.isEmpty()) return List(frequenciesHz.size) { 0.0 }
        val xs = recorded.map { it.key.toDouble() }
        val ys = recorded.map { it.value }
        return frequenciesHz.map { hz -> logInterpolateMonotone(xs, ys, hz.toDouble()) }
    }

    /**
     * This audiogram as the [Audiogram] the compensation pipeline consumes, or
     * null while nothing has been entered.
     *
     * The run id is a marker rather than a reference: no test run produced
     * these points, and anything that goes looking for the runs behind them
     * should find a name that cannot be mistaken for one.
     *
     * Every point is marked converged. A hollow marker in this app means "the
     * measurement hit its own floor or ceiling", and a clinical audiometer
     * reaching its limits would have been written on the form as a no-response
     * symbol, which is not something that can be typed into the editor at all.
     */
    fun toAudiogram(frequenciesHz: List<Int> = TEST_FREQUENCIES_HZ): Audiogram? {
        if (isEmpty) return null
        fun points(ear: Ear): List<ThresholdPoint> =
            frequenciesHz.zip(prescriptionThresholdsDbHl(ear, frequenciesHz)) { hz, db ->
                ThresholdPoint(frequencyHz = hz, thresholdDb = db, converged = true)
            }
        return Audiogram(runIds = listOf(RUN_ID), left = points(Ear.LEFT), right = points(Ear.RIGHT))
    }

    companion object {
        /**
         * The clinical boundary of normal hearing, in dB HL. Audiology's line,
         * not this app's — see [withinNormalLimits].
         */
        const val NORMAL_LIMIT_DB: Double = 20.0

        /**
         * How far above the audiogram's own median a point may sit and still
         * count as part of a flat curve, in dB.
         *
         * One audiometric step is 5 dB, so two steps is the smallest spread
         * that cannot be produced by rounding alone. Anything larger is a shape
         * worth taking seriously rather than smoothing over.
         */
        const val FLAT_SPREAD_DB: Double = 10.0

        /** Marks an [Audiogram] built from clinical values, not from runs. */
        const val RUN_ID: String = "clinical"

        internal fun medianOf(values: List<Double>): Double {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }
}

/**
 * Which set of thresholds the compensation is built from.
 *
 * Only ever a real choice while a [ClinicalAudiogram] exists; without one there
 * is nothing to choose between and the app does not ask. Persisted, because it
 * decides what the EQ does and a setting that resets itself on the next launch
 * would silently swap the curve underneath the listener.
 */
enum class CompensationSource {
    /**
     * The median of the hearing-test runs measured through this headphone —
     * the app's own measurement, and the default.
     *
     * It is the default even though the clinical values are the better data,
     * because it is the only one of the two that is a property of *this*
     * headphone on *these* ears. It also always exists once a test has been
     * run, whereas a clinical audiogram is something most people will never
     * have.
     */
    MEASURED,

    /**
     * The clinical audiogram, in calibrated dB HL.
     *
     * Better data in every respect except one: it says nothing about the
     * headphone. See [ClinicalAudiogram.prescriptionThresholdsDbHl] for how the
     * values enter the prescription and why no device correction is applied.
     */
    CLINICAL,
}

/**
 * The one artifact a headphone self-test produces often enough to be worth
 * naming on screen: raised low-frequency thresholds.
 *
 * Both of the things that go wrong in a living room hit the bottom of the range
 * hardest. A leaking seal — a tip that has worked loose, glasses under an
 * earpad, a cup that does not sit — costs mostly bass, because that is the part
 * of the spectrum that escapes through a gap; and room noise is bass-heavy
 * almost everywhere (traffic, ventilation, the building itself), so what it
 * masks first is the quiet low tones. Either one raises 250 and 500 Hz while
 * leaving the mids alone, and the result looks exactly like a low-frequency
 * hearing loss.
 *
 * It can be told apart from one, but only with an outside reference. That is
 * what the owner's own case demonstrates: a headphone app shows raised lows
 * while the clinical audiogram taken with calibrated equipment is flat and
 * normal down to 125 Hz. Same ears, one week apart — so the raised lows were
 * never in the ears.
 *
 * The rule below therefore never fires on the shape alone. It needs the shape
 * *plus* a reason to disbelieve it: either a clinical audiogram that is normal
 * and flat down there, or a room the microphone measured as loud.
 */
object LowToneArtifact {

    /** The frequencies a leak or a noisy room raises first. */
    val LOW_FREQUENCIES_HZ: List<Int> = listOf(250, 500)

    /**
     * The frequencies the lows are compared against.
     *
     * Deliberately excludes 6 and 8 kHz. The top of the range is where a real,
     * ordinary high-frequency loss lives, and letting it into the reference
     * would drag the median up and hide the very tilt this looks for.
     */
    val MID_FREQUENCIES_HZ: List<Int> = listOf(1000, 2000, 3000, 4000)

    /**
     * How much worse the lows must be than the run's own mid median before the
     * advisory appears, in dB.
     *
     * Two audiometric steps. One step is inside the test-retest spread of a
     * self-administered Hughson-Westlake run and would fire on noise.
     */
    const val RAISED_BY_DB: Double = 10.0

    /**
     * Room level from which masking is the more likely explanation, in dB(A).
     *
     * Higher than [dev.dankyeeter.btdashboard.hearing.noise.MicAmbientNoiseCheck.WARN_THRESHOLD_DB],
     * on purpose. That constant decides when to suggest a quieter room before a
     * run; this one decides when to tell someone their finished result is
     * probably an artifact, which is a much stronger claim and should not be
     * made about a merely imperfect room. The phone microphone is uncalibrated
     * either way, so this is a coarse gate and nothing finer.
     */
    const val NOISY_ROOM_DB: Double = 45.0

    /** Why the advisory is being shown; both reasons can hold at once. */
    data class Advice(
        val clinicalContradicts: Boolean,
        val roomWasNoisy: Boolean,
    )

    /**
     * Whether the freshly finished [run] should carry the low-tone advisory,
     * or null when it should not.
     *
     * Pure and total: this is the whole decision, so the screen has nothing to
     * decide and the rule can be tested without a UI. Each ear is judged on its
     * own median — an asymmetric leak is the common case, not the exception —
     * and one ear is enough to raise the notice, because the reason it exists
     * applies just as much to a single loose tip.
     */
    fun evaluate(
        run: AudiogramRun,
        clinical: ClinicalAudiogram?,
        ambientNoiseDbA: Double? = run.ambientNoiseDbA,
    ): Advice? {
        val raised = Ear.entries.any { ear -> lowTonesAreRaised(run.points(ear)) }
        if (!raised) return null

        val clinicalContradicts = clinical?.isNormalAndFlatAt(LOW_FREQUENCIES_HZ) == true
        val roomWasNoisy = (ambientNoiseDbA ?: Double.NEGATIVE_INFINITY) >= NOISY_ROOM_DB
        if (!clinicalContradicts && !roomWasNoisy) return null
        return Advice(clinicalContradicts = clinicalContradicts, roomWasNoisy = roomWasNoisy)
    }

    /**
     * The shape test, on one ear.
     *
     * Both scales this can be handed run in the same direction — a larger
     * number is a worse threshold in dBFS just as in dB HL — so the comparison
     * needs no knowledge of which scale it is looking at. That is also why it
     * is done against the run's *own* mid median rather than any fixed level:
     * the absolute offset of the app's scale is unknown, and it cancels.
     *
     * Points that did not converge are ignored. One sitting on the level floor
     * says "quieter than the app can ask", which is a statement about the test,
     * and letting it into either side of the comparison would compare the
     * test's limits with itself.
     */
    fun lowTonesAreRaised(points: List<ThresholdPoint>): Boolean {
        val usable = points.filter { it.converged }.associate { it.frequencyHz to it.thresholdDb }
        val mids = MID_FREQUENCIES_HZ.mapNotNull { usable[it] }
        if (mids.isEmpty()) return false
        val midMedian = ClinicalAudiogram.medianOf(mids)
        val lows = LOW_FREQUENCIES_HZ.mapNotNull { usable[it] }
        if (lows.isEmpty()) return false
        return lows.any { it - midMedian >= RAISED_BY_DB }
    }
}
