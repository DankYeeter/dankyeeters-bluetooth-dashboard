package dev.dankyeeter.btdashboard.ui.screens.activate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import dev.dankyeeter.btdashboard.system.boot.ActivationSteps
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dankyeeter.btdashboard.privileged.PrivilegedConnection
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * One button, and only what the moment needs after that.
 *
 * This is where the boot notification lands. The equaliser and the Bluetooth
 * settings are off because the helper does not survive a restart, and the
 * screen exists to fix exactly that - so it shows one control at a time and
 * closes itself the moment the helper reports in. Nothing to acknowledge,
 * nothing to dismiss.
 *
 * The code field only appears when `adbd` has actually asked for it. Showing it
 * up front would be asking the user to hunt through developer options for a
 * number the app may not even need.
 */
@Composable
fun ActivateScreen(
    state: ActivateState,
    onActivate: () -> Unit,
    onSubmitCode: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDisclosureAccepted: () -> Unit,
    onDone: () -> Unit,
) {
    val service by PrivilegedConnection.service.collectAsStateWithLifecycle()

    // Arriving with a live helper means the problem solved itself while the
    // notification sat in the shade.
    LaunchedEffect(service, state) {
        if (service != null || state is ActivateState.Done) onDone()
    }

    if (state is ActivateState.Disclosure) {
        LocalConnectionDisclosure(onAccept = onDisclosureAccepted, onDismiss = onDone)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // This screen is drawn outside the Scaffold - it replaces the whole
            // app while the helper is missing - so nothing else keeps it clear
            // of the status and gesture bars. Since targetSdk 36 the app cannot
            // opt out of edge-to-edge, so it has to say where its content is
            // allowed to live.
            //
            // safeDrawing rather than systemBars because of the pairing-code
            // field below: it also holds the keyboard's inset, and a code field
            // hidden behind the keyboard is a dead end.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        ActivateActions(
            state = state,
            onActivate = onActivate,
            onSubmitCode = onSubmitCode,
            onOpenSettings = onOpenSettings,
        )
    }
}

/**
 * The activation itself, without any framing.
 *
 * Two places show this: the gate, which is the whole screen while the helper is
 * missing, and the last step of the setup process, where it sits inside a
 * panel with the other steps. They must be the same thing - a second copy would
 * drift, and which copy the user sees would depend on how they arrived.
 */
@Composable
fun ActivateActions(
    state: ActivateState,
    onActivate: () -> Unit,
    onSubmitCode: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        when (state) {
            is ActivateState.Idle, is ActivateState.Disclosure -> {
                Button(onClick = onActivate, modifier = Modifier.fillMaxWidth()) {
                    Text("Activate")
                }
                ActivationHelp()
            }

            is ActivateState.Working -> {
                CircularProgressIndicator()
                Text(state.step, style = MaterialTheme.typography.bodyLarge)
            }

            is ActivateState.NeedsCode -> PairingCodeEntry(
                error = state.wrongCode,
                onOpenSettings = onOpenSettings,
                onSubmit = onSubmitCode,
            )

            is ActivateState.Failed -> {
                Text(
                    state.reason,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onActivate, modifier = Modifier.fillMaxWidth()) {
                    Text("Try again")
                }
                // Offered here already open: a failed attempt is the one
                // moment the steps are worth reading.
                ActivationHelp(initiallyExpanded = true)
            }

            is ActivateState.Done -> Unit
        }
    }
}

/**
 * Settings' own extra for "scroll to this entry and highlight it".
 *
 * Not public API, and it fails harmlessly: an unrecognised extra leaves the
 * user on the Developer options list, which is where they were going anyway.
 * The alternative is a paragraph explaining where to scroll.
 */
private const val SETTINGS_HIGHLIGHT_KEY = ":settings:fragment_args_key"
private const val WIRELESS_DEBUGGING_KEY = "toggle_adb_wireless"

/**
 * Everything an activation surface needs, wired to the view model once.
 *
 * The two surfaces below differ in framing, not in behaviour - which view
 * model, and which intent opens Developer options, has no business existing
 * twice.
 */
private class ActivateWiring(
    val state: ActivateState,
    val onActivate: () -> Unit,
    val onSubmitCode: (String) -> Unit,
    val onOpenSettings: () -> Unit,
    val onDisclosureAccepted: () -> Unit,
    val onDisclosureDismissed: () -> Unit,
)

