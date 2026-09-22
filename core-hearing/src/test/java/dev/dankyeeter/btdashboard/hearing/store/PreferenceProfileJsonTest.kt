package dev.dankyeeter.btdashboard.hearing.store

import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import dev.dankyeeter.btdashboard.hearing.preference.FinalCheck
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceAxis
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceCandidate
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceChoice
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceLabelSource
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceProfile
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceRun
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceTrial
import dev.dankyeeter.btdashboard.hearing.preference.TrialPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The round trip this codec exists for. A preference pool is a dozen listening
 * sessions somebody sat through; a silent encoding bug would cost all of them,
 * and an `org.json` codec could not be tested here at all.
 */
class PreferenceProfileJsonTest {

    private val layout = EqBandLayout.HALF_OCTAVE_20

    private val trial = PreferenceTrial(
        index = 3,
        phase = TrialPhase.REFINE,
        axis = PreferenceAxis.TILT,
        a = PreferenceCandidate(4.5f, -1.5f),
        b = PreferenceCandidate(1.5f, 1.5f),
        choice = PreferenceChoice.B,
        repeat = false,
    )

    private val profile = PreferenceProfile(
        deviceKey = "abc123",
        deviceName = "Focal \"Bathys\"\n2",
        runs = listOf(
            PreferenceRun(
                id = "run-1",
                label = "Blue Monday — New Order",
                labelSource = PreferenceLabelSource.TRACK,
                createdAtMillis = 1_700_000_000_000L,
                candidate = PreferenceCandidate(4.5f, -2f),
                consistency = 0.75,
                trials = listOf(trial, trial.copy(index = 8, phase = TrialPhase.VALIDATE, repeat = true)),
            ),
            PreferenceRun(
                id = "run-2",
                label = "",
                labelSource = PreferenceLabelSource.NONE,
                createdAtMillis = 1_700_000_100_000L,
                candidate = PreferenceCandidate(-1f, 3f),
                consistency = 1.0,
            ),
        ),
        layout = layout,
        baseLeftDb = List(layout.bandCount) { it * 0.25f },
        baseRightDb = List(layout.bandCount) { -it * 0.5f },
        manualBassDb = 6f,
        manualTrebleDb = null,
        finalCheck = FinalCheck.YOURS_WON,
        createdAtMillis = 1_700_000_000_000L,
        updatedAtMillis = 1_700_000_200_000L,
    )

    @Test
    fun `a profile survives the round trip exactly`() {
        val back = PreferenceProfileJson.parse(PreferenceProfileJson.encode(listOf(profile)))
        assertEquals(listOf(profile), back)
    }

    @Test
    fun `several profiles survive together`() {
        val other = profile.copy(deviceKey = "def456", deviceName = null, runs = emptyList())
        val back = PreferenceProfileJson.parse(PreferenceProfileJson.encode(listOf(profile, other)))
        assertEquals(listOf(profile, other), back)
    }

    @Test
    fun `an empty list round trips`() {
        assertEquals(emptyList<PreferenceProfile>(), PreferenceProfileJson.parse(PreferenceProfileJson.encode(emptyList())))
    }

    @Test
    fun `nothing stored reads as no profiles`() {
        assertEquals(emptyList<PreferenceProfile>(), PreferenceProfileJson.parse(null))
        assertEquals(emptyList<PreferenceProfile>(), PreferenceProfileJson.parse(""))
        assertEquals(emptyList<PreferenceProfile>(), PreferenceProfileJson.parse("   "))
    }

    @Test
    fun `garbage reads as no profiles rather than throwing`() {
        listOf("{", "not json", "[1,2,3", "\"a string\"", "{\"deviceKey\":\"x\"}")
            .forEach { assertTrue(it, PreferenceProfileJson.parse(it).isEmpty()) }
    }

