package io.github.mayusi.emutran.domain.drivers

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.emutran.data.device.GpuFamily
import io.github.mayusi.emutran.data.device.GpuInfo
import org.junit.Test

class DriverHintProviderTest {

    private val provider = DriverHintProvider()

    private fun adreno(model: Int? = 740) =
        GpuInfo("Qualcomm", "Adreno (TM) ${model ?: ""}", "OpenGL ES 3.2", GpuFamily.ADRENO, model)

    private fun mali() =
        GpuInfo("ARM", "Mali-G715", "OpenGL ES 3.2", GpuFamily.MALI, null)

    @Test
    fun `Switch PS2 PS3 PS Vita emulators get the Turnip hint on Adreno`() {
        val turnipIds = listOf(
            "dev.eden.eden_emulator",
            "xyz.aethersx2.android",
            "xyz.aethersx2.tturnip",
            "aenu.aps3e",
            "org.vita3k.emulator",
        )
        for (id in turnipIds) {
            assertThat(provider.hintFor(id, adreno()))
                .isEqualTo(DriverHintProvider.HINT_TURNIP)
        }
    }

    @Test
    fun `other known emulators get the Qualcomm hint on Adreno`() {
        val qualcommIds = listOf(
            "org.dolphinemu.dolphinemu",
            "info.cemu.cemu",
            "org.azahar_emu.azahar",
            "me.magnum.melonds",
            "org.ppsspp.ppsspp",
            "com.github.stenzek.duckstation",
            "com.flycast.emulator",
        )
        for (id in qualcommIds) {
            assertThat(provider.hintFor(id, adreno()))
                .isEqualTo(DriverHintProvider.HINT_QUALCOMM)
        }
    }

    @Test
    fun `unknown app returns null even on Adreno`() {
        assertThat(provider.hintFor("com.unknown.emulator", adreno())).isNull()
        assertThat(provider.hintFor("", adreno())).isNull()
    }

    @Test
    fun `non-Adreno GPU always returns null even for known apps`() {
        assertThat(provider.hintFor("dev.eden.eden_emulator", mali())).isNull()
        assertThat(provider.hintFor("org.dolphinemu.dolphinemu", mali())).isNull()
        assertThat(provider.hintFor("dev.eden.eden_emulator", GpuInfo.UNKNOWN)).isNull()
    }

    @Test
    fun `hints are short single lines and qualified`() {
        assertThat(DriverHintProvider.HINT_TURNIP).doesNotContain("\n")
        assertThat(DriverHintProvider.HINT_QUALCOMM).doesNotContain("\n")
        assertThat(DriverHintProvider.HINT_TURNIP).contains("often")
        assertThat(DriverHintProvider.HINT_QUALCOMM).contains("usually")
    }
}
