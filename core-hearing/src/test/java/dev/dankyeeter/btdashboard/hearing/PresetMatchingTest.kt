package dev.dankyeeter.btdashboard.hearing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bundled presets are never listed in the UI, so this matcher is the only
 * path from a connected headphone to its correction curve. A wrong match is
 * worse than no match: it applies another device's curve and silently
 * invalidates every threshold measured afterwards.
 */
class PresetMatchingTest {

    @Test
    fun `matches the name Bluetooth actually reports`() {
        assertEquals("focal_bathys", PresetMatching.presetIdFor("Focal Bathys"))
    }

    @Test
    fun `matching ignores case and padding`() {
        assertEquals("focal_bathys", PresetMatching.presetIdFor("  FOCAL BATHYS  "))
    }

    @Test
    fun `a truncated name still matches`() {
        // Real dumps clip long names: "Noble FoKus Prestige Encor".
        assertEquals("noble_encore", PresetMatching.presetIdFor("Noble FoKus Prestige Encore"))
    }

    @Test
    fun `model numbers are part of the key`() {
        assertEquals("sennheiser_momentum4", PresetMatching.presetIdFor("Sennheiser Momentum 4"))
        // Momentum 3 is a different curve and must not borrow the Momentum 4 one.
        assertNull(PresetMatching.presetIdFor("Sennheiser Momentum 3"))
    }

    @Test
    fun `the more specific AirPods model wins`() {
        assertEquals("airpods_pro_2", PresetMatching.presetIdFor("Daniel's AirPods Pro 2"))
        assertEquals("airpods_4_anc", PresetMatching.presetIdFor("AirPods 4 ANC"))
        assertEquals("airpods_4", PresetMatching.presetIdFor("AirPods 4"))
    }

    @Test
    fun `an unknown device gets no preset rather than a wrong one`() {
        assertNull(PresetMatching.presetIdFor("Some Random Speaker"))
        assertNull(PresetMatching.presetIdFor(""))
        assertNull(PresetMatching.presetIdFor(null))
    }

    @Test
    fun `every key resolves to a preset that actually ships`() {
        val repository = BundledCalibrationPresets
        listOf(
            "Focal Bathys",
            "Noble FoKus Prestige Encore",
            "Sennheiser Momentum 4",
            "AirPods Pro 3",
            "AirPods Pro 2",
            "AirPods 4 ANC",
            "AirPods 4",
            "AirPods 3",
            "AirPods 2",
        ).forEach { name ->
            val preset = PresetMatching.presetFor(name, repository)
            assertEquals("no preset for $name", true, preset != null)
        }
    }
}
