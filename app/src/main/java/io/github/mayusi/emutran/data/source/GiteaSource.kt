package io.github.mayusi.emutran.data.source

import io.github.mayusi.emutran.data.manifest.AppEntry
import io.github.mayusi.emutran.data.manifest.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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
 */
@Singleton
class GiteaSource @Inject constructor(
    private val client: OkHttpClient,
) : AppSource {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun resolve(entry: AppEntry): ResolveResult = withContext(Dispatchers.IO) {
        if (entry.source != SourceKind.GITEA) return@withContext ResolveResult.Unsupported

        val parsed = parseHostAndOwnerRepo(entry.sourceUrl)
            ?: return@withContext ResolveResult.Failed("Not a recognized Gitea URL: ${entry.sourceUrl}")

        val (host, ownerRepo) = parsed
        val endpoint = "https://$host/api/v1/repos/$ownerRepo/releases/latest"
        val request = Request.Builder().url(endpoint).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ResolveResult.Failed("Gitea ${response.code} on $ownerRepo")
                }
                val body = response.body?.string()
                    ?: return@withContext ResolveResult.Failed("Empty body from $ownerRepo")

                val release = json.decodeFromString<GiteaRelease>(body)
                val assetNames = release.assets.map { it.name }
                val picked = ApkAssetFilter.pick(assetNames, entry)
                    ?: return@withContext ResolveResult.Failed("No matching asset for $ownerRepo")
                val (pickedName, kind) = picked
                val asset = release.assets.first { it.name == pickedName }

                // FIX 1 (SHA-256 sidecar): look for a sibling asset named "<apkName>.sha256".
                // Same pattern as GitHubReleasesSource. Gitea API exposes the same asset list.
                val sidecarName = asset.name + ".sha256"
                val sha256Url = release.assets
                    .firstOrNull { it.name.equals(sidecarName, ignoreCase = true) }
                    ?.browserDownloadUrl

                // FIX 2 (path-traversal): sanitize the remote asset name.
                val safeFilename = ApkAssetFilter.sanitizeFilename(asset.name)

                ResolveResult.Found(
                    apkUrl = asset.browserDownloadUrl,
                    filename = safeFilename,
                    version = release.tagName ?: release.name ?: "unknown",
                    sizeBytes = asset.size,
                    kind = kind,
                    sha256Url = sha256Url,
                )
            }
        } catch (t: Throwable) {
            ResolveResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Pulls "host" + "owner/repo" from a Gitea-style URL. */
    private fun parseHostAndOwnerRepo(url: String): Pair<String, String>? {
        val regex = Regex("""https?://([^/]+)/([^/]+)/([^/?#.]+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(url) ?: return null
        val host = match.groupValues[1]
        val ownerRepo = "${match.groupValues[2]}/${match.groupValues[3]}"
        return host to ownerRepo
    }

    @Serializable
    private data class GiteaRelease(
        @kotlinx.serialization.SerialName("tag_name") val tagName: String? = null,
        val name: String? = null,
        val prerelease: Boolean = false,
        val draft: Boolean = false,
        val assets: List<GiteaAsset> = emptyList(),
    )

    @Serializable
    private data class GiteaAsset(
        val name: String,
        val size: Long = 0,
        @kotlinx.serialization.SerialName("browser_download_url") val browserDownloadUrl: String,
    )
}
