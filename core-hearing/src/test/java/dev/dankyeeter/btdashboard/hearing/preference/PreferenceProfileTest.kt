package dev.dankyeeter.btdashboard.hearing.preference

import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import dev.dankyeeter.btdashboard.audio.eq.EqBands
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the stored profile applies, what it lets a hand adjustment override, and
 * the loudness match the audition rests on.
 */
class PreferenceProfileTest {

    private val layout = EqBandLayout.OCTAVE_10
    private val flat = List(layout.bandCount) { 0f }

    private fun run(bass: Float, treble: Float = 0f, consistency: Double = 1.0, at: Long = 0) =
        PreferenceRun(
            id = "run-$at-$bass",
            label = "song-$at",
            labelSource = PreferenceLabelSource.TRACK,
            createdAtMillis = at,
            candidate = PreferenceCandidate(bass, treble),
            consistency = consistency,
        )

    private fun profile(
        runs: List<PreferenceRun> = listOf(run(4f, -2f)),
        base: List<Float> = flat,
        manualBass: Float? = null,
        manualTreble: Float? = null,
    ) = PreferenceProfile(
        deviceKey = "abc123",
        deviceName = "Focal Bathys",
        runs = runs,
        layout = layout,
        baseLeftDb = base,
        baseRightDb = base,
        manualBassDb = manualBass,
        manualTrebleDb = manualTreble,
    )

    // ---- identity ------------------------------------------------------------

    @Test
    fun `the preset id is a stable function of the device`() {
        assertEquals("preference_abc123", PreferenceProfile.presetIdFor("abc123"))
        assertTrue(PreferenceProfile.isPreferenceId("preference_abc123"))
        assertFalse(PreferenceProfile.isPreferenceId("derived_abc123"))
        assertFalse(PreferenceProfile.isPreferenceId(null))
    }

    @Test
    fun `a device with no name still has something to call it`() {
        assertEquals("this headphone", profile().copy(deviceName = null).displayDeviceName)
        assertEquals("this headphone", profile().copy(deviceName = "  ").displayDeviceName)
        assertEquals("Focal Bathys", profile().displayDeviceName)
    }

    // ---- rendering -----------------------------------------------------------

    @Test
    fun `the applied curve is the base plus the shelf`() {
        val base = List(layout.bandCount) { 2f }
        val subject = profile(base = base)
        val shelf = PreferenceShelf.gains(subject.candidate, layout)
        subject.gainsDb(Ear.LEFT).forEachIndexed { index, gain ->
            assertEquals((base[index] + shelf[index]).toDouble(), gain.toDouble(), 1e-5)
        }
    }

    @Test
    fun `the two ears keep their own base curves`() {
        val subject = profile().copy(
            baseLeftDb = List(layout.bandCount) { 3f },
            baseRightDb = List(layout.bandCount) { -1f },
        )
        assertNotEquals(subject.gainsDb(Ear.LEFT), subject.gainsDb(Ear.RIGHT))
        // Taste is not per ear, so the difference between them is exactly the
        // difference between their base curves and nothing else.
        subject.gainsDb(Ear.LEFT).zip(subject.gainsDb(Ear.RIGHT)).forEach { (left, right) ->
            assertEquals(4.0, (left - right).toDouble(), 1e-5)
        }
    }

    @Test
    fun `a big base plus a big shelf still stays inside the EQ range`() {
        val subject = profile(
            runs = listOf(run(9f, 6f)),
            base = List(layout.bandCount) { EqBands.MAX_GAIN_DB },
        )
        assertTrue(subject.gainsDb(Ear.LEFT).all { it <= EqBands.MAX_GAIN_DB })
        assertTrue(subject.gainsDb(Ear.RIGHT).all { it >= EqBands.MIN_GAIN_DB })
    }

    // ---- hand adjustment -----------------------------------------------------

    @Test
    fun `an untouched profile follows the pool`() {
        val subject = profile(runs = listOf(run(2f), run(4f), run(6f)))
        assertFalse(subject.handAdjusted)
        assertEquals(4f, subject.candidate.bassDb)
    }

    @Test
    fun `a hand adjustment overrides one axis and leaves the other alone`() {
        val subject = profile(runs = listOf(run(4f, -2f)), manualBass = 7f)
        assertTrue(subject.handAdjusted)
        assertEquals(7f, subject.candidate.bassDb)
        assertEquals(-2f, subject.candidate.trebleDb)
    }

    @Test
    fun `a hand adjustment is still clamped into the parameter space`() {
        val subject = profile(manualBass = 40f, manualTreble = -40f)
        assertEquals(PreferenceShelf.MAX_BASS_DB, subject.candidate.bassDb)
        assertEquals(PreferenceShelf.MIN_TREBLE_DB, subject.candidate.trebleDb)
    }

    @Test
    fun `adding a song does not silently discard a hand adjustment`() {
        // The record keeps it; the question of whether to keep it is the UI's,
        // and the UI can only ask because the flag survives the write.
        val subject = profile(manualBass = 7f).withRun(run(1f, at = 5), nowMillis = 5)
        assertTrue(subject.handAdjusted)
        assertEquals(7f, subject.candidate.bassDb)
    }

    // ---- applying ------------------------------------------------------------

