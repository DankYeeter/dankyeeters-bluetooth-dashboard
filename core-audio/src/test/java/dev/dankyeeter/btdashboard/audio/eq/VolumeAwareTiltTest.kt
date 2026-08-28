package dev.dankyeeter.btdashboard.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The product decisions on top of [Iso226]: what a volume fraction is assumed
 * to mean, how far the correction is allowed to go, and how it composes with
 * everything else the EQ is already doing.
 *
 * The claims tested here are the ones the screen makes to the user — never
 * cuts, never touches the mids, bounded, and off means bit-identical to before.
 */
class VolumeAwareTiltTest {

    private fun index(hz: Float) = Iso226.FREQUENCIES_HZ.indexOf(hz)

    // ---- the volume assumption -----------------------------------------------

    @Test
    fun `the reference fraction maps to the reference loudness by construction`() {
        assertEquals(
            VolumeAwareTilt.REFERENCE_PHON.toDouble(),
            VolumeAwareTilt.phonFor(VolumeAwareTilt.REFERENCE_FRACTION).toDouble(),
            1e-3,
        )
    }

    @Test
    fun `the assumed loudness never falls with the volume slider`() {
        var previous = VolumeAwareTilt.phonFor(0f)
        var fraction = 0f
        while (fraction <= 1f) {
            val phon = VolumeAwareTilt.phonFor(fraction)
            assertTrue("phon fell at fraction $fraction: $previous -> $phon", phon >= previous)
            previous = phon
            fraction += 0.01f
        }
    }

    @Test
    fun `the mapping stops at the floor and at the standard's ceiling`() {
        assertEquals(VolumeAwareTilt.FLOOR_PHON, VolumeAwareTilt.phonFor(0f))
        assertEquals(VolumeAwareTilt.FLOOR_PHON, VolumeAwareTilt.phonFor(0.02f))
        assertTrue(VolumeAwareTilt.phonFor(1f) <= Iso226.MAX_PHON)
    }

    @Test
    fun `the platform volume curve is followed between its published points`() {
        // The four points themselves, and one interpolated value in each span.
        assertEquals(-58.0, VolumeAwareTilt.attenuationDbAt(0.01f).toDouble(), 1e-3)
        assertEquals(-40.0, VolumeAwareTilt.attenuationDbAt(0.20f).toDouble(), 1e-3)
        assertEquals(-17.0, VolumeAwareTilt.attenuationDbAt(0.60f).toDouble(), 1e-3)
        assertEquals(0.0, VolumeAwareTilt.attenuationDbAt(1.00f).toDouble(), 1e-3)
        assertEquals(-28.5, VolumeAwareTilt.attenuationDbAt(0.40f).toDouble(), 1e-3)
        // Below the first point the curve is held, not continued downwards.
        assertEquals(-58.0, VolumeAwareTilt.attenuationDbAt(0f).toDouble(), 1e-3)
    }

    // ---- the curve -----------------------------------------------------------

    @Test
    fun `at the reference volume the correction is nothing at all`() {
        EqBandLayout.entries.forEach { layout ->
            val gains = VolumeAwareTilt.gainsFor(VolumeAwareTilt.REFERENCE_FRACTION, layout)
            assertTrue("${layout.id}: $gains", VolumeAwareTilt.isFlat(gains))
        }
    }

    @Test
    fun `turning the volume up never produces a cut`() {
        listOf(0.7f, 0.8f, 0.9f, 1.0f).forEach { fraction ->
            EqBandLayout.entries.forEach { layout ->
                val gains = VolumeAwareTilt.gainsFor(fraction, layout)
                assertTrue(
                    "${layout.id} at fraction $fraction must stay flat: $gains",
                    VolumeAwareTilt.isFlat(gains),
                )
            }
        }
    }

    @Test
    fun `no band is ever cut and none exceeds the cap`() {
        var fraction = 0f
        while (fraction <= 1f) {
            EqBandLayout.entries.forEach { layout ->
                VolumeAwareTilt.gainsFor(fraction, layout).forEachIndexed { i, gain ->
                    assertTrue(
                        "${layout.id} band $i at fraction $fraction: $gain",
                        gain >= 0f && gain <= VolumeAwareTilt.MAX_TILT_DB,
                    )
                }
            }
            fraction += 0.05f
        }
    }

