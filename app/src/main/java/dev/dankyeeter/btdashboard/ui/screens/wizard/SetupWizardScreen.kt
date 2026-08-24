package dev.dankyeeter.btdashboard.ui.screens.wizard

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dankyeeter.btdashboard.privileged.PrivilegedConnection
import dev.dankyeeter.btdashboard.ui.screens.activate.ActivateStep
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.setup.SetupStep
import dev.dankyeeter.btdashboard.system.setup.SetupStepState
import dev.dankyeeter.btdashboard.system.setup.SetupStepStatus
import dev.dankyeeter.btdashboard.ui.theme.GoldButton
import dev.dankyeeter.btdashboard.ui.theme.GoldOutlinedButton
import dev.dankyeeter.btdashboard.ui.theme.Panel
import dev.dankyeeter.btdashboard.ui.theme.PanelDivider
import dev.dankyeeter.btdashboard.ui.theme.PanelHeader
import dev.dankyeeter.btdashboard.ui.theme.Pill
import dev.dankyeeter.btdashboard.ui.theme.PillTone

/**
 * The one guided flow that asks for everything the app needs, in order.
 *
 * Two rules shape the whole screen:
 *
 * 1. **Every step re-checks live.** Status is recomputed on resume and on
 *    demand, so a permission granted in Settings turns the step green without
 *    a restart, and a helper that died turns it back. Nothing here is
 *    remembered, so nothing here can be stale.
 * 2. **Only what is optional can be skipped.** The microphone is; Bluetooth
 *    and notifications are not, because the app cannot do its job without the
 *    first and cannot even finish this process without the second - the
 *    pairing code is typed into a notification. Offering "Skip anyway" for
 *    those was an empty offer.
 */
@Composable
fun SetupWizardScreen(
    onDone: () -> Unit,
    viewModel: SetupWizardViewModel = viewModel(),
) {
    val steps by viewModel.steps.collectAsStateWithLifecycle()
    val index by viewModel.currentIndex.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Re-check on every resume: the user just came back from Settings.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Open on the first thing that still needs doing, not on step 1.
    //
    // On a first run those are the same, so nothing changes there. What changes
    // is the returning user: after a reboot the helper is gone and everything
    // else is still Done, and landing on "Bluetooth access — Done" makes the
    // person click Next past four finished steps to reach the one that brought
    // them here. The boot notification's "Activate" button leads straight in,
    // and it should land where the work is.
    //
    // Once per screen instance, and only forwards from the default: after that
    // the user's own navigation owns the index.
    var jumped by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(steps) {
        if (jumped || steps.isEmpty()) return@LaunchedEffect
        jumped = true
        val pending = steps.indexOfFirst { it.status != SetupStepStatus.DONE }
        if (pending > 0) viewModel.goTo(pending)
    }

    val current = steps.getOrNull(index) ?: steps.firstOrNull() ?: return
    val done = steps.count { it.status == SetupStepStatus.DONE }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Same reason as on the Activate screen: the wizard runs in front
            // of the Scaffold, so it carries its own insets. Without this the
            // "Setup" title sits under the status bar and the Next button under
            // the gesture bar - on a fresh install, which is the only time this
            // screen is ever seen.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Setup", style = MaterialTheme.typography.displayMedium)
        Text(
            "Step ${index + 1} of ${viewModel.stepCount} · $done done",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { (index + 1f) / viewModel.stepCount },
            modifier = Modifier.fillMaxWidth(),
        )

        StepPanel(state = current, viewModel = viewModel)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (index > 0) GoldOutlinedButton(onClick = viewModel::previous) { Text("Back") }
            if (index < viewModel.stepCount - 1) {
                GoldButton(onClick = viewModel::next) { Text("Next") }
            } else {
                GoldButton(onClick = { viewModel.finish(onDone) }) { Text("Finish") }
            }
            // Only what is genuinely optional can be waved away. The other
            // steps used to offer "Skip anyway", and it was an empty offer:
            // the app does not work without them, so skipping only moved the
            // dead end one screen further on.
            if (current.step.optional) {
                TextButton(onClick = { viewModel.skip(current.step) }) { Text("Skip") }
            }
        }

        // The whole checklist stays visible: a wizard that hides where you are
        // in the sequence is just a series of modal dialogs.
        Panel {
            PanelHeader(
                "All steps",
                trailing = {
                    Pill(
                        "$done of ${viewModel.stepCount}",
                        tone = if (done == viewModel.stepCount) PillTone.ACCENT else PillTone.NEUTRAL,
                    )
                },
            )
            steps.forEachIndexed { i, state ->
                if (i > 0) PanelDivider()
                StepListRow(
                    state = state,
                    current = i == index,
                    onClick = { viewModel.goTo(i) },
                )
            }
        }

    }
}

