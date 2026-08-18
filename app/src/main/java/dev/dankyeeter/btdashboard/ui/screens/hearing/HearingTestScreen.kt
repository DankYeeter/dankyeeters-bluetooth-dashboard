package dev.dankyeeter.btdashboard.ui.screens.hearing

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.hearing.AudiogramRun
import dev.dankyeeter.btdashboard.hearing.fit.DeviceFormFactor
import java.text.DateFormat
import java.util.Date

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
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Hearing test", style = MaterialTheme.typography.headlineMedium)

        Text(
            "This is a modified Hughson-Westlake pure-tone test at 250 to 8000 Hz, one ear at a time. " +
                "It takes about six to eight minutes for both ears. You will hear short beeps that get " +
                "quieter and louder; press the big button whenever you think you hear one, even faintly. " +
                "Some intervals are deliberately silent — pressing during those only makes the result worse.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Before you start:\n" +
                "• Sit in a quiet room. Background noise masks the quietest tones.\n" +
                "• Use the listening mode you normally use (ANC on, transparency or off) and keep it " +
                "for the whole test — and for the EQ afterwards.\n" +
                "• Set the media volume to your usual listening level. It is locked while the test runs.\n" +
                "• Put the earphones in as you always do, then run the fit check.\n" +
                "• Do three or more runs. The app uses the per-frequency median of your runs.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "This is audiometry-inspired consumer calibration, not a clinical hearing test, and it " +
                "cannot diagnose anything. If you suspect hearing loss, see an audiologist.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Your headphones", style = MaterialTheme.typography.titleMedium)
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
                "In-ears change their bass response completely with the seal, so the fit check is required."
            } else {
                "Over-ears are less sensitive to placement, so the fit check is optional here."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.message?.let { message ->
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = viewModel::dismissMessage) { Text("Dismiss") }
                }
            }
        }

        OutlinedButton(
            onClick = viewModel::startFitCheck,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.fitCheckPassed) "Fit check ✓ — run again" else "Run fit check (about 20 s)") }

        Button(
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
            Text(
                "Run the fit check first.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            "The microphone is used once, before the run, to estimate the room noise. Nothing is " +
                "recorded or stored, and the app has no internet permission.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.runs.isNotEmpty()) {
            OutlinedButton(onClick = viewModel::showHistory, modifier = Modifier.fillMaxWidth()) {
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
                if (state.presenting != null) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    CircularProgressIndicator()
                }
            }

            Button(
                onClick = viewModel::onUserResponse,
                shape = CircleShape,
                modifier = Modifier.size(220.dp),
            ) {
                Text("I hear it", style = MaterialTheme.typography.headlineSmall)
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.volumeLockedNotice) {
                    Text(
                        "Volume is locked during the test.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = viewModel::dismissVolumeLockedNotice) { Text("OK") }
                }
                TextButton(onClick = viewModel::cancelRun) {
                    Text("Cancel test", color = Color.White.copy(alpha = 0.7f))
                }
            }
        }
    }
}

// --- result -----------------------------------------------------------------

@Composable
private fun ResultContent(state: HearingUiState, viewModel: HearingTestViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Run finished", style = MaterialTheme.typography.headlineMedium)
        state.message?.let {
            Card { Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
        }
        state.lastReliability?.let {
            Text(it.summary, style = MaterialTheme.typography.bodySmall)
        }
        state.lastRun?.ambientNoiseDbA?.let {
            Text(
                "Room noise before the run: about ${it.toInt()} dB (uncalibrated estimate).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AudiogramChart(runs = state.runs, active = state.audiogram)
        AudiogramLegend()

        Text(
            when (state.runs.size) {
                1 -> "One run stored. Two more make the median meaningful."
                2 -> "Two runs stored. One more and the median is on solid ground."
                else -> "${state.runs.size} runs stored — the thick curve is the per-frequency median."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        state.audiogram?.let { audiogram ->
            val hollow = (audiogram.left + audiogram.right).count { !it.converged }
            if (hollow > 0) {
                Text(
                    "$hollow point(s) did not converge and are drawn hollow: the tone was either " +
                        "inaudible at the loudest allowed level or still audible at the quietest one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(onClick = viewModel::backToIntro, modifier = Modifier.fillMaxWidth()) {
            Text("Run another test")
        }
        OutlinedButton(onClick = viewModel::showHistory, modifier = Modifier.fillMaxWidth()) {
            Text("Manage runs")
        }
    }
}

// --- history ----------------------------------------------------------------

@Composable
private fun HistoryContent(state: HearingUiState, viewModel: HearingTestViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Your runs", style = MaterialTheme.typography.headlineMedium)
        Text(
            "All runs are overlaid; the thick curve is the median that feeds the compensation. " +
                "Delete a run that went wrong (a bad seal, a noisy room, a distracted moment) and retake it.",
            style = MaterialTheme.typography.bodyMedium,
        )

        AudiogramChart(runs = state.runs, active = state.audiogram)
        AudiogramLegend()

        state.runs.sortedByDescending { it.timestampMillis }.forEach { run ->
            RunRow(run = run, onDelete = { viewModel.deleteRun(run.id) })
        }

        if (state.runs.isNotEmpty()) {
            TextButton(onClick = viewModel::deleteAllRuns) { Text("Delete all runs") }
        }
        Button(onClick = viewModel::backToIntro, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun RunRow(run: AudiogramRun, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(run.timestampMillis)),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "${run.left.size} left / ${run.right.size} right points" +
                    (run.ambientNoiseDbA?.let { " · room ≈ ${it.toInt()} dB" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onDelete, label = { Text("Delete") })
            }
        }
    }
}
