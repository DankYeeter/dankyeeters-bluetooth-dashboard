package dev.dankyeeter.btdashboard

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import dev.dankyeeter.btdashboard.system.SystemGraph
import androidx.compose.ui.graphics.Color
import dev.dankyeeter.btdashboard.system.persist.AccentChoice
import dev.dankyeeter.btdashboard.system.persist.AppearanceChoice
import dev.dankyeeter.btdashboard.ui.BtDashboardApp
import dev.dankyeeter.btdashboard.ui.screens.hearing.VolumeKeyLock
import dev.dankyeeter.btdashboard.ui.theme.AppTheme
import dev.dankyeeter.btdashboard.ui.theme.BtDashboardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // Collected here rather than inside the nav host: the theme wraps
            // every screen, so a change has to recompose the whole tree.
            val appearance by SystemGraph.appearanceStore.choice
                .collectAsStateWithLifecycle(initialValue = AppearanceChoice.SYSTEM)

            val accentArgb by SystemGraph.appearanceStore.accentArgb
                .collectAsStateWithLifecycle(initialValue = AccentChoice.GOLD.argb)

            BtDashboardTheme(
                theme = appearance.toAppTheme(),
                accent = Color(accentArgb),
            ) {
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

}

/** The persisted preference in :core-system, expressed as the UI's theme enum. */
private fun AppearanceChoice.toAppTheme(): AppTheme = when (this) {
    AppearanceChoice.SYSTEM -> AppTheme.SYSTEM
    AppearanceChoice.LIGHT -> AppTheme.LIGHT
    AppearanceChoice.DARK -> AppTheme.DARK
    AppearanceChoice.EDGY -> AppTheme.EDGY
}
