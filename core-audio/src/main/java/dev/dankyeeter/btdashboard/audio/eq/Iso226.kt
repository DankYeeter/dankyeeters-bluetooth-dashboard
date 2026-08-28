package dev.dankyeeter.btdashboard.audio.eq

import kotlin.math.log10
import kotlin.math.pow

/**
 * The equal-loudness contours of **ISO 226:2003**, *Acoustics — Normal
 * equal-loudness-level contours*, clause 4.1.
 *
 * The standard gives, for 29 third-octave frequencies from 20 Hz to 12.5 kHz,
 * three coefficients — the exponent `af`, the loudness-linearity offset `Lu`
 * and the threshold of hearing `Tf` — and one formula that turns a loudness
 * level in phon into the sound pressure level in dB that produces it:
 *
 *     Af = 4.47e-3 * (10^(0.025*Ln) - 1.15)
 *          + [0.4 * 10^(((Tf + Lu) / 10) - 9)]^af
 *     Lp = (10 / af) * log10(Af) - Lu + 94
 *
 * The tables below are the standard's own published values, transcribed, not
 * fitted or invented. They are verifiable from outside this file: at 1 kHz the
 * formula must return the phon value it was given, because that is how the
 * phon is defined, and [Iso226Test] checks exactly that across the whole
 * supported range. Any typo in the 1 kHz row shows up there; a typo elsewhere
 * shows up in the monotonicity and shape properties.
 *
 * What this is for: the contours are **steeper at low levels**. Halving the
 * listening level does not take the same number of dB out of the bass as it
 * does out of the mids — the bass loses more loudness, which is why quiet
 * listening sounds thin. Section 4.3 of REPORT-2026-08-26 calls this the one
 * physically hard, level-dependent (not person-dependent) effect behind what
 * headphone vendors sell as "personalisation"; [VolumeAwareTilt] turns it into
 * a correction curve.
 *
 * Nothing here is Android-specific and nothing here knows about volume: this
 * object is the standard and only the standard.
 */
object Iso226 {

    /** The standard's 29 frequencies, in Hz. */
    val FREQUENCIES_HZ: List<Float> = listOf(
        20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f,
        200f, 250f, 315f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f,
        2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f,
    )

    /** Exponent of loudness perception, `af` in the standard's notation. */
    private val AF = doubleArrayOf(
        0.532, 0.506, 0.480, 0.455, 0.432, 0.409, 0.387, 0.367, 0.349, 0.330,
        0.315, 0.301, 0.288, 0.276, 0.267, 0.259, 0.253, 0.250, 0.246, 0.244,
        0.243, 0.243, 0.243, 0.242, 0.242, 0.245, 0.254, 0.271, 0.301,
    )

    /** Magnitude of the linear transfer function normalised at 1 kHz, `Lu` (dB). */
    private val LU = doubleArrayOf(
        -31.6, -27.2, -23.0, -19.1, -15.9, -13.0, -10.3, -8.1, -6.2, -4.5,
        -3.1, -2.0, -1.1, -0.4, 0.0, 0.3, 0.5, 0.0, -2.7, -4.1,
        -1.0, 1.7, 2.5, 1.2, -2.1, -7.1, -11.2, -10.7, -3.1,
    )

    /** Threshold of hearing, `Tf` (dB SPL). */
    private val TF = doubleArrayOf(
        78.5, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9,
        14.4, 11.4, 8.6, 6.2, 4.4, 3.0, 2.2, 2.4, 3.5, 1.7,
        -1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6, 13.9, 12.3,
    )

    /**
     * The frequency the phon scale is defined at: there, and only there, a
     * loudness level of N phon *is* N dB SPL. Every curve in here is
     * normalised against this point rather than against a band average,
     * because that is the one place where "same number, same meaning" is a
     * definition instead of a choice.
     */
    const val ANCHOR_HZ: Float = 1000f

    private const val ANCHOR_INDEX = 17

    /**
     * The loudness levels the standard covers.
     *
     * ISO 226:2003 states its contours for 20 to 90 phon (to 80 phon above
     * 4 kHz). Asking outside that range would be extrapolating the standard,
     * so callers are clamped into it rather than quietly answered.
     */
    const val MIN_PHON: Float = 20f
    const val MAX_PHON: Float = 90f

    /** Sound pressure level in dB that sounds as loud as [phon] phon at [index]. */
    fun soundPressureLevelDb(index: Int, phon: Float): Float {
        val ln = phon.coerceIn(MIN_PHON, MAX_PHON).toDouble()
        val af = AF[index]
        val lu = LU[index]
        val tf = TF[index]
        val a = 4.47e-3 * (10.0.pow(0.025 * ln) - 1.15) +
            (0.4 * 10.0.pow((tf + lu) / 10.0 - 9.0)).pow(af)
        return ((10.0 / af) * log10(a) - lu + 94.0).toFloat()
    }

    /** One whole contour: the SPL of every [FREQUENCIES_HZ] entry at [phon]. */
    fun contourDb(phon: Float): List<Float> =
        FREQUENCIES_HZ.indices.map { soundPressureLevelDb(it, phon) }

    /**
     * The **shape** difference between listening at [currentPhon] and at
     * [referencePhon], anchored at [ANCHOR_HZ].
     *
     * Read it as: playing a programme mixed for the reference loudness at the
     * lower current loudness attenuates every frequency by the same physical
     * amount, but the ear does not lose the same *loudness* everywhere — it
     * loses more at the edges of the spectrum than in the middle. The value
     * returned per band is how much level has to be added back there so the
     * band sits where it sat, relative to the mids, at the reference level.
     *
     * Positive means "quiet listening lost this much here". Values are
     * unclamped and unbounded on purpose: bounding is a product decision and
     * lives in [VolumeAwareTilt], not in the transcription of a standard. At
     * the anchor the result is exactly zero, so the correction can never move
     * the overall level — only the balance.
     *
     * A small *negative* region around 1.25–5 kHz is not a bug: those bands
     * grow marginally faster than 1 kHz does (their `af` is below 0.250), so
     * the standard really does imply a slight cut there. What happens to it is
     * again [VolumeAwareTilt]'s decision.
     */
    fun tiltDb(currentPhon: Float, referencePhon: Float): List<Float> {
        val current = contourDb(currentPhon)
        val reference = contourDb(referencePhon)
        val anchor = current[ANCHOR_INDEX] - reference[ANCHOR_INDEX]
        return FREQUENCIES_HZ.indices.map { (current[it] - reference[it]) - anchor }
    }

    /**
     * Puts a curve given on [FREQUENCIES_HZ] onto a band layout's centres.
     *
     * Uses [EqBandLayout.interpolateAtLogFrequency], the same rule a stored
     * curve is resampled with when the user changes the band count: linear in
     * log frequency, nearest edge held outside the source range. That last
     * part matters here — ISO 226:2003 stops at 12.5 kHz, and every layout has
     * bands above it. Holding the 12.5 kHz value is the honest option; a
     * linear extrapolation of a curve that is already steep would invent a
     * correction of ten dB or more for a region the standard says nothing
     * about.
     */
    fun resampleTo(curveDb: List<Float>, layout: EqBandLayout): List<Float> =
        layout.centersHz.map { EqBandLayout.interpolateAtLogFrequency(it, FREQUENCIES_HZ, curveDb) }
}
