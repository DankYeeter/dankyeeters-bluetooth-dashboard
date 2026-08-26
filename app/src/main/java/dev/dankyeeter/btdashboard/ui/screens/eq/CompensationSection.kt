package dev.dankyeeter.btdashboard.ui.screens.eq

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.hearing.CompensationProfile
import dev.dankyeeter.btdashboard.hearing.CompensationResult
import kotlin.math.abs
import dev.dankyeeter.btdashboard.ui.theme.GoldButton
import dev.dankyeeter.btdashboard.ui.theme.GoldOutlinedButton
import dev.dankyeeter.btdashboard.hearing.AdjustedReference
import dev.dankyeeter.btdashboard.hearing.CalibrationPresetRepository
import dev.dankyeeter.btdashboard.ui.theme.Panel
import dev.dankyeeter.btdashboard.ui.theme.PanelDivider
import dev.dankyeeter.btdashboard.ui.theme.PanelHeader
import dev.dankyeeter.btdashboard.ui.theme.Pill
import dev.dankyeeter.btdashboard.ui.theme.PillTone
import dev.dankyeeter.btdashboard.ui.theme.Readout
import dev.dankyeeter.btdashboard.ui.theme.ExplainedHeader

/**
 * The compensation flow of COMPENSATION.md, rendered on the EQ screen:
 * audiogram (median curve) -> calibration preset -> intensity -> live preview
 * -> apply, plus named profiles.
 *
 * Every disclaimer visible here is deliberate. The numbers are consumer
 * calibration for headphone EQ, and the UI states what the numbers mean where
 * actually looks.
 */
