package dev.dankyeeter.btdashboard.ui.screens.monitor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.dankyeeter.btdashboard.monitor.link.EventLayer
import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventSummary
import dev.dankyeeter.btdashboard.ui.theme.Panel
import dev.dankyeeter.btdashboard.ui.theme.PanelHeader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

/** How many events the log shows before it starts hiding older ones. */
private const val EVENT_LIMIT = 40

/**
 * The event log: one short line per event, and the whole story behind a tap.
 *
 * ## Two layers, and why
 *
 * The log used to render `MonitorEvent.detail` — the finished sentence whichever
 * subsystem produced the event had written for its own reader. Forty of those in
 * one panel is a wall of prose in which a dropout and a routing change look
 * identical, and half of it named counters and profiles rather than anything
 * that happened to the music.
 *
 * So the list carries [MonitorEventSummary]'s derived line — bounded, built from
 * the typed fields, never a substring of a parser's output — and the sentence,
 * the values and the technical payload live one tap down. Nothing in the first
 * layer requires knowing how the app works.
 *
 * The disclosure is an expanding row rather than a bottom sheet because that is
 * this app's idiom: every explanation on these screens, from `ExplainedRow` to
 * the panel headers, opens in place and closes again.
 *
 * ## The filter
 *
 * Some events exist to be read afterwards, not scanned — see [EventLayer]. They
 * are hidden by default and the chip brings them back, which is the honest
 * version of a log that quietly omits things: the count is on the chip, so a
 * reader can see there is more before deciding they want it.
 */
@Composable
fun EventLogPanel(events: List<MonitorEvent>, modifier: Modifier = Modifier) {
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    // Opened by identity rather than by index: a new event arriving shifts every
    // index down, which would silently move the open row to its neighbour.
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }

    val lines = remember(events) { MonitorEventSummary.lines(events) }
    val diagnosticCount = remember(lines) {
        lines.count { it.event.type.layer == EventLayer.DETAIL }
    }
    val listed = remember(lines, showDiagnostics) {
        lines.filter { showDiagnostics || it.event.type.layer == EventLayer.LIST }.asReversed()
    }
    val shown = remember(listed) { listed.take(EVENT_LIMIT) }

    Panel(modifier) {
        PanelHeader(
            "Events",
            trailing = {
                // Only when there is something to reveal. A filter that can only
                // ever show the same list is a control that lies about existing.
                if (diagnosticCount > 0) {
                    FilterChip(
                        selected = showDiagnostics,
                        onClick = { showDiagnostics = !showDiagnostics },
                        label = { Text("Diagnostics ($diagnosticCount)") },
                    )
                }
            },
        )

        if (shown.isEmpty()) {
            Text(
                if (lines.isEmpty()) {
                    "No events yet — connects, dropouts and takeovers appear here."
                } else {
                    // The list is empty but the log is not, which is a different
                    // fact and would otherwise read as "nothing has happened".
                    "Nothing but diagnostics in this window."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Panel
        }

        shown.forEach { line ->
            val key = line.key()
            EventRow(
                line = line,
                open = openKey == key,
                // Tapping the open row closes it: the detail is a look, not a
                // destination, and needing to find a second control to get rid
                // of it is how a log turns into a stack of open cards.
                onToggle = { openKey = if (openKey == key) null else key },
            )
        }

        if (listed.size > EVENT_LIMIT) {
            // Silent truncation made the log look complete when it was not.
            Text(
                "Showing the $EVENT_LIMIT most recent.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One row: a time, a short line, and the detail it opens.
 *
 * The colour is the event type's own `loud` flag, not a set kept here — the
 * timeline draws its ticks from the same property, and the two used to disagree
 * about whether a disconnect counts.
 */
@Composable
private fun EventRow(
    line: MonitorEventSummary.Line,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val event = line.event
    val loud = event.type.loud

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                timeFormat.format(Date(event.timestampMs)),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                // Redacted on the boundary, like every other string this screen
                // takes from below it: the layers underneath work in real
                // addresses and a nameless headphone puts one in `deviceName`.
                redactAddresses(line.summary),
                style = MaterialTheme.typography.bodyMedium,
                color = if (loud) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }

        AnimatedVisibility(open) {
            Column(
                Modifier.padding(top = 4.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    redactAddresses(event.detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The payload: values only, in the order somebody quoting this
                // into a bug report would want them. No type name — an enum
                // constant is the one thing on this screen that is certainly
                // a code line.
                event.payloadLine()?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The values behind a row, or null when the event established none.
 *
 * Only fields that were really recorded appear. A payload line that printed
 * "codec: unknown" for every connect would be noise wearing a monospace font.
 */
private fun MonitorEvent.payloadLine(): String? {
    val parts = buildList {
        deviceName?.takeIf { it.isNotBlank() }?.let { add(redactAddresses(it)) }
        codec?.let { add(it.displayName) }
        bitrateKbps?.let { add("$it kbps") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * A stable identity for a row.
 *
 * Timestamp alone is not enough: a takeover is recorded at the timestamp of the
 * playback stop it explains, so two rows legitimately share one instant.
 */
private fun MonitorEventSummary.Line.key(): String = "${event.timestampMs}/${event.type.name}"
