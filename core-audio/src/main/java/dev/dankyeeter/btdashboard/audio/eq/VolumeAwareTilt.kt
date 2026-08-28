package dev.dankyeeter.btdashboard.audio.eq

import kotlin.math.roundToInt

/**
 * How much bass and treble the current listening volume is costing, expressed
 * as a per-band gain curve.
 *
 * The mechanism is [Iso226]: equal-loudness contours are steeper at low levels,
 * so turning the music down takes more perceived bass and treble away than it
 * takes midrange. This object turns that into a correction and, just as
 * importantly, states the two things it has to assume to do so.
 *
 * ## The honest part
 *
 * The phone knows the media-volume **fraction**. It does not know the sound
 * pressure level at the eardrum, and it cannot: that depends on the headphone's
 * sensitivity, the fit, the source material's mastering level and the amplifier
 * in the phone. Everything below therefore rests on a mapping from a slider
 * position to a loudness level, and that mapping is an assumption with a name
 * and a number rather than a measurement. The UI says so in as many words —
 * "shaped from the ISO 226 average ear and your volume, an estimate, not a
 * measurement" — and the constants here carry the reasoning.
 *
 * Two further honesty limits, both deliberate:
 *
 *  * The contours are the **average** ear. Between normal-hearing listeners the
 *    scatter is 5–6 dB standard deviation (REPORT-2026-08-26, 4.3) — real, but
 *    the same size as the measurement error of a consumer hearing test, which
 *    is why this is a level-dependent correction and not a personal one.
 *  * Nothing is ever cut. See [MAX_TILT_DB] and the clamp in [curveFor].
 */
object VolumeAwareTilt {

    /**
     * The loudness the correction is referenced to: the level at which the
     * curve is flat because nothing needs restoring.
     *
     * 78 phon is the middle of the 75–80 phon band usually quoted for
     * comfortable music listening (film mixing references 83 dB SPL per
     * channel; domestic listening sits below it). It is a convention, not a
     * measurement, and it is the number to change if the whole feature ever
     * feels shifted rather than wrong.
     */
    const val REFERENCE_PHON: Float = 78f

    /**
     * The media-volume fraction assumed to produce [REFERENCE_PHON].
     *
     * Two thirds of the slider. WHY this value: it is where phones sit for
     * ordinary listening on a normally sensitive headphone, and it is the same
     * neighbourhood as the 0.7 fraction the hearing test standardises on
     * (`BackupRun.volumeFraction`), so the two features do not disagree about
     * what "normal" means.
     */
    const val REFERENCE_FRACTION: Float = 0.67f

    /**
     * The quietest listening level the mapping will claim.
     *
     * ISO 226:2003 goes down to 20 phon, but below roughly 40 phon two things
     * stop being true: the fraction-to-level mapping below has no calibration
     * left to stand on, and the correction it implies exceeds what the cap
     * allows anyway. Stopping at 40 phon means the curve reaches its widest
     * shape and stays there instead of growing on an assumption.
     */
    const val FLOOR_PHON: Float = 40f

    /**
     * The cap on the correction, in dB.
     *
     * The uncapped curve is genuinely large: at 40 phon against the reference
     * the standard implies roughly 20 dB at 20 Hz. Twelve dB is where three
     * things stop being sensible — the pre-EQ's own ±15 dB range, the headroom
     * this has to buy (a 12 dB boost costs 12 dB of pre-gain with automatic
     * headroom on, so the balance shifts and the level drops), and the
     * headphone's ability to actually deliver 20 dB of extra sub-bass without
     * distorting. Below about 50 Hz at low volumes the curve is therefore a
     * plateau at the cap rather than the standard's own slope. That is a
     * deliberate, visible limit, not an approximation.
     */
    const val MAX_TILT_DB: Float = 12f

    /**
     * Gains are rounded to this step.
     *
     * Volume is a stepped control and every step would otherwise rewrite every
     * band with a difference of hundredths of a dB — inaudible, but a write to
     * the audio effect all the same. Rounding gives the pipeline something
     * stable to compare against, so holding the volume-down key re-applies the
     * EQ only when the correction has actually moved.
     */
    private const val QUANTUM_DB: Float = 0.25f

    /**
     * AOSP's default media volume curve: fraction of the slider to attenuation
     * in dB, interpolated linearly in between.
     *
     * These four points are the platform's own `sDefaultMediaVolumeCurve`
     * (AudioPolicyManager): −58 dB at the bottom step, −40 dB at a fifth,
     * −17 dB at three fifths, 0 dB at the top. WHY a curve and not a straight
     * line: the media slider is not linear in dB, and treating it as such would
     * put the biggest error exactly where the feature matters most, at the
     * quiet end. WHY it is still an estimate: OEMs replace this curve, and a
     * device with absolute volume delegating to the headphone may follow a
     * different one entirely.
     */
    private val VOLUME_CURVE_DB: List<Pair<Float, Float>> = listOf(
        0.01f to -58f,
        0.20f to -40f,
        0.60f to -17f,
        1.00f to 0f,
    )

