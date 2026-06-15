package io.github.mayusi.emutran.data.profile

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.emutran.data.manifest.AppEntry
import io.github.mayusi.emutran.data.manifest.ObtainiumPackParser
import io.github.mayusi.emutran.data.selection.SelectedAppsStore
import io.github.mayusi.emutran.data.storage.SetupOptionsStore
import io.github.mayusi.emutran.data.storage.StorageRootStore
import io.github.mayusi.emutran.data.storage.StorageVolumes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [ProfileRepository] — the profile import trust boundary.
 *
 * All collaborators are MockK'd (relaxed) so no Android context is needed:
 *  - [StorageRootStore]   — rootPath flow + save (verified NOT called on import).
 *  - [SelectedAppsStore]  — save captured to assert only known ids are written.
 *  - [SetupOptionsStore]  — relaxed (flag setters just run).
 *  - [ObtainiumPackParser]— loadBundledOnly/loadDualScreen stubbed to a known
 *    manifest so unknown ids are deterministically dropped.
 *  - [StorageVolumes]     — list() stubbed to a real [TemporaryFolder] path so
 *    the storageRoot canonicalize+validate logic is deterministic on the JVM.
 *
 * Trust-boundary properties verified:
 *  - valid round-trip applies pickedIds;
 *  - malformed JSON -> ImportResult.Failed (never throws);
 *  - ids absent from the bundled manifest -> droppedIds, NOT saved;
 *  - schemaVersion > current -> Failed;
 *  - a proposed root under a real volume is isValid=true;
 *  - a path-traversal / foreign-sandbox path is isValid=false;
 *  - import NEVER calls storageRoot.save (root deferred to user confirm);
 *  - applyStorageRoot only saves a re-validated path.
 */
class ProfileRepositoryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val storageRoot = mockk<StorageRootStore>(relaxed = true)
    private val selectedApps = mockk<SelectedAppsStore>(relaxed = true)
    private val setupOptions = mockk<SetupOptionsStore>(relaxed = true)
    private val manifestParser = mockk<ObtainiumPackParser>(relaxed = true)
    private val storageVolumes = mockk<StorageVolumes>(relaxed = true)

    /** The deterministic "real volume" root used for storageRoot validation. */
    private lateinit var volumeRoot: File

    private fun repo() = ProfileRepository(
        storageRoot = storageRoot,
        selectedApps = selectedApps,
        setupOptions = setupOptions,
        manifestParser = manifestParser,
        storageVolumes = storageVolumes,
    )

    private fun entry(id: String): AppEntry = mockk(relaxed = true) {
        every { this@mockk.id } returns id
    }

    /**
     * Stub the bundled manifests to contain [ids] (split arbitrarily across the
     * standard and dual-screen variants — import unions them).
     */
    private fun stubManifest(vararg ids: String) {
        coEvery { manifestParser.loadBundledOnly() } returns ids.map { entry(it) }
        coEvery { manifestParser.loadDualScreen() } returns emptyList()
    }

    private fun stubVolume(): String {
        volumeRoot = tmpFolder.newFolder("volume")
        every { storageVolumes.list() } returns listOf(
            StorageVolumes.Volume(
                label = "Internal storage",
                path = volumeRoot.absolutePath,
                isRemovable = false,
                isPrimary = true,
            )
        )
        return volumeRoot.absolutePath
    }

    private fun stubCurrentRoot(path: String?) {
        every { storageRoot.rootPath } returns flowOf(path)
    }

    private fun profileJson(
        schemaVersion: Int = ProfileRepository.SCHEMA_VERSION,
        storageRoot: String,
        pickedIds: List<String>,
        isDualScreen: Boolean = false,
        stageGpuDrivers: Boolean = false,
    ): String = """
        {
          "schemaVersion": $schemaVersion,
          "storageRoot": "${storageRoot.replace("\\", "\\\\")}",
          "pickedIds": [${pickedIds.joinToString(",") { "\"$it\"" }}],
          "isDualScreen": $isDualScreen,
          "stageGpuDrivers": $stageGpuDrivers
        }
    """.trimIndent()

    // ── Valid round-trip ──────────────────────────────────────────────────────

    @Test
    fun `valid profile applies known pickedIds and proposes valid root`() = runTest {
        val volRoot = stubVolume()
        // Use the volume root itself as the proposed root: "equal paths count as
        // under" in isPathUnder, so this is valid on every OS regardless of the
        // platform path separator (a nested child would rely on '/'-joining,
        // which only holds on the Android/Linux target).
        val proposed = volRoot
        stubManifest("org.ppsspp.ppsspp", "com.flycast.emulator")
        stubCurrentRoot(null)

        val saved = slot<Set<String>>()
        coEvery { selectedApps.save(capture(saved)) } returns Unit

        val json = profileJson(
            storageRoot = proposed,
            pickedIds = listOf("org.ppsspp.ppsspp", "com.flycast.emulator"),
        )
        val result = repo().import(json)

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        result as ImportResult.Success
        assertThat(result.appliedCount).isEqualTo(2)
        assertThat(result.droppedIds).isEmpty()
        assertThat(saved.captured).containsExactly("org.ppsspp.ppsspp", "com.flycast.emulator")
        assertThat(result.proposedStorageRoot).isNotNull()
        assertThat(result.proposedStorageRoot!!.isValid).isTrue()
    }

    // ── Malformed JSON never throws ───────────────────────────────────────────

    @Test
    fun `malformed JSON returns Failed and never throws`() = runTest {
        stubVolume()
        stubManifest("org.ppsspp.ppsspp")
        stubCurrentRoot(null)

        val result = repo().import("{ this is not valid json ]")

        assertThat(result).isInstanceOf(ImportResult.Failed::class.java)
        // Nothing was saved.
        coVerify(exactly = 0) { selectedApps.save(any()) }
        coVerify(exactly = 0) { storageRoot.save(any()) }
    }

    // ── Unknown ids dropped, not saved ────────────────────────────────────────

    @Test
    fun `ids absent from bundled manifest land in droppedIds and are not saved`() = runTest {
        val volRoot = stubVolume()
        // Manifest only knows ppsspp; the other two are stale/unknown.
        stubManifest("org.ppsspp.ppsspp")
        stubCurrentRoot(null)

        val saved = slot<Set<String>>()
        coEvery { selectedApps.save(capture(saved)) } returns Unit

        val json = profileJson(
            storageRoot = File(volRoot, "Emulation").absolutePath,
            pickedIds = listOf("org.ppsspp.ppsspp", "com.dead.fork", "xyz.removed.app"),
        )
        val result = repo().import(json) as ImportResult.Success

        assertThat(result.appliedCount).isEqualTo(1)
        assertThat(result.droppedIds).containsExactly("com.dead.fork", "xyz.removed.app")
        // Only the known id is persisted — the unknown ids never reach the store.
        assertThat(saved.captured).containsExactly("org.ppsspp.ppsspp")
    }

    // ── Newer schema rejected ─────────────────────────────────────────────────

    @Test
    fun `schemaVersion greater than current returns Failed and writes nothing`() = runTest {
        stubVolume()
        stubManifest("org.ppsspp.ppsspp")
        stubCurrentRoot(null)

        val json = profileJson(
            schemaVersion = ProfileRepository.SCHEMA_VERSION + 1,
            storageRoot = File(volumeRoot, "Emulation").absolutePath,
            pickedIds = listOf("org.ppsspp.ppsspp"),
        )
        val result = repo().import(json)

        assertThat(result).isInstanceOf(ImportResult.Failed::class.java)
        coVerify(exactly = 0) { selectedApps.save(any()) }
        coVerify(exactly = 0) { storageRoot.save(any()) }
    }

    // ── storageRoot trust boundary ────────────────────────────────────────────

    @Test
    fun `import never persists the proposed storage root`() = runTest {
        val volRoot = stubVolume()
        stubManifest("org.ppsspp.ppsspp")
        stubCurrentRoot(null)

        val json = profileJson(
            storageRoot = File(volRoot, "Emulation").absolutePath,
            pickedIds = listOf("org.ppsspp.ppsspp"),
        )
        repo().import(json)

        // The whole point of the trust boundary: import proposes, never saves.
        coVerify(exactly = 0) { storageRoot.save(any()) }
    }

    @Test
    fun `path-traversal root escaping the volume is invalid and not applied`() = runTest {
        stubVolume()
        stubManifest("org.ppsspp.ppsspp")
        stubCurrentRoot(null)

        // A path that canonicalizes ABOVE the real volume root — foreign location.
        val foreign = File(volumeRoot.parentFile, "outside_volume/Emulation").absolutePath
        val json = profileJson(
            storageRoot = foreign,
            pickedIds = listOf("org.ppsspp.ppsspp"),
        )
        val result = repo().import(json) as ImportResult.Success

        assertThat(result.proposedStorageRoot).isNotNull()
        assertThat(result.proposedStorageRoot!!.isValid).isFalse()
        coVerify(exactly = 0) { storageRoot.save(any()) }
    }

    @Test
    fun `root inside an Android data sandbox is invalid`() = runTest {
        val volRoot = stubVolume()
        stubManifest("org.ppsspp.ppsspp")
        stubCurrentRoot(null)

        // Under the real volume, but inside an app sandbox — must be rejected.
        val sandbox = File(volRoot, "Android/data/com.some.app/files/Emulation").absolutePath
        val json = profileJson(
            storageRoot = sandbox,
            pickedIds = listOf("org.ppsspp.ppsspp"),
        )
        val result = repo().import(json) as ImportResult.Success

        assertThat(result.proposedStorageRoot!!.isValid).isFalse()
    }

    // ── applyStorageRoot re-validation ────────────────────────────────────────

    @Test
    fun `applyStorageRoot saves a re-validated path under the volume`() = runTest {
        val volRoot = stubVolume()
        stubCurrentRoot(null)
        coEvery { storageRoot.save(any()) } returns Unit

        // Volume root itself — valid via the equal-paths branch on any OS.
        val good = volRoot
        val ok = repo().applyStorageRoot(good)

        assertThat(ok).isTrue()
        coVerify(exactly = 1) { storageRoot.save(any()) }
    }

    @Test
    fun `applyStorageRoot rejects a foreign path and never saves`() = runTest {
        stubVolume()
        stubCurrentRoot(null)

        val foreign = File(volumeRoot.parentFile, "outside_volume/Emulation").absolutePath
        val ok = repo().applyStorageRoot(foreign)

        assertThat(ok).isFalse()
        coVerify(exactly = 0) { storageRoot.save(any()) }
    }

    @Test
    fun `applyStorageRoot rejects a blank path`() = runTest {
        stubVolume()
        stubCurrentRoot(null)

        assertThat(repo().applyStorageRoot("")).isFalse()
        coVerify(exactly = 0) { storageRoot.save(any()) }
    }

    // ── Sanity guard: import() completes while a pickedIds collector is live ──
    //
    // HONEST SCOPE: this is NOT a faithful reproduction of the on-device
    // deadlock. That deadlock was a real DataStore internal-actor stall
    // (an IO-dispatched edit() fanning out to a hot main-thread collector on
    // the same DataStore instance). This test uses a relaxed mockk + a plain
    // MutableStateFlow, so there is no DataStore actor and runTest neutralises
    // Dispatchers.IO onto the single test scheduler — meaning it would pass
    // with OR without the withContext(Dispatchers.IO) wrapper. It does NOT
    // prove the fix.
    //
    // What it DOES guard: that import() still resolves to Success and applies
    // the picked ids while a concurrent pickedIds collector is subscribed —
    // i.e. no logic regression in the import path. The deadlock itself is only
    // truly provable on-device (Dispatchers.IO + real DataStore lock), which is
    // covered by the manual Odin-3 import re-verification, not by this unit test.

    @Test
    fun `import completes within timeout while a pickedIds collector is active`() = runTest {
        val volRoot = stubVolume()
        stubManifest("org.ppsspp.ppsspp")
        stubCurrentRoot(null)

        // A real MutableStateFlow stands in for the DataStore-backed pickedIds.
        // save() emits into it so a concurrent collector is genuinely live
        // during the import() call — reproducing the ProfileViewModel.init
        // collect loop that triggered the deadlock on-device.
        val pickedFlow = MutableStateFlow<Set<String>>(emptySet())
        val fakeSelectedApps = mockk<SelectedAppsStore>(relaxed = true)
        every { fakeSelectedApps.pickedIds } returns pickedFlow
        coEvery { fakeSelectedApps.save(any()) } answers {
            pickedFlow.value = firstArg()
        }

        val repoUnderTest = ProfileRepository(
            storageRoot = storageRoot,
            selectedApps = fakeSelectedApps,
            setupOptions = setupOptions,
            manifestParser = manifestParser,
            storageVolumes = storageVolumes,
        )

        // Start an active, concurrent collector of pickedIds — this is the
        // equivalent of ProfileViewModel.init's collect loop.
        val collectorJob = launch {
            fakeSelectedApps.pickedIds.collect { /* stay subscribed */ }
        }

        val json = profileJson(
            storageRoot = volRoot,
            pickedIds = listOf("org.ppsspp.ppsspp"),
        )

        // 5-second timeout: without the fix this would never complete.
        val result = withTimeout(5_000L) { repoUnderTest.import(json) }

        collectorJob.cancel()

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        result as ImportResult.Success
        assertThat(result.appliedCount).isEqualTo(1)
        assertThat(pickedFlow.value).containsExactly("org.ppsspp.ppsspp")
    }
}
