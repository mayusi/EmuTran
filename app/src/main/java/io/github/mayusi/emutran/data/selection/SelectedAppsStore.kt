package io.github.mayusi.emutran.data.selection

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "emutran_selection")

/**
 * Persists the set of app IDs the user picked, so the progress screen
 * can read them after navigation transition.
 *
 * Using a separate DataStore from emutran_prefs to avoid coupling user
 * preferences (storage root, etc.) with transient setup state.
 */
@Singleton
class SelectedAppsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = stringSetPreferencesKey("picked_app_ids")

    val pickedIds: Flow<Set<String>> = context.dataStore.data.map {
        it[key] ?: emptySet()
    }

    suspend fun save(ids: Set<String>) {
        context.dataStore.edit { it[key] = ids }
    }
}