    @Test
    fun `applying keeps the listener's own switches and only forces the curve on`() {
        val current = EqSettings(
            enabled = false,
            layout = EqBandLayout.THIRD_OCTAVE_31,
            limiterEnabled = false,
            autoHeadroom = true,
            loudnessRestoration = true,
            volumeAwareTilt = true,
        )
        val applied = profile().toEqSettings(current)

        assertTrue(applied.enabled)
        assertFalse(applied.limiterEnabled)
        assertTrue(applied.autoHeadroom)
        assertTrue(applied.loudnessRestoration)
        assertTrue(applied.volumeAwareTilt)
        // The profile brings its own grid, and the tilt layer is left for the
        // pipeline to derive rather than carried over at the wrong size.
        assertEquals(layout, applied.layout)
        assertEquals(layout.bandCount, applied.tiltGainsDb.size)
        assertTrue(applied.tiltGainsDb.all { it == 0f })
    }

    @Test
    fun `applying a neutral profile leaves the base curve alone`() {
        val base = List(layout.bandCount) { 2f }
        val applied = profile(runs = listOf(run(0f, 0f)), base = base)
            .toEqSettings(EqSettings(layout = layout))
        applied.leftGainsDb.forEach { assertEquals(2.0, it.toDouble(), 1e-5) }
    }

    // ---- the loudness match --------------------------------------------------

    /**
     * The single most important behaviour in the feature: a bass-boosted
     * candidate must audition *quieter*, by its own pink-noise offset, or the
     * comparison measures level instead of taste.
     */
    @Test
    fun `a bass boost auditions quieter than flat, by its own offset`() {
        val flatGain = PreferenceAudition.preGainFor(PreferenceCandidate.NEUTRAL, layout, flat, flat)
        val boosted = PreferenceAudition.preGainFor(PreferenceCandidate(9f, 0f), layout, flat, flat)
        assertTrue("the boost should audition quieter", boosted < flatGain)
        // Exactly the hand-computed 4.36 dB from PreferenceShelfTest.
        assertEquals(4.356, (flatGain - boosted).toDouble(), 0.05)
    }

    @Test
    fun `a cut auditions louder, by the same rule`() {
        val flatGain = PreferenceAudition.preGainFor(PreferenceCandidate.NEUTRAL, layout, flat, flat)
        val cut = PreferenceAudition.preGainFor(PreferenceCandidate(-6f, 0f), layout, flat, flat)
        assertTrue(cut > flatGain)
    }

    @Test
    fun `the headroom part of the pre-gain is the same for every candidate`() {
        // Two candidates' pre-gains may differ only by their loudness offsets.
        // If the headroom moved with the candidate it would be a second, hidden
        // level difference inside every comparison.
        val candidates = listOf(
            PreferenceCandidate(9f, 6f),
            PreferenceCandidate(-6f, -6f),
            PreferenceCandidate(3f, -1f),
            PreferenceCandidate.NEUTRAL,
        )
        candidates.forEach { candidate ->
            val preGain = PreferenceAudition.preGainFor(candidate, layout, flat, flat)
            val offset = PreferenceShelf.levelOffsetDb(candidate, layout)
            assertEquals(
                -PreferenceShelf.ceilingDb(layout).toDouble(),
                (preGain + offset).toDouble(),
                1e-4,
            )
        }
    }

    @Test
    fun `the audition takes over the three switches that would break the match`() {
        val current = EqSettings(
            layout = layout,
            limiterEnabled = true,
            autoHeadroom = true,
            loudnessRestoration = true,
            volumeAwareTilt = true,
        )
        val audition = PreferenceAudition.settingsFor(
            current,
            PreferenceCandidate(6f, -3f),
            layout,
            flat,
            flat,
        )
        assertFalse("automatic headroom would level-match by peak", audition.autoHeadroom)
        assertFalse("compressed boosts break the match at high level", audition.loudnessRestoration)
        assertFalse("the tilt would cost headroom for nothing", audition.volumeAwareTilt)
        // The safety net is not part of the comparison and is left as it was.
        assertTrue(audition.limiterEnabled)
        assertTrue(audition.enabled)
    }

    @Test
    fun `the audition rides on top of the listener's own curve`() {
        val base = List(layout.bandCount) { 3f }
        val audition = PreferenceAudition.settingsFor(
            EqSettings(layout = layout),
            PreferenceCandidate(6f, 0f),
            layout,
            base,
            base,
        )
        val shelf = PreferenceShelf.gains(PreferenceCandidate(6f, 0f), layout)
        audition.leftGainsDb.forEachIndexed { index, gain ->
            assertEquals(
                (base[index] + shelf[index]).coerceAtMost(EqBands.MAX_GAIN_DB).toDouble(),
                gain.toDouble(),
                1e-5,
            )
        }
    }

    @Test
    fun `a louder base buys more headroom, equally for both sides`() {
        val quiet = PreferenceAudition.preGainFor(PreferenceCandidate(4f, 0f), layout, flat, flat)
        val loudBase = List(layout.bandCount) { 6f }
        val loud = PreferenceAudition.preGainFor(PreferenceCandidate(4f, 0f), layout, loudBase, loudBase)
        assertEquals(-6.0, (loud - quiet).toDouble(), 1e-4)
    }

    @Test
    fun `the audition never leaves an unusable pre-gain behind`() {
        val hotBase = List(layout.bandCount) { EqBands.MAX_GAIN_DB }
        val audition = PreferenceAudition.settingsFor(
            EqSettings(layout = layout),
            PreferenceCandidate(9f, 6f),
            layout,
            hotBase,
            hotBase,
        )
        assertTrue(audition.preGainDb in -24f..0f)
    }
}
