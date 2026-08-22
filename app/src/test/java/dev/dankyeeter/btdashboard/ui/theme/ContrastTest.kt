package dev.dankyeeter.btdashboard.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every colour pair the Edgy theme actually reads text on.
 *
 * This exists because the failure already happened once, silently: `outline`
 * carries secondary *text* in Material 3, not only borders, and dark gold on
 * black measured about 2:1. On a dark theme an unreadable label looks like a
 * deliberately dim one, so nothing flagged it until someone tried to read it.
 *
 * A test is the only thing that catches that class of bug, because the bug is
 * invisible to the person who chose the colour and knows what it says.
 */
class ContrastTest {

    private val scheme = EdgyColorScheme

    private fun assertBody(name: String, foreground: Color, background: Color) {
        val ratio = Contrast.ratio(foreground, background)
        assertTrue(
            "$name is %.2f:1, needs %.1f:1 for body text".format(ratio, Contrast.BODY_MIN),
            ratio >= Contrast.BODY_MIN,
        )
    }

    private fun assertNonText(name: String, foreground: Color, background: Color) {
        val ratio = Contrast.ratio(foreground, background)
        assertTrue(
            "$name is %.2f:1, needs %.1f:1 to be visible as a control".format(
                ratio,
                Contrast.LARGE_MIN,
            ),
            ratio >= Contrast.LARGE_MIN,
        )
    }

    @Test
    fun `body text clears 4 point 5 to 1 on every surface it is painted on`() {
        assertBody("onSurface on surface", scheme.onSurface, scheme.surface)
        assertBody("onBackground on background", scheme.onBackground, scheme.background)
        assertBody("onSurfaceVariant on surfaceVariant", scheme.onSurfaceVariant, scheme.surfaceVariant)
        // Panels and cards paint from the container roles, not from `surface`.
        assertBody("onSurface on surfaceContainer", scheme.onSurface, scheme.surfaceContainer)
        assertBody(
            "onSurface on surfaceContainerHighest",
            scheme.onSurface,
            scheme.surfaceContainerHighest,
        )
    }

    @Test
    fun `outline is readable, because Material 3 puts secondary text in it`() {
        // The regression this whole file guards. Gold here measured ~2:1.
        assertBody("outline on surface", scheme.outline, scheme.surface)
        assertBody("outline on surfaceContainer", scheme.outline, scheme.surfaceContainer)
        assertBody(
            "outline on surfaceContainerHighest",
            scheme.outline,
            scheme.surfaceContainerHighest,
        )
    }

    @Test
    fun `text on filled controls is readable`() {
        assertBody("onPrimary on primary", scheme.onPrimary, scheme.primary)
        assertBody("onSecondary on secondary", scheme.onSecondary, scheme.secondary)
        assertBody("onTertiary on tertiary", scheme.onTertiary, scheme.tertiary)
        assertBody(
            "onPrimaryContainer on primaryContainer",
            scheme.onPrimaryContainer,
            scheme.primaryContainer,
        )
        assertBody(
            "onSecondaryContainer on secondaryContainer",
            scheme.onSecondaryContainer,
            scheme.secondaryContainer,
        )
    }

    @Test
    fun `warnings and accents stay visible as controls`() {
        assertNonText("error on surface", scheme.error, scheme.surface)
        assertNonText("primary on surface", scheme.primary, scheme.surface)
        assertNonText("outlineVariant on surface", scheme.outlineVariant, scheme.surface)
    }

    @Test
    fun `the timeline's codec lane carries its meaning in the divider and the label`() {
        // Measured after compositing: a translucent tint's real contrast
        // depends on what is behind it, and the raw colour is never painted.
        //
        // The fill itself is *not* asked to clear 3:1, and that is a decision
        // rather than an oversight: a tint dark enough for text to sit on top
        // of it cannot also stand out against a black surface. So the lane's
        // boundaries and its label carry the information, and those are what is
        // pinned here.
        val fill = Contrast.composite(scheme.primary.copy(alpha = 0.18f), scheme.surface)
        assertNonText("codec change divider on surface", scheme.primary, scheme.surface)
        assertBody("codec name on the lane fill", scheme.onSurface, fill)
    }

    // ---- the surface system -------------------------------------------------
    //
    // Added when the onboarding, wizard and settings screens were moved onto
    // Panel/Pill. Everything below is a pairing those screens actually paint.

    /**
     * The three stops of [Panel]'s body gradient. A panel is not one colour, so
     * a role that clears the bar on the middle stop can still fail on the
     * lightest one — and the lightest is the top of the panel, where the header
     * and the first line of text sit.
     */
    private val panelStops = listOf(
        "surfaceContainerHigh" to scheme.surfaceContainerHigh,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerLow" to scheme.surfaceContainerLow,
    )

