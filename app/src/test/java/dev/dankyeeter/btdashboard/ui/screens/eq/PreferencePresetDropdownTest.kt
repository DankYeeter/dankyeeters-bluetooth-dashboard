package dev.dankyeeter.btdashboard.ui.screens.eq

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceCandidate
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceLabelSource
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceProfile
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceRun
import dev.dankyeeter.btdashboard.ui.theme.BtDashboardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The preference curve as it appears in the one menu that lists curves.
 *
 * Rendered rather than reasoned about, because the two things worth pinning
 * here are both facts about the menu: that a curve measured on another
 * headphone is *shown and refused* rather than quietly dropped, and that the
 * entry for this headphone actually reaches the ViewModel. A greyed row is the
 * whole point of the device binding, and "greyed" is not a property any state
 * object has.
 *
 * The qualifier gives it a phone-sized window instead of Robolectric's default
 * 320x470 one. This section is taller than that, and on the default the preset
 * row lays out with zero height, so a tap on it lands nowhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class PreferencePresetDropdownTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val layout = EqBandLayout.OCTAVE_10
    private val flat = List(layout.bandCount) { 0f }

    private fun profile(
        key: String = "abc123",
        name: String? = "Focal Bathys",
        manualBass: Float? = null,
    ) = PreferenceProfile(
        deviceKey = key,
        deviceName = name,
        runs = listOf(run("a", 4f), run("b", 4f), run("c", 5f)),
        layout = layout,
        baseLeftDb = flat,
        baseRightDb = flat,
        manualBassDb = manualBass,
    )

    private fun run(id: String, bass: Float) = PreferenceRun(
        id = id,
        label = id,
        labelSource = PreferenceLabelSource.MANUAL,
        createdAtMillis = 0L,
        candidate = PreferenceCandidate(bass, 0f),
        consistency = 1.0,
    )

    private var selected = 0

    private fun show(state: CompensationUiState) {
        composeRule.setContent {
            BtDashboardTheme {
                CompensationSection(
                    state = state,
                    earView = EarView.LINKED,
                    currentEq = EqSettings(layout = layout),
                    onIntensityChange = {},
                    onIntensityChangeFinished = {},
                    onApply = {},
                    onSelectSource = {},
                    onSelectAdjustedReference = {},
                    onSelectPreference = { selected++ },
                    onCreateProfile = {},
                    onSaveIntoActive = {},
                    onLoadProfile = {},
                    onDeleteProfile = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun openMenu() {
        composeRule.onNodeWithText("None — flat").performClick()
        composeRule.waitForIdle()
    }

    private fun nodesWith(text: String) =
        composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes()

    @Test
    fun `the connected headphone's curve is offered as a preset`() {
        show(CompensationUiState(preferenceForDevice = profile()))
        openMenu()

        assertTrue(
            "the preference curve belongs in the preset menu",
            nodesWith("${PreferenceProfile.NAME} — from your preference test").isNotEmpty(),
        )
    }

    @Test
    fun `choosing it asks the ViewModel to apply it`() {
        show(CompensationUiState(preferenceForDevice = profile()))
        openMenu()

        composeRule.onAllNodesWithText(PreferenceProfile.NAME, substring = true)
            .onFirst()
            .performClick()

        assertEquals(1, selected)
    }

    /**
     * The device binding, on screen. A preference is a judgement made *through*
     * one headphone, so the row for another pair names what is stored and
     * refuses to apply it — the same two things the preference card says, in
     * the same words.
     */
    @Test
    fun `a curve stored for another headphone is named and greyed`() {
        show(CompensationUiState(otherPreferences = listOf(profile(key = "zzz", name = "Sony XM5"))))
        openMenu()

        assertTrue(
            "a curve the user made must not vanish from the menu",
            nodesWith("Stored for Sony XM5 — connect that pair to use or edit it.").isNotEmpty(),
        )
        composeRule.onNodeWithText(PreferenceProfile.NAME, substring = true).assertIsNotEnabled()
    }

    @Test
    fun `nothing stored anywhere leaves the menu as it was`() {
        show(CompensationUiState())
        openMenu()

        assertTrue(nodesWith(PreferenceProfile.NAME).isEmpty())
        assertTrue("the add entry is still there", nodesWith("Add new EQ").isNotEmpty())
    }

    /** The row over the menu names whatever is actually playing. */
    @Test
    fun `the active row names the preference curve while it is in force`() {
        val stored = profile()
        show(
            CompensationUiState(
                preferenceForDevice = stored,
                activeProfileId = PreferenceProfile.presetIdFor(stored.deviceKey),
            ),
        )

        assertTrue(nodesWith(PreferenceProfile.NAME).isNotEmpty())
        assertTrue("the flat placeholder is gone", nodesWith("None — flat").isEmpty())
    }

    // ---- the rules underneath ------------------------------------------------

    @Test
    fun `the active flag follows the id a preference preset is selected under`() {
        val id = PreferenceProfile.presetIdFor("abc123")

        assertTrue(CompensationUiState(activeProfileId = id).preferenceActive)
        assertFalse(CompensationUiState(activeProfileId = "my_bass_boost").preferenceActive)
        assertFalse(CompensationUiState().preferenceActive)
    }

    /**
     * The id is a function of the device key rather than anything generated,
     * so the selection survives a restart — the same rule the derived
     * calibration's preset id follows.
     */
    @Test
    fun `the preset id is derived from the headphone it belongs to`() {
        val state = CompensationUiState(preferenceForDevice = profile(key = "abc123"))

        assertEquals("preference_abc123", state.preferencePresetId)
        assertEquals(null, CompensationUiState().preferencePresetId)
    }

    /**
     * Selecting is a selection, not an edit: what goes into the EQ is
     * [PreferenceProfile.toEqSettings], the same call the preference card's own
     * Save makes, and the hand adjustment it honours is still on the profile
     * afterwards.
     */
    @Test
    fun `applying the curve leaves the hand adjustment where it was`() {
        val adjusted = profile(manualBass = 8f)
        val applied = adjusted.toEqSettings(EqSettings(layout = layout))

        assertEquals(adjusted.gainsDb(Ear.LEFT), applied.leftGainsDb)
        assertEquals(8f, adjusted.manualBassDb)
        // And the pool underneath is untouched, so "back to what the songs
        // said" still has something to go back to.
        assertEquals(4f, adjusted.aggregate.candidate.bassDb)
    }
}
