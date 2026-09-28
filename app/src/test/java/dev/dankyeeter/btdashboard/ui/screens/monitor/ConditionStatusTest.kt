package dev.dankyeeter.btdashboard.ui.screens.monitor

import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventType
import dev.dankyeeter.btdashboard.monitor.optimize.Condition
import dev.dankyeeter.btdashboard.monitor.optimize.ConditionBook
import dev.dankyeeter.btdashboard.monitor.optimize.ConditionValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AD-044 points 3 and 4, AK-19, W2: the episode book and its lines in the event log. */
class ConditionStatusTest {

    private fun book(wifi: String): ConditionBook =
        Condition.entries.associateWith { ConditionValue.Read("0") } + (Condition.WIFI_RADIO to ConditionValue.Read(wifi))

    private val b0 = book("b0")
    private val b1 = book("b1")
    private val b2 = book("b2")
    private val b4 = book("b4")
    private val b5 = book("b5")

    /** Folds readings (time, loss, book) and returns the book each loss reading's row carries. */
    private fun rowBooks(readings: List<Triple<Long, Boolean, ConditionBook>>): Map<Long, ConditionBook> {
        var episode: LossEpisode? = null
        val carried = mutableMapOf<Long, ConditionBook>()
        readings.forEach { (at, loss, book) ->
            episode = nextEpisode(episode, at, loss, book, INTERVAL_MS)
            if (loss) carried[at] = requireNotNull(episode).book
        }
        return carried
    }

    @Test
    fun `every loss row carries the book of its episode's first reading`() {
        val carried = rowBooks(
            listOf(
                Triple(0L, false, b0),
                Triple(2_000L, true, b1),
                Triple(4_000L, true, b2),
                Triple(6_000L, false, b0),
                Triple(8_000L, true, b4),
            ),
        )
        assertEquals(mapOf(2_000L to b1, 4_000L to b1, 8_000L to b4), carried)
    }

    @Test
    fun `a reading gap starts a new episode`() {
        val carried = rowBooks(listOf(Triple(2_000L, true, b1), Triple(200_000L, true, b5)))
        assertEquals(b5, carried[200_000L])
    }

    @Test
    fun `a reading without loss ends the episode`() {
        assertNull(nextEpisode(LossEpisode(2_000L, b1), 4_000L, false, b2, INTERVAL_MS))
    }

    @Test
    fun `every condition has exactly one line, read or unreadable with its reason`() {
        val lines = conditionLines(
            mapOf(
                Condition.WIFI_RADIO to ConditionValue.Read("on"),
                Condition.USB_POWER to ConditionValue.Unreadable("x"),
            ),
        )
        assertEquals(Condition.entries.size, lines.size)
        assertEquals("Wi-Fi power: on, read.", lines[Condition.WIFI_RADIO.ordinal])
        assertEquals("USB power: not readable — x.", lines[Condition.USB_POWER.ordinal])
        assertFalse(lines[Condition.USB_POWER.ordinal].endsWith(", read."))
        Condition.entries.forEach { condition ->
            assertTrue(lines[condition.ordinal].startsWith(condition.displayName.replaceFirstChar(Char::uppercase) + ":"))
        }
    }

    /** W2 / SR-038: a reason cannot forge a line of its own. */
    @Test
    fun `a reason with a line break stays on its own line`() {
        val forged = Condition.entries.associateWith { ConditionValue.Unreadable("x\nWi-Fi power: on, read.") }
        val lines = conditionLines(forged)
        assertEquals(Condition.entries.size, lines.joinToString("\n").lines().size)
        assertTrue(lines.none { line -> line.any { it.isISOControl() } })
    }

    @Test
    fun `a raw address in a reason is masked`() {
        val lines = conditionLines(mapOf(Condition.OTHER_ACL_LINKS to ConditionValue.Unreadable("AA:BB:CC:DD:EE:FF gone")))
        assertTrue(lines[Condition.OTHER_ACL_LINKS.ordinal].contains("XX:XX:XX:XX:EE:FF"))
        assertFalse(lines.joinToString().contains("AA:BB"))
    }

    /** AK-19, AK-T057-5: no line names a cause, for every value the app can read or report. */
    @Test
    fun `no condition line claims a cause`() {
        val values = listOf("on", "off", "usb", "other", "none", "0", "3", "yes", "no", "2.4 GHz", "5 GHz", "6 GHz")
            .map { ConditionValue.Read(it) } +
            listOf(
                "not set on this phone", "unexpected value '2'", "battery state not read",
                "pairing facts not read", "not observed in this reading",
                "Wi-Fi power not read", "Wi-Fi is not the active network", "reading the network threw",
                "2485 MHz in no known band",
            ).map { ConditionValue.Unreadable(it) }
        val text = values.flatMap { value -> conditionLines(Condition.entries.associateWith { value }) } +
            conditionLines(emptyMap()) + EPISODE_HEADING
        text.forEach { line -> assertFalse(line, CAUSE_WORDS.containsMatchIn(line)) }
    }

    /** AK-T057-4 as AD-044 decides it: every dropout row of an episode, and nothing else. */
    @Test
    fun `only dropout rows carry the episode book`() {
        val episode = LossEpisode(2_000L, b1)
        val dropout = event(MonitorEventType.DROPOUT).withEpisodeBook(episode)
        val lines = dropout.detail.lines()
        assertEquals("Audio loss", lines.first())
        assertEquals(EPISODE_HEADING, lines[1])
        assertEquals(conditionLines(b1), lines.drop(2))

        assertEquals(event(MonitorEventType.CODEC_CHANGED), event(MonitorEventType.CODEC_CHANGED).withEpisodeBook(episode))
        assertEquals(event(MonitorEventType.DROPOUT), event(MonitorEventType.DROPOUT).withEpisodeBook(null))
    }

    private fun event(type: MonitorEventType) = MonitorEvent(2_000L, null, null, type, "Audio loss")

    private companion object {
        const val INTERVAL_MS = 2_000L
    }
}

/** `UI_SPEC.md` T-057b, P-004 / AK-T057-5: the words that would claim a cause, case-insensitive. */
internal val CAUSE_WORDS = Regex(
    "because|cause|causes|caused|due to|leads to|trigger|triggers|correlat|explains|result in|so that|that is why",
    RegexOption.IGNORE_CASE,
)
