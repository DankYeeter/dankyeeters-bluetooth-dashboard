package dev.dankyeeter.btdashboard.ui.screens.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.dankyeeter.btdashboard.monitor.link.live.LdacState
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.ObservationRun
import dev.dankyeeter.btdashboard.monitor.link.live.RunEnd
import dev.dankyeeter.btdashboard.monitor.link.live.hasReadableRate
import dev.dankyeeter.btdashboard.ui.theme.ExplainedBlock
import dev.dankyeeter.btdashboard.ui.tuning.LdacQuality
import kotlin.time.Duration.Companion.milliseconds

/**
 * The observation run: "can I pin a step, and which one?" answered with
 * figures the user reads for themselves (`GOAL.md` AK-17, `UI_SPEC.md` T-039).
 *
 * Its own section, under the graphs: the graphs and the step row speak about
 * sixty seconds, and a run speaks about as long as the user let it count. Every
 * figure names that span in its own sentence, so no line can be quoted without
 * it. The section counts and does not judge — no colour, no symbol, no advice.
 *
 * Exactly one of five states is on screen: rate not readable, off, collecting,
 * counting with figures, ended with figures kept.
 */
@Composable
internal fun ObservationRunSection(
    snapshot: LinkLiveSnapshot,
    state: ObservationRunUi,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onThreshold: (Long) -> Unit,
    onNoticeContinue: () -> Unit,
    onNoticeDismiss: () -> Unit,
) {
    val run = state.run
    val lowest = run?.lowestKbps?.takeUnless { run.isCollecting }

    ExplainedBlock(RUN_LABEL, runExplanation(run)) { toggle ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                headline(run, snapshot, lowest),
                style = MaterialTheme.typography.bodyMedium,
                // Wraps rather than pushing the question mark off the row.
                modifier = Modifier.weight(1f, fill = false),
            )
            toggle()
        }
        if (run != null && lowest != null) {
            RunFigures(run, lowest, state.thresholdQuality, onThreshold)
        }
        when {
            run != null && run.end == null ->
                FilterChip(selected = true, onClick = onStop, label = { Text("Stop") })

            // Ended run or none: a run started on an unreadable rate would end
            // at its first reading, blaming a rate it never saw.
            snapshot.hasReadableRate ->
                FilterChip(selected = false, onClick = onStart, label = { Text("Start") })
        }
    }

    if (state.startNoticeShown) StartNotice(onNoticeContinue, onNoticeDismiss)
}

/** The section's first line: the state, or while counting, the lowest reading. */
private fun headline(run: ObservationRun?, snapshot: LinkLiveSnapshot, lowest: Int?): String = when {
    run == null && !snapshot.hasReadableRate -> "Observation run — the rate cannot be read on this link."
    run == null -> "Observation run — off."
    else -> run.end?.let { endLine(it, run.observedMs) }
        ?: lowest?.let { lowestLine(it, run.observedMs) }
        ?: ("Collecting — ${plural(run.readings.toLong(), "reading")}, " +
            "${formatSpan(run.observedMs)} observed. " +
            "Figures appear at ${formatSpan(ObservationRun.RUN_MIN_OBSERVED_MS)} observed.")
}

