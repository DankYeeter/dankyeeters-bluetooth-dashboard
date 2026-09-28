package dev.dankyeeter.btdashboard.ui.screens.monitor

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.dankyeeter.btdashboard.monitor.optimize.Measure
import dev.dankyeeter.btdashboard.ui.theme.ExplainedHeader
import dev.dankyeeter.btdashboard.ui.theme.Panel
import java.util.Date

/**
 * The six measures read back against the newest book on the open screen
 * (AK-18, AD-044 point 5, `UI_SPEC.md` T-057b P-001).
 *
 * Rows in [Measure.entries] order and never by state: an order by state would
 * be a ranking (R-010). No combined line, no count, no colour or icon per
 * state (R-E). Each sentence is [readBackSentence], the comparison's own words.
 */
@Composable
internal fun MeasureStatusPanel(readBook: ReadBook?) {
    Panel {
        ExplainedHeader("Measures", MEASURES_EXPLANATION)
        Text(
            readBook?.let { "Read at ${timeFormat.format(Date(it.readAtMs))}." } ?: "Waiting for the first reading.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Measure.entries.forEach { measure ->
            Text(measure.displayName, style = MaterialTheme.typography.bodyMedium)
            readBook?.let {
                Text(
                    readBackSentence(measure, it.book),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** `UI_SPEC.md` T-057b, P-001, verbatim. */
private const val MEASURES_EXPLANATION =
    "The six measures with evidence behind them (R-010). Each line below is read from this phone right now, in " +
        "the same words the guided comparison uses to read a condition back. This is a reading, not a score — " +
        "there is no combined result across the six, and a line reading confirmed here was never tested under " +
        "a pinned run."
