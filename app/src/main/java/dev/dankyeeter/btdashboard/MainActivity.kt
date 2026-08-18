package dev.dankyeeter.btdashboard

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.ui.BtDashboardApp
import dev.dankyeeter.btdashboard.ui.screens.hearing.VolumeKeyLock
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

    /**
     * The hearing test locks the media volume for the duration of a run, so the
     * volume keys are swallowed here (Compose never sees them) and the test
     * screen shows a "volume is locked during the test" notice instead.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (isVolumeKey && VolumeKeyLock.locked) {
            // Notify once per press, not on auto-repeat.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) VolumeKeyLock.consume()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        // Shizuku can be started/stopped while we are backgrounded.
        SystemGraph.shizuku.refresh()
    }
}
