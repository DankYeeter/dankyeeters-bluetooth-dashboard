package dev.dankyeeter.btdashboard.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

/** Whichever of black or parchment is actually readable on [background]. */
private fun readableOn(background: Color): Color =
    if (Contrast.ratio(Color.Black, background) >= Contrast.ratio(Parchment, background)) {
        Color.Black
    } else {
        Parchment
    }

/**
 * True black with gold, all the way down.
 *
 * The container roles have to be listed explicitly: `darkColorScheme()` fills
 * every role it is not given with Material's default dark tones, and Card,
 * chips and dialogs all paint from `surfaceContainer*` rather than `surface`.
 * Overriding only `surface` left the app 60% mid-grey with a gold trim, which
 * is not the look this theme exists for.
 */
internal val EdgyColorScheme = darkColorScheme(
    primary = Gold.Base,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF2A2109),
    onPrimaryContainer = Gold.Base,
    secondary = Gold.Deep,
    // Deep gold is dark: black on it measures 3.1:1, under the 4.5:1 minimum
    // for body text. Parchment clears 5.4:1. Caught by ContrastTest, not by eye
    // — on a dark theme an unreadable label just looks like a dim one.
    onSecondary = Parchment,
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
    // `outline` carries secondary *text*, not just borders. Dark gold on black
    // measures about 2:1 — unreadable at 13 sp. Borders keep the gold via
    // outlineVariant; running text gets a warm grey that clears 7:1.
    outline = Color(0xFF9B948A),
    outlineVariant = Gold.Deep,
)

@Composable
fun BtDashboardTheme(
    theme: AppTheme = AppTheme.SYSTEM,
    accent: Color = Gold.Base,
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

    // Gold keeps its hand-tuned stops; anything else is derived. The
    // derivation reproduces the original to within a tenth of a channel, but
    // routing gold through it anyway would move the reference by a hair for no
    // reason at all.
    //
    // remembered: this composable is the root of the whole tree, so it
    // recomposes often, and deriving six HSL stops plus a ColorScheme copy on
    // every pass was pure waste — the inputs only change on a settings tap.
    val palette = remember(accent) {
        if (accent == Gold.Base) Gold.asPalette else MetalPalette.from(accent)
    }

    // The Edgy scheme's accent roles have to follow the chosen metal too,
    // otherwise a silver theme would still draw gold switches and sliders.
    val scheme = remember(theme, colorScheme, palette) {
        if (theme == AppTheme.EDGY) colorScheme.withAccent(palette) else colorScheme
    }

    // Only Edgy paints metal; Material You owns the accent in the other themes.
    ProvideGoldAccents(enabled = theme == AppTheme.EDGY, palette = palette) {
        MaterialTheme(colorScheme = scheme, typography = BtTypography, content = content)
    }
}

/**
 * Repoints the accent roles of a scheme at [palette].
 *
 * `onPrimary` and `onSecondary` are recomputed rather than copied: the fill
 * changed, so the colour that has to stay readable on top of it is a different
 * question than it was. Black on deep gold already measured 3.1:1 once (see
 * ContrastTest); picking the readable option here rather than a fixed constant
 * is what stops a chosen accent from reintroducing that.
 */
private fun ColorScheme.withAccent(palette: MetalPalette): ColorScheme = copy(
    primary = palette.base,
    onPrimary = readableOn(palette.base),
    onPrimaryContainer = palette.base,
    secondary = palette.deep,
    onSecondary = readableOn(palette.deep),
    onSecondaryContainer = palette.base,
    tertiary = palette.base,
    onTertiary = readableOn(palette.base),
    surfaceTint = palette.base,
    outlineVariant = palette.deep,
)
