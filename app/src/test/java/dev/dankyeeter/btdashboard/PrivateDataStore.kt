package dev.dankyeeter.btdashboard

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlin.properties.ReadOnlyProperty

/**
 * A store's own DataStore, which the store does not expose: the only way to put
 * text in it that its encoder would never write. The delegate ignores the property
 * it is handed, so any one will do.
 *
 * @param fileClass the Kotlin file class that declares the `by preferencesDataStore` property.
 * @param property that property's name.
 */
@Suppress("UNCHECKED_CAST")
fun privateDataStore(context: Context, fileClass: String, property: String): DataStore<Preferences> =
    (
        Class.forName(fileClass)
            .getDeclaredField("$property\$delegate")
            .apply { isAccessible = true }
            .get(null) as ReadOnlyProperty<Context, DataStore<Preferences>>
    ).getValue(context, ::ignoredProperty)

private val ignoredProperty = Unit
