package dev.dankyeeter.btdashboard.system.persist

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
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

/**
 * Which metal the Edgy theme is cast in.
 *
 * Stored as an ARGB value rather than a name so a future colour picker needs no
 * new storage — the named entries below are shortcuts into the same field, not
 * a separate mechanism.
 *
 * Only the Edgy theme uses it. Under Material You the accent belongs to the
 * wallpaper, and painting a chosen metal over it would fight the system palette
 * instead of following it.
 */
enum class AccentChoice(val id: String, val label: String, val argb: Long) {
    GOLD("gold", "Gold", 0xFFC08F28),
    SILVER("silver", "Silver", 0xFFA8ADB4),
    COPPER("copper", "Copper", 0xFFB56A3C),
    STEEL("steel", "Steel blue", 0xFF4E7FA8),
    ROSE("rose", "Rose", 0xFFB05A6A);

    companion object {
        fun fromId(id: String?): AccentChoice = entries.firstOrNull { it.id == id } ?: GOLD
    }
}

/** Persists the chosen theme. One key, no migration surface. */
class AppearanceStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: Flow<Preferences> = appContext.appearanceDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    val choice: Flow<AppearanceChoice> = prefs.map { AppearanceChoice.fromId(it[KEY_THEME]) }

    val accent: Flow<AccentChoice> = prefs.map { AccentChoice.fromId(it[KEY_ACCENT]) }

    /**
     * The accent as a free ARGB value — the single source the theme reads.
     *
     * The named [AccentChoice] entries are shortcuts that write this same
     * field. The legacy id key is only a fallback for profiles saved before
     * the picker existed; the first write of any accent moves them over.
     */
    val accentArgb: Flow<Long> = prefs.map {
        it[KEY_ACCENT_ARGB] ?: AccentChoice.fromId(it[KEY_ACCENT]).argb
    }

    suspend fun current(): AppearanceChoice = choice.first()

    suspend fun setChoice(choice: AppearanceChoice) {
        appContext.appearanceDataStore.edit { it[KEY_THEME] = choice.id }
    }

    suspend fun setAccent(accent: AccentChoice) {
        appContext.appearanceDataStore.edit {
            it[KEY_ACCENT] = accent.id
            it[KEY_ACCENT_ARGB] = accent.argb
        }
    }

    suspend fun setAccentArgb(argb: Long) {
        appContext.appearanceDataStore.edit { it[KEY_ACCENT_ARGB] = argb }
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_choice")
        val KEY_ACCENT = stringPreferencesKey("accent_choice")
        val KEY_ACCENT_ARGB = longPreferencesKey("accent_argb")
    }
}
