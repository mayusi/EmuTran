package io.github.mayusi.emutran.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.emutran.data.device.InstalledAppsRegistry
import io.github.mayusi.emutran.data.manifest.ObtainiumPackParser
import io.github.mayusi.emutran.data.source.AppSourceRouter
import io.github.mayusi.emutran.data.source.ResolveResult
import io.github.mayusi.emutran.data.storage.SetupOptionsStore
import io.github.mayusi.emutran.domain.download.ApkDownloader
import io.github.mayusi.emutran.domain.install.InstallResult
import io.github.mayusi.emutran.domain.install.InstallerRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks for available updates to installed emulators that appear in the
 * Obtainium manifest, and drives the download→install pipeline for those
 * that have an update available.
 *
 * == Version comparison ==
 *
 * [AppSourceRouter.resolve] returns a version *string* (tag name from the
 * GitHub / Gitea API — e.g. "v1.14.0", "2024.12.01", "r3645"). We can't
 * reliably turn every release tag into a numeric version code, so we use
 * string inequality: if the latest tag ≠ the tag we stored at the last
 * install time, we flag it as a potential update. This errs toward
 * showing a badge rather than silently missing one.
 *
 * For the initial installed version we read [PackageInfo.longVersionCode]
 * (and fall back to [PackageInfo.versionName]) from PackageManager so we
 * have *something* to show in the UI. The version we compare against is
 * always the latest string from the source.
 *
 * == Rate limiting ==
 *
 * Per-entry minimum check interval = 6 hours (matches [HttpCache.MANIFEST_TTL_MS]).
 * The ETag mechanism in [GitHubReleasesSource] means 304s burn no quota even
 * within that window, but skipping the network call entirely saves the
 * DNS/TLS round trip too.
 *
 * Concurrency is bounded by [MAX_CONCURRENT_RESOLVES] to avoid hammering
 * GitHub with 30 simultaneous API requests.
 *
 * == Observable state ==
 *
 * [updateState] merges the persisted DataStore state with real-time
 * download/install progress emitted via [updateProgressFlow]. UI agents
 * subscribe to these flows; no method call is needed to start receiving.
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val packParser: ObtainiumPackParser,
    private val sourceRouter: AppSourceRouter,
    private val downloader: ApkDownloader,
    private val installer: InstallerRouter,
    private val store: UpdateStateStore,
    private val setupOptions: SetupOptionsStore,
    // FIX 3a: inject InstalledAppsRegistry so we use its cached PM snapshot
    // instead of doing N full scans per check cycle.
    private val installedAppsRegistry: InstalledAppsRegistry,
) {

    // ── Public: observable state ───────────────────────────────────────────

    /**
     * Map of entryId → [UpdateInfo] for every installed emulator that maps
     * to a known manifest entry. Emits whenever either:
     *   - The persisted DataStore state changes (a check completed).
     *   - A download/install cycle finishes (via [updateProgressFlow]).
     *
     * UI agents should collect this on the main dispatcher via
     * `.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())`.
     */
    fun updateState(): Flow<Map<String, UpdateInfo>> =
        store.allUpdateInfo().map { persisted ->
            persisted.mapValues { (id, info) ->
                UpdateInfo(
                    entryId = id,
                    installedVersionCode = info.installedVersionCode,
                    installedVersionName = info.installedVersionName,
                    availableVersion = info.availableVersion,
                    hasUpdate = computeHasUpdate(info),
                    lastCheckedEpoch = info.lastCheckedEpoch,
                )
            }
        }

    /**
     * Number of installed emulators for which a newer version was found.
     * Suitable for a navigation badge / counter chip.
     */
    fun updateCount(): Flow<Int> = updateState().map { map ->
        map.values.count { it.hasUpdate }
    }

    /**
     * Real-time progress events emitted during [updateOne] / [updateAll].
     * Each emission carries the entryId plus the current download/install
     * progress so the UI can show a per-app progress bar.
     */
    val updateProgressFlow: MutableSharedFlow<UpdateProgress> =
        MutableSharedFlow(extraBufferCapacity = 32)

    // ── Public: actions ────────────────────────────────────────────────────

    /**
     * Check for updates for all installed emulators in the manifest.
     *
     * @param force When true, ignores the 6-hour rate-limit gate and
     *   re-checks every entry even if recently checked (used by "Check now"
     *   button). When false (background worker), entries checked < 6h ago
     *   are skipped.
     *
     * FIX 1b: all results are collected in-memory and written to DataStore in
     * ONE transaction at the end (via [UpdateStateStore.putAllUpdateInfo]),
     * producing 1 DataStore emission instead of N. This eliminates the
     * O(n²) re-decode of all entry blobs on every single write.
     *
     * FIX 3a: uses [InstalledAppsRegistry.snapshot] for the installed-package
     * set, then performs ONE additional PM query for versionCode/versionName on
     * only the matching packages — avoiding N full PM scans per check.
     */
    suspend fun checkNow(force: Boolean = false) = withContext(Dispatchers.IO) {
        val entries = loadManifestEntries()

        // FIX 3a: use the registry's cached Set<String> to find which entries
        // are installed, then do a single targeted PM query for versionCode/Name.
        val installedPackageNames = installedAppsRegistry.snapshot()
        val installedInfoMap = queryVersionInfoForPackages(installedPackageNames)

        // Only check entries that are actually installed and have a real package id.
        val candidates = entries.filter { entry ->
            entry.id in installedPackageNames && !entry.trackOnly
        }

        // Semaphore caps simultaneous in-flight API calls.
        val semaphore = Semaphore(MAX_CONCURRENT_RESOLVES)
        val nowMs = System.currentTimeMillis()

        // FIX 1b: collect all results in-memory first.
        val pendingWrites = mutableListOf<UpdateStateStore.PersistedUpdateInfo>()
        val pendingWritesMutex = Mutex()

        coroutineScope {
            val jobs = candidates.map { entry ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val persisted = store.getUpdateInfo(entry.id)

                        // Rate-limit gate: skip if checked recently and not forced.
                        if (!force && persisted != null) {
                            val ageMs = nowMs - persisted.lastCheckedEpoch
                            if (ageMs < CHECK_INTERVAL_MS) return@withPermit
                        }

                        val pkgInfo = installedInfoMap[entry.id]
                        val installedVersionCode = pkgInfo?.longVersionCode ?: -1L
                        val installedVersionName = pkgInfo?.versionName ?: ""

                        val resolveResult = runCatching { sourceRouter.resolve(entry) }.getOrElse {
                            ResolveResult.Failed(it.message ?: it.javaClass.simpleName)
                        }

                        val availableVersion: String? = when (resolveResult) {
                            is ResolveResult.Found -> resolveResult.version
                            else -> persisted?.availableVersion // keep last known on failure
                        }

                        val info = UpdateStateStore.PersistedUpdateInfo(
                            entryId = entry.id,
                            installedVersionCode = installedVersionCode,
                            installedVersionName = installedVersionName,
                            availableVersion = availableVersion,
                            lastCheckedEpoch = nowMs,
                        )
                        pendingWritesMutex.withLock { pendingWrites.add(info) }
                    }
                }
            }
            jobs.forEach { it.await() }
        }

        // FIX 1b: ONE DataStore transaction writes all entries — 1 emission, not N.
        store.putAllUpdateInfo(pendingWrites)
    }

    /**
     * Download and install the latest version of a single entry.
     * Progress events are emitted to [updateProgressFlow].
     */
    suspend fun updateOne(entryId: String) = withContext(Dispatchers.IO) {
        val entries = loadManifestEntries()
        val entry = entries.find { it.id == entryId }
            ?: run {
                updateProgressFlow.emit(
                    UpdateProgress.Failed(entryId, "Entry not found in manifest")
                )
                return@withContext
            }

        updateProgressFlow.emit(UpdateProgress.Resolving(entryId))

        val resolved = runCatching { sourceRouter.resolve(entry) }.getOrElse {
            updateProgressFlow.emit(
                UpdateProgress.Failed(entryId, it.message ?: "Resolve error")
            )
            return@withContext
        }

        if (resolved !is ResolveResult.Found) {
            val reason = (resolved as? ResolveResult.Failed)?.reason ?: "Unsupported source"
            updateProgressFlow.emit(UpdateProgress.Failed(entryId, reason))
            return@withContext
        }

        // FIX 1 (SHA-256 sidecar): resolved.sha256 is always null (sources only set
        // sha256Url), so passing it left updates installing UNVERIFIED. Mirror the
        // SETUP path (ProgressViewModel): when the source published a sidecar, fetch
        // the hash now and treat a fetch failure as a HARD error — never install
        // without the integrity check. When no sidecar exists, proceed unverified.
        val expectedSha256: String? = if (resolved.sha256Url != null) {
            val hash = sourceRouter.fetchSha256Sidecar(resolved.sha256Url)
            if (hash == null) {
                updateProgressFlow.emit(
                    UpdateProgress.Failed(
                        entryId,
                        "SHA-256 sidecar fetch failed — refusing to download without integrity check"
                    )
                )
                return@withContext
            }
            hash
        } else {
            null  // No sidecar published for this release; download proceeds unverified.
        }

        // Download phase — forward each chunk to the shared progress flow.
        var downloadedFile: File? = null
        downloader.download(
            url = resolved.apkUrl,
            filename = resolved.filename,
            expectedSha256 = expectedSha256,
        ).collect { progress ->
            when (progress) {
                is ApkDownloader.Progress.Started ->
                    updateProgressFlow.emit(UpdateProgress.Downloading(entryId, 0L, progress.totalBytes))
                is ApkDownloader.Progress.Chunk ->
                    updateProgressFlow.emit(
                        UpdateProgress.Downloading(entryId, progress.downloaded, progress.totalBytes)
                    )
                is ApkDownloader.Progress.Done -> {
                    downloadedFile = progress.file
                }
                is ApkDownloader.Progress.Failed -> {
                    updateProgressFlow.emit(UpdateProgress.Failed(entryId, progress.message))
                    return@collect
                }
            }
        }

        val apk = downloadedFile ?: return@withContext   // Failed already emitted above

        // Install phase.
        updateProgressFlow.emit(UpdateProgress.Installing(entryId))
        val installResult = runCatching { installer.install(apk) }.getOrElse { t ->
            updateProgressFlow.emit(UpdateProgress.Failed(entryId, t.message ?: "Install exception"))
            return@withContext
        }

        when (installResult) {
            is InstallResult.Installed -> {
                // FIX 3a: refresh the registry then snapshot for just this package
                // instead of doing a full PM scan (installedPackages() call removed).
                installedAppsRegistry.refresh()
                val currentInfo = store.getUpdateInfo(entryId)
                if (currentInfo != null) {
                    // Re-query PM for the newly-installed version of this one package.
                    val freshPkgInfo = querySinglePackage(entryId)
                    store.putUpdateInfo(
                        currentInfo.copy(
                            installedVersionCode = freshPkgInfo?.longVersionCode
                                ?: currentInfo.installedVersionCode,
                            installedVersionName = freshPkgInfo?.versionName
                                ?: currentInfo.installedVersionName,
                            // Keep availableVersion so UI can show "up to date" label
                        )
                    )
                }
                updateProgressFlow.emit(UpdateProgress.Done(entryId))
            }
            is InstallResult.Cancelled ->
                updateProgressFlow.emit(UpdateProgress.Cancelled(entryId))
            is InstallResult.Failed ->
                updateProgressFlow.emit(UpdateProgress.Failed(entryId, installResult.message))
        }
    }

    // FIX 3b: guard updateAll() against concurrent invocation.
    private val updateAllMutex = Mutex()

    /**
     * Update all entries that currently have [UpdateInfo.hasUpdate] = true.
     * Runs installs sequentially (PackageInstaller requires serial sessions).
     *
     * FIX 3b: a Mutex prevents double-invocation (e.g. double-tap on
     * "Update all"). If already running, the new call returns immediately.
     */
    suspend fun updateAll() {
        if (!updateAllMutex.tryLock()) return  // already running — skip
        try {
            val pending = updateState().first()
                .values.filter { it.hasUpdate }.map { it.entryId }
            for (id in pending) {
                updateOne(id)
            }
        } finally {
            updateAllMutex.unlock()
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Load the correct manifest variant (standard vs. dual-screen) from
     * [ObtainiumPackParser], respecting the user's screen-type setting.
     */
    private suspend fun loadManifestEntries() =
        if (setupOptions.isDualScreen.first()) {
            packParser.loadDualScreen()
        } else {
            packParser.loadStandard()
        }

    /**
     * FIX 3a: Query the PM for only the packages that match the installed
     * set. Returns a Map<packageName, PackageInfo> with versionCode + name.
     * Does ONE PM scan and filters — far cheaper than scanning all packages
     * twice (once for names, once per-entry inside updateOne).
     */
    private fun queryVersionInfoForPackages(packageNames: Set<String>): Map<String, PackageInfo> {
        val pm = context.packageManager
        return try {
            val list: List<PackageInfo> = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
            list.filter { it.packageName in packageNames }.associateBy { it.packageName }
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    /** Query PM for a single package (used after a successful install). */
    private fun querySinglePackage(packageName: String): PackageInfo? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Returns true when the source reported a version that is different from
     * the last version we recorded as installed.
     */
    internal fun computeHasUpdate(info: UpdateStateStore.PersistedUpdateInfo): Boolean {
        if (info.installedVersionCode < 0) return false       // not installed
        val available = info.availableVersion ?: return false  // not yet checked
        return available.trimStart('v', 'V') != info.installedVersionName.trimStart('v', 'V')
    }

    companion object {
        /** Minimum age between automatic checks for a single entry: 6 hours. */
        const val CHECK_INTERVAL_MS: Long = 6 * 60 * 60 * 1_000L

        /**
         * Maximum simultaneous source API calls.
         */
        private const val MAX_CONCURRENT_RESOLVES = 4
    }
}

// ── Public model types ─────────────────────────────────────────────────────────

/**
 * Runtime view of one entry's update state.
 */
data class UpdateInfo(
    val entryId: String,
    /** Installed package versionCode, or -1 if not currently installed. */
    val installedVersionCode: Long,
    /** Installed package versionName (may be empty if querying failed). */
    val installedVersionName: String,
    /** Latest version string from the source; null if not yet checked. */
    val availableVersion: String?,
    /** True when [availableVersion] is newer than [installedVersionName]. */
    val hasUpdate: Boolean,
    /** [System.currentTimeMillis] of the last successful check, 0 = never. */
    val lastCheckedEpoch: Long,
)

/** Real-time events emitted by [UpdateRepository.updateProgressFlow]. */
sealed interface UpdateProgress {
    val entryId: String

    data class Resolving(override val entryId: String) : UpdateProgress
    data class Downloading(
        override val entryId: String,
        val downloaded: Long,
        val totalBytes: Long,
    ) : UpdateProgress
    data class Installing(override val entryId: String) : UpdateProgress
    data class Done(override val entryId: String) : UpdateProgress
    data class Cancelled(override val entryId: String) : UpdateProgress
    data class Failed(override val entryId: String, val reason: String) : UpdateProgress
}
