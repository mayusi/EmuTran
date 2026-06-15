package io.github.mayusi.emutran.domain.scaffold

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FolderSpecTest {

    @Test
    fun `every readme path's parent appears in the folder tree`() {
        val folders = FolderSpec.tree.toSet()
        val missingParents = FolderSpec.biosReadmes.keys
            .map { it.substringBeforeLast('/') }
            .filterNot { it in folders }
        assertThat(missingParents).isEmpty()
    }

    @Test
    fun `tree includes core roms and bios roots`() {
        assertThat(FolderSpec.tree).contains("Emulation/roms")
        assertThat(FolderSpec.tree).contains("Emulation/bios")
        assertThat(FolderSpec.tree).contains("Emulation/saves")
        assertThat(FolderSpec.tree).contains("Emulation/tools/turnip")
        assertThat(FolderSpec.tree).contains("Emulation/tools/retroarch_cores")
    }

    @Test
    fun `every entry uses forward slashes and starts with Emulation`() {
        val bad = FolderSpec.tree.filter { '\\' in it || !it.startsWith("Emulation") }
        assertThat(bad).isEmpty()
    }

    @Test
    fun `no duplicate folder entries`() {
        assertThat(FolderSpec.tree).containsNoDuplicates()
    }

    @Test
    fun `biosFilesBySystem strips notes and skips prose`() {
        // Parenthetical region/optional notes are stripped to clean filenames.
        assertThat(FolderSpec.biosFilesBySystem["ps1"])
            .containsExactly("scph1001.bin", "scph5501.bin", "scph7001.bin", "scph1002.bin")
        assertThat(FolderSpec.biosFilesBySystem["gba"]).containsExactly("gba_bios.bin")
        assertThat(FolderSpec.biosFilesBySystem["dc"]).containsExactly("dc_boot.bin", "dc_flash.bin")

        // Prose-only entries (no real filename) yield an empty list, not a fake file.
        assertThat(FolderSpec.biosFilesBySystem["psp"]).isEmpty()
        assertThat(FolderSpec.biosFilesBySystem["retroarch"]).isEmpty()

        // No extracted "filename" ever contains whitespace or a parenthesis.
        val bad = FolderSpec.biosFilesBySystem.values.flatten()
            .filter { it.isBlank() || it.any(Char::isWhitespace) || '(' in it }
        assertThat(bad).isEmpty()
    }

    @Test
    fun `every bios system folder has a matching biosFilesBySystem key`() {
        val biosSystems = FolderSpec.biosReadmes.keys
            .map { it.removePrefix("Emulation/bios/").substringBefore('/') }
            .toSet()
        assertThat(FolderSpec.biosFilesBySystem.keys).isEqualTo(biosSystems)
    }
}
