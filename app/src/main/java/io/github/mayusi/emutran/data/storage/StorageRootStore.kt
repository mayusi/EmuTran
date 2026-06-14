package io.github.mayusi.emutran.data.storage

import android.content.Context
import android.os.Environment
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "emutran_prefs")

/**
 * Persists the user's chosen storage root as an absolute path.
 *
 * Replaces the SAF-based URI flow. Now that we hold
 * MANAGE_EXTERNAL_STORAGE we can use plain File and the user can pick
 * absolutely anywhere — internal storage, SD card, USB-OTG.
 */
@Singleton
class StorageRootStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = stringPreferencesKey("storage_root_path")

    /** Sensible default: /storage/emulated/0/Emulation/ on internal storage. */
    val defaultPath: String =
        File(Environment.getExternalStorageDirectory(), "Emulation").absolutePath

    val rootPath: Flow<String?> = context.dataStore.data.map { it[key] }

    suspend fun save(path: String) {
        context.dataStore.edit { it[key] = path }
    }
}
