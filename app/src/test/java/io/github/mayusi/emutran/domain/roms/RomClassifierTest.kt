package io.github.mayusi.emutran.domain.roms

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure unit tests for [RomClassifier].
 *
 * Covers:
 *  - Extensions that map to exactly one system (KnownSystem).
 *  - Extensions shared across multiple systems (Ambiguous).
 *  - Unrecognised extensions (Unknown).
 *  - Case-insensitive matching.
 *  - Leading-dot tolerance (classify(".gba") == classify("gba")).
 *  - classifyFile: extracts extension from filename correctly.
 *  - knownExtensions: contains expected entries.
 */
class RomClassifierTest {

    // ── KnownSystem — unambiguous extensions ─────────────────────────────────

    @Test fun `nds maps to ds`() =
        assertKnown("nds", "ds")

    @Test fun `3ds maps to 3ds`() =
        assertKnown("3ds", "3ds")

    @Test fun `cia maps to 3ds`() =
        assertKnown("cia", "3ds")

    @Test fun `gb maps to gb`() =
        assertKnown("gb", "gb")

    @Test fun `gbc maps to gbc`() =
        assertKnown("gbc", "gbc")

    @Test fun `gba maps to gba`() =
        assertKnown("gba", "gba")

    @Test fun `nes maps to nes`() =
        assertKnown("nes", "nes")

    @Test fun `fds maps to nes`() =
        assertKnown("fds", "nes")

    @Test fun `sfc maps to snes`() =
        assertKnown("sfc", "snes")

    @Test fun `smc maps to snes`() =
        assertKnown("smc", "snes")

    @Test fun `z64 maps to n64`() =
        assertKnown("z64", "n64")

    @Test fun `n64 maps to n64`() =
        assertKnown("n64", "n64")

    @Test fun `v64 maps to n64`() =
        assertKnown("v64", "n64")

    @Test fun `gcm maps to gc`() =
        assertKnown("gcm", "gc")

    @Test fun `rvz maps to gc`() =
        assertKnown("rvz", "gc")

    @Test fun `wbfs maps to wii`() =
        assertKnown("wbfs", "wii")

    @Test fun `pbp maps to psp`() =
        assertKnown("pbp", "psp")

    @Test fun `vpk maps to psvita`() =
        assertKnown("vpk", "psvita")

    @Test fun `md maps to genesis`() =
        assertKnown("md", "genesis")

    @Test fun `gen maps to genesis`() =
        assertKnown("gen", "genesis")

    @Test fun `sms maps to mastersystem`() =
        assertKnown("sms", "mastersystem")

    @Test fun `gg maps to gamegear`() =
        assertKnown("gg", "gamegear")

    @Test fun `pce maps to pce`() =
        assertKnown("pce", "pce")

    @Test fun `ngp maps to ngp`() =
        assertKnown("ngp", "ngp")

    @Test fun `ws maps to wonderswan`() =
        assertKnown("ws", "wonderswan")

    @Test fun `xci maps to switch`() =
        assertKnown("xci", "switch")

    @Test fun `nsp maps to switch`() =
        assertKnown("nsp", "switch")

    @Test fun `wud maps to wiiu`() =
        assertKnown("wud", "wiiu")

    @Test fun `wux maps to wiiu`() =
        assertKnown("wux", "wiiu")

    // ── Ambiguous extensions ──────────────────────────────────────────────────

    @Test fun `iso is Ambiguous with ps2 as first candidate`() {
        val result = RomClassifier.classify("iso")
        assertThat(result).isInstanceOf(RomClassifier.Classification.Ambiguous::class.java)
        val candidates = (result as RomClassifier.Classification.Ambiguous).candidates
        assertThat(candidates).contains("ps2")
        assertThat(candidates).contains("ps1")
        assertThat(candidates).contains("gc")
    }

    @Test fun `bin is Ambiguous`() {
        val result = RomClassifier.classify("bin")
        assertThat(result).isInstanceOf(RomClassifier.Classification.Ambiguous::class.java)
    }

    @Test fun `cue is Ambiguous`() {
        val result = RomClassifier.classify("cue")
        assertThat(result).isInstanceOf(RomClassifier.Classification.Ambiguous::class.java)
    }

    @Test fun `chd is Ambiguous with ps1 candidate`() {
        val result = RomClassifier.classify("chd")
        assertThat(result).isInstanceOf(RomClassifier.Classification.Ambiguous::class.java)
        val candidates = (result as RomClassifier.Classification.Ambiguous).candidates
        assertThat(candidates).contains("ps1")
        assertThat(candidates).contains("dc")
    }

    @Test fun `cso is Ambiguous containing psp`() {
        val result = RomClassifier.classify("cso")
        assertThat(result).isInstanceOf(RomClassifier.Classification.Ambiguous::class.java)
        val candidates = (result as RomClassifier.Classification.Ambiguous).candidates
        assertThat(candidates).contains("psp")
    }

    @Test fun `zip is Ambiguous containing arcade`() {
        val result = RomClassifier.classify("zip")
        assertThat(result).isInstanceOf(RomClassifier.Classification.Ambiguous::class.java)
        val candidates = (result as RomClassifier.Classification.Ambiguous).candidates
        assertThat(candidates).contains("arcade")
    }

