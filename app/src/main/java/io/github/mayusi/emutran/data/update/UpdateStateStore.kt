package io.github.mayusi.emutran.data.update

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

// FIX 1a: two separate DataStore files.
//
//   emutran_update_state  — per-entry JSON blobs only (the allUpdateInfo() flow).
//   emutran_update_meta   — scalar keys (selfCheckEpoch, dismissedVersion,
//                           lastNotifiedSignature) so a write to a scalar key
//                           does NOT re-emit allUpdateInfo() and trigger O(n)
//                           re-decodes of every entry blob.
//
private val Context.updateStateDataStore by preferencesDataStore(name = "emutran_update_state")
private val Context.updateMetaDataStore  by preferencesDataStore(name = "emutran_update_meta")

/**
 * Persists per-entry update check results in DataStore so they survive
 * process death. One JSON blob per entry id, plus scalar meta keys
 * (selfCheckEpoch, dismissedSelfUpdateVersion, lastNotifiedSignature)
 * in a separate DataStore file so writing them does NOT trigger
 * allUpdateInfo() re-emission (FIX 1).
 *
 * Using a separate DataStore from emutran_setup_options / emutran_selection
 * so a future "reset" wipe doesn't clear update metadata.
 */
@Singleton
class UpdateStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ── Emulator update state ──────────────────────────────────────────────

    /**
     * Serialized shape for one entry's known update state.
     *
     * [installedVersionCode] is -1 when the entry is not (or no longer) installed.
     * [availableVersion] is the latest version string returned by the source;
     * null means "not yet checked" or "check failed".
     * [lastCheckedEpoch] is [System.currentTimeMillis] at time of last check; 0 = never.
     */
    @Serializable
    data class PersistedUpdateInfo(
        val entryId: String,
        val installedVersionCode: Long = -1L,
        val installedVersionName: String = "",
        val availableVersion: String? = null,
        val lastCheckedEpoch: Long = 0L,
    )

    /**
     * Emit the full map of persisted update info, keyed by entryId.
     * Emits on every change to emutran_update_state (DataStore is reactive).
     * The map only contains entries that have been written at least once.
     *
     * FIX 1: scalar keys (selfCheckEpoch, dismissedVersion,
     * lastNotifiedSignature) now live in emutran_update_meta, so a write
     * to those keys no longer re-triggers this flow.
     */
    fun allUpdateInfo(): Flow<Map<String, PersistedUpdateInfo>> =
        context.updateStateDataStore.data.map { prefs ->
            // FIX 9: iterate entries and read the value directly — no second
            // preference-key rebuild + lookup per entry.
            prefs.asMap()
                .entries
                .filter { it.key.name.startsWith(ENTRY_PREFIX) }
                .mapNotNull { (_, value) ->
                    val raw = value as? String ?: return@mapNotNull null
                    runCatching { json.decodeFromString<PersistedUpdateInfo>(raw) }.getOrNull()
                }
                .associateBy { it.entryId }
        }

    /** Read a single entry's state synchronously (suspending). */
    suspend fun getUpdateInfo(entryId: String): PersistedUpdateInfo? {
        val key = entryKey(entryId)
        val raw = context.updateStateDataStore.data.first()[key] ?: return null
        return runCatching { json.decodeFromString<PersistedUpdateInfo>(raw) }.getOrNull()
    }

    /** Persist updated state for one entry. */
    suspend fun putUpdateInfo(info: PersistedUpdateInfo) {
        val key = entryKey(info.entryId)
        context.updateStateDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(PersistedUpdateInfo.serializer(), info)
        }
    }

    /**
     * FIX 1b: Bulk-write all entries in ONE DataStore transaction (1 emission
     * instead of N). UpdateRepository.checkNow() collects results in memory
     * then calls this once so 30 entries produce 1 DataStore write, not 30.
     */
    suspend fun putAllUpdateInfo(infos: List<PersistedUpdateInfo>) {
        if (infos.isEmpty()) return
        context.updateStateDataStore.edit { prefs ->
            for (info in infos) {
                prefs[entryKey(info.entryId)] =
                    json.encodeToString(PersistedUpdateInfo.serializer(), info)
            }
        }
    }

    // ── Self-update last-check epoch (scalar → meta store) ─────────────────

    private val selfCheckEpochKey = longPreferencesKey("self_update_last_checked_epoch")

    val selfCheckLastEpoch: Flow<Long> =
        context.updateMetaDataStore.data.map { it[selfCheckEpochKey] ?: 0L }

    suspend fun setSelfCheckEpoch(epochMs: Long) {
        context.updateMetaDataStore.edit { it[selfCheckEpochKey] = epochMs }
    }

    // ── Self-update skip-this-version (scalar → meta store) ───────────────

    /**
     * Persists the version string the user chose to skip ("Not now" / "Later").
     * When [SelfUpdateRepository.bannerState] finds an [SelfUpdateResult.Available]
     * whose version equals this value, it returns [SelfUpdateResult.UpToDate] so
     * the launch-time banner is suppressed.
     *
     * null  → no version has been skipped (or the skip was cleared).
     * non-null → the exact cleaned version string (e.g. "0.3.0") to suppress.
     *
     * The dashboard agent reads this via [SelfUpdateRepository.bannerState]; it
     * should never need to read this key directly.
     */
    private val dismissedSelfUpdateVersionKey =
        stringPreferencesKey("self_update_dismissed_version")

    val dismissedSelfUpdateVersion: Flow<String?> =
        context.updateMetaDataStore.data.map { it[dismissedSelfUpdateVersionKey] }

    /**
     * Persist [version] as the version to skip, or pass null to clear the skip
     * (e.g. when a new release supersedes the skipped one — though currently this is
     * not called automatically; the dismissal flows naturally when a newer
     * release appears with a different version string).
     */
    suspend fun setDismissedSelfUpdateVersion(version: String?) {
        context.updateMetaDataStore.edit { prefs ->
            if (version == null) {
                prefs.remove(dismissedSelfUpdateVersionKey)
            } else {
                prefs[dismissedSelfUpdateVersionKey] = version
            }
        }
    }

    // ── Notification deduplication signature (scalar → meta store) ────────

    /**
     * Stable signature of the update set that was most recently notified to
     * the user. Built by sorting and joining "entryId:availableVersion" pairs
     * for every entry that has [UpdateInfo.hasUpdate] == true, so the signature
     * changes whenever the set of pending updates changes (new entry appears or
     * an existing one is superseded by yet another release).
     *
     * null means we have never sent an update notification, so the first check
     * that finds updates will always notify.
     */
    private val lastNotifiedSignatureKey =
        stringPreferencesKey("update_last_notified_signature")

    /**
     * Returns the signature string stored by the last successful
     * [setLastNotifiedSignature] call, or null if no notification has ever
     * been posted.
     */
    suspend fun getLastNotifiedSignature(): String? =
        context.updateMetaDataStore.data.first()[lastNotifiedSignatureKey]

    /**
     * Persist [signature] as the marker for the set of updates that were
     * most recently notified.
     */
    suspend fun setLastNotifiedSignature(signature: String) {
        context.updateMetaDataStore.edit { prefs ->
            prefs[lastNotifiedSignatureKey] = signature
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun entryKey(entryId: String) =
        stringPreferencesKey("$ENTRY_PREFIX$entryId")

    companion object {
        /** All per-entry keys share this prefix so we can enumerate them. */
        private const val ENTRY_PREFIX = "update_entry_"
    }
}
