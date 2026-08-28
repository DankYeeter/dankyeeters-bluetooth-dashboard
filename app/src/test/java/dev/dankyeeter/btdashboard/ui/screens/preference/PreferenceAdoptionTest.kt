package dev.dankyeeter.btdashboard.ui.screens.preference

import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceCandidate
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceLabelSource
import dev.dankyeeter.btdashboard.hearing.preference.PreferencePool
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceProfile
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the preference screens are driven by, and what "save" actually puts
 * into the EQ.
 *
 * These live in the UI state rather than in a composable for the reason
 * `DerivedPresetAdoptionTest` gives about the compensation state: a rule that
 * only exists as an `if` inside a `@Composable` can only be checked by rendering
 * it, and rendering is the least reliable way to find out what a rule says.
 */
class PreferenceAdoptionTest {

    private val layout = EqBandLayout.OCTAVE_10
    private val flat = List(layout.bandCount) { 0f }

    private fun run(id: String, bass: Float, treble: Float = 0f) = PreferenceRun(
        id = id,
        label = id,
        labelSource = PreferenceLabelSource.MANUAL,
        createdAtMillis = 0L,
        candidate = PreferenceCandidate(bass, treble),
        consistency = 1.0,
    )

    private fun profile(
        runs: List<PreferenceRun> = listOf(run("a", 4f), run("b", 4f), run("c", 5f)),
        manualBass: Float? = null,
        key: String = "abc123",
    ) = PreferenceProfile(
        deviceKey = key,
        deviceName = "Focal Bathys",
        runs = runs,
        layout = layout,
        baseLeftDb = flat,
        baseRightDb = flat,
        manualBassDb = manualBass,
    )

    // ---- when a test can start ----------------------------------------------

    @Test
    fun `a test needs a headphone to belong to`() {
        assertFalse(PreferenceUiState().canStart)
        assertTrue(PreferenceUiState(deviceKey = "abc123").canStart)
    }

    // ---- the device binding -------------------------------------------------

    @Test
    fun `the screens read the connected headphone's profile, never another one`() {
        val mine = profile(key = "abc123")
        val theirs = profile(key = "zzz")
        val state = PreferenceUiState(
            deviceKey = "abc123",
            stored = mine,
            otherProfiles = listOf(theirs),
        )
        assertEquals(mine, state.active)
        assertEquals(4f, state.candidate.bassDb)
    }

    @Test
    fun `the working copy wins over what is stored while a test is open`() {
        val stored = profile(runs = listOf(run("a", 2f)))
        val draft = profile(runs = listOf(run("a", 2f), run("b", 6f), run("c", 6f)))
        val state = PreferenceUiState(deviceKey = "abc123", stored = stored, draft = draft)
        assertEquals(draft, state.active)
        assertEquals(6f, state.candidate.bassDb)
    }

    // ---- the dirty rule ------------------------------------------------------

    @Test
    fun `an empty draft is not unsaved work`() {
        val empty = profile(runs = emptyList())
        assertFalse(PreferenceUiState(draft = empty).dirty)
    }

    @Test
    fun `a draft with a song in it is unsaved work`() {
        assertTrue(PreferenceUiState(draft = profile(runs = listOf(run("a", 4f)))).dirty)
    }

    @Test
    fun `a draft equal to what is stored is not unsaved work`() {
        val stored = profile()
        assertFalse(PreferenceUiState(stored = stored, draft = stored).dirty)
    }

    @Test
    fun `a hand adjustment on its own counts as unsaved work`() {
        val stored = profile(runs = emptyList())
        val adjusted = stored.copy(manualBassDb = 5f)
        assertTrue(PreferenceUiState(stored = stored, draft = adjusted).dirty)
    }

    @Test
    fun `the pool reports when it is full`() {
        val full = profile(runs = (1..PreferencePool.MAX_RUNS).map { run("r$it", 4f) })
        assertTrue(PreferenceUiState(draft = full).poolFull)
        assertFalse(PreferenceUiState(draft = profile()).poolFull)
    }

    @Test
    fun `progress follows the comparison in hand`() {
        assertEquals(0f, PreferenceUiState().progress)
    }

    // ---- what save applies ---------------------------------------------------

    /**
     * Saving is adoption: the curve goes into the EQ in the same act that stores
     * it. A result that has to be gone and applied from somewhere else is a
     * result most people never hear — the same argument the derived calibration
     * makes for being offered as a preset the moment it exists.
     */
    @Test
    fun `saving applies the pool's own answer on top of the base curve`() {
        val subject = profile()
        val live = EqSettings(layout = layout, enabled = false)
        val applied = subject.toEqSettings(live)

        assertTrue("applying a preset that cannot be heard is not applying it", applied.enabled)
        assertEquals(subject.gainsDb(Ear.LEFT), applied.leftGainsDb)
        assertEquals(subject.gainsDb(Ear.RIGHT), applied.rightGainsDb)
    }

    @Test
    fun `a hand adjustment is what gets applied, not the pool it overrode`() {
        val subject = profile(manualBass = 8f)
        val applied = subject.toEqSettings(EqSettings(layout = layout))
        val expected = profile().copy(manualBassDb = 8f).gainsDb(Ear.LEFT)

        assertEquals(expected, applied.leftGainsDb)
        // And the pool underneath is untouched, so "back to what the songs said"
        // has something to go back to.
        assertEquals(4f, subject.aggregate.candidate.bassDb)
    }

    @Test
    fun `removing a song changes what would be applied`() {
        val subject = profile(runs = listOf(run("a", 2f), run("b", 2f), run("c", 9f)))
        assertEquals(2f, subject.candidate.bassDb)
        val without = subject.withoutRun("a", nowMillis = 1L)
        assertEquals(5.5f, without.candidate.bassDb)
    }

    @Test
    fun `re-running a song replaces its answer rather than adding a vote`() {
        val subject = profile(runs = listOf(run("a", 2f), run("b", 2f)))
        val again = subject.withRun(run("a-again", 9f).copy(label = "a"), nowMillis = 2L)
        assertEquals(2, again.runs.size)
        assertEquals(setOf("a", "b"), again.runs.map { it.label }.toSet())
    }
}
