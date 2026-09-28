package dev.dankyeeter.btdashboard.ui.screens.monitor

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import dev.dankyeeter.btdashboard.monitor.optimize.Condition
import dev.dankyeeter.btdashboard.monitor.optimize.ConditionBook
import dev.dankyeeter.btdashboard.monitor.optimize.ConditionValue
import dev.dankyeeter.btdashboard.monitor.optimize.Measure
import dev.dankyeeter.btdashboard.ui.theme.BtDashboardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

/** AK-18, AK-T057-1..3: the Measures panel on the Monitor screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MeasureStatusSectionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** One condition per `Verification` value: Wi-Fi off confirms, a second device contradicts, USB is unreadable. */
    private val book: ConditionBook = mapOf(
        Condition.WIFI_RADIO to ConditionValue.Read("off"),
        Condition.WIFI_BAND to ConditionValue.Read("off"),
        Condition.WIFI_SCAN_ALWAYS to ConditionValue.Read("on"),
        Condition.USB_POWER to ConditionValue.Unreadable("battery state not read"),
        Condition.OTHER_ACL_LINKS to ConditionValue.Read("2"),
        Condition.DISCOVERY_SEEN to ConditionValue.Read("no"),
    )

    private fun show(readBook: ReadBook?): List<String> {
        composeRule.setContent { BtDashboardTheme { MeasureStatusPanel(readBook) } }
        fun SemanticsNode.walk(): List<SemanticsNode> = listOf(this) + children.flatMap { it.walk() }
        return composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode().walk()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
    }

    @Test
    fun `six rows in measure order, each in the comparison's own words`() {
        val texts = show(ReadBook(book, READ_AT_MS))

        val expected = listOf("MEASURES", "Read at ${timeFormat.format(Date(READ_AT_MS))}.") +
            Measure.entries.flatMap { listOf(it.displayName, readBackSentence(it, book)) }
        assertEquals(expected, texts)
    }

    @Test
    fun `an unreadable condition is not checkable with its reason, never confirmed`() {
        val texts = show(ReadBook(book, READ_AT_MS))
        val a5 = texts[texts.indexOf(Measure.USB_CABLE_OFF.displayName) + 1]

        assertEquals("Not checkable: battery state not read.", a5)
        listOf(Measure.NO_DISCOVERY, Measure.BODY_OUT_OF_PATH, Measure.SINK_ALLOWS_LDAC).forEach { measure ->
            assertEquals("Not checkable from this phone.", texts[texts.indexOf(measure.displayName) + 1])
        }
    }

    @Test
    fun `before a reading the six names stand under one waiting line`() {
        val texts = show(null)

        assertEquals(listOf("MEASURES", "Waiting for the first reading.") + Measure.entries.map { it.displayName }, texts)
    }

    /** AK-T057-3 on what is drawn; the file itself is in [AcceptanceCriteriaGrepTest]. */
    @Test
    fun `no combined judgement and no count`() {
        val texts = show(ReadBook(book, READ_AT_MS)).joinToString("\n")

        assertFalse(texts, JUDGEMENT_WORDS.containsMatchIn(texts))
        assertFalse(texts, COUNTER.containsMatchIn(texts))
    }

    private companion object {
        const val READ_AT_MS = 1_700_000_000_000L
    }
}

/** `UI_SPEC.md` T-057b P-001: the T-039 word lists and the collective words named there. */
internal val JUDGEMENT_WORDS = Regex(
    """\b(lowest rate|minimum bitrate|worst|floor|always|never below|never dropped|consistently|""" +
        """stable|steady enough|safe to pin|you can pin|guaranteed|reliable|ready|good to go)\b""",
    RegexOption.IGNORE_CASE,
)

/** AK-T057-3: no "4 of 6". */
internal val COUNTER = Regex("""\d+\s*(of|von)\s*6""")
