package dev.dankyeeter.btdashboard.ui.screens.hearing

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.hearing.AudiogramRun
import dev.dankyeeter.btdashboard.hearing.store.AudiogramStore
import dev.dankyeeter.btdashboard.hearing.fit.DeviceFormFactor
import java.text.DateFormat
import java.util.Date
import dev.dankyeeter.btdashboard.ui.theme.ExplainedHeader
import dev.dankyeeter.btdashboard.ui.theme.GoldButton
import dev.dankyeeter.btdashboard.ui.theme.GoldOutlinedButton
import dev.dankyeeter.btdashboard.ui.theme.Panel
import dev.dankyeeter.btdashboard.ui.theme.PanelHeader
import dev.dankyeeter.btdashboard.ui.theme.Pill
import dev.dankyeeter.btdashboard.ui.theme.PillTone
import dev.dankyeeter.btdashboard.hearing.AdjustedReference

/**
 * Hearing-test flow: plain-text intro, optional fit check, a distraction-free
 * full-screen test, the result with the audiogram chart, and the run history
 * with the overlay view.
 */
@Composable
fun HearingTestScreen(viewModel: HearingTestViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (state.phase) {
        HearingPhase.INTRO -> IntroContent(state, viewModel)
        HearingPhase.FIT_CHECK, HearingPhase.TESTING -> RunningContent(state, viewModel)
        HearingPhase.RESULT -> ResultContent(state, viewModel)
        HearingPhase.HISTORY -> HistoryContent(state, viewModel)
    }
}

// --- intro ------------------------------------------------------------------

@Composable
private fun IntroContent(state: HearingUiState, viewModel: HearingTestViewModel) {
    val scroll = rememberScrollState()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.startTest(runAmbientCheck = granted) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Hearing test", style = MaterialTheme.typography.displayMedium)

        Panel {
            // One sentence on the surface; the method lives behind the
            // question mark. Whoever opens this screen wants to run a test,
            // not read about Hughson and Westlake first.
            ExplainedHeader(
                "What happens",
                explanation = "A modified Hughson-Westlake pure-tone test at 250 to 8000 Hz, one " +
                    "ear at a time. The beeps step quieter and louder around your threshold. " +
                    "Some intervals are deliberately silent — pressing during those only makes " +
                    "the result worse, so press only when you actually hear something, however " +
                    "faint.",
            )
            Text(
                "Short beeps, one ear at a time — press whenever you hear one. " +
                    "Six to eight minutes.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Panel {
            ExplainedHeader(
                "Before you start",
                explanation = "Quiet matters because background noise masks exactly the tones " +
                    "being measured. Keep the listening mode you always use — ANC changes what " +
                    "reaches your ear, so a curve measured with it on fits only listening with " +
                    "it on. The volume is locked during the run so every tone keeps the level " +
                    "it was measured at. And one run carries one lapse in attention; the median " +
                    "of three outvotes it. The microphone measures room noise once before the " +
                    "run; nothing is recorded.",
            )
            Text(
                "• A quiet room\n" +
                    "• Your usual listening mode and volume\n" +
                    "• Earphones in as always, then the fit check\n" +
                    "• Three runs",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Panel {
            // Why the two chips lead to different rules is acoustics, and
            // acoustics is not what the person picking a chip is deciding. The
            // caption underneath says what it means for them; the reason is
            // behind the question mark for whoever wonders.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                ExplainedHeader(
                    "Your headphones",
                    explanation = "In-ears change their bass response completely with the seal, " +
                        "so a run with a loose tip measures the tip and not your ear — that is " +
                        "why the fit check is required for them. Over-ears are far less " +
                        "sensitive to placement, so it is optional there, though still worth " +
                        "20 seconds before a first run. The check plays two low tones and " +
                        "compares them against the baseline it stored the first time.",
                    modifier = Modifier.weight(1f),
                )
                if (state.fitCheckPassed) Pill("Fit check ✓", tone = PillTone.ACCENT)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.formFactor == DeviceFormFactor.IN_EAR,
                    onClick = { viewModel.setFormFactor(DeviceFormFactor.IN_EAR) },
                    label = { Text("In-ear / IEM") },
                )
                FilterChip(
                    selected = state.formFactor == DeviceFormFactor.OVER_EAR,
                    onClick = { viewModel.setFormFactor(DeviceFormFactor.OVER_EAR) },
                    label = { Text("Over-ear") },
                )
            }
            Text(
                if (state.formFactor.fitCheckMandatory) {
                    "Fit check required."
                } else {
                    "Fit check optional."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.message?.let { message ->
            Panel {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                // Most messages here are an aborted run, and an abort with only
                // "Dismiss" under it leaves the person who came to test a
                // hearing curve with nothing to press. The way back in is one
                // tap, so it is one button.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoldButton(
                        onClick = {
                            viewModel.dismissMessage()
                            viewModel.startTest()
                        },
                        enabled = !state.busy && !state.fitCheckRequired,
                    ) { Text("Start again") }
                    TextButton(onClick = viewModel::dismissMessage) { Text("Dismiss") }
                }
            }
        }

        Panel {
            PanelHeader("Run it")
            GoldOutlinedButton(
                onClick = viewModel::startFitCheck,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.fitCheckPassed) "Run fit check again" else "Run fit check") }

            GoldButton(
                onClick = {
                    if (viewModel.needsMicPermission) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.startTest()
                    }
                },
                enabled = !state.busy && !state.fitCheckRequired,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start hearing test") }

            if (state.fitCheckRequired) {
                Pill("Run the fit check first.", tone = PillTone.WARN)
            }
        }

        // A button, not a panel. The panel around it had a header saying
        // "Stored runs", a large numeral, the word "runs" under the numeral and
        // then this button with the count in it again - four renderings of one
        // number, and only the button did anything.
        if (state.runs.isNotEmpty()) {
            GoldOutlinedButton(onClick = viewModel::showHistory, modifier = Modifier.fillMaxWidth()) {
                Text("Your runs (${state.runs.size})")
            }
        }
    }
}

