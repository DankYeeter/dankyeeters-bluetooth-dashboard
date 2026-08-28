package dev.dankyeeter.btdashboard.ui.screens.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dankyeeter.btdashboard.monitor.MonitorGraph
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dankyeeter.btdashboard.monitor.diagnostic.DiagnosticReport
import dev.dankyeeter.btdashboard.monitor.diagnostic.StepOutcome
import dev.dankyeeter.btdashboard.monitor.link.LinkDataSource
import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventType
import dev.dankyeeter.btdashboard.monitor.link.QualityReportAvailability
import dev.dankyeeter.btdashboard.monitor.sampling.SamplingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.dankyeeter.btdashboard.ui.theme.ExplainedHeader
import dev.dankyeeter.btdashboard.ui.theme.GoldButton
import dev.dankyeeter.btdashboard.ui.theme.GoldOutlinedButton
import dev.dankyeeter.btdashboard.ui.theme.Panel
import dev.dankyeeter.btdashboard.ui.theme.PanelDivider
import dev.dankyeeter.btdashboard.ui.theme.PanelHeader
import dev.dankyeeter.btdashboard.ui.theme.Pill
import dev.dankyeeter.btdashboard.ui.theme.PillTone

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

/** How many events the log shows before it starts hiding older ones. */
private const val EVENT_LIMIT = 40

