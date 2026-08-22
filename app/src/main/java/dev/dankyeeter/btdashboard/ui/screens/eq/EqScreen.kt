package dev.dankyeeter.btdashboard.ui.screens.eq

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import dev.dankyeeter.btdashboard.audio.eq.EqBands
import dev.dankyeeter.btdashboard.hearing.AdjustedReference
import dev.dankyeeter.btdashboard.system.attach.AttachmentStatus
import dev.dankyeeter.btdashboard.ui.theme.GoldCard
import dev.dankyeeter.btdashboard.ui.theme.GoldTitle
import dev.dankyeeter.btdashboard.ui.theme.Panel
import dev.dankyeeter.btdashboard.ui.theme.PanelHeader
import dev.dankyeeter.btdashboard.ui.screens.dashboard.ForeignEqSection
import dev.dankyeeter.btdashboard.ui.theme.ExplainedRow

/**
 * Which ear the screen is looking at. Drives the band sliders *and* the
 * compensation preview chart — one selector, both views, so the chart never
 * shows a different ear than the sliders underneath it.
 *
 * [LINKED] is the default and keeps the both-ears overlay in the chart.
 */
internal enum class EarView(val label: String) {
    LINKED("Both"),
    LEFT("Left"),
    RIGHT("Right"),
}

@Composable
fun EqScreen(viewModel: EqViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.attachmentStatus.collectAsStateWithLifecycle()
    val bypass by viewModel.bypass.collectAsStateWithLifecycle()
    val compensation by viewModel.compensation.collectAsStateWithLifecycle()
    var earView by remember { mutableStateOf(EarView.LINKED) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("System EQ", style = MaterialTheme.typography.displayMedium)

        Panel {
                Text("Reach", style = MaterialTheme.typography.titleSmall)
                Text(status.describe(), style = MaterialTheme.typography.bodySmall)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = settings.enabled, onCheckedChange = viewModel::setEnabled)
            Text("  EQ enabled")
        }
        ExplainedRow(
            label = "Output limiter",
            explanation = "Catches the loudest peaks so a boosted band cannot distort. " +
                "Leave it on unless you are measuring something — it only acts on the " +
                "few percent of moments that would clip, and never changes the level " +
                "of normal listening.",
        ) {
            Switch(checked = settings.limiterEnabled, onCheckedChange = viewModel::setLimiterEnabled)
        }
        ExplainedRow(
            label = "Compare with EQ off",
            explanation = "Plays your music untouched so you can hear the difference, " +
                "without losing your settings — flip it back and the curve returns. " +
                "Both sides play at the same loudness on purpose: louder always sounds " +
                "better at first, and that would make the comparison worthless.",
        ) {
            Switch(checked = bypass, onCheckedChange = viewModel::setBypass)
        }

        Text(
            "Headroom (pre-gain): ${"%.1f".format(settings.preGainDb)} dB — applied " +
                "automatically so boosted bands cannot clip.",
            style = MaterialTheme.typography.bodySmall,
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Ear view", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EarView.entries.forEach { view ->
                    FilterChip(
                        selected = earView == view,
                        onClick = { earView = view },
                        label = { Text(view.label) },
                    )
                }
            }
            Text(
                "Applies to the band sliders and to the compensation preview below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        CompensationSection(
            state = compensation,
            earView = earView,
            onSelectPreset = viewModel::selectPreset,
            onIntensityChange = viewModel::setIntensity,
            onIntensityChangeFinished = viewModel::applyCompensationIfActive,
            onApply = viewModel::applyCompensation,
            onSelectAdjustedReference = viewModel::selectAdjustedReference,
            onSaveProfile = viewModel::saveProfile,
            onLoadProfile = viewModel::loadProfile,
            onDeleteProfile = viewModel::deleteProfile,
        )

        ForeignEqSection()

        PanelHeader("Bands")

        // The generated profile states why its sliders do not move. A control
        // that silently ignores a drag is worse than no control at all.
        if (compensation.adjustedReferenceActive) {
            Text(
                "${AdjustedReference.NAME} is generated from your " +
                    "${compensation.runCount} hearing-test runs, so its bands cannot be " +
                    "edited \u2014 that is what makes it a reference rather than a taste " +
                    "setting. The band count is fixed with them: on a coarser grid the " +
                    "3 kHz and 6 kHz tones you were tested at never reach the sound at " +
                    "all. Save a copy under a new name to tune it by hand, or reset to " +
                    "flat to leave it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        // Layout picker. Switching resamples the curve onto the new centres, so
        // this is a resolution control, not a reset button \u2014 except on the
        // generated curve, where the grid is part of the measurement and the
        // ViewModel refuses the change outright.
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            EqBandLayout.entries.forEachIndexed { index, layout ->
                SegmentedButton(
                    selected = settings.layout == layout,
                    enabled = !compensation.adjustedReferenceActive,
                    onClick = { viewModel.setBandLayout(layout) },
                    shape = SegmentedButtonDefaults.itemShape(index, EqBandLayout.entries.size),
                ) { Text(layout.label) }
            }
        }
        Text(
            settings.layout.description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

        val gains = when (earView) {
            EarView.RIGHT -> settings.rightGainsDb
            else -> settings.leftGainsDb
        }

        settings.centersHz.forEachIndexed { index, freq ->
            BandSlider(
                label = freq.labelHz(),
                extrapolated = index in settings.layout.extrapolatedIndices,
                gainDb = gains[index],
                enabled = !compensation.adjustedReferenceActive,
                onChange = { value ->
                    when (earView) {
                        EarView.LINKED -> viewModel.setLinkedBandGain(index, value)
                        EarView.LEFT -> viewModel.setBandGain(Ear.LEFT, index, value)
                        EarView.RIGHT -> viewModel.setBandGain(Ear.RIGHT, index, value)
                    }
                },
                onRelease = viewModel::persist,
            )
        }

        TextButton(onClick = viewModel::resetFlat) { Text("Reset to flat") }
    }
}

@Composable
private fun BandSlider(
    label: String,
    extrapolated: Boolean,
    gainDb: Float,
    onChange: (Float) -> Unit,
    onRelease: () -> Unit,
    enabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(88.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (extrapolated) {
                Text(
                    "extrapolated",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Slider(
            value = gainDb,
            onValueChange = onChange,
            onValueChangeFinished = onRelease,
            enabled = enabled,
            valueRange = EqBands.MIN_GAIN_DB..EqBands.MAX_GAIN_DB,
            modifier = Modifier.weight(1f),
        )
        Text(
            "%+.1f".format(gainDb),
            modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private fun Float.labelHz(): String =
    if (this >= 1000f) "${(this / 1000f).let { if (it % 1f == 0f) it.toInt().toString() else "%.1f".format(it) }} kHz"
    else "${if (this % 1f == 0f) toInt().toString() else "%.1f".format(this)} Hz"

private fun AttachmentStatus.describe(): String = when (this) {
    is AttachmentStatus.ActiveGlobal ->
        "Global: attached to the output mix — reaches every app, including Tidal."
    is AttachmentStatus.ActiveSessions ->
        "Session mode: attached to ${sessionIds.size} announced session(s). Players " +
            "that do not announce their session are not affected."
    is AttachmentStatus.Unavailable -> reason
    AttachmentStatus.Inactive -> "Not attached."
}
