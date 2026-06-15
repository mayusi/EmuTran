package io.github.mayusi.emutran.domain.health

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.emutran.data.device.GpuDetector
import io.github.mayusi.emutran.data.device.GpuFamily
import io.github.mayusi.emutran.data.device.GpuInfo
import io.github.mayusi.emutran.data.device.InstalledAppsRegistry
import io.github.mayusi.emutran.data.device.NetworkChecker
import io.github.mayusi.emutran.data.manifest.ObtainiumPackParser
import io.github.mayusi.emutran.data.selection.SelectedAppsStore
import io.github.mayusi.emutran.data.setup.SetupStateDetector
import io.github.mayusi.emutran.data.source.AppSourceRouter
import io.github.mayusi.emutran.data.storage.SetupOptionsStore
import io.github.mayusi.emutran.data.storage.StorageRootStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [HealthChecker.runChecks].
 *
 * All four checks are exercised:
 *  1. checkEmulationTree  — pure File I/O; uses @TempDir for a real filesystem.
 *  2. checkBiosFolders    — File I/O; uses @TempDir.
 *  3. checkInstalledEmulators — mocked InstalledAppsRegistry + SelectedAppsStore.
 *  4. checkGpuDrivers     — mixed: SetupOptionsStore mock + @TempDir.
 *
 * All DataStore-backed stores ([StorageRootStore], [SelectedAppsStore],
 * [SetupOptionsStore]) are mocked with relaxed MockK instances so their
 * [Flow] properties can be stubbed without initialising an Android context.
 *
 * [GpuDetector] is mocked because the real implementation calls EGL14/GLES20
 * which is not available in the JVM unit test environment.
 *
 * [InstalledAppsRegistry] is mocked because [snapshot] queries PackageManager
 * which requires a real Android device/emulator.
 *
 * [ObtainiumPackParser] is mocked because it reads bundled assets which are
 * available at runtime but need a Context stub; we only need its return value.
 *
 * NetworkChecker is NOT a dependency of HealthChecker — nothing to skip there.
 */
class HealthCheckerTest {

    // JUnit4 temp-dir rule — folders are created fresh per test and cleaned up on teardown.
    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ── Shared mocks — each test overrides what it needs ─────────────────────

    private val storageRoot  = mockk<StorageRootStore>(relaxed = true)
    private val selectedApps = mockk<SelectedAppsStore>(relaxed = true)
    private val installedApps = mockk<InstalledAppsRegistry>(relaxed = true)
    private val setupOptions = mockk<SetupOptionsStore>(relaxed = true)
    private val manifestParser = mockk<ObtainiumPackParser>(relaxed = true)
    private val gpuDetector = mockk<GpuDetector>(relaxed = true)
    // NetworkChecker and AppSourceRouter mocks for the catalog-resolution check.
    // Default: offline so catalog check is SKIPPED (avoids real network calls
    // in unit tests). Tests that want to exercise catalog resolution can
    // override networkChecker to return true.
    private val networkChecker = mockk<NetworkChecker>(relaxed = true)
    private val sourceRouter = mockk<AppSourceRouter>(relaxed = true)
    // BiosValidator mock for the BIOS-file deep-scan check.
    // Default (relaxed): validate() returns a result with rootConfigured=false
    // and no systems, so checkBiosFiles is SKIPPED unless a test overrides it.
    private val biosValidator = mockk<BiosValidator>(relaxed = true)

    // SetupStateDetector is now injected into HealthChecker; checkEmulationTree
    // delegates its sentinel-presence decision to hasEmulationTree(). These tests
    // build REAL temp-dir trees, so we replicate the detector's real filesystem
    // logic (any of roms/bios/saves/tools present) against the actual path
    // argument. This keeps the existing PASS/WARN emulation-tree assertions valid
    // without per-test stubbing.
    private val setupStateDetector = mockk<SetupStateDetector>(relaxed = true) {
        every { hasEmulationTree(any()) } answers {
            val rootPath = firstArg<String>()
            val root = File(rootPath)
            val emulationDir =
                if (root.name.equals("Emulation", ignoreCase = true)) root
                else File(root, "Emulation")
            emulationDir.isDirectory &&
                listOf("roms", "bios", "saves", "tools").any { File(emulationDir, it).isDirectory }
        }
    }