@Composable
fun MonitorScreen(viewModel: MonitorViewModel = viewModel()) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val samples by viewModel.samples.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val bqr by viewModel.bqrAvailability.collectAsStateWithLifecycle()
    val diagnostic by viewModel.diagnostic.collectAsStateWithLifecycle()
    // The live poller is started by this collection and stopped by it: the flow
    // is WhileSubscribed in the ViewModel, and collecting it with the lifecycle
    // means a backgrounded screen stops paying for three dumpsys calls a poll.
    val liveLink by viewModel.liveLink.collectAsStateWithLifecycle()
    val liveInterval by viewModel.liveIntervalMs.collectAsStateWithLifecycle()
    val ldacTuning by viewModel.ldacTuning.collectAsStateWithLifecycle()
    // The chip the Bluetooth tab would light for this same headphone: its
    // profile's stored wish, which is what the next connect will ask for.
    val storedLdacQuality by viewModel.storedLdacQuality.collectAsStateWithLifecycle()
    // Two more lifecycle-bound collections, and the close-up's probe only runs
    // while this one is collected *and* the user has switched it on.
    val overviewTrace by viewModel.overviewTrace.collectAsStateWithLifecycle()
    val closeUpTrace by viewModel.closeUpTrace.collectAsStateWithLifecycle()
    val closeUpEnabled by viewModel.closeUpEnabled.collectAsStateWithLifecycle()

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

        // First panel on the screen because it answers the question people open
        // this screen with — "what is my link doing right now, and did it just
        // drop out" — while everything below it is history and machinery.
        LiveLinkPanel(
            snapshot = liveLink,
            intervalMs = liveInterval,
            onIntervalChange = viewModel::setLiveIntervalMs,
            ldacTuning = ldacTuning,
            onLdacQuality = viewModel::setLdacQuality,
            onDismissLdacMessage = viewModel::dismissLdacMessage,
            storedQuality = storedLdacQuality,
            overviewTrace = overviewTrace,
            closeUpTrace = closeUpTrace,
            closeUpEnabled = closeUpEnabled,
            onCloseUpEnabled = viewModel::setCloseUpEnabled,
        )

        Panel {
            // Three stacked paragraphs used to stand here: what BQR would give,
            // which permission it needs, and the sampler's own reason string.
            // Only one of them answers the question somebody opens this panel
            // with — which source is feeding the timeline — so that is the only
            // one left in the first layer. The rest is behind the question mark.
            ExplainedHeader("Data source", dataSourceExplanation(bqr, status.reason))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The sampling mode is a state, so it wears a pill rather than
                // a sentence — but in words the user can act on, never the
                // enum constant.
                Pill(status.mode.label(), tone = status.mode.tone())
                Text(
                    viewModel.activeSource().readableName()
                        ?.let { "Reading the link through $it." }
                        ?: "Nothing is reading the link right now.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoldButton(onClick = viewModel::startDeepCapture) { Text("Watch live") }
                // Only live while there is a capture to stop. The button clears
                // the deep-capture window and nothing else, so it stays disabled
                // in BURST too: burst is the sampler reacting to an anomaly on
                // its own and expires by itself, and a button that visibly does
                // nothing is worse than one that is honestly greyed out.
                GoldOutlinedButton(
                    onClick = viewModel::stopDeepCapture,
                    enabled = status.mode == SamplingMode.DEEP,
                ) { Text("Stop capture") }
            }
        }

        Panel {
            // No Readout here any more. "418" samples is a number nobody can
            // check against anything they heard; the lanes are the measurement,
            // and how much was collected is a footnote to them.
            ExplainedHeader("Timeline", TIMELINE_EXPLANATION)
            LinkTimeline(samples = samples, events = events)
            Text(
                "${plural(samples.size, "sample")} · ${plural(events.size, "event")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Panel {
            PanelHeader("Events")
            if (events.isEmpty()) {
                Text(
                    "No events yet — connects, dropouts and takeovers appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // No rules between the rows: the panel already spaces them, and
                // forty hairlines in one panel read as a table, not a log.
                val newestFirst = events.asReversed()
                newestFirst.take(EVENT_LIMIT).forEach { EventRow(it) }
                // Silent truncation made the log look complete when it was not.
                if (newestFirst.size > EVENT_LIMIT) {
                    Text(
                        "Showing the $EVENT_LIMIT most recent.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
    // ENCODER_STARVATION joins the loud set because it is the one line in this
    // log that is worth interrupting somebody for: it only appears when the
    // encoder has been starving for seconds on end, and it carries the one-shot
    // forensic capture of what was attached at the time. A line nobody notices
    // would defeat the whole point of taking the capture.
    val loud = event.type == MonitorEventType.TAKEOVER ||
        event.type == MonitorEventType.INTERRUPTION ||
        event.type == MonitorEventType.ENCODER_STARVATION
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

/** The guided device test and its report. */
@Composable
private fun DiagnosticCard(
    state: DiagnosticUiState,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current

    Panel {
        // One noun for the whole feature — "device test" in the header, in the
        // buttons and in the messages. It was "test device", "diagnostic" and
        // "run" in three different places, which reads as three features.
        ExplainedHeader(
            "Device test",
            "It checks the connection, watches the codec negotiate, cycles through the " +
                "codecs the headphone offers, then records for three minutes and summarises.",
        )
        Text(
            "Runs a three-minute check of this headphone's link and reports what it found.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.running) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill("Running", tone = PillTone.ACCENT)
                LinearProgressIndicator(Modifier.weight(1f))
            }
        }

        // The outcome used to be a bracketed marker glued to the front of the
        // sentence, which made "[FAIL]" and "[OK]" scan identically. As a pill
        // it is a state again: same words, but colour and shape carry it.
        state.steps.forEach { step ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val marker = when (step.outcome) {
                    is StepOutcome.Passed -> "Passed"
                    is StepOutcome.Warned -> "Warning"
                    is StepOutcome.Failed -> "Failed"
                    is StepOutcome.Skipped -> "Skipped"
                }
                val tone = when (step.outcome) {
                    is StepOutcome.Passed -> PillTone.ACCENT
                    is StepOutcome.Warned -> PillTone.WARN
                    is StepOutcome.Failed -> PillTone.WARN
                    is StepOutcome.Skipped -> PillTone.NEUTRAL
                }
                Pill(marker, tone = tone)
                Text(
                    // The detail is quoted from below: a codec read that failed
                    // reports the reason it was given, and the layers under this
                    // one work in real addresses. Same boundary rule as the LDAC
                    // tuning message — see [redactAddresses].
                    redactAddresses("${step.step.title}: ${step.outcome.detail}"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        state.report?.let { report ->
            PanelDivider()
            Text(report.verdict, style = MaterialTheme.typography.bodyMedium)
            // A report is the one thing on this screen somebody wants to send
            // to support or paste into a forum thread, and re-typing a verdict
            // from a phone screen is not a plan.
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(report.asPlainText())) },
            ) { Text("Copy report") }
        }

        state.message?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.messageIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            TextButton(onClick = onDismissMessage) { Text("OK") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoldButton(onClick = onRun, enabled = !state.running) { Text("Run device test") }
            if (state.running) {
                GoldOutlinedButton(onClick = onCancel) { Text("Stop device test") }
            }
        }
    }
}

/**
 * What the lanes mean, what the grey means, and how far back the screen looks.
 *
 * It lives here rather than under the drawing because the panel is what the
 * user is asking about, and because a legend printed under every lane would be
 * longer than the chart it explains.
 */
private const val TIMELINE_EXPLANATION =
    "The last two hours on one time axis: whether audio was flowing, which codec and " +
        "sample rate were negotiated, radio strength where a source provides it, and " +
        "connects, disconnects and interruptions. Grey means nothing was recorded then, " +
        "not that the link was silent."

/**
 * The permission story behind the data source, plus the raw reasons.
 *
 * Both reason strings come from the machinery — "not checked", "playing, screen
 * on" — and are useful when something looks wrong, so they are kept, but they
 * are the last thing anybody needs to read and belong behind the question mark.
 */
private fun dataSourceExplanation(
    bqr: QualityReportAvailability,
    samplingReason: String,
): String = buildString {
    append(
        "Bluetooth Quality Report reads packet loss straight from the controller, but " +
            "Android offers it to privileged apps only — without it the link is read " +
            "from the Bluetooth stack's own dump.",
    )
    (bqr as? QualityReportAvailability.Unavailable)?.let {
        append("\n\nAndroid's reason: ${it.reason}.")
    }
    append("\n\nSampling right now: $samplingReason.")
}

/**
 * Source names a listener can place. [LinkDataSource.displayName] is written
 * for the log, and "dumpsys fallback" in a sentence names an Android command
 * rather than anything the user has heard of.
 */
private fun LinkDataSource.readableName(): String? = when (this) {
    LinkDataSource.QUALITY_REPORT -> "Bluetooth Quality Report"
    LinkDataSource.CODEC_API -> "Android's codec status API"
    LinkDataSource.DUMPSYS -> "the Bluetooth stack's own dump"
    LinkDataSource.NONE -> null
}

/** "1 sample" / "2 samples" — never "1 sample(s)". */
private fun plural(count: Int, singular: String): String =
    if (count == 1) "$count $singular" else "$count ${singular}s"

/**
 * What the sampler is doing, said as an activity rather than as its enum name.
 *
 * BURST is a capture the sampler started for itself after an anomaly; from the
 * outside it is the same thing as "watch live" — a faster look at the link —
 * so it wears the same word.
 */
private fun SamplingMode.label(): String = when (this) {
    SamplingMode.DEEP, SamplingMode.BURST -> "Capturing"
    SamplingMode.ACTIVE -> "Watching"
    SamplingMode.BACKGROUND -> "Idle"
    SamplingMode.STOPPED -> "Stopped"
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

/**
 * The report as text somebody can paste somewhere else — the steps included,
 * because the verdict alone loses which check produced which finding.
 *
 * Redacted on the way out, and that is not belt-and-braces: [DiagnosticReport]
 * carries the **raw** address, because it is built from the A2DP profile rather
 * than from the redacted dump, and an unnamed headphone printed it verbatim into
 * the one string on this screen whose whole purpose is to be pasted into a
 * support ticket or a forum thread. Same rule as the live panel's header, which
 * masks for the same reason: the platform's own dumps only redact on a user
 * build, so the app cannot rely on its inputs being redacted for it.
 *
 * Internal rather than private so `MacRedactionInvariantTest` can walk it — the
 * leak was in this function, and a rule nothing checks is a rule that comes back.
 */
internal fun DiagnosticReport.asPlainText(): String = redactAddresses(
    buildString {
        appendLine("Device test — ${deviceName ?: deviceAddress}")
        appendLine("Duration: ${durationMs / 1000} s, $sampleCount samples")
        appendLine()
        steps.forEach { result ->
            val marker = when (result.outcome) {
                is StepOutcome.Passed -> "Passed"
                is StepOutcome.Warned -> "Warning"
                is StepOutcome.Failed -> "Failed"
                is StepOutcome.Skipped -> "Skipped"
            }
            appendLine("$marker — ${result.step.title}: ${result.outcome.detail}")
        }
        appendLine()
        append(verdict)
    },
)
