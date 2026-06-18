package io.github.mayusi.emutran.data.update

import android.content.ActivityNotFoundException
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.emutran.BuildConfig
import io.github.mayusi.emutran.data.source.GhRelease
import io.github.mayusi.emutran.data.source.HttpCache
import io.github.mayusi.emutran.data.source.parseSha256SidecarBody
import io.github.mayusi.emutran.domain.download.ApkDownloader
import io.github.mayusi.emutran.domain.install.IntentInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks for newer releases of EmuTran itself on GitHub and drives the
 * self-update download → install flow.
 *
 * == Why a separate repository from [UpdateRepository]? ==
 *
 * The self-update check has a different cadence (24h vs 6h), hits a
 * hard-coded endpoint rather than the manifest-driven source layer, and
 * uses the ACTION_VIEW system installer rather than [InstallerRouter]
 * (Shizuku cannot silently replace a running app cleanly — the system
 * installer is the correct UX here).
 *
 * == Version comparison ==
 *
 * GitHub release tag_name is assumed to be semver-ish (e.g. "v0.2.0").
 * We strip the leading 'v' and compare as [SemVer] tuples. If parsing
 * fails we fall back to string inequality so a changed tag still surfaces.
 *
 * == Rate limiting ==
 *
 * Check at most once per 24h. The [HttpCache] ETag mechanism makes any
 * 304 response free of quota; the 24h guard eliminates the round trip
 * entirely in the common steady-state. A forced call ([checkSelfUpdate]
 * from the About screen "Check for update" button) always hits the network.
 *
 * == SHA-256 sidecar verification ==
 *
 * If a release asset named "<apkName>.sha256" is present, its URL is stored
 * in [SelfUpdateResult.Available.sha256Url]. During [downloadAndInstall] the
 * sidecar is fetched, the 64-hex-char token extracted, and passed to
 * [ApkDownloader.download] as expectedSha256. On mismatch the downloader
 * emits [ApkDownloader.Progress.Failed] and deletes the file; we surface
 * that to the UI as a failure and do NOT launch the installer.
 */
