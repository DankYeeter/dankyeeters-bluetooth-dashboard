package dev.dankyeeter.btdashboard.ui.screens.monitor

import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.dankyeeter.btdashboard.monitor.optimize.Measure
import dev.dankyeeter.btdashboard.ui.theme.ExplainedHeader
import dev.dankyeeter.btdashboard.ui.theme.GoldOutlinedButton
import dev.dankyeeter.btdashboard.ui.theme.Panel
import dev.dankyeeter.btdashboard.ui.tuning.openSettings
import dev.dankyeeter.btdashboard.ui.tuning.settingsAction
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
            SettingsJump(measure)
        }
    }
}

/**
 * The system page for [measure] where AD-045 names one, and nothing where it does not — one
 * composable for the Measures panel and the comparison's instruction (AK-T057-7). A start that
 * fails puts a sentence where the button stood (AK-T057-8); the reading after the return comes
 * from the open screen itself (AD-044 point 6).
 */
@Composable
internal fun SettingsJump(measure: Measure) {
    val action = settingsAction(measure) ?: return
    val page = when (action) {
        Settings.ACTION_WIFI_SETTINGS -> "Wi-Fi settings"
        Settings.ACTION_BLUETOOTH_SETTINGS -> "Bluetooth settings"
        else -> error("no page name for $action")
    }
    val context = LocalContext.current
    var failed by remember(measure) { mutableStateOf(false) }
    if (failed) {
        Text("Could not open $page on this phone — nothing changed.")
    } else {
        GoldOutlinedButton(onClick = { failed = !openSettings(context, action) }) { Text("Open $page") }
    }
}

/** `UI_SPEC.md` T-057b, P-001, verbatim. */
private const val MEASURES_EXPLANATION =
    "The six measures with evidence behind them (R-010). Each line below is read from this phone right now, in " +
        "the same words the guided comparison uses to read a condition back. This is a reading, not a score — " +
        "there is no combined result across the six, and a line reading confirmed here was never tested under " +
        "a pinned run."
