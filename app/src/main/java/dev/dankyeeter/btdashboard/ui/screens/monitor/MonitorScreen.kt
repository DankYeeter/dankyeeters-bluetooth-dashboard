package dev.dankyeeter.btdashboard.ui.screens.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dankyeeter.btdashboard.monitor.MonitorGraph
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dankyeeter.btdashboard.monitor.link.LinkDataSource
import dev.dankyeeter.btdashboard.monitor.link.LinkQualitySample
import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.QualityReportAvailability
import dev.dankyeeter.btdashboard.monitor.sampling.SamplingMode
import dev.dankyeeter.btdashboard.ui.screens.devices.SettingsRestoreBanner
import dev.dankyeeter.btdashboard.ui.theme.ExplainedHeader
import dev.dankyeeter.btdashboard.ui.theme.GoldButton
import dev.dankyeeter.btdashboard.ui.theme.GoldOutlinedButton
import dev.dankyeeter.btdashboard.ui.theme.Panel
import dev.dankyeeter.btdashboard.ui.theme.Pill
import dev.dankyeeter.btdashboard.ui.theme.PillTone

@Composable
fun MonitorScreen(viewModel: MonitorViewModel = viewModel()) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val samples by viewModel.samples.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val bqr by viewModel.bqrAvailability.collectAsStateWithLifecycle()
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
    val observationRun by viewModel.observationRun.ui.collectAsStateWithLifecycle()
    val comparison by viewModel.comparison.ui.collectAsStateWithLifecycle()

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
            observationRun = { snapshot ->
                val control = viewModel.observationRun
                ObservationRunSection(
                    snapshot,
                    observationRun,
                    onStart = control::onStartTapped,
                    onStop = control::onStop,
                    onThreshold = control::onThreshold,
                    onNoticeContinue = control::onNoticeContinue,
                    onNoticeDismiss = control::onNoticeDismiss,
                )
            },
            comparison = { snapshot ->
                val control = viewModel.comparison
                ComparisonSection(
                    snapshot,
                    comparison,
                    onMeasure = control::onMeasure,
                    onCompare = control::onCompare,
                    onCheck = control::onCheck,
                    onReset = control::onReset,
                    // D2's banner and its one flow, not a second way back (AK-T047-13).
                    restoreButton = { SettingsRestoreBanner() },
                )
            },
        )

        DataSourcePanel(
            mode = status.mode,
            source = viewModel.activeSource(),
            bqr = bqr,
            samplingReason = status.reason,
            onWatchLive = viewModel::startDeepCapture,
            onStopCapture = viewModel::stopDeepCapture,
        )

        TimelinePanel(samples = samples, events = events)

        EventLogPanel(events)
    }
}

/**
 * Which source is feeding the timeline, and the capture controls.
 *
 * Three stacked paragraphs used to stand here: what BQR would give, which
 * permission it needs, and the sampler's own reason string. Then two — a state
 * pill and a sentence wrapped around the source name. The sentence is gone too:
 * "Reading the link through the Bluetooth stack's own dump." spends fourteen
 * words on one noun, and the pill beside it already says whether anything is
 * reading. What is left is an instrument reading: state, source, controls. The
 * permission story is behind the question mark, where somebody goes when a row
 * above is missing.
 *
 * Internal rather than private, and taking values rather than the ViewModel, so
 * that the audit that removed those paragraphs can be pinned without composing
 * the whole screen — which starts a Bluetooth-backed ViewModel and fails
 * asynchronously in a test JVM.
 */
@Composable
internal fun DataSourcePanel(
    mode: SamplingMode,
    source: LinkDataSource,
    bqr: QualityReportAvailability,
    samplingReason: String,
    onWatchLive: () -> Unit,
    onStopCapture: () -> Unit,
) {
    Panel {
        ExplainedHeader("Data source", dataSourceExplanation(bqr, samplingReason))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The sampling mode is a state, so it wears a pill rather than a
            // sentence — but in words the user can act on, never the enum
            // constant.
            Pill(mode.label(), tone = mode.tone())
            Text(
                source.shortName(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoldButton(onClick = onWatchLive) { Text("Watch live") }
            // Only live while there is a capture to stop. The button clears the
            // deep-capture window and nothing else, so it stays disabled in
            // BURST too: burst is the sampler reacting to an anomaly on its own
            // and expires by itself, and a button that visibly does nothing is
            // worse than one that is honestly greyed out.
            GoldOutlinedButton(
                onClick = onStopCapture,
                enabled = mode == SamplingMode.DEEP,
            ) { Text("Stop capture") }
        }
    }
}

/**
 * The two-hour history.
 *
 * No `Readout` here any more, and no tally under the chart either.
 * "418 samples · 12 events" is a count of the app's own bookkeeping: nobody can
 * check it against anything they heard, and the lanes already show how much was
 * recorded — by how much of the axis is not grey.
 */
@Composable
internal fun TimelinePanel(
    samples: List<LinkQualitySample>,
    events: List<MonitorEvent>,
) {
    Panel {
        ExplainedHeader("Timeline", TIMELINE_EXPLANATION)
        LinkTimeline(samples = samples, events = events)
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
 * Source names a listener can place, in the fewest words that still name it.
 *
 * [LinkDataSource.displayName] is written for the log, and "dumpsys fallback"
 * names an Android command rather than anything the user has heard of. These
 * are the same three sources said as labels rather than as sentences — the row
 * they sit in is a reading, not a paragraph, and the full permission story is
 * behind the panel's question mark where it belongs.
 */
private fun LinkDataSource.shortName(): String = when (this) {
    LinkDataSource.QUALITY_REPORT -> "Quality Report"
    LinkDataSource.CODEC_API -> "Codec API"
    LinkDataSource.DUMPSYS -> "Bluetooth stack dump"
    LinkDataSource.NONE -> "no source"
}

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
