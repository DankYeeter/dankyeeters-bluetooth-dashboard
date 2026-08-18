package dev.dankyeeter.btdashboard.ui.screens.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.shizuku.SecureSettingsState
import dev.dankyeeter.btdashboard.system.shizuku.ShizukuManager
import dev.dankyeeter.btdashboard.system.shizuku.ShizukuState

/**
 * Guided setup. There is no Play Store in this project, so the install step
 * points at the Shizuku GitHub releases page — we show the URL rather than
 * opening a browser, since the app has no INTERNET permission and should not
 * pretend to fetch anything itself.
 */
@Composable
fun ShizukuOnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val manager = SystemGraph.shizuku
    val state by manager.state.collectAsState()
    val secureSettings = SystemGraph.secureSettings

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Set up system-wide EQ", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Android only lets an app equalize other apps' audio with elevated " +
                "privileges. Shizuku provides these without root, via a one-time " +
                "ADB wireless-debugging pairing.",
            style = MaterialTheme.typography.bodyMedium,
        )

        StepCard(title = "1. Shizuku status", body = state.describe()) {
            when (state) {
                ShizukuState.NotInstalled -> CopyRow(
                    context,
                    label = "Download the APK from",
                    value = ShizukuManager.SHIZUKU_RELEASES_URL,
                )

                ShizukuState.InstalledNotRunning -> Text(
                    "Open Shizuku and start the service:\n" +
                        "1. Settings → System → Developer options → Wireless debugging: on\n" +
                        "2. In Shizuku: \"Pair device with pairing code\", enter the code " +
                        "shown by wireless debugging\n" +
                        "3. Tap \"Start\" in Shizuku\n\n" +
                        "This must be repeated after every reboot — wireless debugging " +
                        "turns itself off.",
                    style = MaterialTheme.typography.bodySmall,
                )

                ShizukuState.NotAuthorized, ShizukuState.PermissionDenied -> Button(
                    onClick = { manager.requestPermission() },
                ) { Text("Authorize this app in Shizuku") }

                is ShizukuState.Ready -> Text(
                    "Shizuku is ready. The EQ can attach globally.",
                    style = MaterialTheme.typography.bodySmall,
                )

                is ShizukuState.Error -> Text(
                    "Shizuku could not be reached. The app still works in session mode.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = { manager.refresh() }) { Text("Re-check") }
        }

        val secure = secureSettings.state()
        StepCard(
            title = "2. WRITE_SECURE_SETTINGS (optional)",
            body = if (secure == SecureSettingsState.GRANTED) {
                "Granted. Persistent system-level settings are available."
            } else {
                "Not granted. Some persistent settings stay unavailable. You can grant " +
                    "it yourself over ADB — the app never does this for you."
            },
        ) {
            if (secure != SecureSettingsState.GRANTED) {
                CopyRow(context, label = "ADB command", value = secureSettings.adbGrantCommand())
            }
        }

        StepCard(
            title = "3. Headphone app",
            body = "Set the EQ inside the Focal & Naim / Noble FoKus app to Flat. Their " +
                "EQ runs in the headphone's own DSP after Bluetooth transmission, so it " +
                "adds to ours instead of being replaced. This app never modifies another " +
                "app's stored settings.",
        ) {}

        TextButton(onClick = onDone) { Text("Done") }
    }
}

@Composable
private fun StepCard(title: String, body: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

@Composable
private fun CopyRow(context: Context, label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        }) { Text("Copy") }
    }
}

private fun ShizukuState.describe(): String = when (this) {
    ShizukuState.NotInstalled -> "Shizuku is not installed."
    ShizukuState.InstalledNotRunning -> "Shizuku is installed but the service is not running."
    ShizukuState.NotAuthorized -> "Shizuku is running but this app is not authorized yet."
    ShizukuState.PermissionDenied -> "Authorization was denied."
    is ShizukuState.Ready -> "Ready (uid $uid, API v$apiVersion)."
    is ShizukuState.Error -> "Unavailable: $message"
}