    @Test
    fun `an entry with no device is dropped, and the rest survive`() {
        val encoded = PreferenceProfileJson.encode(listOf(profile))
            .let { "[{\"deviceKey\":\"\"}," + it.substring(1) }
        val back = PreferenceProfileJson.parse(encoded)
        assertEquals(1, back.size)
        assertEquals("abc123", back.single().deviceKey)
    }

    @Test
    fun `a hand adjustment of null stays null and is not read as zero`() {
        val back = PreferenceProfileJson.parse(PreferenceProfileJson.encode(listOf(profile))).single()
        assertEquals(6f, back.manualBassDb)
        assertEquals(null, back.manualTrebleDb)
        assertTrue(back.handAdjusted)
    }

    @Test
    fun `a base curve at another resolution is resampled rather than dropped`() {
        val tenBand = profile.copy(
            layout = EqBandLayout.OCTAVE_10,
            baseLeftDb = List(10) { 3f },
            baseRightDb = List(10) { 3f },
        )
        // Rewrite the layout id under a curve that belongs to the old one, the
        // way a stored record from a build with a different default would look.
        val tampered = PreferenceProfileJson.encode(listOf(tenBand))
            .replace("\"layout\":\"octave_10\"", "\"layout\":\"half_octave_20\"")
        val back = PreferenceProfileJson.parse(tampered).single()
        assertEquals(EqBandLayout.HALF_OCTAVE_20, back.layout)
        assertEquals(20, back.baseLeftDb.size)
        assertTrue(back.baseLeftDb.all { kotlin.math.abs(it - 3f) < 1e-5f })
    }

    @Test
    fun `a base curve of no known length degrades to flat`() {
        val tampered = PreferenceProfileJson.encode(listOf(profile))
            .replace(Regex("\"baseLeft\":\\[[^]]*]"), "\"baseLeft\":[1.0,2.0,3.0]")
        val back = PreferenceProfileJson.parse(tampered).single()
        assertEquals(layout.bandCount, back.baseLeftDb.size)
        assertTrue(back.baseLeftDb.all { it == 0f })
    }

    @Test
    fun `an unknown enum name falls back rather than losing the record`() {
        val tampered = PreferenceProfileJson.encode(listOf(profile))
            .replace("\"finalCheck\":\"YOURS_WON\"", "\"finalCheck\":\"SOMETHING_NEW\"")
            .replace("\"labelSource\":\"TRACK\"", "\"labelSource\":\"LYRICS\"")
        val back = PreferenceProfileJson.parse(tampered).single()
        assertEquals(FinalCheck.NOT_RUN, back.finalCheck)
        assertEquals(PreferenceLabelSource.NONE, back.runs.first().labelSource)
        assertEquals(2, back.runs.size)
    }

    @Test
    fun `a label with quotes and newlines comes back intact`() {
        val awkward = profile.copy(
            runs = listOf(
                profile.runs.first().copy(label = "He said \"hi\"\n\tand left \\ then"),
            ),
        )
        val back = PreferenceProfileJson.parse(PreferenceProfileJson.encode(listOf(awkward))).single()
        assertEquals(awkward.runs.first().label, back.runs.first().label)
    }

    @Test
    fun `a consistency outside zero to one is pulled back into it`() {
        val tampered = PreferenceProfileJson.encode(listOf(profile))
            .replace("\"consistency\":0.75", "\"consistency\":7.5")
        assertEquals(1.0, PreferenceProfileJson.parse(tampered).single().runs.first().consistency, 1e-9)
    }

    /**
     * What phones already hold. The literal was written by the MiniJson encoder
     * (AD-026) from [legacyFixture] and is never regenerated: whatever the codec
     * is built on, this string has to read back as exactly that fixture.
     */
    @Test
    fun `a string written by the MiniJson encoder reads back exactly`() {
        assertEquals(legacyFixture, PreferenceProfileJson.parse(LEGACY_PREFERENCE_PROFILES_WRITTEN_BY_MINIJSON))
    }

