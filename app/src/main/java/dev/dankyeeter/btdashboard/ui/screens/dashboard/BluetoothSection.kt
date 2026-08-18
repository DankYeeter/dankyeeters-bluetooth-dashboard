package dev.dankyeeter.btdashboard.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Codec dashboard section: connected devices, the negotiated codec as a badge,
 * a "watch live" quick action, and the foreign-EQ warning surface.
 *
 * Lives in its own file so the shared [DashboardScreen] only gains two calls —
 * another worker is editing that file in parallel.
 */
@Composable
fun BluetoothCodecSection(
    onWatchLive: () -> Unit,
    viewModel: BluetoothDashboardViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Bluetooth, contentDescription = null)
                    Text("Bluetooth audio", style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = viewModel::refresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    Text(" Refresh")
                }
            }

            when {
                state.loading -> Text("Reading codec status…", style = MaterialTheme.typography.bodySmall)

                !state.profileAvailable -> Text(
                    "The Bluetooth audio profile is not reachable. Codec details need " +
                        "the Bluetooth permission and a connected A2DP device.",
                    style = MaterialTheme.typography.bodySmall,
                )

                state.rows.isEmpty() -> Text(
                    "No Bluetooth audio device connected.",
                    style = MaterialTheme.typography.bodySmall,
                )

                else -> state.rows.forEach { row -> DeviceRow(row) }
            }

            if (state.rows.isNotEmpty()) {
                OutlinedButton(onClick = {
                    viewModel.startWatchLive()
                    onWatchLive()
                }) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = null)
                    Text("  Watch live (10 s capture)")
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(row: DeviceCodecRow) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(row.device.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    buildString {
                        append(row.device.address)
                        if (row.device.isActive) append(" · active")
                        if (row.device.isPlaying) append(" · playing")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            AssistChip(onClick = {}, label = { Text(row.codecBadge) })
        }
        // Honest degradation: say why the codec is unknown instead of hiding it.
        row.codecNote?.takeIf { !row.codecKnown }?.let {
            Text("Reason: $it", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Foreign-EQ warning surface with app attribution (PLAN.md, promoted to v1). */
@Composable
fun ForeignEqSection(viewModel: BluetoothDashboardViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val scan = state.foreignEq

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Text("Other equalizers", style = MaterialTheme.typography.titleMedium)
            }

            when {
                scan == null -> Text(
                    "Another equalizer stacking on top of ours silently undoes the " +
                        "compensation curve. Run a check to see what else is attached.",
                    style = MaterialTheme.typography.bodySmall,
                )

                !scan.available -> Text(
                    scan.unavailableReason ?: "Check unavailable.",
                    style = MaterialTheme.typography.bodySmall,
                )

                scan.warnings.isEmpty() -> Text(
                    "No other equalizer found on the active audio sessions.",
                    style = MaterialTheme.typography.bodySmall,
                )

                else -> scan.warnings.forEach { warning ->
                    Text(warning.message, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Session ${warning.sessionId} — set that app's EQ to flat, " +
                            "or disable it while using ours.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            TextButton(onClick = viewModel::scanForeignEq) { Text("Check now") }
        }
    }
}
