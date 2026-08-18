package dev.dankyeeter.btdashboard.ui.screens.dashboard

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dankyeeter.btdashboard.hearing.CalibrationPreset
import dev.dankyeeter.btdashboard.nowplaying.CodecSummary
import dev.dankyeeter.btdashboard.nowplaying.NotificationAccess
import dev.dankyeeter.btdashboard.nowplaying.NowPlaying
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.airpods.AirPodsBeacon
import dev.dankyeeter.btdashboard.system.airpods.AirPodsScanState
import dev.dankyeeter.btdashboard.system.shizuku.ShizukuState
import dev.dankyeeter.btdashboard.transfer.BackupSchema
import dev.dankyeeter.btdashboard.ui.icons.DeviceIcons

@Composable
fun DashboardScreen(
    onOpenOnboarding: () -> Unit,
    viewModel: DashboardViewModel = viewModel(),
    onWatchLive: () -> Unit = {},
    onOpenWizard: () -> Unit = {},
    onOpenDeviceProfiles: () -> Unit = {},
) {
    val shizukuState by SystemGraph.shizuku.state.collectAsState()
    val attachment by SystemGraph.eqController.status.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // The wizard's steps can be satisfied outside the app; re-check on resume.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshSetupStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Dashboard", style = MaterialTheme.typography.headlineSmall)

        SetupStatusCard(viewModel, onOpenWizard)
        NowPlayingCard(viewModel)
        AirPodsCard(viewModel)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("System access", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (shizukuState) {
                        is ShizukuState.Ready -> "Shizuku ready — global EQ possible."
                        else -> "Shizuku not ready — EQ limited to session mode."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("EQ attachment: $attachment", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenWizard) { Text("Setup wizard") }
                    OutlinedButton(onClick = onOpenOnboarding) { Text("Shizuku details") }
                }
                OutlinedButton(onClick = onOpenDeviceProfiles) { Text("Device profiles") }
            }
        }

        // Milestone 2 monitor sections; bodies live in BluetoothSection.kt.
        BluetoothCodecSection(onWatchLive = onWatchLive)
        ForeignEqSection()

        DeviceListCard(viewModel.presets)
        BackupCard(viewModel)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Honest limits", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Audiometry-inspired consumer calibration without clinical validity — " +
                        "not a substitute for professional hearing diagnostics.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ---- setup ------------------------------------------------------------------

/**
 * Compact nag until the wizard's steps are either satisfied or skipped.
 *
 * It counts *outstanding* steps, not ungranted ones: a step the user
 * deliberately skipped is a decision, and repeating the question forever is how
 * an app teaches people to ignore it.
 */
@Composable
private fun SetupStatusCard(viewModel: DashboardViewModel, onOpenWizard: () -> Unit) {
    val summary by viewModel.setupSummary.collectAsState()
    val text = summary ?: return

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Button(onClick = onOpenWizard) { Text("Finish setup") }
        }
    }
}

// ---- now playing ------------------------------------------------------------

/**
 * "You're hearing X in LDAC @ 990 kbps".
 *
 * Both halves degrade independently: without notification access there is no
 * track, and until the link monitor registers a codec source there is no codec
 * clause. Neither absence is allowed to invent a value.
 */
@Composable
private fun NowPlayingCard(viewModel: DashboardViewModel) {
    val context = LocalContext.current
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val codec by viewModel.codec.collectAsState()
    val connected by viewModel.listenerConnected.collectAsState()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Now playing", style = MaterialTheme.typography.titleMedium)

            when {
                !connected && !NotificationAccess.isGranted(context) -> {
                    Text(
                        "Grant notification access and this card shows what Tidal is " +
                            "playing, together with the codec it is riding on. We only " +
                            "read the media notification — Tidal itself is never touched.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = {
                            context.startActivity(NotificationAccess.settingsIntent())
                            NotificationAccess.requestRebind(context)
                        },
                    ) { Text("Open notification access") }
                }

                nowPlaying == null -> Text(
                    "Nothing is playing right now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                else -> NowPlayingLine(nowPlaying!!, codec)
            }
        }
    }
}

@Composable
private fun NowPlayingLine(nowPlaying: NowPlaying, codec: CodecSummary?) {
    Text(
        buildString {
            append("You're hearing ")
            append(nowPlaying.describe())
            if (codec != null) {
                append(" in ")
                append(codec.describe())
            }
            append('.')
        },
        style = MaterialTheme.typography.bodyLarge,
    )
    val detail = buildString {
        append(nowPlaying.appLabel)
        nowPlaying.album?.let { append(" · ").append(it) }
        if (codec == null) append(" · codec unknown (link monitor not reporting yet)")
        codec?.sampleRateHz?.let { append(" · ").append(it / 1000).append(" kHz") }
    }
    Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
}

