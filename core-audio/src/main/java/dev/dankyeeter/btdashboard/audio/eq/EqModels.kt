package dev.dankyeeter.btdashboard.audio.eq

/**
 * Which ear a set of band gains applies to. `DynamicsProcessing` exposes the
 * pre-EQ per channel, so left/right can differ — that is what makes asymmetric
 * hearing loss compensable.
 */
enum class Ear(val channelIndex: Int) {
    LEFT(0),
    RIGHT(1),
}

/**
 * Layout-independent EQ constants.
 *
 * The band *positions* moved to [EqBandLayout] once the EQ stopped being fixed
 * at ten; what is left here applies to every layout.
 */
object EqBands {
    /** Gain range the UI and the compensation math must stay inside. */
    const val MIN_GAIN_DB: Float = -15f
    const val MAX_GAIN_DB: Float = 15f

    /** The default layout's centres, kept for callers that predate layouts. */
    val CENTER_FREQUENCIES_HZ: List<Float> get() = EqBandLayout.DEFAULT.centersHz

    val COUNT: Int get() = EqBandLayout.DEFAULT.bandCount

    val EXTRAPOLATED_INDICES: Set<Int> get() = EqBandLayout.DEFAULT.extrapolatedIndices
}

/**
 * How far [EqSettings.sanitized] is allowed to move the automatic pre-gain.
 *
 * The two directions are not symmetrical in cost, which is why they are a
 * choice rather than one rule. Making the headroom *deeper* is always safe and
 * always urgent: the boost is already in the signal, so the pre-gain has to
 * follow it in the same write or the next buffer clips. Making it *shallower*
 * gives level back, and doing that on every intermediate value of a drag turns
 * a slider pull into audible pumping — the music rising and falling while the
 * finger is still down.
 *
 * So: [DEEPEN_ONLY] while an edit is in flight, [TRACK] once it has settled.
 */
enum class HeadroomMode {
    /**
     * The pre-gain may only get deeper. A boost is charged immediately; a boost
     * taken back keeps its headroom until the edit is committed.
     */
    DEEPEN_ONLY,

    /**
     * The pre-gain is set to exactly what the curve costs right now, in both
     * directions. This is the committed state — a drag that has been released,
     * a switch that has been flipped, a preset that has been loaded — where
     * "what is playing" and "what it costs" have to agree.
     */
    TRACK,
}

/**
 * Complete state of the system EQ. This is the single object persisted by
 * :core-system and produced by the compensation math in :core-hearing.
 *
 * @param enabled master on/off; when false the effect is detached, not zeroed
 * @param leftGainsDb 10 gains in dB for the left channel, index-aligned with
 *   [EqBands.CENTER_FREQUENCIES_HZ]
 * @param rightGainsDb same for the right channel
 * @param preGainDb input gain applied before the bands. Should be negative
 *   headroom: roughly `-max(0, maxBandGain)` so boosted bands cannot clip.
 * @param limiterEnabled enables the built-in per-channel limiter as the last
 *   stage (tames loudly mastered tracks; not a loudness normaliser)
 * @param autoHeadroom lowers the whole signal by whatever the loudest band was
 *   raised, so nothing can overflow. On by default. Turning it off makes a
 *   boost audible *as* a boost - which is what a person expects when they drag
 *   a band upwards - at the price that a loud passage can now clip. The
 *   limiter is the second net; whoever switches that off as well is on their
 *   own, and should be.
 */
