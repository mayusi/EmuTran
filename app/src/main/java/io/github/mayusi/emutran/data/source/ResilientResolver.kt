package io.github.mayusi.emutran.data.source

import io.github.mayusi.emutran.data.manifest.AppEntry
import io.github.mayusi.emutran.data.manifest.SourceKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Source-rot self-healing: wraps [AppSourceRouter]'s per-source dispatch with a
 * fallback ladder so transient 404s and dead latest-releases don't surface
 * immediately as hard failures.
 *
 * Ladder (in order):
 *   Tier 0 (primary):     normal [AppSourceRouter.dispatchToSource] — latest release.
 *   Tier 1 (older release): re-resolve with a synthesised entry whose
 *                           [AppEntry.fallbackToOlderReleases] = true so the
 *                           broader /releases?per_page=10 list is walked.
 *                           Only attempted when the primary failure looks like a
 *                           missing/dead release (HTTP 404 or "No matching asset"),
 *                           NOT when the device is offline (network-down returns
 *                           ambiguous messages that should NOT trigger a fallback).
 *
 * The scope of fallback sources is intentionally narrow:
 *   - Only GITHUB and GITEA entries participate in Tier 1 (HTML_SCRAPE sources
 *     don't have a broader-list API; UNKNOWN is already a hard skip).
 *   - No arbitrary third-party host is ever tried — every fallback stays within
 *     the same trusted origin already declared in the manifest.
 *
 * SHA-256 sidecar integrity:
 *   Every tier that produces a [ResolveResult.Found] carries the same
 *   [ResolveResult.Found.sha256Url] field populated by the underlying source
 *   (GitHub/Gitea). [AppSourceRouter.fetchSha256Sidecar] is called by the
 *   download path (ProgressViewModel, UpdateRepository) unchanged — there is
 *   no special handling needed here.
 *
 * The result shape is unchanged from the primary path except that
 * [ResolveResult.Found.recoveredVia] is non-null when a fallback tier was used.
 * Callers that don't care about the recovery note continue to work unmodified.
 */
@Singleton
class ResilientResolver @Inject constructor(
    private val github: GitHubReleasesSource,
    private val gitea: GiteaSource,
    private val htmlScrape: HtmlScrapeSource,
) {

    /**
     * Resolve [entry] via the fallback ladder.
     *
     * Returns:
     *  - [ResolveResult.Found] (recoveredVia = null) on primary success.
     *  - [ResolveResult.Found] (recoveredVia = OLDER_RELEASE) when Tier 1 saved it.
     *  - [ResolveResult.Failed] when every tier failed (message describes all attempts).
     */
    suspend fun resolve(entry: AppEntry): ResolveResult {
        // ── Tier 0: primary (latest release) ─────────────────────────────────
        val primary = dispatchToSource(entry)
        if (primary is ResolveResult.Found) return primary   // common path — fast exit

        // Unsupported entries don't go further.
        if (primary is ResolveResult.Unsupported) return primary

        val primaryReason = (primary as ResolveResult.Failed).reason

        // ── Tier 1: older release ─────────────────────────────────────────────
        // Only try when:
        //   1. The source kind supports a broader release list (GitHub / Gitea).
        //   2. The failure looks like a dead/missing release, not a connectivity
        //      problem. "No matching asset" and HTTP 404 are safe to retry with an
        //      older release. Network-down errors ("Unable to resolve host",
        //      "timeout", "Connection refused", "ENETUNREACH", "ETIMEDOUT") are
        //      NOT: trying another release would immediately fail again and delay
        //      the pipeline. We detect connectivity errors conservatively by
        //      checking for the absence of the known source-origin patterns.
        if (entry.source in OLDER_RELEASE_CAPABLE_SOURCES &&
            !entry.fallbackToOlderReleases &&  // already has broader list — no point
            isRecoverableFailure(primaryReason)
        ) {
            // Synthesise a copy of the entry that asks for the broader list.
            // We do NOT permanently mutate the manifest entry — this is only for
            // this single resolve attempt.
            val broaderEntry = entry.copy(fallbackToOlderReleases = true)
            val tier1 = dispatchToSource(broaderEntry)
            if (tier1 is ResolveResult.Found) {
                // Tag the result so callers can surface a "used older release" note.
                return tier1.copy(recoveredVia = RecoveryTier.OLDER_RELEASE)
            }
            // Tier 1 also failed — fall through to the combined failure message.
            val tier1Reason = (tier1 as? ResolveResult.Failed)?.reason ?: "unsupported"
            return ResolveResult.Failed(
                "$primaryReason (older-release fallback also failed: $tier1Reason)"
            )
        }

        // No further tiers applicable — return the primary failure.
        return primary
    }

    /** Routes an entry to its concrete AppSource implementation. */
    private suspend fun dispatchToSource(entry: AppEntry): ResolveResult = when (entry.source) {
        SourceKind.GITHUB     -> github.resolve(entry)
        SourceKind.GITEA      -> gitea.resolve(entry)
        SourceKind.HTML_SCRAPE -> htmlScrape.resolve(entry)
        SourceKind.UNKNOWN    -> ResolveResult.Failed("Source not supported yet")
    }

    companion object {
        /** Source kinds that support a paginated older-release list. */
        private val OLDER_RELEASE_CAPABLE_SOURCES = setOf(SourceKind.GITHUB, SourceKind.GITEA)

        /**
         * Returns true when the failure reason indicates a dead/missing release
         * (safe to retry with an older release) rather than a connectivity problem
         * (pointless to retry immediately).
         *
         * Conservative: treat anything that smells like a network-layer error as
         * NOT recoverable. The patterns cover OkHttp and Android's socket errors.
         */
        internal fun isRecoverableFailure(reason: String): Boolean {
            val lower = reason.lowercase()
            // Network connectivity / DNS / TLS — not recoverable via older release.
            val networkPatterns = listOf(
                "unable to resolve host",
                "failed to connect",
                "connection refused",
                "connection reset",
                "timeout",
                "timed out",
                "enetunreach",
                "etimedout",
                "econnrefused",
                "econnreset",
                "socket",
                "ssl",
                "certificate",
                "handshake",
            )
            if (networkPatterns.any { lower.contains(it) }) return false
            // Anything else (404, "No matching asset", "Empty body", bad JSON, etc.)
            // is considered a source-side failure — worth trying an older release.
            return true
        }
    }
}