/** The three figures, each with its span, and the gaps they leave out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunFigures(
    run: ObservationRun,
    lowest: Int,
    thresholdQuality: Long,
    onThreshold: (Long) -> Unit,
) {
    // The run's own ladder, not the link's now: an ended run stays as it was measured.
    val sampleRateHz = run.sampleRateHz
    val observed = formatSpan(run.observedMs)
    val threshold = LdacState.nominalKbps(LdacState.modeOf(thresholdQuality), sampleRateHz) ?: return
    val quiet = MaterialTheme.typography.bodySmall
    val quietColor = MaterialTheme.colorScheme.onSurfaceVariant

    // While counting, the lowest reading is the headline; once ended, the
    // headline is the reason and the lowest reading follows it.
    if (run.end != null) Text(lowestLine(lowest, run.observedMs), style = MaterialTheme.typography.bodyMedium)

    Text(
        "At or above $threshold kbps ${run.atOrAbovePercent(threshold)} % of the time, $observed observed.",
        style = MaterialTheme.typography.bodyMedium,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        THRESHOLD_QUALITIES.forEach { quality ->
            FilterChip(
                selected = quality == thresholdQuality,
                onClick = { onThreshold(quality) },
                label = { Text(LdacQuality.chipLabel(quality, sampleRateHz)) },
            )
        }
    }

    Text("Time at each step, $observed observed:", style = quiet, color = quietColor)
    val steps = run.stepDwell
    steps.take(RUN_STEP_ROWS_MAX).forEach { step ->
        Text("${step.kbps} kbps — ${formatSpan(step.ms)}", style = quiet, color = quietColor)
    }
    steps.drop(RUN_STEP_ROWS_MAX).takeIf { it.isNotEmpty() }?.let { shorter ->
        Text(
            "${plural(shorter.size.toLong(), "shorter step")} — ${formatSpan(shorter.sumOf { it.ms })}",
            style = quiet,
            color = quietColor,
        )
    }
    run.movingMs.takeIf { it > 0 }?.let { moving ->
        Text("moving between steps — ${formatSpan(moving)}", style = quiet, color = quietColor)
    }

    if (run.gapCount > 0) {
        Text(
            "Not observed for ${formatSpan(run.gapMs)} in ${plural(run.gapCount.toLong(), "break")}; " +
                "that time is in no figure here.",
            style = quiet,
            color = quietColor,
        )
    }
}

/** The one-time notice before the very first run (`UI_SPEC.md` T-039, decision 5). */
@Composable
private fun StartNotice(onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("This screen must stay open") },
        text = { Text(LEAVING_DISCARDS_THE_RUN) },
        confirmButton = { TextButton(onClick = onContinue) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

internal fun lowestLine(lowestKbps: Int, observedMs: Long): String =
    "Lowest reading $lowestKbps kbps, ${formatSpan(observedMs)} observed."

/** The first line of an ended run: why it ended, and how much it saw. */
internal fun endLine(end: RunEnd, observedMs: Long): String {
    val observed = "${formatSpan(observedMs)} observed."
    return when (end) {
        RunEnd.STOPPED -> "Run stopped. $observed"
        RunEnd.DISCONNECTED -> "Run ended when the headphone disconnected. $observed"
        RunEnd.QUALITY_CHANGED -> "Run ended when the LDAC quality changed. $observed"
        RunEnd.CODEC_CHANGED -> "Run ended when the codec changed. $observed"
        RunEnd.READING_GAP ->
            "Run ended after ${formatSpan(ObservationRun.RUN_GAP_MAX_MS)} without a reading. $observed"
        RunEnd.PAUSED ->
            "Run ended after ${formatSpan(ObservationRun.RUN_GAP_MAX_MS)} of paused playback. $observed"
        RunEnd.RATE_UNREADABLE -> "Run ended when the rate stopped being readable. $observed"
    }
}

/**
 * A span as the run may state it: seconds below 90 s, whole minutes from there,
 * hours and minutes from an hour. Minutes are cut, not rounded up, so a span is
 * never stated longer than it was; and no span over 90 s carries seconds, a
 * precision a 1-to-5 s cadence does not have.
 */
internal fun formatSpan(ms: Long): String = ms.milliseconds.toComponents { hours, minutes, _, _ ->
    when {
        ms < SECONDS_SHOWN_BELOW_MS -> "${ms.milliseconds.inWholeSeconds} s"
        hours == 0L -> "$minutes min"
        else -> "$hours h $minutes min"
    }
}

/** The second layer: four sentences, and a fifth only when the run could not keep all its steps apart. */
private fun runExplanation(run: ObservationRun?): String = listOfNotNull(
    LEAVING_DISCARDS_THE_RUN,
    "The lowest figure is the lowest single reading — between two readings nothing is " +
        "observed, so the encoder may have gone lower.",
    "Time that crossed the threshold between two readings is counted against the share, " +
        "not for it, so the share is a lower bound.",
    "Time nobody observed is in no figure here.",
    run?.takeIf { it.levelLimitReached }?.let {
        "There were more steps than this run can separate; time at the extra ones counts " +
            "only as observed time."
    },
).joinToString(" ")

private const val RUN_LABEL = "Observation run"

/** Sentence 1 of the second layer and the start notice's text — one string, two places. */
private const val LEAVING_DISCARDS_THE_RUN =
    "A run counts only while this screen is open; leaving it discards the run, and nothing is recorded."

/**
 * The share's threshold chips: the pinnable steps only (decision 4). A share
 * over a rate nobody can pin answers no action; adaptive has no single rate.
 */
private val THRESHOLD_QUALITIES = LdacQuality.pinnable - LdacQuality.ADAPTIVE

/**
 * Step rows before the rest are summed into one: four, the number of ABR steps
 * measured in one session (330/396/492/660), so the measured case fits whole.
 */
private const val RUN_STEP_ROWS_MAX = 4

/** Below this a span is given in seconds, from it in whole minutes (`UI_SPEC.md` T-039). */
private const val SECONDS_SHOWN_BELOW_MS = 90_000L
