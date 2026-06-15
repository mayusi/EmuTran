package io.github.mayusi.emutran.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.emutran.BuildConfig
import io.github.mayusi.emutran.data.source.GhAsset
import io.github.mayusi.emutran.data.source.GhRelease
import io.github.mayusi.emutran.data.source.HttpCache
import io.github.mayusi.emutran.data.source.parseSha256SidecarBody
import io.github.mayusi.emutran.domain.download.ApkDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
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
     * Download the self-update APK from [apkUrl] and launch the system
     * installer via ACTION_VIEW. The running app process is replaced by
     * the installer session — Shizuku silent install is intentionally not
     * used here because silently replacing the running host process is
     * unreliable and can leave the device in a bad state.
     *
     * Emits the same [ApkDownloader.Progress] events as the emulator
     * update flow so the UI can show a shared progress sheet.
     *
     * On [ApkDownloader.Progress.Done] the file is handed to the system
     * installer and the flow completes. The user will see the standard
     * "Install / Cancel" dialog.
     *
     * If [sha256Url] is non-null the sidecar is fetched first and the hex
     * hash is passed to the downloader. On checksum mismatch the downloader
     * emits [ApkDownloader.Progress.Failed] — the installer is NOT launched.
     *
     * This flow is cancellation-cooperative: if the collecting coroutine is
     * cancelled before [ApkDownloader.Progress.Done] is received, the
     * [launchInstaller] call is never reached. The caller (AboutViewModel)
     * cancels this coroutine from [dismissSheet] so mid-download dismissal
     * is safe.
     *
     * After the installer is launched, [UpdateStateStore.setSelfCheckEpoch]
     * is reset to 0 so the 24h gate doesn't suppress the next check after
     * the update is applied.
     */
    fun downloadAndInstall(apkUrl: String, sha256Url: String? = null): Flow<ApkDownloader.Progress> = flow {
        val filename = apkUrl.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: "emutran-update.apk"

        // Resolve expected SHA-256 from the sidecar file if a URL was provided.
        // The sidecar contains either just the 64-hex-char hash or a sha256sum
        // line of the form "<hash>  <filename>". We take the first 64-hex-char token.
        //
        // SECURITY (Fix 3): when a sidecar URL is present but unreachable, we ABORT.
        // Proceeding without verification when a sidecar was expected would allow a
        // targeted MITM that 404s the sidecar to bypass integrity checks. Only when
        // sha256Url is null (release publishes no sidecar) do we skip verification.
        val expectedSha256: String? = if (sha256Url != null) {
            val hash = fetchSidecarHash(sha256Url)
            if (hash == null) {
                android.util.Log.e(
                    TAG,
                    "SHA-256 sidecar fetch failed for $sha256Url; aborting update for safety"
                )
                emit(ApkDownloader.Progress.Failed(
                    "SHA-256 sidecar fetch failed; update aborted for safety"
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
            emit(progress)
            if (progress is ApkDownloader.Progress.Done) {
                // Invalidate the 24h gate so the next launch-time check is fresh
                // and won't re-offer a version the user is currently installing.
                store.setSelfCheckEpoch(0L)
                launchInstaller(progress.file)
            }
            // Progress.Failed is emitted by the downloader (checksum mismatch
            // or network failure) — we re-emit it above and do NOT launch the
            // installer because the Done branch is never reached.
        }
    }.flowOn(Dispatchers.IO)

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
     */
    private suspend fun cachedSelfUpdateResult(): SelfUpdateResult {
        val cached = httpCache.get(RELEASES_LATEST_URL, Long.MAX_VALUE) // any age is OK here
            ?: return SelfUpdateResult.Failed("No cached release data")
        return parseRelease(cached.body)
    }

    /** Parse a raw GitHub releases/latest JSON body into a [SelfUpdateResult]. */
    private fun parseRelease(body: String): SelfUpdateResult {
        return try {
            val release = json.decodeFromString<GhRelease>(body)
            val tag = release.tagName ?: return SelfUpdateResult.Failed("Missing tag_name")

            // FIX 3: Skip drafts and pre-releases — releases/latest usually
            // excludes them, but this is cheap belt-and-suspenders that also
            // documents intent for anyone reading this code.
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

            // FIX 2: Look for a SHA-256 sidecar asset named "<apkName>.sha256".
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
     * Fetches the sidecar .sha256 file at [url] and extracts the 64-hex-char
     * hash token. Handles both:
     *   - Bare hex:                    "abc123...def"
     *   - sha256sum format:            "abc123...def  filename.apk"
     *
     * Returns the lowercase hash string, or null if the fetch or parse fails.
     * Failures are non-fatal: the download continues without verification.
     */
    /**
     * Fetches the sidecar .sha256 file at [url] and delegates body parsing
     * to [parseSha256SidecarBody] (shared with AppSourceRouter so parsing
     * logic is not duplicated). Returns null if the fetch or parse fails.
     */
    private fun fetchSidecarHash(url: String): String? {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val text = response.body?.string() ?: return null
                parseSha256SidecarBody(text)
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Failed to fetch SHA-256 sidecar: ${t.message}")
            null
        }
    }

    /**
     * Launch the system installer via ACTION_VIEW for [apk].
     * Uses the shared FileProvider authority (apks/ cache path exposed in file_paths.xml).
     */
    private fun launchInstaller(apk: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(context, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        context.startActivity(intent)
    }

    // GhRelease and GhAsset are defined in GithubDtos.kt (shared with
    // GitHubReleasesSource, GiteaSource, and DriverStager).

    companion object {
        /** EmuTran's own GitHub releases/latest endpoint. */
        private const val RELEASES_LATEST_URL =
            "https://api.github.com/repos/mayusi/EmuTran/releases/latest"

        /** Minimum gap between self-update checks: 24 hours. */
        const val SELF_CHECK_INTERVAL_MS: Long = 24 * 60 * 60 * 1_000L

        private const val TAG = "SelfUpdateRepository"
    }
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

/**
 * Minimal semver parser: strips leading 'v', then splits on '.' and
 * compares major.minor.patch numerically. Non-numeric or missing components
 * default to 0. Sufficient for EmuTran's "v0.x.y" release tags.
 */
internal data class SemVer(val major: Int, val minor: Int, val patch: Int) :
    Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    companion object {
        fun parse(raw: String): SemVer {
            val clean = raw.trimStart('v', 'V')
            val parts = clean.split('.').mapNotNull { it.toIntOrNull() }
            return SemVer(
                major = parts.getOrElse(0) { 0 },
                minor = parts.getOrElse(1) { 0 },
                patch = parts.getOrElse(2) { 0 },
            )
        }
    }
}
