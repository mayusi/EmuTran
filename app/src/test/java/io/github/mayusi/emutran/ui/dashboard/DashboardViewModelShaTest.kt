package io.github.mayusi.emutran.ui.dashboard

import io.github.mayusi.emutran.data.manifest.AppEntry
import io.github.mayusi.emutran.data.source.AppSourceRouter
import io.github.mayusi.emutran.data.source.ResolveResult
import io.github.mayusi.emutran.domain.download.ApkDownloader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the SHA-256 sidecar gate added to
 * [DashboardViewModel.downloadAndInstall] (FIX 1, security).
 *
 * Before the fix, the dashboard install path (AvailableCard "Install" + the
 * featured EmuHelper card) called [ApkDownloader.download] with NO expectedSha256
 * and never fetched the sidecar, so those installs ran with integrity
 * verification SILENTLY disabled — unlike the setup + update paths.
 *
 * The fix mirrors ProgressViewModel: when the resolved entry carries a sha256Url,
 * downloadAndInstall fetches the sidecar via [AppSourceRouter.fetchSha256Sidecar]
 * and:
 *   - if the fetch returns null → ABORT (never call download);
 *   - if the fetch returns a hash → pass that hash to download(expectedSha256).
 *
 * All 14 collaborators are MockK'd (relaxed) — no Android context needed. The
 * init block's flows resolve to harmless relaxed defaults; the assertions ride
 * on whether [ApkDownloader.download] was called and with which expectedSha256.
 *
 * The Main dispatcher is replaced with an [UnconfinedTestDispatcher] so the
 * viewModelScope.launch in downloadAndInstall runs eagerly and deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelShaTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val parser = mockk<io.github.mayusi.emutran.data.manifest.ObtainiumPackParser>(relaxed = true)
    private val installed = mockk<io.github.mayusi.emutran.data.device.InstalledAppsRegistry>(relaxed = true)
    private val selectedStore = mockk<io.github.mayusi.emutran.data.selection.SelectedAppsStore>(relaxed = true)
    private val router = mockk<AppSourceRouter>(relaxed = true)
    private val downloader = mockk<ApkDownloader>(relaxed = true)
    private val installer = mockk<io.github.mayusi.emutran.domain.install.InstallerRouter>(relaxed = true)
    private val storage = mockk<io.github.mayusi.emutran.data.storage.StorageRootStore>(relaxed = true)
    private val uninstaller = mockk<io.github.mayusi.emutran.domain.install.Uninstaller>(relaxed = true)
    private val setupOptions = mockk<io.github.mayusi.emutran.data.storage.SetupOptionsStore>(relaxed = true)
    private val updateRepository = mockk<io.github.mayusi.emutran.data.update.UpdateRepository>(relaxed = true)
    private val selfUpdateRepository = mockk<io.github.mayusi.emutran.data.update.SelfUpdateRepository>(relaxed = true)
    private val driverHintProvider = mockk<io.github.mayusi.emutran.domain.drivers.DriverHintProvider>(relaxed = true)
    private val gpuDetector = mockk<io.github.mayusi.emutran.data.device.GpuDetector>(relaxed = true)
    private val manifestDiffStore = mockk<io.github.mayusi.emutran.data.manifest.ManifestDiffStore>(relaxed = true)

    private fun viewModel() = DashboardViewModel(
        parser = parser,
        installed = installed,
        selectedStore = selectedStore,
        router = router,
        downloader = downloader,
        installer = installer,
        storage = storage,
        uninstaller = uninstaller,
        setupOptions = setupOptions,
        updateRepository = updateRepository,
        selfUpdateRepository = selfUpdateRepository,
        driverHintProvider = driverHintProvider,
        gpuDetector = gpuDetector,
        manifestDiffStore = manifestDiffStore,
    )

    /** A relaxed AppEntry whose only stubbed property is the id busy-tracking keys on. */
    private fun entry(): AppEntry = mockk(relaxed = true) {
        every { id } returns "test.app"
        every { name } returns "Test App"
    }

    private fun found(sha256Url: String?) = ResolveResult.Found(
        apkUrl = "https://example.com/app.apk",
        filename = "app.apk",
        version = "2.0.0",
        sha256Url = sha256Url,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // The DashboardViewModel init block eagerly collects several flows on the
        // (unconfined) viewModelScope. A relaxed mockk returns a child-mock for the
        // MutableSharedFlow *property* updateProgressFlow, whose collect() throws
        // KotlinNothingValueException. Stub it (and the other init-collected flows)
        // with real, never-emitting flows so construction is inert for these tests.
        every { updateRepository.updateProgressFlow } returns MutableSharedFlow()
        every { updateRepository.updateState() } returns emptyFlow()
        every { updateRepository.updateCount() } returns emptyFlow()
        every { manifestDiffStore.pendingDiff } returns emptyFlow()
        // downloadAndInstall() reads storage.rootPath.first() before downloading.
        // A relaxed mock returns a child-mock Flow whose first() never yields a real
        // value, which would abort downloadAndInstall before the download call we
        // assert on. Stub it with a real single-value flow + a concrete defaultPath.
        every { storage.rootPath } returns flowOf("/sdcard/Emulation")
        every { storage.defaultPath } returns "/sdcard/Emulation"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sidecar expected but fetch returns null - aborts without downloading`() = runTest {
        coEvery { router.resolve(any()) } returns found(sha256Url = "https://example.com/app.apk.sha256")
        coEvery { router.fetchSha256Sidecar(any()) } returns null

        viewModel().downloadAndInstall(entry())

        // The download MUST NOT have been attempted when the integrity check is unavailable.
        coVerify(exactly = 0) { downloader.download(any(), any(), any()) }
    }

    @Test
    fun `sidecar resolves - downloads with the fetched hash`() = runTest {
        val hash = "a".repeat(64)
        coEvery { router.resolve(any()) } returns found(sha256Url = "https://example.com/app.apk.sha256")
        coEvery { router.fetchSha256Sidecar(any()) } returns hash
        // download emits an empty flow — we only care that it was invoked with the hash.
        every { downloader.download(any(), any(), any()) } returns flowOf()

        val shaSlot = slot<String?>()
        viewModel().downloadAndInstall(entry())

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
        coEvery { router.resolve(any()) } returns found(sha256Url = null)
        every { downloader.download(any(), any(), any()) } returns flowOf()

        val shaSlot = slot<String?>()
        viewModel().downloadAndInstall(entry())

        // No sidecar URL ⇒ sidecar fetch is never attempted and download proceeds with null.
        coVerify(exactly = 0) { router.fetchSha256Sidecar(any()) }
        coVerify(exactly = 1) {
            downloader.download(any(), any(), captureNullable(shaSlot))
        }
        assert(shaSlot.captured == null) {
            "expected expectedSha256=null (unverified), was ${shaSlot.captured}"
        }
    }
}
