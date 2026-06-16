package io.github.mayusi.emutran.data.update

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.emutran.data.device.InstalledAppsRegistry
import io.github.mayusi.emutran.data.manifest.ObtainiumPackParser
import io.github.mayusi.emutran.data.source.AppSourceRouter
import io.github.mayusi.emutran.domain.download.ApkDownloader
import io.github.mayusi.emutran.domain.install.InstallerRouter
import io.mockk.mockk
import org.junit.Test

/**
 * Unit tests for [UpdateRepository.computeHasUpdate].
 *
 * [computeHasUpdate] was bumped from private to internal to allow direct testing.
 *
 * The CONSERVATIVE, confidence-biased logic (see KDoc on computeHasUpdate):
 *   - installedVersionCode < 0  → false  (not installed)
 *   - availableVersion == null  → false  (not yet checked)
 *   - either side is a date-style tag, "unknown", a git hash, or otherwise has
 *     no leading numeric version run → false (indeterminate; never badge)
 *   - both parse to a SemVer → true iff available is STRICTLY greater
 *     (equal or downgrade → false)
 *
 * This replaces the old raw-string-inequality behavior, which compared the
 * source's git TAG against the PM versionName and produced a permanent false
 * "Update" badge on nearly every installed emulator.
 *
 * The repository is constructed with relaxed mocks for all injected deps since
 * computeHasUpdate() is a pure synchronous function that touches no deps.
 */
class UpdateRepositoryComputeHasUpdateTest {

    private val repo = UpdateRepository(
        context              = mockk(relaxed = true),
        packParser           = mockk<ObtainiumPackParser>(relaxed = true),
        sourceRouter         = mockk<AppSourceRouter>(relaxed = true),
        downloader           = mockk<ApkDownloader>(relaxed = true),
        installer            = mockk<InstallerRouter>(relaxed = true),
        store                = mockk<UpdateStateStore>(relaxed = true),
        setupOptions         = mockk(relaxed = true),
        installedAppsRegistry = mockk<InstalledAppsRegistry>(relaxed = true),
    )

    private fun info(
        installedVersionCode: Long = 1L,
        installedVersionName: String = "1.14.0",
        availableVersion: String? = "1.14.0",
    ) = UpdateStateStore.PersistedUpdateInfo(
        entryId              = "test.app",
        installedVersionCode = installedVersionCode,
        installedVersionName = installedVersionName,
        availableVersion     = availableVersion,
    )

    // ── Not installed → always false ──────────────────────────────────────────

    @Test
    fun `not installed - versionCode minus 1 - returns false`() {
        val result = repo.computeHasUpdate(info(installedVersionCode = -1L))
        assertThat(result).isFalse()
    }

    @Test
    fun `not installed - versionCode minus 2 - returns false`() {
        val result = repo.computeHasUpdate(info(installedVersionCode = -2L))
        assertThat(result).isFalse()
    }

    // ── No check performed yet → always false ─────────────────────────────────

    @Test
    fun `available version null - not yet checked - returns false`() {
        val result = repo.computeHasUpdate(info(availableVersion = null))
        assertThat(result).isFalse()
    }

    // ── Same version → false ──────────────────────────────────────────────────

    @Test
    fun `same version string - no update`() {
        val result = repo.computeHasUpdate(info(
            installedVersionName = "1.14.0",
            availableVersion     = "1.14.0",
        ))
        assertThat(result).isFalse()
    }

    @Test
    fun `v-prefix on available matches bare installed - no update`() {
        // "v1.14.0" stripped to "1.14.0" should equal installed "1.14.0"
        val result = repo.computeHasUpdate(info(
            installedVersionName = "1.14.0",
            availableVersion     = "v1.14.0",
        ))
        assertThat(result).isFalse()
    }

    @Test
    fun `v-prefix on installed matches bare available - no update`() {
        val result = repo.computeHasUpdate(info(
            installedVersionName = "v1.14.0",
            availableVersion     = "1.14.0",
        ))
        assertThat(result).isFalse()
    }

    @Test
    fun `v-prefix on both sides - same version - no update`() {
        val result = repo.computeHasUpdate(info(
            installedVersionName = "v1.14.0",
            availableVersion     = "v1.14.0",
        ))
        assertThat(result).isFalse()
    }

    @Test
    fun `V uppercase prefix stripped same as v lowercase`() {
        val result = repo.computeHasUpdate(info(
            installedVersionName = "V1.14.0",
            availableVersion     = "v1.14.0",
        ))
        assertThat(result).isFalse()
    }

    // ── Genuinely newer semver → true ─────────────────────────────────────────

    @Test
    fun `newer available version - hasUpdate true`() {
        val result = repo.computeHasUpdate(info(
            installedVersionName = "1.14.0",
            availableVersion     = "1.15.0",
        ))
        assertThat(result).isTrue()
    }

    @Test
    fun `newer available with v-prefix on available - hasUpdate true`() {
        val result = repo.computeHasUpdate(info(
            installedVersionName = "1.14.0",
            availableVersion     = "v1.15.0",
        ))
        assertThat(result).isTrue()
    }

