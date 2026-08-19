package dev.dankyeeter.btdashboard.ui.screens.dashboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dankyeeter.btdashboard.monitor.effects.EqCandidate
import dev.dankyeeter.btdashboard.ui.theme.GoldCard
import dev.dankyeeter.btdashboard.ui.theme.GoldTitle
import dev.dankyeeter.btdashboard.ui.theme.GoldOutlinedButton

/**
 * Codec dashboard section: connected devices, the negotiated codec as a badge,
 * a "watch live" quick action, and the foreign-EQ warning surface.
 *
 * Lives in its own file so the shared [DashboardScreen] only gains two calls —
 * another worker is editing that file in parallel.
 */
@Composable
fun BluetoothCodecSection(
    onWatchLive: () -> Unit,
    viewModel: BluetoothDashboardViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // The beacon scan lives with the device list now that battery is shown on
    // the device's own row. Scanning only while this screen is resumed: the
    // advertisement is free to listen to, a permanent BLE scan is not.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.startBeaconScan()
                Lifecycle.Event.ON_STOP -> viewModel.stopBeaconScan()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopBeaconScan()
        }
    }

    GoldCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Bluetooth, contentDescription = null)
                    GoldTitle("Bluetooth audio")
                }
                TextButton(onClick = viewModel::refresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    Text(" Refresh")
                }
            }

            when {
                state.loading -> Text("Reading codec status…", style = MaterialTheme.typography.bodySmall)

                !state.profileAvailable -> Text(
                    "The Bluetooth audio profile is not reachable. Codec details need " +
                        "the Bluetooth permission and a connected A2DP device.",
                    style = MaterialTheme.typography.bodySmall,
                )

                state.rows.isEmpty() -> Text(
                    "No Bluetooth audio device connected.",
                    style = MaterialTheme.typography.bodySmall,
                )

                else -> state.rows.forEach { row -> DeviceRow(row) }
            }

            if (state.rows.isNotEmpty()) {
                GoldOutlinedButton(onClick = {
                    viewModel.startWatchLive()
                    onWatchLive()
                }) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = null)
                    Text("  Watch live (10 s capture)")
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(row: DeviceCodecRow) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(row.device.name, style = MaterialTheme.typography.titleSmall)

        // The badge gets its own line. Sharing one with the device name meant a
        // long codec string ("aptX HD · 48 kHz · 24 bit") and a long device name
        // fought over the same width, and both lost.
        AssistChip(
            onClick = {},
            enabled = row.codecKnown,
            label = { Text(row.codecBadge) },
            leadingIcon = { Icon(Icons.Filled.GraphicEq, contentDescription = null) },
        )

        val state = buildList {
            if (row.device.isActive) add("active")
            if (row.device.isPlaying) add("playing")
            row.beacon?.let { beacon ->
                // Battery belongs to the device, so it goes on the device's line.
                beacon.leftBatteryPercent?.let { add("L $it%" + if (beacon.leftCharging) "⚡" else "") }
                beacon.rightBatteryPercent?.let { add("R $it%" + if (beacon.rightCharging) "⚡" else "") }
                beacon.caseBatteryPercent?.let { add("case $it%" + if (beacon.caseCharging) "⚡" else "") }
            }
        }.joinToString(" · ")
        if (state.isNotEmpty()) {
            Text(
                state,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        // Honest degradation: say why the codec is unknown instead of hiding it.
        row.codecNote?.takeIf { !row.codecKnown }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** Foreign-EQ warning surface with app attribution (PLAN.md, promoted to v1). */
@Composable
fun ForeignEqSection(viewModel: BluetoothDashboardViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val scan = state.foreignEq

    GoldCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                GoldTitle("Other equalizers")
            }

            when {
                scan == null -> Text(
                    "Another equalizer stacking on top of ours silently undoes the " +
                        "compensation curve. Run a check to see what else is attached.",
                    style = MaterialTheme.typography.bodySmall,
                )

                !scan.available -> Text(
                    scan.unavailableReason ?: "Check unavailable.",
                    style = MaterialTheme.typography.bodySmall,
                )

                else -> {
                    if (scan.warnings.isEmpty()) {
                        Text(
                            "No other equalizer is attached to the phone's audio.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        scan.warnings.forEach { warning ->
                            Text(warning.message, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Session ${warning.sessionId} — set that app's EQ to flat, " +
                                    "or disable it while using ours.",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }

                    // The honest half. A companion app's EQ runs in the headphone
                    // itself and never enters Android's audio path, so the scan
                    // above is structurally blind to it — saying "all clear"
                    // without this would be a false all-clear.
                    scan.vendorApps.forEach { app ->
                        HorizontalDivider()
                        Text(
                            "${app.appLabel} is installed.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Its equalizer runs inside the headphones, not on the phone, so " +
                                "no app on Android can read it — this check included. If you " +
                                "set a curve there it stacks on top of ours. Set it flat in " +
                                "the ${app.vendor} app before running a hearing test.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            TextButton(onClick = viewModel::scanForeignEq) { Text("Check now") }

            HorizontalDivider()
            EqCandidateList(viewModel)
        }
    }
}

/**
 * The wider, honest net: every installed app that *could* carry an equalizer.
 *
 * Deliberately worded as a prompt rather than a finding. The strongest tier
 * here is still only "this app says it has an equalizer panel", and the case
 * that motivated the whole feature — a companion app whose EQ lives in the
 * headphone, or a player filtering in its own native code — leaves no trace on
 * Android at all. Presenting any of this as a verdict would be a lie.
 */
@Composable
private fun EqCandidateList(viewModel: BluetoothDashboardViewModel) {
    val state by viewModel.state.collectAsState()
    val scan = state.eqCandidates
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showWeak by rememberSaveable { mutableStateOf(false) }

    // The package pass runs when the section becomes visible and never on a
    // timer. The playback callback is event-driven and is dropped again on
    // ON_STOP so nothing keeps listening in the background.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    viewModel.scanEqCandidates()
                    viewModel.startPlaybackWatch()
                }

                Lifecycle.Event.ON_STOP -> viewModel.stopPlaybackWatch()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopPlaybackWatch()
        }
    }

    Text(
        "These apps could have their own EQ — worth checking",
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        "This is a hint, not a verdict. It only sees what apps declare to Android: " +
            "an app that filters audio in its own code, or in the headphones, cannot " +
            "be detected at all — not by this app and not by any other.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )

    when {
        state.candidatesScanning && scan == null ->
            Text("Checking installed apps…", style = MaterialTheme.typography.bodySmall)

        scan == null -> Text(
            "Not checked yet.",
            style = MaterialTheme.typography.bodySmall,
        )

        !scan.available -> Text(
            scan.unavailableReason ?: "Cannot check which apps might have an equalizer.",
            style = MaterialTheme.typography.bodySmall,
        )

        scan.isEmpty -> Text(
            "No installed app declares an equalizer or asks for audio-effect access. " +
                "That is not a guarantee — see the note above.",
            style = MaterialTheme.typography.bodySmall,
        )

        else -> {
            scan.strong.forEach { candidate -> EqCandidateRow(candidate, context) }

            if (scan.weak.isNotEmpty()) {
                TextButton(onClick = { showWeak = !showWeak }) {
                    Text(
                        if (showWeak) {
                            "Hide the ${scan.weak.size} weaker matches"
                        } else {
                            "Show ${scan.weak.size} more that could merely attach an effect"
                        },
                    )
                }
                if (showWeak) {
                    Text(
                        "These only request the permission an equalizer would need. Most of " +
                            "them use it for volume or routing and have no EQ whatsoever.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    scan.weak.forEach { candidate -> EqCandidateRow(candidate, context) }
                }
            }

            if (!scan.playbackKnown) {
                Text(
                    "Cannot tell which app is playing right now" +
                        (scan.playbackNote?.let { " — $it" } ?: "") + ".",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            // Say what the check cost. It runs once per app start, not on a
            // timer, and the numbers are here so that claim is checkable.
            Text(
                "${scan.scannedPackages} installed apps checked in ${scan.durationMs} ms" +
                    if (scan.fromCache) " (reusing that scan)" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }

    TextButton(onClick = { viewModel.scanEqCandidates(refresh = true) }) {
        Text("Check installed apps again")
    }
}

/** One candidate: name, why it is listed, and a tap straight into its settings. */
@Composable
private fun EqCandidateRow(candidate: EqCandidate, context: Context) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { openAppSettings(context, candidate.packageName) }
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            candidate.appLabel + if (candidate.playingNow) "  · playing now" else "",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            candidate.reason,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * Opens the app's own settings page so the user can go and look. We never
 * change another app's settings — see PLAN.md non-goals.
 */
private fun openAppSettings(context: Context, packageName: String) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
