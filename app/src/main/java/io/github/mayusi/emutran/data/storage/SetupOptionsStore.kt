package io.github.mayusi.emutran.data.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.setupOptionsDataStore by preferencesDataStore(name = "emutran_setup_options")

/**
 * Boolean toggles the user flips during setup. Lives in its own DataStore
 * so a future "reset setup" wipe doesn't tangle with the persisted root
 * path or selected-apps set.
 *
 * Currently only one flag — GPU driver staging — but designed so adding
 * "include RetroArch cores" or similar opt-ins later is one key change.
 */
@Singleton
class SetupOptionsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val stageDriversKey = booleanPreferencesKey("stage_gpu_drivers")
    private val dualScreenKey = booleanPreferencesKey("is_dual_screen")

    /** Stage custom Adreno GPU drivers in tools/turnip/. Default off — niche feature. */
    val stageGpuDrivers: Flow<Boolean> = context.setupOptionsDataStore.data.map {
        it[stageDriversKey] ?: false
    }

    suspend fun setStageGpuDrivers(value: Boolean) {
        context.setupOptionsDataStore.edit { it[stageDriversKey] = value }
    }

    /**
     * Whether this device is a dual-screen handheld (AYN Thor, Anbernic DS,
     * AYANEO Pocket DS, …). Drives which Obtainium manifest the picker,
     * dashboard, and setup pipeline load (standard vs. dual-screen).
     *
     * Default off. The Device Info screen seeds this with the auto-detected
     * guess and lets the user override it; everyone downstream reads it so a
     * Thor user's picker/dashboard match.
     */
    val isDualScreen: Flow<Boolean> = context.setupOptionsDataStore.data.map {
        it[dualScreenKey] ?: false
    }

    suspend fun setIsDualScreen(value: Boolean) {
        context.setupOptionsDataStore.edit { it[dualScreenKey] = value }
    }
}
