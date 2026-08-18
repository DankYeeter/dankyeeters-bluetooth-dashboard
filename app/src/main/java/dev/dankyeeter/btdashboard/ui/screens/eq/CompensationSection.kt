package dev.dankyeeter.btdashboard.ui.screens.eq

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dankyeeter.btdashboard.audio.eq.EqBands
import dev.dankyeeter.btdashboard.hearing.CompensationProfile
import dev.dankyeeter.btdashboard.hearing.CompensationResult
import kotlin.math.abs

/**
 * The compensation flow of COMPENSATION.md, rendered on the EQ screen:
 * audiogram (median curve) -> calibration preset -> intensity -> live preview
 * -> apply, plus named profiles.
 *
 * Every disclaimer visible here is deliberate. The numbers are consumer
 * calibration, not clinical audiometry, and the UI says so where the user
 * actually looks.
 */
@Composable
internal fun CompensationSection(
    state: CompensationUiState,
    earView: EarView,
    onSelectPreset: (String) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onIntensityChangeFinished: () -> Unit,
    onApply: () -> Unit,
    onSaveProfile: (String) -> Unit,
    onLoadProfile: (CompensationProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Hearing compensation", style = MaterialTheme.typography.titleMedium)

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
                        "Your thresholds are well outside what a consumer EQ should try " +
                            "to compensate. Please see a hearing professional — this app " +
                            "is not a medical device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                CompensationPreview(result, earView)
                EarDifference(result)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onApply, enabled = !state.applied) {
                        Text(if (state.applied) "Applied" else "Apply to EQ")
                    }
                }
            }

            HorizontalDivider()
            ProfileList(state, onSaveProfile, onLoadProfile, onDeleteProfile)
        }
    }
}

@Composable
private fun AudiogramSummary(state: CompensationUiState) {
    val text = when {
        state.audiogram == null -> "No hearing test data yet."
        state.runCount == 0 -> "Curve loaded from a saved profile."
        state.runCount < 3 ->
            "Median of ${state.runCount} run(s) — the plan asks for at least 3 " +
                "before you trust the curve."
        else -> "Median curve across ${state.runCount} runs."
    }
    Text(text, style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetPicker(state: CompensationUiState, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val preset = state.preset

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = preset?.displayName ?: state.presetId,
            onValueChange = {},
            readOnly = true,
            label = { Text("Device calibration preset") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.presets.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.displayName) },
                    onClick = {
                        onSelect(item.id)
                        expanded = false
                    },
                )
            }
        }
    }

    if (preset != null) {
        Text(
            preset.provenanceLine(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        if (preset.notes.isNotBlank()) {
            Text(
                preset.notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (preset.requiresFitCheck) {
            AssistChip(onClick = {}, label = { Text("In-ear — fit check required") })
        }
    }
}

@Composable
private fun IntensityControl(
    state: CompensationUiState,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Intensity", style = MaterialTheme.typography.titleSmall)
            Text(
                "${(state.intensity * 100).toInt()} %",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = state.intensity,
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            valueRange = 0f..1f,
        )
        Text(
            "Share of the full NAL-R prescription. 100 % is not the goal: partial " +
                "compensation is what keeps loud passages from turning shrill. Start " +
                "at 50 % or below and work upwards over one or two weeks.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
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

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Preview", style = MaterialTheme.typography.titleSmall)
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
                "0–12 dB, bands 31.5 Hz … 16 kHz",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        EqBands.CENTER_FREQUENCIES_HZ.forEachIndexed { i, hz ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    buildString {
                        append(hz.bandLabel())
                        if (i in EqBands.EXTRAPOLATED_INDICES) append("  (extrapolated)")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i in EqBands.EXTRAPOLATED_INDICES) {
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
    Column {
        Text("Left / right difference", style = MaterialTheme.typography.titleSmall)
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

@Composable
private fun ProfileList(
    state: CompensationUiState,
    onSave: (String) -> Unit,
    onLoad: (CompensationProfile) -> Unit,
    onDelete: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Profiles", style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Profile name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    onSave(name)
                    name = ""
                },
                enabled = state.result != null,
            ) { Text("Save") }
        }

        if (state.profiles.isEmpty()) {
            Text(
                "No saved profiles yet. A profile stores the audiogram, the preset " +
                    "and the intensity together, so a later re-test never changes it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            state.profiles.forEach { profile ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                profile.name +
                                    if (profile.id == state.activeProfileId) "  · active" else "",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${(profile.intensity * 100).toInt()} % · ${profile.calibrationPresetId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        TextButton(onClick = { onLoad(profile) }) { Text("Load") }
                        TextButton(onClick = { onDelete(profile.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

private fun Float.bandLabel(): String = when {
    this >= 1000f -> {
        val k = this / 1000f
        if (k % 1f == 0f) "${k.toInt()} kHz" else "%.1f kHz".format(k)
    }
    this % 1f == 0f -> "${toInt()} Hz"
    else -> "%.1f Hz".format(this)
}
