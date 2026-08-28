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
import androidx.compose.ui.platform.LocalContext
import dev.dankyeeter.btdashboard.system.attach.PlayingApps
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import dev.dankyeeter.btdashboard.audio.eq.EqBands
import dev.dankyeeter.btdashboard.audio.eq.VolumeAwareTilt
import dev.dankyeeter.btdashboard.hearing.AdjustedReference
import dev.dankyeeter.btdashboard.ui.common.describeWithApps
import dev.dankyeeter.btdashboard.ui.theme.Panel
import dev.dankyeeter.btdashboard.ui.theme.PanelHeader
import dev.dankyeeter.btdashboard.ui.screens.dashboard.ForeignEqSection
import dev.dankyeeter.btdashboard.ui.theme.ExplainedHeader
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
fun EqScreen(
    viewModel: EqViewModel = viewModel(),
    onOpenHearingTest: () -> Unit = {},
) {
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
        val playingApps = rememberPlayingAppNames()

        Text("System EQ", style = MaterialTheme.typography.displayMedium)

        Panel {
            ExplainedHeader(
                "Where the EQ acts",
                "With the helper running, the EQ attaches to the output mix and every " +
                    "app is equalised. Without it the EQ can only follow apps that " +
                    "announce their audio session, so a player that stays quiet about it " +
                    "plays uncorrected — which is why the line below names apps rather " +
                    "than counting sessions.",
            )
            Text(status.describeWithApps(playingApps), style = MaterialTheme.typography.bodySmall)
        }

        ExplainedRow(
            label = "EQ enabled",
            explanation = "The master switch. Off, the audio effect is detached " +
                "entirely — nothing is processed. Your curve, presets and hearing runs " +
                "are all kept.",
        ) {
            Switch(checked = settings.enabled, onCheckedChange = viewModel::setEnabled)
        }
        ExplainedRow(
            label = "Output limiter",
            explanation = "Catches the loudest peaks so a boosted band cannot distort. " +
                "Leave it on unless you are measuring something — it only acts on the " +
                "moments that would clip, and never changes the level of normal " +
                "listening.",
        ) {
            Switch(checked = settings.limiterEnabled, onCheckedChange = viewModel::setLimiterEnabled)
        }
        ExplainedRow(
            label = "Compare with EQ off",
            explanation = "Plays your music untouched so you can hear the difference; " +
                "flip it back and the curve returns. Both sides play at the same " +
                "loudness on purpose, because louder always sounds better at first — " +
                "switch the automatic headroom off and that match is gone with it.",
        ) {
            Switch(checked = bypass, onCheckedChange = viewModel::setBypass)
        }

        ExplainedRow(
            label = "Loudness restoration",
            explanation = "A static boost raises a band by the same amount whether the " +
                "signal there is a whisper or a full snare hit; a healthy ear does the " +
                "opposite. With this on, every boost in your curve acts that way — quiet " +
                "detail gets the full lift, loud passages pass as recorded, and by full " +
                "scale the boost is gone entirely, so nothing can clip. Cuts stay as " +
                "they are.",
        ) {
            Switch(
                checked = settings.loudnessRestoration,
                onCheckedChange = viewModel::setLoudnessRestoration,
            )
        }

        ExplainedRow(
            label = "Volume-aware tuning",
            explanation = "Quiet listening loses bass and treble — the equal-loudness " +
                "contours of ISO 226 get steeper as the level drops. With this on, the " +
                "EQ adds back what the current volume is costing you and adds nothing " +
                "once you are at normal listening level; it never cuts and never " +
                "changes the midrange.\n\n" +
                "It is an estimate, not a measurement: the phone knows how far the " +
                "volume slider is up, not how loud that is in your ears. The curve is " +
                "shaped from the ISO 226 average ear, assuming two thirds of the slider " +
                "is normal listening, and is capped at 12 dB.",
        ) {
            Switch(
                checked = settings.volumeAwareTilt,
                onCheckedChange = viewModel::setVolumeAwareTilt,
            )
        }

        if (settings.volumeAwareTilt) {
            val tilt = VolumeAwareTilt.summarise(settings.activeTiltDb, settings.layout)
            Text(
                if (tilt.isFlat) {
                    "Quiet-listening tilt: none at this volume (estimate)"
                } else {
                    "Quiet-listening tilt: %+.0f dB bass · %+.0f dB treble (estimate)"
                        .format(tilt.bassDb, tilt.trebleDb)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ExplainedRow(
            label = "Automatic headroom",
            explanation = "Raising a band multiplies numbers that have a ceiling, so this " +
                "lowers everything by however much the loudest band was raised and " +
                "nothing can overflow. The cost is that the whole thing gets quieter; " +
                "turn it up on your phone and you have what you asked for, without " +
                "distortion.\n\n" +
                "Switched off, a boost is heard as a boost, and a loud passage can clip " +
                "into brief crackle. The output limiter stays as a second net.",
        ) {
            Switch(checked = settings.autoHeadroom, onCheckedChange = viewModel::setAutoHeadroom)
        }

        // Just the state and the number. What the number is for is one tap away
        // in the row above it, and repeating the reason here is what made the
        // headroom the most explained control on the screen.
        Text(
            if (settings.autoHeadroom) {
                "Headroom: ${"%.1f".format(settings.preGainDb)} dB"
            } else {
                "Headroom off"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ExplainedHeader(
                "Ear view",
                "Both moves the two ears together, which is what you want for taste. " +
                    "Left and Right split them, which is what a measurement usually " +
                    "needs — hearing is rarely symmetrical.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EarView.entries.forEach { view ->
                    FilterChip(
                        selected = earView == view,
                        onClick = { earView = view },
                        label = { Text(view.label) },
                    )
                }
            }
        }

        CompensationSection(
            state = compensation,
            earView = earView,
            currentEq = settings,
            onOpenHearingTest = onOpenHearingTest,
            onIntensityChange = viewModel::setIntensity,
            onIntensityChangeFinished = viewModel::applyCompensationIfActive,
            onApply = viewModel::applyCompensation,
            onSelectSource = viewModel::setCompensationSource,
            onSelectAdjustedReference = viewModel::selectAdjustedReference,
            onCreateProfile = viewModel::createProfile,
            onSaveIntoActive = viewModel::saveCurrentIntoActive,
            onLoadProfile = viewModel::loadProfile,
            onDeleteProfile = viewModel::deleteProfile,
        )

        ForeignEqSection()

        // The generated profile states why its sliders do not move, and the
        // heading itself carries the state: a control that silently ignores a
        // drag is worse than no control at all. The reasoning is a paragraph,
        // so it goes behind the question mark and only the way out stays on the
        // surface.
        if (compensation.adjustedReferenceActive) {
            ExplainedHeader(
                "Bands are locked",
                "${AdjustedReference.NAME} is generated from your hearing-test runs, " +
                    "so its bands cannot be edited \u2014 that is what makes it a " +
                    "reference rather than a taste setting. The band count is fixed " +
                    "with them: on a coarser grid the 3 kHz and 6 kHz tones you were " +
                    "tested at fall between the bands.",
            )
            Text(
                "Save a copy under a new name to tune it by hand, or reset to flat to " +
                    "leave it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            PanelHeader("Bands")
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
        // A disabled segmented row with its usual caption underneath reads as a
        // bug. While the generated curve is in force the caption says why the
        // buttons do not respond instead of describing a grid that cannot be
        // chosen.
        Text(
            if (compensation.adjustedReferenceActive) {
                "Fixed while ${AdjustedReference.NAME} is active — the grid is part " +
                    "of the measurement."
            } else {
                settings.layout.description
            },
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

        // Named for what it clears and followed by what it does not: "Reset to
        // flat" beside a list of saved presets reads like it might take those
        // with it.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = viewModel::resetFlat) { Text("Reset bands to flat") }
            Text(
                "Clears the curve that is playing. Saved EQs and hearing runs are " +
                    "untouched.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
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

/**
 * The names of the apps whose sound is being equalised right now.
 *
 * Resolved here rather than anywhere deeper: turning a uid into a name needs a
 * PackageManager, and the only reason to do it at all is to put words on a
 * screen. Everything below this layer works in session ids, which is what an
 * audio effect actually attaches to.
 *
 * A uid can hold several packages; the first one that has a label is close
 * enough, because the alternative is a list nobody asked for.
 */
@Composable
private fun rememberPlayingAppNames(): List<String> {
    val context = LocalContext.current
    val uids by PlayingApps.uids.collectAsStateWithLifecycle()
    return remember(uids) {
        val packages = context.packageManager
        uids.mapNotNull { uid ->
            runCatching {
                packages.getPackagesForUid(uid)
                    ?.firstNotNullOfOrNull { name ->
                        packages.getApplicationLabel(packages.getApplicationInfo(name, 0)).toString()
                    }
            }.getOrNull()
        }.distinct().sorted()
    }
}
