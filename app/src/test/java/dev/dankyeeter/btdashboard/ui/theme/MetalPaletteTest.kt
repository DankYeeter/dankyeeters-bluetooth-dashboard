package dev.dankyeeter.btdashboard.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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

    private fun lightness(color: Color) = color.toHsl()[2]

    private fun hue(color: Color) = color.toHsl()[0]

    private fun saturation(color: Color) = color.toHsl()[1]

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
            val restored = Color.hsl(h, s, l)
            assertEquals("red of $original", original.red, restored.red, 1e-3f)
            assertEquals("green of $original", original.green, restored.green, 1e-3f)
            assertEquals("blue of $original", original.blue, restored.blue, 1e-3f)
        }
    }

    @Test
    fun `derived ramps keep the values of the hand-written HSL conversion`() {
        // Pinned from the hand-written toHsl()/hsl() pair that ColorUtils and
        // Color.hsl replaced (T-056): swapping the conversion must not shift a
        // single stop by one step.
        val pinned = mapOf(
            0xFFC08F28 to listOf(0xFF3A2A08, 0xFF7A5912, 0xFFC08F28, 0xFFD9A73F, 0xFFE3BE70, 0xFFF7E0B0),
            0xFF3A7BD5 to listOf(0xFF081D3A, 0xFF133E7A, 0xFF2968BF, 0xFF4080D8, 0xFF71A0E3, 0xFFB0CEF7),
            0xFFB03060 to listOf(0xFF370B1B, 0xFF73193B, 0xFFB63263, 0xFFCF497B, 0xFFDC779D, 0xFFF3B4CC),
            0xFF8A8A88 to listOf(0xFF28281A, 0xFF545438, 0xFF89895F, 0xFFA1A177, 0xFFB9B99A, 0xFFDDDDCA),
            0xFFE53935 to listOf(0xFF3E0503, 0xFF830C09, 0xFFCE1F1A, 0xFFE73631, 0xFFED6966, 0xFFFDABA9),
            0xFF2E7D32 to listOf(0xFF0F3311, 0xFF226B25, 0xFF3EAA44, 0xFF56C25B, 0xFF81D285, 0xFFBAEDBD),
            0xFF00ACC1 to listOf(0xFF003B42, 0xFF007D8C, 0xFF00CFE8, 0xFF19E6FF, 0xFF54ECFF, 0xFFA8F6FF),
            0xFF8E24AA to listOf(0xFF2F083A, 0xFF64137A, 0xFFA029BF, 0xFFB840D8, 0xFFCB70E3, 0xFFE8B0F7),
            0xFFFFFFFF to listOf(0xFF281A1A, 0xFF543838, 0xFF895F5F, 0xFFA17777, 0xFFB99A9A, 0xFFDDCACA),
            0xFF000000 to listOf(0xFF281A1A, 0xFF543838, 0xFF895F5F, 0xFFA17777, 0xFFB99A9A, 0xFFDDCACA),
        )
        pinned.forEach { (seed, expected) ->
            val actual = MetalPalette.from(Color(seed)).stops.map { it.toArgb().toLong() and 0xFFFFFFFF }
            assertEquals("ramp from 0x%08X".format(seed), expected, actual)
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
