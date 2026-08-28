package dev.dankyeeter.btdashboard.ui.screens.preference

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.dankyeeter.btdashboard.hearing.preference.FinalCheck
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceLabelSource
import dev.dankyeeter.btdashboard.hearing.preference.PreferencePool
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceProfile
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceRun
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceShelf
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceVerdict
import dev.dankyeeter.btdashboard.ui.theme.ExplainedHeader
import dev.dankyeeter.btdashboard.ui.theme.GoldButton
import dev.dankyeeter.btdashboard.ui.theme.GoldOutlinedButton
import dev.dankyeeter.btdashboard.ui.theme.Panel
import dev.dankyeeter.btdashboard.ui.theme.PanelHeader
import dev.dankyeeter.btdashboard.ui.theme.Pill
import dev.dankyeeter.btdashboard.ui.theme.PillTone
import dev.dankyeeter.btdashboard.ui.theme.Readout
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

/**
 * The preference test's full-screen phases.
 *
 * Rendered instead of the hearing-test screen while a test is running, the same
 * way that screen swaps its own content by phase. The card that starts it all is
 * [PreferenceTestCard], which sits among the other panels on Sound Profiling.
 */
@Composable
internal fun PreferenceTestContent(
    state: PreferenceUiState,
    actions: PreferenceTestActions,
) {
    when (state.phase) {
        PreferencePhase.IDLE -> Unit
        PreferencePhase.RUNNING -> RunningContent(state, actions)
        PreferencePhase.RUN_RESULT -> RunResultContent(state, actions)
        PreferencePhase.FINAL_CHECK -> FinalCheckContent(state, actions)
        PreferencePhase.RESULT -> ResultContent(state, actions)
    }
}

// --- the card ---------------------------------------------------------------

/**
 * The entry point, beside the hearing test.
 *
 * The first layer is one honest sentence about what this measures, because the
 * one thing a reader must not conclude from a card next to a hearing test is
 * that this is a second hearing test. It is not a measurement of anything about
 * the ears; it is a record of what somebody said they liked.
 */
@Composable
internal fun PreferenceTestCard(
    state: PreferenceUiState,
    actions: PreferenceTestActions,
) {
    val stored = state.stored
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            ExplainedHeader(
                "Preference test",
                explanation = "This measures taste, not hearing: it moves a bass and a " +
                    "treble shelf and asks which you prefer, so there is no right answer. " +
                    "Both sides are matched for loudness, because louder always sounds " +
                    "better at first.",
                modifier = Modifier.weight(1f),
            )
            if (stored != null) Pill("stored", tone = PillTone.ACCENT)
        }
        Text(
            "Ten quick A/B choices over music you are already playing. Needs something " +
                "playing to work.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (stored != null) {
            Text(
                summaryLine(stored),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        GoldButton(
            onClick = actions::start,
            enabled = state.canStart,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (stored == null) "Start preference test" else "Add another song")
        }
        if (stored != null) {
            GoldOutlinedButton(onClick = actions::openResult, modifier = Modifier.fillMaxWidth()) {
                Text("Your preference curve")
            }
        }
        if (!state.canStart) {
            Pill("Connect your headphones first.", tone = PillTone.WARN)
        }

        // Named, not hidden. A curve belonging to another pair is real data the
        // user made, and a card that silently showed nothing would read as one
        // that lost it.
        state.otherProfiles.forEach { other ->
            Text(
                "Stored for ${other.displayDeviceName} — connect that pair to use or edit it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        state.message?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = actions::dismissMessage) { Text("Dismiss") }
        }
    }
}

private fun summaryLine(profile: PreferenceProfile): String {
    val aggregate = profile.aggregate
    val songs = if (aggregate.runCount == 1) "1 song" else "${aggregate.runCount} songs"
    return "$songs · ${shelfLine(profile)} · ${profile.displayDeviceName}" +
        if (profile.handAdjusted) " · adjusted by hand" else ""
}

private fun shelfLine(profile: PreferenceProfile): String =
    "bass %+.1f dB, treble %+.1f dB".format(profile.candidate.bassDb, profile.candidate.trebleDb)

// --- running ----------------------------------------------------------------

/**
 * One comparison, distraction-free.
 *
 * Black ground and no panels, for the reason the hearing test's running screen
 * gives: the listener is supposed to be listening, and the app's gold rims in
 * the corner of the eye cost attention that a judgement this fine cannot spare.
 *
 * A and B switch the sound and select; the button underneath commits. Two steps
 * rather than one on purpose — a single tap that both switched and answered
 * would record a preference for a curve the listener had not heard yet.
 */
