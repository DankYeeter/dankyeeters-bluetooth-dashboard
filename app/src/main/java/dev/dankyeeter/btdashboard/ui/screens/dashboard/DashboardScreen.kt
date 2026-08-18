package dev.dankyeeter.btdashboard.ui.screens.dashboard

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.shizuku.ShizukuState

@Composable
fun DashboardScreen(onOpenOnboarding: () -> Unit) {
    val shizukuState by SystemGraph.shizuku.state.collectAsState()
    val attachment by SystemGraph.eqController.status.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Dashboard", style = MaterialTheme.typography.headlineSmall)

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
                Button(onClick = onOpenOnboarding) { Text("Open setup") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bluetooth", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Codec, sample rate and bitrate of the active device land here " +
                        "(Milestone 2). OEM implementations of getCodecStatus() are " +
                        "inconsistent, so this degrades to \"unknown\" rather than failing.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

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
