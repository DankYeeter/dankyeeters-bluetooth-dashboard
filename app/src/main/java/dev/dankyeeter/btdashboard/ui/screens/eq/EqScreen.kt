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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.audio.eq.EqBands
import dev.dankyeeter.btdashboard.system.attach.AttachmentStatus

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
    val settings by viewModel.settings.collectAsState()
    val status by viewModel.attachmentStatus.collectAsState()
    val bypass by viewModel.bypass.collectAsState()
    val compensation by viewModel.compensation.collectAsState()
    var earView by remember { mutableStateOf(EarView.LINKED) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("System EQ", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Reach", style = MaterialTheme.typography.titleSmall)
                Text(status.describe(), style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = settings.enabled, onCheckedChange = viewModel::setEnabled)
            Text("  EQ enabled")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = settings.limiterEnabled, onCheckedChange = viewModel::setLimiterEnabled)
            Text("  Output limiter")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = bypass, onCheckedChange = viewModel::setBypass)
            Text("  A/B: play flat (curve kept)")
        }
        Text(
            "The pre-gain stays applied while you A/B, so flat and compensated " +
                "play at matched loudness — louder must not be able to pass for better.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

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
            onSaveProfile = viewModel::saveProfile,
            onLoadProfile = viewModel::loadProfile,
            onDeleteProfile = viewModel::deleteProfile,
        )

        Text("Bands", style = MaterialTheme.typography.titleMedium)

        val gains = when (earView) {
            EarView.RIGHT -> settings.rightGainsDb
            else -> settings.leftGainsDb
        }

        EqBands.CENTER_FREQUENCIES_HZ.forEachIndexed { index, freq ->
            BandSlider(
                label = freq.labelHz(),
                extrapolated = index in EqBands.EXTRAPOLATED_INDICES,
                gainDb = gains[index],
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
