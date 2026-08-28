package dev.dankyeeter.btdashboard.hearing.preference

import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import dev.dankyeeter.btdashboard.audio.eq.EqBands
import dev.dankyeeter.btdashboard.audio.eq.EqSettings

/**
 * One headphone's preference curve: the pool of song-runs behind it, the EQ it
 * was measured on top of, and any hand adjustment made afterwards.
 *
 * ## Why it is bound to a headphone
 *
 * The same binding rule as
 * [dev.dankyeeter.btdashboard.hearing.DerivedCalibration], for a related but
 * not identical reason. That record is a *measurement* of a device. This one is
 * a *judgement made through* a device: the listener said "more bass" while
 * hearing this headphone's own bass response, on this headphone's seal, at this
 * headphone's level. Move the same +5 dB shelf to a pair that already has a
 * lifted low end and it is no longer the thing that was preferred. So one
 * profile per device key, greyed out for any other headphone, and re-testing on
 * a new pair rather than carrying the old answer across.
 *
 * ## Why the base curve is stored
 *
 * The test is deliberately run **on top of** whatever the listener normally
 * hears — their compensation curve, their manual preset, whatever is in force —
 * because that is the sound they are choosing between, and stripping it away to
 * test against flat would measure a preference for a sound they never listen to.
 * The consequence is that the answer only means anything next to that baseline,
 * so the baseline travels with it. Captured once, when the pool's first song-run
 * starts, and reused for every later song so that all the runs in one pool are
 * comparable; changing it mid-pool would make the median an average of answers
 * to different questions. Starting over re-captures it.
 *
 * This is the same reasoning
 * [dev.dankyeeter.btdashboard.hearing.CompensationProfile] gives for storing a
 * complete snapshot rather than a reference to whatever is current.
 *
 * @param manualBassDb a hand adjustment that overrides the pool's own answer on
 *   that axis, or null to follow the pool. Non-null on either axis makes the
 *   profile [handAdjusted], which is what the "your adjustment will be
 *   overwritten" question is asked from.
 */
data class PreferenceProfile(
    val deviceKey: String,
    val deviceName: String?,
    val runs: List<PreferenceRun> = emptyList(),
    val layout: EqBandLayout = EqBandLayout.DEFAULT,
    val baseLeftDb: List<Float> = List(layout.bandCount) { 0f },
    val baseRightDb: List<Float> = List(layout.bandCount) { 0f },
    val manualBassDb: Float? = null,
    val manualTrebleDb: Float? = null,
    val finalCheck: FinalCheck = FinalCheck.NOT_RUN,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
) {
    init {
        require(baseLeftDb.size == layout.bandCount) {
            "baseLeftDb must have ${layout.bandCount} entries for ${layout.id}"
        }
        require(baseRightDb.size == layout.bandCount) {
            "baseRightDb must have ${layout.bandCount} entries for ${layout.id}"
        }
    }

    /** What the pool alone says, before any hand adjustment. */
    val aggregate: PreferenceAggregate get() = PreferencePool.aggregate(runs, finalCheck)

    /** True once a slider has been moved by hand. */
    val handAdjusted: Boolean get() = manualBassDb != null || manualTrebleDb != null

    /**
     * The shelf actually applied: the hand adjustment where there is one, the
     * pool's answer everywhere else.
     *
     * Per axis, not all-or-nothing, so nudging the bass does not freeze the
     * treble at whatever it happened to be.
     */
    val candidate: PreferenceCandidate
        get() = PreferenceCandidate(
            bassDb = manualBassDb ?: aggregate.candidate.bassDb,
            trebleDb = manualTrebleDb ?: aggregate.candidate.trebleDb,
        ).clamped()

    /** What to call the headphone on screen; same idiom as [DerivedCalibration]. */
    val displayDeviceName: String
        get() = deviceName?.takeIf { it.isNotBlank() } ?: "this headphone"

    /** The base curve plus the shelf, per ear, clamped into the EQ's own range. */
    fun gainsDb(ear: Ear): List<Float> {
        val base = when (ear) {
            Ear.LEFT -> baseLeftDb
            Ear.RIGHT -> baseRightDb
        }
        val shelf = PreferenceShelf.gains(candidate, layout)
        return List(layout.bandCount) { band ->
            (base[band] + shelf[band]).coerceIn(EqBands.MIN_GAIN_DB, EqBands.MAX_GAIN_DB)
        }
    }

    /**
     * This profile written into the live EQ.
     *
     * Everything about *how* the EQ runs — the master switch's siblings, the
     * limiter, automatic headroom, loudness restoration, the volume tilt — is
     * taken from [current] and left alone. A preset decides the curve; it has no
     * business deciding whether the limiter is on. Only [EqSettings.enabled] is
     * forced, because applying a preset that cannot be heard is not applying it.
     *
     * The tilt layer is zeroed rather than carried: it is derived from the media
     * volume on the way to the effect, and the size has to match the layout this
     * profile brings with it.
     */
    fun toEqSettings(current: EqSettings): EqSettings =
        current.withLayout(layout).copy(
            enabled = true,
            leftGainsDb = gainsDb(Ear.LEFT),
            rightGainsDb = gainsDb(Ear.RIGHT),
            tiltGainsDb = List(layout.bandCount) { 0f },
        ).sanitized()

    /** Adds a run, applying the pool's replacement and trim rules. */
    fun withRun(run: PreferenceRun, nowMillis: Long): PreferenceProfile =
        copy(runs = PreferencePool.add(runs, run), updatedAtMillis = nowMillis)

    fun withoutRun(id: String, nowMillis: Long): PreferenceProfile =
        copy(runs = PreferencePool.remove(runs, id), updatedAtMillis = nowMillis)

    companion object {
        /** What the preset is called wherever it is named. */
        const val NAME: String = "Personal preference"

        /**
         * Prefix keeping a preference preset id out of every other namespace,
         * the same shape [DerivedCalibration.ID_PREFIX] uses and for the same
         * reason: the id has to be a stable function of the device key rather
         * than anything generated, because it is written into stored state.
         */
        const val ID_PREFIX: String = "preference_"

        fun presetIdFor(deviceKey: String): String = ID_PREFIX + deviceKey

        fun isPreferenceId(id: String?): Boolean = id != null && id.startsWith(ID_PREFIX)
    }
}