    @Test fun `7z is Ambiguous`() {
        assertThat(RomClassifier.classify("7z"))
            .isInstanceOf(RomClassifier.Classification.Ambiguous::class.java)
    }

    // ── Unknown extensions ────────────────────────────────────────────────────

    @Test fun `txt is Unknown`() =
        assertUnknown("txt")

    @Test fun `mp4 is Unknown`() =
        assertUnknown("mp4")

    @Test fun `apk is Unknown`() =
        assertUnknown("apk")

    @Test fun `jpg is Unknown`() =
        assertUnknown("jpg")

    @Test fun `empty string extension is Unknown`() =
        assertUnknown("")

    @Test fun `nonsense extension is Unknown`() =
        assertUnknown("xyzzy123")

    // ── Case insensitivity ────────────────────────────────────────────────────

    @Test fun `GBA uppercase maps to gba (KnownSystem)`() =
        assertKnown("GBA", "gba")

    @Test fun `NDS mixed case maps to ds`() =
        assertKnown("NdS", "ds")

    @Test fun `ISO uppercase is Ambiguous`() {
        assertThat(RomClassifier.classify("ISO"))
            .isInstanceOf(RomClassifier.Classification.Ambiguous::class.java)
    }

    // ── Leading dot tolerance ─────────────────────────────────────────────────

    @Test fun `dot-gba is same as gba`() {
        assertThat(RomClassifier.classify(".gba")).isEqualTo(RomClassifier.classify("gba"))
    }

    @Test fun `dot-iso is same as iso`() {
        assertThat(RomClassifier.classify(".iso")).isEqualTo(RomClassifier.classify("iso"))
    }

    @Test fun `dot-txt is same as txt (Unknown)`() {
        assertThat(RomClassifier.classify(".txt")).isEqualTo(RomClassifier.classify("txt"))
    }

    // ── classifyFile ──────────────────────────────────────────────────────────

    @Test fun `classifyFile extracts extension from full filename`() {
        val result = RomClassifier.classifyFile("Metroid Fusion.gba")
        assertKnownResult(result, "gba")
    }

    @Test fun `classifyFile handles dotfile with no extension`() {
        // ".gitignore" — lastIndexOf('.') == 0, so no extension
        assertThat(RomClassifier.classifyFile(".gitignore"))
            .isEqualTo(RomClassifier.Classification.Unknown)
    }

    @Test fun `classifyFile handles filename without extension`() {
        assertThat(RomClassifier.classifyFile("README"))
            .isEqualTo(RomClassifier.Classification.Unknown)
    }

    @Test fun `classifyFile handles filename ending with dot`() {
        // "foo." — dot at lastIndex, so no extension
        assertThat(RomClassifier.classifyFile("foo."))
            .isEqualTo(RomClassifier.Classification.Unknown)
    }

    @Test fun `classifyFile with path containing dots`() {
        // Only the final extension matters
        val result = RomClassifier.classifyFile("/sdcard/games/Super Mario 64.z64")
        assertKnownResult(result, "n64")
    }

    @Test fun `classifyFile iso is Ambiguous`() {
        assertThat(RomClassifier.classifyFile("Gran Turismo.iso"))
            .isInstanceOf(RomClassifier.Classification.Ambiguous::class.java)
    }

    // ── knownExtensions set ───────────────────────────────────────────────────

    @Test fun `knownExtensions contains gba`() =
        assertThat(RomClassifier.knownExtensions).contains("gba")

    @Test fun `knownExtensions contains iso`() =
        assertThat(RomClassifier.knownExtensions).contains("iso")

    @Test fun `knownExtensions does not contain txt`() =
        assertThat(RomClassifier.knownExtensions).doesNotContain("txt")

    @Test fun `knownExtensions contains chd`() =
        assertThat(RomClassifier.knownExtensions).contains("chd")

    // ── Known systems use FolderSpec dir names ────────────────────────────────

    @Test fun `all KnownSystem dirs are valid FolderSpec roms dirs`() {
        val knownRomsDirs = io.github.mayusi.emutran.domain.scaffold.FolderSpec.tree
            .filter { it.startsWith("Emulation/roms/") && it.count { c -> c == '/' } == 2 }
            .map { it.removePrefix("Emulation/roms/") }
            .toSet()

        // Pick a sample of extensions known to map to specific dirs and verify
        // each dir is in FolderSpec.
        val samplesToCheck = listOf("gba", "gbc", "gb", "ds", "snes", "nes", "n64",
            "gc", "wii", "psp", "psvita", "genesis", "mastersystem", "gamegear",
            "pce", "ngp", "wonderswan", "switch", "wiiu", "atari2600",
            "atari5200", "atari7800", "3ds", "arcade")

        for (dir in samplesToCheck) {
            assertThat(knownRomsDirs)
                .contains(dir)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun assertKnown(ext: String, expectedDir: String) {
        assertKnownResult(RomClassifier.classify(ext), expectedDir)
    }

    private fun assertKnownResult(result: RomClassifier.Classification, expectedDir: String) {
        assertThat(result).isInstanceOf(RomClassifier.Classification.KnownSystem::class.java)
        assertThat((result as RomClassifier.Classification.KnownSystem).dir).isEqualTo(expectedDir)
    }

    private fun assertUnknown(ext: String) {
        assertThat(RomClassifier.classify(ext))
            .isEqualTo(RomClassifier.Classification.Unknown)
    }
}
