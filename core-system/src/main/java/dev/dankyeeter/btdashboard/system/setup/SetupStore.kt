package dev.dankyeeter.btdashboard.system.setup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.setupDataStore: DataStore<Preferences> by preferencesDataStore(name = "setup_state")

/**
 * Remembers that the first-run wizard has been walked through, and which steps
 * the user chose to skip.
 *
 * "Completed" only means "seen"; it is not a claim that everything is granted.
 * The live status is always recomputed from the OS, so the wizard can never
 * show a stale green tick.
 */
class SetupStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: Flow<Preferences> = appContext.setupDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    /** False on a fresh install → the wizard opens by itself once. */
    val wizardCompleted: Flow<Boolean> = prefs.map { it[KEY_COMPLETED] ?: false }

    val skippedStepIds: Flow<Set<String>> = prefs.map { it[KEY_SKIPPED] ?: emptySet() }

    suspend fun isWizardCompleted(): Boolean = wizardCompleted.first()

    suspend fun setWizardCompleted(completed: Boolean) {
        appContext.setupDataStore.edit { it[KEY_COMPLETED] = completed }
    }

    suspend fun setSkipped(stepId: String, skipped: Boolean) {
        appContext.setupDataStore.edit { store ->
            val current = store[KEY_SKIPPED] ?: emptySet()
            store[KEY_SKIPPED] = if (skipped) current + stepId else current - stepId
        }
    }

    /** Used by "run the wizard again" so previously skipped steps are asked once more. */
    suspend fun clearSkips() {
        appContext.setupDataStore.edit { it[KEY_SKIPPED] = emptySet() }
    }

    private companion object {
        val KEY_COMPLETED = booleanPreferencesKey("setup_wizard_completed")
        val KEY_SKIPPED = stringSetPreferencesKey("setup_skipped_steps")
    }
}