    private fun checker() = HealthChecker(
        storageRoot       = storageRoot,
        selectedApps      = selectedApps,
        installedApps     = installedApps,
        setupOptions      = setupOptions,
        manifestParser    = manifestParser,
        gpuDetector       = gpuDetector,
        networkChecker    = networkChecker,
        sourceRouter      = sourceRouter,
        biosValidator     = biosValidator,
        setupStateDetector = setupStateDetector,
    )

    // ── Helper: stub null rootPath ────────────────────────────────────────────

    private fun stubNullRoot() {
        every { storageRoot.rootPath } returns flowOf(null)
    }

    private fun stubRoot(path: String) {
        every { storageRoot.rootPath } returns flowOf(path)
    }

    private fun stubGpuDriversOff() {
        every { setupOptions.stageGpuDrivers } returns flowOf(false)
    }

    private fun stubGpuDriversOn() {
        every { setupOptions.stageGpuDrivers } returns flowOf(true)
    }

    private fun stubSelectedApps(ids: Set<String> = emptySet()) {
        every { selectedApps.pickedIds } returns flowOf(ids)
    }

    private fun stubInstalledApps(packages: Set<String> = emptySet()) {
        coEvery { installedApps.refresh() } returns Unit
        every { installedApps.snapshot() } returns packages
    }

    private fun stubIsDualScreen(value: Boolean = false) {
        every { setupOptions.isDualScreen } returns flowOf(value)
    }

    /**
     * Stubs [NetworkChecker.isOnline] to return [online].
     * Default across all shared tests is offline (false) so the catalog
     * check is SKIPPED and no real network calls are made in unit tests.
     */
    private fun stubNetwork(online: Boolean = false) {
        every { networkChecker.isOnline() } returns online
    }

    /**
     * Stubs [BiosValidator.validate] to return [result]. Default is an
     * unconfigured-root result so [checkBiosFiles] is SKIPPED.
     */
    private fun stubBios(
        result: BiosValidationResult = BiosValidationResult(
            rootConfigured = false,
            systems = emptyList(),
        ),
    ) {
        coEvery { biosValidator.validate(any()) } returns result
    }

    // ── Check 1: checkEmulationTree ───────────────────────────────────────────

    @Test
    fun `emulation tree - null rootPath - FAIL`() = runTest {
        stubNullRoot()
        stubGpuDriversOff()
        stubSelectedApps()
        stubInstalledApps()
        stubIsDualScreen()

        val results = checker().runChecks()
        val tree = results.first { it.id == "emulation_tree" }
        assertThat(tree.status).isEqualTo(HealthStatus.FAIL)
        assertThat(tree.detail).ignoringCase().contains("no storage root")
    }

    @Test
    fun `emulation tree - Emulation dir does not exist - FAIL`() = runTest {
        // Point root at a temp dir but don't create Emulation/ inside it
        val root = tmpFolder.newFolder("storage")
        stubRoot(root.absolutePath)
        stubGpuDriversOff()
        stubSelectedApps()
        stubInstalledApps()
        stubIsDualScreen()

        val results = checker().runChecks()
        val tree = results.first { it.id == "emulation_tree" }
        assertThat(tree.status).isEqualTo(HealthStatus.FAIL)
    }

    @Test
    fun `emulation tree - Emulation dir exists but empty - WARN`() = runTest {
        val root = tmpFolder.newFolder("storage")
        File(root, "Emulation").mkdirs()
        stubRoot(root.absolutePath)
        stubGpuDriversOff()
        stubSelectedApps()
        stubInstalledApps()
        stubIsDualScreen()

        val results = checker().runChecks()
        val tree = results.first { it.id == "emulation_tree" }
        assertThat(tree.status).isEqualTo(HealthStatus.WARN)
    }