@Composable
private fun RunningContent(state: PreferenceUiState, actions: PreferenceTestActions) {
    BackHandler { actions.requestCancel() }
    val trial = state.trial

    Box(Modifier.fillMaxSize().background(Color.Black).padding(24.dp)) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Which sounds better?",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                Text(
                    "Tap A and B to switch. Take the one you would rather keep listening to.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
                if (trial != null) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Comparison ${trial.index + 1} of ${trial.total}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                AbButton("A", state.playing == AbSlot.A) { actions.play(AbSlot.A) }
                AbButton("B", state.playing == AbSlot.B) { actions.play(AbSlot.B) }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GoldButton(onClick = actions::confirm, modifier = Modifier.fillMaxWidth()) {
                    Text("Prefer ${if (state.playing == AbSlot.A) "A" else "B"}")
                }
                TextButton(onClick = actions::noDifference, modifier = Modifier.fillMaxWidth()) {
                    Text("No difference", color = Color.White.copy(alpha = 0.85f))
                }
                TextButton(onClick = actions::requestCancel) {
                    Text("Stop this song", color = Color.White.copy(alpha = 0.6f))
                }
            }
        }
    }

    if (state.confirmingCancel) {
        AlertDialog(
            onDismissRequest = actions::dismissDialog,
            title = { Text("Stop this song's test?") },
            text = {
                Text(
                    "The answers you have given for this song are lost. Songs you already " +
                        "finished stay.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = actions::dismissDialog) { Text("Keep going") } },
            dismissButton = { TextButton(onClick = actions::confirmCancel) { Text("Stop") } },
        )
    }
}

@Composable
private fun AbButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        modifier = Modifier.size(132.dp).testTag("preference-$label"),
        colors = if (selected) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
        },
    ) {
        Text(label, style = MaterialTheme.typography.displayMedium)
    }
}

// --- one song's result ------------------------------------------------------

