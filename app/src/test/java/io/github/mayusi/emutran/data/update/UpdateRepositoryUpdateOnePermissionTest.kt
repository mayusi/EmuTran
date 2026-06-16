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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

/**
 * Unit test for the emulator install-permission gate added to
 * [UpdateRepository.updateOne].
 *
 * When [InstallerRouter.install] returns [InstallResult.NeedsPermission] (the OS
 * blocked the install because the per-app "Install unknown apps" grant is
 * missing), updateOne must emit [UpdateProgress.NeedsInstallPermission] — NOT
 * [UpdateProgress.Failed] — so the UI can deep-link the user to settings and let
 * them retry, mirroring the self-update path.
 *
 * All collaborators are MockK'd (relaxed). We stub [ObtainiumPackParser.loadStandard]
 * to a single entry matching the updateOne target, [AppSourceRouter.resolve] to a
 * Found without a sidecar (so the download proceeds straight to install), and the
 * downloader to emit a single Done so updateOne reaches the install phase.
 */
class UpdateRepositoryUpdateOnePermissionTest {

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

    private fun stubManifest() {
        every { setupOptions.isDualScreen } returns flowOf(false)
        coEvery { packParser.loadStandard() } returns listOf(entry())
    }

    @Test
    fun `installer returns NeedsPermission - emits NeedsInstallPermission not Failed`() = runTest {
        stubManifest()
        // No sidecar ⇒ download proceeds unverified straight to the install phase.
        coEvery { sourceRouter.resolve(any()) } returns ResolveResult.Found(
            apkUrl = "https://example.com/app.apk",
            filename = "app.apk",
            version = "2.0.0",
            sha256Url = null,
        )
        // Emit a single Done so updateOne reaches the install hand-off.
        every { downloader.download(any(), any(), any()) } returns
            flowOf(ApkDownloader.Progress.Done(File("app.apk")))
        coEvery { installer.install(any()) } returns InstallResult.NeedsPermission

        repo.updateProgressFlow.test {
            repo.updateOne(entryId)

            // Resolving → Installing → NeedsInstallPermission (no Failed).
            assert(awaitItem() is UpdateProgress.Resolving)
            assert(awaitItem() is UpdateProgress.Installing)
            val terminal = awaitItem()
            assert(terminal is UpdateProgress.NeedsInstallPermission && terminal.entryId == entryId) {
                "expected NeedsInstallPermission for $entryId, got $terminal"
            }
            cancelAndIgnoreRemainingEvents()
        }
    }
}
