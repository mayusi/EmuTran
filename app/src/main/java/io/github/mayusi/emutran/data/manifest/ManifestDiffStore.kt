package io.github.mayusi.emutran.data.manifest

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.emutran.data.storage.SetupOptionsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.manifestDiffDataStore by preferencesDataStore(name = "emutran_manifest_diff")

/**
 * Captures a small "what's new" diff whenever the catalog auto-refreshes
 * from upstream and the *set of emulator entry ids* changes.
 *
 * The dashboard can render a "Pack updated: +X, -Y" banner from [pendingDiff]
 * and clear it via [clearPendingDiff] once the user dismisses it.
 *
 * == How it works ==
 *
 * [ObtainiumPackParser] calls [computeAndStoreDiff] after every successful
 * upstream refresh, passing the freshly-parsed set of entry ids plus a name
 * lookup AND the manifest variant it just refreshed (standard vs dual-screen).
 * We compare that set against the last-seen set persisted from the previous
 * refresh *of that same variant*:
 *
 *   - First run (no last-seen set on disk): store the baseline, emit NO diff.
 *     We never want to show "+everything" on first launch.
 *   - Subsequent runs where the id set is identical: update nothing, emit
 *     nothing new (the existing pending diff, if any, is left untouched).
 *   - Subsequent runs where the id set changed: compute added/removed ids,
 *     map them to display names via the supplied lookup, store a
 *     [PendingPackDiff], and update the last-seen set.
 *
 * == Why everything is keyed per variant ==
 *
 * The standard and dual-screen manifests have different id sets (~50 vs ~54).
 * If both variants are refreshed in one session and they shared a single
 * baseline key, each cross-variant refresh would diff its ids against the
 * *other* variant's baseline and fabricate a bogus +/- "Catalog updated"
 * banner. So the last-seen baseline AND the pending diff are both stored
 * under variant-qualified keys ([lastSeenIdsKey]/[pendingDiffKey]). The
 * parser passes which variant it just refreshed; the dashboard reads the
 * pending diff for the variant the device actually uses (resolved from
 * [SetupOptionsStore.isDualScreen]).
 *
 * Comparison is by *id*, never by name — names can be re-labelled upstream
 * without it being a meaningful add/remove. The caller is responsible for
 * excluding [ObtainiumPackParser.OBTAINIUM_META_ENTRY_ID] before passing the
 * id set in (it carries no real package and shouldn't surface in a banner).
 *
 * Using a dedicated DataStore file (emutran_manifest_diff) so this transient
 * banner state stays isolated from update metadata and user selections.
 */
