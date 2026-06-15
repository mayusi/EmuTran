package io.github.mayusi.emutran.domain.health

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.emutran.data.storage.StorageRootStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [BiosValidator] — the md5 hash-compare scanner.
 *
 * Strategy:
 *  - A real [TemporaryFolder] stands in for the storage root; we lay down a
 *    fake Emulation/bios/<system>/ tree on the real filesystem (the validator
 *    is pure java.io.File + MessageDigest, so no Android context is needed).
 *  - [StorageRootStore] is a relaxed MockK whose [StorageRootStore.rootPath]
 *    flow we point at the temp root (exercising the null-rootPath fallback).
 *
 * Hash paths covered against the real [BiosValidator.KNOWN_MD5] table:
 *  - WRONG_HASH — a file at a filename WITH a known reference, but whose bytes
 *    md5 to something else (the dominant, fully-deterministic check).
 *  - PRESENT    — a file at a filename WITHOUT a known reference hash.
 *  - MISSING    — an expected file omitted from disk.
 *  - expectedCount == 0 — prose-only systems (switch / psp).
 *
 * NOTE on the OK (exact-match) path: KNOWN_MD5 holds the published md5s of real
 * console BIOS dumps (e.g. ps1/scph5501.bin -> 490f666e…). md5 is one-way, so
 * we cannot synthesize bytes that hash to a real reference without the actual
 * (copyrighted) BIOS bytes, which EmuTran never ships. We therefore verify the
 * compare LOGIC exhaustively via the WRONG_HASH path (identical code branch,
 * inverse verdict) and the PRESENT/MISSING paths. The OK branch itself is a
 * one-line `actual.equals(expected)` returning OK on match; the WRONG_HASH test
 * proves the surrounding wiring. Exact-OK coverage needs the real BIOS bytes.
 */
class BiosValidatorTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val storageRoot = mockk<StorageRootStore>(relaxed = true)

    private fun validator() = BiosValidator(storageRoot = storageRoot)

    /** Create Emulation/bios/<system>/ under [root] and return that system dir. */
    private fun biosSystemDir(root: File, system: String): File =
        File(root, "Emulation/bios/$system").also { it.mkdirs() }

    private fun systemStatus(result: BiosValidationResult, system: String): BiosSystemStatus =
        result.systems.first { it.system == system }

    private fun fileStatus(status: BiosSystemStatus, filename: String): BiosFileStatus =
        status.files.first { it.filename.equals(filename, ignoreCase = true) }.status

    // ── rootConfigured wiring ────────────────────────────────────────────────

    @Test
    fun `null rootPath and no persisted root - rootConfigured false`() = runTest {
        every { storageRoot.rootPath } returns flowOf(null)

        val result = validator().validate(rootPath = null)

        assertThat(result.rootConfigured).isFalse()
        assertThat(result.systems).isEmpty()
    }

    @Test
    fun `null rootPath falls back to persisted StorageRootStore root`() = runTest {
        val root = tmpFolder.newFolder("storage")
        every { storageRoot.rootPath } returns flowOf(root.absolutePath)

        val result = validator().validate(rootPath = null)

        assertThat(result.rootConfigured).isTrue()
        // Every system in the spec is represented.
        assertThat(result.systems.map { it.system })
            .containsExactlyElementsIn(io.github.mayusi.emutran.domain.scaffold.FolderSpec.biosFilesBySystem.keys)
    }

    // ── WRONG_HASH: file present at a known-reference filename, wrong bytes ────

    @Test
    fun `known-reference file with wrong bytes - WRONG_HASH`() = runTest {
        val root = tmpFolder.newFolder("storage")
        val ps1 = biosSystemDir(root, "ps1")
        // scph5501.bin HAS a reference md5 (490f666e…). These bytes won't match it.
        File(ps1, "scph5501.bin").writeBytes("not-a-real-bios".toByteArray())
        every { storageRoot.rootPath } returns flowOf(null)

        val result = validator().validate(rootPath = root.absolutePath)
        val ps1Status = systemStatus(result, "ps1")

        assertThat(fileStatus(ps1Status, "scph5501.bin")).isEqualTo(BiosFileStatus.WRONG_HASH)
        assertThat(ps1Status.wrongHashFilenames).contains("scph5501.bin")
        assertThat(result.anyWrongHash).isTrue()
    }

    @Test
    fun `flipping one byte of otherwise-identical content still mismatches - WRONG_HASH`() = runTest {
        // Two near-identical payloads that differ in exactly one byte must produce
        // different md5s; at most one could match a reference, so a one-byte flip
        // away from any candidate is observably WRONG_HASH. We assert both are
        // WRONG_HASH against the real (real-BIOS) reference — neither equals it.
        val root = tmpFolder.newFolder("storage")
        val ps1 = biosSystemDir(root, "ps1")
        val base = ByteArray(64) { it.toByte() }
        File(ps1, "scph1001.bin").writeBytes(base)
        every { storageRoot.rootPath } returns flowOf(null)

        val first = validator().validate(rootPath = root.absolutePath)
        assertThat(fileStatus(systemStatus(first, "ps1"), "scph1001.bin"))
            .isEqualTo(BiosFileStatus.WRONG_HASH)

        // Flip one byte and re-scan — still not the real reference dump.
        base[0] = (base[0] + 1).toByte()
        File(ps1, "scph1001.bin").writeBytes(base)
        val second = validator().validate(rootPath = root.absolutePath)
        assertThat(fileStatus(systemStatus(second, "ps1"), "scph1001.bin"))
            .isEqualTo(BiosFileStatus.WRONG_HASH)
    }

    // ── PRESENT: file present at a filename with no reference hash ─────────────

    @Test
    fun `file present but no reference hash for that name - PRESENT`() = runTest {
        val root = tmpFolder.newFolder("storage")
        // ps2 filenames (SCPH-70012.bin etc.) have NO entry in KNOWN_MD5.
        val ps2 = biosSystemDir(root, "ps2")
        File(ps2, "SCPH-70012.bin").writeBytes("anything".toByteArray())
        every { storageRoot.rootPath } returns flowOf(null)

        val result = validator().validate(rootPath = root.absolutePath)
        val ps2Status = systemStatus(result, "ps2")

        assertThat(fileStatus(ps2Status, "SCPH-70012.bin")).isEqualTo(BiosFileStatus.PRESENT)
        assertThat(ps2Status.wrongHashFilenames).isEmpty()
        // PRESENT counts as present (non-missing).
        assertThat(ps2Status.presentCount).isAtLeast(1)
    }

    // ── MISSING: expected file omitted from disk ──────────────────────────────

    @Test
    fun `expected file absent from disk - MISSING`() = runTest {
        val root = tmpFolder.newFolder("storage")
        // Create the ps1 bios folder but place NO files in it.
        biosSystemDir(root, "ps1")
        every { storageRoot.rootPath } returns flowOf(null)

        val result = validator().validate(rootPath = root.absolutePath)
        val ps1Status = systemStatus(result, "ps1")

        // scph5501.bin is one of ps1's expected files; it wasn't written.
        assertThat(fileStatus(ps1Status, "scph5501.bin")).isEqualTo(BiosFileStatus.MISSING)
        assertThat(ps1Status.missingFilenames).contains("scph5501.bin")
        assertThat(ps1Status.folderExists).isTrue()
    }

    @Test
    fun `system folder absent entirely - all expected MISSING and folderExists false`() = runTest {
        val root = tmpFolder.newFolder("storage")
        // Only create Emulation/bios (no ps1 subfolder).
        File(root, "Emulation/bios").mkdirs()
        every { storageRoot.rootPath } returns flowOf(null)

        val result = validator().validate(rootPath = root.absolutePath)
        val ps1Status = systemStatus(result, "ps1")

        assertThat(ps1Status.folderExists).isFalse()
        assertThat(ps1Status.presentCount).isEqualTo(0)
        assertThat(ps1Status.missingFilenames).isEqualTo(ps1Status.files.map { it.filename })
        assertThat(ps1Status.files.map { it.status }.toSet())
            .containsExactly(BiosFileStatus.MISSING)
    }

    // ── expectedCount == 0: prose-only systems ────────────────────────────────

    @Test
    fun `prose-only systems have zero expected files`() = runTest {
        val root = tmpFolder.newFolder("storage")
        biosSystemDir(root, "psp")
        biosSystemDir(root, "wiiu")
        biosSystemDir(root, "retroarch")
        every { storageRoot.rootPath } returns flowOf(null)

        val result = validator().validate(rootPath = root.absolutePath)

        // psp (PPSSPP prose), wiiu ("Title keys (where applicable)…"), and
        // retroarch ("This is RetroArch's 'system' directory…") are all prose
        // with no parseable filename, so FolderSpec.biosFilesBySystem maps them
        // to empty lists. (Note: `switch` is NOT empty — prod.keys / title.keys
        // ARE valid filenames; only genuinely prose entries resolve to nothing.)
        for (system in listOf("psp", "wiiu", "retroarch")) {
            assertThat(systemStatus(result, system).expectedCount).isEqualTo(0)
            assertThat(systemStatus(result, system).files).isEmpty()
        }
    }

    // ── Aggregate accounting across systems ──────────────────────────────────

    @Test
    fun `totals aggregate expected and present across systems`() = runTest {
        val root = tmpFolder.newFolder("storage")
        val ps1 = biosSystemDir(root, "ps1")
        File(ps1, "scph1001.bin").writeBytes("x".toByteArray()) // present (wrong hash)
        every { storageRoot.rootPath } returns flowOf(null)

        val result = validator().validate(rootPath = root.absolutePath)

        assertThat(result.totalExpected).isEqualTo(
            result.systems.sumOf { it.expectedCount }
        )
        assertThat(result.totalPresent).isAtLeast(1)
        // ps1 expects 4 cleaned filenames per FolderSpec.
        assertThat(systemStatus(result, "ps1").expectedCount).isEqualTo(4)
    }
}