/**
 * The two curves one comparison plays, built so that the only difference between
 * them is the one being asked about.
 *
 * ## The confound this exists to remove
 *
 * Louder sounds better. Not as an opinion — as a robust, decades-old result in
 * every listening-test methodology text there is. A +9 dB bass shelf raises the
 * level of pink noise by about 4.4 dB, and a listener asked to compare it
 * against flat will pick it for that reason alone and sincerely report that it
 * sounded fuller. An A/B preference test that does not level-match is a level
 * test with the labels changed.
 *
 * So every candidate carries a pre-gain of exactly minus its own
 * [PreferenceShelf.levelOffsetDb], and the two sides of a comparison therefore
 * play at the same computed loudness whatever their shape.
 *
 * ## The three switches this takes over, and why
 *
 *  * **Automatic headroom is off.** It would otherwise do its own, different
 *    level correction — by peak band gain rather than by mean energy — and that
 *    correction differs between the two candidates, which is precisely the
 *    difference being controlled for. In its place goes one fixed ceiling
 *    ([PreferenceShelf.ceilingDb] plus the base curve's own peak) computed for
 *    the *whole parameter space*, so it is identical for every candidate and
 *    cancels out of every comparison. The music plays a little quieter for the
 *    duration; that is the price of the match.
 *  * **Loudness restoration is off.** In that mode boosts move to the multiband
 *    compressor and give their gain back as the signal gets louder, so the level
 *    match would hold for quiet passages and quietly fail for loud ones.
 *  * **The volume-aware tilt is off.** It is a correction for the listening
 *    level, the test runs at one level, it would be identical on both sides
 *    anyway — and it would cost up to 12 dB of further headroom, taken out of
 *    the music, to buy nothing.
 *
 * The limiter stays exactly as the listener had it: it is a safety net, not part
 * of the comparison.
 */
object PreferenceAudition {

    /**
     * The live EQ for one candidate, riding on top of [baseLeftDb]/[baseRightDb].
     *
     * @param current the settings whose switches are preserved
     */
    fun settingsFor(
        current: EqSettings,
        candidate: PreferenceCandidate,
        layout: EqBandLayout,
        baseLeftDb: List<Float>,
        baseRightDb: List<Float>,
    ): EqSettings {
        val shelf = PreferenceShelf.gains(candidate, layout)
        val left = List(layout.bandCount) { band ->
            (baseLeftDb[band] + shelf[band]).coerceIn(EqBands.MIN_GAIN_DB, EqBands.MAX_GAIN_DB)
        }
        val right = List(layout.bandCount) { band ->
            (baseRightDb[band] + shelf[band]).coerceIn(EqBands.MIN_GAIN_DB, EqBands.MAX_GAIN_DB)
        }
        return EqSettings(
            enabled = true,
            layout = layout,
            leftGainsDb = left,
            rightGainsDb = right,
            preGainDb = preGainFor(candidate, layout, baseLeftDb, baseRightDb),
            limiterEnabled = current.limiterEnabled,
            autoHeadroom = false,
            loudnessRestoration = false,
            volumeAwareTilt = false,
        ).sanitized()
    }

    /**
     * The pre-gain one candidate auditions at: a fixed ceiling for the whole
     * test, minus this candidate's own loudness offset.
     *
     * Public because it is the number the loudness-matching test pins, and
     * because a number this important should be checkable without reconstructing
     * an [EqSettings] around it.
     */
    fun preGainFor(
        candidate: PreferenceCandidate,
        layout: EqBandLayout,
        baseLeftDb: List<Float>,
        baseRightDb: List<Float>,
    ): Float {
        val basePeak = (baseLeftDb + baseRightDb).maxOrNull()?.coerceAtLeast(0f) ?: 0f
        val ceiling = basePeak + PreferenceShelf.ceilingDb(layout)
        return -ceiling - PreferenceShelf.levelOffsetDb(candidate, layout)
    }
}
