package io.github.mayusi.emutran.data.source

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shared GitHub / Gitea release DTOs used by GitHubReleasesSource,
 * GiteaSource, DriverStager, and SelfUpdateRepository.
 *
 * The union of all fields needed across those consumers:
 *   - [GhRelease.body]       — used by SelfUpdateRepository for the changelog.
 *   - [GhRelease.prerelease] / [GhRelease.draft] — used by all three callers.
 *   - [GhAsset.size]         — used by GitHubReleasesSource / GiteaSource for the
 *                              download size shown in the UI.
 *
 * DriverStager previously needed its own types to be `internal`; it now
 * uses these (which are package-private to data/source) and its own
 * selection logic remains internal.
 */
@Serializable
data class GhRelease(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val body: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
data class GhAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

// ── Shared sidecar-hash parsing ───────────────────────────────────────────────

/**
 * Parses the text body of a ".sha256" sidecar file and returns the
 * lowercase 64-hex-character SHA-256 hash token, or null if none is found.
 *
 * Handles both common sidecar formats:
 *   - Bare hex:          "abc123…def"
 *   - sha256sum format:  "abc123…def  filename.apk"
 *
 * This pure function is shared between [AppSourceRouter.fetchSha256Sidecar]
 * and [SelfUpdateRepository.fetchSidecarHash] so the parsing logic is not
 * duplicated. Each call site handles its own network fetch.
 */
fun parseSha256SidecarBody(text: String): String? =
    text.trim()
        .splitToSequence(Regex("\\s+"))
        .firstOrNull { token ->
            token.length == 64 &&
                token.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }
        }
        ?.lowercase()
