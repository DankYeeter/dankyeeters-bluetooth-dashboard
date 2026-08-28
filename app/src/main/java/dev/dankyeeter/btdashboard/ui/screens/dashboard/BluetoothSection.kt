package dev.dankyeeter.btdashboard.ui.screens.dashboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.dankyeeter.btdashboard.monitor.effects.EqCandidateScan
import dev.dankyeeter.btdashboard.monitor.effects.ForeignEqWarning
import dev.dankyeeter.btdashboard.ui.theme.GoldOutlinedButton
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Headphones
import dev.dankyeeter.btdashboard.ui.theme.ExplainedBlock
import dev.dankyeeter.btdashboard.ui.theme.ExplainedHeader
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
        // would only teach the user to distrust what the screen says. What the
        // capture actually costs used to be crammed into the button's own
        // label; it is a detail about the action, not part of its name.
        ExplainedHeader(
            "Bluetooth audio",
            "The codec is negotiated between the phone and the headphone, so it can " +
                "change on its own — this reads back whatever was agreed. Watch live " +
                "samples it closely for five minutes on the Monitoring timeline, then " +
                "stops by itself.",
        )

            // The panel keeps its shape whether or not anything is connected.
            // Collapsing to a single line of prose made an idle screen look
            // broken, and hid where the values were going to appear.
            when {
                state.loading -> WaitingDeviceRow("Reading codec status…")

                !state.profileAvailable -> {
                    // Naming the missing thing is only half an answer; the other
                    // half is the dialog that fixes it. Without the button this
                    // was a statement the user could do nothing about.
                    WaitingDeviceRow("Bluetooth access is missing, so no codec can be read.")
                    GrantBluetoothButton(onResult = viewModel::refreshAfterPermissionGrant)
                }

                state.rows.isEmpty() -> WaitingDeviceRow(
                    "Connect a headphone and this fills in by itself.",
                )

                else -> state.rows.forEach { row -> DeviceRow(row) }
            }

            // Present but disabled rather than absent: a control that appears
            // and disappears makes the screen feel like it is rearranging
            // itself, and hides that the capture exists at all. A disabled
            // control still owes an answer to "why can I not press this".
            GoldOutlinedButton(
                enabled = state.rows.isNotEmpty(),
                onClick = {
                    viewModel.startWatchLive()
                    onWatchLive()
                },
            ) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null)
                Text("  Watch live")
            }
            if (state.rows.isEmpty()) {
                Text(
                    "Connect a headphone to capture.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
    }
}

/** Asks for the two Bluetooth permissions the codec read needs. */
@Composable
private fun GrantBluetoothButton(onResult: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onResult() }

    GoldOutlinedButton(
        onClick = {
            launcher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                ),
            )
        },
    ) { Text("Grant Bluetooth access") }
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
    // that number rather than a row of equal-weight text. The two halves come
    // from the row model now; this used to split a joined badge back apart.
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
                Readout(value = row.codecName, caption = row.codecDetail)
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

    // Collapsed by default. This is a "check when something sounds wrong"
    // surface, not a daily read: expanded by default it was the longest thing
    // on the screen and buried the controls the user came for.
    val warnings = remember(scan) { scan?.warnings.orEmpty().distinctBy { it.packageName } }
    val labels = rememberForeignEqLabels(warnings)
    val vendorApps = scan?.vendorApps.orEmpty()
    val total = warnings.size + vendorApps.size
    // Names, never a count: "2" is not something anyone can check against what
    // they are hearing, and an unresolvable app is "another app" rather than a
    // package name nobody can act on.
    val foundNames = remember(warnings, labels, vendorApps) {
        (warnings.mapNotNull { labels[it.packageName] } + vendorApps.map { it.appLabel })
            .distinct()
    }

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
                        else -> Pill("Found", tone = PillTone.WARN)
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
                // No button on this one: the check runs through the privileged
                // helper, which the app's own gate starts — there is nothing
                // here for the user to press, only a reason.
                !scan.available -> scan.unavailableReason ?: "Check unavailable."
                total == 0 -> "Nothing else is shaping your audio."
                foundNames.isEmpty() -> "Another app could be shaping your audio too."
                else -> "${listSentence(foundNames)} could be shaping your audio too."
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        if (expanded) {
            PanelDivider()

            warnings.forEach { warning ->
                Text(
                    labels[warning.packageName]
                        ?.let { "$it has an equalizer running on your audio." }
                        ?: "Another app has an equalizer running on your audio.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Set its equalizer to flat, or turn it off while this one is on.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            // A companion app's EQ runs in the headphone itself and never enters
            // Android's audio path, so the scan above is structurally blind to
            // it. Saying "all clear" without this would be a false all-clear.
            vendorApps.forEach { app ->
                ExplainedBlock(
                    label = app.appLabel,
                    explanation = "Its equalizer runs inside the headphones, so no app on " +
                        "Android can read it — this check included. A curve set there " +
                        "stacks on top of this one.",
                ) { toggle ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${app.appLabel} has its own equalizer inside the headphone.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        toggle()
                    }
                }
            }

            TextButton(onClick = viewModel::scanForeignEq) { Text("Check running effects") }
            PanelDivider()
            EqCandidateList(viewModel)
        }
    }
}