/** One line of the checklist. The whole row is the target, not just the text. */
@Composable
private fun StepListRow(state: SetupStepState, current: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            state.step.title,
            style = MaterialTheme.typography.bodyMedium,
            // Where you are, marked by lifting this row out of the secondary
            // text colour rather than by adding a marker glyph — the pill on
            // the right is already carrying one piece of state per row, and a
            // second symbol competing with it is what made the old list of
            // "✓ / • / – / !" text buttons hard to scan.
            color = if (current) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Pill(state.status.label(), tone = state.status.tone())
    }
}

private fun SetupStepStatus.label(): String = when (this) {
    SetupStepStatus.DONE -> "Done"
    SetupStepStatus.PENDING -> "Not set up"
    SetupStepStatus.SKIPPED -> "Skipped"
    SetupStepStatus.BLOCKED -> "Blocked"
}

private fun SetupStepStatus.tone(): PillTone = when (this) {
    SetupStepStatus.DONE -> PillTone.ACCENT
    SetupStepStatus.PENDING, SetupStepStatus.SKIPPED -> PillTone.NEUTRAL
    SetupStepStatus.BLOCKED -> PillTone.WARN
}

/**
 * The extra sentence a status needs, or null when the pill already said it.
 * "Skipped" and "Blocked" both leave an obvious question open; "Done" and
 * "Not set up" do not, and repeating them under the pill is noise.
 */
private fun SetupStepStatus.note(): String? = when (this) {
    SetupStepStatus.SKIPPED -> "Skipped — you can still set it up here."
    SetupStepStatus.BLOCKED -> "Not available right now."
    SetupStepStatus.DONE, SetupStepStatus.PENDING -> null
}

@Composable
private fun StepPanel(state: SetupStepState, viewModel: SetupWizardViewModel) {
    Panel {
        PanelHeader(
            state.step.title,
            trailing = { Pill(state.status.label(), tone = state.status.tone()) },
        )
        state.status.note()?.let {
            Text(it, style = MaterialTheme.typography.labelLarge)
        }
        Text(
            state.step.rationale,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.status != SetupStepStatus.DONE) {
            PanelDivider()
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

                SetupStep.HELPER -> HelperAction()
            }
        }

        GoldOutlinedButton(onClick = viewModel::refresh) { Text("Re-check") }
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The last step: pairing and the helper, which are one action.
 *
 * The phone pairs with its own debugging service, that starts the helper, and
 * the helper grants the app WRITE_SECURE_SETTINGS. There is nothing for the
 * user to do between those, so there is nothing to split into two steps - that
 * division was a leftover from when each half was an ADB command typed on a
 * computer.
 *
 * The activation control is the same one the gate shows, not a copy of it.
 */
@Composable
private fun HelperAction() {
    val context = LocalContext.current
    val helper by PrivilegedConnection.service.collectAsStateWithLifecycle()
    val helperRunning = helper != null

    // Re-read whenever the helper changes: the grant happens the instant a
    // helper attaches, so that is exactly when this answer goes out of date.
    val environment = remember(context) { AndroidSetupEnvironment(context) }
    val secureSettings = remember(helperRunning) { environment.secureSettingsGranted() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("App helper", style = MaterialTheme.typography.titleSmall)
            Pill(
                if (helperRunning) "Running" else "Not running",
                tone = if (helperRunning) PillTone.ACCENT else PillTone.WARN,
            )
        }
        // Reported, not gated on.
        //
        // The step counts as done when a helper is attached, because that is
        // the thing the user acted on. The permission arrives milliseconds
        // later on its own - but if it ever does not, saying so here is the
        // difference between a known limp and a silent one: without it the app
        // cannot close wireless debugging again by itself.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Secure settings", style = MaterialTheme.typography.titleSmall)
            Pill(
                if (secureSettings) "Granted" else "Not granted",
                tone = if (secureSettings) PillTone.ACCENT else PillTone.NEUTRAL,
            )
        }
        ActivateStep()
    }
}