// --- running ----------------------------------------------------------------

/**
 * Distraction-free: black background, no navigation, one large response button.
 * The screen deliberately shows neither the current level nor the frequency —
 * that would bias the listener into pressing when a tone is "due".
 *
 * Deliberately outside the app's panel/metal design system, and the only screen
 * that is. Panels, accents and gradients all exist to be looked at; here the
 * listener is supposed to be listening, and a gold-rimmed surface in the corner
 * of the eye is a distraction with a measurable cost — a missed faint tone is a
 * wrong threshold. Black ground, white type, one circular button.
 */
@Composable
private fun RunningContent(state: HearingUiState, viewModel: HearingTestViewModel) {
    BackHandler { viewModel.cancelRun() }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val ear = state.presenting?.ear
                Text(
                    when (state.phase) {
                        HearingPhase.FIT_CHECK -> "Fit check"
                        else -> when (ear) {
                            Ear.LEFT -> "Left ear"
                            Ear.RIGHT -> "Right ear"
                            null -> "Getting ready"
                        }
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                Text(
                    "Press when you hear a beep — even a very faint one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
                val presenting = state.presenting
                if (presenting != null) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // A full run is ten minutes of near-identical screens. Without
                    // a moving number there is no way to tell it apart from a
                    // frozen one — which is exactly how the first build read.
                    // The frequency is safe to show; the level is not, because
                    // knowing it would bias the answer.
                    Text(
                        "Tone ${presenting.frequencyIndex + 1} of ${presenting.frequencyCount}" +
                            " · ${formatHz(presenting.frequencyHz)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                } else {
                    CircularProgressIndicator()
                    Text(
                        "Getting the audio stream ready…",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }

            Button(
                onClick = viewModel::onUserResponse,
                shape = CircleShape,
                modifier = Modifier.size(220.dp),
            ) {
                Text("I hear it", style = MaterialTheme.typography.displayMedium)
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Fades in, fades out, asks nothing. A message that has to be
                // dismissed turns a keypress into a decision, and this one has
                // nothing to decide - it only explains why the volume did not
                // move.
                AnimatedVisibility(
                    visible = state.volumeLockedNotice,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Text(
                        "Volume is locked during the test.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // Named for what it does once tones are running: there is no
                // pause and no resume, so eight minutes of answers go with it.
                // "Cancel test" read like backing out of something that had not
                // started yet.
                TextButton(onClick = viewModel::cancelRun) {
                    Text(
                        if (state.presenting != null) "Stop and discard this run" else "Cancel",
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

// --- result -----------------------------------------------------------------

@Composable
private fun ResultContent(state: HearingUiState, viewModel: HearingTestViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Run finished", style = MaterialTheme.typography.displayMedium)

        state.message?.let { message ->
            Panel {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                // The message says the run is probably too good to trust. Both
                // answers to that are one tap, and neither used to exist here:
                // the run could not be deleted from this screen and the message
                // could not be got rid of at all.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.lastRun?.let { lastRun ->
                        GoldButton(
                            onClick = {
                                viewModel.deleteRun(lastRun.id)
                                viewModel.backToIntro()
                            },
                        ) { Text("Discard this run") }
                    }
                    TextButton(onClick = viewModel::dismissMessage) { Text("Keep it") }
                }
            }
        }

        // Only drawn when there is something in it: an empty panel with a
        // header is a promise of information the screen does not have.
        if (state.lastReliability != null || state.lastRun?.ambientNoiseDbA != null) {
            Panel {
                // Both numbers in here come with a caveat that decides how much
                // weight to give them, and both caveats are a paragraph. The
                // panel shows the figures; the paragraph is one tap away.
                ExplainedHeader(
                    "This run",
                    explanation = "Some intervals during the test are deliberately silent. A " +
                        "press during one of those is a false positive, and a run with several " +
                        "of them has thresholds that look better than your hearing is. The " +
                        "room-noise figure comes from the phone's microphone, which is not a " +
                        "calibrated meter — it is good enough to tell a quiet room from a noisy " +
                        "one and nothing finer.",
                )
                state.lastReliability?.let {
                    Text(it.summary, style = MaterialTheme.typography.bodySmall)
                }
                state.lastRun?.ambientNoiseDbA?.let {
                    Text(
                        "Room noise: roughly ${it.toInt()} dB.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Panel {
            ExplainedHeader(
                "Your hearing",
                explanation = "Each point is the quietest level you still answered at that " +
                    "frequency. A hollow point is one the test could not pin down: the tone was " +
                    "still inaudible at the loudest level the app allows, or still audible at " +
                    "the quietest. Both ends are limits of the measurement, not of your ears.",
            )
            AudiogramChart(runs = state.runs, active = state.audiogram)
            AudiogramLegend()

            // Counted the way the curve is actually built, not by counting
            // every run on the phone.
            //
            // This line used to read state.runs.size, which is every run ever
            // stored for every headphone - so a phone with six runs from two
            // devices announced "6 runs stored — the thick curve is the median"
            // over a curve drawn from three of them, or from none at all when
            // they all belonged to a headphone that was not connected. The
            // number the user is promised has to be the number in the curve.
            val counted = remember(state.runs, state.selectedRunIds, state.currentDeviceKey) {
                AudiogramStore.selectionOf(state.runs, state.selectedRunIds, state.currentDeviceKey).size
            }
            Text(
                when (counted) {
                    0 -> "No run from this headphone counts yet."
                    1 -> "One run in the curve. Two more make the median meaningful."
                    2 -> "Two runs in the curve. One more and it is on solid ground."
                    else -> "Three runs in the curve — the thick line is the per-frequency median."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            state.audiogram?.let { audiogram ->
                val hollow = (audiogram.left + audiogram.right).count { !it.converged }
                if (hollow > 0) {
                    // What is on screen, and nothing about why - the why is in
                    // the header's explanation, where it is read once rather
                    // than after every run.
                    Text(
                        if (hollow == 1) {
                            "One point is drawn hollow."
                        } else {
                            "$hollow points are drawn hollow."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        GoldButton(onClick = viewModel::backToIntro, modifier = Modifier.fillMaxWidth()) {
            Text("Run another test")
        }
        GoldOutlinedButton(onClick = viewModel::showHistory, modifier = Modifier.fillMaxWidth()) {
            Text("Manage runs")
        }
    }
}

// --- history ----------------------------------------------------------------

@Composable
private fun HistoryContent(state: HearingUiState, viewModel: HearingTestViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Your runs", style = MaterialTheme.typography.displayMedium)

        Panel {
            ExplainedHeader(
                "All runs overlaid",
                // The "one run carries a lapse in attention, three outvote it"
                // half of this already stands on the intro screen, under
                // "Before you start". Said twice it is not twice as convincing;
                // what is left here is the part only this screen answers, which
                // is why the cap is three and not more.
                explanation = "Every run is drawn thin; the thick curve is the median of the " +
                    "runs you chose, and that median is what the equaliser corrects for. " +
                    "Three at most — averaging a dozen sessions from different weeks would " +
                    "blur the very change you would want to see. Swap which three count as " +
                    "often as you like; nothing is lost by trying.",
            )
            Text(
                "The thick curve is the median of the runs you chose — up to three count.",
                style = MaterialTheme.typography.bodyMedium,
            )
            AudiogramChart(runs = state.runs, active = state.audiogram)
            AudiogramLegend()
        }

        // Which runs count, resolved the same way the store resolves it: an
        // empty selection means the three newest, so the screen never shows a
        // different set than the equaliser is using — and only runs measured
        // through the connected headphone are in the running at all.
        val counted = remember(state.runs, state.selectedRunIds, state.currentDeviceKey) {
            AudiogramStore.selectionOf(state.runs, state.selectedRunIds, state.currentDeviceKey)
                .map { it.id }.toSet()
        }
        val full = counted.size >= AudiogramStore.MAX_SELECTED

        state.runs.sortedByDescending { it.timestampMillis }.forEach { run ->
            // A run from another headphone is shown but cannot be chosen: the
            // curve it holds corrects for a driver that is not on the head
            // right now. It comes back to life the moment its device does.
            val foreign = run.deviceAddressHash != null &&
                state.currentDeviceKey != null &&
                run.deviceAddressHash != state.currentDeviceKey
            RunRow(
                run = run,
                counted = run.id in counted,
                // A fourth is refused rather than pushing one of the others
                // out: which three count decides what the EQ does, and a set
                // that rearranges itself behind your back is not a choice.
                selectable = !foreign && (run.id in counted || !full),
                foreign = foreign,
                onCountedChange = { viewModel.setRunSelected(run.id, it) },
                onDelete = { viewModel.deleteRun(run.id) },
            )
        }
        if (full) {
            Text(
                "Three runs are in use — take one out to put another in.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.runs.isNotEmpty()) {
            // Asked first, because this one takes more than the runs with it.
            //
            // Every other destructive control here removes a single run, which
            // is a twenty-minute mistake at worst. This one also takes the
            // generated compensation curve, which cannot be rebuilt without
            // measuring three runs again - and it used to fire on the first
            // tap, one thumb-width from the Back button.
            var confirming by rememberSaveable { mutableStateOf(false) }

            TextButton(onClick = { confirming = true }) { Text("Delete all runs") }

            if (confirming) {
                AlertDialog(
                    onDismissRequest = { confirming = false },
                    title = { Text("Delete all runs?") },
                    text = {
                        Text(
                            "Every stored run is removed, and the generated " +
                                "${AdjustedReference.NAME} curve goes with them. Saved EQ " +
                                "presets keep the bands they already have.",
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirming = false
                                viewModel.deleteAllRuns()
                            },
                        ) { Text("Delete all") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirming = false }) { Text("Cancel") }
                    },
                )
            }
        }
        GoldButton(onClick = viewModel::backToIntro, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun RunRow(
    run: AudiogramRun,
    counted: Boolean,
    selectable: Boolean,
    foreign: Boolean,
    onCountedChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    // One run per panel, with its timestamp as the eyebrow: the date is what
    // tells two runs apart, and everything else on the row is about that date.
    // A foreign-device run fades as a whole — the pill alone reads as state,
    // the fade reads as "not yours right now", which is the actual situation.
    Panel(contentPadding = 16, modifier = Modifier.alpha(if (foreign) 0.5f else 1f)) {
        PanelHeader(
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(run.timestampMillis)),
            trailing = {
                Pill(
                    when {
                        foreign -> "Other device"
                        counted -> "In the curve"
                        else -> "Not used"
                    },
                    tone = if (counted && !foreign) PillTone.ACCENT else PillTone.NEUTRAL,
                )
            },
        )
        Text(
            run.deviceName ?: "No device recorded",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Use for the curve",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(
                checked = counted,
                onCheckedChange = onCountedChange,
                enabled = selectable,
            )
        }
        // Delete sits beside the counts rather than in the header's trailing
        // slot: that slot is sized for a pill, and a full button next to an
        // 11 sp eyebrow makes the header taller than the row it labels.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The point counts are gone: every run measures the same eight
            // frequencies per ear, so "8 left / 8 right points" was printed on
            // every row of every list and told nobody anything. The room noise
            // genuinely differs between runs, which is why it is what remains.
            Text(
                run.ambientNoiseDbA?.let { "Room ≈ ${it.toInt()} dB" }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GoldOutlinedButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

/** 1000 -> "1 kHz", 250 -> "250 Hz". Locale-free: the app is English-only. */
private fun formatHz(hz: Int): String =
    if (hz >= 1000 && hz % 1000 == 0) "${hz / 1000} kHz" else "$hz Hz"