    @Test
    fun `emulation tree - Emulation slash roms exists - PASS`() = runTest {
        val root = tmpFolder.newFolder("storage")
        File(root, "Emulation/roms").mkdirs()
        stubRoot(root.absolutePath)
        stubGpuDriversOff()
        stubSelectedApps()
        stubInstalledApps()
        stubIsDualScreen()

        val results = checker().runChecks()
        val tree = results.first { it.id == "emulation_tree" }
        assertThat(tree.status).isEqualTo(HealthStatus.PASS)
    }

    @Test
    fun `emulation tree - rootPath already ends in Emulation - no double suffix`() = runTest {
        // Root stored as "/tmp/.../Emulation" directly
        val emulationDir = tmpFolder.newFolder("Emulation")
        File(emulationDir, "roms").mkdirs()
        stubRoot(emulationDir.absolutePath)
        stubGpuDriversOff()
        stubSelectedApps()
        stubInstalledApps()
        stubIsDualScreen()

        val results = checker().runChecks()
        val tree = results.first { it.id == "emulation_tree" }
        // Must find the roms folder at the right path (no double suffix)
        assertThat(tree.status).isEqualTo(HealthStatus.PASS)
        assertThat(tree.detail).doesNotContain("Emulation/Emulation")
    }

    // ── Check 2: checkBiosFolders ─────────────────────────────────────────────

    @Test
    fun `bios check - null rootPath - SKIPPED`() = runTest {
        stubNullRoot()
        stubGpuDriversOff()
        stubSelectedApps()
        stubInstalledApps()
        stubIsDualScreen()

        val results = checker().runChecks()
        val bios = results.first { it.id == "bios_folders" }
        assertThat(bios.status).isEqualTo(HealthStatus.SKIPPED)
    }

    @Test
    fun `bios check - bios dir missing - FAIL`() = runTest {
        val root = tmpFolder.newFolder("storage")
        File(root, "Emulation").mkdirs()  // Emulation/ exists but no bios/ inside
        stubRoot(root.absolutePath)
        stubGpuDriversOff()
        stubSelectedApps()
        stubInstalledApps()
        stubIsDualScreen()

        val results = checker().runChecks()
        val bios = results.first { it.id == "bios_folders" }
        assertThat(bios.status).isEqualTo(HealthStatus.FAIL)
    }

    @Test
    fun `bios check - bios dir exists but no system subdirs - WARN`() = runTest {
        val root = tmpFolder.newFolder("storage")
        File(root, "Emulation/bios").mkdirs()
        stubRoot(root.absolutePath)
        stubGpuDriversOff()
        stubSelectedApps()
        stubInstalledApps()
        stubIsDualScreen()

        val results = checker().runChecks()
        val bios = results.first { it.id == "bios_folders" }
        // 0 of N present → WARN
        assertThat(bios.status).isEqualTo(HealthStatus.WARN)
    }

    // ── Check 3: checkInstalledEmulators ─────────────────────────────────────

    @Test
    fun `installed emulators - no apps selected - WARN`() = runTest {
        val root = tmpFolder.newFolder("storage")
        stubRoot(root.absolutePath)
        stubGpuDriversOff()
        stubSelectedApps(emptySet())
        stubInstalledApps(emptySet())
        stubIsDualScreen()

        val results = checker().runChecks()
        val check = results.first { it.id == "installed_emulators" }
        assertThat(check.status).isEqualTo(HealthStatus.WARN)
        assertThat(check.detail).ignoringCase().contains("no emulators")
    }

    @Test
    fun `installed emulators - all selected apps are installed - PASS`() = runTest {
        val root = tmpFolder.newFolder("storage")
        stubRoot(root.absolutePath)
        stubGpuDriversOff()
        stubSelectedApps(setOf("com.example.emulator", "org.ppsspp.ppsspp"))
        stubInstalledApps(setOf("com.example.emulator", "org.ppsspp.ppsspp"))
        stubIsDualScreen()
        coEvery { manifestParser.loadStandard() } returns emptyList()

        val results = checker().runChecks()
        val check = results.first { it.id == "installed_emulators" }
        assertThat(check.status).isEqualTo(HealthStatus.PASS)
    }

