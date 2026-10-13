package dev.dankyeeter.btdashboard.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.1 contrast, used both as a test and at runtime.
 *
 * This exists because the failure it guards against already happened: `outline`
 * carries secondary *text* in Material 3, not just borders, and dark gold on
 * black measured about 2:1. It looked deliberate — a dim label on a dark
 * theme — and was simply unreadable. Nothing about the code said so, which is
 * why the check lives here rather than in someone's eye.
 *
 * Runtime matters too: once the accent colour is the user's to choose, a
 * picked colour has to be *checked*, not trusted.
 */
object Contrast {

    /**
     * Contrast ratio between two opaque colours, 1.0 (identical) to 21.0
     * (black on white).
     *
     * Alpha is ignored: a translucent colour's real contrast depends on
     * whatever is behind it, so composite it first and pass the result. A ratio
     * computed against a colour the user never sees is worse than no check.
     */
    fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Relative luminance, per the WCAG definition. */
    private fun luminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    /** Undoes the sRGB transfer curve for one channel. */
    private fun linear(channel: Float): Double {
        val v = channel.toDouble()
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
}
