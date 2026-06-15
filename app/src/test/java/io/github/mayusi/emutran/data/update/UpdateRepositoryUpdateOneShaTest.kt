package io.github.mayusi.emutran.data.update

import app.cash.turbine.test
import io.github.mayusi.emutran.data.device.InstalledAppsRegistry
import io.github.mayusi.emutran.data.manifest.AppEntry
import io.github.mayusi.emutran.data.manifest.ObtainiumPackParser
import io.github.mayusi.emutran.data.source.AppSourceRouter
import io.github.mayusi.emutran.data.source.ResolveResult
import io.github.mayusi.emutran.data.storage.SetupOptionsStore
import io.github.mayusi.emutran.domain.download.ApkDownloader
import io.github.mayusi.emutran.domain.install.InstallResult
import io.github.mayusi.emutran.domain.install.InstallerRouter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for the SHA-256 sidecar gate added to [UpdateRepository.updateOne]
 * (FIX 1, security).
 *
 * Before the fix, updateOne passed [ResolveResult.Found.sha256] — which no source
 * ever populates (sources set sha256Url) — straight to [ApkDownloader.download], so
 * every update installed with integrity verification SILENTLY disabled.
 *
 * The fix mirrors the SETUP path (ProgressViewModel): when the resolved entry
 * carries a sha256Url, updateOne fetches the sidecar via
 * [AppSourceRouter.fetchSha256Sidecar] and:
 *   - if the fetch returns null → ABORT (emit Failed, never call download);
 *   - if the fetch returns a hash → pass that hash to download(expectedSha256).
 * When sha256Url is null, the download proceeds unverified (no sidecar published).
 *
 * All collaborators are MockK'd (relaxed) — no Android context needed. We stub
 * [ObtainiumPackParser.loadStandard] to a single entry whose id matches the
 * updateOne target, and [AppSourceRouter.resolve] to a Found with/without a
 * sidecar URL. Assertions are on whether [ApkDownloader.download] was called and
 * with which expectedSha256.
 */
class UpdateRepositoryUpdateOneShaTest {

    private val packParser = mockk<ObtainiumPackParser>(relaxed = true)
    private val sourceRouter = mockk<AppSourceRouter>(relaxed = true)
    private val downloader = mockk<ApkDownloader>(relaxed = true)
    private val installer = mockk<InstallerRouter>(relaxed = true)
    private val store = mockk<UpdateStateStore>(relaxed = true)
    private val setupOptions = mockk<SetupOptionsStore>(relaxed = true)
    private val installedAppsRegistry = mockk<InstalledAppsRegistry>(relaxed = true)

    private val repo = UpdateRepository(
        context = mockk(relaxed = true),
        packParser = packParser,
        sourceRouter = sourceRouter,
        downloader = downloader,
        installer = installer,
        store = store,
        setupOptions = setupOptions,
        installedAppsRegistry = installedAppsRegistry,
    )

    private val entryId = "test.app"

    /** A relaxed AppEntry whose only stubbed property is the id updateOne matches on. */
    private fun entry(): AppEntry = mockk(relaxed = true) {
        every { id } returns entryId
    }

    private fun found(sha256Url: String?) = ResolveResult.Found(
        apkUrl = "https://example.com/app.apk",
        filename = "app.apk",
        version = "2.0.0",
        sha256Url = sha256Url,
    )

    private fun stubManifest() {
        // loadManifestEntries() picks loadStandard() when isDualScreen is false.
        every { setupOptions.isDualScreen } returns flowOf(false)
        coEvery { packParser.loadStandard() } returns listOf(entry())
    }

    @Test
    fun `sidecar expected but fetch returns null - aborts without downloading`() = runTest {
        stubManifest()
        coEvery { sourceRouter.resolve(any()) } returns found(sha256Url = "https://example.com/app.apk.sha256")
        coEvery { sourceRouter.fetchSha256Sidecar(any()) } returns null

        // updateOne emits Resolving then (on the gate) Failed. Collect both via turbine.
        repo.updateProgressFlow.test {
            repo.updateOne(entryId)

            assert(awaitItem() is UpdateProgress.Resolving)
            val failed = awaitItem()
            assert(failed is UpdateProgress.Failed && failed.entryId == entryId) {
                "expected a Failed emission for $entryId, got $failed"
            }
            cancelAndIgnoreRemainingEvents()
        }

        // The download MUST NOT have been attempted when the integrity check is unavailable.
        coVerify(exactly = 0) { downloader.download(any(), any(), any()) }
    }

    @Test
    fun `sidecar resolves - downloads with the fetched hash`() = runTest {
        stubManifest()
        val hash = "a".repeat(64)
        coEvery { sourceRouter.resolve(any()) } returns found(sha256Url = "https://example.com/app.apk.sha256")
        coEvery { sourceRouter.fetchSha256Sidecar(any()) } returns hash
        // Make install succeed so updateOne completes cleanly; download emits no-op flow.
        every { downloader.download(any(), any(), any()) } returns flowOf()
        coEvery { installer.install(any()) } returns InstallResult.Installed

        val shaSlot = slot<String?>()
        repo.updateOne(entryId)

        // download was called exactly once, and the fetched hash was forwarded as expectedSha256.
        coVerify(exactly = 1) {
            downloader.download(any(), any(), captureNullable(shaSlot))
        }
        assert(shaSlot.captured == hash) {
            "expected expectedSha256=$hash, was ${shaSlot.captured}"
        }
    }

    @Test
    fun `no sidecar published - downloads unverified with null hash`() = runTest {
        stubManifest()
        coEvery { sourceRouter.resolve(any()) } returns found(sha256Url = null)
        every { downloader.download(any(), any(), any()) } returns flowOf()
        coEvery { installer.install(any()) } returns InstallResult.Installed

        val shaSlot = slot<String?>()
        repo.updateOne(entryId)

        // No sidecar URL ⇒ sidecar fetch is never attempted and download proceeds with null.
        coVerify(exactly = 0) { sourceRouter.fetchSha256Sidecar(any()) }
        coVerify(exactly = 1) {
            downloader.download(any(), any(), captureNullable(shaSlot))
        }
        assert(shaSlot.captured == null) {
            "expected expectedSha256=null (unverified), was ${shaSlot.captured}"
        }
    }
}
