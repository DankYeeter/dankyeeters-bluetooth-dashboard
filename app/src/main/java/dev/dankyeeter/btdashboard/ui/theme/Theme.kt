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

private val Parchment = Color(0xFFEDE9DF)

/**
 * True black with gold, all the way down.
 *
 * The container roles have to be listed explicitly: `darkColorScheme()` fills
 * every role it is not given with Material's default dark tones, and Card,
 * chips and dialogs all paint from `surfaceContainer*` rather than `surface`.
 * Overriding only `surface` left the app 60% mid-grey with a gold trim, which
 * is not the look this theme exists for.
 */
private val EdgyColorScheme = darkColorScheme(
    primary = Gold.Base,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF2A2109),
    onPrimaryContainer = Gold.Base,
    secondary = Gold.Deep,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF1F1907),
    onSecondaryContainer = Gold.Base,
    tertiary = Gold.Base,
    onTertiary = Color.Black,
    background = Color.Black,
    onBackground = Parchment,
    surface = Color.Black,
    onSurface = Parchment,
    surfaceVariant = Color(0xFF141414),
    onSurfaceVariant = Color(0xFFCFC8B8),
    surfaceTint = Gold.Base,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF101010),
    surfaceContainerHigh = Color(0xFF161616),
    surfaceContainerHighest = Color(0xFF1C1C1C),
    inverseSurface = Parchment,
    inverseOnSurface = Color.Black,
    outline = Gold.Deep,
    outlineVariant = Color(0xFF3A3016),
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

    // Only Edgy paints metal; Material You owns the accent in the other themes.
    ProvideGoldAccents(enabled = theme == AppTheme.EDGY) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
