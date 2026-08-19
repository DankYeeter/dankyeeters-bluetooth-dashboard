package dev.dankyeeter.btdashboard.ui.screens.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dankyeeter.btdashboard.monitor.diagnostic.StepOutcome
import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventType
import dev.dankyeeter.btdashboard.monitor.link.QualityReportAvailability
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.dankyeeter.btdashboard.ui.theme.GoldCard
import dev.dankyeeter.btdashboard.ui.theme.GoldTitle
import dev.dankyeeter.btdashboard.ui.theme.GoldButton
import dev.dankyeeter.btdashboard.ui.theme.GoldOutlinedButton

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
fun MonitorScreen(viewModel: MonitorViewModel = viewModel()) {
    val events by viewModel.events.collectAsState()
    val samples by viewModel.samples.collectAsState()
    val status by viewModel.status.collectAsState()
    val bqr by viewModel.bqrAvailability.collectAsState()
    val diagnostic by viewModel.diagnostic.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GoldTitle("Link Monitor", style = MaterialTheme.typography.headlineSmall)

        GoldCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GoldTitle("Data source")
                // Lead with the source that is working. The old wording opened
                // with the BQR failure, which read as "nothing is being measured"
                // even though the fallback was collecting fine.
                Text(
                    when (bqr) {
                        is QualityReportAvailability.Active ->
                            "Bluetooth Quality Report active — packet loss and glitch counts " +
                                "come straight from the controller."
                        is QualityReportAvailability.Unavailable ->
                            "Reading the link through ${viewModel.activeSource().displayName}."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                (bqr as? QualityReportAvailability.Unavailable)?.let { unavailable ->
                    Text(
                        "Bluetooth Quality Report would give controller-level packet loss, but " +
                            "it needs BLUETOOTH_PRIVILEGED, which no normal app can hold " +
                            "(${unavailable.reason}).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Text(
                    "Sampling: ${status.mode.name.lowercase()} — ${status.reason}",
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoldButton(onClick = viewModel::startDeepCapture) { Text("Watch live") }
                    GoldOutlinedButton(onClick = viewModel::stopDeepCapture) { Text("Stop capture") }
                }
            }
        }

        GoldCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GoldTitle("Timeline (last 2 hours)")
                LinkTimeline(samples = samples, events = events)
                Text(
                    "${samples.size} samples · ${events.size} events",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        GoldCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GoldTitle("Events")
                if (events.isEmpty()) {
                    Text(
                        "No events recorded yet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    events.asReversed().take(40).forEach { EventRow(it) }
                }
            }
        }

        DiagnosticCard(
            diagnostic,
            onRun = { viewModel.runDiagnostic() },
            onCancel = viewModel::cancelDiagnostic,
            onDismissMessage = viewModel::dismissDiagnosticMessage,
        )
    }
}

@Composable
private fun EventRow(event: MonitorEvent) {
    val loud = event.type == MonitorEventType.TAKEOVER ||
        event.type == MonitorEventType.INTERRUPTION
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            timeFormat.format(Date(event.timestampMs)),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            event.detail,
            style = if (loud) {
                MaterialTheme.typography.bodyMedium
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = if (loud) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/** The guided "test device" routine and its report. */
@Composable
private fun DiagnosticCard(
    state: DiagnosticUiState,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    GoldCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GoldTitle("Test device")
            Text(
                "Connection check, codec negotiation, codec cycling and a 3-minute " +
                    "soak with deep capture, ending in a summary report.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())

            state.steps.forEach { step ->
                val marker = when (step.outcome) {
                    is StepOutcome.Passed -> "OK"
                    is StepOutcome.Warned -> "!"
                    is StepOutcome.Failed -> "FAIL"
                    is StepOutcome.Skipped -> "skipped"
                }
                Text(
                    "[$marker] ${step.step.title}: ${step.outcome.detail}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            state.report?.let { report ->
                Text(report.verdict, style = MaterialTheme.typography.bodyMedium)
            }

            state.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onDismissMessage) { Text("OK") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoldButton(onClick = onRun, enabled = !state.running) { Text("Run diagnostic") }
                if (state.running) {
                    GoldOutlinedButton(onClick = onCancel) { Text("Stop diagnostic") }
                }
            }
        }
    }
}
