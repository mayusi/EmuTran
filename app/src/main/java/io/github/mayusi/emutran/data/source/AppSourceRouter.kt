package io.github.mayusi.emutran.data.source

import io.github.mayusi.emutran.data.manifest.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks the right [AppSource] for an [AppEntry] and calls into it.
 *
 * Everything outside `data/source/` talks to this. Adding a new source
 * type means writing one new implementation and adding it to the `when` inside
 * [ResilientResolver.dispatchToSource].
 *
 * FIX 1 (SHA-256 sidecar): This router also exposes [fetchSha256Sidecar],
 * which ProgressViewModel calls to resolve an expectedSha256 before handing
 * the URL to ApkDownloader. The sidecar fetch is a tiny text request
 * (64 bytes) using the same injected OkHttpClient.
 *
 * Sidecar contract:
 *   - If [ResolveResult.Found.sha256Url] is non-null, the sidecar IS expected
 *     to be present and the download MUST be verified. A fetch failure is
 *     surfaced as a download failure (no silent install).
 *   - If [ResolveResult.Found.sha256Url] is null (HTML-scrape sources,
 *     releases without sidecars), verification is skipped. This is acceptable
 *     because those paths depend on transport hardening (FIX 3) instead.
 *
 * Source-rot self-healing: [resolve] now delegates to [ResilientResolver]
 * which walks a fallback ladder (latest → older release) before returning
 * [ResolveResult.Failed]. Every caller benefits transparently; when a fallback
 * tier succeeded the returned [ResolveResult.Found.recoveredVia] is non-null
 * so callers can surface a subtle note to the user.
 */
@Singleton
class AppSourceRouter @Inject constructor(
    private val resilient: ResilientResolver,
    private val client: OkHttpClient,
) {
    /**
     * Resolve [entry] to a download URL, running the source-rot fallback
     * ladder via [ResilientResolver] before returning failure. All SHA-256
     * integrity contracts are preserved on every tier.
     */
    suspend fun resolve(entry: AppEntry): ResolveResult = resilient.resolve(entry)

    /**
     * FIX 1: Fetches the SHA-256 sidecar at [sha256Url] and returns the
     * lowercase 64-hex-char hash token.
     *
     * Handles both common sidecar formats:
     *   - Bare hex:         "abc123...def\n"
     *   - sha256sum format: "abc123...def  filename.apk\n"
     *
     * Returns:
     *   - The hash string on success.
     *   - null if the network fetch fails OR the response body contains no
     *     valid 64-hex token. Callers MUST treat null as a hard failure when
     *     a sidecar URL was expected (not as "skip verification").
     */
    suspend fun fetchSha256Sidecar(sha256Url: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(sha256Url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val text = response.body?.string() ?: return@withContext null
                    // Delegate parsing to the shared utility in GithubDtos.kt so
                    // the extraction logic is not duplicated with SelfUpdateRepository.
                    parseSha256SidecarBody(text)
                }
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "SHA-256 sidecar fetch failed for $sha256Url: ${t.message}")
                null
            }
        }

    private companion object {
        private const val TAG = "AppSourceRouter"
    }
}
