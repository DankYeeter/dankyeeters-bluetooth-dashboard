package dev.dankyeeter.btdashboard.audio.eq

/**
 * The numbers behind the "boosts act only on quiet passages" mode.
 *
 * A static EQ raises a band by the same amount whether the signal there is a
 * whisper of room reverb or a full snare hit — which is why boosting either
 * clips or has to buy headroom by making everything quieter. A healthy
 * cochlea does neither: its outer hair cells amplify quiet sound and compress
 * loud sound. This mode borrows that shape using the multiband compressor
 * stage that Android's DynamicsProcessing effect already carries.
 *
 * Per band with a positive gain B: the band's post-gain is raised by B, and a
 * compressor above [THRESHOLD_DB] takes the boost back as the level rises, at
 * a ratio chosen so the net gain reaches zero exactly at full scale. Quiet
 * detail gets the full lift, loud passages pass as recorded, and nothing can
 * exceed the level the recording already had — which is why this mode needs no
 * pre-gain headroom for its boosts.
 *
 * Cuts stay in the static pre-EQ: attenuation cannot clip, and making a cut
 * level-dependent would un-fix whatever the cut was for.
 */
object LoudnessRestorationMath {

    /**
     * Where the compression starts, in dBFS.
     *
     * Below this a band keeps its full boost. Popular music averages around
     * −14 to −20 dBFS; the detail this mode exists for — reverb tails,
     * overtones, room air — sits well below that. −35 leaves the average level
     * of a track in the transition region rather than fully boosted, which is
     * what keeps the mode from being a plain volume increase.
     */
    const val THRESHOLD_DB = -35f

    /** Soft knee, so the transition is a slope rather than a corner. */
    const val KNEE_WIDTH_DB = 10f

    /**
     * Fast enough that a drum hit does not slip through at full boost and then
     * audibly duck; slow enough on release that the gain does not pump between
     * syllables. Values in the range hearing-aid compressors use.
     */
    const val ATTACK_MS = 3f
    const val RELEASE_MS = 80f

    /**
     * The ratio that makes the net gain hit zero at 0 dBFS.
     *
     * Above the threshold the compressor removes (1 − 1/ratio) dB of output
     * per input dB. Over the (0 − threshold) dB between threshold and full
     * scale it must remove exactly the boost:
     *
     *     boost = (0 − threshold) · (1 − 1/ratio)
     *
     * solved for the ratio. A boost of zero degenerates to 1 (no compression),
     * and the boost is clamped below the available range so the ratio stays
     * finite — with a 15 dB slider maximum that clamp is never reached.
     */
    fun ratioFor(boostDb: Float, thresholdDb: Float = THRESHOLD_DB): Float {
        val range = -thresholdDb
        val boost = boostDb.coerceIn(0f, range - 1f)
        if (boost == 0f) return 1f
        return range / (range - boost)
    }
}