/**
 * App labels for the packages a foreign-EQ warning names.
 *
 * The warning carries whatever `ps` reported: usually a package name, sometimes
 * with a `:process` suffix, sometimes already a friendly name from the
 * detector's own list of known equalizers. Only PackageManager turns the first
 * kind into something a user recognises, and anything it cannot resolve is
 * dropped here so the sentence can fall back to "another app" — a package name
 * on screen is an identifier nobody can act on.
 */
@Composable
private fun rememberForeignEqLabels(warnings: List<ForeignEqWarning>): Map<String, String> {
    val context = LocalContext.current
    val names = warnings.mapNotNull { it.packageName }.distinct()
    return remember(names) {
        val packages = context.packageManager
        names.mapNotNull { reported ->
            val candidate = reported.substringBefore(':')
            val label = runCatching {
                packages.getApplicationLabel(packages.getApplicationInfo(candidate, 0)).toString()
            }.getOrNull() ?: candidate.takeIf { !it.looksLikeAPackageName() }
            label?.let { reported to it }
        }.toMap()
    }
}

/** "com.pittvandewitt.wavelet" is an id; "Wavelet" is a name. */
private fun String.looksLikeAPackageName(): Boolean = contains('.') && !contains(' ')

/** "X", "X and Y", "X, Y and Z", then "X, Y, Z and others". */
private fun listSentence(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    2 -> "${names[0]} and ${names[1]}"
    3 -> "${names[0]}, ${names[1]} and ${names[2]}"
    else -> "${names.take(3).joinToString(", ")} and others"
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

    // The disclaimer is the whole point of this list and it is three sentences
    // long, which is two more than anyone reads above a list they are scanning.
    // Behind the question mark it is still there for whoever wants to know how
    // far to trust it — and that is also where the cost of the check belongs.
    ExplainedHeader("Apps that might have an equalizer", candidateExplanation(scan))

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

        // No "see the note above" any more: the note is behind the header's
        // question mark, and a pointer to something that is not on screen is
        // worse than no pointer at all.
        scan.isEmpty -> Text(
            "No installed app declares an equalizer.",
            style = MaterialTheme.typography.bodySmall,
        )

        else -> {
            scan.strong.forEach { candidate -> EqCandidateRow(candidate, context) }

            if (scan.weak.isNotEmpty()) {
                // What the weak tier really means was printed underneath the
                // list once it was open — after the user had already decided
                // to trust it. As the toggle's own explanation it is available
                // before the list appears.
                ExplainedBlock(
                    label = "Weaker matches",
                    explanation = "These only request the permission an equalizer would " +
                        "need. Most use it for volume or routing and have no EQ at all.",
                ) { toggle ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showWeak = !showWeak }) {
                            Text(if (showWeak) "Hide weaker matches" else "Show weaker matches")
                        }
                        toggle()
                    }
                }
                if (showWeak) {
                    scan.weak.forEach { candidate -> EqCandidateRow(candidate, context) }
                }
            }

            if (!scan.playbackKnown) {
                // Built as one sentence. Appending a full stop to a note that
                // already ended in one produced "… no permission.." on screen.
                val note = scan.playbackNote?.trim()?.trimEnd('.')?.takeIf { it.isNotBlank() }
                Text(
                    note?.let { "Cannot tell which app is playing right now — $it." }
                        ?: "Cannot tell which app is playing right now.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }

    TextButton(onClick = { viewModel.scanEqCandidates(refresh = true) }) {
        Text("Check installed apps")
    }
}

/**
 * What the list is worth, and what it cost.
 *
 * The disclaimer is fixed text; the numbers are the claim "this runs once, not
 * on a timer" made checkable, which is why they are still here at all.
 */
private fun candidateExplanation(scan: EqCandidateScan?): String = buildString {
    append(
        "This is a hint, not a verdict. It only sees what apps declare to Android: " +
            "an app that filters audio in its own code, or in the headphones, cannot " +
            "be detected at all.",
    )
    scan?.takeIf { it.available && it.scannedPackages > 0 }?.let {
        append("\n\n${it.scannedPackages} apps checked in ${it.durationMs} ms")
        append(if (it.fromCache) ", reused since." else ".")
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