@Composable
internal fun CompensationSection(
    state: CompensationUiState,
    earView: EarView,
    currentEq: EqSettings,
    onSelectPreset: (String) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onIntensityChangeFinished: () -> Unit,
    onApply: () -> Unit,
    onSelectAdjustedReference: () -> Unit,
    onCreateProfile: (String) -> Unit,
    onSaveIntoActive: () -> Unit,
    onLoadProfile: (CompensationProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Panel(modifier) {
        ExplainedHeader(
            "Tuned to your hearing",
            "Your sound profile shows which frequencies you hear less well. This " +
                "lifts exactly those, so quiet detail comes back without making " +
                "everything else louder. It is built from your test automatically — " +
                "the controls below only decide how far it goes.",
        )

        AudiogramSummary(state)
        PresetPicker(state, onSelectPreset)
        IntensityControl(state, onIntensityChange, onIntensityChangeFinished)

        val result = state.result
        if (result == null) {
            Text(
                "Complete a hearing test to see a compensation curve here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            if (result.severeLossWarning) {
                Text(
                    "Your thresholds are outside the range this EQ can correct — the " +
                        "gain needed here would clip long before it helped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            DeadRegionNotice(result)
            CompensationPreview(result, earView)
            EarDifference(result)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoldButton(onClick = onApply, enabled = !state.applied) {
                    Text(if (state.applied) "Applied" else "Apply to EQ")
                }
            }
        }

        // A hairline inside a panel, not Material's full-strength rule: at full
        // weight it cuts the panel in two and the presets read as a second card.
        PanelDivider()
        ProfileList(
            state,
            currentEq,
            onSelectAdjustedReference,
            onCreateProfile,
            onSaveIntoActive,
            onLoadProfile,
            onDeleteProfile,
        )
    }
}

@Composable
private fun AudiogramSummary(state: CompensationUiState) {
    val text = when {
        state.audiogram == null -> "Run a sound profile first — the Sound Profiling tab."
        state.runCount == 0 -> "Loaded from a saved preset."
        state.runCount < 3 ->
            "Based on ${state.runCount} run(s). Do at least three: single runs " +
                "disagree by a few dB, and the app uses the middle value of yours."
        else -> "Based on ${state.runCount} runs — enough to be steady."
    }
    Text(text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun PresetPicker(state: CompensationUiState, onSelect: (String) -> Unit) {
    val preset = state.preset

    // No model list. The supported headphones are shipped support, not a
    // catalogue to shop from: the connected device decides which curve is in
    // force, and this only states which one that is.
    Text(
        when {
            preset == null -> "No device calibration."
            preset.id == CalibrationPresetRepository.GENERIC_ID ->
                "No calibration for this device — running uncorrected."
            else -> "Calibrated for ${preset.displayName}."
        },
        style = MaterialTheme.typography.bodyMedium,
    )

}

/**
 * The strength slider, and the one line that says what its number means.
 *
 * The percentage on its own was the app's largest honesty gap: "60 %" of an
 * unstated quantity, which a reader will naturally complete as "60 % of my
 * hearing loss" — and that is roughly three times what actually happens. Two
 * things fix it without a technical caption. The scale is named for what it
 * scales ("correction strength", where 100 % is the whole correction the app
 * prescribes), and underneath it the curve reports itself in decibels at a
 * frequency, recomputed as the thumb moves. That line is read off the computed
 * curve, so it stays true where the percentage stops being proportional: at the
 * 12 dB cap, at the slope limiter, and outside the measured range.
 *
 * The rest — that the prescription is itself a half-gain rule, and why that is
 * deliberate — sits behind the question mark. It is the honest explanation and
 * it is also four sentences long; on the surface it would bury the control it
 * describes. Same affordance as the `?` icons elsewhere on this screen
 * (ui.theme.ExplainedRow), built inline only because the disclosure has to sit
 * beside the slider track rather than in front of a label.
 */
@Composable
private fun IntensityControl(
    state: CompensationUiState,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
) {
    var explain by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The percentage is the number this whole section is about, so it gets
        // the app's readout treatment instead of sitting at label size on the
        // far side of a row, where it read as a unit tacked onto a caption.
        PanelHeader("Correction strength")
        Readout("${(state.intensity * 100).toInt()} %")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = state.intensity,
                onValueChange = onChange,
                onValueChangeFinished = onChangeFinished,
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { explain = !explain }) {
                Icon(
                    Icons.Outlined.HelpOutline,
                    contentDescription = if (explain) {
                        "Hide explanation"
                    } else {
                        "What does correction strength mean?"
                    },
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        EffectReadOut(state.result)

        AnimatedVisibility(explain) {
            Text(
                "100 % is the whole correction this app prescribes for your ears — " +
                    "not the whole size of your hearing loss. The rule it follows " +
                    "(NAL-R) asks for about half a decibel of lift per decibel of " +
                    "measured loss, so 100 % here makes up roughly 46 % of what you " +
                    "measured, and the 60 % it starts at lands near 28 %.\n\n" +
                    "That gap is the point, not a shortcoming. Restoring a loss in " +
                    "full over-lifts everything that was already audible: quiet detail " +
                    "comes back, but loud passages turn harsh, because a damaged ear's " +
                    "loudness grows faster with level than a healthy one's, not slower. " +
                    "When the same laboratory later measured its own prescriptions " +
                    "against real listeners, the numbers moved down rather than up — " +
                    "nearly half of the people tested wanted less gain than the theory " +
                    "called for.\n\n" +
                    "So the slider is taste inside a range that stays sane at both ends. " +
                    "Judge it with \"Compare with EQ off\" rather than by the number, and " +
                    "give a change a few days: a setting that felt right on day one often " +
                    "feels thin by day five.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What the current slider position actually does, in dB.
 *
 * Silent while there is no curve to describe — an empty line here is correct,
 * whereas a placeholder would be a claim about a measurement that does not
 * exist yet. At strength zero it says so instead of printing "+0.0 dB", which
 * reads like a control that has not finished loading.
 */
@Composable
private fun EffectReadOut(result: CompensationResult?) {
    if (result == null) return
    val peak = result.peakBand
    Text(
        if (peak == null) {
            "Nothing is lifted at this setting — playback is unchanged."
        } else {
            "Lifts up to %+.1f dB, strongest around %s.".format(
                peak.gainDb,
                peak.centerHz.bandLabel(),
            )
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The one thing the app is obliged to say about a very high threshold, and
 * cannot say anything more about.
 *
 * Above roughly 70 dB, published prevalence data gives better-than-even odds
 * that a frequency has no working sensory cells left — a cochlear dead region —
 * in which case lifting that band adds level and distortion instead of detail.
 * Whether that is true *here* is not determinable from a hearing test: the test
 * that decides it (TEN) needs calibrated presentation levels no consumer
 * headphone can produce.
 *
 * So this says "cannot check" and stops. It is not a diagnosis, and its absence
 * is not an all-clear either — that is why the notice never appears in the
 * negative. Below the flagging threshold there is no notice at all, because for
 * a mild or moderate loss a false alarm costs more than the warning is worth.
 */
@Composable
private fun DeadRegionNotice(result: CompensationResult) {
    val flagged = result.possibleDeadRegionFrequenciesHz
    if (flagged.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Cannot check: " + flagged.joinToString(", ") { it.toFloat().bandLabel() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            (if (flagged.size == 1) {
                "Your measured threshold at that frequency is "
            } else {
                "Your measured thresholds at those frequencies are "
            }) +
                "high enough that the sensory cells there may no longer be working. " +
                "Where that is the case, lifting the band adds loudness and roughness " +
                "rather than detail — but this app has no way to find out: the test for " +
                "it needs calibrated equipment, which a Bluetooth headphone is not. " +
                "The compensation is applied there unchanged; judge those frequencies " +
                "with \"Compare with EQ off\" and turn the strength down if they sound " +
                "coarse rather than clearer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Band-gain curves plus the numbers underneath, following the screen's ear
 * selector: both ears overlaid by default ([EarView.LINKED]), or a single ear
 * when the user is working on one side.
 */
@Composable
private fun CompensationPreview(result: CompensationResult, earView: EarView) {
    val left = result.left.bandGainsDb.map { it.toFloat() }
    val right = result.right.bandGainsDb.map { it.toFloat() }
    val showLeft = earView != EarView.RIGHT
    val showRight = earView != EarView.LEFT
    val leftColor = MaterialTheme.colorScheme.primary
    val rightColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val maxGain = 12f
    // The curve's own grid, not the app default. These stopped being the same
    // list when the generated profile moved to 20 bands: reading the centres
    // from a fixed ten-entry table would have labelled a 25 Hz band "31.5 Hz"
    // and silently hidden the ten bands past the end of it.
    val centres = result.eq.centersHz
    val extrapolated = result.eq.layout.extrapolatedIndices

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelHeader("Preview")
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp),
        ) {
            Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                val w = size.width
                val h = size.height
                fun y(gain: Float) = h - (gain / maxGain).coerceIn(0f, 1f) * h
                fun x(i: Int) = if (left.size <= 1) 0f else w * i / (left.size - 1)

                // 0, 6 and 12 dB grid lines.
                listOf(0f, 6f, 12f).forEach { g ->
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y(g)),
                        end = Offset(w, y(g)),
                        strokeWidth = 1f,
                        pathEffect = if (g == 0f) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                    )
                }

                fun curve(values: List<Float>, color: Color) {
                    val path = Path()
                    values.forEachIndexed { i, v ->
                        if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v))
                    }
                    drawPath(path, color, style = Stroke(width = 4f))
                    values.forEachIndexed { i, v -> drawCircle(color, radius = 5f, center = Offset(x(i), y(v))) }
                }
                if (showLeft) curve(left, leftColor)
                if (showRight) curve(right, rightColor)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (showLeft) Text("Left", style = MaterialTheme.typography.labelMedium, color = leftColor)
            if (showRight) Text("Right", style = MaterialTheme.typography.labelMedium, color = rightColor)
            Text(
                "0–12 dB, ${centres.size} bands " +
                    "${centres.first().bandLabel()} … ${centres.last().bandLabel()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        centres.forEachIndexed { i, hz ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    buildString {
                        append(hz.bandLabel())
                        if (i in extrapolated) append("  (extrapolated)")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i in extrapolated) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    when {
                        showLeft && showRight -> "L %+.1f   R %+.1f".format(left[i], right[i])
                        showLeft -> "L %+.1f".format(left[i])
                        else -> "R %+.1f".format(right[i])
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Text(
            "Headroom (pre-gain): ${"%+.1f".format(result.eq.preGainDb)} dB",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun EarDifference(result: CompensationResult) {
    val diffs = result.bandDifferenceDb
    val worst = diffs.maxByOrNull { abs(it) } ?: 0.0
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PanelHeader("Left / right difference")
        Text(
            if (abs(worst) < 0.5) {
                "Both ears get essentially the same correction."
            } else {
                "Largest difference: %+.1f dB (positive = more gain on the left). ".format(worst) +
                    "Asymmetry is normal and the ears are compensated independently."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            "PTA  left %.1f dB · right %.1f dB".format(result.left.ptaDb, result.right.ptaDb),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * The generated profile, always first and never deletable.
 *
 * It sits among the saved presets because that is where the user looks for a
 * curve, but it is deliberately not one of them: no name field, no delete, no
 * sliders. Below the run threshold it stays visible and says what is missing —
 * hiding it would leave no trace of why the hearing test is worth finishing.
 */
@Composable
private fun AdjustedReferenceCard(
    state: CompensationUiState,
    onSelect: () -> Unit,
) {
    val ready = state.adjustedReferenceReady
    val active = state.adjustedReferenceActive

    // A panel inside a panel at tighter padding: this is one item in a list of
    // presets and has to read as an item, not as a second section. "active" is
    // a state, so it wears the pill instead of hanging off the name as text.
    Panel(contentPadding = 12) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(AdjustedReference.NAME, style = MaterialTheme.typography.bodyMedium)
            if (active) Pill("active", tone = PillTone.ACCENT)
        }
        Text(
            when {
                !ready && state.runCount == 0 ->
                    "Generated from your hearing test. Run the Sound Profiling tab " +
                        "${AdjustedReference.REQUIRED_RUNS} times and it appears here."
                !ready ->
                    "${state.runCount} of ${AdjustedReference.REQUIRED_RUNS} runs done \u2014 " +
                        "${state.runsStillNeeded} more and this becomes available."
                else ->
                    "Median of ${state.runCount} runs. Not adjustable by hand: it is a " +
                        "measurement of your hearing, not a taste setting."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        if (ready) {
            // Worth a line on the surface, because it visibly changes the
            // band list when this profile is selected — and because the
            // reason is a real defect rather than a preference.
            Text(
                "Uses ${AdjustedReference.LAYOUT.bandCount} bands instead of " +
                    "${EqBandLayout.DEFAULT.bandCount}. On the coarser grid two of the " +
                    "eight tones you were tested at — 3 kHz and 6 kHz, where age and " +
                    "loud noise show up first — fall between the bands and never reach " +
                    "the sound at all. The band count stays fixed while this is active.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (ready && !active) {
            GoldOutlinedButton(onClick = onSelect) { Text("Use it") }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ProfileList(
    state: CompensationUiState,
    currentEq: EqSettings,
    onSelectAdjustedReference: () -> Unit,
    onCreate: (String) -> Unit,
    onSaveIntoActive: () -> Unit,
    onLoad: (CompensationProfile) -> Unit,
    onDelete: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }

    val activeProfile = state.profiles.firstOrNull { it.id == state.activeProfileId }
    val activeName = when {
        state.adjustedReferenceActive -> AdjustedReference.NAME
        activeProfile != null -> activeProfile.name
        else -> "None — flat"
    }
    // "Changed since saved" only means something for a hand-tuned preset: the
    // Personal Reference is a measurement and is never edited back into.
    val dirty = activeProfile != null && activeProfile.audiogram == null &&
        (activeProfile.eq.leftGainsDb != currentEq.leftGainsDb ||
            activeProfile.eq.rightGainsDb != currentEq.rightGainsDb ||
            activeProfile.eq.layout != currentEq.layout)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelHeader("EQ presets")

        // Until the hearing test has produced its curve, the card explains
        // what is missing; once it is ready it becomes a dropdown entry and
        // the card would only repeat the menu.
        if (!state.adjustedReferenceReady) {
            AdjustedReferenceCard(state, onSelectAdjustedReference)
        }

        // One control instead of a list: pick, or add. Selecting applies
        // immediately — a preset you cannot hear right away is a file, not
        // a sound.
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
            OutlinedTextField(
                value = activeName,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text("Active EQ") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (state.adjustedReferenceReady) {
                    DropdownMenuItem(
                        text = { Text(AdjustedReference.NAME + " — from your hearing test") },
                        onClick = {
                            open = false
                            onSelectAdjustedReference()
                        },
                    )
                }
                state.profiles.forEach { profile ->
                    DropdownMenuItem(
                        text = { Text(profile.name) },
                        onClick = {
                            open = false
                            onLoad(profile)
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("＋ Add new EQ…") },
                    onClick = {
                        open = false
                        naming = true
                    },
                )
            }
        }

        if (dirty) {
            GoldOutlinedButton(onClick = onSaveIntoActive, modifier = Modifier.fillMaxWidth()) {
                Text("Save changes to “${activeProfile?.name}”")
            }
        }
        if (activeProfile != null) {
            TextButton(onClick = { onDelete(activeProfile.id) }) { Text("Delete this EQ") }
        }
    }

    if (naming) {
        NewEqDialog(
            onCreate = { name ->
                naming = false
                onCreate(name)
            },
            onDismiss = { naming = false },
        )
    }
}

/**
 * Name first, bands second: the name is the only thing the dialog asks for,
 * because everything else about a new EQ is done better with the sliders that
 * are already on this screen. The new preset starts flat — a fresh sheet of
 * paper, not a copy of the last experiment.
 */
@Composable
private fun NewEqDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New EQ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                Text(
                    "Starts flat. Shape it with the band sliders, then save.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun Float.bandLabel(): String = when {
    this >= 1000f -> {
        val k = this / 1000f
        if (k % 1f == 0f) "${k.toInt()} kHz" else "%.1f kHz".format(k)
    }
    this % 1f == 0f -> "${toInt()} Hz"
    else -> "%.1f Hz".format(this)
}
