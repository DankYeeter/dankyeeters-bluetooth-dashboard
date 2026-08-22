package dev.dankyeeter.btdashboard.ui.screens.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dankyeeter.btdashboard.monitor.MonitorGraph
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dankyeeter.btdashboard.monitor.diagnostic.StepOutcome
import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventType
import dev.dankyeeter.btdashboard.monitor.link.QualityReportAvailability
import dev.dankyeeter.btdashboard.monitor.sampling.SamplingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.dankyeeter.btdashboard.ui.theme.GoldButton
import dev.dankyeeter.btdashboard.ui.theme.GoldOutlinedButton
import dev.dankyeeter.btdashboard.ui.theme.Panel
import dev.dankyeeter.btdashboard.ui.theme.PanelDivider
import dev.dankyeeter.btdashboard.ui.theme.PanelHeader
import dev.dankyeeter.btdashboard.ui.theme.Pill
import dev.dankyeeter.btdashboard.ui.theme.PillTone
import dev.dankyeeter.btdashboard.ui.theme.Readout

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
fun MonitorScreen(viewModel: MonitorViewModel = viewModel()) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val samples by viewModel.samples.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val bqr by viewModel.bqrAvailability.collectAsStateWithLifecycle()
    val diagnostic by viewModel.diagnostic.collectAsStateWithLifecycle()

    // The sampler only polls on a lit screen while somebody is actually
    // looking at link data. The ViewModel covers screen-open/close; this
    // covers the app going to the background with the screen still in the
    // back stack — ON_STOP must stop the polling too.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> MonitorGraph.setUiVisible(true)
                Lifecycle.Event.ON_STOP -> MonitorGraph.setUiVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            MonitorGraph.setUiVisible(false)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Monitoring", style = MaterialTheme.typography.displayMedium)

        Panel {
            // The sampling mode is a state, so it wears the panel's status pill
            // instead of being spelled out in a sentence; the reason keeps the
            // line below it, where it is the only thing left to read.
            PanelHeader(
                "Data source",
                trailing = { Pill(status.mode.name.lowercase(), tone = status.mode.tone()) },
            )
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
                status.reason,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoldButton(onClick = viewModel::startDeepCapture) { Text("Watch live") }
                GoldOutlinedButton(onClick = viewModel::stopDeepCapture) { Text("Stop capture") }
            }
        }

        Panel {
            PanelHeader("Timeline (last 2 hours)")
            LinkTimeline(samples = samples, events = events)
            // How much there is to look at is a measurement of its own: the
            // figure carries it, the words below only say what was counted.
            Readout(
                value = samples.size.toString(),
                caption = "samples · ${events.size} events",
            )
        }

        Panel {
            PanelHeader("Events")
            if (events.isEmpty()) {
                Text(
                    "No events recorded yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // No rules between the rows: the panel already spaces them, and
                // forty hairlines in one panel read as a table, not a log.
                events.asReversed().take(40).forEach { EventRow(it) }
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
    Panel {
        PanelHeader(
            "Test device",
            trailing = { if (state.running) Pill("running", tone = PillTone.ACCENT) },
        )
        Text(
            "Connection check, codec negotiation, codec cycling and a 3-minute " +
                "soak with deep capture, ending in a summary report.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())

        // The outcome used to be a bracketed marker glued to the front of the
        // sentence, which made "[FAIL]" and "[OK]" scan identically. As a pill
        // it is a state again: same words, but colour and shape carry it.
        state.steps.forEach { step ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val marker = when (step.outcome) {
                    is StepOutcome.Passed -> "OK"
                    is StepOutcome.Warned -> "!"
                    is StepOutcome.Failed -> "FAIL"
                    is StepOutcome.Skipped -> "skipped"
                }
                val tone = when (step.outcome) {
                    is StepOutcome.Passed -> PillTone.ACCENT
                    is StepOutcome.Warned -> PillTone.WARN
                    is StepOutcome.Failed -> PillTone.WARN
                    is StepOutcome.Skipped -> PillTone.NEUTRAL
                }
                Pill(marker, tone = tone)
                Text(
                    "${step.step.title}: ${step.outcome.detail}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        state.report?.let { report ->
            PanelDivider()
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

/**
 * Sampling modes as pill tones. Only the accent says "measuring right now";
 * a stopped poller is a normal resting state, not a fault, so it stays
 * neutral rather than wearing a warning colour it has not earned.
 */
private fun SamplingMode.tone(): PillTone = when (this) {
    SamplingMode.DEEP, SamplingMode.BURST, SamplingMode.ACTIVE -> PillTone.ACCENT
    SamplingMode.BACKGROUND, SamplingMode.STOPPED -> PillTone.NEUTRAL
}
