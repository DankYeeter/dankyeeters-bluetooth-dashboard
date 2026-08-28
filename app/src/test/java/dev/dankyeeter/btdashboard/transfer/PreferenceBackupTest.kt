package dev.dankyeeter.btdashboard.transfer

import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.hearing.preference.FinalCheck
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceCandidate
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceLabelSource
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceProfile
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preference pool through the backup file and back.
 *
 * Two things are being pinned here. The obvious one is that a curve somebody
 * spent an hour listening for survives a phone change. The less obvious one is
 * the wire-format contract: the field was **added**, defaulted, and the schema
 * version deliberately not bumped, so every backup written before this feature
 * existed still loads.
 */
class PreferenceBackupTest {

    private val layout = EqBandLayout.HALF_OCTAVE_20

    private val profile = PreferenceProfile(
        deviceKey = "abc123",
        deviceName = "Focal Bathys",
        runs = listOf(
            PreferenceRun(
                id = "run-1",
                label = "Blue Monday",
                labelSource = PreferenceLabelSource.MANUAL,
                createdAtMillis = 1_700_000_000_000L,
                candidate = PreferenceCandidate(4.5f, -2f),
                consistency = 0.75,
            ),
            PreferenceRun(
                id = "run-2",
                label = "Spotify · 14:05",
                labelSource = PreferenceLabelSource.APP,
                createdAtMillis = 1_700_000_100_000L,
                candidate = PreferenceCandidate(3f, -1.5f),
                consistency = 1.0,
            ),
        ),
        layout = layout,
        baseLeftDb = List(layout.bandCount) { it * 0.25f },
        baseRightDb = List(layout.bandCount) { -it * 0.5f },
        manualBassDb = 6f,
        finalCheck = FinalCheck.YOURS_WON,
        createdAtMillis = 1_700_000_000_000L,
        updatedAtMillis = 1_700_000_200_000L,
    )

    private fun documentWith(vararg profiles: PreferenceProfile) = BackupMapper.buildDocument(
        runs = emptyList(),
        audiogram = null,
        profiles = emptyList(),
        eq = EqSettings.FLAT,
        activeProfileId = null,
        appVersion = "test",
        nowMillis = 0L,
        preferenceProfiles = profiles.toList(),
    )

    @Test
    fun `a preference profile survives encode and decode`() {
        val json = BackupCodec.encode(documentWith(profile))
        val decoded = BackupCodec.decode(json)
        assertTrue(decoded is BackupParseResult.Success)
        val back = (decoded as BackupParseResult.Success).document.preferenceProfiles.single()
        assertEquals(profile, BackupMapper.toDomain(back))
    }

    @Test
    fun `the mapping is a straight round trip`() {
        assertEquals(profile, BackupMapper.toDomain(BackupMapper.toBackup(profile)))
    }

    @Test
    fun `several headphones travel together`() {
        val other = profile.copy(deviceKey = "def456", deviceName = null, runs = emptyList())
        val document = documentWith(profile, other)
        val back = document.preferenceProfiles.mapNotNull(BackupMapper::toDomain)
        assertEquals(listOf(profile, other), back)
    }

    /**
     * The wire-format rule this field was added under: a file from before it
     * existed carries no such key, and it has to load exactly as it always did.
     */
    @Test
    fun `a file written before the field existed still loads`() {
        val old = BackupCodec.encode(
            BackupMapper.buildDocument(
                runs = emptyList(),
                audiogram = null,
                profiles = emptyList(),
                eq = EqSettings.FLAT,
                activeProfileId = null,
                appVersion = "old",
                nowMillis = 0L,
            ),
        ).replace(Regex(",\\s*\"preferenceProfiles\"\\s*:\\s*\\[]"), "")
        assertTrue("the field should have been removed", !old.contains("preferenceProfiles"))
        val decoded = BackupCodec.decode(old)
        assertTrue(decoded is BackupParseResult.Success)
        assertTrue((decoded as BackupParseResult.Success).document.preferenceProfiles.isEmpty())
    }