    @Test
    fun `panel body text is readable on every stop of the panel gradient`() {
        panelStops.forEach { (name, background) ->
            assertBody("onSurface on $name", scheme.onSurface, background)
            assertBody("onSurfaceVariant on $name", scheme.onSurfaceVariant, background)
            assertBody("outline on $name", scheme.outline, background)
        }
    }

    /**
     * [Pill] is the app's state marker, so its label is load-bearing text: the
     * difference between "Running" and "Not running" is the whole point of it.
     *
     * The label sits on the pill's own 14% tint of the same colour, over a
     * panel — so the ratio has to be measured after compositing, not against
     * the panel. Tightest of the three is ACCENT at about 5.1:1.
     */
    @Test
    fun `every pill tone keeps its label readable on its own tint`() {
        val tones = listOf(
            // ACCENT resolves to Gold.Base under Edgy, not to scheme.primary.
            "ACCENT" to Gold.Base,
            "WARN" to scheme.error,
            "NEUTRAL" to scheme.onSurfaceVariant,
        )
        tones.forEach { (tone, accent) ->
            panelStops.forEach { (name, background) ->
                val fill = Contrast.composite(accent.copy(alpha = 0.14f), background)
                assertBody("$tone pill label over $name", accent, fill)
            }
        }

        // The fill itself and the 30% border are *not* asked to clear 3:1, and
        // that is a decision rather than an oversight — the same one already
        // made for the codec lane above. A tint dark enough for coloured text
        // to stay readable on it cannot also stand out against a near-black
        // panel; measured, the fill sits at about 1.2-1.35:1 and the border at
        // 1.6-2.1:1. So the pill's shape carries no information and the label
        // carries all of it. Nothing may be encoded in a pill's outline alone.
    }

    /**
     * The small caps label above every panel. [PanelHeader] paints it at 85%
     * under Edgy, which is close enough to the line to be worth pinning: it
     * measures 4.81:1 on the lightest stop, against a 4.5:1 minimum.
     *
     * It is 11 sp, so body rules apply to it however small and decorative it
     * looks — it is the only thing naming what the panel is about.
     */
    @Test
    fun `panel header eyebrow is readable, not just decorative`() {
        panelStops.forEach { (name, background) ->
            val eyebrow = Contrast.composite(Gold.Base.copy(alpha = 0.85f), background)
            assertBody("panel header eyebrow on $name", eyebrow, background)
        }
    }

    /**
     * [Readout] fills its glyphs with the metal ramp, which is a gradient, so
     * "the" contrast of a readout does not exist — each stop has its own.
     *
     * The bright half (Pale through Base, the top ~58% of every letterform)
     * clears the body minimum and is what makes the value legible. The tail
     * does not: Gold.Deep measures about 2.7:1 against a panel and Gold.Shadow
     * about 1.3:1. That tail is the shading that makes the ramp read as metal
     * rather than as yellow paint, and removing it would cost the whole effect.
     *
     * The consequence is a rule for the screens, not a change to the ramp: a
     * state shown in a readout must also be stated somewhere flat — which is
     * why the System access screen puts the helper's state in a pill as well.
     */
    @Test
    fun `the bright half of the metal ramp carries the glyphs`() {
        val bright = listOf(
            "Gold.Pale" to Gold.Pale,
            "Gold.Warm" to Gold.Warm,
            "Gold.Highlight" to Gold.Highlight,
            "Gold.Base" to Gold.Base,
        )
        bright.forEach { (stop, color) ->
            panelStops.forEach { (name, background) ->
                assertBody("$stop on $name", color, background)
            }
        }
    }

    // ---- the maths itself ---------------------------------------------------

    @Test
    fun `the extremes are the WCAG reference values`() {
        assertEquals(21.0, Contrast.ratio(Color.White, Color.Black), 0.01)
        assertEquals(1.0, Contrast.ratio(Color.White, Color.White), 0.001)
        // Order must not matter.
        assertEquals(
            Contrast.ratio(Color.White, Color.Black),
            Contrast.ratio(Color.Black, Color.White),
            1e-9,
        )
    }

    @Test
    fun `compositing an opaque colour changes nothing`() {
        assertEquals(Color.Red, Contrast.composite(Color.Red, Color.Blue))
    }

    @Test
    fun `a fully transparent foreground becomes the background`() {
        val result = Contrast.composite(Color.Red.copy(alpha = 0f), Color.Blue)
        assertEquals(Color.Blue.red, result.red, 1e-6f)
        assertEquals(Color.Blue.blue, result.blue, 1e-6f)
    }
}
