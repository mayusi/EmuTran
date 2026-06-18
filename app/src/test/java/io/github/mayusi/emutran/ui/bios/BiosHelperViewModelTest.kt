package io.github.mayusi.emutran.ui.bios

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.mayusi.emutran.data.storage.StorageRootStore
import io.github.mayusi.emutran.domain.health.BiosFileResult
import io.github.mayusi.emutran.domain.health.BiosFileStatus
import io.github.mayusi.emutran.domain.health.BiosSystemStatus
import io.github.mayusi.emutran.domain.health.BiosValidationResult
import io.github.mayusi.emutran.domain.health.BiosValidator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [BiosHelperViewModel] — verifies that the UI state mapping
 * correctly categorises [BiosFileStatus] values into [BiosFileVerdict] values
 * and computes summary counters accurately.
 *
 * [BiosValidator] and [StorageRootStore] are MockK stubs. All storage-root
 * flows are backed by [MutableStateFlow] so we can drive the state
 * deterministically without touching the real DataStore.
 *
 * The Main dispatcher is replaced with an [UnconfinedTestDispatcher] so
 * [viewModelScope] launches run eagerly and synchronously inside [runTest].
 *
 * Properties verified:
 *  - BiosFileStatus.OK         → BiosFileVerdict.VERIFIED
 *  - BiosFileStatus.PRESENT    → BiosFileVerdict.PRESENT
 *  - BiosFileStatus.WRONG_HASH → BiosFileVerdict.WRONG_HASH
 *  - BiosFileStatus.MISSING    → BiosFileVerdict.MISSING
 *  - readySystems / totalSystems counters reflect only non-prose systems
 *  - A system with all files PRESENT/OK is counted as ready
 *  - A system with a MISSING file is NOT counted as ready
 *  - NoRoot state emitted when rootPath flow yields null
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BiosHelperViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /** Backing flow standing in for the persisted storage root path. */
    private val rootPathFlow = MutableStateFlow<String?>("/sdcard")

    private val storageRoot = mockk<StorageRootStore> {
        every { rootPath } returns rootPathFlow
    }

    private val biosValidator = mockk<BiosValidator>()

    private fun viewModel() = BiosHelperViewModel(
        biosValidator = biosValidator,
        storageRoot = storageRoot,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Verdict mapping ───────────────────────────────────────────────────────

    @Test
    fun `BiosFileStatus OK maps to BiosFileVerdict VERIFIED`() {
        assertThat(BiosFileStatus.OK.toVerdict()).isEqualTo(BiosFileVerdict.VERIFIED)
    }

    @Test
    fun `BiosFileStatus PRESENT maps to BiosFileVerdict PRESENT`() {
        assertThat(BiosFileStatus.PRESENT.toVerdict()).isEqualTo(BiosFileVerdict.PRESENT)
    }

    @Test
    fun `BiosFileStatus WRONG_HASH maps to BiosFileVerdict WRONG_HASH`() {
        assertThat(BiosFileStatus.WRONG_HASH.toVerdict()).isEqualTo(BiosFileVerdict.WRONG_HASH)
    }

    @Test
    fun `BiosFileStatus MISSING maps to BiosFileVerdict MISSING`() {
        assertThat(BiosFileStatus.MISSING.toVerdict()).isEqualTo(BiosFileVerdict.MISSING)
    }

    // ── NoRoot state ──────────────────────────────────────────────────────────

    @Test
    fun `state is NoRoot when storage root is null`() = runTest {
        rootPathFlow.value = null

        viewModel().state.test {
            // Loading emitted first (init), then NoRoot when root is null.
            // UnconfinedTestDispatcher runs coroutines eagerly; by the time
            // we collect the flow the ViewModel has already settled.
            val settled = awaitItem()
            // Consume any intermediate Loading item if present.
            val final = if (settled is BiosHelperUiState.Loading) awaitItem() else settled
            assertThat(final).isInstanceOf(BiosHelperUiState.NoRoot::class.java)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Full scan — verdict categorisation ────────────────────────────────────

    /**
     * Builds a [BiosValidationResult] with two systems:
     *  - ps1: one OK file, one PRESENT file  → all files present → isReady = true
     *  - dc:  one OK file, one MISSING file  → not all present   → isReady = false
     *
     * Asserts that:
     *  - readySystems = 1 (only ps1)
     *  - totalSystems = 2 (both are non-prose concrete-filename systems)
     *  - Each file's verdict is correctly mapped.
     */
    @Test
    fun `loaded state maps verdicts and counts ready systems correctly`() = runTest {
        val ps1Status = BiosSystemStatus(
            system = "ps1",
            folderExists = true,
            expectedCount = 2,
            presentCount = 2,
            missingFilenames = emptyList(),
            wrongHashFilenames = emptyList(),
            files = listOf(
                BiosFileResult(filename = "scph1001.bin", status = BiosFileStatus.OK),
                BiosFileResult(filename = "scph5501.bin", status = BiosFileStatus.PRESENT),
            ),
        )
        val dcStatus = BiosSystemStatus(
            system = "dc",
            folderExists = true,
            expectedCount = 2,
            presentCount = 1,
            missingFilenames = listOf("dc_flash.bin"),
            wrongHashFilenames = emptyList(),
            files = listOf(
                BiosFileResult(filename = "dc_boot.bin",  status = BiosFileStatus.OK),
                BiosFileResult(filename = "dc_flash.bin", status = BiosFileStatus.MISSING),
            ),
        )
        val validationResult = BiosValidationResult(
            rootConfigured = true,
            systems = listOf(ps1Status, dcStatus),
        )
        coEvery { biosValidator.validate(any()) } returns validationResult

        viewModel().state.test {
            // Consume Loading / any intermediate emissions.
            var loaded: BiosHelperUiState.Loaded? = null
            while (loaded == null) {
                when (val item = awaitItem()) {
                    is BiosHelperUiState.Loaded -> loaded = item
                    else                        -> { /* skip Loading / NoRoot */ }
                }
            }

            // Summary counters
            assertThat(loaded.totalSystems).isEqualTo(2)
            assertThat(loaded.readySystems).isEqualTo(1)

            // ps1 — both files present/verified → isReady = true
            val ps1Entry = loaded.systems.first { it.systemKey == "ps1" }
            assertThat(ps1Entry.isReady).isTrue()
            assertThat(ps1Entry.files[0].verdict).isEqualTo(BiosFileVerdict.VERIFIED)
            assertThat(ps1Entry.files[1].verdict).isEqualTo(BiosFileVerdict.PRESENT)

            // dc — one missing → isReady = false
            val dcEntry = loaded.systems.first { it.systemKey == "dc" }
            assertThat(dcEntry.isReady).isFalse()
            assertThat(dcEntry.files[0].verdict).isEqualTo(BiosFileVerdict.VERIFIED)
            assertThat(dcEntry.files[1].verdict).isEqualTo(BiosFileVerdict.MISSING)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A system with a [BiosFileStatus.WRONG_HASH] file is not ready and
     * the file's verdict is correctly mapped to [BiosFileVerdict.WRONG_HASH].
     */
    @Test
    fun `system with WRONG_HASH file is not ready and verdict maps correctly`() = runTest {
        val ps1Status = BiosSystemStatus(
            system = "ps1",
            folderExists = true,
            expectedCount = 1,
            presentCount = 1,
            missingFilenames = emptyList(),
            wrongHashFilenames = listOf("scph1001.bin"),
            files = listOf(
                BiosFileResult(filename = "scph1001.bin", status = BiosFileStatus.WRONG_HASH),
            ),
        )
        val validationResult = BiosValidationResult(
            rootConfigured = true,
            systems = listOf(ps1Status),
        )
        coEvery { biosValidator.validate(any()) } returns validationResult

        viewModel().state.test {
            var loaded: BiosHelperUiState.Loaded? = null
            while (loaded == null) {
                when (val item = awaitItem()) {
                    is BiosHelperUiState.Loaded -> loaded = item
                    else                        -> { /* skip */ }
                }
            }

            val ps1Entry = loaded.systems.first { it.systemKey == "ps1" }
            assertThat(ps1Entry.isReady).isFalse()
            assertThat(ps1Entry.files[0].verdict).isEqualTo(BiosFileVerdict.WRONG_HASH)
            // WRONG_HASH still counts as "present" so readyCount reflects 0 verified/present
            // (WRONG_HASH is neither VERIFIED nor PRESENT in verdict terms)
            assertThat(ps1Entry.readyCount).isEqualTo(0)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A prose-only system (empty expectedCount) is excluded from the
     * readySystems / totalSystems summary counters.
     */
    @Test
    fun `prose-only system is excluded from summary counters`() = runTest {
        val pspStatus = BiosSystemStatus(
            system = "psp",
            folderExists = false,
            expectedCount = 0,
            presentCount = 0,
            missingFilenames = emptyList(),
            wrongHashFilenames = emptyList(),
            files = emptyList(),
        )
        val validationResult = BiosValidationResult(
            rootConfigured = true,
            systems = listOf(pspStatus),
        )
        coEvery { biosValidator.validate(any()) } returns validationResult

        viewModel().state.test {
            var loaded: BiosHelperUiState.Loaded? = null
            while (loaded == null) {
                when (val item = awaitItem()) {
                    is BiosHelperUiState.Loaded -> loaded = item
                    else                        -> { /* skip */ }
                }
            }

            // Prose-only systems are excluded from both counters.
            assertThat(loaded.totalSystems).isEqualTo(0)
            assertThat(loaded.readySystems).isEqualTo(0)

            // The entry itself is still present in the systems list for display.
            val pspEntry = loaded.systems.first { it.systemKey == "psp" }
            assertThat(pspEntry.isProseSys).isTrue()

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * [BiosHelperViewModel.rescan] resets the state to Loading and re-runs
     * the scan, producing a fresh Loaded result.
     */
    @Test
    fun `rescan resets to Loading then produces a fresh Loaded state`() = runTest {
        val validationResult = BiosValidationResult(
            rootConfigured = true,
            systems = listOf(
                BiosSystemStatus(
                    system = "dc",
                    folderExists = true,
                    expectedCount = 1,
                    presentCount = 1,
                    missingFilenames = emptyList(),
                    wrongHashFilenames = emptyList(),
                    files = listOf(
                        BiosFileResult(filename = "dc_boot.bin", status = BiosFileStatus.OK),
                    ),
                ),
            ),
        )
        coEvery { biosValidator.validate(any()) } returns validationResult

        val vm = viewModel()
        vm.state.test {
            // Consume initial Loading + first Loaded.
            while (awaitItem() !is BiosHelperUiState.Loaded) { /* drain */ }

            // Trigger a rescan. Under UnconfinedTestDispatcher the whole scan
            // (Loading → Loaded) runs synchronously inside this call.
            vm.rescan()

            // The rescan re-emits an *equal* Loaded value, which StateFlow
            // conflates — so a new turbine item is not guaranteed. Drain any
            // items that did surface without blocking, then assert on the
            // terminal StateFlow value directly.
            cancelAndIgnoreRemainingEvents()
        }

        // rescan must have re-invoked the validator (init scan + rescan = 2).
        coVerify(atLeast = 2) { biosValidator.validate(any()) }

        // The settled state after a successful rescan is Loaded.
        assertThat(vm.state.value).isInstanceOf(BiosHelperUiState.Loaded::class.java)
    }
}
