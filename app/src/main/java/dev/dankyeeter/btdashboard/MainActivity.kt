package dev.dankyeeter.btdashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.ui.BtDashboardApp
import dev.dankyeeter.btdashboard.ui.theme.BtDashboardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BtDashboardTheme {
                BtDashboardApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Shizuku can be started/stopped while we are backgrounded.
        SystemGraph.shizuku.refresh()
    }
}
