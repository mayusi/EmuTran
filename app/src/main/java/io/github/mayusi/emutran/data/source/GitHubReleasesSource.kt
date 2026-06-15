package io.github.mayusi.emutran.data.source

import io.github.mayusi.emutran.data.manifest.AppEntry
import io.github.mayusi.emutran.data.manifest.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves an [AppEntry] backed by github.com → an APK URL by hitting
 * api.github.com/repos/{owner}/{repo}/releases/latest, parsing the
 * `assets[]` array, and running [ApkAssetFilter] against the names.
 *
 * Why we DON'T use the redirect URL shortcut: a few entries in the pack
 * use unusual asset names that github.com/owner/repo/releases/latest
 * /download/{filename} doesn't know about. Going through the API
 * guarantees correctness at the cost of one extra request per app.
 *
 * Rate-limit story: unauthenticated GitHub allows 60 calls/hour.
 * v0.x just accepts the limit. A future iteration will:
 *   - Cache ETag and send If-None-Match (304s don't count).
 *   - Optionally accept a user-supplied PAT in Settings.
 */
@Singleton
class GitHubReleasesSource @Inject constructor(
    private val cache: HttpCache,
    private val client: OkHttpClient,
    private val json: Json,
) : AppSource {

    override suspend fun resolve(entry: AppEntry): ResolveResult = withContext(Dispatchers.IO) {
        if (entry.source != SourceKind.GITHUB) return@withContext ResolveResult.Unsupported

        val ownerRepo = parseOwnerRepo(entry.sourceUrl)
            ?: return@withContext ResolveResult.Failed("Not a github.com/owner/repo URL: ${entry.sourceUrl}")

        // /releases/latest excludes prereleases. If the entry wants
        // prereleases or fallback, we use the broader /releases endpoint
        // and pick the first match ourselves.
        val needsBroaderList = entry.includePrereleases || entry.fallbackToOlderReleases
        val endpoint = if (needsBroaderList) {
            "https://api.github.com/repos/$ownerRepo/releases?per_page=10"
        } else {
            "https://api.github.com/repos/$ownerRepo/releases/latest"
        }

        // Send If-None-Match if we've seen this URL before. GitHub 304s
        // don't count against the 60/hr rate limit, so the steady-state
        // cost of resolving many emulators repeatedly is near zero.
        val cached = cache.get(endpoint)
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply { cached?.let { header("If-None-Match", it.etag) } }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                // 304 Not Modified -> reuse cached body. Saves a rate-limit token.
                if (response.code == 304 && cached != null) {
                    return@withContext parseAndPick(cached.body, entry, ownerRepo, needsBroaderList)
                }
                if (!response.isSuccessful) {
                    return@withContext ResolveResult.Failed("GitHub ${response.code} on $ownerRepo")
                }
                val body = response.body?.string()
                    ?: return@withContext ResolveResult.Failed("Empty body from $ownerRepo")

                // Save the ETag if GitHub gave one — they always do.
                response.header("ETag")?.let { etag ->
                    cache.put(endpoint, HttpCache.Entry(etag, body, System.currentTimeMillis()))
                }

                parseAndPick(body, entry, ownerRepo, needsBroaderList)
            }
        } catch (t: Throwable) {
            ResolveResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Shared body parser used by both fresh and 304-served paths. */
    private fun parseAndPick(
        body: String,
        entry: AppEntry,
        ownerRepo: String,
        broaderList: Boolean,
    ): ResolveResult {
        val releases: List<GhRelease> = if (broaderList) {
            json.decodeFromString(body)
        } else {
            listOf(json.decodeFromString<GhRelease>(body))
        }

        for (release in releases) {
            if (!entry.includePrereleases && release.prerelease) continue
            val assetNames = release.assets.map { it.name }
            val picked = ApkAssetFilter.pick(assetNames, entry) ?: continue
            val (pickedName, kind) = picked
            val asset = release.assets.firstOrNull { it.name == pickedName }
                ?: continue

            // FIX 1 (SHA-256 sidecar): look for a sibling asset named "<apkName>.sha256"
            // in the same release. If present, store its URL so AppSourceRouter can
            // fetch + verify the hash at download time. If absent, sha256Url stays null
            // and the download proceeds without verification (acceptable for releases
            // that don't publish sidecars).
            val sidecarName = asset.name + ".sha256"
            val sha256Url = release.assets
                .firstOrNull { it.name.equals(sidecarName, ignoreCase = true) }
                ?.browserDownloadUrl

            // FIX 2 (path-traversal): sanitize the remote asset name before it
            // reaches File(cacheDir, filename) in ApkDownloader.
            val safeFilename = ApkAssetFilter.sanitizeFilename(asset.name)

            return ResolveResult.Found(
                apkUrl = asset.browserDownloadUrl,
                filename = safeFilename,
                version = release.tagName ?: release.name ?: "unknown",
                sizeBytes = asset.size,
                kind = kind,
                sha256Url = sha256Url,
            )
        }
        return ResolveResult.Failed(
            "No matching asset across ${releases.size} release(s) for $ownerRepo"
        )
    }

    /** Pulls "owner/repo" from a github URL, tolerating trailing slashes / .git. */
    private fun parseOwnerRepo(url: String): String? {
        val match = OWNER_REPO_REGEX.find(url) ?: return null
        return "${match.groupValues[1]}/${match.groupValues[2]}"
    }

    // GhRelease and GhAsset are defined in GithubDtos.kt (shared with
    // GiteaSource, DriverStager, and SelfUpdateRepository).

    companion object {
        /** "owner/repo" extractor — hoisted so it compiles once, not per resolve. */
        private val OWNER_REPO_REGEX =
            Regex("""github\.com/([^/]+)/([^/?#.]+)""", RegexOption.IGNORE_CASE)
    }
}