    @Test
    fun `adding the field did not bump the schema version`() {
        assertEquals(1, BackupSchema.CURRENT_VERSION)
        assertEquals(
            BackupSchema.CURRENT_VERSION,
            documentWith(profile).schemaVersion,
        )
    }

    @Test
    fun `a hand adjustment of null is not read back as zero`() {
        val back = BackupMapper.toDomain(BackupMapper.toBackup(profile))
        assertEquals(6f, back?.manualBassDb)
        assertNull(back?.manualTrebleDb)
        assertTrue(back?.handAdjusted == true)
    }

    @Test
    fun `a record with no headphone is dropped`() {
        assertNull(BackupMapper.toDomain(BackupPreferenceProfile(deviceKey = "")))
    }

    @Test
    fun `a run with no id is dropped, the rest of the pool survives`() {
        val stored = BackupMapper.toBackup(profile).let {
            it.copy(runs = it.runs + BackupPreferenceRun(id = ""))
        }
        assertEquals(2, BackupMapper.toDomain(stored)?.runs?.size)
    }

    @Test
    fun `an unknown enum name degrades rather than losing the record`() {
        val stored = BackupMapper.toBackup(profile).let {
            it.copy(
                finalCheck = "SOMETHING_NEW",
                runs = it.runs.map { run -> run.copy(labelSource = "LYRICS") },
            )
        }
        val back = BackupMapper.toDomain(stored)
        assertEquals(FinalCheck.NOT_RUN, back?.finalCheck)
        assertEquals(PreferenceLabelSource.NONE, back?.runs?.first()?.labelSource)
        assertEquals(2, back?.runs?.size)
    }

    /**
     * A base curve saved on a phone whose EQ used another grid is the same
     * curve at another resolution, so it is resampled rather than dropped —
     * the rule the EQ curve already follows.
     */
    @Test
    fun `a base curve at another resolution is resampled onto the layout`() {
        val stored = BackupMapper.toBackup(profile).copy(
            layout = EqBandLayout.OCTAVE_10.id,
            baseLeftDb = List(20) { 3f },
            baseRightDb = List(20) { 3f },
        )
        val back = BackupMapper.toDomain(stored)!!
        assertEquals(EqBandLayout.OCTAVE_10, back.layout)
        assertEquals(10, back.baseLeftDb.size)
        assertTrue(back.baseLeftDb.all { kotlin.math.abs(it - 3f) < 1e-4f })
    }

    @Test
    fun `a base curve of no known length degrades to flat`() {
        val stored = BackupMapper.toBackup(profile).copy(baseLeftDb = listOf(1f, 2f, 3f))
        val back = BackupMapper.toDomain(stored)!!
        assertEquals(layout.bandCount, back.baseLeftDb.size)
        assertTrue(back.baseLeftDb.all { it == 0f })
    }

    @Test
    fun `shelf values outside the parameter space are pulled back into it`() {
        val stored = BackupMapper.toBackup(profile).let {
            it.copy(runs = it.runs.map { run -> run.copy(bassDb = 99f, trebleDb = -99f) })
        }
        val run = BackupMapper.toDomain(stored)!!.runs.first()
        assertEquals(run.candidate, run.candidate.clamped())
    }

    /**
     * The trial log is not in the file, and that is a decision rather than an
     * omission — see [BackupPreferenceRun]. The record still has to survive.
     */
    @Test
    fun `the trial log is not carried, and nothing depends on it`() {
        val withTrials = profile.copy(
            runs = profile.runs.map { it.copy(trials = emptyList()) },
        )
        val back = BackupMapper.toDomain(BackupMapper.toBackup(withTrials))!!
        assertEquals(withTrials.aggregate.candidate, back.aggregate.candidate)
        assertTrue(back.runs.all { it.trials.isEmpty() })
    }

    @Test
    fun `a backup that only holds a preference curve is still nothing importable`() {
        // Deliberate: the codec's "nothing importable" gate looks at runs,
        // profiles and the EQ curve. A preference-only file is a real edge case
        // and it is refused with a readable reason rather than half-imported.
        val decoded = BackupCodec.decode(BackupCodec.encode(documentWith(profile).copy(eq = null)))
        assertTrue(decoded is BackupParseResult.Failure)
    }
}
