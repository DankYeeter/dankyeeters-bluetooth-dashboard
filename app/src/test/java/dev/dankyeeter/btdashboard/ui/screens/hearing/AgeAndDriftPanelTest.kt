package dev.dankyeeter.btdashboard.ui.screens.hearing

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.hearing.AgeReference
import dev.dankyeeter.btdashboard.hearing.EarDrift
import dev.dankyeeter.btdashboard.hearing.FrequencyShift
import dev.dankyeeter.btdashboard.hearing.HearingDriftResult
import dev.dankyeeter.btdashboard.hearing.Iso7029Sex
import dev.dankyeeter.btdashboard.ui.theme.BtDashboardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two new cards on the hearing screen, checked at the only layer that
 * matters for them: the first line.
 *
 * Both panels exist to say one short thing on the surface and keep the
 * paragraph behind the question mark, and both say something a user could act
 * on wrongly if the wording drifted. A "not enough comparable runs" line with
 * no number in it, or a drift notice that named the wrong ear, would be a
 * defect nobody would catch by reading the model — the arithmetic would still
 * be right.
 *
 * The state each case needs is expensive to reach through the whole screen (six
 * runs, six months apart, one headphone) and trivial to hand the panel
 * directly, which is why the two composables are internal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgeAndDriftPanelTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // ---- hearing over time ---------------------------------------------------

    private fun showDrift(result: HearingDriftResult) {
        composeRule.setContent { BtDashboardTheme { HearingOverTimePanel(result) } }
        composeRule.waitForIdle()
    }

    @Test
    fun `not enough runs names how many more and on which headphones`() {
        showDrift(
            HearingDriftResult.NotEnoughData(
                comparableRuns = 2,
                moreRunsNeeded = 4,
                spanDays = 120,
                moreDaysNeeded = 0,
                deviceName = "Focal Bathys",
                volumeFraction = 0.7,
            ),
        )
        composeRule.onNodeWithText("4 runs", substring = true).assertExists()
        composeRule.onNodeWithText("Focal Bathys", substring = true).assertExists()
        composeRule.onNodeWithText("70 %", substring = true).assertExists()
    }

    @Test
    fun `enough runs but too close together asks for time, not for runs`() {
        showDrift(
            HearingDriftResult.NotEnoughData(
                comparableRuns = 6,
                moreRunsNeeded = 0,
                spanDays = 20,
                moreDaysNeeded = 70,
                deviceName = "Focal Bathys",
                volumeFraction = 0.7,
            ),
        )
        composeRule.onNodeWithText("70 more days", substring = true).assertExists()
    }

    @Test
    fun `with nothing connected the card says what to do about it`() {
        showDrift(
            HearingDriftResult.NotEnoughData(
                comparableRuns = 0,
                moreRunsNeeded = 6,
                spanDays = 0,
                moreDaysNeeded = 90,
                deviceName = null,
                volumeFraction = null,
                noDeviceConnected = true,
            ),
        )
        composeRule.onNodeWithText("Connect your headphones", substring = true).assertExists()
    }

    @Test
    fun `stable says so, and still owns up to how much the runs moved`() {
        showDrift(
            HearingDriftResult.Stable(
                comparableRuns = 9,
                baselineAtMillis = 1_700_000_000_000L,
                latestAtMillis = 1_720_000_000_000L,
                largestShiftDb = 6.4,
            ),
        )
        composeRule.onNodeWithText("Stable across 9", substring = true).assertExists()
        // The honest footnote: "stable" must not read as "nothing moved".
        composeRule.onNodeWithText("6 dB", substring = true).assertExists()
    }

    @Test
    fun `a drift notice names the ear and the frequencies and stays calm`() {
        showDrift(
            HearingDriftResult.DriftSuspected(
                comparableRuns = 8,
                baselineAtMillis = 1_700_000_000_000L,
                latestAtMillis = 1_720_000_000_000L,
                ears = listOf(
                    EarDrift(
                        Ear.LEFT,
                        listOf(
                            FrequencyShift(4000, 12.0, everyRecentRunWorse = true),
                            FrequencyShift(6000, 14.0, everyRecentRunWorse = true),
                        ),
                    ),
                ),
            ),
        )
        composeRule.onNodeWithText("Left ear", substring = true).assertExists()
        composeRule.onNodeWithText("4 kHz and 6 kHz", substring = true).assertExists()
        // A recommendation, never a diagnosis, and the cheap check comes first.
        composeRule.onNodeWithText("Re-run the test", substring = true).assertExists()
        composeRule.onNodeWithText("practice", substring = true).assertExists()
    }

    // ---- age reference -------------------------------------------------------

    private var savedYear: Int? = null
    private var savedSex: Iso7029Sex? = null

    private fun showAge(state: HearingUiState) {
        composeRule.setContent {
            BtDashboardTheme {
                AgeReferencePanel(
                    state = state,
                    onSave = { year, sex -> savedYear = year; savedSex = sex },
                    onClear = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a birth year and a sex reach the callback`() {
        showAge(HearingUiState(currentYear = 2026))
        composeRule.onNodeWithTag(BIRTH_YEAR_TAG).performTextInput("1984")
        composeRule.onNodeWithText("Male").performClick()
        composeRule.onNodeWithText("Save age reference").performClick()
        composeRule.waitForIdle()

        assertEquals(1984, savedYear)
        assertEquals(Iso7029Sex.MALE, savedSex)
    }

    @Test
    fun `an empty field cannot be saved`() {
        showAge(HearingUiState(currentYear = 2026))
        composeRule.onNodeWithText("Save age reference").assertIsNotEnabled()
        assertNull(savedYear)
    }

    @Test
    fun `a stored reference shows the age it currently means`() {
        showAge(
            HearingUiState(
                currentYear = 2026,
                ageReference = AgeReference(birthYear = 1984, sex = Iso7029Sex.MALE),
            ),
        )
        composeRule.onNodeWithText("Born 1984", substring = true).assertExists()
        composeRule.onNodeWithText("42 this year", substring = true).assertExists()
    }

    /**
     * The ranking the whole feature promises: a calibrated measurement of these
     * ears outranks a statistic about a population, so the age panel draws no
     * conclusions once a clinical audiogram exists. Checked on the state rather
     * than on the pixels, because that is where the rule lives.
     */
    @Test
    fun `an age gap is never reported while a clinical audiogram exists`() {
        val steep = dev.dankyeeter.btdashboard.hearing.TEST_FREQUENCIES_HZ.map { hz ->
            dev.dankyeeter.btdashboard.hearing.ThresholdPoint(
                hz,
                if (hz >= 4000) -25.0 else -60.0,
            )
        }
        val audiogram = dev.dankyeeter.btdashboard.hearing.Audiogram(
            runIds = listOf("r"),
            left = steep,
            right = steep,
        )
        val withoutClinic = HearingUiState(
            currentYear = 2026,
            ageReference = AgeReference(birthYear = 2000),
            audiogram = audiogram,
        )
        val withClinic = withoutClinic.copy(
            clinicalAudiogram = dev.dankyeeter.btdashboard.hearing.ClinicalAudiogram(
                leftDbHl = mapOf(1000 to 10.0, 4000 to 15.0),
            ),
        )

        assertEquals(2, withoutClinic.ageReferenceGaps.size)
        assertEquals(0, withClinic.ageReferenceGaps.size)
    }
}