    private companion object {
        val legacyFixture = listOf(
            PreferenceProfile(
                deviceKey = "legacy-awkward",
                deviceName = null,
                runs = listOf(
                    PreferenceRun(
                        id = "run-1",
                        label = "He said \"hi\"\n\tand left \\ — then",
                        labelSource = PreferenceLabelSource.TRACK,
                        createdAtMillis = 1_756_000_000_123L,
                        candidate = PreferenceCandidate(1.0E-4f, -2.5f),
                        consistency = 1.0E-4,
                        trials = listOf(
                            PreferenceTrial(
                                index = 12,
                                phase = TrialPhase.VALIDATE,
                                axis = PreferenceAxis.TILT,
                                a = PreferenceCandidate(0.1f, -6f),
                                b = PreferenceCandidate(9f, 1.0E-4f),
                                choice = PreferenceChoice.NO_DIFFERENCE,
                                repeat = true,
                            ),
                        ),
                    ),
                ),
                layout = EqBandLayout.OCTAVE_10,
                baseLeftDb = listOf(0f, 0.1f, -0.25f, 1.0E-4f, 3f, -6f, 12.5f, 0f, -1.0E-4f, 2f),
                baseRightDb = List(EqBandLayout.OCTAVE_10.bandCount) { 0f },
                manualBassDb = 1.0E-4f,
                manualTrebleDb = null,
                finalCheck = FinalCheck.YOURS_WON,
                createdAtMillis = 1_699_999_999_999L,
                updatedAtMillis = 1_756_000_000_123L,
            ),
            PreferenceProfile(
                deviceKey = "legacy-empty",
                deviceName = "Focal \"Bathys\"\n2",
                runs = listOf(
                    PreferenceRun(
                        id = "run-2",
                        label = "",
                        labelSource = PreferenceLabelSource.NONE,
                        createdAtMillis = 1_700_000_100_000L,
                        candidate = PreferenceCandidate(0f, 0f),
                        consistency = 0.0,
                        trials = emptyList(),
                    ),
                ),
                layout = EqBandLayout.OCTAVE_10,
            ),
        )

        const val LEGACY_PREFERENCE_PROFILES_WRITTEN_BY_MINIJSON =
            """[{"deviceKey":"legacy-awkward","deviceName":null,"layout":"octave_10",""" +
                """"baseLeft":[0.0,0.10000000149011612,-0.25,9.999999747378752E-5,3.0,-6.0,12.5,0.0,-9.999999747378752E-5,2.0],""" +
                """"baseRight":[0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0],""" +
                """"manualBassDb":9.999999747378752E-5,"manualTrebleDb":null,"finalCheck":"YOURS_WON",""" +
                """"createdAtMillis":1699999999999,"updatedAtMillis":1756000000123,""" +
                """"runs":[{"id":"run-1","label":"He said \"hi\"\n\tand left \\ — then","labelSource":"TRACK",""" +
                """"createdAtMillis":1756000000123,"bassDb":9.999999747378752E-5,"trebleDb":-2.5,"consistency":1.0E-4,""" +
                """"trials":[{"i":12,"p":"VALIDATE","x":"TILT","ab":0.10000000149011612,"at":-6.0,""" +
                """"bb":9.0,"bt":9.999999747378752E-5,"c":"NO_DIFFERENCE","r":true}]}]},""" +
                """{"deviceKey":"legacy-empty","deviceName":"Focal \"Bathys\"\n2","layout":"octave_10",""" +
                """"baseLeft":[0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0],""" +
                """"baseRight":[0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0],""" +
                """"manualBassDb":null,"manualTrebleDb":null,"finalCheck":"NOT_RUN",""" +
                """"createdAtMillis":0,"updatedAtMillis":0,""" +
                """"runs":[{"id":"run-2","label":"","labelSource":"NONE","createdAtMillis":1700000100000,""" +
                """"bassDb":0.0,"trebleDb":0.0,"consistency":0.0,"trials":[]}]}]"""
    }
}
