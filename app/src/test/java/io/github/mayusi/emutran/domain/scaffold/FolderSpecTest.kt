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
}
