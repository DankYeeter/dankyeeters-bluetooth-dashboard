package dev.dankyeeter.btdashboard.ui.screens.devices

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.dankyeeter.btdashboard.monitor.link.live.LdacQualityMode
import dev.dankyeeter.btdashboard.ui.theme.BtDashboardTheme
import dev.dankyeeter.btdashboard.ui.tuning.LdacQuality
import dev.dankyeeter.btdashboard.ui.tuning.LdacTuningState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bitrate chips on the Bluetooth tab's device card.
 *
 * Three things are pinned here, and each has a way of going quietly wrong:
 *
 *  - the chips are **there**, in the open, for a device on a codec that has a
 *    quality to pin. They spent a release behind an "Advanced device settings"
 *    expander, which is what this whole change undoes;
 *  - the lit chip comes from the *stored* choice, by the same rule the
 *    Monitoring panel uses — see `LdacQualityTest` for the rule itself;
 *  - the card never claims more than it knows. It does not read the link, and
 *    the line under the chips says so instead of printing a rate it did not
 *    measure. The renegotiation caveat is on screen either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BitrateSectionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val pinned = mutableListOf<Long>()

    private fun render(
        state: BitrateSectionState = BitrateSectionState.SHOWN,
        storedQuality: Long = LdacQuality.NONE,
        tuning: LdacTuningState = LdacTuningState(),
        enabled: Boolean = true,
        sampleRateHz: Int? = 96_000,
        measuredKbps: Int? = null,
    ) {
        composeRule.setContent {
            BtDashboardTheme {
                BitrateSection(
                    state = state,
                    storedQuality = storedQuality,
                    tuning = tuning,
                    onPin = { pinned += it },
                    onDismissMessage = {},
                    enabled = enabled,
                    sampleRateHz = sampleRateHz,
                    measuredKbps = measuredKbps,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertShows(text: String) =
        composeRule.onAllNodesWithText(text, substring = true).onFirst().assertExists()

    private fun assertHides(text: String) =
        composeRule.onAllNodesWithText(text, substring = true).assertCountEquals(0)

    @Test
    fun `the four chips are on the card, not behind an expander`() {
        render()

        listOf("990 kbps", "660 kbps", "330 kbps", "ABR").forEach { assertShows(it) }
        assertHides("Advanced device settings")
    }

    @Test
    fun `the stored rate is the chip that is lit`() {
        render(storedQuality = LdacQuality.HIGH_QUALITY)

        composeRule.onNodeWithText("990 kbps").assertIsSelected()
        composeRule.onNodeWithText("ABR").assertIsNotSelected()
    }

    /**
     * With nothing stored the resting state is ABR, because that is what an
     * unpinned LDAC link actually does. A card with no chip lit would read as a
     * control that had lost its value.
     */
    @Test
    fun `nothing stored lights ABR and says why`() {
        render(storedQuality = LdacQuality.NONE)

        composeRule.onNodeWithText("ABR").assertIsSelected()
        assertShows("Nothing stored")
        assertShows("adaptive")
    }

    @Test
    fun `the same stored value lights the same chip the monitoring panel would`() {
        // The screens differ in what they can see, not in what they conclude:
        // this card has no live reading and the panel does, and with a stored
        // wish both land on the same chip. The rule is one function.
        render(storedQuality = LdacQuality.CONNECTION_PRIORITY)

        composeRule.onNodeWithText("330 kbps").assertIsSelected()
        assertEquals(
            LdacQuality.selected(LdacQuality.CONNECTION_PRIORITY, LdacQualityMode.NOT_PINNED),
            LdacQuality.selected(LdacQuality.CONNECTION_PRIORITY),
        )
    }

    @Test
    fun `tapping a chip asks for that rate`() {
        render()

        composeRule.onNodeWithText("660 kbps").performClick()

        assertEquals(listOf(LdacQuality.STANDARD), pinned)
    }

    /** Each request renegotiates, and the second would race the first's read-back. */
    @Test
    fun `a request in flight locks the chips`() {
        render(tuning = LdacTuningState(busy = true))

        composeRule.onNodeWithText("990 kbps").assertIsNotEnabled()
    }

    @Test
    fun `a disabled card cannot be tapped into changing a link`() {
        render(enabled = false)

        composeRule.onNodeWithText("990 kbps").assertIsNotEnabled()
    }

    /**
     * The honesty rule this card lives under: it does not poll the link — one
     * pass is three `dumpsys` calls on the app's start screen — so it says what
     * is stored and names the tab that can answer the other question.
     */
    @Test
    fun `without a live reading the card says what it is showing instead`() {
        render(storedQuality = LdacQuality.HIGH_QUALITY)

        assertShows("Stored: 990 kbps")
        assertShows("Not read live here")
        assertShows("Monitoring")
        // Nothing on this card may look like a measurement.
        assertHides("measured")
    }

    @Test
    fun `a live reading is printed as the measurement it is`() {
        render(storedQuality = LdacQuality.HIGH_QUALITY, measuredKbps = 660)

        assertShows("660 kbps measured right now")
    }

    /** The caveat is short and always there — the audio really does cut out. */
    @Test
    fun `the renegotiation caveat is on the card`() {
        render()

        assertShows("audio cuts out for a moment")
    }

    @Test
    fun `an outcome is shown rather than swallowed`() {
        render(
            tuning = LdacTuningState(
                message = "Stored for this headphone and asked for again on every connect. " +
                    "It was not changed on the link right now — the privileged helper is " +
                    "not running.",
                messageIsError = false,
            ),
        )

        assertShows("Stored for this headphone")
        assertShows("the privileged helper is not running")
        assertShows("OK")
    }

    @Test
    fun `a codec with nothing to pin draws nothing at all`() {
        render(state = BitrateSectionState.HIDDEN)

        assertHides("990 kbps")
        assertHides("Bitrate")
    }

    /**
     * The state that used to make the control vanish: connected, but no helper,
     * so Android will not name the codec. The chips stay and explain themselves.
     */
    @Test
    fun `an unreadable codec keeps the chips and names the gap`() {
        render(state = BitrateSectionState.UNREADABLE_CODEC)

        assertShows("990 kbps")
        assertShows("will not say which codec")
    }

    @Test
    fun `the chips carry the ladder of the negotiated sample rate`() {
        render(sampleRateHz = 44_100)

        assertShows("909 kbps")
        assertHides("990 kbps")
    }
}

/** When the section belongs on the card at all. */
class BitrateSectionStateTest {

    @Test
    fun `an LDAC link shows the chips`() {
        assertEquals(
            BitrateSectionState.SHOWN,
            bitrateSectionState(negotiated = "LDAC", stored = null, deviceConnected = true),
        )
    }

    /**
     * A stored wish keeps the section even with the headphone away or running
     * something else: it is what the next connect will ask for, and a control
     * that disappears is a wish that cannot be withdrawn.
     */
    @Test
    fun `a stored LDAC wish shows the chips with nothing connected`() {
        assertEquals(
            BitrateSectionState.SHOWN,
            bitrateSectionState(negotiated = null, stored = "LDAC", deviceConnected = false),
        )
    }

    @Test
    fun `a codec with no quality knob and nothing stored hides the section`() {
        assertEquals(
            BitrateSectionState.HIDDEN,
            bitrateSectionState(negotiated = "AAC", stored = null, deviceConnected = true),
        )
        assertEquals(
            BitrateSectionState.HIDDEN,
            bitrateSectionState(negotiated = null, stored = null, deviceConnected = false),
        )
    }

    @Test
    fun `connected with an unreadable codec is its own state`() {
        assertEquals(
            BitrateSectionState.UNREADABLE_CODEC,
            bitrateSectionState(negotiated = null, stored = null, deviceConnected = true),
        )
    }

    @Test
    fun `the state line never calls a stored choice a measurement`() {
        val line = stateLine(
            BitrateSectionState.SHOWN,
            storedQuality = LdacQuality.HIGH_QUALITY,
            measuredKbps = null,
            sampleRateHz = 96_000,
        )

        assertTrue(line.startsWith("Stored:"))
        assertTrue("measured" !in line)
    }
}