    @Test
    fun `patch bump - 1 dot 17 dot 0 to 1 dot 17 dot 1 - hasUpdate true`() {
        val result = repo.computeHasUpdate(info(
            installedVersionName = "1.17.0",
            availableVersion     = "1.17.1",
        ))
        assertThat(result).isTrue()
    }

    @Test
    fun `numeric not lexical - 0 dot 9 to 0 dot 10 - hasUpdate true`() {
        // Lexical "9" > "10" would wrongly say no update. SemVer is numeric.
        val result = repo.computeHasUpdate(info(
            installedVersionName = "0.9.0",
            availableVersion     = "0.10.0",
        ))
        assertThat(result).isTrue()
    }

    // ── Downgrade / equal → false (conservative bias) ─────────────────────────

    @Test
    fun `older available version - downgrade - no update`() {
        // OLD behavior returned true on any string difference. New behavior
        // uses SemVer ordering: an older available tag is NOT an update.
        val result = repo.computeHasUpdate(info(
            installedVersionName = "1.14.0",
            availableVersion     = "1.13.0",
        ))
        assertThat(result).isFalse()
    }

    @Test
    fun `equal semver expressed differently - 1 dot 0 vs 1 dot 0 dot 0 - no update`() {
        val result = repo.computeHasUpdate(info(
            installedVersionName = "1.0.0",
            availableVersion     = "1.0",
        ))
        assertThat(result).isFalse()
    }

    // ── Date-style tags → indeterminate → false ───────────────────────────────

    @Test
    fun `date-style tags - different date string - no update`() {
        // OLD behavior badged on any date-string difference. A calendar tag is
        // indeterminate for our SemVer compare, so we never badge on it.
        val result = repo.computeHasUpdate(info(
            installedVersionName = "2024.12.01",
            availableVersion     = "2025.01.15",
        ))
        assertThat(result).isFalse()
    }

    @Test
    fun `date-style tags - same date string - no update`() {
        val result = repo.computeHasUpdate(info(
            installedVersionName = "2024.12.01",
            availableVersion     = "2024.12.01",
        ))
        assertThat(result).isFalse()
    }

    @Test
    fun `date-style tags - dash separated - no update`() {
        val result = repo.computeHasUpdate(info(
            installedVersionName = "2024-12-01",
            availableVersion     = "2025-01-15",
        ))
        assertThat(result).isFalse()
    }

    // ── Unparseable / unknown / hash → indeterminate → false ──────────────────

    @Test
    fun `available unknown - indeterminate - no update`() {
        // HtmlScrapeSource emits literal "unknown" for Dolphin / generic scrapes.
        val result = repo.computeHasUpdate(info(
            installedVersionName = "5.0-21449",
            availableVersion     = "unknown",
        ))
        assertThat(result).isFalse()
    }

    @Test
    fun `installed unknown - indeterminate - no update`() {
        val result = repo.computeHasUpdate(info(
            installedVersionName = "unknown",
            availableVersion     = "1.15.0",
        ))
        assertThat(result).isFalse()
    }

    @Test
    fun `git-hash style tag - indeterminate - no update`() {
        val result = repo.computeHasUpdate(info(
            installedVersionName = "1.14.0",
            availableVersion     = "g3ab12cf",
        ))
        assertThat(result).isFalse()
    }

    @Test
    fun `empty installed versionName - indeterminate - no update`() {
        val result = repo.computeHasUpdate(info(
            installedVersionName = "",
            availableVersion     = "1.15.0",
        ))
        assertThat(result).isFalse()
    }

    // ── Real-world emulator strings ───────────────────────────────────────────

    @Test
    fun `retroarch arch suffix - installed 1 dot 19 dot 1 vs available 1 dot 19 dot 1 arm64 - no update`() {
        // The arch suffix must be ignored: same version, just a build label.
        val result = repo.computeHasUpdate(info(
            installedVersionName = "1.19.1",
            availableVersion     = "1.19.1-arm64-v8a",
        ))
        assertThat(result).isFalse()
    }

    @Test
    fun `retroarch arch suffix - genuinely newer despite suffix - hasUpdate true`() {
        val result = repo.computeHasUpdate(info(
            installedVersionName = "1.19.1",
            availableVersion     = "1.20.0-arm64-v8a",
        ))
        assertThat(result).isTrue()
    }

    @Test
    fun `dolphin rev suffix - installed 5 dot 0 dash 21449 vs available unknown - no update`() {
        val result = repo.computeHasUpdate(info(
            installedVersionName = "5.0-21449",
            availableVersion     = "unknown",
        ))
        assertThat(result).isFalse()
    }

    @Test
    fun `numeric-only tag - installed 2120 vs available v2120 - no update`() {
        // Same numeric version, v-prefix on available only.
        val result = repo.computeHasUpdate(info(
            installedVersionName = "2120",
            availableVersion     = "v2120",
        ))
        assertThat(result).isFalse()
    }

    @Test
    fun `numeric-only tag - installed 2120 vs available 2121 - hasUpdate true`() {
        val result = repo.computeHasUpdate(info(
            installedVersionName = "2120",
            availableVersion     = "2121",
        ))
        assertThat(result).isTrue()
    }
}