@Singleton
class ManifestDiffStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val setupOptions: SetupOptionsStore,
    private val json: Json,
) {

    /**
     * The pending "what's new" diff for an explicit manifest [variant], or
     * null when there is nothing new to show (first launch, no change since
     * last refresh, or the banner was already dismissed via
     * [clearPendingDiff]).
     *
     * Reactive: emits on every change to emutran_manifest_diff.
     */
    fun pendingDiff(variant: String): Flow<PendingPackDiff?> =
        context.manifestDiffDataStore.data.map { prefs ->
            val raw = prefs[pendingDiffKey(variant)] ?: return@map null
            runCatching { json.decodeFromString(PendingPackDiff.serializer(), raw) }.getOrNull()
        }

    /**
     * The pending "what's new" diff for the variant THIS device uses, resolved
     * reactively from [SetupOptionsStore.isDualScreen]. The dashboard collects
     * this no-arg flow; it re-keys automatically if the user flips the
     * dual-screen toggle.
     *
     * Reactive: emits on every change to the device variant or to
     * emutran_manifest_diff.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val pendingDiff: Flow<PendingPackDiff?> =
        setupOptions.isDualScreen.flatMapLatest { dualScreen ->
            pendingDiff(variantOf(dualScreen))
        }

    /**
     * Compare [currentIds] against the last-seen id set persisted from the
     * previous refresh OF THE SAME [variant] and, if the set changed, store a
     * [PendingPackDiff] of the added/removed *names* (resolved via [idToName])
     * and advance that variant's last-seen set.
     *
     * Everything is keyed per [variant] so a standard refresh never diffs
     * against the dual-screen baseline (or vice-versa) and fabricates a bogus
     * banner when both variants are refreshed in one session.
     *
     * First run for that variant (no last-seen set persisted) records
     * [currentIds] as the baseline and stores NO diff — we don't want a
     * "+everything" banner on first launch.
     *
     * Safe to call on every successful refresh; a no-change refresh is a
     * cheap read with no write.
     *
     * @param variant which manifest variant these ids came from
     *   ([VARIANT_STANDARD] or [VARIANT_DUAL_SCREEN]).
     * @param idToName maps an entry id to its display name. Called for both
     *   added ids (looked up in the fresh catalog) and removed ids (which may
     *   no longer be in the catalog — the lookup should fall back to the id
     *   string itself rather than throwing).
     */
    suspend fun computeAndStoreDiff(
        variant: String,
        currentIds: Set<String>,
        idToName: (String) -> String,
    ) {
        val lastSeenKey = lastSeenIdsKey(variant)
        val pendingKey = pendingDiffKey(variant)

        val prefs = context.manifestDiffDataStore.data.first()
        val lastSeenRaw = prefs[lastSeenKey]

        when (val outcome = computeDiff(lastSeenRaw, currentIds, idToName)) {
            // First run / no change: just persist the baseline (or nothing).
            is DiffOutcome.BaselineOnly -> {
                context.manifestDiffDataStore.edit { it[lastSeenKey] = outcome.newLastSeen }
            }
            // Nothing changed — leave any pending diff as-is, write nothing.
            DiffOutcome.NoChange -> Unit
            // Set changed — advance baseline and persist the captured diff.
            is DiffOutcome.Changed -> {
                context.manifestDiffDataStore.edit { editor ->
                    editor[lastSeenKey] = outcome.newLastSeen
                    editor[pendingKey] = json.encodeToString(PendingPackDiff.serializer(), outcome.diff)
                }
            }
        }
    }

    /**
     * Pure diff math, factored out of [computeAndStoreDiff] so it can be unit
     * tested on the JVM without a DataStore. Given the previously-persisted
     * last-seen string ([lastSeenRaw], null on first run) and the freshly-parsed
     * [currentIds], decide what should be written:
     *
     *  - [DiffOutcome.BaselineOnly] — first run for this variant: record the
     *    baseline, emit NO diff (no "+everything" banner on first launch).
     *  - [DiffOutcome.NoChange]     — id set is identical to last-seen: write
     *    nothing, leave any pending diff untouched.
     *  - [DiffOutcome.Changed]      — id set changed: advance the baseline and
     *    capture a [PendingPackDiff] of added/removed *names* (via [idToName]).
     *
     * [now] is injected (defaults to wall-clock) so tests can assert a stable
     * [PendingPackDiff.computedAtEpoch].
     */
    internal fun computeDiff(
        lastSeenRaw: String?,
        currentIds: Set<String>,
        idToName: (String) -> String,
        now: Long = System.currentTimeMillis(),
    ): DiffOutcome {
        // First run for this variant: establish the baseline, never emit a diff.
        if (lastSeenRaw == null) {
            return DiffOutcome.BaselineOnly(newLastSeen = joinIds(currentIds))
        }

        val lastSeen = splitIds(lastSeenRaw)
        if (lastSeen == currentIds) return DiffOutcome.NoChange  // nothing changed

        val addedIds = currentIds - lastSeen
        val removedIds = lastSeen - currentIds
        val diff = PendingPackDiff(
            added = addedIds.map(idToName).sorted(),
            removed = removedIds.map(idToName).sorted(),
            computedAtEpoch = now,
        )
        return DiffOutcome.Changed(newLastSeen = joinIds(currentIds), diff = diff)
    }

    /**
     * Clear the pending diff for an explicit manifest [variant] (e.g. when the
     * user dismisses the banner).
     */
    suspend fun clearPendingDiff(variant: String) {
        context.manifestDiffDataStore.edit { it.remove(pendingDiffKey(variant)) }
    }

    /**
     * Clear the pending diff for the variant THIS device uses, resolved from
     * [SetupOptionsStore.isDualScreen]. The dashboard calls this no-arg form
     * when the user dismisses the banner.
     */
    suspend fun clearPendingDiff() {
        clearPendingDiff(variantOf(setupOptions.isDualScreen.first()))
    }

    /**
     * Serialize a set of ids to a single newline-joined string. Ids are
     * sorted so the persisted form is stable (purely cosmetic — equality
     * is computed on the parsed Set, not this string).
     */
    private fun joinIds(ids: Set<String>): String = ids.sorted().joinToString("\n")

    private fun splitIds(raw: String): Set<String> =
        if (raw.isEmpty()) emptySet() else raw.split("\n").toSet()

    companion object {
        /** Manifest variant tag for the standard / single-screen pack. */
        const val VARIANT_STANDARD = "standard"

        /** Manifest variant tag for the dual-screen pack. */
        const val VARIANT_DUAL_SCREEN = "dual_screen"

        /** Map the device's dual-screen flag to its manifest variant tag. */
        fun variantOf(isDualScreen: Boolean): String =
            if (isDualScreen) VARIANT_DUAL_SCREEN else VARIANT_STANDARD

        // Keys are variant-qualified so the standard and dual-screen baselines
        // (and their pending diffs) never collide. See class KDoc.
        // `internal` so unit tests can assert the per-variant keys never collide.
        internal fun lastSeenIdsKey(variant: String) =
            stringPreferencesKey("last_seen_entry_ids_$variant")

        internal fun pendingDiffKey(variant: String) =
            stringPreferencesKey("pending_pack_diff_$variant")
    }
}

/**
 * Outcome of [ManifestDiffStore.computeDiff] — the pure decision about what (if
 * anything) the variant's baseline/pending-diff keys should be updated to.
 */
internal sealed interface DiffOutcome {
    /**
     * First run for this variant: record [newLastSeen] as the baseline and emit
     * no diff banner.
     */
    data class BaselineOnly(val newLastSeen: String) : DiffOutcome

    /** Id set identical to last-seen: write nothing, leave any pending diff. */
    data object NoChange : DiffOutcome

    /**
     * Id set changed: advance the baseline to [newLastSeen] and persist [diff].
     */
    data class Changed(val newLastSeen: String, val diff: PendingPackDiff) : DiffOutcome
}

/**
 * A captured "what's new" diff between the previously-seen catalog and the
 * latest upstream refresh.
 *
 * [added] / [removed] are display names (already resolved from ids), sorted
 * for stable rendering. [computedAtEpoch] is [System.currentTimeMillis] at
 * the moment the diff was captured, so the banner can show recency if wanted.
 *
 * By construction at least one of [added]/[removed] is non-empty when this is
 * persisted (an unchanged refresh writes nothing).
 */
@Serializable
data class PendingPackDiff(
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val computedAtEpoch: Long = 0L,
)
