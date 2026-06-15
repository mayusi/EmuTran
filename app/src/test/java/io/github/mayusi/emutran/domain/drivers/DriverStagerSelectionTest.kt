package io.github.mayusi.emutran.domain.drivers

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.emutran.data.device.GpuFamily
import io.github.mayusi.emutran.data.device.GpuInfo
import io.github.mayusi.emutran.data.source.GhAsset
import io.github.mayusi.emutran.data.source.GhRelease
import io.mockk.mockk
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Test

class DriverStagerSelectionTest {

    // DriverStager now requires an injected OkHttpClient and Json. The selection
    // tests are pure (no network, no JSON parsing), so a relaxed OkHttpClient
    // mock and a default Json instance are sufficient.
    private val stager = DriverStager(mockk<OkHttpClient>(relaxed = true), Json)

    /** Releases modeled on the real K11MCH1/AdrenoToolsDrivers API,
     * newest-first. */
    private val releases = listOf(
        release("v840", "Qualcomm_840_adpkg.zip"),
        release("v837", "Qualcomm_837_adpkg.zip"),
        release(
            "v26.0.0-rc08",
            "turnip_a8xx.zip",
            "Turnip_v26.0.0_R8.zip",
            "Turnip_v26.0.0_R8_Gmem.zip",
            "Turnip_v26.0.0_R8_Sysmem.zip",
        ),
        release(
            "v26.0.0-rc07",
            "Turnip_v26.0.0_R7.zip",
            "Turnip_v26.0.0_R7_Gmem.zip",
        ),
        release("v849", "Qualcomm_849_adpkg.zip"),
        release("v842.6", "8Elite2-842.6.zip"),
    )

    private fun release(tag: String, vararg assets: String) =
        GhRelease(
            tagName = tag,
            name = tag,
            assets = assets.map { GhAsset(name = it, size = 1, browserDownloadUrl = "https://x/$it") },
        )

    private fun adreno(model: Int?, renderer: String = "Adreno (TM) ${model ?: ""}") =
        GpuInfo("Qualcomm", renderer, "OpenGL ES 3.2", GpuFamily.ADRENO, model)

    @Test
    fun `7xx selects highest Qualcomm 840 plus latest turnip set`() {
        val sel = stager.selectAssetsForGpu(releases, adreno(740))
        val names = sel.assets.map { it.name }
        assertThat(names).contains("Qualcomm_840_adpkg.zip")
        assertThat(names).doesNotContain("Qualcomm_837_adpkg.zip")
        assertThat(names).doesNotContain("Qualcomm_849_adpkg.zip")
        // newest turnip set (rc08), including its series extra
        assertThat(names).contains("Turnip_v26.0.0_R8.zip")
        assertThat(names).contains("Turnip_v26.0.0_R8_Gmem.zip")
        assertThat(names).contains("turnip_a8xx.zip")
        assertThat(names).doesNotContain("Turnip_v26.0.0_R7.zip")
        // tag points at the GPU-specific Qualcomm release
        assertThat(sel.releaseTag).isEqualTo("v840")
    }

    @Test
    fun `8xx selects Qualcomm 849 plus latest turnip set`() {
        val sel = stager.selectAssetsForGpu(releases, adreno(840))
        val names = sel.assets.map { it.name }
        assertThat(names).contains("Qualcomm_849_adpkg.zip")
        assertThat(names).doesNotContain("Qualcomm_840_adpkg.zip")
        assertThat(names).contains("Turnip_v26.0.0_R8.zip")
        assertThat(sel.releaseTag).isEqualTo("v849")
    }

    @Test
    fun `8 Elite renderer selects 8Elite2 build`() {
        val sel = stager.selectAssetsForGpu(
            releases,
            adreno(840, renderer = "Adreno (TM) 840 / Snapdragon 8 Elite 2"),
        )
        val names = sel.assets.map { it.name }
        assertThat(names).contains("8Elite2-842.6.zip")
        assertThat(names).doesNotContain("Qualcomm_849_adpkg.zip")
        assertThat(sel.releaseTag).isEqualTo("v842.6")
    }

    @Test
    fun `unknown model falls back to latest universal turnip only`() {
        val sel = stager.selectAssetsForGpu(releases, adreno(null, renderer = "Adreno"))
        val names = sel.assets.map { it.name }
        assertThat(names.none { it.startsWith("Qualcomm") }).isTrue()
        assertThat(names.none { it.startsWith("8Elite2") }).isTrue()
        assertThat(names).contains("Turnip_v26.0.0_R8.zip")
        assertThat(sel.releaseTag).isEqualTo("v26.0.0-rc08")
    }

    @Test
    fun `series with no qualcomm match still returns turnip set`() {
        // Releases with only turnip, GPU is 8xx (849 absent).
        val turnipOnly = listOf(
            release("v26.0.0-rc08", "Turnip_v26.0.0_R8.zip", "Turnip_v26.0.0_R8_Gmem.zip"),
        )
        val sel = stager.selectAssetsForGpu(turnipOnly, adreno(840))
        val names = sel.assets.map { it.name }
        assertThat(names.none { it.startsWith("Qualcomm") }).isTrue()
        assertThat(names).contains("Turnip_v26.0.0_R8.zip")
    }

    @Test
    fun `nothing found returns empty selection`() {
        val sel = stager.selectAssetsForGpu(emptyList(), adreno(740))
        assertThat(sel.assets).isEmpty()
    }
}