    /**
     * The mids are the anchor, and the readout claims the balance moves while
     * the level does not. At 1 kHz the correction is exactly zero; across the
     * 500 Hz–2 kHz region it stays inside a couple of dB even at the quietest
     * setting, where the bass is at the 12 dB cap.
     */
    @Test
    fun `the midrange stays put while the bass is at the cap`() {
        val curve = VolumeAwareTilt.curveFor(VolumeAwareTilt.FLOOR_PHON)
        assertEquals(0.0, curve[index(1000f)].toDouble(), 1e-4)
        listOf(500f, 630f, 800f, 1000f, 1250f, 1600f, 2000f).forEach { hz ->
            assertTrue(
                "$hz Hz moved by ${curve[index(hz)]} dB",
                curve[index(hz)] <= 2.5f,
            )
        }
        assertEquals(VolumeAwareTilt.MAX_TILT_DB, curve[index(63f)])
    }

    @Test
    fun `quieter listening asks for at least as much bass, never less`() {
        val fractions = listOf(0.67f, 0.6f, 0.5f, 0.4f, 0.3f, 0.2f, 0.1f)
        val layout = EqBandLayout.THIRD_OCTAVE_31
        val band = layout.centersHz.indexOf(125f)
        val values = fractions.map { VolumeAwareTilt.gainsFor(it, layout)[band] }
        values.zipWithNext().forEach { (louder, quieter) ->
            assertTrue("125 Hz must not fall as the volume drops: $values", quieter >= louder)
        }
        // And it really moves: this is not a list of zeros.
        assertTrue("$values", values.last() > values.first() + 5f)
    }

    @Test
    fun `gains are quantised so a held volume key does not rewrite every band`() {
        EqBandLayout.entries.forEach { layout ->
            VolumeAwareTilt.gainsFor(0.23f, layout).forEach { gain ->
                val steps = gain / 0.25f
                assertEquals("$gain is not a quarter-dB step", steps, Math.round(steps).toFloat())
            }
        }
    }

    @Test
    fun `the readout reports the largest correction in each region`() {
        val layout = EqBandLayout.OCTAVE_10
        val gains = VolumeAwareTilt.gainsFor(0.15f, layout)
        val summary = VolumeAwareTilt.summarise(gains, layout)
        assertEquals(gains[layout.centersHz.indexOf(31.5f)], summary.bassDb)
        assertTrue("treble: ${summary.trebleDb}", summary.trebleDb > 0f)
        assertTrue(!summary.isFlat)

        val flat = VolumeAwareTilt.summarise(List(layout.bandCount) { 0f }, layout)
        assertTrue(flat.isFlat)
    }

    // ---- composition into the pipeline ---------------------------------------

    private fun settings(
        layout: EqBandLayout = EqBandLayout.OCTAVE_10,
        gains: List<Float> = List(layout.bandCount) { 0f },
        tiltOn: Boolean = true,
        loudnessRestoration: Boolean = false,
        autoHeadroom: Boolean = true,
    ) = EqSettings(
        enabled = true,
        layout = layout,
        leftGainsDb = gains,
        rightGainsDb = gains,
        autoHeadroom = autoHeadroom,
        loudnessRestoration = loudnessRestoration,
        volumeAwareTilt = tiltOn,
    )

    @Test
    fun `switching the tilt off is identical to never having had one`() {
        val quiet = settings(tiltOn = false).withVolumeTilt(0.1f)
        assertTrue(VolumeAwareTilt.isFlat(quiet.tiltGainsDb))
        Ear.entries.forEach { ear ->
            assertEquals(quiet.gainsFor(ear), quiet.staticGainsFor(ear))
        }
        assertEquals(0f, quiet.sanitized().preGainDb)
    }

    @Test
    fun `a stored tilt is ignored while the switch is off`() {
        // Belt and braces: the switch, not the list, is what decides.
        val stale = settings(tiltOn = true).withVolumeTilt(0.1f).copy(volumeAwareTilt = false)
        assertTrue(stale.tiltGainsDb.any { it > 0f })
        assertTrue(VolumeAwareTilt.isFlat(stale.activeTiltDb))
        assertEquals(stale.leftGainsDb, stale.staticGainsFor(Ear.LEFT))
    }

