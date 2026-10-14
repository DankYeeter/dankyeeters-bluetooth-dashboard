package dev.dankyeeter.btdashboard.ui.screens.monitor

import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventType
import dev.dankyeeter.btdashboard.monitor.link.live.isReadingGap
import dev.dankyeeter.btdashboard.monitor.optimize.Condition
import dev.dankyeeter.btdashboard.monitor.optimize.ConditionBook
import dev.dankyeeter.btdashboard.monitor.optimize.ConditionValue
import dev.dankyeeter.btdashboard.monitor.optimize.Measure
import dev.dankyeeter.btdashboard.monitor.optimize.Verification

/**
 * A run of loss readings on the open screen, and the book of its first one
 * (AD-044 point 3). Stands in until the AD-023 episodes are built; their start
 * takes this role then.
 */
internal data class LossEpisode(val lastLossAtMs: Long, val book: ConditionBook)

/**
 * No loss -> null; loss after loss without a reading gap -> [prev] with the new
 * [atMs]; otherwise a new episode holding [book].
 */
internal fun nextEpisode(
    prev: LossEpisode?,
    atMs: Long,
    hasLoss: Boolean,
    book: ConditionBook,
    expectedIntervalMs: Long,
): LossEpisode? = when {
    !hasLoss -> null
    prev != null && !isReadingGap(atMs - prev.lastLossAtMs, expectedIntervalMs) -> prev.copy(lastLossAtMs = atMs)
    else -> LossEpisode(atMs, book)
}

/**
 * One line per [Condition], in its declared order: the value as read, or why it
 * could not be (`UI_SPEC.md` T-057b, P-004). Each line stays one line (W2): a
 * reason is masked first, then every control character becomes a space, so no
 * reason can forge a line of its own in the stored text.
 */
internal fun conditionLines(book: ConditionBook): List<String> = Condition.entries.map { condition ->
    val name = condition.displayName.replaceFirstChar(Char::uppercase)
    when (val value = book[condition]) {
        is ConditionValue.Read -> "$name: ${value.value}, read."
        is ConditionValue.Unreadable -> "$name: not readable — ${singleLine(redactAddresses(value.reason))}."
        null -> "$name: not readable — not in this reading."
    }
}

/**
 * A dropout row with the book of its episode's first reading under it (AD-044
 * point 4); every other row, or a row outside an episode, unchanged.
 */
internal fun MonitorEvent.withEpisodeBook(episode: LossEpisode?): MonitorEvent {
    if (type != MonitorEventType.DROPOUT || episode == null) return this
    return copy(detail = (listOf(detail, EPISODE_HEADING) + conditionLines(episode.book)).joinToString("\n"))
}

private fun singleLine(text: String): String = text.map { if (it.isISOControl()) ' ' else it }.joinToString("")

/** `UI_SPEC.md` T-057b, P-004, verbatim. */
internal const val EPISODE_HEADING = "Conditions when this episode was first read:"

/** The read-back words (AD-035, AD-046): of the Wi-Fi bands only 2.4 GHz contradicts; 5 and 6 GHz are not checkable. */
internal fun readBackSentence(measure: Measure?, book: ConditionBook): String {
    val condition = measure?.condition ?: return "Not checkable from this phone."
    val value = book[condition] ?: return "Not checkable from this phone."
    if (value is ConditionValue.Unreadable) return "Not checkable: ${redactAddresses(value.reason)}."
    return when (measure.verify(value) to condition) {
        Verification.VERIFIED to Condition.WIFI_BAND -> "Confirmed: Wi-Fi is off on this phone."
        Verification.CONTRADICTED to Condition.WIFI_BAND ->
            "Contradicts what this run needs: Wi-Fi here is on a 2.4 GHz network."
        Verification.NOT_VERIFIABLE to Condition.WIFI_BAND ->
            "Not checkable: whether ${book.shown(condition)} Wi-Fi affects this pairing has not been measured."
        Verification.VERIFIED to Condition.OTHER_ACL_LINKS -> "Confirmed: no other Bluetooth device is connected."
        Verification.VERIFIED to Condition.USB_POWER -> "Confirmed: the phone is running on battery."
        Verification.CONTRADICTED to Condition.OTHER_ACL_LINKS ->
            "Contradicts what this run needs: ${book.shown(condition)} other Bluetooth device(s) " +
                "are still connected."
        Verification.CONTRADICTED to Condition.USB_POWER ->
            "Contradicts what this run needs: the phone is still receiving power."
        else -> "Not checkable from this phone."
    }
}

internal fun ConditionBook.shown(condition: Condition): String =
    (this[condition] as? ConditionValue.Read)?.value ?: "not checkable"

internal val Condition.displayName: String
    get() = when (this) {
        Condition.WIFI_RADIO -> "Wi-Fi power"
        Condition.WIFI_BAND -> "Wi-Fi band"
        Condition.WIFI_SCAN_ALWAYS -> "Wi-Fi scanning"
        Condition.OTHER_ACL_LINKS -> "another Bluetooth device"
        Condition.USB_POWER -> "USB power"
        Condition.DISCOVERY_SEEN -> "device discovery"
    }
