package dev.dankyeeter.btdashboard.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Three themes per DESIGN.md: Light, Dark (both Material You / dynamic color on
 * Android 12+) and "Edgy" — true black for OLED with gold accents.
 * Stage A ships the colour plumbing; the full Edgy typography/detail pass is
 * part of the UI stages.
 */
enum class AppTheme { LIGHT, DARK, EDGY, SYSTEM }

private val Gold = Color(0xFFC9A227)
private val GoldMuted = Color(0xFF8C6F1A)

private val EdgyColorScheme = darkColorScheme(
    primary = Gold,
    onPrimary = Color.Black,
    secondary = GoldMuted,
    background = Color.Black,
    onBackground = Color(0xFFEDE9DF),
    surface = Color.Black,
    onSurface = Color(0xFFEDE9DF),
    surfaceVariant = Color(0xFF121212),
    outline = GoldMuted,
)

@Composable
fun BtDashboardTheme(
    theme: AppTheme = AppTheme.SYSTEM,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dark = when (theme) {
        AppTheme.LIGHT -> false
        AppTheme.DARK, AppTheme.EDGY -> true
        AppTheme.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        theme == AppTheme.EDGY -> EdgyColorScheme
        dynamicAvailable && dark -> dynamicDarkColorScheme(context)
        dynamicAvailable -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
