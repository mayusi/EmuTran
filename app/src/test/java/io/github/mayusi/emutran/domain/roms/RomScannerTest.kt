package io.github.mayusi.emutran.domain.roms

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [RomScanner] using [TemporaryFolder] to build a synthetic
 * directory tree on the real filesystem.
 *
 * These tests verify:
 *  - ROM files in plain directories are found.
 *  - Files already inside the Emulation/ tree are NOT returned.
 *  - Hidden directories (names starting with '.') are skipped.
 *  - Directories in [RomScanner.SKIP_DIR_NAMES] are skipped.
 *  - Top-level "Android" directories (depth 0–1) are skipped.
 *  - Only files whose extension is in the classifier are returned.
 *  - KnownSystem entries have a non-null suggestedTargetDir.
 *  - Ambiguous entries have a null suggestedTargetDir.
 *  - Results are sorted: KnownSystem < Ambiguous < Unknown.
 *
 * Note: the scanner runs on Dispatchers.IO; runTest drives the coroutine.
 */
class RomScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scanner = RomScanner()

    // ── Basic discovery ────────────────────────────────────────────────────────

    @Test
    fun `finds rom files in a flat directory`() = runTest {
        val root = tmp.newFolder("storage")
        File(root, "Metroid Fusion.gba").writeText("data")
        File(root, "Mario.sfc").writeText("data")
        File(root, "readme.txt").writeText("data") // not a ROM

        val emulationRoot = File(tmp.root, "Emulation")
        val results = scanner.scan(listOf(root), emulationRoot)

        val names = results.map { it.file.name }
        assertThat(names).contains("Metroid Fusion.gba")
        assertThat(names).contains("Mario.sfc")
        assertThat(names).doesNotContain("readme.txt")
    }

    @Test
    fun `finds rom files in subdirectories`() = runTest {
        val root = tmp.newFolder("sdcard")
        val sub = File(root, "Downloads/games").apply { mkdirs() }
        File(sub, "Pokemon Crystal.gbc").writeText("data")
        File(sub, "Zelda.z64").writeText("data")

        val emulationRoot = File(tmp.root, "Emulation")
        val results = scanner.scan(listOf(root), emulationRoot)

        val names = results.map { it.file.name }
        assertThat(names).contains("Pokemon Crystal.gbc")
        assertThat(names).contains("Zelda.z64")
    }

    // ── Emulation/ tree is excluded ───────────────────────────────────────────

    @Test
    fun `does NOT return files already inside emulationRoot`() = runTest {
        val root = tmp.newFolder("storage")
        val emulationRoot = File(root, "Emulation").apply { mkdirs() }
        val gbaDir = File(emulationRoot, "roms/gba").apply { mkdirs() }
        File(gbaDir, "AlreadySorted.gba").writeText("data")
        // Also place a file outside Emulation that should be found.
        File(root, "Downloads/NotSorted.gba").apply { parentFile?.mkdirs(); writeText("data") }

        val results = scanner.scan(listOf(root), emulationRoot)

        val names = results.map { it.file.name }
        assertThat(names).doesNotContain("AlreadySorted.gba")
        assertThat(names).contains("NotSorted.gba")
    }

    // ── Hidden directories are skipped ────────────────────────────────────────

    @Test
    fun `does NOT enter hidden directories`() = runTest {
        val root = tmp.newFolder("storage")
        val hiddenDir = File(root, ".hidden").apply { mkdirs() }
        File(hiddenDir, "secret.gba").writeText("data")
        File(root, "visible.gba").writeText("data")

        val emulationRoot = File(tmp.root, "Emulation")
        val results = scanner.scan(listOf(root), emulationRoot)

        val names = results.map { it.file.name }
        assertThat(names).doesNotContain("secret.gba")
        assertThat(names).contains("visible.gba")
    }

    // ── SKIP_DIR_NAMES are skipped ────────────────────────────────────────────

    @Test
    fun `does NOT enter directories in SKIP_DIR_NAMES`() = runTest {
        val root = tmp.newFolder("storage")
        // "data" is in SKIP_DIR_NAMES
        val dataDir = File(root, "data").apply { mkdirs() }
        File(dataDir, "trapped.gba").writeText("data")
        File(root, "safe.gba").writeText("data")

        val emulationRoot = File(tmp.root, "Emulation")
        val results = scanner.scan(listOf(root), emulationRoot)

        val names = results.map { it.file.name }
        assertThat(names).doesNotContain("trapped.gba")
        assertThat(names).contains("safe.gba")
    }

    // ── Android/ at depth 0/1 is skipped ──────────────────────────────────────

    @Test
    fun `does NOT enter top-level Android directory`() = runTest {
        val root = tmp.newFolder("sdcard")
        val androidDir = File(root, "Android").apply { mkdirs() }
        File(androidDir, "game.gba").writeText("data")
        File(root, "open.gba").writeText("data")

        val emulationRoot = File(tmp.root, "Emulation")
        val results = scanner.scan(listOf(root), emulationRoot)

        val names = results.map { it.file.name }
        assertThat(names).doesNotContain("game.gba")
        assertThat(names).contains("open.gba")
    }

    // ── Classification in results ─────────────────────────────────────────────

    @Test
    fun `KnownSystem entry has non-null suggestedTargetDir`() = runTest {
        val root = tmp.newFolder("storage")
        File(root, "FinalFantasy.gba").writeText("data")

        val emulationRoot = File(tmp.root, "Emulation")
        val results = scanner.scan(listOf(root), emulationRoot)

        val entry = results.first { it.file.name == "FinalFantasy.gba" }
        assertThat(entry.classification).isInstanceOf(RomClassifier.Classification.KnownSystem::class.java)
        assertThat(entry.suggestedTargetDir).isNotNull()
        assertThat(entry.suggestedTargetDir!!.name).isEqualTo("gba")
    }

    @Test
    fun `Ambiguous entry has null suggestedTargetDir`() = runTest {
        val root = tmp.newFolder("storage")
        File(root, "game.iso").writeText("data")

        val emulationRoot = File(tmp.root, "Emulation")
        val results = scanner.scan(listOf(root), emulationRoot)

        val entry = results.first { it.file.name == "game.iso" }
        assertThat(entry.classification).isInstanceOf(RomClassifier.Classification.Ambiguous::class.java)
        assertThat(entry.suggestedTargetDir).isNull()
    }

    // ── Result ordering ────────────────────────────────────────────────────────

    @Test
    fun `results are sorted KnownSystem first then Ambiguous`() = runTest {
        val root = tmp.newFolder("storage")
        File(root, "ambiguous.iso").writeText("data")  // Ambiguous
        File(root, "known.gba").writeText("data")       // KnownSystem

        val emulationRoot = File(tmp.root, "Emulation")
        val results = scanner.scan(listOf(root), emulationRoot)

        assertThat(results.size).isAtLeast(2)
        // KnownSystem should come before Ambiguous.
        val knownIdx = results.indexOfFirst { it.classification is RomClassifier.Classification.KnownSystem }
        val ambigIdx = results.indexOfFirst { it.classification is RomClassifier.Classification.Ambiguous }
        assertThat(knownIdx).isLessThan(ambigIdx)
    }

    // ── Non-ROM files are excluded ────────────────────────────────────────────

    @Test
    fun `non-ROM files are not included in results`() = runTest {
        val root = tmp.newFolder("storage")
        File(root, "document.pdf").writeText("data")
        File(root, "photo.png").writeText("data")
        File(root, "music.mp3").writeText("data")
        File(root, "real.gba").writeText("data")

        val emulationRoot = File(tmp.root, "Emulation")
        val results = scanner.scan(listOf(root), emulationRoot)

        val names = results.map { it.file.name }
        assertThat(names).doesNotContain("document.pdf")
        assertThat(names).doesNotContain("photo.png")
        assertThat(names).doesNotContain("music.mp3")
        assertThat(names).contains("real.gba")
    }

    // ── Empty directory tree ──────────────────────────────────────────────────

    @Test
    fun `returns empty list for directory with no ROMs`() = runTest {
        val root = tmp.newFolder("empty_storage")
        File(root, "notes.txt").writeText("hello")

        val emulationRoot = File(tmp.root, "Emulation")
        val results = scanner.scan(listOf(root), emulationRoot)

        assertThat(results).isEmpty()
    }

    @Test
    fun `returns empty list when volumeRoots is empty`() = runTest {
        val emulationRoot = File(tmp.root, "Emulation")
        val results = scanner.scan(emptyList(), emulationRoot)
        assertThat(results).isEmpty()
    }

    // ── Size is captured ──────────────────────────────────────────────────────

    @Test
    fun `sizeBytes matches actual file size`() = runTest {
        val root = tmp.newFolder("storage")
        val content = ByteArray(4096) { 0xFF.toByte() }
        File(root, "bigrom.gba").writeBytes(content)

        val emulationRoot = File(tmp.root, "Emulation")
        val results = scanner.scan(listOf(root), emulationRoot)

        val entry = results.first { it.file.name == "bigrom.gba" }
        assertThat(entry.sizeBytes).isEqualTo(4096L)
    }
}
