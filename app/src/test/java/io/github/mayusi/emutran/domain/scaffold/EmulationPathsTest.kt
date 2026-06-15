package io.github.mayusi.emutran.domain.scaffold

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Pure unit tests for [resolveEmulationRoot] and [resolveTurnipDir].
 *
 * These are top-level package functions that only use java.io.File —
 * no Android framework, no mocks needed.
 *
 * Key invariant: the "double-suffix guard" — if the stored root already ends
 * in a directory named "Emulation" (case-insensitive), appending "Emulation"
 * again must be suppressed.
 *
 * Note: tests are written to be platform-safe. We assert structural/logical
 * properties (name, parent, ends-with) rather than exact path strings with
 * forward slashes, because File.path uses the OS separator (backslash on
 * Windows, forward slash on Linux/macOS) and these tests run on Windows.
 */
class EmulationPathsTest {

    // ── resolveEmulationRoot ──────────────────────────────────────────────────

    @Test
    fun `resolveEmulationRoot appends Emulation when root is a parent dir`() {
        val result = resolveEmulationRoot("/sdcard")
        assertThat(result.name).isEqualTo("Emulation")
        assertThat(result.parentFile?.name).isEqualTo("sdcard")
    }

    @Test
    fun `resolveEmulationRoot does NOT double-suffix when root ends in Emulation`() {
        val input = File("/sdcard", "Emulation")
        val result = resolveEmulationRoot(input.path)
        // Must not append a second Emulation — parent should still be "sdcard"
        assertThat(result.name).isEqualTo("Emulation")
        assertThat(result.parentFile?.name).isEqualTo("sdcard")
        assertThat(result.path).doesNotContain("Emulation" + File.separator + "Emulation")
    }

    @Test
    fun `resolveEmulationRoot is case-insensitive - lowercase emulation`() {
        val input = File("/sdcard", "emulation")
        val result = resolveEmulationRoot(input.path)
        // "emulation" should be treated as the Emulation root — no appending
        assertThat(result.name).ignoringCase().isEqualTo("emulation")
        assertThat(result.parentFile?.name).isEqualTo("sdcard")
        assertThat(result.path).doesNotContain("emulation" + File.separator + "Emulation")
        assertThat(result.path).doesNotContain("emulation" + File.separator + "emulation")
    }

    @Test
    fun `resolveEmulationRoot is case-insensitive - uppercase EMULATION`() {
        val input = File("/sdcard", "EMULATION")
        val result = resolveEmulationRoot(input.path)
        assertThat(result.name).ignoringCase().isEqualTo("EMULATION")
        assertThat(result.parentFile?.name).isEqualTo("sdcard")
        assertThat(result.path).doesNotContain("EMULATION" + File.separator + "Emulation")
    }

    @Test
    fun `resolveEmulationRoot is case-insensitive - mixed case eMuLaTiOn`() {
        val input = File("/sdcard", "eMuLaTiOn")
        val result = resolveEmulationRoot(input.path)
        assertThat(result.name).ignoringCase().isEqualTo("eMuLaTiOn")
        assertThat(result.parentFile?.name).isEqualTo("sdcard")
    }

    @Test
    fun `resolveEmulationRoot does not treat EmulationGames as Emulation suffix`() {
        // "EmulationGames" should NOT be treated as ending in "Emulation"
        val input = File("/sdcard", "EmulationGames")
        val result = resolveEmulationRoot(input.path)
        assertThat(result.name).isEqualTo("Emulation")
        assertThat(result.parentFile?.name).isEqualTo("EmulationGames")
    }

    @Test
    fun `resolveEmulationRoot handles deep parent path`() {
        val input = File(File(File("/storage"), "emulated"), "0")
        val result = resolveEmulationRoot(input.path)
        assertThat(result.name).isEqualTo("Emulation")
        assertThat(result.parentFile?.name).isEqualTo("0")
    }

    @Test
    fun `resolveEmulationRoot handles path ending in Emulation with trailing slash stripped`() {
        // File("/sdcard/Emulation/") normalises trailing slash on JVM
        val inputWithTrailing = File("/sdcard/Emulation/").path
        val result = resolveEmulationRoot(inputWithTrailing)
        // Must not append a second Emulation
        assertThat(result.path).doesNotContain("Emulation" + File.separator + "Emulation")
        assertThat(result.name).ignoringCase().isEqualTo("Emulation")
    }

    // ── resolveTurnipDir ──────────────────────────────────────────────────────

    @Test
    fun `resolveTurnipDir has name turnip`() {
        val result = resolveTurnipDir("/sdcard")
        assertThat(result.name).isEqualTo("turnip")
    }

    @Test
    fun `resolveTurnipDir parent is tools`() {
        val result = resolveTurnipDir("/sdcard")
        assertThat(result.parentFile?.name).isEqualTo("tools")
    }

    @Test
    fun `resolveTurnipDir grandparent is Emulation - parent root`() {
        val result = resolveTurnipDir("/sdcard")
        assertThat(result.parentFile?.parentFile?.name).isEqualTo("Emulation")
    }

    @Test
    fun `resolveTurnipDir grandparent is Emulation - already Emulation root`() {
        val emulationPath = File("/sdcard", "Emulation").path
        val result = resolveTurnipDir(emulationPath)
        assertThat(result.parentFile?.parentFile?.name).isEqualTo("Emulation")
    }

    @Test
    fun `resolveTurnipDir no double suffix when root ends in Emulation case-insensitive`() {
        val emulationPath = File("/sdcard", "emulation").path
        val result = resolveTurnipDir(emulationPath)
        assertThat(result.name).isEqualTo("turnip")
        // Should not have emulation/Emulation anywhere in path
        assertThat(result.path.lowercase()).doesNotContain(
            "emulation" + File.separator + "emulation"
        )
    }

    @Test
    fun `resolveTurnipDir produces correct depth for all root styles`() {
        listOf(
            "/sdcard" to "sdcard",
            File("/sdcard/Emulation").path to "sdcard",
            File("/sdcard/emulation").path to "sdcard",
            File("/sdcard/EMULATION").path to "sdcard",
        ).forEach { (rootPath, expectedGreatGrandparent) ->
            val result = resolveTurnipDir(rootPath)
            // turnip/tools/Emulation/<parent>
            assertThat(result.name).isEqualTo("turnip")
            assertThat(result.parentFile?.name).isEqualTo("tools")
            val grandParentName = result.parentFile?.parentFile?.name?.lowercase()
            assertThat(grandParentName).isEqualTo("emulation")
            assertThat(result.parentFile?.parentFile?.parentFile?.name).isEqualTo(expectedGreatGrandparent)
        }
    }

    @Test
    fun `resolveTurnipDir is child of resolveEmulationRoot`() {
        val emulationRoot = resolveEmulationRoot("/sdcard")
        val turnipDir     = resolveTurnipDir("/sdcard")
        // turnip must be under emulationRoot
        assertThat(turnipDir.path).startsWith(emulationRoot.path)
    }
}
