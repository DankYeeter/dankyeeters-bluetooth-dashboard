package dev.dankyeeter.btdashboard.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A metallic ramp derived from one colour.
 *
 * [Gold] is hand-tuned and stays the reference. This is what makes the same
 * treatment available for *any* accent the user picks: the stops are not
 * arbitrary tints of the input, they reproduce the structure that makes gold
 * read as metal rather than as paint — a long shadow, a compressed body around
 * the chosen colour, and a sudden pale specular band.
 *
 * The two things that carry that structure:
 *
 *  - **Lightness spacing is uneven.** Evenly spaced stops look like a printed
 *    ramp. Real metal has a long shadow, a compressed body and a sudden
 *    highlight.
 *  - **Saturation stays high, including at the highlight.** This one is worth
 *    stating because the obvious guess is wrong: a specular band looks like
 *    scattered light, so one expects it to drift toward white. Measured in
 *    HSL, the hand-tuned gold does the opposite — saturation runs 0.76 in the
 *    shadow, 0.66 at the body and 0.82 at the highlight. The highlight is a
 *    pale gold, not a white. Desaturating it is what would make the ramp look
 *    like plastic.
 *
 * The input colour becomes the [base] stop; everything else is derived around
 * it, so the user's choice is the colour they actually see on a filled control.
 */
data class MetalPalette(
    val shadow: Color,
    val deep: Color,
    val base: Color,
    val warm: Color,
    val pale: Color,
    val highlight: Color,
) {

    /** Left-to-right sheen, matching [Gold.horizontal]'s stop placement. */
    val horizontal: Brush = Brush.linearGradient(
            0.00f to shadow,
            0.12f to deep,
            0.30f to base,
            0.38f to highlight,
            0.48f to warm,
            0.62f to base,
            0.78f to deep,
            0.86f to pale,
            1.00f to shadow,
        )

    /** Top-to-bottom sheen, matching [Gold.vertical]. */
    val vertical: Brush = Brush.verticalGradient(
            0.00f to pale,
            0.22f to warm,
            0.44f to highlight,
            0.58f to base,
            0.82f to deep,
            1.00f to shadow,
        )

    /** Filled controls: bright at the top, falling into shadow like a cast ingot. */
    val fill: Brush = Brush.verticalGradient(
            0.00f to warm,
            0.18f to highlight,
            0.42f to base,
            0.78f to deep,
            1.00f to shadow,
        )

    /** Faint metal edge for card outlines. */
    val border: BorderStroke = BorderStroke(1.dp, horizontal)

    /** Every stop, dark to light — useful for previews and for contrast checks. */
    val stops: List<Color> = listOf(shadow, deep, base, warm, pale, highlight)

    companion object {

        /**
         * Target lightness and saturation scaling per stop, read off the
         * hand-tuned [Gold]. `from(Gold.Base)` therefore lands close to the
         * original, which is what the tests pin.
         */
        private val SHAPE = listOf(
            Step(lightness = 0.129f, saturation = 1.16f),
            Step(lightness = 0.275f, saturation = 1.13f),
            Step(lightness = 0.455f, saturation = 1.00f),
            Step(lightness = 0.549f, saturation = 1.02f),
            Step(lightness = 0.665f, saturation = 1.03f),
            Step(lightness = 0.829f, saturation = 1.25f),
        )

        /**
         * Builds a ramp whose body stop is [base].
         *
         * A near-grey input has almost no saturation to spread across the ramp,
         * so saturation is floored before the per-stop scaling. The result is a
         * duller metal rather than six shades of the same nothing, which is the
         * honest outcome for that input.
         */
        fun from(base: Color): MetalPalette {
            val (hue, saturation, _) = base.toHsl()
            val bodySaturation = saturation.coerceAtLeast(MIN_SATURATION)
            val colors = SHAPE.map { step ->
                hsl(
                    hue = hue,
                    saturation = (bodySaturation * step.saturation).coerceIn(0f, 1f),
                    lightness = step.lightness,
                )
            }
            return MetalPalette(
                shadow = colors[0],
                deep = colors[1],
                base = colors[2],
                warm = colors[3],
                pale = colors[4],
                highlight = colors[5],
            )
        }

        /** Below this a ramp reads as dirty grey, not as metal. */
        private const val MIN_SATURATION = 0.18f

        private data class Step(val lightness: Float, val saturation: Float)
    }
}

/** HSL as (hue in degrees, saturation 0..1, lightness 0..1). */
internal fun Color.toHsl(): Triple<Float, Float, Float> {
    val r = red
    val g = green
    val b = blue
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC
    val lightness = (maxC + minC) / 2f

    if (delta < 1e-6f) return Triple(0f, 0f, lightness)

    val saturation = delta / (1f - abs(2f * lightness - 1f))
    val hue = when (maxC) {
        r -> 60f * (((g - b) / delta) % 6f)
        g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    return Triple((hue + 360f) % 360f, saturation.coerceIn(0f, 1f), lightness)
}

/** The inverse of [toHsl]. */
internal fun hsl(hue: Float, saturation: Float, lightness: Float): Color {
    val c = (1f - abs(2f * lightness - 1f)) * saturation
    val h = ((hue % 360f) + 360f) % 360f / 60f
    val x = c * (1f - abs((h % 2f) - 1f))
    val m = lightness - c / 2f

    val (r, g, b) = when {
        h < 1f -> Triple(c, x, 0f)
        h < 2f -> Triple(x, c, 0f)
        h < 3f -> Triple(0f, c, x)
        h < 4f -> Triple(0f, x, c)
        h < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        red = (r + m).coerceIn(0f, 1f),
        green = (g + m).coerceIn(0f, 1f),
        blue = (b + m).coerceIn(0f, 1f),
    )
}
