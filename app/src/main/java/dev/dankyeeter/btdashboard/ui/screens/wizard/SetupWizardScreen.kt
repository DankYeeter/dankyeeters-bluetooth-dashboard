package dev.dankyeeter.btdashboard.ui.screens.wizard

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.setup.SetupStep
import dev.dankyeeter.btdashboard.system.setup.SetupStepState
import dev.dankyeeter.btdashboard.system.setup.SetupStepStatus
import dev.dankyeeter.btdashboard.system.shizuku.ShizukuManager
import dev.dankyeeter.btdashboard.system.shizuku.ShizukuState
import dev.dankyeeter.btdashboard.ui.screens.devices.CopyableCommand
import dev.dankyeeter.btdashboard.ui.theme.GoldCard
import dev.dankyeeter.btdashboard.ui.theme.GoldTitle
import dev.dankyeeter.btdashboard.ui.theme.GoldButton
import dev.dankyeeter.btdashboard.ui.theme.GoldOutlinedButton

/**
 * The one guided flow that asks for everything the app needs, in order.
 *
 * Two rules shape the whole screen:
 *
 * 1. **Every step re-checks live.** Status is recomputed on resume and on
 *    demand, so a permission granted in Settings turns the step green without
 *    a restart, and a Shizuku service that died turns it back.
 * 2. **Every step can be skipped.** Only Bluetooth access is marked required,
 *    and even that is not enforced — the app degrades instead of blocking. A
 *    wizard that traps the user on a step they cannot complete right now
 *    (Shizuku needs a computer!) would be worse than one they can leave.
 */
@Composable
fun SetupWizardScreen(
    onDone: () -> Unit,
    viewModel: SetupWizardViewModel = viewModel(),
) {
    val steps by viewModel.steps.collectAsState()
    val index by viewModel.currentIndex.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Re-check on every resume: the user just came back from Settings.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val current = steps.getOrNull(index) ?: steps.firstOrNull() ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GoldTitle("Setup", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Step ${index + 1} of ${viewModel.stepCount} · " +
                "${steps.count { it.status == SetupStepStatus.DONE }} done",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        LinearProgressIndicator(
            progress = { (index + 1f) / viewModel.stepCount },
            modifier = Modifier.fillMaxWidth(),
        )

        StepCard(state = current, viewModel = viewModel)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (index > 0) GoldOutlinedButton(onClick = viewModel::previous) { Text("Back") }
            if (index < viewModel.stepCount - 1) {
                GoldButton(onClick = viewModel::next) { Text("Next") }
            } else {
                GoldButton(onClick = { viewModel.finish(onDone) }) { Text("Finish") }
            }
            TextButton(onClick = { viewModel.skip(current.step) }) {
                Text(if (current.step.optional) "Skip" else "Skip anyway")
            }
        }

        HorizontalDivider()

        // The whole checklist stays visible: a wizard that hides where you are
        // in the sequence is just a series of modal dialogs.
        Text("All steps", style = MaterialTheme.typography.titleSmall)
        steps.forEachIndexed { i, state ->
            TextButton(onClick = { viewModel.goTo(i) }) {
                Text("${state.status.marker()} ${state.step.title}")
            }
        }

        TextButton(onClick = { viewModel.finish(onDone) }) { Text("Close setup") }
    }
}

private fun SetupStepStatus.marker(): String = when (this) {
    SetupStepStatus.DONE -> "✓"
    SetupStepStatus.PENDING -> "•"
    SetupStepStatus.SKIPPED -> "–"
    SetupStepStatus.BLOCKED -> "!"
}

private fun SetupStepStatus.describe(): String = when (this) {
    SetupStepStatus.DONE -> "Done."
    SetupStepStatus.PENDING -> "Not set up yet."
    SetupStepStatus.SKIPPED -> "Skipped — you can still set it up here."
    SetupStepStatus.BLOCKED -> "Not available right now."
}

@Composable
private fun StepCard(state: SetupStepState, viewModel: SetupWizardViewModel) {
    GoldCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.step.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "${state.status.marker()} ${state.status.describe()}",
                style = MaterialTheme.typography.labelLarge,
                color = if (state.status == SetupStepStatus.DONE) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(state.step.rationale, style = MaterialTheme.typography.bodySmall)

            if (state.status != SetupStepStatus.DONE) {
                when (state.step) {
                    SetupStep.BLUETOOTH -> PermissionAction(
                        viewModel,
                        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                        "Allow Bluetooth access",
                    )

                    SetupStep.MICROPHONE -> PermissionAction(
                        viewModel,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        "Allow the microphone",
                    )

                    SetupStep.NOTIFICATIONS -> PermissionAction(
                        viewModel,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        "Allow notifications",
                    )

                    SetupStep.SHIZUKU -> ShizukuAction()
                    SetupStep.SECURE_SETTINGS -> SecureSettingsAction()
                }
            }

            GoldOutlinedButton(onClick = viewModel::refresh) { Text("Re-check") }
        }
    }
}

@Composable
private fun PermissionAction(
    viewModel: SetupWizardViewModel,
    permissions: Array<String>,
    label: String,
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refresh() }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GoldButton(onClick = { launcher.launch(permissions) }) { Text(label) }
        Text(
            "If Android does not show a dialog, the permission was denied permanently — " +
                "grant it in Settings → Apps → this app → Permissions.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * Reuses the three-state Shizuku content from the existing onboarding stepper:
 * not installed → not running → not authorized. Each state gets exactly one
 * next action, because "install, pair and authorize Shizuku" as a single
 * instruction is where people give up.
 */
@Composable
private fun ShizukuAction() {
    val context = LocalContext.current
    val manager = SystemGraph.shizuku
    val state by manager.state.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            ShizukuState.NotInstalled -> {
                Text(
                    "Shizuku is not installed. There is no Play Store link in this " +
                        "project — download the APK from the official GitHub releases " +
                        "page and sideload it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                CopyableCommand(context, ShizukuManager.SHIZUKU_RELEASES_URL)
            }

            ShizukuState.InstalledNotRunning -> Text(
                "Shizuku is installed but not running:\n" +
                    "1. Settings → System → Developer options → Wireless debugging: on\n" +
                    "2. In Shizuku: \"Pair device with pairing code\", enter the code " +
                    "wireless debugging shows\n" +
                    "3. Tap \"Start\" in Shizuku\n\n" +
                    "This has to be redone after every reboot, because Android turns " +
                    "wireless debugging off at boot. Shizuku's own start-on-boot option " +
                    "helps where the OS allows it.",
                style = MaterialTheme.typography.bodySmall,
            )

            ShizukuState.NotAuthorized, ShizukuState.PermissionDenied -> Button(
                onClick = { manager.requestPermission() },
            ) { Text("Authorize this app in Shizuku") }

            is ShizukuState.Ready -> Text(
                "Shizuku is ready — the EQ can attach globally.",
                style = MaterialTheme.typography.bodySmall,
            )

            is ShizukuState.Error -> Text(
                "Shizuku could not be reached: ${(state as ShizukuState.Error).message}. " +
                    "The app keeps working in session mode, which only reaches players " +
                    "that broadcast their audio session.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SecureSettingsAction() {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Connect the phone to a computer with ADB (or run it from a paired " +
                "wireless-debugging shell) and execute:",
            style = MaterialTheme.typography.bodySmall,
        )
        CopyableCommand(context, SystemGraph.secureSettings.adbGrantCommand())
    }
}
