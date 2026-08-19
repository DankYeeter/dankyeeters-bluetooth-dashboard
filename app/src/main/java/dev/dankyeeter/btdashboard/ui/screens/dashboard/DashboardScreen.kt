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
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.shizuku.ShizukuState
import dev.dankyeeter.btdashboard.transfer.BackupSchema
import dev.dankyeeter.btdashboard.ui.icons.DeviceIcons
import dev.dankyeeter.btdashboard.ui.theme.GoldRule
import dev.dankyeeter.btdashboard.ui.theme.GoldTitle
import dev.dankyeeter.btdashboard.ui.theme.GoldCard
import dev.dankyeeter.btdashboard.ui.theme.GoldButton
import dev.dankyeeter.btdashboard.ui.theme.GoldOutlinedButton

@Composable
fun DashboardScreen(
    onOpenOnboarding: () -> Unit,
    viewModel: DashboardViewModel = viewModel(),
    onWatchLive: () -> Unit = {},
    onOpenWizard: () -> Unit = {},
    onOpenDeviceProfiles: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
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
        GoldTitle("Dashboard", style = MaterialTheme.typography.headlineSmall)
        GoldRule()

        SetupStatusCard(viewModel, onOpenWizard)

        GoldCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GoldTitle("System access")
                Text(
                    when (shizukuState) {
                        is ShizukuState.Ready -> "Shizuku ready — global EQ possible."
                        else -> "Shizuku not ready — EQ limited to session mode."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("EQ attachment: $attachment", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoldButton(onClick = onOpenWizard) { Text("Setup wizard") }
                    GoldOutlinedButton(onClick = onOpenOnboarding) { Text("Shizuku details") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoldOutlinedButton(onClick = onOpenDeviceProfiles) { Text("Device profiles") }
                    GoldOutlinedButton(onClick = onOpenSettings) { Text("Settings") }
                }
            }
        }

        // Milestone 2 monitor sections; bodies live in BluetoothSection.kt.
        BluetoothCodecSection(onWatchLive = onWatchLive)
        ForeignEqSection()

        BackupCard(viewModel)

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

    GoldCard {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            GoldButton(onClick = onOpenWizard) { Text("Finish setup") }
        }
    }
}

// ---- now playing ------------------------------------------------------------

// ---- devices ----------------------------------------------------------------

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

    GoldCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GoldTitle("Backup")
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
