package dev.dankyeeter.btdashboard.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The metallic gold used by the Edgy theme.
 *
 * A single flat colour cannot read as metal: what the eye recognises is the
 * *gradient* — a dark base, a narrow bright specular band where the light
 * catches, and a fall-off back to shadow. So gold here is a [Brush], not a
 * [Color], and anything that should look like polished metal (rules, borders,
 * headings) paints with it rather than with `colorScheme.primary`.
 *
 * Stops are deliberately uneven: an evenly spaced gradient looks like a
 * printed ramp, while a tight highlight around the 40% mark looks like a
 * curved metal surface catching one light source.
 */
object Gold {

    // Gold is not yellow. Yellow has red and green at roughly equal strength;
    // metallic gold keeps red well ahead of green and drops blue almost to
    // nothing, which is what makes it read as warm metal rather than paint.
    // Every stop below holds R-G at ~55-70 instead of the ~39 that made the
    // first attempt look like a highlighter.
    val Shadow = Color(0xFF3A2708)
    val Deep = Color(0xFF7A5512)
    val Base = Color(0xFFC08F28)
    val Warm = Color(0xFFD9A93F)
    val Pale = Color(0xFFE3C070)

    /** The narrow specular band. Warm white, never pure white. */
    val Highlight = Color(0xFFF7E6B0)

    /**
     * Left-to-right sheen for rules, borders and wide elements.
     *
     * Two highlights, not one: a real bevel catches the light on both of its
     * rounded edges, and the asymmetry between them (a bright one at 38% and a
     * weaker one at 82%) is what stops the gradient reading as a printed ramp.
     */
    val horizontal: Brush
        get() = Brush.linearGradient(
            0.00f to Shadow,
            0.12f to Deep,
            0.30f to Base,
            0.38f to Highlight,
            0.48f to Warm,
            0.62f to Base,
            0.78f to Deep,
            0.86f to Pale,
            1.00f to Shadow,
        )

    /** Top-to-bottom sheen, for text and anything read as an engraved surface. */
    val vertical: Brush
        get() = Brush.verticalGradient(
            0.00f to Pale,
            0.22f to Warm,
            0.44f to Highlight,
            0.58f to Base,
            0.82f to Deep,
            1.00f to Shadow,
        )

    /** Filled controls: bright at the top, falling into shadow like a cast ingot. */
    val fill: Brush
        get() = Brush.verticalGradient(
            0.00f to Warm,
            0.18f to Highlight,
            0.42f to Base,
            0.78f to Deep,
            1.00f to Shadow,
        )

    /** Faint metal edge for card outlines. */
    val border: BorderStroke get() = BorderStroke(1.dp, horizontal)
}

/**
 * Whether the metallic treatment applies. Only the Edgy theme gets it — under
 * Material You the accent belongs to the wallpaper, and painting gold over it
 * would fight the system palette rather than follow it.
 */
val LocalGoldAccents: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

@Composable
fun ProvideGoldAccents(enabled: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGoldAccents provides enabled, content = content)
}
