package dev.dankyeeter.btdashboard.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that make a derived ramp read as metal rather than as paint.
 *
 * [Gold] stays hand-tuned and is the reference: if the derivation cannot
 * approximately reproduce it from its own base colour, the derivation is not
 * capturing what makes it look like metal, and every user-picked accent would
 * inherit that failure.
 */
class MetalPaletteTest {

    private fun lightness(color: Color) = color.toHsl().third

    private fun hue(color: Color) = color.toHsl().first

    private fun saturation(color: Color) = color.toHsl().second

    @Test
    fun `deriving from gold's own base approximates the hand-tuned gold`() {
        val derived = MetalPalette.from(Gold.Base)
        val expected = listOf(Gold.Shadow, Gold.Deep, Gold.Base, Gold.Warm, Gold.Pale, Gold.Highlight)
        val names = listOf("shadow", "deep", "base", "warm", "pale", "highlight")

        derived.stops.forEachIndexed { i, actual ->
            val want = expected[i]
            listOf(
                Triple("red", actual.red, want.red),
                Triple("green", actual.green, want.green),
                Triple("blue", actual.blue, want.blue),
            ).forEach { (channel, got, target) ->
                assertTrue(
                    "${names[i]}.$channel: derived %.3f vs hand-tuned %.3f".format(got, target),
                    abs(got - target) <= TOLERANCE,
                )
            }
        }
    }

    @Test
    fun `the base stop is the colour the user chose`() {
        // What makes the picker honest: the colour on a filled control is the
        // colour in the swatch, not a tint of it.
        val picked = Color(0xFF3A7BD5)
        val ramp = MetalPalette.from(picked)
        assertEquals(hue(picked).toDouble(), hue(ramp.base).toDouble(), 1.0)
    }

    @Test
    fun `stops run dark to light without doubling back`() {
        listOf(Gold.Base, Color(0xFF3A7BD5), Color(0xFFB03060)).forEach { seed ->
            val stops = MetalPalette.from(seed).stops
            stops.zipWithNext().forEach { (darker, lighter) ->
                assertTrue(
                    "a ramp from $seed doubles back at $darker -> $lighter",
                    lightness(lighter) > lightness(darker),
                )
            }
        }
    }

    @Test
    fun `the highlight stays saturated, contrary to the obvious guess`() {
        // Worth a test precisely because the intuition is wrong. A specular
        // band looks like scattered light, so one expects it to drift toward
        // white — but the hand-tuned gold measures 0.82 saturation at the
        // highlight against 0.66 at the body. It is a pale gold, not a white,
        // and desaturating it is what would make the ramp read as plastic.
        val ramp = MetalPalette.from(Gold.Base)
        assertTrue(
            "highlight %.2f should be at least as saturated as base %.2f".format(
                saturation(ramp.highlight),
                saturation(ramp.base),
            ),
            saturation(ramp.highlight) >= saturation(ramp.base),
        )
    }

    @Test
    fun `one hue runs through the whole ramp`() {
        val seed = Color(0xFF3A7BD5)
        val seedHue = hue(seed)
        MetalPalette.from(seed).stops.forEach { stop ->
            assertEquals("stop $stop drifted off the hue", seedHue.toDouble(), hue(stop).toDouble(), 2.0)
        }
    }

    @Test
    fun `a near-grey accent still yields a ramp instead of a smear`() {
        // Honest degradation: grey has no saturation to fall off, so the result
        // is a duller metal rather than six shades of the same nothing.
        val ramp = MetalPalette.from(Color(0xFF8A8A88))
        assertTrue(saturation(ramp.base) > 0.15f)
        ramp.stops.zipWithNext().forEach { (darker, lighter) ->
            assertTrue(lightness(lighter) > lightness(darker))
        }
    }

    @Test
    fun `HSL survives a round trip`() {
        listOf(
            Gold.Base,
            Color(0xFF3A7BD5),
            Color.White,
            Color.Black,
            Color(0xFF808080),
        ).forEach { original ->
            val (h, s, l) = original.toHsl()
            val restored = hsl(h, s, l)
            assertEquals("red of $original", original.red, restored.red, 1e-3f)
            assertEquals("green of $original", original.green, restored.green, 1e-3f)
            assertEquals("blue of $original", original.blue, restored.blue, 1e-3f)
        }
    }

    private companion object {
        /**
         * Per channel, against the hand-tuned gold. Loose on purpose: the
         * derivation reproduces the *structure* of the ramp, and the original
         * stops were nudged by eye. A tight bound here would only pin the
         * nudges.
         */
        const val TOLERANCE = 0.10f
    }
}