    @Test
    fun `installed emulators - some selected apps not installed by package id - WARN`() = runTest {
        val root = tmpFolder.newFolder("storage")
        stubRoot(root.absolutePath)
        stubGpuDriversOff()
        stubSelectedApps(setOf("com.example.emulator", "org.ppsspp.ppsspp"))
        // Only one of the two is installed
        stubInstalledApps(setOf("com.example.emulator"))
        stubIsDualScreen()
        coEvery { manifestParser.loadStandard() } returns emptyList()

        val results = checker().runChecks()
        val check = results.first { it.id == "installed_emulators" }
        // Partial miss → WARN (not FAIL, because package-id mismatch is common for forks)
        assertThat(check.status).isEqualTo(HealthStatus.WARN)
    }

    @Test
    fun `installed emulators - all selected apps not found by package id - WARN`() = runTest {
        val root = tmpFolder.newFolder("storage")
        stubRoot(root.absolutePath)
        stubGpuDriversOff()
        stubSelectedApps(setOf("com.example.emulator"))
        // None found — common with forks that use different package ids
        stubInstalledApps(emptySet())
        stubIsDualScreen()
        coEvery { manifestParser.loadStandard() } returns emptyList()

        val results = checker().runChecks()
        val check = results.first { it.id == "installed_emulators" }
        assertThat(check.status).isEqualTo(HealthStatus.WARN)
    }

    // ── Check 4: checkGpuDrivers ──────────────────────────────────────────────

    @Test
    fun `gpu drivers - not opted in - SKIPPED`() = runTest {
        val root = tmpFolder.newFolder("storage")
        stubRoot(root.absolutePath)
        stubGpuDriversOff()
        stubSelectedApps()
        stubInstalledApps()
        stubIsDualScreen()

        val results = checker().runChecks()
        val check = results.first { it.id == "gpu_drivers" }
        assertThat(check.status).isEqualTo(HealthStatus.SKIPPED)
    }

    @Test
    fun `gpu drivers - opted in - turnip dir missing - FAIL`() = runTest {
        val root = tmpFolder.newFolder("storage")
        File(root, "Emulation/tools").mkdirs()  // tools/ exists but NOT turnip/
        stubRoot(root.absolutePath)
        stubGpuDriversOn()
        coEvery { gpuDetector.snapshot() } returns GpuInfo("Qualcomm", "Adreno (TM) 740", "OpenGL ES 3.2", GpuFamily.ADRENO, 740)
        stubSelectedApps()
        stubInstalledApps()
        stubIsDualScreen()

        val results = checker().runChecks()
        val check = results.first { it.id == "gpu_drivers" }
        assertThat(check.status).isEqualTo(HealthStatus.FAIL)
    }

    @Test
    fun `gpu drivers - opted in - turnip dir exists but no zips - FAIL`() = runTest {
        val root = tmpFolder.newFolder("storage")
        File(root, "Emulation/tools/turnip").mkdirs()
        stubRoot(root.absolutePath)
        stubGpuDriversOn()
        coEvery { gpuDetector.snapshot() } returns GpuInfo("Qualcomm", "Adreno (TM) 740", "OpenGL ES 3.2", GpuFamily.ADRENO, 740)
        stubSelectedApps()
        stubInstalledApps()
        stubIsDualScreen()

        val results = checker().runChecks()
        val check = results.first { it.id == "gpu_drivers" }
        assertThat(check.status).isEqualTo(HealthStatus.FAIL)
        assertThat(check.detail).ignoringCase().contains("no driver")
    }

    @Test
    fun `gpu drivers - opted in - zip present - PASS`() = runTest {
        val root = tmpFolder.newFolder("storage")
        val turnipDir = File(root, "Emulation/tools/turnip").also { it.mkdirs() }
        File(turnipDir, "turnip_driver.zip").createNewFile()
        stubRoot(root.absolutePath)
        stubGpuDriversOn()
        coEvery { gpuDetector.snapshot() } returns GpuInfo("Qualcomm", "Adreno (TM) 740", "OpenGL ES 3.2", GpuFamily.ADRENO, 740)
        stubSelectedApps()
        stubInstalledApps()
        stubIsDualScreen()

        val results = checker().runChecks()
        val check = results.first { it.id == "gpu_drivers" }
        assertThat(check.status).isEqualTo(HealthStatus.PASS)
        assertThat(check.detail).contains("1")
    }