@Composable
private fun RunResultContent(state: PreferenceUiState, actions: PreferenceTestActions) {
    BackHandler { actions.finish() }
    val result = state.runResult

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("This song", style = MaterialTheme.typography.displayMedium)

        Panel {
            PanelHeader("What you picked")
            Readout(
                result?.let { "%+.1f / %+.1f dB".format(it.candidate.bassDb, it.candidate.trebleDb) }
                    ?: "—",
                caption = "bass shelf / treble shelf",
            )
            result?.let {
                Text(
                    runConsistencyLine(it.consistency, it.repeats),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Panel {
            PanelHeader("What was playing")
            Text(
                labelSourceLine(state.runLabel.source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.typedLabel.ifEmpty { "" },
                onValueChange = actions::setRunLabel,
                label = { Text("Name this song") },
                placeholder = { Text(state.runLabel.text) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(RUN_LABEL_TAG),
            )
        }

        Panel {
            PanelHeader("Next")
            Text(
                nextStepLine(state.runs.size + 1, state.poolFull),
                style = MaterialTheme.typography.bodyMedium,
            )
            GoldButton(onClick = actions::addAnotherSong, modifier = Modifier.fillMaxWidth()) {
                Text("Add another song")
            }
            GoldOutlinedButton(onClick = actions::finish, modifier = Modifier.fillMaxWidth()) {
                Text("Finish")
            }
        }
    }

    HandAdjustmentDialog(state, actions)
}

/**
 * Why another song is worth two more minutes, in one line.
 *
 * Counting up rather than down: "your second song" is a fact about what just
 * happened, where "one more to go" would be an instruction, and there is no
 * number of songs this app is entitled to demand.
 */
private fun nextStepLine(songsAfterThis: Int, poolFull: Boolean): String = when {
    poolFull ->
        "That is ${PreferencePool.MAX_RUNS} songs — the most kept. Another one pushes the " +
            "oldest out."
    songsAfterThis < PreferencePool.RECOMMENDED_RUNS ->
        "That is song $songsAfterThis. Every track's mastering pulls the answers its own way, " +
            "so ${PreferencePool.RECOMMENDED_RUNS} different songs is where the result stops " +
            "being about one recording."
    else ->
        "That is $songsAfterThis songs — enough to be steady. More still helps if your taste " +
            "changes with the music."
}

private fun labelSourceLine(source: PreferenceLabelSource): String = when (source) {
    PreferenceLabelSource.TRACK -> "Read from the track that was playing."
    PreferenceLabelSource.APP ->
        "The phone will not hand over a track title without notification access, which this " +
            "app does not ask for — so this is the player and the time. Rename it if you like."
    PreferenceLabelSource.MANUAL -> "Your own name for it."
    PreferenceLabelSource.NONE ->
        "Nothing was readable about what was playing, so this run is filed under its time."
}

private fun runConsistencyLine(consistency: Double, repeats: Int): String = when {
    repeats == 0 -> "Nothing was decisive enough to double-check, so this song has no steadiness score."
    consistency >= 0.99 -> "You gave the same answer both times it was checked."
    consistency >= PreferencePool.CONSISTENCY_THRESHOLD -> "Mostly the same answer when checked."
    else -> "Your answers moved when checked — this song counts for less in the total."
}

// --- the blind check --------------------------------------------------------

@Composable
private fun FinalCheckContent(state: PreferenceUiState, actions: PreferenceTestActions) {
    BackHandler { actions.skipFinalCheck() }

    Box(Modifier.fillMaxSize().background(Color.Black).padding(24.dp)) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("One last check", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text(
                    "One of these is the curve your songs asked for. The other is no change at " +
                        "all, at the same loudness. You are not told which.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                AbButton("A", state.playing == AbSlot.A) { actions.playFinalCheck(AbSlot.A) }
                AbButton("B", state.playing == AbSlot.B) { actions.playFinalCheck(AbSlot.B) }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GoldButton(
                    onClick = { actions.answerFinalCheck(state.playing) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Prefer ${if (state.playing == AbSlot.A) "A" else "B"}")
                }
                TextButton(
                    onClick = { actions.answerFinalCheck(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("No difference", color = Color.White.copy(alpha = 0.85f))
                }
                TextButton(onClick = actions::skipFinalCheck) {
                    Text("Skip the check", color = Color.White.copy(alpha = 0.6f))
                }
            }
        }
    }
}

// --- the pool's result ------------------------------------------------------

@Composable
private fun ResultContent(state: PreferenceUiState, actions: PreferenceTestActions) {
    BackHandler { actions.discard() }
    val profile = state.active
    val aggregate = state.aggregate

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(PreferenceProfile.NAME, style = MaterialTheme.typography.displayMedium)

        Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PanelHeader("Your curve")
                if (profile?.handAdjusted == true) Pill("adjusted", tone = PillTone.ACCENT)
            }
            Readout(
                "%+.1f / %+.1f dB".format(state.candidate.bassDb, state.candidate.trebleDb),
                caption = "bass shelf / treble shelf",
            )
            profile?.let { ShelfChart(it) }
            Text(verdictLine(aggregate.verdict), style = MaterialTheme.typography.bodyMedium)
            Text(
                spreadLine(aggregate.bassSpreadDb, aggregate.trebleSpreadDb, aggregate.runCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (aggregate.thin) {
                Text(
                    thinLine(aggregate.runCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                finalCheckLine(aggregate.finalCheck),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Panel {
            ExplainedHeader(
                "Adjust it",
                explanation = "The two sliders are the same numbers the test searched for, " +
                    "so moving one lands on a different point of the same space rather than " +
                    "overriding the result. Once you move them, adding another song asks " +
                    "before replacing your curve.",
            )
            ShelfSlider(
                label = "Bass",
                value = state.candidate.bassDb,
                range = PreferenceShelf.MIN_BASS_DB..PreferenceShelf.MAX_BASS_DB,
                tag = BASS_SLIDER_TAG,
                onChange = actions::setBassDb,
            )
            ShelfSlider(
                label = "Treble",
                value = state.candidate.trebleDb,
                range = PreferenceShelf.MIN_TREBLE_DB..PreferenceShelf.MAX_TREBLE_DB,
                tag = TREBLE_SLIDER_TAG,
                onChange = actions::setTrebleDb,
            )
            if (profile?.handAdjusted == true) {
                TextButton(onClick = actions::clearAdjustment) { Text("Back to what the songs said") }
            }
        }

        Panel {
            PanelHeader("Songs")
            if (state.runs.isEmpty()) {
                Text(
                    "No songs yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            state.runs.sortedByDescending { it.createdAtMillis }.forEach { run ->
                RunRow(run) { actions.removeRun(run.id) }
            }
            GoldOutlinedButton(onClick = actions::start, modifier = Modifier.fillMaxWidth()) {
                Text("Add another song")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoldButton(onClick = actions::save) {
                Text(if (state.saved && !state.dirty) "Saved" else "Save and apply")
            }
            TextButton(onClick = actions::discard) { Text("Discard") }
        }
        if (state.stored != null) {
            TextButton(onClick = actions::deleteProfile) { Text("Delete this preference curve") }
        }
    }

    HandAdjustmentDialog(state, actions)
}

/**
 * One song's answer, and the way to take it out again.
 *
 * A row rather than a swipe: a swipe on a list that is also scrolling is how
 * data gets deleted by accident, and this list is short enough that a button
 * costs nothing.
 */
@Composable
private fun RunRow(run: PreferenceRun, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                run.label.ifBlank { "Unnamed song" },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "%+.1f / %+.1f dB · %s".format(
                    run.candidate.bassDb,
                    run.candidate.trebleDb,
                    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(run.createdAtMillis)),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}

/**
 * The curve, plus one dot per song.
 *
 * The dots are the point of the picture. When the songs disagree, an average
 * drawn on its own is a claim of precision the pool has not got; showing where
 * each song actually landed is the only way the reader can judge whether the
 * middle of them means anything.
 */
@Composable
private fun ShelfChart(profile: PreferenceProfile) {
    val layout = profile.layout
    val gains = PreferenceShelf.gains(profile.candidate, layout)
    val lineColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val span = 12f

    Box(Modifier.fillMaxWidth().height(120.dp)) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val w = size.width
            val h = size.height
            fun y(db: Float) = h / 2f - (db / span).coerceIn(-1f, 1f) * (h / 2f)
            fun x(i: Int, count: Int) = if (count <= 1) 0f else w * i / (count - 1)

            listOf(-6f, 0f, 6f).forEach { db ->
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y(db)),
                    end = Offset(w, y(db)),
                    strokeWidth = 1f,
                    pathEffect = if (db == 0f) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
            }
            val path = Path()
            gains.forEachIndexed { i, v ->
                if (i == 0) path.moveTo(x(i, gains.size), y(v)) else path.lineTo(x(i, gains.size), y(v))
            }
            drawPath(path, lineColor, style = Stroke(width = 4f))

            // Each song as two dots — its bass answer at the left edge of the
            // plot and its treble answer at the right, which is where those two
            // shelves live on the frequency axis behind them.
            profile.runs.forEach { run ->
                drawCircle(dotColor, radius = 5f, center = Offset(w * 0.08f, y(run.candidate.bassDb)))
                drawCircle(dotColor, radius = 5f, center = Offset(w * 0.92f, y(run.candidate.trebleDb)))
            }
        }
    }
    Text(
        "Line: the curve applied. Dots: what each song asked for, bass on the left, " +
            "treble on the right.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun ShelfSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    tag: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("%+.1f dB".format(value), style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
    }
}

@Composable
private fun HandAdjustmentDialog(state: PreferenceUiState, actions: PreferenceTestActions) {
    if (state.pendingRun == null) return
    AlertDialog(
        onDismissRequest = actions::dismissDialog,
        title = { Text("Replace your adjustment?") },
        text = {
            Text(
                "You moved the sliders by hand. Adding this song puts the songs back in " +
                    "charge of the curve.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = actions::keepAdjustment) { Text("Keep my adjustment") }
        },
        dismissButton = {
            TextButton(onClick = actions::useNewMeasurement) { Text("Use the songs") }
        },
    )
}

// --- words ------------------------------------------------------------------

internal fun verdictLine(verdict: PreferenceVerdict): String = when (verdict) {
    PreferenceVerdict.NONE -> "No songs yet, so there is nothing to say."
    PreferenceVerdict.CONSISTENT -> "Consistent across your music."
    PreferenceVerdict.MIXED ->
        "Mixed — your answers moved when they were checked. Worth re-testing somewhere quiet."
    PreferenceVerdict.VARIED ->
        "Your taste varies across songs. The middle of them is applied; the dots below show " +
            "how far apart they were."
    PreferenceVerdict.WEAK ->
        "Weak — blind against no change at all, you picked no change. The curve is still here " +
            "if you want it, but the test did not confirm it."
    PreferenceVerdict.NEUTRAL ->
        "You like it as it is. Nothing worth applying came out of this, which is a real answer."
}

/** The nudge towards a third song, without demanding one. */
private fun thinLine(runs: Int): String {
    val so = if (runs == 1) "One song so far" else "$runs songs so far"
    return "$so. ${PreferencePool.RECOMMENDED_RUNS} different ones is where one recording " +
        "stops deciding the answer."
}

private fun spreadLine(bassSpread: Float, trebleSpread: Float, runs: Int): String {
    if (runs <= 1) return "One song, so there is nothing to compare it against yet."
    val widest = maxOf(bassSpread, trebleSpread)
    if (widest <= PreferencePool.SPREAD_WARN_DB) {
        return "Your songs agreed to within %.1f dB.".format(widest)
    }
    return "Your songs disagreed by up to %.1f dB in the bass and %.1f dB in the treble."
        .format(abs(bassSpread), abs(trebleSpread))
}

private fun finalCheckLine(check: FinalCheck): String = when (check) {
    FinalCheck.NOT_RUN -> "The blind check against no change was not run."
    FinalCheck.YOURS_WON -> "Blind against no change at all, you picked your own curve."
    FinalCheck.FLAT_WON -> "Blind against no change at all, you picked no change."
    FinalCheck.NO_DIFFERENCE -> "Blind against no change at all, you could not tell them apart."
}

internal const val RUN_LABEL_TAG: String = "preference-run-label"
internal const val BASS_SLIDER_TAG: String = "preference-bass-slider"
internal const val TREBLE_SLIDER_TAG: String = "preference-treble-slider"