    @Test
    fun `the tilt adds to the user's own curve rather than replacing it`() {
        val user = List(EqBandLayout.OCTAVE_10.bandCount) { -2f }
        val quiet = settings(gains = user).withVolumeTilt(0.1f)
        val static = quiet.staticGainsFor(Ear.LEFT)
        static.forEachIndexed { i, value ->
            assertEquals("band $i", (user[i] + quiet.tiltGainsDb[i]), value)
        }
    }

    @Test
    fun `the sum stays inside the gain range the sliders promise`() {
        val user = List(EqBandLayout.OCTAVE_10.bandCount) { EqBands.MAX_GAIN_DB }
        val quiet = settings(gains = user).withVolumeTilt(0.05f)
        Ear.entries.forEach { ear ->
            quiet.staticGainsFor(ear).forEach { assertTrue("$it", it <= EqBands.MAX_GAIN_DB) }
        }
    }

    /**
     * The headroom rule is the one that decides whether a boost can clip, so a
     * boost the model invented has to be charged for exactly like one the user
     * dragged in.
     */
    @Test
    fun `the tilt buys its own headroom`() {
        val quiet = settings().withVolumeTilt(0.1f).sanitized()
        val peak = quiet.staticGainsFor(Ear.LEFT).max()
        assertTrue("expected a real boost, got $peak", peak > 0f)
        assertEquals(-peak, quiet.preGainDb)
    }

    /**
     * And in loudness-restoration mode too, where the user's own boosts have
     * moved to the compressor and cost nothing. The tilt does not move — it is a
     * correction for the volume setting, not for the signal level — so it is
     * still the static path's peak and still has to be paid for.
     */
    @Test
    fun `the tilt costs headroom even in loudness-restoration mode`() {
        val boosted = List(EqBandLayout.OCTAVE_10.bandCount) { 9f }
        val restoring = settings(gains = boosted, loudnessRestoration = true)
            .withVolumeTilt(0.1f)
            .sanitized()

        // The user's boosts are in the compressor, and the cuts (none here) plus
        // the tilt are static.
        assertEquals(boosted, restoring.compressorGainsFor(Ear.LEFT))
        assertEquals(restoring.tiltGainsDb, restoring.staticGainsFor(Ear.LEFT))
        assertEquals(-restoring.tiltGainsDb.max(), restoring.preGainDb)

        // Without the tilt the same settings buy no headroom at all — that is
        // the behaviour this must not have broken.
        val untilted = settings(gains = boosted, loudnessRestoration = true, tiltOn = false).sanitized()
        assertEquals(0f, untilted.preGainDb)
    }

    @Test
    fun `with automatic headroom off the tilt buys none`() {
        val quiet = settings(autoHeadroom = false).withVolumeTilt(0.1f).sanitized()
        assertTrue(quiet.tiltGainsDb.any { it > 0f })
        assertEquals(0f, quiet.preGainDb)
    }

    @Test
    fun `changing the band layout keeps the tilt valid`() {
        EqBandLayout.entries.forEach { from ->
            EqBandLayout.entries.forEach { to ->
                val moved = settings(layout = from).withVolumeTilt(0.1f).withLayout(to)
                assertEquals("${from.id} -> ${to.id}", to.bandCount, moved.tiltGainsDb.size)
                // Re-deriving on the new grid is what the owner does next; it
                // must not disagree about the length either.
                assertEquals(to.bandCount, moved.withVolumeTilt(0.1f).tiltGainsDb.size)
            }
        }
    }

    @Test
    fun `a volume change re-derives the curve`() {
        val loud = settings().withVolumeTilt(0.67f)
        val quiet = loud.withVolumeTilt(0.15f)
        assertNotEquals(loud.tiltGainsDb, quiet.tiltGainsDb)
        assertTrue(VolumeAwareTilt.isFlat(loud.tiltGainsDb))
        assertTrue(quiet.tiltGainsDb.any { it > 0f })
        // And back again: the layer is derived, so nothing accumulates.
        assertEquals(loud.tiltGainsDb, quiet.withVolumeTilt(0.67f).tiltGainsDb)
    }
}