@Composable
private fun rememberActivateWiring(): ActivateWiring {
    val viewModel: ActivateViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    return ActivateWiring(
        state = state,
        onActivate = viewModel::activate,
        onSubmitCode = viewModel::submitCode,
        // Straight to Developer options, with the wireless debugging entry
        // asked for by name. Describing where to find it would be one more
        // thing to read while holding a six-digit number in your head.
        onOpenSettings = {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(SETTINGS_HIGHLIGHT_KEY, WIRELESS_DEBUGGING_KEY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .onFailure { Log.w("ActivateScreen", "cannot open developer options", it) }
        },
        onDisclosureAccepted = viewModel::onDisclosureAccepted,
        onDisclosureDismissed = viewModel::dismissDisclosure,
    )
}

/**
 * Activation as the whole screen: the gate, and the route the boot
 * notification opens.
 */
@Composable
fun ActivateRoute(onDone: () -> Unit) {
    val wiring = rememberActivateWiring()
    ActivateScreen(
        state = wiring.state,
        onActivate = wiring.onActivate,
        onSubmitCode = wiring.onSubmitCode,
        onOpenSettings = wiring.onOpenSettings,
        onDisclosureAccepted = wiring.onDisclosureAccepted,
        onDone = onDone,
    )
}

/**
 * The same activation inside the last step of the setup process.
 *
 * No `onDone` here, because there is nothing to dismiss: a helper reporting in
 * changes what the live setup state says, and the process moves on by itself.
 */
@Composable
fun ActivateStep() {
    val wiring = rememberActivateWiring()
    if (wiring.state is ActivateState.Disclosure) {
        LocalConnectionDisclosure(
            onAccept = wiring.onDisclosureAccepted,
            onDismiss = wiring.onDisclosureDismissed,
        )
    }
    ActivateActions(
        state = wiring.state,
        onActivate = wiring.onActivate,
        onSubmitCode = wiring.onSubmitCode,
        onOpenSettings = wiring.onOpenSettings,
    )
}

/**
 * Two taps, six digits, done - and no hunting in between.
 *
 * The friction here is switching apps, so everything that can be removed is.
 * The button goes straight to Developer options instead of describing where to
 * find them, the keyboard is already up and numeric when the user comes back,
 * and the sixth digit submits by itself.
 *
 * What cannot be removed: the code has to be read off Android's own dialog and
 * typed. No app can generate it, read it, or reach it through the clipboard -
 * that is precisely the barrier stopping an app from granting itself shell
 * access, so the two switches are the floor, not an oversight.
 */
@Composable
private fun PairingCodeEntry(
    error: Boolean,
    onOpenSettings: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    // Coming back from Settings with a code in mind, the last thing anyone
    // wants is to tap a field first.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Text(
        "Open Wireless debugging, tap \"Pair device with pairing code\", and " +
            "type the six digits here. Leave that screen open while you do.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    OutlinedTextField(
        value = code,
        // Digits only, never more than six: the field is not a place to
        // discover that a typo was possible.
        onValueChange = { entered ->
            code = entered.filter(Char::isDigit).take(CODE_LENGTH)
            // The sixth digit is unambiguous - there is nothing left to confirm,
            // so confirming it would only be another tap.
            if (code.length == CODE_LENGTH) onSubmit(code)
        },
        singleLine = true,
        isError = error,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        supportingText = if (error) {
            { Text("That code was not accepted. Android shows a new one each time.") }
        } else {
            null
        },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus),
    )
    Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
        Text("Open Wireless debugging")
    }
}

/**
 * Said once, before the first connection, because it changes what the app is.
 *
 * The app carried no INTERNET permission for its whole life, and that was a
 * structural guarantee rather than a promise. Starting the helper without a
 * computer needs a socket, and Android gates every socket - loopback
 * included - behind that permission. The guarantee is therefore gone, and the
 * honest thing is to say so before the first use rather than bury it in a
 * settings screen.
 */
@Composable
private fun LocalConnectionDisclosure(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("This connects to your phone") },
        text = {
            Text(
                "To start its helper without a computer, this app opens a " +
                    "connection to the debugging service running on this phone. " +
                    "It always connects to 127.0.0.1 — your device talking to " +
                    "itself — so nothing is sent anywhere.\n\n" +
                    "Android requires the internet permission for any connection " +
                    "at all, including this one. The app has no code that " +
                    "contacts a remote server, but you are trusting that, not a " +
                    "locked door. Continue at your own discretion.",
            )
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

private const val CODE_LENGTH = 6

/**
 * The setup procedure, folded away.
 *
 * Every later activation is one tap, so putting these on the face of the screen
 * would mean showing a numbered list to someone who needs none of it. Collapsed
 * it is a single quiet line; the person who is stuck taps it.
 *
 * The text is [ActivationSteps], shared with both notifications - a procedure
 * described in three places is a procedure that will eventually be described
 * three different ways.
 */
@Composable
private fun ActivationHelp(initiallyExpanded: Boolean = false) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Hide steps" else "Show steps")
    }
    if (expanded) {
        Text(
            ActivationSteps.FULL_IN_APP,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
