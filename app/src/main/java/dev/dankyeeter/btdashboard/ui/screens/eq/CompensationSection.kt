package dev.dankyeeter.btdashboard.ui.screens.eq

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import dev.dankyeeter.btdashboard.ui.theme.ExplainedBlock
import dev.dankyeeter.btdashboard.ui.theme.ExplainedHeader
import dev.dankyeeter.btdashboard.ui.theme.ExplainedRow

/**
 * The compensation flow of COMPENSATION.md, rendered on the EQ screen:
 * audiogram (median curve) -> calibration preset -> intensity -> live preview
 * -> apply, plus named profiles.
 *
 * Every disclaimer visible here is deliberate. The numbers are consumer
 * calibration for headphone EQ, and the UI states what the numbers mean at the
 * point where the user actually looks at them.
 */
@Composable
internal fun CompensationSection(
    state: CompensationUiState,
    earView: EarView,
    currentEq: EqSettings,
    onIntensityChange: (Float) -> Unit,
    onIntensityChangeFinished: () -> Unit,
    onApply: () -> Unit,
    onSelectAdjustedReference: () -> Unit,
    onCreateProfile: (String) -> Unit,
    onSaveIntoActive: () -> Unit,
    onLoadProfile: (CompensationProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onOpenHearingTest: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Panel(modifier) {
        ExplainedHeader(
            "Tuned to your hearing",
            "Your sound profile shows which frequencies you hear less well. This " +
                "lifts exactly those, so quiet detail comes back without making " +
                "everything else louder. It is built from your test automatically — " +
                "the controls below only decide how far it goes. When the generated " +
                "curve is active the EQ moves to " +
                "${AdjustedReference.LAYOUT.bandCount} bands: on the coarser default " +
                "grid, two of the eight tones you were tested at — 3 kHz and 6 kHz, " +
                "where age and loud noise show up first — fall between the bands and " +
                "never reach the sound at all.",
        )

        AudiogramSummary(state)
        CalibrationRow(state)
        IntensityControl(state, onIntensityChange, onIntensityChangeFinished)

        val result = state.result
        if (result == null) {
            // A dead end otherwise: the one thing that would fix this state is
            // on another screen, so the way there belongs here.
            Text(
                "No hearing test yet — there is nothing to correct for.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            GoldOutlinedButton(onClick = onOpenHearingTest) { Text("Run a hearing test") }
        } else {
            if (result.severeLossWarning) {
                SevereLossNotice()
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
            onOpenHearingTest,
        )
    }
}

/**
 * The one honest thing to say when the measurement is past the reach of the
 * tool, and the reason it is not simply "turn it up".
 *
 * Deliberately not a refusal: the correction below still does something for the
 * quietest detail, and hiding it would leave a user with a severe loss with
 * nothing at all. The heading names the limit, the line underneath says what is
 * still true, and the rest — why gain is the wrong instrument here — sits behind
 * the question mark rather than turning a warning into a lecture.
 */
@Composable
private fun SevereLossNotice() {
    ExplainedHeader(
        "Beyond what an EQ can fix",
        "Correcting a loss this large needs gain that would clip the music long " +
            "before it restored the detail, and level alone does not bring back " +
            "clarity once the sensory cells are gone. An equaliser is the wrong " +
            "instrument for it — a fitted hearing aid compresses loudness rather " +
            "than just raising it, which is the part that is missing here. What " +
            "this app can still do is make the quietest detail audible: keep the " +
            "strength low and judge it by ear.",
    )
    Text(
        "Your thresholds are past what an EQ can correct. What is applied below " +
            "still helps, but only partly.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun AudiogramSummary(state: CompensationUiState) {
    // Counted out in words up to three, because the number is the whole point
    // of the sentence and "1 run(s)" makes the reader do the work of deciding
    // whether that is enough.
    val text = when {
        state.audiogram == null -> "No hearing test yet."
        state.runCount == 0 -> "Loaded from a saved preset."
        state.runCount == 1 -> "Based on one run — two more make it steady."
        state.runCount == 2 -> "Based on two runs — one more makes it steady."
        else -> "Based on ${state.runCount} runs — enough to be steady."
    }
    Text(text, style = MaterialTheme.typography.bodySmall)
}

/**
 * Which headphone the thresholds were measured through, stated and not offered.
 *
 * No model list. The supported headphones are shipped support, not a catalogue
 * to shop from: the preset belongs to the device and is set under Devices, and
 * a picker here would let a curve measured through one headphone be
 * reinterpreted through another one's correction.
 */
@Composable
private fun CalibrationRow(state: CompensationUiState) {
    val preset = state.preset
    // [ExplainedRow] renders its control slot *before* the label, which is right
    // for a switch and wrong for a readout: this row came out as "Not set
    // Device calibration ?", a value announced before the reader has been told
    // what it is a value for. This is not a control at all, so it is built from
    // [ExplainedBlock] instead — label, question mark, then the value at the far
    // end, in the order the row is read.
    ExplainedBlock(
        label = "Device calibration",
        explanation = "Every headphone colours the test tones before they reach your " +
            "ear, so a threshold measured through one is not the same number " +
            "measured through another. A calibration preset subtracts that " +
            "colouring, which is what makes the correction about your ears rather " +
            "than about the driver. Presets are set per headphone under Devices; " +
            "without one the test result is used raw.",
    ) { toggle ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Device calibration", style = MaterialTheme.typography.bodyLarge)
            toggle()
            Spacer(Modifier.weight(1f))
            Text(
                if (preset == null || preset.id == CalibrationPresetRepository.GENERIC_ID) {
                    "Not set"
                } else {
                    preset.displayName
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
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
 * describes. This is also the one place on the screen that tells the reader how
 * to judge the result by ear: the same advice used to be repeated beside the
 * dead-region notice and the severe-loss warning, where it read as three
 * separate cautions instead of one instruction.
 *
 * Uses [ExplainedBlock] rather than [ExplainedRow] only because the disclosure
 * has to sit beside the slider track instead of in front of a label; the state
 * and the styling are the shared ones.
 */
@Composable
private fun IntensityControl(
    state: CompensationUiState,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
) {
    ExplainedBlock(
        label = "Correction strength",
        explanation = "100 % is the whole correction this app prescribes for your " +
            "ears — not the whole size of your hearing loss. The rule it follows " +
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
    ) { toggle ->
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
            toggle()
        }

        EffectReadOut(state.result)
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
 * So the heading names what may happen and stops. It is not a diagnosis, and its
 * absence is not an all-clear either — that is why the notice never appears in
 * the negative. Below the flagging threshold there is no notice at all, because
 * for a mild or moderate loss a false alarm costs more than the warning is
 * worth. It is also not an error: nothing has gone wrong and nothing needs
 * fixing, so it is set in the ordinary colour rather than in red.
 */
@Composable
private fun DeadRegionNotice(result: CompensationResult) {
    val flagged = result.possibleDeadRegionFrequenciesHz
    if (flagged.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "May not respond to boosting: " +
                flagged.joinToString(", ") { it.toFloat().bandLabel() },
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            "Your measured threshold there is high enough that the sensory cells may " +
                "no longer be working. Where that is the case, lifting the band adds " +
                "loudness and roughness rather than detail — but this app cannot find " +
                "out which: the test for it needs calibrated equipment, and a " +
                "Bluetooth headphone is not that. The correction is applied unchanged; " +
                "turn the strength down if those frequencies sound coarse rather than " +
                "clearer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Band-gain curves plus the numbers underneath, following the screen's ear
 * selector: both ears overlaid by default ([EarView.LINKED]), or a single ear
 * when the user is working on one side.
 *
 * The per-band table is collapsed by default. On the generated curve it is
 * thirty-one rows of decibels, which pushed the apply button and the presets
 * off the bottom of the screen for a reader who only wanted to see the shape —
 * and the chart above already shows that shape. Anyone comparing bands can open
 * it, which is a deliberate act rather than a wall to scroll past every time.
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
        var showNumbers by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { showNumbers = !showNumbers }) {
            Text(if (showNumbers) "Hide the numbers" else "Show the numbers")
        }
        AnimatedVisibility(showNumbers) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                // "Pre-gain" is the implementation's word for it. What the
                // reader can check against the numbers above is that everything
                // sits that far below where it started.
                Text(
                    "Everything is lowered by ${"%.1f".format(abs(result.eq.preGainDb))} dB " +
                        "so the boosts cannot overflow.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
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
 *
 * Its caller only draws it *below* that threshold; once the curve is ready it
 * becomes a dropdown entry instead. So there is no "use it" button and no
 * band-count notice here: both would only ever render in a state this card is
 * never shown in. The band count is stated in the section header instead, where
 * it is read before the switch rather than after it.
 */
@Composable
private fun AdjustedReferenceCard(
    state: CompensationUiState,
    onOpenHearingTest: () -> Unit,
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
                    "Generated from your hearing test. " +
                        "${AdjustedReference.REQUIRED_RUNS.spelledOut()} runs and it " +
                        "appears here."
                !ready ->
                    "${state.runCount} of ${AdjustedReference.REQUIRED_RUNS} runs done."
                else ->
                    "Median of ${state.runCount} runs. Not adjustable by hand — it " +
                        "is a measurement, not a taste setting."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        // The card only exists to explain a missing measurement, so it carries
        // the way to take it. Without this the reader is told what is needed
        // and left to go hunting for the screen that provides it.
        if (!ready) {
            GoldOutlinedButton(onClick = onOpenHearingTest) { Text("Run a hearing test") }
        }
    }
}

/** A small count reads as a word in prose; the digit belongs in "2 of 3". */
private fun Int.spelledOut(): String = when (this) {
    1 -> "One"
    2 -> "Two"
    3 -> "Three"
    else -> toString()
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
    onOpenHearingTest: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    val activeProfile = state.profiles.firstOrNull { it.id == state.activeProfileId }
    val activeName = when {
        state.adjustedReferenceActive -> AdjustedReference.NAME
        activeProfile != null -> activeProfile.name
        else -> "None — flat"
    }
    // "Changed since saved" only means something for a hand-tuned preset: the
    // Personal Reference is a measurement and is never edited back into.
    val dirty = activeProfile != null && activeProfile.audiogram == null &&
        activeProfile.eq.forDirtyCheck() != currentEq.forDirtyCheck()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelHeader("EQ presets")

        // Until the hearing test has produced its curve, the card explains
        // what is missing; once it is ready it becomes a dropdown entry and
        // the card would only repeat the menu.
        if (!state.adjustedReferenceReady) {
            AdjustedReferenceCard(state, onOpenHearingTest)
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
                // PrimaryNotEditable: the field is the menu's own anchor and is
                // readOnly, so it opens the menu rather than taking a caret.
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
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
            TextButton(onClick = { confirmingDelete = true }) { Text("Delete this EQ") }
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

    // Asked for, because a preset is the one thing on this screen that cannot be
    // dialled back: the bands can always be moved again, but a name and the
    // curve stored under it are gone with nothing to restore them from. The
    // question says which name is going, so a mis-tap on the wrong active preset
    // is caught here rather than discovered later.
    if (confirmingDelete && activeProfile != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this EQ?") },
            text = {
                Text(
                    "“${activeProfile.name}” is removed. The bands stay as they are; " +
                        "only the saved name goes.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete(activeProfile.id)
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            },
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

/**
 * Everything a preset stores, minus the two fields that are not part of what it
 * stores.
 *
 * The check used to compare gains and layout only, so the limiter, the
 * automatic headroom and loudness restoration could all be switched while the
 * preset stayed "unchanged" — no offer to save, and the next load quietly put
 * them back. Comparing the whole object is the fix; the two exceptions are
 * exceptions for a reason. `preGainDb` is derived from the gains by sanitized()
 * rather than chosen, and `enabled` is the master switch for the session, not
 * part of any preset's identity — a preset does not become a different preset
 * because the EQ is currently off.
 */
private fun EqSettings.forDirtyCheck() = copy(enabled = false, preGainDb = 0f)

private fun Float.bandLabel(): String = when {
    this >= 1000f -> {
        val k = this / 1000f
        if (k % 1f == 0f) "${k.toInt()} kHz" else "%.1f kHz".format(k)
    }
    this % 1f == 0f -> "${toInt()} Hz"
    else -> "%.1f Hz".format(this)
}
