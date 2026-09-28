package dev.dankyeeter.btdashboard.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

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
     * whatever is behind it, so composite it first (`compositeOver`) and pass
     * the result. The app tints a lot of things at 25-35% alpha; a ratio
     * computed against a colour the user never sees is worse than no check.
     */
    fun ratio(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}
