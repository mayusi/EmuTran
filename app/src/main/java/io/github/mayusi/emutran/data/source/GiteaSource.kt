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
 * Gitea-compatible (Codeberg, git.eden-emu.dev) release fetcher.
 *
 * Gitea's API mirrors GitHub closely — same `/repos/{owner}/{repo}/releases/latest`
 * endpoint and the same asset shape — so we can reuse most of the logic
 * with a different base URL.
 *
 * Note: Eden is the only currently-shipping entry that uses this source.
 * If/when more Gitea-hosted projects join the pack, the URL parsing here
 * may need to learn more hosts.
 *
 * ETag/HttpCache: mirrors GitHubReleasesSource — sends If-None-Match and
 * caches the response body so repeated resolves cost zero extra network
 * round-trips on 304 responses.
 */
@Singleton
class GiteaSource @Inject constructor(
    private val cache: HttpCache,
    private val client: OkHttpClient,
    private val json: Json,
) : AppSource {

    override suspend fun resolve(entry: AppEntry): ResolveResult = withContext(Dispatchers.IO) {
        if (entry.source != SourceKind.GITEA) return@withContext ResolveResult.Unsupported

        val parsed = parseHostAndOwnerRepo(entry.sourceUrl)
            ?: return@withContext ResolveResult.Failed("Not a recognized Gitea URL: ${entry.sourceUrl}")

        val (host, ownerRepo) = parsed
        val endpoint = "https://$host/api/v1/repos/$ownerRepo/releases/latest"

        // Send If-None-Match if we've seen this endpoint before.
        val cached = cache.get(endpoint)
        val request = Request.Builder()
            .url(endpoint)
            // Explicit browser-ish UA: git.eden-emu.dev has returned 403 to
            // OkHttp's default UA ("okhttp/4.x"). A recognisable UA prevents
            // the Gitea host's bot-filter from blocking the API request.
            .header("User-Agent", GITEA_UA)
            .apply { cached?.let { header("If-None-Match", it.etag) } }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                // 304 Not Modified → reuse cached body.
                if (response.code == 304 && cached != null) {
                    return@withContext parseAndPick(cached.body, entry, ownerRepo)
                }
                if (!response.isSuccessful) {
                    return@withContext ResolveResult.Failed("Gitea ${response.code} on $ownerRepo")
                }
                val body = response.body?.string()
                    ?: return@withContext ResolveResult.Failed("Empty body from $ownerRepo")

                // Cache the ETag for future If-None-Match requests.
                response.header("ETag")?.let { etag ->
                    cache.put(endpoint, HttpCache.Entry(etag, body, System.currentTimeMillis()))
                }

                parseAndPick(body, entry, ownerRepo)
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
    ): ResolveResult {
        val release = json.decodeFromString<GhRelease>(body)
        val assetNames = release.assets.map { it.name }
        val picked = ApkAssetFilter.pick(assetNames, entry)
            ?: return ResolveResult.Failed("No matching asset for $ownerRepo")
        val (pickedName, kind) = picked
        // Use firstOrNull to avoid NoSuchElementException on a malformed response.
        val asset = release.assets.firstOrNull { it.name == pickedName }
            ?: return ResolveResult.Failed("Asset '$pickedName' not found in response for $ownerRepo")

        // SHA-256 sidecar: look for a sibling asset named "<apkName>.sha256".
        val sidecarName = asset.name + ".sha256"
        val sha256Url = release.assets
            .firstOrNull { it.name.equals(sidecarName, ignoreCase = true) }
            ?.browserDownloadUrl

        // Sanitize remote asset name to guard against path traversal.
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

    /** Pulls "host" + "owner/repo" from a Gitea-style URL. */
    private fun parseHostAndOwnerRepo(url: String): Pair<String, String>? {
        val match = HOST_OWNER_REPO_REGEX.find(url) ?: return null
        val host = match.groupValues[1]
        val ownerRepo = "${match.groupValues[2]}/${match.groupValues[3]}"
        return host to ownerRepo
    }

    // GhRelease and GhAsset are defined in GithubDtos.kt (shared with
    // GitHubReleasesSource, DriverStager, and SelfUpdateRepository).

    companion object {
        /** "host" + "owner/repo" extractor — hoisted so it compiles once, not per resolve. */
        private val HOST_OWNER_REPO_REGEX =
            Regex("""https?://([^/]+)/([^/]+)/([^/?#.]+)""", RegexOption.IGNORE_CASE)

        /**
         * Browser-ish UA sent with every Gitea API request.
         *
         * OkHttp's default UA ("okhttp/4.x") has triggered 403 responses on
         * git.eden-emu.dev. Using a recognisable browser UA prevents Gitea
         * instances from classifying the request as a bot and blocking it.
         * This does not interfere with ETag/304 caching.
         */
        private const val GITEA_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
