package dev.dankyeeter.btdashboard.system.persist

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.appearanceDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "appearance")

/**
 * The three themes from DESIGN.md, plus "follow the system".
 *
 * Stored as a plain id string rather than an ordinal: reordering or removing an
 * entry must never silently repaint someone's app in a different theme.
 */
enum class AppearanceChoice(val id: String, val label: String, val description: String) {
    SYSTEM(
        id = "system",
        label = "Follow system",
        description = "Light or dark, whichever Android is currently using.",
    ),
    LIGHT(
        id = "light",
        label = "Light",
        description = "Material You light, tinted from your wallpaper.",
    ),
    DARK(
        id = "dark",
        label = "Dark",
        description = "Material You dark, tinted from your wallpaper.",
    ),
    EDGY(
        id = "edgy",
        label = "Edgy",
        description = "True black with gold accents. Saves power on this OLED panel.",
    ),
    ;

    companion object {
        fun fromId(id: String?): AppearanceChoice =
            entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/** Persists the chosen theme. One key, no migration surface. */
class AppearanceStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: Flow<Preferences> = appContext.appearanceDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    val choice: Flow<AppearanceChoice> = prefs.map { AppearanceChoice.fromId(it[KEY_THEME]) }

    suspend fun current(): AppearanceChoice = choice.first()

    suspend fun setChoice(choice: AppearanceChoice) {
        appContext.appearanceDataStore.edit { it[KEY_THEME] = choice.id }
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_choice")
    }
}
