package dev.dankyeeter.btdashboard.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
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
    val horizontal: Brush = Brush.linearGradient(
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
    val vertical: Brush = Brush.verticalGradient(
            0.00f to Pale,
            0.22f to Warm,
            0.44f to Highlight,
            0.58f to Base,
            0.82f to Deep,
            1.00f to Shadow,
        )

    /** Filled controls: bright at the top, falling into shadow like a cast ingot. */
    val fill: Brush = Brush.verticalGradient(
            0.00f to Warm,
            0.18f to Highlight,
            0.42f to Base,
            0.78f to Deep,
            1.00f to Shadow,
        )

    /** Faint metal edge for card outlines. */
    val border: BorderStroke = BorderStroke(1.dp, horizontal)

    /**
     * The hand-tuned stops as a [MetalPalette], so the default accent travels
     * through the same path as a chosen one. Deliberately the literal values
     * rather than `MetalPalette.from(Base)`: the derivation approximates these
     * to within a tenth of a channel, and the original should not shift
     * slightly just because it now goes through a formula.
     */
    val asPalette: MetalPalette = MetalPalette(
        shadow = Shadow,
        deep = Deep,
        base = Base,
        warm = Warm,
        pale = Pale,
        highlight = Highlight,
    )
}

/**
 * Whether the metallic treatment applies. Only the Edgy theme gets it — under
 * Material You the accent belongs to the wallpaper, and painting gold over it
 * would fight the system palette rather than follow it.
 */
val LocalGoldAccents: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/**
 * The metal currently being painted.
 *
 * Defaults to the hand-tuned [Gold] so nothing that does not care has to think
 * about it. A chosen accent replaces it wholesale, which is why every component
 * reads its brushes from here rather than from [Gold] directly — a component
 * that keeps reaching for the constant would stay gold while the rest of the
 * app changed colour.
 */
val LocalMetalPalette: ProvidableCompositionLocal<MetalPalette> =
    staticCompositionLocalOf { Gold.asPalette }

@Composable
fun ProvideGoldAccents(
    enabled: Boolean,
    palette: MetalPalette = Gold.asPalette,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalGoldAccents provides enabled,
        LocalMetalPalette provides palette,
        content = content,
    )
}
