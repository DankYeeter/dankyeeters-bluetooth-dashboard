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
 * Remembers what the OS cannot be asked about: which optional setup steps the
 * user waved away, and which one-time notices have been accepted.
 *
 * Everything else is read from the system on the spot. There used to be a
 * "wizard completed" flag here as well, and it was the wrong shape: it went on
 * claiming the setup was done while Android quietly revoked a permission, and
 * on a fresh install it decided the question before its own value had arrived.
 * Whether the setup is needed is now derived from the live state - see
 * [SetupStatus.phase].
 */
class SetupStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: Flow<Preferences> = appContext.setupDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    val skippedStepIds: Flow<Set<String>> = prefs.map { it[KEY_SKIPPED] ?: emptySet() }

    /**
     * Whether the user has been told that the app opens a connection to this
     * phone's own debugging service, and agreed to it.
     *
     * Asked once and remembered, because it is an explanation rather than a
     * permission: the app had no INTERNET permission for its whole life, and
     * that was a structural guarantee. Starting the helper without a computer
     * needs a socket, Android gates every socket behind that permission - so
     * the guarantee became a promise, and the user should hear that from the
     * app rather than from a permission list.
     */
    val localConnectionAccepted: Flow<Boolean> = prefs.map { it[KEY_LOCAL_CONNECTION] ?: false }

    suspend fun isLocalConnectionAccepted(): Boolean = localConnectionAccepted.first()

    suspend fun setLocalConnectionAccepted(accepted: Boolean) {
        appContext.setupDataStore.edit { it[KEY_LOCAL_CONNECTION] = accepted }
    }

    /**
     * Whether the observation run's start notice — leaving the screen discards
     * the run — has been confirmed with "Continue" (`UI_SPEC.md` T-039,
     * decision 5).
     *
     * A confirmation, not a measurement: nothing of any run is kept here, which
     * is what `GOAL.md` AK-17 rules out.
     */
    suspend fun isObservationRunNoticeAccepted(): Boolean =
        prefs.map { it[KEY_OBSERVATION_RUN_NOTICE] ?: false }.first()

    suspend fun setObservationRunNoticeAccepted(accepted: Boolean) {
        appContext.setupDataStore.edit { it[KEY_OBSERVATION_RUN_NOTICE] = accepted }
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
        val KEY_LOCAL_CONNECTION = booleanPreferencesKey("local_connection_accepted")
        val KEY_OBSERVATION_RUN_NOTICE = booleanPreferencesKey("observation_run_notice_accepted")
        val KEY_SKIPPED = stringSetPreferencesKey("setup_skipped_steps")
    }
}
