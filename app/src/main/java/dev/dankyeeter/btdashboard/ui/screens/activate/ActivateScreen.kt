package dev.dankyeeter.btdashboard.ui.screens.activate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dankyeeter.btdashboard.privileged.PrivilegedConnection

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
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (state) {
                is ActivateState.Idle, is ActivateState.Disclosure ->
                    Button(onClick = onActivate, modifier = Modifier.fillMaxWidth()) {
                        Text("Activate")
                    }

                is ActivateState.Working -> {
                    CircularProgressIndicator()
                    Text(state.step, style = MaterialTheme.typography.bodyLarge)
                }

                is ActivateState.NeedsCode -> PairingCodeEntry(
                    error = state.wrongCode,
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
                }

                is ActivateState.Done -> Unit
            }
        }
    }
}

@Composable
private fun PairingCodeEntry(error: Boolean, onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }

    Text(
        "In Developer options, open Wireless debugging and tap " +
            "\"Pair device with pairing code\". Type the six digits here and " +
            "leave that screen open.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    OutlinedTextField(
        value = code,
        // Digits only, and never more than six: the field is not a place to
        // discover that a typo was possible.
        onValueChange = { entered -> code = entered.filter(Char::isDigit).take(CODE_LENGTH) },
        singleLine = true,
        isError = error,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        supportingText = if (error) {
            { Text("That code was not accepted. Android shows a new one each time.") }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { onSubmit(code) },
        enabled = code.length == CODE_LENGTH,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Pair")
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
