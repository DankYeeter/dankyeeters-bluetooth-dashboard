package dev.dankyeeter.btdashboard.hearing.store

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import dev.dankyeeter.btdashboard.privateDataStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

private const val SENTINEL = "HEARING-SENTINEL"

/** Broken on purpose, so org.json quotes it in its message. */
private const val BROKEN = """{"left":"$SENTINEL""""

/** V12 for health data: a store that cannot parse what it holds must not quote it into Logcat. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HearingStoreLogTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `unreadable hearing data keeps its content out of the log`() = runBlocking {
        val hearing = privateDataStore(context, "dev.dankyeeter.btdashboard.hearing.store.AudiogramStoreKt", "hearingDataStore")
        val compensation = privateDataStore(
            context,
            "dev.dankyeeter.btdashboard.hearing.store.CompensationProfileStoreKt",
            "profileDataStore",
        )
        hearing.edit { prefs ->
            listOf("audiogram_runs_json", "clinical_audiogram_json", "age_reference_json", "fit_baseline_json")
                .forEach { prefs[stringPreferencesKey(it)] = BROKEN }
        }
        compensation.edit { it[stringPreferencesKey("compensation_profiles_json")] = BROKEN }
        try {
            ShadowLog.clear()
            val audiograms = AudiogramStore(context)

            audiograms.currentRuns()
            audiograms.currentClinicalAudiogram()
            audiograms.currentAgeReference()
            audiograms.currentFitBaseline()
            CompensationProfileStore(context).current()

            val logged = ShadowLog.getLogs()
                .filter { it.tag == "AudiogramStore" || it.tag == "CompensationProfileStore" }
                .map { "${it.msg} ${it.throwable}" }
            assertEquals(logged.toString(), 5, logged.count { it.contains("JSONException") })
            assertTrue(logged.toString(), logged.none { it.contains(SENTINEL) })
        } finally {
            hearing.edit { it.clear() }
            compensation.edit { it.clear() }
        }
    }
}