    /** Attenuation in dB the media slider applies at [fraction]. */
    fun attenuationDbAt(fraction: Float): Float {
        val x = fraction.coerceIn(0f, 1f)
        val first = VOLUME_CURVE_DB.first()
        if (x <= first.first) return first.second
        val last = VOLUME_CURVE_DB.last()
        if (x >= last.first) return last.second
        for (i in 1 until VOLUME_CURVE_DB.size) {
            val (x1, y1) = VOLUME_CURVE_DB[i]
            if (x > x1) continue
            val (x0, y0) = VOLUME_CURVE_DB[i - 1]
            return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
        }
        return last.second
    }

    /**
     * The listening loudness the mapping assumes for a media-volume fraction.
     *
     * By construction [REFERENCE_FRACTION] maps to [REFERENCE_PHON]; from there
     * the platform volume curve carries the level up and down dB for dB. The
     * result is clamped into [FLOOR_PHON]..[Iso226.MAX_PHON] — the bottom
     * because the mapping runs out of ground, the top because that is where the
     * standard stops.
     */
    fun phonFor(volumeFraction: Float): Float {
        val delta = attenuationDbAt(volumeFraction) - attenuationDbAt(REFERENCE_FRACTION)
        return (REFERENCE_PHON + delta).coerceIn(FLOOR_PHON, Iso226.MAX_PHON)
    }

    /**
     * The correction curve on [Iso226.FREQUENCIES_HZ] for a listening level.
     *
     * **This stage only ever restores, and only ever below the reference.** Two
     * clamps say so:
     *
     *  * At or above [REFERENCE_PHON] the curve is flat, full stop. The
     *    symmetric case is real: above the reference the standard says a *cut*
     *    in bass and treble would hold the balance, and it also implies a small
     *    *boost* around 1.25–5 kHz there, because those bands grow marginally
     *    faster than 1 kHz does. Neither is applied in v1. Both would change a
     *    recording on the strength of a volume-to-SPL mapping this file has
     *    already admitted is an assumption, and they would do it in the
     *    direction where the listener has least reason to suspect the EQ: they
     *    turned the music up and got a colouration nobody asked for.
     *  * Below the reference every band is clamped into `0..`[MAX_TILT_DB], so
     *    the slight cut the standard implies at 1.25–5 kHz becomes nothing
     *    rather than a cut. A boost that is 3 dB too generous is a colouration
     *    the listener can hear and switch off; a cut that is 3 dB too deep
     *    removes material and reads as a defect in the recording.
     */
    fun curveFor(phon: Float): List<Float> {
        if (phon >= REFERENCE_PHON) return List(Iso226.FREQUENCIES_HZ.size) { 0f }
        return Iso226.tiltDb(currentPhon = phon, referencePhon = REFERENCE_PHON)
            .map { it.coerceIn(0f, MAX_TILT_DB) }
    }

    /**
     * The per-band gains to add for a given media-volume fraction and layout.
     *
     * Clamped before resampling, so no interpolated point can leave the bounds
     * the clamp established.
     */
    fun gainsFor(volumeFraction: Float, layout: EqBandLayout): List<Float> =
        Iso226.resampleTo(curveFor(phonFor(volumeFraction)), layout)
            .map { quantise(it) }

    /** True when a curve does nothing — used to keep "off" and "flat" identical. */
    fun isFlat(gainsDb: List<Float>): Boolean = gainsDb.all { it == 0f }

    private fun quantise(db: Float): Float =
        (db / QUANTUM_DB).roundToInt() * QUANTUM_DB

    /**
     * What the one-line readout says, in numbers.
     *
     * Deliberately the *largest* correction in each region rather than an
     * average: the reading exists to tell the user how far the curve has moved,
     * and an average over bands that are mostly zero would understate it.
     */
    fun summarise(gainsDb: List<Float>, layout: EqBandLayout): TiltSummary {
        val bass = layout.centersHz.indices
            .filter { layout.centersHz[it] <= BASS_MAX_HZ }
            .maxOfOrNull { gainsDb[it] } ?: 0f
        val treble = layout.centersHz.indices
            .filter { layout.centersHz[it] >= TREBLE_MIN_HZ }
            .maxOfOrNull { gainsDb[it] } ?: 0f
        return TiltSummary(bassDb = bass, trebleDb = treble)
    }

    /** Where "bass" ends and "treble" begins for the readout only. */
    private const val BASS_MAX_HZ = 250f
    private const val TREBLE_MIN_HZ = 4000f
}

/** The two numbers the EQ screen prints for the active tilt. */
data class TiltSummary(val bassDb: Float, val trebleDb: Float) {
    val isFlat: Boolean get() = bassDb <= 0f && trebleDb <= 0f
}

/**
 * Fills in [EqSettings.tiltGainsDb] for the volume that is set right now.
 *
 * The one place the derived layer is produced, so that everything which can
 * push settings into the pipeline — the EQ screen while it is open, the
 * foreground service while it is not — composes it identically. Switched off,
 * the layer is written back as zeros rather than left as it was: a stale tilt
 * surviving the switch would be the exact bug this centralisation prevents.
 *
 * Idempotent, and safe to call on any layout: the curve is resampled onto
 * whatever [EqSettings.layout] says, so a layout change followed by this call
 * always satisfies the model's size invariant.
 */
fun EqSettings.withVolumeTilt(volumeFraction: Float): EqSettings = copy(
    tiltGainsDb = if (volumeAwareTilt) {
        VolumeAwareTilt.gainsFor(volumeFraction, layout)
    } else {
        List(layout.bandCount) { 0f }
    },
)
