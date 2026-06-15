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
 * The logic is:
 *   - installedVersionCode < 0  → false  (not installed)
 *   - availableVersion == null  → false  (not yet checked)
 *   - strip 'v'/'V' prefix from both sides, then string inequality → true if different
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

    // ── Different version → true ──────────────────────────────────────────────

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
    fun `older available version - string inequality still returns true`() {
        // computeHasUpdate uses string inequality only, NOT semver ordering.
        // Even a "downgrade" tag shows as hasUpdate because the installed
        // versionName is different.
        val result = repo.computeHasUpdate(info(
            installedVersionName = "1.14.0",
            availableVersion     = "1.13.0",
        ))
        assertThat(result).isTrue()
    }

    @Test
    fun `date-style tags - different date string - hasUpdate true`() {
        val result = repo.computeHasUpdate(info(
            installedVersionName = "2024.12.01",
            availableVersion     = "2025.01.15",
        ))
        assertThat(result).isTrue()
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
    fun `retroarch tag style r3645 - different - hasUpdate true`() {
        val result = repo.computeHasUpdate(info(
            installedVersionName = "r3640",
            availableVersion     = "r3645",
        ))
        assertThat(result).isTrue()
    }

    @Test
    fun `V uppercase prefix stripped same as v lowercase`() {
        val result = repo.computeHasUpdate(info(
            installedVersionName = "V1.14.0",
            availableVersion     = "v1.14.0",
        ))
        assertThat(result).isFalse()
    }
}