    @Test
    fun `gpu drivers - opted in - non-Adreno GPU - WARN`() = runTest {
        val root = tmpFolder.newFolder("storage")
        stubRoot(root.absolutePath)
        stubGpuDriversOn()
        coEvery { gpuDetector.snapshot() } returns GpuInfo("ARM", "Mali-G77", "OpenGL ES 3.2", GpuFamily.MALI, null)
        stubSelectedApps()
        stubInstalledApps()
        stubIsDualScreen()

        val results = checker().runChecks()
        val check = results.first { it.id == "gpu_drivers" }
        assertThat(check.status).isEqualTo(HealthStatus.WARN)
        assertThat(check.detail).ignoringCase().contains("adreno")
    }

    @Test
    fun `gpu drivers - opted in - null rootPath - FAIL`() = runTest {
        stubNullRoot()
        stubGpuDriversOn()
        // GPU is Adreno so we pass the GPU check — but rootPath is null
        coEvery { gpuDetector.snapshot() } returns GpuInfo("Qualcomm", "Adreno (TM) 740", "OpenGL ES 3.2", GpuFamily.ADRENO, 740)
        stubSelectedApps()
        stubInstalledApps()
        stubIsDualScreen()

        val results = checker().runChecks()
        val check = results.first { it.id == "gpu_drivers" }
        assertThat(check.status).isEqualTo(HealthStatus.FAIL)
    }

    // ── runChecks result count and order ─────────────────────────────────────

    @Test
    fun `runChecks always returns exactly 6 check results`() = runTest {
        stubNullRoot()
        stubGpuDriversOff()
        stubSelectedApps()
        stubInstalledApps()
        stubIsDualScreen()
        stubBios()
        // NetworkChecker relaxed mock returns false → catalog check is SKIPPED.

        val results = checker().runChecks()
        assertThat(results).hasSize(6)
    }

    @Test
    fun `runChecks result IDs are stable and in expected order`() = runTest {
        stubNullRoot()
        stubGpuDriversOff()
        stubSelectedApps()
        stubInstalledApps()
        stubIsDualScreen()
        stubBios()
        // NetworkChecker relaxed mock returns false → catalog check is SKIPPED.

        val results = checker().runChecks()
        assertThat(results.map { it.id }).containsExactly(
            "emulation_tree",
            "bios_folders",
            "bios_files",
            "installed_emulators",
            "gpu_drivers",
            "catalog_resolution",
        ).inOrder()
    }

    // ── Check 3b: checkBiosFiles ─────────────────────────────────────────────

    @Test
    fun `bios files - null rootPath - SKIPPED`() = runTest {
        stubNullRoot()
        stubGpuDriversOff()
        stubSelectedApps()
        stubInstalledApps()
        stubIsDualScreen()
        stubBios()

        val results = checker().runChecks()
        val check = results.first { it.id == "bios_files" }
        assertThat(check.status).isEqualTo(HealthStatus.SKIPPED)
    }

    @Test
    fun `bios files - root configured but no system folders on disk - SKIPPED`() = runTest {
        val root = tmpFolder.newFolder("storage")
        stubRoot(root.absolutePath)
        stubGpuDriversOff()
        stubSelectedApps()
        stubInstalledApps()
        stubIsDualScreen()
        stubBios(
            BiosValidationResult(
                rootConfigured = true,
                systems = listOf(
                    BiosSystemStatus(
                        system = "ps1",
                        folderExists = false,
                        expectedCount = 1,
                        presentCount = 0,
                        missingFilenames = listOf("scph5501.bin"),
                        wrongHashFilenames = emptyList(),
                        files = listOf(BiosFileResult("scph5501.bin", BiosFileStatus.MISSING)),
                    ),
                ),
            ),
        )

        val results = checker().runChecks()
        val check = results.first { it.id == "bios_files" }
        assertThat(check.status).isEqualTo(HealthStatus.SKIPPED)
    }

