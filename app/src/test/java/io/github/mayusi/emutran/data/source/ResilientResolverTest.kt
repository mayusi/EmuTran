package io.github.mayusi.emutran.data.source

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.mayusi.emutran.data.manifest.AppEntry
import io.github.mayusi.emutran.data.manifest.SourceKind
import io.github.mayusi.emutran.data.manifest.SystemTag
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for [ResilientResolver] source-rot self-healing.
 *
 * The ladder:
 *   Tier 0 (primary)  — normal resolve via underlying source.
 *   Tier 1 (older release) — synthesised entry with fallbackToOlderReleases=true;
 *                            only attempted when the primary failure is recoverable
 *                            (404, asset-not-found) and NOT a network-down error.
 *
 * GitHub and Gitea sources are mocked; HtmlScrapeSource is mocked and not expected
 * to receive fallback calls (HTML_SCRAPE doesn't have a paginated release list).
 */
class ResilientResolverTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private val github    = mockk<GitHubReleasesSource>(relaxed = true)
    private val gitea     = mockk<GiteaSource>(relaxed = true)
    private val htmlScrape = mockk<HtmlScrapeSource>(relaxed = true)

    private val resolver = ResilientResolver(github, gitea, htmlScrape)

    /**
     * Minimal GitHub AppEntry with fallbackToOlderReleases=false (the typical state
     * before the resilient resolver kicks in).
     */
    private fun githubEntry(fallbackToOlderReleases: Boolean = false): AppEntry = AppEntry(
        id = "com.example.emu",
        name = "ExampleEmu",
        author = "example",
        about = "",
        sourceUrl = "https://github.com/example/emu",
        source = SourceKind.GITHUB,
        apkFilterRegEx = "",
        invertApkFilter = false,
        autoFilterByArch = false,
        includePrereleases = false,
        fallbackToOlderReleases = fallbackToOlderReleases,
        versionExtractionRegEx = "",
        filterReleaseTitlesRegEx = "",
        categories = emptyList(),
        trackOnly = false,
        system = SystemTag.OTHER,
        recommended = false,
    )

    private fun giteaEntry(): AppEntry = githubEntry().copy(
        sourceUrl = "https://codeberg.org/example/emu",
        source = SourceKind.GITEA,
    )

    private fun htmlEntry(): AppEntry = githubEntry().copy(
        sourceUrl = "https://buildbot.libretro.com/stable/",
        source = SourceKind.HTML_SCRAPE,
    )

    private fun found(version: String = "1.0.0", recoveredVia: RecoveryTier? = null) =
        ResolveResult.Found(
            apkUrl = "https://example.com/emu.apk",
            filename = "emu.apk",
            version = version,
            recoveredVia = recoveredVia,
        )

    // ── Tests: primary success path ───────────────────────────────────────────

    @Test
    fun `primary success returns Found with null recoveredVia`() = runTest {
        val entry = githubEntry()
        coEvery { github.resolve(entry) } returns found()

        val result = resolver.resolve(entry)

        assertThat(result).isInstanceOf(ResolveResult.Found::class.java)
        assertThat((result as ResolveResult.Found).recoveredVia).isNull()
    }

    // ── Tests: Tier 1 older-release fallback ─────────────────────────────────

    /**
     * Primary returns a 404-style failure → Tier 1 (older release) succeeds →
     * result is Found with recoveredVia = OLDER_RELEASE.
     */
    @Test
    fun `primary 404 failure falls back to older release and returns Found with OLDER_RELEASE`() = runTest {
        val entry = githubEntry()
        val primaryFail = ResolveResult.Failed("GitHub 404 on example/emu")
        val olderFound  = found(version = "0.9.9")

        // Primary call fails; broader-list call (entry with fallbackToOlderReleases=true) succeeds.
        coEvery { github.resolve(entry) } returns primaryFail
        coEvery { github.resolve(entry.copy(fallbackToOlderReleases = true)) } returns olderFound

        val result = resolver.resolve(entry)

        assertThat(result).isInstanceOf(ResolveResult.Found::class.java)
        val found = result as ResolveResult.Found
        assertThat(found.version).isEqualTo("0.9.9")
        assertThat(found.recoveredVia).isEqualTo(RecoveryTier.OLDER_RELEASE)
    }

    /**
     * "No matching asset across N releases" is also a recoverable failure → try older.
     */
    @Test
    fun `no-matching-asset failure falls back to older release`() = runTest {
        val entry = githubEntry()
        coEvery { github.resolve(entry) } returns
            ResolveResult.Failed("No matching asset across 1 release(s) for example/emu")
        coEvery { github.resolve(entry.copy(fallbackToOlderReleases = true)) } returns found("0.8.0")

        val result = resolver.resolve(entry)

        assertThat(result).isInstanceOf(ResolveResult.Found::class.java)
        assertThat((result as ResolveResult.Found).recoveredVia).isEqualTo(RecoveryTier.OLDER_RELEASE)
    }

    /**
     * Gitea entries follow the same ladder as GitHub.
     */
    @Test
    fun `gitea primary failure falls back to older release`() = runTest {
        val entry = giteaEntry()
        coEvery { gitea.resolve(entry) } returns ResolveResult.Failed("Gitea 404 on example/emu")
        coEvery { gitea.resolve(entry.copy(fallbackToOlderReleases = true)) } returns found("0.7.0")

        val result = resolver.resolve(entry)

        assertThat(result).isInstanceOf(ResolveResult.Found::class.java)
        assertThat((result as ResolveResult.Found).recoveredVia).isEqualTo(RecoveryTier.OLDER_RELEASE)
    }

    // ── Tests: network-down does NOT trigger fallback ─────────────────────────

    /**
     * "Unable to resolve host" is a connectivity failure — the fallback should NOT
     * be attempted (it would fail identically and waste time).
     */
    @Test
    fun `network-down error does NOT fall back and returns original Failed`() = runTest {
        val entry = githubEntry()
        val networkFail = ResolveResult.Failed("Unable to resolve host api.github.com")
        coEvery { github.resolve(entry) } returns networkFail

        val result = resolver.resolve(entry)

        assertThat(result).isInstanceOf(ResolveResult.Failed::class.java)
        assertThat((result as ResolveResult.Failed).reason).contains("Unable to resolve host")
        // Tier 1 must NOT have been called.
        coVerify(exactly = 1) { github.resolve(any()) }
    }

    @Test
    fun `timeout error does NOT fall back`() = runTest {
        val entry = githubEntry()
        coEvery { github.resolve(entry) } returns ResolveResult.Failed("timeout connecting to api.github.com")

        val result = resolver.resolve(entry)

        assertThat(result).isInstanceOf(ResolveResult.Failed::class.java)
        coVerify(exactly = 1) { github.resolve(any()) }
    }

    @Test
    fun `socket error does NOT fall back`() = runTest {
        val entry = githubEntry()
        coEvery { github.resolve(entry) } returns ResolveResult.Failed("Failed to connect: ETIMEDOUT")

        val result = resolver.resolve(entry)

        assertThat(result).isInstanceOf(ResolveResult.Failed::class.java)
        coVerify(exactly = 1) { github.resolve(any()) }
    }

    // ── Tests: all tiers fail → descriptive Failed ────────────────────────────

    /**
     * Primary fails with a recoverable error, Tier 1 also fails → the returned
     * Failed message mentions both failures.
     */
    @Test
    fun `all tiers fail returns Failed with both failure reasons`() = runTest {
        val entry = githubEntry()
        coEvery { github.resolve(entry) } returns
            ResolveResult.Failed("GitHub 404 on example/emu")
        coEvery { github.resolve(entry.copy(fallbackToOlderReleases = true)) } returns
            ResolveResult.Failed("No matching asset across 10 release(s) for example/emu")

        val result = resolver.resolve(entry)

        assertThat(result).isInstanceOf(ResolveResult.Failed::class.java)
        val reason = (result as ResolveResult.Failed).reason
        assertThat(reason).contains("GitHub 404")
        assertThat(reason).contains("older-release fallback also failed")
    }

    // ── Tests: HTML_SCRAPE does NOT get Tier 1 ───────────────────────────────

    /**
     * HTML_SCRAPE sources don't have a paginated release list — they are excluded
     * from the older-release fallback entirely.
     */
    @Test
    fun `HTML_SCRAPE failure is returned directly without fallback`() = runTest {
        val entry = htmlEntry()
        coEvery { htmlScrape.resolve(entry) } returns ResolveResult.Failed("Scrape failed")

        val result = resolver.resolve(entry)

        assertThat(result).isInstanceOf(ResolveResult.Failed::class.java)
        // htmlScrape.resolve must have been called exactly once; no second call with a modified entry.
        coVerify(exactly = 1) { htmlScrape.resolve(any()) }
    }

    // ── Tests: entry already has fallbackToOlderReleases = true ──────────────

    /**
     * When the entry already has fallbackToOlderReleases=true, the broader list
     * was already used on Tier 0. A second attempt with the same flag would be
     * redundant — the resolver must NOT try again.
     */
    @Test
    fun `entry with existing fallbackToOlderReleases does not attempt second broader-list call`() = runTest {
        val entry = githubEntry(fallbackToOlderReleases = true)
        coEvery { github.resolve(entry) } returns
            ResolveResult.Failed("No matching asset across 10 release(s) for example/emu")

        val result = resolver.resolve(entry)

        assertThat(result).isInstanceOf(ResolveResult.Failed::class.java)
        // Only one resolve call — no duplicated attempt.
        coVerify(exactly = 1) { github.resolve(any()) }
    }

    // ── Tests: isRecoverableFailure classification ────────────────────────────

    @Test
    fun `isRecoverableFailure returns false for all network patterns`() {
        val networkErrors = listOf(
            "Unable to resolve host api.github.com",
            "Failed to connect: ECONNREFUSED",
            "Connection reset by peer",
            "Connection refused: 443",
            "timeout connecting to host",
            "Read timed out",
            "ENETUNREACH",
            "ETIMEDOUT",
            "ECONNRESET",
            "SSL handshake failed",
            "Certificate verification failed",
            "Socket closed unexpectedly",
        )
        for (msg in networkErrors) {
            assertWithMessage("should NOT be recoverable: $msg")
                .that(ResilientResolver.isRecoverableFailure(msg))
                .isFalse()
        }
    }

    @Test
    fun `isRecoverableFailure returns true for source-side failures`() {
        val sourceErrors = listOf(
            "GitHub 404 on example/emu",
            "No matching asset across 1 release(s) for example/emu",
            "Empty body from example/emu",
            "Gitea 404 on codeberg.org/example/emu",
            "No matching asset across 10 release(s) for codeberg.org/example/emu",
            "JSON parse error",
        )
        for (msg in sourceErrors) {
            assertWithMessage("should be recoverable: $msg")
                .that(ResilientResolver.isRecoverableFailure(msg))
                .isTrue()
        }
    }
}
