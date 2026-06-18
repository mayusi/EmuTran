package io.github.mayusi.emutran.data.device

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot of installed packages on the device.
 *
 * Used by the picker to mark entries the user already has, so we don't
 * pointlessly redownload Flycast when it's already sitting in the
 * launcher. Cheap to refresh — re-call snapshot() any time you need
 * the latest view (e.g., on screen ON_RESUME after the user comes
 * back from installing something).
 *
 * NOTE: Most Obtainium pack entries have IDs that match the real
 * Android package name (com.flycast.emulator, org.ppsspp.ppsspp, etc.)
 * so a direct lookup works. A handful use Obtainium's numeric tracker
 * IDs (487343354 for RetroArch) which CAN'T be matched this way —
 * those entries are best-effort and we just won't flag them as
 * installed even if they are. Worst case the user gets an "install"
 * that the system installer detects as a downgrade/same-version and
 * either skips or prompts to update.
 *
 * Caching strategy (FIX 2):
 *  - [snapshot] keeps its non-suspend signature so every existing caller
 *    keeps compiling without edits. It returns the cache instantly when
 *    warm (< [CACHE_TTL_MS] old); on a cold cache it falls back to the
 *    blocking PackageManager query (only happens once per app session on
 *    the very first call — after that the cache is always warm).
 *  - [refresh] is a suspend fun that does the IO-dispatched query and
 *    updates the cache. Coroutine-owning callers (ProgressViewModel,
 *    SetupStateDetector) can call refresh() before snapshot() to ensure
 *    freshness. Non-coroutine callers (DashboardViewModel.isEmuHelperInstalled,
 *    PickAppsViewModel.refreshInstalled) rely on the TTL-based cache, which
 *    is always warm by the time the user reaches those screens since
 *    ProgressViewModel called refresh() during setup.
 *
 * After a known install/uninstall event, call [refresh] (it re-queries
 * PackageManager and re-warms the cache in one step) rather than waiting for
 * the TTL to lapse — UpdateRepository.updateOne does this after a successful
 * install.
 */
@Singleton
class InstalledAppsRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var cachedSet: Set<String> = emptySet()
    @Volatile private var cacheTimestampMs: Long = 0L

    companion object {
        /** How long (ms) the in-memory package-list cache stays valid. */
        private const val CACHE_TTL_MS = 4_000L
    }

    /**
     * Return the set of currently-installed package names.
     *
     * Returns the cached set immediately when the cache is younger than
     * [CACHE_TTL_MS]. On a cold/expired cache, falls back to a blocking
     * PackageManager query (typically < 50 ms; only happens once per session
     * on the very first call). Existing callers need no changes.
     *
     * Callers that are already inside a coroutine should call [refresh] first
     * to guarantee freshness without blocking the calling thread.
     */
    fun snapshot(): Set<String> {
        val now = System.currentTimeMillis()
        if (now - cacheTimestampMs < CACHE_TTL_MS) return cachedSet
        // Cache is cold — do the blocking query. This is the "once per app
        // session" cold-start path; after this the cache stays warm for all
        // subsequent calls within the TTL window.
        val fresh = queryPackageManager()
        cachedSet = fresh
        cacheTimestampMs = System.currentTimeMillis()
        return fresh
    }

    /**
     * IO-dispatched cache refresh. Call this from a coroutine (e.g., in a
     * ViewModel's viewModelScope.launch) before performance-sensitive reads
     * so the blocking query never runs on the main thread.
     *
     * After this returns, [snapshot] will return the updated set without
     * re-querying until the TTL expires.
     */
    suspend fun refresh() {
        val fresh = withContext(Dispatchers.IO) { queryPackageManager() }
        cachedSet = fresh
        cacheTimestampMs = System.currentTimeMillis()
    }

    /** Raw PackageManager query — always blocking, always off-cache. */
    private fun queryPackageManager(): Set<String> {
        val pm = context.packageManager
        return try {
            val list: List<PackageInfo> = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
            list.mapNotNull { it.packageName }.toSet()
        } catch (t: Throwable) {
            // If the OS or some MIUI-style restriction blocks this,
            // fall back to empty rather than crashing the picker.
            emptySet()
        }
    }
}