    @Test
    fun `bios files - all expected present and hashes ok - PASS`() = runTest {
        val root = tmpFolder.newFolder("storage")
        stubRoot(root.absolutePath)
        stubGpuDriversOff()
        stubSelectedApps()
        stubInstalledApps()
        stubIsDualScreen()
        stubBios(
            BiosValidationResult(
                rootConfigured = true,
                systems = listOf(
                    BiosSystemStatus(
                        system = "ps1",
                        folderExists = true,
                        expectedCount = 1,
                        presentCount = 1,
                        missingFilenames = emptyList(),
                        wrongHashFilenames = emptyList(),
                        files = listOf(BiosFileResult("scph5501.bin", BiosFileStatus.OK)),
                    ),
                ),
            ),
        )

        val results = checker().runChecks()
        val check = results.first { it.id == "bios_files" }
        assertThat(check.status).isEqualTo(HealthStatus.PASS)
    }

    @Test
    fun `bios files - missing and wrong-hash files - WARN with names`() = runTest {
        val root = tmpFolder.newFolder("storage")
        stubRoot(root.absolutePath)
        stubGpuDriversOff()
        stubSelectedApps()
        stubInstalledApps()
        stubIsDualScreen()
        stubBios(
            BiosValidationResult(
                rootConfigured = true,
                systems = listOf(
                    BiosSystemStatus(
                        system = "ps1",
                        folderExists = true,
                        expectedCount = 2,
                        presentCount = 1,
                        missingFilenames = listOf("scph5501.bin"),
                        wrongHashFilenames = emptyList(),
                        files = listOf(
                            BiosFileResult("scph5501.bin", BiosFileStatus.MISSING),
                            BiosFileResult("scph1001.bin", BiosFileStatus.OK),
                        ),
                    ),
                    BiosSystemStatus(
                        system = "dc",
                        folderExists = true,
                        expectedCount = 1,
                        presentCount = 1,
                        missingFilenames = emptyList(),
                        wrongHashFilenames = listOf("dc_boot.bin"),
                        files = listOf(BiosFileResult("dc_boot.bin", BiosFileStatus.WRONG_HASH)),
                    ),
                ),
            ),
        )

        val results = checker().runChecks()
        val check = results.first { it.id == "bios_files" }
        assertThat(check.status).isEqualTo(HealthStatus.WARN)
        assertThat(check.detail).ignoringCase().contains("scph5501.bin")
        assertThat(check.detail).ignoringCase().contains("missing")
        assertThat(check.detail).ignoringCase().contains("wrong hash")
        assertThat(check.detail).contains("DC")
    }

    @Test
    fun `bios files - prose-only systems with empty expected list are ignored - SKIPPED`() = runTest {
        val root = tmpFolder.newFolder("storage")
        stubRoot(root.absolutePath)
        stubGpuDriversOff()
        stubSelectedApps()
        stubInstalledApps()
        stubIsDualScreen()
        stubBios(
            BiosValidationResult(
                rootConfigured = true,
                systems = listOf(
                    BiosSystemStatus(
                        system = "switch",
                        folderExists = true,
                        expectedCount = 0,
                        presentCount = 0,
                        missingFilenames = emptyList(),
                        wrongHashFilenames = emptyList(),
                        files = emptyList(),
                    ),
                ),
            ),
        )

        val results = checker().runChecks()
        val check = results.first { it.id == "bios_files" }
        assertThat(check.status).isEqualTo(HealthStatus.SKIPPED)
    }

    // ── Check 5: checkCatalogResolution ──────────────────────────────────────

    @Test
    fun `catalog resolution - offline - SKIPPED`() = runTest {
        stubNullRoot()
        stubGpuDriversOff()
        stubSelectedApps()
        stubInstalledApps()
        stubIsDualScreen()
        stubNetwork(online = false)

        val results = checker().runChecks()
        val check = results.first { it.id == "catalog_resolution" }
        assertThat(check.status).isEqualTo(HealthStatus.SKIPPED)
        assertThat(check.detail).ignoringCase().contains("network")
    }
}
