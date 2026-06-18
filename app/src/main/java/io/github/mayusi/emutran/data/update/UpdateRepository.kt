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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * GitHub / Gitea API — e.g. "v1.14.0", "1.19.1-arm64-v8a", "unknown") while
 * PackageManager reports the installed [PackageInfo.versionName] (e.g.
 * "1.19.1", "5.0-21449"). These two namespaces rarely match character-for-
 * character, so a raw string compare badged nearly every installed emulator
 * permanently. Instead [computeHasUpdate] parses both sides into [SemVer]
 * (lenient to arch/build suffixes) and badges ONLY when the available version
 * is strictly greater. When either side is a date tag, "unknown", a git hash,
 * or otherwise unparseable, the result is treated as indeterminate and NO
 * badge is shown — a missed update is far better UX than a permanent false one.
 *
 * For the installed version we read [PackageInfo.longVersionCode] (and
 * [PackageInfo.versionName]) from PackageManager so we have something to show
 * in the UI; the SemVer compare runs against the latest string from the source.
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
 * [updateState] reflects the persisted DataStore state; real-time
 * download/install progress is a SEPARATE stream emitted via
 * [updateProgressFlow]. UI agents subscribe to both flows; no method call is
 * needed to start receiving.
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
    // Inject InstalledAppsRegistry so we use its cached PM snapshot instead of
    // doing N full scans per check cycle.
    private val installedAppsRegistry: InstalledAppsRegistry,
) {

    // ── Public: observable state ───────────────────────────────────────────

    /**
     * Map of entryId → [UpdateInfo] for every installed emulator that maps
     * to a known manifest entry. Emits whenever the persisted DataStore state
     * changes (a check completed, or an install re-baselined a version).
     * Real-time download/install progress is delivered separately via
     * [updateProgressFlow], not merged here.
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
     * All results are collected in-memory and written to DataStore in ONE
     * transaction at the end (via [UpdateStateStore.putAllUpdateInfo]),
     * producing 1 DataStore emission instead of N. This eliminates the
     * O(n²) re-decode of all entry blobs on every single write.
     *
     * Uses [InstalledAppsRegistry.snapshot] for the installed-package set, then
     * performs ONE additional PM query for versionCode/versionName on only the
     * matching packages — avoiding N full PM scans per check.
     */
    suspend fun checkNow(force: Boolean = false) = withContext(Dispatchers.IO) {
        val entries = loadManifestEntries()

        // Use the registry's cached Set<String> to find which entries are
        // installed, then do a single targeted PM query for versionCode/Name.
        val installedPackageNames = installedAppsRegistry.snapshot()
        val installedInfoMap = queryVersionInfoForPackages(installedPackageNames)

        // Only check entries that are actually installed and have a real package id.
        val candidates = entries.filter { entry ->
            entry.id in installedPackageNames && !entry.trackOnly
        }

        // Semaphore caps simultaneous in-flight API calls.
        val semaphore = Semaphore(MAX_CONCURRENT_RESOLVES)
        val nowMs = System.currentTimeMillis()

        // Collect all results in-memory first.
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

        // ONE DataStore transaction writes all entries — 1 emission, not N.
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

        // SHA-256 sidecar: integrity is driven solely by sha256Url. Mirror the
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
                // Refresh the registry then snapshot for just this package
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
                            // Re-baseline: re-stamp availableVersion to the tag we
                            // just installed. The PM versionName and the source tag live in
                            // different namespaces (e.g. "1.19.1" vs "1.19.1-arm64-v8a"), so
                            // leaving availableVersion at the old resolved tag could keep the
                            // badge lit even though we are now current. Setting both sides to
                            // the just-installed resolved version guarantees computeHasUpdate
                            // returns false until the NEXT check finds something genuinely newer.
                            availableVersion = resolved.version,
                        )
                    )
                }
                updateProgressFlow.emit(UpdateProgress.Done(entryId))
            }
            is InstallResult.Cancelled ->
                updateProgressFlow.emit(UpdateProgress.Cancelled(entryId))
            // Mirror the self-update path: a missing "Install unknown apps" grant
            // is NOT a hard failure — emit a distinct state so the UI can route the
            // user to settings and let them retry once it's enabled.
            is InstallResult.NeedsPermission ->
                updateProgressFlow.emit(UpdateProgress.NeedsInstallPermission(entryId))
            is InstallResult.Failed ->
                updateProgressFlow.emit(UpdateProgress.Failed(entryId, installResult.message))
        }
    }

    // Guard updateAll() against concurrent invocation.
    private val updateAllMutex = Mutex()

    /**
     * Update all entries that currently have [UpdateInfo.hasUpdate] = true.
     * Runs installs sequentially (PackageInstaller requires serial sessions).
     *
     * A Mutex prevents double-invocation (e.g. double-tap on "Update all").
     * If already running, the new call returns immediately.
     *
     * Returns an [UpdateAllResult] so the caller can give the user feedback
     * instead of silently no-op'ing: [UpdateAllResult.AlreadyRunning] when a
     * prior run still holds the lock, [UpdateAllResult.NothingToUpdate] when no
     * entry has a pending update, and [UpdateAllResult.Started] otherwise.
     */
    suspend fun updateAll(): UpdateAllResult {
        if (!updateAllMutex.tryLock()) return UpdateAllResult.AlreadyRunning  // already running — skip
        try {
            val pending = updateState().first()
                .values.filter { it.hasUpdate }.map { it.entryId }
            if (pending.isEmpty()) return UpdateAllResult.NothingToUpdate
            for (id in pending) {
                // Honour cancellation promptly between items: each updateOne can
                // sit on a ~90s install dialog, so a cancelled job must not march
                // on into the next install. Per-item progress is already streamed
                // live via updateProgressFlow, so the UI keeps up even though
                // updateAll() only returns once every item finishes.
                currentCoroutineContext().ensureActive()
                updateOne(id)
            }
            return UpdateAllResult.Started
        } finally {
            updateAllMutex.unlock()
        }
    }

    /**
     * Route the user to the per-app "Install unknown apps" settings page after a
     * [UpdateProgress.NeedsInstallPermission] emission. Passthrough to
     * [InstallerRouter.openInstallPermissionSettings] so the VM doesn't need to
     * inject [InstallerRouter] directly — it already holds this repository.
     */
    fun openInstallPermissionSettings() {
        installer.openInstallPermissionSettings()
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
     * Query the PM for only the packages that match the installed set.
     * Returns a Map<packageName, PackageInfo> with versionCode + name.
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
        } catch (t: Throwable) {
            // Silent-zero guard: a PM bulk-query failure here used to vanish
            // every badge silently (all versionCodes fall to -1). Log it so the
            // failure is at least diagnosable; we still return emptyMap because a
            // partial result isn't available and over-engineering a retry here is
            // out of scope.
            android.util.Log.w(TAG, "PM bulk query failed; update badges suppressed this cycle", t)
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
     * Returns true ONLY when we are confident a strictly-newer version is
     * available — a deliberately conservative bias.
     *
     * The old implementation compared the source's git TAG (e.g.
     * "1.19.1-arm64-v8a", "unknown", "2025-01-15", "v2120") against the
     * Android PM versionName (e.g. "1.19.1", "5.0-21449") with raw string
     * inequality. Those namespaces almost never match, so nearly every
     * installed emulator showed a permanent false "Update" badge that never
     * cleared. See the root-cause note in the repo header.
     *
     * New rule (like-for-like, confidence-biased):
     *   - Not installed (versionCode < 0) or not yet checked (available null)
     *     → false.
     *   - If EITHER side is a date-style tag, "unknown", a git hash, or
     *     otherwise has no leading numeric version run → indeterminate → false.
     *     A missed update is far better UX than a permanent false one.
     *   - If BOTH parse to a [SemVer] → true iff available is STRICTLY greater.
     *     Equal versions and downgrades → false (no badge).
     */
    internal fun computeHasUpdate(info: UpdateStateStore.PersistedUpdateInfo): Boolean {
        if (info.installedVersionCode < 0) return false       // not installed
        val available = info.availableVersion ?: return false  // not yet checked
        val installed = info.installedVersionName

        // Date-style tags ("2025.01.15", "2024-12-01") start with digits and
        // would otherwise parse to a numeric SemVer and mis-compare. Treat them
        // as indeterminate on either side → no badge.
        if (looksLikeDate(available) || looksLikeDate(installed)) return false

        val availableSemVer = SemVer.parseOrNull(available) ?: return false
        val installedSemVer = SemVer.parseOrNull(installed) ?: return false

        return availableSemVer > installedSemVer
    }

    /**
     * Heuristic: does this tag look like a calendar version rather than a
     * semantic version? Matches a 4-digit year followed by a month part (and an
     * optional day part) separated by '.' or '-' (e.g. "2024.12.01",
     * "2025-01-15"). A trailing build suffix is tolerated ("2024-12-01-build4")
     * so date-with-suffix tags are still treated as indeterminate rather than
     * slipping through to parse as a numeric SemVer and badge forever. Used to
     * keep date-style emulator tags out of the SemVer comparison so they never
     * produce a false update badge.
     */
    private fun looksLikeDate(raw: String): Boolean =
        DATE_TAG_REGEX.matches(raw.trim().trimStart('v', 'V'))

    companion object {
        /** Minimum age between automatic checks for a single entry: 6 hours. */
        const val CHECK_INTERVAL_MS: Long = 6 * 60 * 60 * 1_000L

        /**
         * Maximum simultaneous source API calls.
         */
        private const val MAX_CONCURRENT_RESOLVES = 4

        private const val TAG = "UpdateRepository"

        /**
         * Calendar-version tag: 4-digit year + a month part and an optional day
         * part separated by '.' or '-', with any trailing junk tolerated
         * (e.g. "2024.12.01", "2025-01-15", "2024-12-01-build4"). Anchored on a
         * 4-digit lead followed by a separator so it never matches "5.0-21449"
         * (Dolphin), "1.19.1", or a bare numeric tag like "2120"/"v2120".
         */
        private val DATE_TAG_REGEX =
            Regex("""^\d{4}[.\-]\d{1,2}([.\-]\d{1,2})?.*$""")
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

/**
 * Outcome of [UpdateRepository.updateAll] so the UI can surface a snackbar
 * instead of letting a no-op "Update all" tap look broken.
 */
enum class UpdateAllResult {
    /** A prior [UpdateRepository.updateAll] run still holds the lock. */
    AlreadyRunning,

    /** No installed entry currently has a pending update. */
    NothingToUpdate,

    /** Updates were dispatched (progress flows via [UpdateRepository.updateProgressFlow]). */
    Started,
}

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

    /**
     * DEFECT (emulator install-permission gate): the OS blocked the install
     * because the per-app "Install unknown apps" grant (Android 8+) is missing.
     * Distinct from [Failed] so the UI can deep-link the user to settings via
     * [UpdateRepository.openInstallPermissionSettings] and let them retry,
     * mirroring [SelfUpdateProgress.NeedsInstallPermission].
     */
    data class NeedsInstallPermission(override val entryId: String) : UpdateProgress
    data class Failed(override val entryId: String, val reason: String) : UpdateProgress
}
