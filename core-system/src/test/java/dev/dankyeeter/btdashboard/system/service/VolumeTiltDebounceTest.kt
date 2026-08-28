package dev.dankyeeter.btdashboard.system.service

import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.audio.eq.withVolumeTilt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a volume ramp costs the audio effect.
 *
 * One `update` writes four parameters per band per channel plus the limiter and
 * the enable — 124 binder calls into audioserver on the 31-band layout — inside
 * the lock that serialises every attach and update in the app. Holding
 * volume-down walks the slider through a dozen steps in well under a second,
 * and every step whose quantised tilt differed used to produce a full write of
 * a curve that the next step was about to replace.
 *
 * These tests run on virtual time: nothing here waits 150 ms in real life.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VolumeTiltDebounceTest {

    private val tilting = EqSettings.FLAT.copy(enabled = true, volumeAwareTilt = true)

    @Test
    fun `a volume ramp coalesces into a single update`() = runTest {
        val settings = MutableStateFlow(tilting)
        val volume = MutableStateFlow(START_FRACTION)
        val seen = mutableListOf<EqSettings>()
        val job = launch { volumeTiltUpdates(settings, volume).collect { seen += it } }
        runCurrent()

        // A held volume key, faster than the debounce window.
        RAMP.forEach { fraction ->
            volume.value = fraction
            advanceTimeBy(RAMP_STEP_MS)
        }
        assertTrue(
            "nothing may be written while the volume is still moving, was $seen",
            seen.isEmpty(),
        )

        advanceTimeBy(VOLUME_TILT_DEBOUNCE_MS + 1)

        assertEquals("the ramp must cost exactly one write", 1, seen.size)
        assertEquals(
            "and it must be the curve for the volume the user stopped at",
            tilting.withVolumeTilt(RAMP.last()),
            seen.single(),
        )
        job.cancel()
    }

    @Test
    fun `the update lands once the volume has been still for the debounce window`() = runTest {
        val settings = MutableStateFlow(tilting)
        val volume = MutableStateFlow(START_FRACTION)
        val seen = mutableListOf<EqSettings>()
        val job = launch { volumeTiltUpdates(settings, volume).collect { seen += it } }
        runCurrent()

        volume.value = 0.20f
        advanceTimeBy(VOLUME_TILT_DEBOUNCE_MS - 1)
        assertTrue("too early: the window has not elapsed", seen.isEmpty())

        advanceTimeBy(2)
        assertEquals(1, seen.size)
        assertEquals(tilting.withVolumeTilt(0.20f), seen.single())
        job.cancel()
    }

    /**
     * The debounce must not have cost the quantisation its effect: two volume
     * steps whose curves round to the same gains are still one update, even when
     * they are far apart in time.
     */
    @Test
    fun `two volume steps with the same quantised curve are one update`() = runTest {
        val settings = MutableStateFlow(tilting)
        // Both above the reference fraction, so both curves are flat - the
        // "turned it up past comfortable" case, where the tilt does nothing.
        val volume = MutableStateFlow(0.90f)
        val seen = mutableListOf<EqSettings>()
        val job = launch { volumeTiltUpdates(settings, volume).collect { seen += it } }
        runCurrent()
        advanceTimeBy(VOLUME_TILT_DEBOUNCE_MS + 1)
        assertEquals(1, seen.size)

        volume.value = 0.95f
        advanceTimeBy(VOLUME_TILT_DEBOUNCE_MS * 2)

        assertEquals("an identical curve must not be written again", 1, seen.size)
        job.cancel()
    }

    /**
     * Off is off. A settings snapshot with the tilt disabled produces nothing,
     * and must not resurrect the last curve either.
     */
    @Test
    fun `nothing is written while the tilt is switched off`() = runTest {
        val settings = MutableStateFlow(EqSettings.FLAT.copy(enabled = true))
        val volume = MutableStateFlow(0.20f)
        val seen = mutableListOf<EqSettings>()
        val job = launch { volumeTiltUpdates(settings, volume).collect { seen += it } }
        runCurrent()

        volume.value = 0.10f
        advanceTimeBy(VOLUME_TILT_DEBOUNCE_MS * 2)

        assertEquals(0, seen.size)
        job.cancel()
    }

    private companion object {
        /** Above the reference fraction, so the ramp starts from a flat curve. */
        const val START_FRACTION = 0.70f

        /**
         * Volume steps whose ISO 226 curves are genuinely different, so the ramp
         * is testing the debounce rather than the quantisation.
         */
        val RAMP = listOf(0.50f, 0.40f, 0.30f, 0.20f)

        /** Faster than a held volume key, and far inside the debounce window. */
        const val RAMP_STEP_MS = 20L
    }
}