data class EqSettings(
    val enabled: Boolean = false,
    val layout: EqBandLayout = EqBandLayout.DEFAULT,
    val leftGainsDb: List<Float> = List(layout.bandCount) { 0f },
    val rightGainsDb: List<Float> = List(layout.bandCount) { 0f },
    val preGainDb: Float = 0f,
    val limiterEnabled: Boolean = true,
    val autoHeadroom: Boolean = true,
    /**
     * Routes the positive band gains through the effect's multiband
     * compressor so they act on quiet passages only — loud passages pass as
     * recorded. Cuts stay static. See [LoudnessRestorationMath] for the shape
     * and the reasoning; the practical consequence here is that boosts in
     * this mode cannot clip and therefore cost no headroom.
     */
    val loudnessRestoration: Boolean = false,
    /**
     * Whether the ISO 226 volume-aware tilt is switched on. This is the part
     * the user owns and the part that is persisted; [tiltGainsDb] is derived.
     */
    val volumeAwareTilt: Boolean = false,
    /**
     * The tilt layer for the volume that is set right now, in dB per band.
     *
     * Derived, never stored and never edited: whoever pushes settings into the
     * pipeline recomputes it from the media-volume fraction (see
     * [VolumeAwareTilt]), so a restored or imported curve arrives at zeros and
     * is filled in on the way to the effect. Kept separate from
     * [leftGainsDb]/[rightGainsDb] rather than folded into them because those
     * are the user's own curve: folding would mean saving a correction for
     * whatever the volume happened to be at the moment of the save, and
     * saving it again on top of itself the next time.
     *
     * Applies to both ears. The tilt is a property of the listening level, not
     * of an ear, and giving it a side would imply a measurement that does not
     * exist.
     */
    val tiltGainsDb: List<Float> = List(layout.bandCount) { 0f },
) {
    init {
        require(leftGainsDb.size == layout.bandCount) {
            "leftGainsDb must have ${layout.bandCount} entries for ${layout.id}"
        }
        require(rightGainsDb.size == layout.bandCount) {
            "rightGainsDb must have ${layout.bandCount} entries for ${layout.id}"
        }
        require(tiltGainsDb.size == layout.bandCount) {
            "tiltGainsDb must have ${layout.bandCount} entries for ${layout.id}"
        }
    }

    val bandCount: Int get() = layout.bandCount

    val centersHz: List<Float> get() = layout.centersHz

    /**
     * Same curve, different resolution. Gains are resampled rather than reset,
     * so trying a finer layout never costs the user the setting they had.
     */
    fun withLayout(target: EqBandLayout): EqSettings {
        if (target == layout) return this
        return copy(
            layout = target,
            leftGainsDb = EqBandLayout.resample(leftGainsDb, layout, target),
            rightGainsDb = EqBandLayout.resample(rightGainsDb, layout, target),
            // Resampled like the rest even though the owner recomputes it from
            // the volume anyway: the size invariant in `init` has to hold at
            // every intermediate step, and a copy() with a new layout and a
            // stale tilt list would throw before anyone got the chance.
            tiltGainsDb = EqBandLayout.resample(tiltGainsDb, layout, target),
        ).sanitized()
    }

    fun gainsFor(ear: Ear): List<Float> = when (ear) {
        Ear.LEFT -> leftGainsDb
        Ear.RIGHT -> rightGainsDb
    }

    /**
     * The tilt actually in force: zero unless the feature is switched on, so
     * "off" and "flat" are the same signal path rather than two of them.
     */
    val activeTiltDb: List<Float>
        get() = if (volumeAwareTilt) tiltGainsDb else List(layout.bandCount) { 0f }

    /**
     * What the static pre-EQ gets, per band: the user's curve plus the tilt.
     *
     * The tilt composes as an additional gain layer rather than as its own
     * effect stage, which is what lets it coexist with a compensation curve, a
     * hand-tuned preset and loudness restoration without any of them knowing
     * about it.
     *
     * With [loudnessRestoration] on, the user's boosts move to the compressor
     * ([compressorGainsFor]) and only their cuts stay here — but **the tilt
     * stays static in either mode**. WHY: the tilt is a correction for the
     * *volume setting*, and the compressor gives its gain back as the signal
     * gets louder. Routed through it, the correction would disappear on
     * exactly the loud passages it was computed for, which is the opposite of
     * what the equal-loudness contours say. The price is that tilt boosts cost
     * headroom even in that mode, and [sanitized] charges it.
     */
    fun staticGainsFor(ear: Ear): List<Float> {
        val user = gainsFor(ear)
        val tilt = activeTiltDb
        return List(layout.bandCount) { band ->
            val base = if (loudnessRestoration) user[band].coerceAtMost(0f) else user[band]
            (base + tilt[band]).coerceIn(EqBands.MIN_GAIN_DB, EqBands.MAX_GAIN_DB)
        }
    }

    /** What the multiband compressor gets: the user's boosts, in that mode only. */
    fun compressorGainsFor(ear: Ear): List<Float> =
        if (!loudnessRestoration) {
            List(layout.bandCount) { 0f }
        } else {
            gainsFor(ear).map { it.coerceAtLeast(0f) }
        }

    /**
     * Clamps all gains into the supported range and, if asked, recomputes safe
     * headroom.
     *
     * The headroom rule used to be unconditional, and it made every boost
     * inaudible as a boost: raising a band by 15 dB lowered everything else by
     * 15 dB, so the band ended up back where it started and the only audible
     * change was that the music got quieter. Correct against clipping, useless
     * as a control. It stays the default and is now a choice - see
     * [autoHeadroom].
     *
     * @param headroom which way the automatic pre-gain may move; see
     *   [HeadroomMode]. The default is the safe one, so every caller that has
     *   not thought about it charges for a boost and never hands level back.
     *   The callers that *have* thought about it — the ones that know an edit
     *   is finished — pass [HeadroomMode.TRACK], which is what lets a band
     *   pulled back to zero give its 5 dB back instead of leaving the music
     *   quiet until Reset is pressed.
     */
    fun sanitized(headroom: HeadroomMode = HeadroomMode.DEEPEN_ONLY): EqSettings {
        val l = leftGainsDb.map { it.coerceIn(EqBands.MIN_GAIN_DB, EqBands.MAX_GAIN_DB) }
        val r = rightGainsDb.map { it.coerceIn(EqBands.MIN_GAIN_DB, EqBands.MAX_GAIN_DB) }
        val tilt = tiltGainsDb.map { it.coerceIn(0f, VolumeAwareTilt.MAX_TILT_DB) }
        val clamped = copy(leftGainsDb = l, rightGainsDb = r, tiltGainsDb = tilt)
        // Whatever the static pre-EQ ends up writing is what can clip, so the
        // headroom is read off exactly that. With loudness restoration on the
        // user's boosts have moved to the compressor and contribute nothing
        // here — but the tilt has not moved, and it is charged in both modes.
        val peak = Ear.entries
            .flatMap { clamped.staticGainsFor(it) }
            .maxOrNull()
            ?.coerceAtLeast(0f)
            ?: 0f
        val gain = preGainDb.coerceIn(-24f, 0f)
        // What the curve costs right now. Written as a branch rather than as a
        // bare `-peak` because negating a zero float gives -0.0f, which is
        // equal to 0f for arithmetic, unequal to it for `equals`, and reads on
        // screen as "Headroom: -0.0 dB" — a flat EQ announcing an attenuation
        // it is not applying.
        val target = if (peak > 0f) -peak else 0f
        // DEEPEN_ONLY takes that only when it is deeper than what is already
        // set, which is what keeps a drag from pumping and what keeps a
        // hand-set headroom from being overwritten; TRACK takes it outright,
        // because at that point the two numbers describe the same moment and
        // disagreeing is the bug.
        return clamped.copy(
            preGainDb = when {
                !autoHeadroom -> gain
                headroom == HeadroomMode.TRACK -> target
                else -> gain.coerceAtMost(target)
            },
        )
    }

    companion object {
        val FLAT = EqSettings()

        // `headroomFor(vararg gains)` used to live here and is deliberately
        // gone. It answered from the user's band gains alone, so it forgot the
        // volume tilt — which stays on the static path in every mode and has to
        // be paid for there — and it forgot that loudness restoration moves the
        // boosts somewhere they cost nothing. Its two callers used it to
        // pre-set a pre-gain that `sanitized()` was about to compute properly
        // anyway. [sanitized] with [HeadroomMode.TRACK] is the one answer now.
    }
}
