package dev.dankyeeter.btdashboard.ui.screens.wizard

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dankyeeter.btdashboard.privileged.PrivilegedConnection
import dev.dankyeeter.btdashboard.ui.screens.activate.ActivateStep
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.setup.SetupStep
import dev.dankyeeter.btdashboard.system.setup.SetupStepState
import dev.dankyeeter.btdashboard.system.setup.SetupStepStatus
import dev.dankyeeter.btdashboard.ui.theme.ExplainedHeader
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
        // The bar says where you are and the "N of 4" pill on the checklist
        // below says how much is done. A line spelling out both in words was a
        // third rendering of the same two numbers.
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
}

private fun SetupStepStatus.tone(): PillTone = when (this) {
    SetupStepStatus.DONE -> PillTone.ACCENT
    SetupStepStatus.PENDING, SetupStepStatus.SKIPPED -> PillTone.NEUTRAL
}

/**
 * The extra sentence a status needs, or null when the pill already said it.
 *
 * Only "Skipped" leaves a question open, and the answer has two halves: what
 * the app will do without this step, and that the decision is not final. The
 * note used to carry the second half alone, which told the user how to undo a
 * choice without ever saying what the choice costs.
 *
 * Per step, because the cost is: the microphone is the only step that can be
 * skipped today, but a generic "you can set it up later" would be exactly the
 * sentence this replaces.
 */
private fun SetupStepState.note(): String? = when (status) {
    SetupStepStatus.SKIPPED -> when (step) {
        SetupStep.MICROPHONE ->
            "Skipped — the hearing test will run without the room-noise check. " +
                "You can set it up here any time."

        else -> "Skipped — you can still set it up here any time."
    }

    SetupStepStatus.DONE, SetupStepStatus.PENDING -> null
}

@Composable
private fun StepPanel(state: SetupStepState, viewModel: SetupWizardViewModel) {
    Panel {
        // The paragraph goes behind the question mark and one line stays on the
        // face of the step. Four steps each fronted by a four-line rationale is
        // a wall of text between the user and the four buttons they came for -
        // and the reasoning still matters to whoever asks for it, so it moves
        // rather than goes.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            ExplainedHeader(
                state.step.title,
                explanation = state.step.rationale,
                modifier = Modifier.weight(1f),
            )
            Pill(state.status.label(), tone = state.status.tone())
        }
        state.note()?.let {
            Text(it, style = MaterialTheme.typography.labelLarge)
        }
        Text(state.step.summary, style = MaterialTheme.typography.bodyMedium)

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

                SetupStep.NOTIFICATIONS -> NotificationsAction(viewModel)

                SetupStep.HELPER -> HelperAction()
            }
            // Only where there is something to re-check. On a finished step it
            // offered to look again at an answer the screen already re-reads on
            // every resume, and the one outcome it could produce - the step
            // falling back to "Not set up" - is not what the button promises.
            GoldOutlinedButton(onClick = viewModel::refresh) { Text("Re-check") }
        }
    }
}

/**
 * Notifications, which are a runtime permission only from Android 13 on.
 *
 * Below that there is no dialog to show and nothing to grant: notifications
 * are on unless the user has turned them off, and the only way back is the
 * app's own notification settings. Asking for a permission the platform has
 * never heard of would put up a button that silently does nothing - on the one
 * step that cannot be skipped.
 */
@Composable
private fun NotificationsAction(viewModel: SetupWizardViewModel) {
    val context = LocalContext.current

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PermissionAction(
            viewModel,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            "Allow notifications",
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GoldButton(
            onClick = {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
                    .onFailure { Log.w("SetupWizard", "cannot open notification settings", it) }
            },
        ) { Text("Open notification settings") }
        Text(
            "On this Android version notifications are switched on or off in " +
                "settings rather than granted in a dialog.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionAction(
    viewModel: SetupWizardViewModel,
    permissions: Array<String>,
    label: String,
) {
    val context = LocalContext.current

    // Said only once it has happened.
    //
    // Android stops showing the dialog after two refusals, and the button then
    // does nothing visible - which reads as a broken app rather than as a
    // decision the user made earlier. But printing that warning before the
    // button has ever been pressed tells a first-time user that something is
    // already wrong, so it waits for a launch that came back with nothing
    // granted. That is the exact signature of a permanently denied permission.
    var locked by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        locked = results.isNotEmpty() && results.values.none { it }
        viewModel.refresh()
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GoldButton(onClick = { launcher.launch(permissions) }) { Text(label) }
        if (locked) {
            Text(
                "No dialog appeared, so Android has this one locked. Grant it in " +
                    "the app's settings.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The app knows where that screen is, so it opens it rather than
            // describing the path through Settings.
            GoldOutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                        .onFailure { Log.w("SetupWizard", "cannot open app settings", it) }
                },
            ) { Text("Open app settings") }
        }
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
        ExplainedHeader(
            "App helper",
            explanation = "The helper is a small process running at shell level — the same " +
                "access \"adb shell\" has, not root. It dies whenever the phone restarts and " +
                "this screen brings it back. The moment it attaches it also grants the app " +
                "the one system permission it needs; that grant, unlike the helper, survives " +
                "a reboot.",
        )
        // One row, because there is one thing to know: whether the helper is
        // there, and whether it did its job on the way in.
        //
        // This used to be two rows - "App helper: Not running" above "Secure
        // settings: Not granted" - which is two negatives for one situation,
        // and the second is not a thing the user can act on separately. The
        // permission is still not swept away: it arrives milliseconds after the
        // helper attaches, and on the rare occasion it does not, the pill says
        // so. Without it the app cannot close wireless debugging by itself.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Status", style = MaterialTheme.typography.titleSmall)
            Pill(
                when {
                    helperRunning && secureSettings -> "Running"
                    helperRunning -> "Running, permission missing"
                    else -> "Not running"
                },
                tone = if (helperRunning && secureSettings) PillTone.ACCENT else PillTone.WARN,
            )
        }
        ActivateStep()
    }
}
