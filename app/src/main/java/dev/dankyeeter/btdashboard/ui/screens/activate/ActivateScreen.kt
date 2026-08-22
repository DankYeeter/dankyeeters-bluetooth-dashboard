package dev.dankyeeter.btdashboard.ui.screens.activate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dankyeeter.btdashboard.privileged.PrivilegedConnection

/**
 * One button, and nothing else.
 *
 * This is where the boot notification's "Activate" lands. It exists because the
 * setup wizard was the wrong destination for it: the wizard is a five-step
 * review of every permission the app wants, and after a reboot the user has
 * exactly one thing broken and no interest in auditing the other four.
 *
 * The screen closes itself the moment the helper reports in. Nothing to
 * acknowledge, nothing to dismiss - the reason to be here has gone, so the
 * screen goes.
 *
 * @param onActivate what the button does. Today that is putting the ADB command
 *   on the clipboard, which still needs a computer; the screen is shaped for the
 *   automatic start that is meant to replace it, so that when it arrives only
 *   this lambda changes.
 * @param onDone called once the helper is connected, or when the user leaves.
 */
@Composable
fun ActivateScreen(
    onActivate: () -> Unit,
    onDone: () -> Unit,
) {
    val service by PrivilegedConnection.service.collectAsStateWithLifecycle()
    var working by remember { mutableStateOf(false) }

    // Arriving with a live helper means the problem solved itself while the
    // notification sat in the shade - the user should not have to look at a
    // screen about a thing that is already fine.
    LaunchedEffect(service) {
        if (service != null) onDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (working) {
                CircularProgressIndicator()
                Text(
                    "Waiting for the helper…",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                Button(
                    onClick = {
                        working = true
                        onActivate()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Activate")
                }
            }
        }
    }
}