@Singleton
class SelfUpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val httpCache: HttpCache,
    private val downloader: ApkDownloader,
    private val store: UpdateStateStore,
    private val json: Json,
    private val intentInstaller: IntentInstaller,
) {

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Check whether a newer EmuTran release exists on GitHub.
     *
     * Respects a 24-hour check interval stored in [UpdateStateStore].
     * Pass [force] = true (e.g. "Check now" tap) to bypass the gate.
     *
     * Returns one of:
     *   [SelfUpdateResult.UpToDate]   — running latest.
     *   [SelfUpdateResult.Available]  — a newer version exists (details inside).
     *   [SelfUpdateResult.Failed]     — network or parse error; message inside.
     *
     * Note: [force] = true always returns the raw network result regardless of
     * any skip-this-version preference. Use [bannerState] for launch-time checks
     * that should respect the user's "skip" preference.
     */
    suspend fun checkSelfUpdate(force: Boolean = false): SelfUpdateResult =
        withContext(Dispatchers.IO) {
            // 24-hour rate-limit gate (skipped when force=true).
            if (!force) {
                val lastCheck = store.selfCheckLastEpoch.first()
                val ageMs = System.currentTimeMillis() - lastCheck
                if (ageMs < SELF_CHECK_INTERVAL_MS) {
                    // Return what we know from the most recent persisted check.
                    return@withContext cachedSelfUpdateResult()
                }
            }

            val cached = httpCache.get(RELEASES_LATEST_URL, HttpCache.DEFAULT_TTL_MS)
            val request = Request.Builder()
                .url(RELEASES_LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .apply { cached?.let { header("If-None-Match", it.etag) } }
                .build()

            return@withContext try {
                client.newCall(request).execute().use { response ->
                    when {
                        // 304: nothing changed since our last fetch.
                        response.code == 304 && cached != null -> {
                            store.setSelfCheckEpoch(System.currentTimeMillis())
                            parseRelease(cached.body)
                        }
                        response.isSuccessful -> {
                            val body = response.body?.string()
                                ?: return@use SelfUpdateResult.Failed("Empty body from releases API")
                            response.header("ETag")?.let { etag ->
                                httpCache.put(
                                    RELEASES_LATEST_URL,
                                    HttpCache.Entry(etag, body, System.currentTimeMillis())
                                )
                            }
                            store.setSelfCheckEpoch(System.currentTimeMillis())
                            parseRelease(body)
                        }
                        else ->
                            SelfUpdateResult.Failed("GitHub ${response.code} on releases/latest")
                    }
                }
            } catch (t: Throwable) {
                SelfUpdateResult.Failed(t.message ?: t.javaClass.simpleName)
            }
        }

    /**
     * Download the self-update APK from [apkUrl] and hand it to the system
     * installer via [IntentInstaller.install] (ACTION_VIEW + FileProvider).
     * The running app process is replaced by the installer session — Shizuku
     * silent install is intentionally not used here because silently replacing
     * the running host process is unreliable and can leave the device in a bad
     * state.
     *
     * Emits [SelfUpdateProgress] events so the UI can show a shared progress
     * sheet. The [ApkDownloader.Progress] events from the underlying download
     * are mapped to the matching [SelfUpdateProgress] variants.
     *
     * == Install-permission gate ==
     *
     * On Android 8+ the system installer silently no-ops if this app lacks the
     * per-app "Install unknown apps" grant. Before launching, we check
     * [IntentInstaller.canRequestInstalls]; if it returns false we emit
     * [SelfUpdateProgress.NeedsInstallPermission] (a distinct state) instead of
     * silently failing. The UI then deep-links the user to the settings page
     * via [openInstallPermissionSettings] and asks them to enable it and retry.
     * The downloaded APK is preserved so the retry resumes from cache.
     *
     * The [IntentInstaller.install] call is wrapped in try/catch: an
     * [ActivityNotFoundException] or [SecurityException] surfaces as
     * [SelfUpdateProgress.Failed] rather than crashing the flow.
     *
     * == SHA-256 sidecar verification ==
     *
     * If [sha256Url] is non-null the sidecar is fetched first (with a few
     * retries — see [fetchSidecarHash]) and the hex hash is passed to the
     * downloader. On checksum mismatch the downloader emits
     * [ApkDownloader.Progress.Failed] — the installer is NOT launched. When a
     * sidecar URL is present but ALL retries fail, we ABORT (a single transient
     * blip no longer kills the update, but a definitively-unreachable sidecar
     * still blocks the install — MITM protection).
     *
     * This flow is cancellation-cooperative: if the collecting coroutine is
     * cancelled before [ApkDownloader.Progress.Done] is received, the install
     * hand-off is never reached. The caller (AboutViewModel) cancels this
     * coroutine from [dismissSheet] so mid-download dismissal is safe.
     *
     * After the installer is launched, [UpdateStateStore.setSelfCheckEpoch]
     * is reset to 0 so the 24h gate doesn't suppress the next check after
     * the update is applied.
     */
    fun downloadAndInstall(apkUrl: String, sha256Url: String? = null): Flow<SelfUpdateProgress> = flow {
        val filename = apkUrl.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: "emutran-update.apk"

        // Install-permission gate: bail early — before downloading —
        // if the OS won't let us install. Emitting a distinct state lets the UI
        // deep-link the user to the "Install unknown apps" settings page instead
        // of pulling the APK and then silently no-op'ing the installer.
        if (!intentInstaller.canRequestInstalls()) {
            android.util.Log.w(TAG, "Install-unknown-apps permission not granted; prompting user")
            emit(SelfUpdateProgress.NeedsInstallPermission)
            return@flow
        }

        // Resolve expected SHA-256 from the sidecar file if a URL was provided.
        // The sidecar contains either just the 64-hex-char hash or a sha256sum
        // line of the form "<hash>  <filename>". We take the first 64-hex-char token.
        //
        // SECURITY (Fix 3): when a sidecar URL is present but unreachable, we ABORT.
        // Proceeding without verification when a sidecar was expected would allow a
        // targeted MITM that 404s the sidecar to bypass integrity checks. Only when
        // sha256Url is null (release publishes no sidecar) do we skip verification.
        //
        // Resilient fetch: fetchSidecarHash retries a few times with backoff, so
        // a single transient non-2xx blip no longer aborts the whole update.
        // Abort only happens after ALL retries genuinely fail.
        val expectedSha256: String? = if (sha256Url != null) {
            val hash = fetchSidecarHash(sha256Url)
            if (hash == null) {
                android.util.Log.e(
                    TAG,
                    "SHA-256 sidecar fetch failed for $sha256Url after $SIDECAR_FETCH_ATTEMPTS " +
                        "attempts; aborting update for safety"
                )
                emit(SelfUpdateProgress.Failed(
                    "SHA-256 sidecar unreachable after $SIDECAR_FETCH_ATTEMPTS attempts; " +
                        "update aborted for safety"
                ))
                return@flow
            }
            hash
        } else {
            // No sidecar URL for this release — integrity is unverified.
            android.util.Log.d(TAG, "No SHA-256 sidecar for $apkUrl; skipping integrity check")
            null
        }

        downloader.download(
            url = apkUrl,
            filename = filename,
            expectedSha256 = expectedSha256,
        ).collect { progress ->
            when (progress) {
                is ApkDownloader.Progress.Started ->
                    emit(SelfUpdateProgress.Started(progress.totalBytes))
                is ApkDownloader.Progress.Chunk ->
                    emit(SelfUpdateProgress.Chunk(progress.downloaded, progress.totalBytes))
                is ApkDownloader.Progress.Failed ->
                    // Checksum mismatch or network failure from the downloader —
                    // the installer is NOT launched (Done branch never reached).
                    emit(SelfUpdateProgress.Failed(progress.message))
                is ApkDownloader.Progress.Done -> {
                    // Invalidate the 24h gate so the next launch-time check is fresh
                    // and won't re-offer a version the user is currently installing.
                    store.setSelfCheckEpoch(0L)
                    // Wrap the install hand-off so a missing installer activity or
                    // a revoked permission surfaces as Failed instead of crashing
                    // the collecting coroutine.
                    try {
                        intentInstaller.install(progress.file)
                        emit(SelfUpdateProgress.Done)
                    } catch (e: ActivityNotFoundException) {
                        android.util.Log.e(TAG, "No installer activity for self-update", e)
                        emit(SelfUpdateProgress.Failed(
                            "Couldn't open the system installer on this device"
                        ))
                    } catch (e: SecurityException) {
                        android.util.Log.e(TAG, "Installer denied for self-update", e)
                        emit(SelfUpdateProgress.NeedsInstallPermission)
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Deep-link the user to the per-app "Install unknown apps" settings page so
     * they can grant the permission that [SelfUpdateProgress.NeedsInstallPermission]
     * told the UI was missing. After granting it the user re-taps "Update now"
     * and the download resumes from cache.
     */
    fun openInstallPermissionSettings() {
        intentInstaller.openManageUnknownAppsSettings()
    }

    /**
     * Returns the self-update status suitable for a launch-time dashboard banner.
     *
     * Runs [checkSelfUpdate] with force=false (24h-gated, cheap). If the
     * result is [SelfUpdateResult.Available] AND its version matches the
     * version the user previously chose to skip, returns [SelfUpdateResult.UpToDate]
     * so the banner is suppressed.
     *
     * Dashboard agent usage:
     * ```kotlin
     * when (val result = selfUpdateRepository.bannerState()) {
     *     is SelfUpdateResult.Available -> showBanner(result.version)
     *     is SelfUpdateResult.UpToDate  -> hideBanner()
     *     is SelfUpdateResult.Failed    -> hideBanner() // silent on background check
     * }
     * ```
     *
     * This method must NOT be called on the main thread (it is a suspend fun that
     * does I/O). Collect it inside a coroutine on Dispatchers.IO or use
     * [kotlinx.coroutines.withContext].
     */
    suspend fun bannerState(): SelfUpdateResult {
        val result = checkSelfUpdate(force = false)
        if (result is SelfUpdateResult.Available) {
            val dismissed = store.dismissedSelfUpdateVersion.first()
            if (dismissed != null && dismissed == result.version) {
                return SelfUpdateResult.UpToDate
            }
        }
        return result
    }

    /**
     * Persist [version] as the version the user wants to skip. The next call
     * to [bannerState] (24h-gated) will return [SelfUpdateResult.UpToDate] if
     * the available version matches [version].
     *
     * Pass null to clear any prior skip (e.g. triggered internally when a new
     * version supersedes the previously skipped one — though currently this is
     * not called automatically; the dismissal flows naturally when a newer
     * release appears with a different version string).
     *
     * Dashboard agent usage:
     * ```kotlin
     * selfUpdateRepository.skipVersion(available.version)
     * ```
     */
    suspend fun skipVersion(version: String) {
        store.setDismissedSelfUpdateVersion(version)
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * Re-construct the last [SelfUpdateResult] from the HttpCache without
     * hitting the network. Called when the 24h gate is active.
     *
     * The cache read is bounded by [SELF_CHECK_INTERVAL_MS] (the same 24h gate)
     * rather than an unbounded age: if the entry is older than that, the gate
     * itself would have re-checked the network, so a stale hit here means we
     * have no usable recent data. [HttpCache.get] returns null past that age,
     * and we surface [SelfUpdateResult.Failed] so the caller treats it as "no
     * cached data" instead of re-offering a months-old release as Available.
     */
    private suspend fun cachedSelfUpdateResult(): SelfUpdateResult {
        val cached = httpCache.get(RELEASES_LATEST_URL, SELF_CHECK_INTERVAL_MS)
            ?: return SelfUpdateResult.Failed("No recent cached release data")
        return parseRelease(cached.body)
    }

    /** Parse a raw GitHub releases/latest JSON body into a [SelfUpdateResult]. */
    private fun parseRelease(body: String): SelfUpdateResult {
        return try {
            val release = json.decodeFromString<GhRelease>(body)
            val tag = release.tagName ?: return SelfUpdateResult.Failed("Missing tag_name")

            // Skip drafts and pre-releases — releases/latest usually excludes
            // them, but this is cheap belt-and-suspenders that also documents
            // intent for anyone reading this code.
            if (release.draft || release.prerelease) return SelfUpdateResult.UpToDate

            val currentSemver = SemVer.parse(BuildConfig.VERSION_NAME)
            val latestSemver = SemVer.parse(tag.trimStart('v', 'V'))

            if (latestSemver <= currentSemver) {
                return SelfUpdateResult.UpToDate
            }

            // Find the APK asset (prefer arm64 if multiple APKs exist).
            val apkAsset = release.assets
                .filter { it.name.endsWith(".apk", ignoreCase = true) }
                .minByOrNull { if ("arm64" in it.name.lowercase()) 0 else 1 }
                ?: return SelfUpdateResult.Failed("No APK asset in release $tag")

            // Look for a SHA-256 sidecar asset named "<apkName>.sha256".
            // The release process publishes it alongside the APK (e.g.
            // "EmuTran-arm64-v0.3.0.apk.sha256"). We store the URL here and
            // fetch + parse the actual hash at download time in downloadAndInstall.
            val sidecarName = apkAsset.name + ".sha256"
            val sha256Url = release.assets
                .firstOrNull { it.name.equals(sidecarName, ignoreCase = true) }
                ?.browserDownloadUrl

            SelfUpdateResult.Available(
                version = tag.trimStart('v', 'V'),
                changelogMarkdown = release.body ?: "",
                apkUrl = apkAsset.browserDownloadUrl,
                sha256Url = sha256Url,
            )
        } catch (t: Throwable) {
            SelfUpdateResult.Failed("Parse error: ${t.message}")
        }
    }

    /**
     * Fetches the sidecar .sha256 file at [url] and delegates body parsing to
     * [parseSha256SidecarBody] (shared with AppSourceRouter so parsing logic is
     * not duplicated). Returns the lowercase hash string, or null if the fetch
     * or parse definitively fails.
     *
     * Resilient fetch: the GET is retried up to [SIDECAR_FETCH_ATTEMPTS]
     * times with a short exponential backoff before giving up. This distinguishes
     * a single transient blip (a non-2xx / IO error on one attempt, which a retry
     * recovers from) from a sidecar that is genuinely unreachable (every attempt
     * fails → return null → caller ABORTS for MITM safety). The success path is
     * unchanged: as soon as a 2xx body parses to a hash we return it immediately.
     */
    private suspend fun fetchSidecarHash(url: String): String? {
        var lastReason = "unknown"
        for (attempt in 1..SIDECAR_FETCH_ATTEMPTS) {
            // One attempt: returns the parsed hash on success, or null on a
            // (possibly transient) failure with [lastReason] set for logging.
            val hash: String? = try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    when {
                        !response.isSuccessful -> {
                            lastReason = "HTTP ${response.code}"; null
                        }
                        else -> {
                            val text = response.body?.string()
                            if (text == null) {
                                lastReason = "empty body"; null
                            } else {
                                parseSha256SidecarBody(text)
                                    ?: run { lastReason = "unparseable body"; null }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                lastReason = t.message ?: t.javaClass.simpleName
                null
            }

            if (hash != null) return hash

            if (attempt < SIDECAR_FETCH_ATTEMPTS) {
                android.util.Log.w(
                    TAG,
                    "SHA-256 sidecar fetch attempt $attempt/$SIDECAR_FETCH_ATTEMPTS failed " +
                        "($lastReason); retrying"
                )
                delay(SIDECAR_RETRY_BASE_DELAY_MS * (1L shl (attempt - 1))) // 300ms, 600ms…
            }
        }
        android.util.Log.w(
            TAG,
            "SHA-256 sidecar fetch exhausted $SIDECAR_FETCH_ATTEMPTS attempts ($lastReason)"
        )
        return null
    }

    // GhRelease and GhAsset are defined in GithubDtos.kt (shared with
    // GitHubReleasesSource, GiteaSource, and DriverStager).

    companion object {
        /** EmuTran's own GitHub releases/latest endpoint. */
        private const val RELEASES_LATEST_URL =
            "https://api.github.com/repos/mayusi/EmuTran/releases/latest"

        /** Minimum gap between self-update checks: 24 hours. */
        const val SELF_CHECK_INTERVAL_MS: Long = 24 * 60 * 60 * 1_000L

        /**
         * Total attempts to fetch the SHA-256 sidecar before aborting.
         * One initial try + 2 retries. A single transient blip recovers; a
         * genuinely-unreachable sidecar still aborts the update (MITM safety).
         */
        const val SIDECAR_FETCH_ATTEMPTS: Int = 3

        /** Base backoff between sidecar fetch retries (doubles each attempt). */
        private const val SIDECAR_RETRY_BASE_DELAY_MS: Long = 300L

        private const val TAG = "SelfUpdateRepository"
    }
}

// ── Self-update install progress ──────────────────────────────────────────────

/**
 * Progress events for [SelfUpdateRepository.downloadAndInstall].
 *
 * Mirrors the relevant [ApkDownloader.Progress] variants ([Started], [Chunk],
 * [Done], [Failed]) but adds [NeedsInstallPermission] so the UI can
 * distinguish "the OS won't let us install yet — send the user to settings" from
 * a generic failure. UI agents switch on this to drive the self-update sheet.
 */
sealed interface SelfUpdateProgress {
    /** Download started; [totalBytes] is the content length (may be 0 if unknown). */
    data class Started(val totalBytes: Long) : SelfUpdateProgress

    /** A chunk landed; [downloaded] of [totalBytes] bytes written so far. */
    data class Chunk(val downloaded: Long, val totalBytes: Long) : SelfUpdateProgress

    /** APK downloaded (and checksum-verified if a sidecar was present) and the
     *  system installer was launched successfully. */
    data object Done : SelfUpdateProgress

    /**
     * The app lacks the per-app "Install unknown apps" grant (Android 8+),
     * so the installer would silently no-op. The UI should show a message and an
     * action that calls [SelfUpdateRepository.openInstallPermissionSettings], then
     * let the user retry once they've enabled it.
     */
    data object NeedsInstallPermission : SelfUpdateProgress

    /** Download, checksum, sidecar, or installer-launch failure. [message] is
     *  suitable for a Snackbar. */
    data class Failed(val message: String) : SelfUpdateProgress
}

// ── Public result types ─────────────────────────────────────────────────────

/**
 * Result of [SelfUpdateRepository.checkSelfUpdate]. UI agents switch on
 * this to decide whether to show the "What's new" bottom sheet.
 */
sealed interface SelfUpdateResult {
    /** Running the latest released version. */
    data object UpToDate : SelfUpdateResult

    /**
     * A newer version exists.
     *
     * @param version Cleaned version string (e.g. "0.3.0") — ready for display.
     * @param changelogMarkdown Raw GitHub release body (markdown). UI may render
     *   as plain text; it's typically short enough that that's fine.
     * @param apkUrl Direct download URL for the release APK.
     * @param sha256Url URL of the ".sha256" sidecar asset, or null if the release
     *   does not publish one. [SelfUpdateRepository.downloadAndInstall] fetches
     *   this automatically; callers do not need to resolve it themselves.
     */
    data class Available(
        val version: String,
        val changelogMarkdown: String,
        val apkUrl: String,
        val sha256Url: String?,
    ) : SelfUpdateResult

    /** Network/parse failure. [reason] is suitable for a Snackbar or log. */
    data class Failed(val reason: String) : SelfUpdateResult
}

// SemVer now lives in its own file (data/update/SemVer.kt) so it can be shared
// with UpdateRepository's emulator update check. Same package → no import needed.
