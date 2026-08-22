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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Headphones
import dev.dankyeeter.btdashboard.ui.theme.Panel
import dev.dankyeeter.btdashboard.ui.theme.Pill
import dev.dankyeeter.btdashboard.ui.theme.PillTone
import dev.dankyeeter.btdashboard.ui.theme.Readout
import dev.dankyeeter.btdashboard.ui.theme.PanelHeader
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import dev.dankyeeter.btdashboard.ui.theme.PanelDivider

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
    val state by viewModel.state.collectAsStateWithLifecycle()
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

    Panel {
        // No refresh affordance: the list is push-based now, so a button here
        // would only teach the user to distrust what the screen says.
        PanelHeader("Bluetooth audio")

            // The panel keeps its shape whether or not anything is connected.
            // Collapsing to a single line of prose made an idle screen look
            // broken, and hid where the values were going to appear.
            when {
                state.loading -> WaitingDeviceRow("Reading codec status…")

                !state.profileAvailable -> WaitingDeviceRow(
                    "The Bluetooth audio profile is not reachable. Codec details need " +
                        "the Bluetooth permission and a connected A2DP device.",
                )

                state.rows.isEmpty() -> WaitingDeviceRow(
                    "Connect a headphone and this fills in by itself.",
                )

                else -> state.rows.forEach { row -> DeviceRow(row) }
            }

            // Present but disabled rather than absent: a control that appears
            // and disappears makes the screen feel like it is rearranging
            // itself, and hides that the capture exists at all.
            GoldOutlinedButton(
                enabled = state.rows.isNotEmpty(),
                onClick = {
                    viewModel.startWatchLive()
                    onWatchLive()
                },
            ) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null)
                Text("  Watch live (10 s capture)")
            }
    }
}

/**
 * The device row's skeleton, with the numbers still missing.
 *
 * Deliberately the same shape as [DeviceRow] rather than a sentence: a screen
 * with nothing connected should read as one waiting for a value, not one that
 * failed. It also shows *where* the codec will appear before it exists, which
 * is what makes the automatic fill-in legible as an event rather than a jump.
 */
@Composable
private fun WaitingDeviceRow(note: String) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "No device",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Readout(value = "—")
            }
            Icon(
                Icons.Filled.Headphones,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        Text(
            note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceRow(row: DeviceCodecRow) {
    // The codec is the answer this screen exists to give, so it is the largest
    // thing on it. Everything else — name, state, battery — is support around
    // that number rather than a row of equal-weight text.
    val parts = row.codecBadge.split(" · ")
    val codecName = parts.firstOrNull().orEmpty()
    val detail = parts.drop(1).joinToString(" · ").ifBlank { null }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    row.device.name,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Readout(
                    value = if (row.codecKnown) codecName else "Unknown",
                    caption = detail,
                )
            }
            Icon(
                Icons.Filled.Headphones,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (row.device.isActive) Pill("Active", tone = PillTone.ACCENT)
            if (row.device.isPlaying) Pill("Playing", tone = PillTone.ACCENT)
            row.beacon?.let { beacon ->
                beacon.leftBatteryPercent?.let {
                    Pill("L $it%" + if (beacon.leftCharging) " ⚡" else "")
                }
                beacon.rightBatteryPercent?.let {
                    Pill("R $it%" + if (beacon.rightCharging) " ⚡" else "")
                }
                beacon.caseBatteryPercent?.let {
                    Pill("Case $it%" + if (beacon.caseCharging) " ⚡" else "")
                }
            }
        }

        // Honest degradation: say why the codec is unknown instead of hiding it.
        row.codecNote?.takeIf { !row.codecKnown }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Foreign-EQ warning surface with app attribution (PLAN.md, promoted to v1). */
@Composable
fun ForeignEqSection(viewModel: BluetoothDashboardViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scan = state.foreignEq
    var expanded by rememberSaveable { mutableStateOf(false) }

    // Collapsed by default and led by a count. This is a "check when something
    // sounds wrong" surface, not a daily read: expanded by default it was the
    // longest thing on the screen and buried the controls the user came for.
    val attached = scan?.warnings?.size ?: 0
    val vendors = scan?.vendorApps?.size ?: 0
    val total = attached + vendors

    Panel {
        PanelHeader(
            "Other equalizers",
            trailing = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        scan == null -> Pill("Not checked")
                        !scan.available -> Pill("Unavailable", tone = PillTone.WARN)
                        total == 0 -> Pill("None found", tone = PillTone.ACCENT)
                        else -> Pill("$total", tone = PillTone.WARN)
                    }
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )

        Text(
            when {
                scan == null ->
                    "Another equalizer stacking on top of this one silently undoes the curve."
                !scan.available -> scan.unavailableReason ?: "Check unavailable."
                total == 0 -> "Nothing else is shaping your audio."
                else -> "$total app(s) could be shaping your audio as well."
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        if (expanded) {
            PanelDivider()

            scan?.warnings?.forEach { warning ->
                Text(warning.message, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Session ${warning.sessionId} — set that app's EQ to flat, " +
                        "or disable it while using ours.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            // A companion app's EQ runs in the headphone itself and never enters
            // Android's audio path, so the scan above is structurally blind to
            // it. Saying "all clear" without this would be a false all-clear.
            scan?.vendorApps?.forEach { app ->
                Text("${app.appLabel} is installed.", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Its equalizer runs inside the headphones, not on the phone, so no app " +
                        "on Android can read it — this check included. If you set a curve " +
                        "there it stacks on top of this one.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            TextButton(onClick = viewModel::scanForeignEq) { Text("Check now") }
            PanelDivider()
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
    val state by viewModel.state.collectAsStateWithLifecycle()
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