// ---- AirPods ----------------------------------------------------------------

/**
 * Battery and wear state decoded from the AirPods BLE beacon.
 *
 * Scanning runs only while this screen is resumed — the advertisement is free
 * to listen to, but a permanently running BLE scan is not free in battery.
 */
@Composable
private fun AirPodsCard(viewModel: DashboardViewModel) {
    val state by viewModel.airPods.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.startScan() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.startScan()
                Lifecycle.Event.ON_STOP -> viewModel.stopScan()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopScan()
        }
    }

    // Nothing to say before a scan has produced anything.
    if (state is AirPodsScanState.Idle) return

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AirPods", style = MaterialTheme.typography.titleMedium)

            when (val current = state) {
                is AirPodsScanState.Found -> AirPodsDetail(current.beacon, current.rssi)

                AirPodsScanState.PermissionMissing -> {
                    Text(
                        "Allow nearby-device scanning to read AirPods battery and wear " +
                            "state. We only listen to the beacon they broadcast anyway; " +
                            "the permission is declared with neverForLocation, so Android " +
                            "does not ask for your location.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = { permissionLauncher.launch(Manifest.permission.BLUETOOTH_SCAN) },
                    ) { Text("Allow scanning") }
                }

                is AirPodsScanState.Unavailable -> Text(
                    current.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                else -> Text(
                    "Listening for AirPods nearby…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun AirPodsDetail(beacon: AirPodsBeacon, rssi: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            painter = painterResource(DeviceIcons.forPresetId(beacon.model.calibrationPresetId)),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Column {
            Text(beacon.model.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${beacon.color.displayName} · $rssi dBm",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }

    BatteryRow("Left", beacon.leftBatteryPercent, beacon.leftCharging, beacon.leftInEar)
    BatteryRow("Right", beacon.rightBatteryPercent, beacon.rightCharging, beacon.rightInEar)
    BatteryRow("Case", beacon.caseBatteryPercent, beacon.caseCharging, inEar = false)

    Text(
        buildString {
            append(if (beacon.bothInEar) "Both buds in ear" else "Not fully worn")
            append(" · lid ")
            append(if (beacon.lidOpen) "open" else "closed")
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )

    if (beacon.model.calibrationPresetId != null) {
        Text(
            "Detected model — the EQ screen selects the matching calibration preset " +
                "unless you already picked one.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun BatteryRow(label: String, percent: Int?, charging: Boolean, inEar: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.size(width = 48.dp, height = 20.dp))
        if (percent == null) {
            Text(
                "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
        } else {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.weight(1f),
            )
            Text("$percent %", style = MaterialTheme.typography.labelMedium)
        }
        Text(
            buildString {
                if (charging) append("charging")
                if (inEar) {
                    if (isNotEmpty()) append(" · ")
                    append("in ear")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

// ---- devices ----------------------------------------------------------------

@Composable
private fun DeviceListCard(presets: List<CalibrationPreset>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Devices", style = MaterialTheme.typography.titleMedium)
            Text(
                "Bundled calibration presets. Pick the matching one on the EQ screen " +
                    "before a hearing test.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            presets.forEach { preset ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(DeviceIcons.forPresetId(preset.id)),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(preset.displayName, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

// ---- backup -----------------------------------------------------------------

/**
 * Export/import over the Storage Access Framework: the user picks the location,
 * so the app needs no storage permission and never browses files on its own.
 */
@Composable
private fun BackupCard(viewModel: DashboardViewModel) {
    val message by viewModel.backupMessage.collectAsState()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupSchema.MIME_TYPE),
    ) { uri -> uri?.let(viewModel::export) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::import) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Backup", style = MaterialTheme.typography.titleMedium)
            Text(
                "Hearing runs, profiles and the EQ curve as a plain JSON file — for " +
                    "moving to another phone. Local only: there is no cloud and no " +
                    "INTERNET permission.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        exportLauncher.launch(BackupSchema.defaultFileName(System.currentTimeMillis()))
                    },
                ) { Text("Export") }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf(BackupSchema.MIME_TYPE, "*/*")) },
                ) { Text("Import") }
            }
            message?.let { current ->
                Text(
                    current.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (current.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                TextButton(onClick = viewModel::dismissBackupMessage) { Text("Dismiss") }
            }
        }
    }
}
