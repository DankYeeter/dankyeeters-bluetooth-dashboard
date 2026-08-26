package dev.dankyeeter.btdashboard.ui.screens.wizard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dankyeeter.btdashboard.privileged.PrivilegedConnection
import dev.dankyeeter.btdashboard.system.setup.SetupPhase
import dev.dankyeeter.btdashboard.system.setup.SetupSignals
import dev.dankyeeter.btdashboard.system.setup.SetupStatus

/**
 * Which face of the app the current state calls for, worked out fresh on every
 * look.
 *
 * There is nothing to load and nothing to wait for: a permission is a
 * synchronous question to the OS, and the helper is a [PrivilegedConnection]
 * that already knows. That matters more than it sounds. The old gate read a
 * stored "wizard completed" flag, had to guess an answer while the read was in
 * flight, guessed "yes" - and a freshly installed app therefore walked past its
 * own setup and landed in a dead end. Nothing is stored here, so there is no
 * value to guess.
 *
 * Recomputed when either of the two things that can change it changes: the
 * helper connection, and a [SetupSignals] tick. The tick is bumped on every
 * resume, which covers the only other route - the user granting or revoking
 * something in Settings and coming back.
 */
@Composable
fun rememberSetupPhase(): SetupPhase {
    val context = LocalContext.current
    val environment = remember(context) { AndroidSetupEnvironment(context) }

    val helper by PrivilegedConnection.service.collectAsStateWithLifecycle()
    val signal by SetupSignals.changed.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) SetupSignals.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return remember(helper, signal) { SetupStatus.phase(environment) }
}
