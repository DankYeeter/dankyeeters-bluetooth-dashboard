package dev.dankyeeter.btdashboard.ui.screens.monitor

import androidx.compose.runtime.Composable
import dev.dankyeeter.btdashboard.ui.screens.StageStub

@Composable
fun MonitorScreen() {
    StageStub(
        title = "Link Monitor",
        body = "Bluetooth link-quality history: codec and bitrate changes, RSSI, " +
            "A2DP state and active-device takeovers.",
        owner = "Milestone 2",
    )
}
